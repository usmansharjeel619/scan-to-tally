package tally

import (
	"encoding/xml"
	"strings"
	"testing"
)

// The exact shape that broke the live sync: a stock item under Primary.
const liveItemXML = `<ENVELOPE><BODY><DATA><COLLECTION>
<STOCKITEM NAME="4190-9822 WIRED MEDIA CARD">
 <PARENT>&#4; Primary</PARENT>
 <CATEGORY>&#4; Not Applicable</CATEGORY>
 <BASEUNITS>NO</BASEUNITS>
 <ISBATCHWISEON>Yes</ISBATCHWISEON>
</STOCKITEM>
</COLLECTION></DATA></BODY></ENVELOPE>`

func TestTallyXMLWithControlMarkerParses(t *testing.T) {
	// Proves the problem is real: untouched, this fails outright.
	var probe struct{}
	if err := xml.Unmarshal([]byte(liveItemXML), &probe); err == nil {
		t.Fatal("expected raw Tally XML to be rejected; the fix may be unnecessary now")
	} else if !strings.Contains(err.Error(), "illegal character code") {
		t.Fatalf("unexpected error, test is not exercising the right thing: %v", err)
	}

	var got []StockItem
	err := walk([]byte(liveItemXML), "STOCKITEM", func(d *xml.Decoder, se xml.StartElement) error {
		var row struct {
			Parent        string `xml:"PARENT"`
			BaseUnits     string `xml:"BASEUNITS"`
			IsBatchWiseOn string `xml:"ISBATCHWISEON"`
		}
		if err := d.DecodeElement(&row, &se); err != nil {
			return err
		}
		got = append(got, StockItem{
			Name:       attr(se, "NAME"),
			BaseUnits:  strings.TrimSpace(row.BaseUnits),
			HasBatches: yes(row.IsBatchWiseOn),
		})
		return nil
	})
	if err != nil {
		t.Fatalf("walk: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("want 1 item, got %d", len(got))
	}
	if got[0].Name != "4190-9822 WIRED MEDIA CARD" {
		t.Errorf("name = %q", got[0].Name)
	}
	if got[0].BaseUnits != "NO" || !got[0].HasBatches {
		t.Errorf("item did not survive sanitising: %+v", got[0])
	}
}

func TestSanitiseKeepsOrdinaryContent(t *testing.T) {
	in := []byte(`<A>Bolt &amp; Nut &#233; 18 &lt;x&gt;</A>`)
	out := string(sanitiseTallyXML(in))
	for _, want := range []string{"&amp;", "&#233;", "&lt;", "&gt;", "Bolt", "18"} {
		if !strings.Contains(out, want) {
			t.Errorf("sanitising removed %q: %s", want, out)
		}
	}
}

func TestSanitiseStripsRawControlBytes(t *testing.T) {
	out := string(sanitiseTallyXML([]byte("<A>\x04 Primary\x1f</A>")))
	if strings.ContainsAny(out, "\x04\x1f") {
		t.Errorf("control bytes survived: %q", out)
	}
	if !strings.Contains(out, "Primary") {
		t.Errorf("content lost: %q", out)
	}
}
