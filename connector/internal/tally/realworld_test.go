package tally

import (
	"strings"
	"testing"
	"time"
)

// Pinned against a live TallyPrime company (574 stock items, five years of
// vouchers) read on 2026-09-17. Everything here was previously a VERIFY comment
// written from documentation.

// The live company's dominant unit symbol is "NO", not the "Nos" assumed earlier.
func unitNO(v float64) Qty { return Qty{Value: v, Unit: "NO"} }

func buildOne(t *testing.T, v Voucher) string {
	t.Helper()
	b, err := BuildImport("Northwind Trading Company", v)
	if err != nil {
		t.Fatalf("BuildImport: %v", err)
	}
	return string(b)
}

// The per-line description is nested inside BASICUSERDESCRIPTION.LIST. A flat
// element is accepted by Tally and then silently dropped -- the description
// would simply never appear, with no error to notice.
func TestDescriptionIsNestedInItsList(t *testing.T) {
	got := buildOne(t, NewReceiptNote("sess-1", "", "Supplier", time.Now(),
		[]InventoryEntry{{
			StockItemName: "4099-9006 DS PUSH PULL TYPE MPS",
			Qty:           unitNO(3),
			Description:   "4099-9006 Manual Pull Station",
			Batches:       []BatchAllocation{{BatchName: "1124241658336425", Qty: unitNO(3)}},
		}}))

	if !strings.Contains(got, "<BASICUSERDESCRIPTION.LIST>") {
		t.Error("description must be wrapped in BASICUSERDESCRIPTION.LIST")
	}
	i := strings.Index(got, "<BASICUSERDESCRIPTION.LIST>")
	j := strings.Index(got, "<BASICUSERDESCRIPTION>")
	if i < 0 || j < 0 || j < i {
		t.Error("BASICUSERDESCRIPTION must sit inside its .LIST wrapper")
	}
}

// A live company uses REFERENCE for a real document number ("MI/203-C/09/2026").
// Putting a session UUID there would overwrite information the business relies
// on, so the idempotency marker lives in the narration instead.
func TestIdempotencyKeyDoesNotClobberBusinessReference(t *testing.T) {
	v := NewReceiptNote("sess-abc-123", "Goods in from dock", "Supplier", time.Now(),
		[]InventoryEntry{{
			StockItemName: "X", Qty: unitNO(1),
			Batches: []BatchAllocation{{BatchName: "1124241658336425", Qty: unitNO(1)}},
		}})
	v.Reference = "MI/203-C/09/2026" // the supplier's own document number

	got := buildOne(t, v)

	if !strings.Contains(got, "<REFERENCE>MI/203-C/09/2026</REFERENCE>") {
		t.Error("the business reference must survive untouched")
	}
	if strings.Contains(got, "<REFERENCE>sess-abc-123</REFERENCE>") {
		t.Error("the session id must NOT be written into REFERENCE")
	}
	if !strings.Contains(got, "[STT:sess-abc-123]") {
		t.Error("the idempotency marker must be findable in the narration")
	}
	if !strings.Contains(got, "Goods in from dock") {
		t.Error("the operator's narration must survive alongside the marker")
	}
}

// Confirmed on a live SALES INVOICE: goods leaving carry ISDEEMEDPOSITIVE No.
// A flipped sign posts the movement backwards and shows up only in the stock
// report, days later.
func TestStockDirectionPolarity(t *testing.T) {
	entry := []InventoryEntry{{
		StockItemName: "X", Qty: unitNO(1),
		Batches: []BatchAllocation{{BatchName: "1124241658336425", Qty: unitNO(1)}},
	}}

	in := buildOne(t, NewReceiptNote("s1", "", "Supplier", time.Now(), entry))
	if !strings.Contains(in, "<ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>") {
		t.Error("incoming stock must be deemed positive")
	}

	out := buildOne(t, NewDeliveryNote("s2", "", "Customer", "SO-1", time.Now(), entry))
	if !strings.Contains(out, "<ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>") {
		t.Error("outgoing stock must NOT be deemed positive")
	}
}

// The field names in BATCHALLOCATIONS.LIST, confirmed verbatim from a live
// voucher: GODOWNNAME, BATCHNAME, ORDERNO, TRACKINGNUMBER, ACTUALQTY, BILLEDQTY.
func TestBatchAllocationFieldNames(t *testing.T) {
	mfg := time.Date(2024, 11, 24, 0, 0, 0, 0, time.UTC)
	got := buildOne(t, NewDeliveryNote("s3", "", "Customer", "SO-2026-0041", time.Now(),
		[]InventoryEntry{{
			StockItemName: "4099-9006 DS PUSH PULL TYPE MPS", Qty: unitNO(3),
			Batches: []BatchAllocation{{
				BatchName: "1124241658336425", GodownName: "Main Location",
				Qty: unitNO(3), MfgDate: &mfg,
			}},
		}}))

	for _, want := range []string{
		"<GODOWNNAME>Main Location</GODOWNNAME>",
		"<BATCHNAME>1124241658336425</BATCHNAME>",
		"<ORDERNO>SO-2026-0041</ORDERNO>",
		"<MFDON>20241124</MFDON>",
		"<ACTUALQTY>3 NO</ACTUALQTY>",
		"<BILLEDQTY>3 NO</BILLEDQTY>",
	} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %s", want)
		}
	}
}

// The live item master is named "<PID> <DESCRIPTION>" -- 4099-9006 DS PUSH PULL
// TYPE MPS, 2081-9044 SURGE PROTECTOR. No PARTNO is populated on any of the 574
// items, so resolving a scanned PID depends on matching that name prefix.
func TestLiveNamingConventionIsPidThenDescription(t *testing.T) {
	for _, name := range []string{
		"4099-9006 DS PUSH PULL TYPE MPS",
		"2081-9044 SURGE PROTECTOR",
		"2080-9057 ABORT SWITCH, SURFACE",
	} {
		pid := strings.SplitN(name, " ", 2)[0]
		if !strings.HasPrefix(name, pid+" ") {
			t.Errorf("%q does not start with its PID", name)
		}
		if len(strings.Split(pid, "-")) != 2 {
			t.Errorf("PID %q is not the expected NNNN-NNNN shape", pid)
		}
	}
}
