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
	// A dashed part number.
	//
	// Simplex's own are nnnn-nnnn, but the same shelves carry JCI, Tyco,
	// Apollo and KAC cartons, and theirs carry LETTERS: a revision suffix
	// (4090-9001B), Apollo's 55000-390APO. A pattern that accepts only
	// nnnn-nnnn refuses those outright, and a part number the app refuses is
	// a carton that does not get counted -- the one outcome worth avoiding.
	//
	// Still only the DASHED form here, because this is the blind path, which
	// never guesses. Undashed codes stay ambiguous against a box id until the
	// operator says which field they are filling. A three-digit head stays
	// out too: the Simplex Mexico supplier ref "742-949" is nnn-nnn and is
	// not a part number.
	reProduct = regexp.MustCompile(`^[0-9]{4,6}-[0-9]{2,5}[A-Z]{0,4}$`)

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

// CanonicalPID is the form a part number is MATCHED on, never the form it is
// stored in.
//
// The same model arrives on cartons printed both 41009701 and 4100-9701. Stored
// verbatim -- which is right, the audit trail should say what was on the box --
// those are two different strings, and a duplicate check keyed on them would
// let the same physical box be received twice under the two spellings. So
// identity collapses the dash and comparison uses this; display and storage do
// not.
func CanonicalPID(v string) string {
	return strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(v), "-", ""))
}

// PIDVariants returns the spellings a part number might be filed under.
//
// Some cartons print it as 8 bare digits with the dash dropped. It is the same
// product either way, so a lookup tries both -- but the SCANNED form is what
// gets stored. Rewriting the payload would put a part number in the audit trail
// that was never on the carton, and would stop matching an item already in
// Tally under the other spelling.
//
// The scanned form is always first, so a caller that only takes the head still
// gets what was actually read.
func PIDVariants(v string) []string {
	v = strings.TrimSpace(v)
	switch {
	case reEightDigits.MatchString(v):
		return []string{v, v[:4] + "-" + v[4:]}
	case reProduct.MatchString(v):
		return []string{v, v[:4] + v[5:]}
	default:
		return []string{v}
	}
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
		// The operator has SAID this is the part number, so it is taken as
		// one. Whatever a supplier prints -- dashed, undashed, lettered, a
		// code no pattern here has ever seen -- is a part number if that is
		// the field being filled.
		//
		// Only the three things that certainly are NOT one are refused, and
		// they are refused because each is a barcode printed inches away on
		// the same label: the 16-digit box serial, the small bare number under
		// QTY, and the two-letter country of origin.
		if reSerial16.MatchString(v) ||
			(reDigits.MatchString(v) && len(v) <= 4) ||
			len(v) < 3 || len(v) > MaxSerialLen {
			return Fragment{Kind: FragmentNotMine, Hint: HintPartNo, Raw: raw}
		}
		// Stored as scanned. Reconciling the dash is the lookup's job.
		return Fragment{Kind: FragmentProduct, Value: v, Raw: raw}

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
