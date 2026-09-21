package runner

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/acme/scan-to-tally/connector/internal/protocol"
	"github.com/acme/scan-to-tally/connector/internal/store"
	"github.com/acme/scan-to-tally/connector/internal/tally"
)

// These run against tools/tallysim:
//
//	./scripts/test-all.sh
//
// The duplicate-voucher tests are the reason this package exists. Warehouse
// Wi-Fi drops mid-post, the app retries, and without the idempotency ledger
// Tally ends up holding two Receipt Notes for one pallet.

type capture struct {
	mu      sync.Mutex
	results []protocol.JobResult
}

func (c *capture) Report(_ context.Context, res protocol.JobResult) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.results = append(c.results, res)
	return nil
}

func (c *capture) last() (protocol.JobResult, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.results) == 0 {
		return protocol.JobResult{}, false
	}
	return c.results[len(c.results)-1], true
}

func simURL(t *testing.T) string {
	t.Helper()
	u := os.Getenv("STT_TALLY_URL")
	if u == "" {
		t.Skip("set STT_TALLY_URL to run runner tests against tallysim")
	}
	return u
}

func setFault(t *testing.T, mode string) {
	t.Helper()
	resp, err := http.Get(simURL(t) + "/_sim/fault?mode=" + mode)
	if err != nil {
		t.Fatalf("fault %s: %v", mode, err)
	}
	resp.Body.Close()
}

// simVoucherCount counts vouchers the simulator has actually created. This is
// the ground truth these tests assert against.
func simVoucherCount(t *testing.T) int {
	t.Helper()
	resp, err := http.Get(simURL(t) + "/_sim/state")
	if err != nil {
		t.Fatalf("state: %v", err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	n := 0
	for _, line := range strings.Split(string(b), "\n") {
		if strings.Contains(line, "ref=") {
			n++
		}
	}
	return n
}

func newHarness(t *testing.T) (*Runner, *store.Store, *capture) {
	t.Helper()
	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "connector.db"))
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { st.Close() })

	tc := tally.NewClient(tally.Config{
		BaseURL: simURL(t),
		Company: "ACME FIRE SYSTEMS",
		Timeout: 10 * time.Second,
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))

	cap := &capture{}
	r := New(st, tc, cap, slog.New(slog.NewTextHandler(io.Discard, nil)),
		Options{BaseBackoff: 10 * time.Millisecond, MaxBackoff: 20 * time.Millisecond})
	return r, st, cap
}

func incomingJob(sessionID string, boxes ...protocol.Box) protocol.PostVoucherJob {
	return protocol.PostVoucherJob{
		SessionID: sessionID,
		Kind:      protocol.KindIncoming,
		Company:   "ACME FIRE SYSTEMS",
		Godown:    "Main Store",
		Party:     "Simplex Supplies",
		Date:      time.Now(),
		Operator:  "RK",
		Lines: []protocol.Line{{
			StockItemName: "4098-9792 SSD SENSOR BASE",
			Unit:          "Nos",
			Boxes:         boxes,
		}},
	}
}

func enqueue(t *testing.T, st *store.Store, id string, pj protocol.PostVoucherJob) store.Job {
	t.Helper()
	payload, _ := json.Marshal(pj)
	j := store.Job{ID: id, SessionID: pj.SessionID, Kind: string(pj.Kind), Payload: payload}
	if err := st.Enqueue(context.Background(), j); err != nil {
		t.Fatalf("enqueue: %v", err)
	}
	return j
}

func drain(t *testing.T, r *Runner) {
	t.Helper()
	for i := 0; i < 20; i++ {
		worked, err := r.step(context.Background())
		if err != nil {
			t.Fatalf("step: %v", err)
		}
		if !worked {
			return
		}
	}
	t.Fatal("queue did not drain")
}

// TestRetryDoesNotDoublePost is THE test. A redelivered job for a session that
// already reached Tally must return the stored result and never touch Tally.
func TestRetryDoesNotDoublePost(t *testing.T) {
	setFault(t, "none")
	r, st, cap := newHarness(t)
	ctx := context.Background()

	sess := "sess-nodouble-" + time.Now().Format("150405.000")
	pj := incomingJob(sess, protocol.Box{BoxSerial: "1124249911110001", Qty: 18})

	before := simVoucherCount(t)

	enqueue(t, st, "job-1", pj)
	drain(t, r)

	res, _ := cap.last()
	if !res.OK || res.Duplicate {
		t.Fatalf("first post should succeed and not be a duplicate: %+v", res)
	}
	firstVoucher := res.TallyVoucherID
	if got := simVoucherCount(t); got != before+1 {
		t.Fatalf("voucher count = %d, want %d", got, before+1)
	}

	// The relay redelivers the same session after a reconnect, under a NEW job
	// id. The queue's unique index on session_id cannot help here -- only the
	// idempotency ledger can -- so drive processOne directly.
	payload, _ := json.Marshal(pj)
	r.processOne(ctx, store.Job{
		ID: "job-2-redelivered", SessionID: sess,
		Kind: string(pj.Kind), Payload: payload,
	})

	res2, _ := cap.last()
	if !res2.OK {
		t.Fatalf("redelivery should succeed: %+v", res2)
	}
	if !res2.Duplicate {
		t.Error("redelivery must be reported as a duplicate")
	}
	if res2.TallyVoucherID != firstVoucher {
		t.Errorf("duplicate returned voucher %s, want the original %s",
			res2.TallyVoucherID, firstVoucher)
	}
	if got := simVoucherCount(t); got != before+1 {
		t.Fatalf("SECOND VOUCHER CREATED: count = %d, want %d", got, before+1)
	}
}

// TestCrashMidImportIsSafeToRequeue covers the nastiest case: the connector
// died after Tally created the voucher but before the job was marked done.
func TestCrashMidImportIsSafeToRequeue(t *testing.T) {
	setFault(t, "none")
	r, st, _ := newHarness(t)
	ctx := context.Background()

	sess := "sess-crash-" + time.Now().Format("150405.000")
	pj := incomingJob(sess, protocol.Box{BoxSerial: "1124249922220001", Qty: 6})

	before := simVoucherCount(t)
	enqueue(t, st, "job-crash", pj)
	drain(t, r)
	if got := simVoucherCount(t); got != before+1 {
		t.Fatalf("setup failed: count = %d, want %d", got, before+1)
	}

	// Simulate the crash: put the job back as if it had been mid-flight.
	payload, _ := json.Marshal(pj)
	if err := st.Enqueue(ctx, store.Job{
		ID: "job-crash-2", SessionID: sess, Kind: string(pj.Kind), Payload: payload,
	}); err != nil {
		t.Fatalf("enqueue: %v", err)
	}
	if _, err := st.RequeueStuck(ctx); err != nil {
		t.Fatalf("requeue: %v", err)
	}
	r.processOne(ctx, store.Job{ID: "job-crash-2", SessionID: sess,
		Kind: string(pj.Kind), Payload: payload})

	if got := simVoucherCount(t); got != before+1 {
		t.Fatalf("requeue after crash created a duplicate: count = %d, want %d", got, before+1)
	}
}

// TestConflictingRetryIsRefused: same session id, different contents. That is
// never a network retry -- it is a bug, and posting it would create a second,
// DIFFERENT voucher under one identity.
func TestConflictingRetryIsRefused(t *testing.T) {
	setFault(t, "none")
	r, st, cap := newHarness(t)
	ctx := context.Background()

	sess := "sess-conflict-" + time.Now().Format("150405.000")
	pj := incomingJob(sess, protocol.Box{BoxSerial: "1124249933330001", Qty: 4})
	enqueue(t, st, "job-c1", pj)
	drain(t, r)

	before := simVoucherCount(t)

	// Same session, different quantity.
	pj2 := incomingJob(sess, protocol.Box{BoxSerial: "1124249933330001", Qty: 99})
	payload2, _ := json.Marshal(pj2)
	r.processOne(ctx, store.Job{ID: "job-c2", SessionID: sess,
		Kind: string(pj2.Kind), Payload: payload2})

	res, _ := cap.last()
	if res.OK {
		t.Fatal("a conflicting retry must not be accepted")
	}
	if res.ErrorCode != "CONFLICTING_RETRY" {
		t.Errorf("errorCode = %q, want CONFLICTING_RETRY", res.ErrorCode)
	}
	if got := simVoucherCount(t); got != before {
		t.Errorf("conflicting retry posted anyway: count = %d, want %d", got, before)
	}
}

// TestBusinessErrorStopsRetrying: over-shipping cannot be fixed by waiting, so
// it must go straight to a human instead of looping in the queue.
func TestBusinessErrorStopsRetrying(t *testing.T) {
	setFault(t, "none")
	r, st, cap := newHarness(t)
	ctx := context.Background()

	pj := protocol.PostVoucherJob{
		SessionID:  "sess-over-" + time.Now().Format("150405.000"),
		Kind:       protocol.KindOutgoing,
		Company:    "ACME FIRE SYSTEMS",
		Godown:     "Main Store",
		Party:      "Example Project FZC",
		SalesOrder: "SO-2026-0041",
		Date:       time.Now(),
		Lines: []protocol.Line{{
			StockItemName: "4098-9792 SSD SENSOR BASE",
			Unit:          "Nos",
			// The box label says 18; only 13 remain.
			Boxes: []protocol.Box{{BoxSerial: "1124241658336425", Qty: 18}},
		}},
	}
	enqueue(t, st, "job-over", pj)
	drain(t, r)

	res, _ := cap.last()
	if res.OK {
		t.Fatal("over-ship must not succeed")
	}
	if res.ErrorClass != "BUSINESS" {
		t.Errorf("errorClass = %q, want BUSINESS", res.ErrorClass)
	}
	// The pre-flight should have caught it before Tally was ever asked.
	if res.ErrorCode != "INSUFFICIENT_STOCK" {
		t.Logf("caught at Tally rather than pre-flight: %s / %s", res.ErrorCode, res.ErrorMessage)
	}

	counts, _ := st.Counts(ctx)
	if counts[store.JobFailed] != 1 {
		t.Errorf("failed jobs = %d, want 1", counts[store.JobFailed])
	}
	if counts[store.JobQueued] != 0 {
		t.Errorf("job was requeued; a business error must not retry")
	}
}

// TestTransientErrorRetriesQuietly: a closed company is the commonest real
// failure (employee shut Tally, laptop slept). It must stay in the queue and
// must NOT be reported to the operator as a failure.
func TestTransientErrorRetriesQuietly(t *testing.T) {
	setFault(t, "closed")
	defer setFault(t, "none")

	r, st, cap := newHarness(t)
	ctx := context.Background()

	pj := incomingJob("sess-closed-"+time.Now().Format("150405.000"),
		protocol.Box{BoxSerial: "1124249944440001", Qty: 3})
	enqueue(t, st, "job-closed", pj)

	if _, err := r.step(ctx); err != nil {
		t.Fatalf("step: %v", err)
	}

	counts, _ := st.Counts(ctx)
	if counts[store.JobQueued] != 1 {
		t.Errorf("queued = %d, want 1 (should still be waiting)", counts[store.JobQueued])
	}
	if counts[store.JobFailed] != 0 {
		t.Errorf("failed = %d, want 0 (a closed company is not a failure)", counts[store.JobFailed])
	}
	if _, ok := cap.last(); ok {
		t.Error("a transient failure must not be reported upstream")
	}

	// Reopen the company; the same job should now go through.
	setFault(t, "none")
	time.Sleep(60 * time.Millisecond) // clear the backoff
	drain(t, r)

	res, ok := cap.last()
	if !ok || !res.OK {
		t.Fatalf("job should succeed once the company reopens: %+v", res)
	}
}

// TestWrongCompanyIsRefusedAndPostsNothing is the guard against the worst
// silent failure this system can have.
//
// A receipt is raised on a handset against one company. Somebody then points
// this connector at a different one -- a second Northwind entity, a test company,
// last year's books. The voucher would post perfectly happily into whichever
// company the connector is configured for, look entirely ordinary there, and
// never appear in the company the warehouse actually counted against. Nobody
// would find it by looking at either ledger.
//
// So it must be refused, and refused PERMANENTLY: a retry cannot help, because
// the connector is not going to change its mind.
func TestWrongCompanyIsRefusedAndPostsNothing(t *testing.T) {
	setFault(t, "none")
	r, st, cap := newHarness(t)
	before := simVoucherCount(t)

	job := incomingJob("sess-wrong-co", protocol.Box{BoxSerial: "BX-1", Qty: 10})
	job.Company = "NORTHWIND TRADING LLC" // not the company this connector serves
	enqueue(t, st, "job-wrong-co", job)
	drain(t, r)

	res, ok := cap.last()
	if !ok {
		t.Fatal("no result reported")
	}
	if res.OK {
		t.Fatal("a voucher for another company was accepted")
	}
	if res.ErrorCode != "WRONG_COMPANY" {
		t.Fatalf("want WRONG_COMPANY, got %s: %s", res.ErrorCode, res.ErrorMessage)
	}
	// The message has to name BOTH companies: whoever reads it needs to know
	// which way round the mistake is before they can fix it.
	for _, want := range []string{"NORTHWIND TRADING LLC", "ACME FIRE SYSTEMS"} {
		if !strings.Contains(res.ErrorMessage, want) {
			t.Errorf("message does not name %q: %s", want, res.ErrorMessage)
		}
	}
	if got := simVoucherCount(t); got != before {
		t.Fatalf("Tally was written to: %d vouchers before, %d after", before, got)
	}
}

// A job that does not name a company at all is an older relay, not a mistake,
// and must keep working.
func TestMissingCompanyOnJobStillPosts(t *testing.T) {
	setFault(t, "none")
	r, st, cap := newHarness(t)

	job := incomingJob("sess-no-co", protocol.Box{BoxSerial: "BX-2", Qty: 4})
	job.Company = ""
	enqueue(t, st, "job-no-co", job)
	drain(t, r)

	res, ok := cap.last()
	if !ok || !res.OK {
		t.Fatalf("a job with no company should still post: %+v", res)
	}
}

// TestIncomingPostsPhysicalStock pins the voucher type for goods inward.
//
// The warehouse is recording what is physically on the shelf, not raising a
// goods-inward document for an accounts team to settle later. A Receipt Note
// says the second thing and leaves a document nobody closes.
func TestIncomingPostsPhysicalStock(t *testing.T) {
	pj := incomingJob("sess-vtype", protocol.Box{BoxSerial: "1124249955550001", Qty: 4})

	v, err := buildVoucher(pj)
	if err != nil {
		t.Fatalf("buildVoucher: %v", err)
	}
	if v.Type != tally.PhysicalStock {
		t.Errorf("incoming voucher type = %q, want %q", v.Type, tally.PhysicalStock)
	}
	// Physical Stock has no party field; setting one would be sent to Tally as
	// a field the voucher type does not have.
	if v.PartyLedgerName != "" {
		t.Errorf("party = %q, want empty on a Physical Stock voucher", v.PartyLedgerName)
	}
	if v.IdempotencyKey != pj.SessionID {
		t.Errorf("idempotency key = %q, want the session id", v.IdempotencyKey)
	}
}

// TestIncomingVoucherTypeStillOverridable: a company that wants a Receipt Note
// can still ask for one, and then the supplier is carried again.
func TestIncomingVoucherTypeStillOverridable(t *testing.T) {
	pj := incomingJob("sess-vtype-2", protocol.Box{BoxSerial: "1124249955550002", Qty: 4})
	pj.VoucherType = string(tally.ReceiptNote)
	pj.Party = "Simplex Supplies"

	v, err := buildVoucher(pj)
	if err != nil {
		t.Fatalf("buildVoucher: %v", err)
	}
	if v.Type != tally.ReceiptNote {
		t.Errorf("type = %q, want %q", v.Type, tally.ReceiptNote)
	}
	if v.PartyLedgerName != "Simplex Supplies" {
		t.Errorf("party = %q, want it carried on a party-bearing type", v.PartyLedgerName)
	}
}
