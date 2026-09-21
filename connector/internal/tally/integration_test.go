package tally

import (
	"context"
	"errors"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"
)

// These exercise the full request/response path against tools/tallysim.
//
// Run with:
//
//	./scripts/dev-tally.sh            # starts the simulator
//	STT_TALLY_URL=http://127.0.0.1:9000 go test ./internal/tally/ -run Integration -v
//
// They prove the plumbing -- aggregation, ceilings, error classification,
// health transitions. They do NOT prove our XML matches real Tally, because the
// simulator shares our assumptions. Only Phase 0 settles that.

const simCompany = "ACME FIRE SYSTEMS"

func simClient(t *testing.T) *Client {
	t.Helper()
	url := os.Getenv("STT_TALLY_URL")
	if url == "" {
		t.Skip("set STT_TALLY_URL to run integration tests against tallysim")
	}
	return NewClient(Config{
		BaseURL: url,
		Company: simCompany,
		Timeout: 10 * time.Second,
	}, nil)
}

func setFault(t *testing.T, mode string) {
	t.Helper()
	url := os.Getenv("STT_TALLY_URL")
	resp, err := http.Get(url + "/_sim/fault?mode=" + mode)
	if err != nil {
		t.Fatalf("set fault %s: %v", mode, err)
	}
	resp.Body.Close()
}

func nos(v float64) Qty { return Qty{Value: v, Unit: "Nos"} }

func balanceOf(t *testing.T, c *Client, item, batch string) float64 {
	t.Helper()
	bals, err := c.ListBatchBalances(context.Background())
	if err != nil {
		t.Fatalf("list balances: %v", err)
	}
	for _, b := range bals {
		if b.StockItemName == item && b.BatchName == batch {
			return b.ClosingQty
		}
	}
	return 0
}

func TestIntegrationMasterData(t *testing.T) {
	c := simClient(t)
	setFault(t, "none")
	ctx := context.Background()

	companies, err := c.ListCompanies(ctx)
	if err != nil {
		t.Fatalf("companies: %v", err)
	}
	if len(companies) == 0 || companies[0].Name != simCompany {
		t.Fatalf("companies = %+v", companies)
	}
	if c.Health() != HealthOnline {
		t.Errorf("health = %s, want ONLINE", c.Health())
	}

	items, err := c.ListStockItems(ctx)
	if err != nil {
		t.Fatalf("stock items: %v", err)
	}
	var found *StockItem
	for i := range items {
		if items[i].PartNo == "0677197CN" {
			found = &items[i]
		}
	}
	if found == nil {
		t.Fatal("did not find the item with part no 0677197CN")
	}
	if found.BaseUnits != "Nos" || !found.HasBatches {
		t.Errorf("item = %+v, want Nos and batch-wise", *found)
	}

	// An item without batch-wise details cannot carry a box serial. Surfacing
	// this at sync time is much cheaper than discovering it at post time.
	for _, it := range items {
		if it.Name == "MISC-CABLE-2C" && it.HasBatches {
			t.Error("MISC-CABLE-2C should not be batch-wise")
		}
	}

	orders, err := c.ListSalesOrders(ctx, time.Now().AddDate(0, -3, 0), time.Now())
	if err != nil {
		t.Fatalf("sales orders: %v", err)
	}
	if len(orders) < 2 {
		t.Fatalf("orders = %d, want >= 2", len(orders))
	}
	if orders[0].Lines[0].PendingQty() <= 0 {
		t.Error("expected outstanding quantity on the first order line")
	}
}

func TestIntegrationReceiptNoteAddsStock(t *testing.T) {
	c := simClient(t)
	setFault(t, "none")
	ctx := context.Background()

	const item = "4098-9792 SSD SENSOR BASE"
	const boxA = "1124249900000001"
	const boxB = "1124249900000002"

	before := balanceOf(t, c, item, boxA)

	// Two boxes of the SAME part number: one inventory entry, two batch
	// allocations. Emitting two entries instead is the mistake this asserts
	// against, because Tally accepts it and the reports come out wrong.
	v := NewReceiptNote("WH-IN-TEST-0001", "integration test", "Simplex Supplies", time.Now(),
		[]InventoryEntry{{
			StockItemName: item,
			Qty:           nos(36),
			Batches: []BatchAllocation{
				{BatchName: boxA, GodownName: "Main Store", Qty: nos(18)},
				{BatchName: boxB, GodownName: "Main Store", Qty: nos(18)},
			},
		}})

	res, err := c.Import(ctx, v)
	if err != nil {
		t.Fatalf("import: %v", err)
	}
	if res.Created != 1 {
		t.Fatalf("created = %d, want 1", res.Created)
	}

	if got := balanceOf(t, c, item, boxA); got != before+18 {
		t.Errorf("box A balance = %v, want %v", got, before+18)
	}
	if got := balanceOf(t, c, item, boxB); got != 18 {
		t.Errorf("box B balance = %v, want 18", got)
	}
}

func TestIntegrationDeliveryNoteRemovesStock(t *testing.T) {
	c := simClient(t)
	setFault(t, "none")
	ctx := context.Background()

	const item = "4090-9001 ADDRESSABLE HEAT DETECTOR"
	const box = "0701240099887766"

	before := balanceOf(t, c, item, box)
	if before < 5 {
		t.Skipf("not enough seeded stock (%v) to run this", before)
	}

	v := NewDeliveryNote("WH-OUT-TEST-0001", "integration test", "Example Project FZC",
		"SO-2026-0041", time.Now(),
		[]InventoryEntry{{
			StockItemName: item,
			Qty:           nos(5),
			Batches:       []BatchAllocation{{BatchName: box, GodownName: "Main Store", Qty: nos(5)}},
		}})

	if _, err := c.Import(ctx, v); err != nil {
		t.Fatalf("import: %v", err)
	}
	if got := balanceOf(t, c, item, box); got != before-5 {
		t.Errorf("balance = %v, want %v", got, before-5)
	}
}

// TestIntegrationOverShipIsBusinessError is the ceiling that matters: the box
// label says 18 but only 13 remain. Tally must refuse, and the failure must be
// classified BUSINESS so the queue stops instead of retrying forever.
func TestIntegrationOverShipIsBusinessError(t *testing.T) {
	c := simClient(t)
	setFault(t, "none")
	ctx := context.Background()

	const item = "4098-9792 SSD SENSOR BASE"
	const box = "1124241658336425" // seeded at 13, label says 18

	v := NewDeliveryNote("WH-OUT-TEST-OVER", "over-ship", "Example Project FZC",
		"SO-2026-0041", time.Now(),
		[]InventoryEntry{{
			StockItemName: item,
			Qty:           nos(18),
			Batches:       []BatchAllocation{{BatchName: box, GodownName: "Main Store", Qty: nos(18)}},
		}})

	_, err := c.Import(ctx, v)
	if err == nil {
		t.Fatal("expected an error shipping 18 from a box holding 13")
	}
	if IsTransient(err) {
		t.Fatalf("over-ship must not be retryable, got %v", err)
	}
	var te *Error
	if !errors.As(err, &te) || te.Class != Business {
		t.Fatalf("want a BUSINESS error, got %#v", err)
	}
	t.Logf("correctly refused: %v", te.Message)
}

func TestIntegrationUnknownItemIsBusinessError(t *testing.T) {
	c := simClient(t)
	setFault(t, "none")

	v := NewReceiptNote("WH-IN-TEST-UNKNOWN", "", "Simplex Supplies", time.Now(),
		[]InventoryEntry{{
			StockItemName: "NO SUCH ITEM 9999",
			Qty:           nos(1),
			Batches:       []BatchAllocation{{BatchName: "1124249900000009", Qty: nos(1)}},
		}})

	_, err := c.Import(context.Background(), v)
	if err == nil {
		t.Fatal("expected an error for an unknown stock item")
	}
	if IsTransient(err) {
		t.Fatalf("unknown item must not be retryable, got %v", err)
	}
}

// TestIntegrationCompanyClosedIsTransient: a closed company is the single most
// common real-world failure (the employee shut Tally, the laptop slept). It
// must keep retrying and must show as COMPANY_CLOSED, not as a hard failure.
func TestIntegrationCompanyClosedIsTransient(t *testing.T) {
	c := simClient(t)
	setFault(t, "closed")
	defer setFault(t, "none")

	_, err := c.ListCompanies(context.Background())
	if err == nil {
		t.Fatal("expected an error while the company is closed")
	}
	if !IsTransient(err) {
		t.Fatalf("company-closed must be retryable, got %v", err)
	}
	if c.Health() != HealthCompanyClosed {
		t.Errorf("health = %s, want COMPANY_CLOSED", c.Health())
	}

	setFault(t, "none")
	if _, err := c.ListCompanies(context.Background()); err != nil {
		t.Fatalf("should recover once the company reopens: %v", err)
	}
	if c.Health() != HealthOnline {
		t.Errorf("health = %s, want ONLINE after recovery", c.Health())
	}
}

func TestIntegrationVoucherValidationRejectsSplitItem(t *testing.T) {
	// Same item twice at the top level is the aggregation bug. It must fail
	// before we ever reach the network.
	v := Voucher{
		Type: ReceiptNote, Date: time.Now(), Reference: "x",
		Entries: []InventoryEntry{
			{StockItemName: "A", Qty: nos(1), Batches: []BatchAllocation{{BatchName: "11242499000001", Qty: nos(1)}}},
			{StockItemName: "A", Qty: nos(1), Batches: []BatchAllocation{{BatchName: "11242499000002", Qty: nos(1)}}},
		},
	}
	if err := v.Validate(); err == nil {
		t.Fatal("expected validation to reject the same stock item in two entries")
	}
}

func TestIntegrationVoucherValidationCatchesQtyMismatch(t *testing.T) {
	v := Voucher{
		Type: ReceiptNote, Date: time.Now(), Reference: "x",
		Entries: []InventoryEntry{{
			StockItemName: "A",
			Qty:           nos(30), // boxes only add to 36
			Batches: []BatchAllocation{
				{BatchName: "11242499000001", Qty: nos(18)},
				{BatchName: "11242499000002", Qty: nos(18)},
			},
		}},
	}
	if err := v.Validate(); err == nil {
		t.Fatal("expected validation to catch line total != sum of boxes")
	}
}

// TestProbeCatchesAMisconfiguredCompany covers the failure that used to be
// invisible: this connector configured against a company Tally does not have
// open.
//
// Tally answers such a request perfectly happily -- it is running, the port is
// open, the XML is well formed -- so the old probe, which asked for the company
// list and discarded it, reported ONLINE. The warehouse's status bar said all
// was well while every single job failed further down, one at a time, with an
// error that never pointed back at the cause.
//
// The name is checked against what is actually loaded, and the message says
// what IS open so whoever reads it can see the mistake immediately. It is
// transient, not permanent: opening the company fixes it with no restart.
func TestProbeCatchesAMisconfiguredCompany(t *testing.T) {
	// Skip BEFORE touching the simulator. Reaching for it first made a plain
	// `go test ./...` fail rather than skip, and a suite that is red for a
	// reason everyone has learned to ignore hides the failures that matter.
	url := os.Getenv("STT_TALLY_URL")
	if url == "" {
		t.Skip("set STT_TALLY_URL to run integration tests against tallysim")
	}
	setFault(t, "none")

	c := NewClient(Config{
		BaseURL: url,
		Company: "ACME FIRE SYSTEM", // one character short of the real name
		Timeout: 10 * time.Second,
	}, nil)

	err := c.Probe(context.Background())
	if err == nil {
		t.Fatal("probe accepted a company Tally does not have open")
	}
	var te *Error
	if !errors.As(err, &te) || te.Code != "COMPANY_NOT_OPEN" {
		t.Fatalf("want COMPANY_NOT_OPEN, got %v", err)
	}
	if !IsTransient(err) {
		t.Error("must be transient: opening the company should fix it without a restart")
	}
	if !strings.Contains(te.Message, simCompany) {
		t.Errorf("message should say what IS open, got: %s", te.Message)
	}
	if c.Health() != HealthCompanyClosed {
		t.Errorf("health should report the closed company, got %s", c.Health())
	}

	// And the correctly-spelled one still passes, so this is a real check and
	// not a probe that now rejects everything.
	if err := simClient(t).Probe(context.Background()); err != nil {
		t.Fatalf("probe rejected the company that IS open: %v", err)
	}
}
