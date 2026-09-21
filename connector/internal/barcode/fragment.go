package barcode

import (
	"regexp"
	"strings"
)

// A scan that is ONE FIELD of a multi-barcode label rather than a whole box.
//
// The Tyco, KAC and US/UK Simplex cartons carry the part number, quantity, box
// id, part no, date code, week number and issue number as separate barcodes.
// Only two of those can be placed safely by looking at them.
type FragmentKind string

const (
	// FragmentProduct is the part number. Every label seen so far prints it as
	// nnnn-nnnn, which is what makes the rest of this workable.
	FragmentProduct FragmentKind = "PRODUCT"

	// FragmentBox is the carton's own id.
	FragmentBox FragmentKind = "BOX"

	// FragmentNotMine is a barcode that belongs to the label but to none of our
	// fields. It is REFUSED, never guessed into a slot.
	//
	// This is the whole reason the quantity is typed rather than scanned: on a
	// Tyco label "Week No 17" and "Quantity 20" are both two-digit barcodes, and
	// nothing about the payload says which is which. Accepting either would put
	// stock wrong with nothing on screen to show for it.
	FragmentNotMine FragmentKind = "NOT_MINE"
)

type Fragment struct {
	Kind FragmentKind
	// Value is the cleaned payload, set for PRODUCT and BOX only.
	Value string
	// Hint names what the operator most likely pointed at, so the refusal can
	// say something useful instead of just "no". Shares the Hint type with the
	// whole-label parser: the same barcodes are being described either way.
	Hint Hint
	Raw  string
}

var (
	// Every part number on every label examined: four digits, dash, four
	// digits. A supplier ref like "742-949" is nnn-nnn and deliberately does
	// not match.
	reProduct = regexp.MustCompile(`^[0-9]{4}-[0-9]{4}$`)

	// The Simplex serial, scanned on its own rather than inside the long code.
	reSerial16 = regexp.MustCompile(`^[0-9]{16}$`)

	// A Tyco box id: letters THEN digits -- HFE283, HKT710, HJO761.
	//
	// The letters-first ordering is load bearing. A Simplex part no can also
	// mix letters and digits ("0677197CN"), but digits first, so a looser
	// "contains both" rule would file a part number as a box id and quietly
	// invent a batch that no carton carries.
	reBoxID = regexp.MustCompile(`^[A-Z]{2,4}[0-9]{3,6}$`)

	reDigits = regexp.MustCompile(`^[0-9]+$`)
)

// ClassifyFragment decides which slot a lone barcode belongs in.
//
// It never guesses. Anything it cannot place with certainty comes back as
// NOT_MINE, and the operator types that field instead.
func ClassifyFragment(raw string) Fragment {
	v := strings.ToUpper(strings.TrimSpace(raw))

	switch {
	case v == "":
		return Fragment{Kind: FragmentNotMine, Raw: raw}

	case reProduct.MatchString(v):
		return Fragment{Kind: FragmentProduct, Value: v, Raw: raw}

	case reSerial16.MatchString(v):
		return Fragment{Kind: FragmentBox, Value: v, Raw: raw}

	case reBoxID.MatchString(v):
		return Fragment{Kind: FragmentBox, Value: v, Raw: raw}

	case reDigits.MatchString(v):
		// A quantity, a week number, an issue number, a date code or a part
		// no. Indistinguishable from each other, so all of them are refused.
		//
		// Length only picks the wording of the refusal, never whether to
		// accept: four digits or fewer could plausibly be a carton quantity
		// (and could equally be a week or issue number), more than that could
		// not be.
		if len(v) <= 4 {
			return Fragment{Kind: FragmentNotMine, Hint: HintQty, Raw: raw}
		}
		return Fragment{Kind: FragmentNotMine, Hint: HintPartNo, Raw: raw}

	case strings.ContainsAny(v, "0123456789"):
		// Mixed letters and digits that is not a box id: a part no such as
		// 0677197CN, or a supplier reference.
		return Fragment{Kind: FragmentNotMine, Hint: HintPartNo, Raw: raw}
	}

	return Fragment{Kind: FragmentNotMine, Raw: raw}
}

// --- targeted scanning ------------------------------------------------------

// Slot is the field the operator asked for BEFORE scanning.
//
// Blind scanning has to place a barcode by shape alone, which is why so much of
// a label is refused: a quantity, a week number and an issue number are the
// same shape, and an 8-digit part number is the same shape as a date code.
//
// When the operator taps "scan the product number" first, the slot stops being
// a guess. Only the shape has to fit, and fields that can never be placed
// blind become safe to scan.
type Slot string

const (
	SlotProduct  Slot = "PRODUCT"
	SlotBox      Slot = "BOX"
	SlotQuantity Slot = "QUANTITY"
)

// FragmentQuantity is a quantity the operator explicitly asked to scan.
//
// It is never produced by a blind scan, and that asymmetry is the point.
const FragmentQuantity FragmentKind = "QUANTITY"

// maxScannedQty bounds a scanned carton count. Beyond this it is a misread or
// somebody has pointed at a serial.
const maxScannedQty = 9999

var reEightDigits = regexp.MustCompile(`^[0-9]{8}$`)

// NormalisePID puts a part number in its canonical dashed form.
//
// Some cartons print the part number as 8 bare digits with the dash dropped.
// It is the same product, so it is normalised to nnnn-nnnn and resolves to one
// entry in the catalogue however it was printed.
//
// Anything that is not exactly 8 digits is returned untouched -- this widens
// nothing and invents nothing.
func NormalisePID(v string) string {
	v = strings.TrimSpace(v)
	if reEightDigits.MatchString(v) {
		return v[:4] + "-" + v[4:]
	}
	return v
}

// ClassifyFragmentFor places a barcode into a slot the operator named.
//
// The difference from ClassifyFragment is the whole design: here the slot is
// known, so the only question is whether the payload could be that field. A
// payload that does not fit is still refused rather than coerced -- scanning a
// box serial into the quantity slot must fail, not become a quantity of
// 1124241658336425.
func ClassifyFragmentFor(raw string, want Slot) Fragment {
	v := strings.ToUpper(strings.TrimSpace(raw))
	if v == "" {
		return Fragment{Kind: FragmentNotMine, Raw: raw}
	}

	switch want {
	case SlotProduct:
		// Dashed, or the same number with the dash dropped.
		if reProduct.MatchString(v) || reEightDigits.MatchString(v) {
			return Fragment{Kind: FragmentProduct, Value: NormalisePID(v), Raw: raw}
		}
		return Fragment{Kind: FragmentNotMine, Hint: HintPartNo, Raw: raw}

	case SlotBox:
		if reSerial16.MatchString(v) || reBoxID.MatchString(v) {
			return Fragment{Kind: FragmentBox, Value: v, Raw: raw}
		}
		return Fragment{Kind: FragmentNotMine, Raw: raw}

	case SlotQuantity:
		// Deliberately narrow. A quantity is a small positive number; anything
		// longer is a serial or a date the operator has pointed at by mistake.
		if !reDigits.MatchString(v) || len(v) > 4 {
			return Fragment{Kind: FragmentNotMine, Hint: HintQty, Raw: raw}
		}
		n := 0
		for _, c := range v {
			n = n*10 + int(c-'0')
		}
		if n <= 0 || n > maxScannedQty {
			return Fragment{Kind: FragmentNotMine, Hint: HintQty, Raw: raw}
		}
		return Fragment{Kind: FragmentQuantity, Value: v, Raw: raw}
	}

	// An unknown slot falls back to blind classification rather than accepting
	// something on a caller's typo.
	return ClassifyFragment(raw)
}

// RefusedForSlot explains, in the operator's terms, why a targeted scan did not
// fit the field they asked for.
func RefusedForSlot(want Slot) string {
	switch want {
	case SlotProduct:
		return "That is not a part number. Scan the code printed under PID, Type or Part."
	case SlotBox:
		return "That is not a box number. Scan the long serial, or the box id."
	case SlotQuantity:
		return "That is not a quantity. Scan the number printed under QTY, or type it."
	}
	return "That barcode does not belong in this field."
}
