// Package barcode turns a raw scanner payload into a box we can post to Tally.
//
// The parser set is deliberately a registry rather than a single function: the
// Simplex pipe format is the only one in production today, but other suppliers
// will arrive and must slot in without touching any call site.
//
// Every implementation of this parser (Go here, TypeScript in the relay, Kotlin
// on the device) is driven by contracts/barcode-vectors.json. Change the rules
// here and you must change them there, and re-run all three suites.
package barcode

import (
	"fmt"
	"time"
)

// Outcome is the four-way result of looking at a scanned payload. The
// distinction between WrongBarcode and Unknown is what lets the app say
// "scan the long code at the bottom" instead of a useless generic error.
type Outcome string

const (
	// Accept means the payload yielded a usable box.
	Accept Outcome = "ACCEPT"
	// WrongBarcode means we recognised the payload as one of the *other*
	// codes printed on the same label. The operator scanned the wrong one.
	WrongBarcode Outcome = "WRONG_BARCODE"
	// Reject means the payload looked like our format but was invalid.
	// This fails loudly; a mis-parsed quantity is worse than no scan.
	Reject Outcome = "REJECT"
	// Unknown means no parser recognised the payload at all.
	Unknown Outcome = "UNKNOWN"
)

// Hint names which of the label's other barcodes was scanned, so the UI can
// point the operator at the right one.
type Hint string

const (
	HintPID    Hint = "PID"
	HintPartNo Hint = "PART_NO"
	HintCOO    Hint = "COO"
	HintQty    Hint = "QTY"
)

// Reason explains a Reject. These strings reach the operator (via a lookup
// table of friendly text) and the audit log, so they are stable identifiers.
type Reason string

const (
	ReasonQtyNotPositiveInt Reason = "QTY_NOT_A_POSITIVE_INTEGER"
	ReasonQtyOutOfRange     Reason = "QTY_OUT_OF_RANGE"
	ReasonEmptySerial       Reason = "EMPTY_SERIAL"
	ReasonEmptyPID          Reason = "EMPTY_PID"
	ReasonSerialLength      Reason = "SERIAL_LENGTH"
	ReasonFieldCount        Reason = "FIELD_COUNT"
)

// Limits on what we will believe from a label. These are sanity bounds against
// a misread, not business rules -- the real quantity ceilings live in the
// validation layer and come from Tally.
const (
	MinSerialLen = 8
	MaxSerialLen = 32
	MaxQty       = 10000
)

// ParsedBox is one physical carton: what it holds, which box it is, how many.
type ParsedBox struct {
	// PID is the product identifier from the label. It is the join key to a
	// Tally stock item, resolved through the item master -- never assumed to
	// be the stock item name itself.
	PID string `json:"pid"`
	// BoxSerial is the per-box unique number. It becomes the Tally batch name
	// verbatim: no transform, so the outgoing scan reproduces it byte for byte.
	BoxSerial string `json:"boxSerial"`
	// Qty is the units in this box as printed on the label. For incoming this
	// is authoritative; for outgoing it is only the ceiling's upper bound.
	Qty int `json:"qty"`
	// Firmware is the label's fourth field. Empty on passive devices.
	Firmware *string `json:"firmware"`
	// MfgDate is derived from the serial's MMDDYY prefix when that prefix is a
	// real date, nil otherwise. Treat as advisory until confirmed across more
	// labels -- see the spec's section 03.
	MfgDate *time.Time `json:"-"`
}

// MfgDateString renders MfgDate for JSON and for comparison against the shared
// vectors. Empty string when no date could be derived.
func (b ParsedBox) MfgDateString() string {
	if b.MfgDate == nil {
		return ""
	}
	return b.MfgDate.Format("2006-01-02")
}

// Key is the duplicate-detection key: (product, box). Tally scopes a batch
// under a stock item, so this pair is exactly its natural key too. The same
// box number under a different PID is a different box.
func (b ParsedBox) Key() string {
	return b.PID + "\x1f" + b.BoxSerial
}

// Result is what the registry hands back. Raw is always populated, including
// on Unknown, because the raw payload is stored on every scan line forever --
// it is the only evidence available when a scan goes wrong in the field.
type Result struct {
	Outcome    Outcome    `json:"outcome"`
	Parser     string     `json:"parser,omitempty"`
	Raw        string     `json:"raw"`
	Symbology  string     `json:"symbology,omitempty"`
	Box        *ParsedBox `json:"box,omitempty"`
	Hint       Hint       `json:"hint,omitempty"`
	Reason     Reason     `json:"reason,omitempty"`
	Confidence float64    `json:"confidence,omitempty"`
}

func (r Result) String() string {
	switch r.Outcome {
	case Accept:
		return fmt.Sprintf("ACCEPT %s box=%s qty=%d", r.Box.PID, r.Box.BoxSerial, r.Box.Qty)
	case WrongBarcode:
		return fmt.Sprintf("WRONG_BARCODE (%s)", r.Hint)
	case Reject:
		return fmt.Sprintf("REJECT (%s)", r.Reason)
	default:
		return "UNKNOWN"
	}
}

// Parser recognises one label dialect.
type Parser interface {
	// Name identifies the parser in logs and on stored scan lines, so we can
	// tell later which dialect interpreted a given raw payload.
	Name() string
	// Probe returns 0 when this parser does not recognise the payload at all,
	// or a confidence in (0,1]. It must be cheap and must not panic on junk.
	Probe(symbology, raw string) float64
	// Parse is only called when Probe returned > 0.
	Parse(symbology, raw string) Result
}
