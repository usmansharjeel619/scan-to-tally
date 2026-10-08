// Package runner drains the job queue into Tally.
//
// It is the only place that decides whether a voucher gets posted, and it
// enforces three things in a fixed order, because each one is cheaper and safer
// than the next:
//
//  1. Idempotency  -- has this session already reached Tally? Never post twice.
//  2. Pre-flight   -- does the live stock actually support this movement?
//  3. Import       -- only now do we change anything.
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
		// Same identity, different content. For a CREATE that is always a bug
		// upstream, and refusing is the only safe move -- posting it would put
		// a second, different voucher into the books under one identity.
		//
		// An ALTER is a different thing entirely. It names the voucher it
		// replaces and states everything that voucher should hold, so sending
		// it twice leaves Tally in exactly the same place as sending it once.
		// The content is EXPECTED to grow between attempts: another receipt of
		// the same product lands in the meantime and its boxes join the set.
		// Refusing that stranded the receipt for a danger that does not exist.
		if !pj.Alter {
			log.Error("conflicting retry refused", "err", err)
			r.fail(ctx, job, "CONFLICTING_RETRY", err.Error())
			return
		}
		log.Info("content changed since the last attempt; safe for an alter",
			"voucher", pj.AlterMasterID)
		// Both cleared, or the next check treats a handled condition as a
		// store failure and retries this job for ever without ever sending it.
		posted, err = nil, nil
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

	// Verify the target in Tally before selecting it by master ID.
	if pj.Alter {
		id, err := r.resolveAlterTarget(ctx, pj)
		if err != nil {
			if tally.IsTransient(err) {
				r.retry(ctx, job, "RESOLVE_TARGET", err.Error())
			} else {
				var te *tally.Error
				code, msg := "ALTER_TARGET_MISSING", err.Error()
				if errors.As(err, &te) {
					code, msg = te.Code, te.Message
				}
				r.fail(ctx, job, code, msg)
			}
			return
		}
		// A box removed in Tally must never be reintroduced from relay cache.
		if len(pj.RetainedBoxes) > 0 {
			history, historyErr := r.tc.ListVoucherHistory(ctx)
			if historyErr != nil {
				r.retry(ctx, job, "HISTORY_CHECK", historyErr.Error())
				return
			}
			if !retainedBoxesPresent(history, pj, id.MasterID) {
				r.fail(ctx, job, "TALLY_HISTORY_CHANGED", "This voucher changed in Tally. Wait for history sync, then retry this receipt; deleted boxes will not be recreated.")
				return
			}
		}
		voucher.AlterMasterID = id.MasterID
		voucher.AlterDate, err = time.Parse("20060102", id.Date)
		if err != nil {
			r.fail(ctx, job, "ALTER_TARGET_INVALID", "Tally returned an invalid voucher date")
			return
		}
		// Keep the original date: the relay retains it to find this standing
		// voucher on future receipts, including receipts on later days.
		voucher.Date = voucher.AlterDate
		log.Info("altering verified voucher", "masterId", id.MasterID)
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
		SessionID: pj.SessionID, Reference: voucher.IdempotencyKey,
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

// markerKey pulls the session key out of a "[STT:...]" narration marker.
// Only the target voucher/product/godown can prove retained boxes still exist.
func retainedBoxesPresent(history []tally.VoucherHistory, pj protocol.PostVoucherJob, masterID string) bool {
	present := map[string]bool{}
	for _, v := range history {
		if v.MasterID != masterID {
			continue
		}
		for _, b := range v.Batches {
			if strings.EqualFold(strings.TrimSpace(b.Godown), strings.TrimSpace(pj.Godown)) && len(pj.Lines) > 0 && b.Item == pj.Lines[0].StockItemName {
				present[b.Box] = true
			}
		}
	}
	for _, box := range pj.RetainedBoxes {
		if !present[box] {
			return false
		}
	}
	return true
}

func markerKey(marker string) string {
	m := strings.TrimSpace(marker)
	prefix := "[" + tally.IdempotencyMarker + ":"
	if !strings.HasPrefix(m, prefix) || !strings.HasSuffix(m, "]") {
		return ""
	}
	return m[len(prefix) : len(m)-1]
}

// resolveAlterTarget verifies identity and ownership before replacing a voucher.
func (r *Runner) resolveAlterTarget(ctx context.Context, pj protocol.PostVoucherJob) (tally.VoucherIdentity, error) {
	if pj.AlterMasterID == "" {
		return tally.VoucherIdentity{}, tally.NewBusiness("ALTER_TARGET_MISSING",
			"There is no record of which voucher to add to.")
	}

	day := pj.AlterDate
	if day.IsZero() {
		day = time.Now()
	}
	// A day either side: the connector's clock and Tally's need not agree, and
	// a voucher posted near midnight would otherwise be invisible.
	ids, err := r.tc.ListVoucherIdentities(ctx, day.AddDate(0, 0, -1), day.AddDate(0, 0, 1))
	if err != nil {
		return tally.VoucherIdentity{}, err
	}

	for _, id := range ids {
		if id.MasterID != pj.AlterMasterID {
			continue
		}
		if markerKey(pj.AlterMarker) == "" || !strings.Contains(id.Narration, pj.AlterMarker) || id.VoucherType != string(tally.PhysicalStock) {
			return tally.VoucherIdentity{}, tally.NewBusiness("ALTER_TARGET_NOT_OURS", fmt.Sprintf(
				"Voucher %s is not the one this receipt created, so it will not be "+
					"replaced. A new voucher will be raised instead.", pj.AlterMasterID))
		}
		return id, nil
	}

	return tally.VoucherIdentity{}, tally.NewBusiness("ALTER_TARGET_MISSING", fmt.Sprintf(
		"Voucher %s is not in Tally any more, so there was nothing to add to. "+
			"A new voucher will be raised for this product instead.", pj.AlterMasterID))
}

// preflight re-checks against LIVE Tally what the device could only check
// against a cached copy.
//
// The device's batch balances are minutes old. Between the scan and the post,
// somebody may have invoiced that stock in Tally directly. Catching it here
// turns a confusing partial failure into a clean business error.
func (r *Runner) preflight(ctx context.Context, pj protocol.PostVoucherJob) error {
	// A voucher is posted into the company THIS connector is configured for,
	// and every job says which company it was raised against. Normally they
	// agree. When they do not, somebody has moved this connector to a different
	// company -- or a handset is still working from an older one -- and posting
	// anyway would file a warehouse's count into another company's books.
	//
	// Nothing about that is recoverable by looking at it later: the voucher
	// looks perfectly ordinary in the wrong ledger, and the right ledger simply
	// never hears about the delivery. So it is refused, permanently, and named.
	if mine := r.tc.Company(); pj.Company != "" && !strings.EqualFold(pj.Company, mine) {
		return &tally.Error{
			Class: tally.Business,
			Code:  "WRONG_COMPANY",
			Message: fmt.Sprintf(
				"This was counted against %q, but this PC posts into %q. "+
					"Nothing has been written. Check which company Tally is set "+
					"to before sending it again.",
				pj.Company, mine),
		}
	}

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
		// Incoming posts a PHYSICAL STOCK voucher, not a Receipt Note.
		//
		// A Receipt Note is a goods-inward document that an accounts team is
		// expected to settle against a Purchase invoice later. The warehouse is
		// not doing that: it is recording what is physically on the shelf. A
		// Physical Stock voucher says exactly that and carries no accounting
		// weight and no party.
		//
		// Physical Stock SETS a batch quantity rather than adding to it. That is
		// equivalent here only because a box is its own batch and a box is
		// received once -- if that ever stops being true, this is the line that
		// makes a second receipt overwrite the first instead of adding to it.
		v := tally.NewPhysicalStock(pj.SessionID, narration, pj.Date, entries)
		// One voucher per product, added to rather than repeated. The relay has
		// sent every box the voucher must end up holding, so replacing it
		// wholesale is what is wanted.
		v.Alter = pj.Alter

		// A STANDING VOUCHER KEEPS ITS ORIGINAL MARKER.
		//
		// Tally replaces the narration along with everything else, so writing
		// this receipt's marker would overwrite the one that identifies the
		// voucher -- and the NEXT receipt, checking for the marker it recorded,
		// would find a stranger's voucher and refuse to touch it. The whole
		// merge then stops after exactly one carton. Caught end to end: "session
		// did not recover, stuck in FAILED".
		if pj.Alter {
			if key := markerKey(pj.AlterMarker); key != "" {
				v.IdempotencyKey = key
			}
		}
		if pj.VoucherType != "" {
			v.Type = tally.VoucherType(pj.VoucherType)
			// Only a party-bearing type should carry the supplier; Physical
			// Stock has no party field at all.
			if v.Type != tally.PhysicalStock {
				v.PartyLedgerName = pj.Party
			}
		}
		return v, v.Validate()
	case protocol.KindOutgoing:
		v := tally.NewDeliveryNote(pj.SessionID, narration, pj.Party, pj.SalesOrder, pj.Date, entries)
		if pj.VoucherType != "" {
			v.Type = tally.VoucherType(pj.VoucherType)
		}
		return v, v.Validate()
	case protocol.KindStockCheck:
		v := tally.NewPhysicalStock(pj.SessionID, narration, pj.Date, entries)
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
