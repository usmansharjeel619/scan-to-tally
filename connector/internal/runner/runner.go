// Package runner drains the job queue into Tally.
//
// It is the only place that decides whether a voucher gets posted, and it
// enforces three things in a fixed order, because each one is cheaper and safer
// than the next:
//
//	1. Idempotency  -- has this session already reached Tally? Never post twice.
//	2. Pre-flight   -- does the live stock actually support this movement?
//	3. Import       -- only now do we change anything.
//
// There is exactly ONE worker. Tally's gateway is serial; concurrency here buys
// nothing and risks partially applied vouchers.
package runner

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"strings"
	"time"

	"github.com/acme/scan-to-tally/connector/internal/protocol"
	"github.com/acme/scan-to-tally/connector/internal/store"
	"github.com/acme/scan-to-tally/connector/internal/tally"
)

// Reporter receives the outcome of every job, for forwarding to the relay.
type Reporter interface {
	Report(ctx context.Context, res protocol.JobResult) error
}

// Options tune the retry curve.
type Options struct {
	// PollInterval is how often the queue is checked when it is empty.
	PollInterval time.Duration
	// BaseBackoff is the first retry delay; it doubles up to MaxBackoff.
	BaseBackoff time.Duration
	MaxBackoff  time.Duration
	// MaxAttempts bounds transient retries. A laptop that has been shut for a
	// week should eventually surface rather than retry silently forever.
	MaxAttempts int
}

func (o Options) withDefaults() Options {
	if o.PollInterval == 0 {
		o.PollInterval = 3 * time.Second
	}
	if o.BaseBackoff == 0 {
		o.BaseBackoff = 5 * time.Second
	}
	if o.MaxBackoff == 0 {
		o.MaxBackoff = 5 * time.Minute
	}
	if o.MaxAttempts == 0 {
		o.MaxAttempts = 200 // ~17h at the 5-minute ceiling
	}
	return o
}

type Runner struct {
	st  *store.Store
	tc  *tally.Client
	rep Reporter
	log *slog.Logger
	opt Options
}

func New(st *store.Store, tc *tally.Client, rep Reporter, log *slog.Logger, opt Options) *Runner {
	if log == nil {
		log = slog.Default()
	}
	return &Runner{st: st, tc: tc, rep: rep, log: log, opt: opt.withDefaults()}
}

// Run drains the queue until ctx is cancelled.
func (r *Runner) Run(ctx context.Context) {
	// A job left 'running' means we died mid-import and cannot know whether the
	// voucher landed. Requeueing is safe only because processOne checks
	// AlreadyPosted first, so one that did land is recognised, not repeated.
	if n, err := r.st.RequeueStuck(ctx); err != nil {
		r.log.Error("requeue stuck jobs", "err", err)
	} else if n > 0 {
		r.log.Warn("requeued jobs interrupted by a restart", "count", n)
	}

	t := time.NewTicker(r.opt.PollInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			for {
				worked, err := r.step(ctx)
				if err != nil {
					r.log.Error("job step failed", "err", err)
					break
				}
				if !worked {
					break // queue drained
				}
			}
		}
	}
}

func (r *Runner) step(ctx context.Context) (bool, error) {
	job, err := r.st.NextDue(ctx, time.Now())
	if err != nil || job == nil {
		return false, err
	}
	r.processOne(ctx, *job)
	return true, nil
}

func (r *Runner) processOne(ctx context.Context, job store.Job) {
	log := r.log.With("job", job.ID, "session", job.SessionID, "attempt", job.Attempts+1)

	var pj protocol.PostVoucherJob
	if err := json.Unmarshal(job.Payload, &pj); err != nil {
		r.fail(ctx, job, "BAD_PAYLOAD", "Job payload could not be read: "+err.Error())
		return
	}

	hash := store.HashPayload(job.Payload)

	// --- 1. idempotency ------------------------------------------------------
	// This is checked before EVERY import, which is what makes requeueing a
	// crashed job safe.
	posted, err := r.st.AlreadyPosted(ctx, pj.SessionID, hash)
	if errors.Is(err, store.ErrConflictingRetry) {
		// Same session id, different content. Never a network retry -- a bug
		// upstream. Refusing is the only safe move.
		log.Error("conflicting retry refused", "err", err)
		r.fail(ctx, job, "CONFLICTING_RETRY", err.Error())
		return
	}
	if err != nil {
		r.retry(ctx, job, "STORE_ERROR", err.Error())
		return
	}
	if posted != nil {
		log.Info("already posted; returning stored result without touching Tally",
			"voucher", posted.TallyVchID)
		_ = r.st.CompleteJob(ctx, job.ID)
		r.report(ctx, protocol.JobResult{
			SessionID: pj.SessionID, JobID: job.ID, OK: true,
			TallyVoucherID: posted.TallyVchID, Duplicate: true,
			Attempts: job.Attempts + 1, CompletedAt: time.Now(),
		})
		return
	}

	// --- 2. pre-flight -------------------------------------------------------
	if err := r.preflight(ctx, pj); err != nil {
		if tally.IsTransient(err) {
			r.retry(ctx, job, "PREFLIGHT", err.Error())
		} else {
			var te *tally.Error
			code := "PREFLIGHT_FAILED"
			msg := err.Error()
			if errors.As(err, &te) {
				code, msg = te.Code, te.Message
			}
			r.fail(ctx, job, code, msg)
		}
		return
	}

	// --- 3. import -----------------------------------------------------------
	voucher, err := buildVoucher(pj)
	if err != nil {
		r.fail(ctx, job, "INVALID_VOUCHER", err.Error())
		return
	}

	res, err := r.tc.Import(ctx, voucher)
	if err != nil {
		var te *tally.Error
		if errors.As(err, &te) && te.Class == tally.Business {
			log.Warn("business error; not retrying", "code", te.Code, "msg", te.Message)
			r.fail(ctx, job, te.Code, te.Message)
			return
		}
		log.Warn("transient error; will retry", "err", err)
		r.retry(ctx, job, "TALLY", err.Error())
		return
	}

	// Record BEFORE acknowledging anything. A crash between Tally confirming
	// and this write leaves a voucher we do not know about, which is what
	// Reconcile repairs on the next start.
	if err := r.st.RecordPosted(ctx, store.Posted{
		SessionID: pj.SessionID, Reference: voucher.Reference,
		VoucherType: string(voucher.Type), TallyVchID: res.LastVchID,
		Company: r.tc.Company(), PostedAt: time.Now(), PayloadHash: hash,
	}); err != nil {
		// The voucher IS in Tally. Losing this record risks a duplicate later,
		// so shout about it.
		log.Error("POSTED BUT NOT RECORDED -- duplicate risk", "voucher", res.LastVchID, "err", err)
	}

	_ = r.st.CompleteJob(ctx, job.ID)
	log.Info("posted", "voucher", res.LastVchID, "type", voucher.Type)
	r.report(ctx, protocol.JobResult{
		SessionID: pj.SessionID, JobID: job.ID, OK: true,
		TallyVoucherID: res.LastVchID, Attempts: job.Attempts + 1, CompletedAt: time.Now(),
	})
}

// preflight re-checks against LIVE Tally what the device could only check
// against a cached copy.
//
// The device's batch balances are minutes old. Between the scan and the post,
// somebody may have invoiced that stock in Tally directly. Catching it here
// turns a confusing partial failure into a clean business error.
func (r *Runner) preflight(ctx context.Context, pj protocol.PostVoucherJob) error {
	if pj.Kind != protocol.KindOutgoing {
		return nil
	}

	balances, err := r.tc.ListBatchBalances(ctx)
	if err != nil {
		return err // transient: retry rather than reject the operator's work
	}

	avail := map[string]float64{}
	for _, b := range balances {
		avail[balKey(b.StockItemName, b.BatchName, b.GodownName)] += b.ClosingQty
	}

	for _, line := range pj.Lines {
		for _, box := range line.Boxes {
			k := balKey(line.StockItemName, box.BoxSerial, pj.Godown)
			have := avail[k]
			if box.Qty-have > 0.0001 {
				return &tally.Error{
					Class: tally.Business,
					Code:  "INSUFFICIENT_STOCK",
					Message: fmt.Sprintf(
						"Box %s of %s has %s left in %s, but %s was entered. "+
							"The stock moved after the scan was taken.",
						box.BoxSerial, line.StockItemName,
						trimF(have), pj.Godown, trimF(box.Qty)),
				}
			}
			avail[k] = have - box.Qty // a second line off the same box must also fit
		}
	}
	return nil
}

func balKey(item, batch, godown string) string {
	return strings.Join([]string{item, batch, godown}, "\x1f")
}

func trimF(f float64) string {
	if f == math.Trunc(f) {
		return fmt.Sprintf("%d", int64(f))
	}
	return fmt.Sprintf("%g", f)
}

// buildVoucher turns a job into a Tally voucher.
//
// The relay has already aggregated scans into one line per part number;
// tally.Voucher.Validate asserts it again, because splitting an item across
// entries is accepted by Tally and quietly ruins its stock reports.
func buildVoucher(pj protocol.PostVoucherJob) (tally.Voucher, error) {
	entries := make([]tally.InventoryEntry, 0, len(pj.Lines))
	for _, line := range pj.Lines {
		batches := make([]tally.BatchAllocation, 0, len(line.Boxes))
		for _, box := range line.Boxes {
			batches = append(batches, tally.BatchAllocation{
				BatchName:  box.BoxSerial,
				GodownName: pj.Godown,
				Qty:        tally.Qty{Value: box.Qty, Unit: line.Unit},
				MfgDate:    box.MfgDate.AsTime(),
			})
		}
		entries = append(entries, tally.InventoryEntry{
			StockItemName: line.StockItemName,
			Qty:           tally.Qty{Value: line.TotalQty(), Unit: line.Unit},
			Description:   line.Description,
			Batches:       batches,
		})
	}

	narration := pj.Narration
	if narration == "" {
		narration = fmt.Sprintf("Mobile scan | operator %s | session %s", pj.Operator, pj.SessionID)
	}

	switch pj.Kind {
	case protocol.KindIncoming:
		v := tally.NewReceiptNote(pj.SessionID, narration, pj.Party, pj.Date, entries)
		if pj.VoucherType != "" {
			v.Type = tally.VoucherType(pj.VoucherType)
		}
		return v, v.Validate()
	case protocol.KindOutgoing:
		v := tally.NewDeliveryNote(pj.SessionID, narration, pj.Party, pj.SalesOrder, pj.Date, entries)
		if pj.VoucherType != "" {
			v.Type = tally.VoucherType(pj.VoucherType)
		}
		return v, v.Validate()
	default:
		return tally.Voucher{}, fmt.Errorf("unknown job kind %q", pj.Kind)
	}
}

// --- outcome handling -------------------------------------------------------

func (r *Runner) retry(ctx context.Context, job store.Job, code, msg string) {
	attempts := job.Attempts + 1
	if attempts >= r.opt.MaxAttempts {
		r.log.Error("giving up after repeated transient failures",
			"job", job.ID, "attempts", attempts)
		r.fail(ctx, job, "RETRY_EXHAUSTED",
			fmt.Sprintf("Still failing after %d attempts. Last error: %s", attempts, msg))
		return
	}

	backoff := time.Duration(float64(r.opt.BaseBackoff) * math.Pow(2, float64(min(attempts-1, 10))))
	if backoff > r.opt.MaxBackoff {
		backoff = r.opt.MaxBackoff
	}
	if err := r.st.RetryJob(ctx, job.ID, attempts, code+": "+msg, backoff); err != nil {
		r.log.Error("could not requeue job", "job", job.ID, "err", err)
	}
	// Deliberately not reported upstream: a transient failure is a status-bar
	// condition, not an event the operator should be told about.
}

func (r *Runner) fail(ctx context.Context, job store.Job, code, msg string) {
	attempts := job.Attempts + 1
	if err := r.st.FailJob(ctx, job.ID, attempts, msg, "BUSINESS"); err != nil {
		r.log.Error("could not fail job", "job", job.ID, "err", err)
	}
	var sessionID string
	var pj protocol.PostVoucherJob
	if json.Unmarshal(job.Payload, &pj) == nil {
		sessionID = pj.SessionID
	}
	r.report(ctx, protocol.JobResult{
		SessionID: sessionID, JobID: job.ID, OK: false,
		ErrorClass: "BUSINESS", ErrorCode: code, ErrorMessage: msg,
		Attempts: attempts, CompletedAt: time.Now(),
	})
}

func (r *Runner) report(ctx context.Context, res protocol.JobResult) {
	if r.rep == nil {
		return
	}
	if err := r.rep.Report(ctx, res); err != nil {
		// The relay will re-ask on reconnect; the durable truth is in SQLite.
		r.log.Warn("could not report result upstream", "session", res.SessionID, "err", err)
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
