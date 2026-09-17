package tally

import (
	"strings"
	"testing"
)

// A stock item cannot be deleted from Tally once it has transactions, so every
// one of these is permanent. These tests exist to make the refusals hard to
// remove by accident.

func TestCreateStockItemRefusesNonsense(t *testing.T) {
	for _, tc := range []struct {
		name string
		item NewStockItem
		why  string
	}{
		{"empty name", NewStockItem{BaseUnits: "NO"}, "no name"},
		{"stub name", NewStockItem{Name: "AB", BaseUnits: "NO"}, "too short to mean anything"},
		{"no unit", NewStockItem{Name: "4098-9792 SSD SENSOR BASE"}, "a wrong unit is permanent"},
		{"control char", NewStockItem{Name: "4098-9792 SSD\x04 BASE", BaseUnits: "NO"},
			"Tally's own sentinel leaked into a name"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if err := tc.item.Validate(); err == nil {
				t.Fatalf("accepted an item that should be refused (%s)", tc.why)
			}
		})
	}
}

func TestCreateStockItemRefusesUnnamedCompany(t *testing.T) {
	_, err := BuildCreateStockItem("", NewStockItem{
		Name: "4098-9792 SSD SENSOR BASE", BaseUnits: "NO",
	})
	if err == nil {
		t.Fatal("a master must never be created without naming the company")
	}
}

func TestCreateStockItemXMLShape(t *testing.T) {
	got, err := BuildCreateStockItem("Northwind Trading Company", NewStockItem{
		Name: "4098-9792 SSD SENSOR BASE", BaseUnits: "NO",
		Parent: "Fire Alarm", Batchwise: true, TrackMfgDate: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	s := string(got)

	for _, want := range []string{
		`<REPORTNAME>All Masters</REPORTNAME>`,
		`<SVCURRENTCOMPANY>Northwind Trading Company</SVCURRENTCOMPANY>`,
		`NAME="4098-9792 SSD SENSOR BASE"`, // the attribute, present deliberately
		`<NAME>4098-9792 SSD SENSOR BASE</NAME>`,
		`<BASEUNITS>NO</BASEUNITS>`,
		`<ISBATCHWISEON>Yes</ISBATCHWISEON>`,
		`<PARENT>Fire Alarm</PARENT>`,
	} {
		if !strings.Contains(s, want) {
			t.Errorf("missing %s", want)
		}
	}
	// It must be a master import, never a voucher.
	if strings.Contains(s, "<VOUCHER") {
		t.Error("a stock item create must not contain a voucher")
	}
}

// Expiry tracking only makes sense on a batch-tracked item; enabling it
// otherwise produces an item Tally will argue with later.
func TestMfgDateTrackingRequiresBatches(t *testing.T) {
	got, _ := BuildCreateStockItem("Co", NewStockItem{
		Name: "4098-9792 SSD SENSOR BASE", BaseUnits: "NO",
		Batchwise: false, TrackMfgDate: true,
	})
	if strings.Contains(string(got), "<ISPERISHABLEON>Yes</ISPERISHABLEON>") {
		t.Error("date tracking must not be enabled on a non-batch item")
	}
}

// The default is off. A warehouse scanner writing to the books is a choice a
// site makes, not something that arrives switched on.
func TestMasterCreationIsOffByDefault(t *testing.T) {
	var policy MasterPolicy // zero value
	if policy.AllowCreate {
		t.Fatal("master creation must default to disabled")
	}
}
