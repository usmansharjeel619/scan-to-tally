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
