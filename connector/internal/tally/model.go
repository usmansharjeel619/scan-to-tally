// Package tally speaks Tally Prime's HTTP-XML gateway (default port 9000).
//
// Two properties of that gateway shape everything here:
//
//  1. It has no authentication of any kind. Anyone who can reach the port can
//     read the whole book and post vouchers. It is therefore only ever spoken
//     to over localhost by the connector, never exposed to a network.
//  2. It processes requests serially and only exists while Tally is running
//     with the company open. Client below enforces a single in-flight request
//     and treats "not running" / "company closed" as transient, not fatal.
//
// The XML shapes in import.go and export.go are TEMPLATES. Tally's schema
// drifts between ERP 9 and Prime releases, and the only authoritative source is
// the target installation: key the voucher by hand in Tally, export it as XML,
// and diff it against what we emit. See docs/PHASE0.md.
package tally

import (
	"fmt"
	"strings"
	"time"
)

// VoucherType names a Tally voucher type exactly as it is spelled in the
// company. These are configurable because a company may have renamed them.
type VoucherType string

const (
	// ReceiptNote is the default for incoming: a pure inventory movement with
	// no accounting impact, leaving the Purchase invoice to the accounts team.
	// This keeps a warehouse scanner out of the ledger.
	ReceiptNote VoucherType = "Receipt Note"
	// Purchase is the alternative for incoming when stock and accounts must
	// move together.
	Purchase VoucherType = "Purchase"
	// DeliveryNote is outgoing, linked back to a Sales Order by order number.
	DeliveryNote VoucherType = "Delivery Note"
	// PhysicalStock is the inventory-check adjustment (phase 4).
	PhysicalStock VoucherType = "Physical Stock"
)

// Qty is a Tally quantity: a number plus its unit symbol. Tally will not accept
// a bare number on an inventory line, and it must match the stock item's base
// unit or a defined alternate, so the unit always travels with the value.
type Qty struct {
	Value float64
	Unit  string // "Nos", "Pcs", "Box" -- from the item master, never guessed
}

func (q Qty) String() string {
	// Tally accepts "18 Nos". Trailing zeros on a whole number are harmless but
	// noisy in the voucher, so render integers as integers.
	if q.Value == float64(int64(q.Value)) {
		return fmt.Sprintf("%d %s", int64(q.Value), q.Unit)
	}
	return fmt.Sprintf("%g %s", q.Value, q.Unit)
}

// BatchAllocation is one physical box on a voucher line.
//
// Box == batch. Tally scopes a batch under a stock item, which makes
// (stock item, batch) the natural key for exactly the duplicate rule the
// warehouse already applies: same part + same box number must never repeat,
// but the same part across different boxes is the normal case.
type BatchAllocation struct {
	// BatchName is the box serial, stored raw with no transform, so an
	// outgoing scan reproduces the receipt's batch name byte for byte.
	BatchName string
	GodownName string
	Qty        Qty

	// MfgDate is derived from the serial's date prefix where available.
	// Requires "Use expiry/manufacturing dates" on the stock item.
	MfgDate *time.Time

	// OrderNo links a Delivery Note line back to its Sales Order.
	// VERIFY against a hand-keyed export: this is the field most likely to
	// differ between Tally versions.
	OrderNo string
	// TrackingNumber is what later links a Delivery Note to its Sales Invoice.
	TrackingNumber string
}

// InventoryEntry is one stock item on a voucher, carrying every box of that
// item scanned in the session.
//
// This nesting is load-bearing: the operator scans box by box, but the voucher
// must have ONE entry per part number with N batch allocations beneath it.
// Emitting one entry per box is accepted by Tally but makes the stock and batch
// reports unusable.
type InventoryEntry struct {
	StockItemName string
	Qty           Qty // must equal the sum of the batch allocations
	Rate          *float64
	Amount        *float64
	Batches       []BatchAllocation

	// Description is Tally's per-line additional description. Requires the
	// corresponding option enabled on the company/item.
	// VERIFY the field name against a hand-keyed export.
	Description string
}

// SumBatches returns the total across allocations, which must equal Qty.
func (e InventoryEntry) SumBatches() float64 {
	var t float64
	for _, b := range e.Batches {
		t += b.Qty.Value
	}
	return t
}

// Voucher is one posting to Tally.
type Voucher struct {
	Type VoucherType
	Date time.Time

	// Reference is the BUSINESS reference -- a supplier's delivery note number,
	// a customer order number. It is left for the business to use.
	//
	// It deliberately does NOT carry the idempotency key. A live company was
	// found using REFERENCE for a real document number ("MI/203-C/09/2026"),
	// and overwriting that would destroy information somebody relies on.
	Reference string
	Narration string

	// IdempotencyKey is the session UUID. It is written into the NARRATION as a
	// [STT:...] marker rather than into REFERENCE, so reconciliation can still
	// find it by reading the day book after a total loss of the connector's
	// local database, without clobbering a business field.
	IdempotencyKey string

	PartyLedgerName string
	VoucherNumber   string // usually left empty so Tally auto-numbers

	Entries []InventoryEntry
}

// NarrationWithKey appends the idempotency marker to the narration.
//
// Kept greppable and distinctive: reconciliation reads the day book back and
// looks for this exact marker to work out which sessions already reached Tally.
func (v Voucher) NarrationWithKey() string {
	if v.IdempotencyKey == "" {
		return v.Narration
	}
	marker := "[" + IdempotencyMarker + ":" + v.IdempotencyKey + "]"
	if v.Narration == "" {
		return marker
	}
	return v.Narration + " " + marker
}

// IdempotencyMarker prefixes the key in the narration.
const IdempotencyMarker = "STT"

// Validate catches the mistakes that Tally would either silently accept or
// reject with an opaque message. Cheaper to fail here than to debug a
// half-posted voucher later.
func (v Voucher) Validate() error {
	if v.Type == "" {
		return fmt.Errorf("voucher type is required")
	}
	if v.Date.IsZero() {
		return fmt.Errorf("voucher date is required")
	}
	if v.IdempotencyKey == "" {
		return fmt.Errorf("idempotency key is required")
	}
	if len(v.Entries) == 0 {
		return fmt.Errorf("voucher has no inventory entries")
	}

	seenItems := map[string]bool{}
	for i, e := range v.Entries {
		if e.StockItemName == "" {
			return fmt.Errorf("entry %d: stock item name is empty", i)
		}
		// One entry per part number -- a duplicate here means the caller
		// failed to aggregate scans by item.
		if seenItems[e.StockItemName] {
			return fmt.Errorf("entry %d: stock item %q appears twice; aggregate boxes into one entry with multiple batch allocations", i, e.StockItemName)
		}
		seenItems[e.StockItemName] = true

		if len(e.Batches) == 0 {
			return fmt.Errorf("entry %d (%s): no batch allocations", i, e.StockItemName)
		}
		if e.Qty.Unit == "" {
			return fmt.Errorf("entry %d (%s): quantity has no unit", i, e.StockItemName)
		}

		seenBatches := map[string]bool{}
		for j, b := range e.Batches {
			if b.BatchName == "" {
				return fmt.Errorf("entry %d batch %d: batch name (box serial) is empty", i, j)
			}
			if seenBatches[b.BatchName] {
				return fmt.Errorf("entry %d: box %q appears twice under %s", i, b.BatchName, e.StockItemName)
			}
			seenBatches[b.BatchName] = true

			if b.Qty.Value <= 0 {
				return fmt.Errorf("entry %d batch %d (%s): quantity must be positive", i, j, b.BatchName)
			}
			if b.Qty.Unit != e.Qty.Unit {
				return fmt.Errorf("entry %d batch %d: unit %q does not match line unit %q", i, j, b.Qty.Unit, e.Qty.Unit)
			}
		}

		// Float comparison with a tolerance: quantities are near-integers here,
		// but alternate units can introduce fractions.
		if diff := e.SumBatches() - e.Qty.Value; diff > 0.0001 || diff < -0.0001 {
			return fmt.Errorf("entry %d (%s): line qty %v does not equal sum of boxes %v",
				i, e.StockItemName, e.Qty.Value, e.SumBatches())
		}
	}
	return nil
}

// --- master data read back from Tally --------------------------------------

// Company is an open company in the running Tally instance.
type Company struct {
	Name      string
	StartFrom time.Time
	GUID      string
}

// StockItem is one row of the Tally stock item master, cached by the relay and
// synced to devices so PID resolution and unit handling work offline.
type StockItem struct {
	Name      string
	Alias     string
	PartNo    string
	BaseUnits string
	GUID      string
	// HasBatches reports whether "Maintain in batches" is on. An item without
	// it cannot carry a box serial, which is a configuration error worth
	// surfacing loudly rather than discovering at post time.
	HasBatches bool
}

// BatchBalance is the closing stock of one box.
//
// This is the hard ceiling for outgoing quantity, and it is NOT the printed box
// quantity: a box that shipped 5 of 18 last week has 13 left while its label
// still says 18.
type BatchBalance struct {
	StockItemName string
	BatchName     string
	GodownName    string
	ClosingQty    float64
	Unit          string
	// AsOf is when the connector read this from Tally. The device shows it so
	// the operator can see the figure is a few minutes old.
	AsOf time.Time
}

// Key matches a balance to a scan.
func (b BatchBalance) Key() string {
	return strings.Join([]string{b.StockItemName, b.BatchName, b.GodownName}, "\x1f")
}

// SalesOrderLine is one item on a sales order with its outstanding quantity.
type SalesOrderLine struct {
	StockItemName string
	OrderedQty    float64
	DeliveredQty  float64
	Unit          string
}

// PendingQty is what may still be despatched against this line.
func (l SalesOrderLine) PendingQty() float64 {
	p := l.OrderedQty - l.DeliveredQty
	if p < 0 {
		return 0
	}
	return p
}

// SalesOrder is an open order the operator picks before scanning an outgoing
// consignment.
type SalesOrder struct {
	VoucherNumber string
	OrderRef      string
	PartyName     string
	Date          time.Time
	Lines         []SalesOrderLine
	GUID          string
}

// IsFullyDelivered reports whether nothing remains outstanding, so the picking
// list can hide it.
func (s SalesOrder) IsFullyDelivered() bool {
	for _, l := range s.Lines {
		if l.PendingQty() > 0.0001 {
			return false
		}
	}
	return true
}
