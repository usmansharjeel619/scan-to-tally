package barcode

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// vectorFile mirrors contracts/barcode-vectors.json. The relay and the Android
// app read the same file; if this suite and theirs ever disagree, the label
// means two different things in two places and stock will drift.
type vectorFile struct {
	Version int `json:"version"`
	Vectors []struct {
		Name      string `json:"name"`
		Raw       string `json:"raw"`
		Symbology string `json:"symbology"`
		Outcome   string `json:"outcome"`
		Expect    struct {
			PID       string  `json:"pid"`
			BoxSerial string  `json:"boxSerial"`
			Qty       int     `json:"qty"`
			Firmware  *string `json:"firmware"`
			MfgDate   *string `json:"mfgDate"`
			Hint      string  `json:"hint"`
			Reason    string  `json:"reason"`
		} `json:"expect"`
	} `json:"vectors"`
}

func loadVectors(t *testing.T) vectorFile {
	t.Helper()
	path := filepath.Join("..", "..", "..", "contracts", "barcode-vectors.json")
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	var vf vectorFile
	if err := json.Unmarshal(raw, &vf); err != nil {
		t.Fatalf("parse %s: %v", path, err)
	}
	if len(vf.Vectors) == 0 {
		t.Fatal("no vectors loaded")
	}
	return vf
}

func TestSharedVectors(t *testing.T) {
	vf := loadVectors(t)
	reg := NewRegistry()

	for _, v := range vf.Vectors {
		t.Run(v.Name, func(t *testing.T) {
			got := reg.Parse(v.Symbology, v.Raw)

			if string(got.Outcome) != v.Outcome {
				t.Fatalf("outcome = %s, want %s (%s)", got.Outcome, v.Outcome, got)
			}

			// Raw must survive untouched on every outcome -- it is the only
			// field we can debug a field failure from.
			if got.Raw != v.Raw {
				t.Errorf("raw = %q, want %q", got.Raw, v.Raw)
			}

			switch got.Outcome {
			case Accept:
				if got.Box == nil {
					t.Fatal("ACCEPT with nil box")
				}
				if got.Box.PID != v.Expect.PID {
					t.Errorf("pid = %q, want %q", got.Box.PID, v.Expect.PID)
				}
				if got.Box.BoxSerial != v.Expect.BoxSerial {
					t.Errorf("boxSerial = %q, want %q", got.Box.BoxSerial, v.Expect.BoxSerial)
				}
				if got.Box.Qty != v.Expect.Qty {
					t.Errorf("qty = %d, want %d", got.Box.Qty, v.Expect.Qty)
				}

				wantFW := ""
				if v.Expect.Firmware != nil {
					wantFW = *v.Expect.Firmware
				}
				gotFW := ""
				if got.Box.Firmware != nil {
					gotFW = *got.Box.Firmware
				}
				if gotFW != wantFW {
					t.Errorf("firmware = %q, want %q", gotFW, wantFW)
				}

				wantDate := ""
				if v.Expect.MfgDate != nil {
					wantDate = *v.Expect.MfgDate
				}
				if got.Box.MfgDateString() != wantDate {
					t.Errorf("mfgDate = %q, want %q", got.Box.MfgDateString(), wantDate)
				}

			case WrongBarcode:
				if string(got.Hint) != v.Expect.Hint {
					t.Errorf("hint = %q, want %q", got.Hint, v.Expect.Hint)
				}

			case Reject:
				if string(got.Reason) != v.Expect.Reason {
					t.Errorf("reason = %q, want %q", got.Reason, v.Expect.Reason)
				}
			}
		})
	}
}

// TestKeyIsProductScoped pins the duplicate rule: the same box number under a
// different product is a different box, because Tally scopes a batch under a
// stock item. Getting this wrong would silently block legitimate receipts.
func TestKeyIsProductScoped(t *testing.T) {
	a := ParsedBox{PID: "4098-9792", BoxSerial: "1124241658336425"}
	b := ParsedBox{PID: "4090-9001", BoxSerial: "1124241658336425"}
	c := ParsedBox{PID: "4098-9792", BoxSerial: "1124241658336426"}

	if a.Key() == b.Key() {
		t.Error("same serial under different PIDs must not collide")
	}
	if a.Key() == c.Key() {
		t.Error("different serials under the same PID must not collide")
	}
	if a.Key() != (ParsedBox{PID: "4098-9792", BoxSerial: "1124241658336425"}).Key() {
		t.Error("key must be stable")
	}
}

// TestNoPanicOnJunk: the scanner will eventually deliver something bizarre and
// a panic in the parse path takes the connector down mid-shift.
func TestNoPanicOnJunk(t *testing.T) {
	reg := NewRegistry()
	junk := []string{
		"", "|", "||", "|||", "||||", "\x00", "\r\n", "   ",
		"||18|", "a|b|c", "4098-9792|" + string(make([]byte, 4096)) + "|1|",
		"\xff\xfe|\xff|1|", "----|----|----|",
	}
	for _, s := range junk {
		func() {
			defer func() {
				if r := recover(); r != nil {
					t.Errorf("panic on %q: %v", s, r)
				}
			}()
			_ = reg.Parse("CODE128", s)
		}()
	}
}
