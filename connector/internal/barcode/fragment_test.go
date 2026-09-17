package barcode

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type fragmentVector struct {
	Name  string `json:"name"`
	Raw   string `json:"raw"`
	Kind  string `json:"kind"`
	Value string `json:"value"`
	Hint  string `json:"hint"`
}

// Runs the SAME file as the TypeScript and Kotlin suites. If one of them
// disagrees, a barcode means two different things in two places.
func TestFragmentGoldenVectors(t *testing.T) {
	path := filepath.Join("..", "..", "..", "contracts", "barcode-vectors.json")
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read contract: %v", err)
	}
	var doc struct {
		Fragments []fragmentVector `json:"fragments"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatalf("parse contract: %v", err)
	}
	if len(doc.Fragments) == 0 {
		t.Fatal("no fragment vectors; the contract is not being read")
	}

	for _, v := range doc.Fragments {
		t.Run(v.Name, func(t *testing.T) {
			got := ClassifyFragment(v.Raw)

			if string(got.Kind) != v.Kind {
				t.Fatalf("kind = %q, want %q (raw %q)", got.Kind, v.Kind, v.Raw)
			}
			if v.Value != "" && got.Value != v.Value {
				t.Errorf("value = %q, want %q", got.Value, v.Value)
			}
			if v.Kind != "NOT_MINE" && got.Value == "" {
				t.Errorf("a placed fragment must carry its value")
			}
			if v.Hint != "" && string(got.Hint) != v.Hint {
				t.Errorf("hint = %q, want %q", got.Hint, v.Hint)
			}
		})
	}
}

// The distinction the whole design rests on: a part number and a box id can
// both mix letters and digits, and only the ordering separates them.
func TestPartNoIsNeverFiledAsABoxID(t *testing.T) {
	for _, partNo := range []string{"0677197CN", "0677104", "0746234", "742-949"} {
		if got := ClassifyFragment(partNo); got.Kind == FragmentBox {
			t.Errorf("%q was filed as a box id; it would invent a batch no carton carries", partNo)
		}
	}
}

// A week number, an issue number and a quantity are the same shape. None of
// them may ever be accepted, or stock goes wrong silently.
func TestNoBareNumberIsEverAccepted(t *testing.T) {
	for _, n := range []string{"1", "5", "17", "20", "24", "35", "100", "2825", "23205", "26154"} {
		if got := ClassifyFragment(n); got.Kind != FragmentNotMine {
			t.Errorf("%q classified as %s; a bare number must always be refused", n, got.Kind)
		}
	}
}
