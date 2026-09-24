package tally

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// The documented master-ID selector must be present on every alteration.
func physicalStockFor(remoteID string, alter bool) Voucher {
	v := NewPhysicalStock("sess#0", "Mobile scan", time.Now(), []InventoryEntry{{
		StockItemName: "4090-5201 MINI IAM",
		Qty:           Qty{Value: 70, Unit: "Nos"},
		Batches: []BatchAllocation{
			{BatchName: "HIA634", GodownName: "Main Location", Qty: Qty{Value: 35, Unit: "Nos"}},
			{BatchName: "HLA133", GodownName: "Main Location", Qty: Qty{Value: 35, Unit: "Nos"}},
		},
	}})
	v.RemoteID = remoteID
	v.Alter = alter
	if alter {
		v.AlterMasterID = "39"
		v.AlterDate = time.Date(2026, 9, 22, 0, 0, 0, 0, time.UTC)
	}
	return v
}

func TestBuildImportCreatesWhenNotAltering(t *testing.T) {
	xml, err := BuildImport("ACME", physicalStockFor("STT-sess-0", false))
	if err != nil {
		t.Fatal(err)
	}
	got := string(xml)
	if !strings.Contains(got, `ACTION="Create"`) {
		t.Errorf("expected a create; got:\n%s", got)
	}
	// Create preserves caller metadata without an alteration selector.
	if strings.Contains(got, "TAGNAME=") || strings.Contains(got, "TAGVALUE=") {
		t.Fatal("create has alteration selector")
	}
	if !strings.Contains(got, `REMOTEID="STT-sess-0"`) {
		t.Errorf("a create must still name itself for later; got:\n%s", got)
	}
}

func TestBuildImportAltersTheVoucherItNames(t *testing.T) {
	xml, err := BuildImport("ACME", physicalStockFor("STT-sess-0", true))
	if err != nil {
		t.Fatal(err)
	}
	got := string(xml)
	if !strings.Contains(got, `ACTION="Alter"`) {
		t.Errorf("expected an alter; got:\n%s", got)
	}
	for _, want := range []string{`TAGNAME="MASTER ID"`, `TAGVALUE="39"`, `DATE="22-Sep-2026"`} {
		if !strings.Contains(got, want) {
			t.Errorf("missing selector %s: %s", want, got)
		}
	}
	// Every box the voucher should end up holding, because Tally replaces.
	for _, batch := range []string{"HIA634", "HLA133"} {
		if !strings.Contains(got, batch) {
			t.Errorf("batch %s missing; an alter that omits it deletes it from the books", batch)
		}
	}
}

func clientAnswering(t *testing.T, body string) *Client {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/xml")
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return NewClient(Config{BaseURL: srv.URL, Company: "ACME", Timeout: 5 * time.Second}, nil)
}

func TestAlterThatCreatedAVoucherIsRefused(t *testing.T) {
	// The exact shape of the disaster: Tally reports a perfectly successful
	// CREATE in response to an alter. Stock is now doubled.
	c := clientAnswering(t, `<RESPONSE>
 <CREATED>1</CREATED><ALTERED>0</ALTERED><DELETED>0</DELETED>
 <LASTVCHID>43</LASTVCHID><ERRORS>0</ERRORS></RESPONSE>`)

	_, err := c.Import(context.Background(), physicalStockFor("STT-sess-0", true))
	if err == nil {
		t.Fatal("an alter that created a second voucher must not be reported as success")
	}
	if !strings.Contains(err.Error(), "ALTER_BECAME_CREATE") {
		t.Errorf("wrong error: %v", err)
	}
}

func TestAlterOfAVoucherThatIsGoneIsNamedAsSuch(t *testing.T) {
	c := clientAnswering(t, `<RESPONSE>
 <CREATED>0</CREATED><ALTERED>0</ALTERED><DELETED>0</DELETED>
 <LASTVCHID>0</LASTVCHID><IGNORED>1</IGNORED><ERRORS>0</ERRORS></RESPONSE>`)

	_, err := c.Import(context.Background(), physicalStockFor("STT-sess-0", true))
	if err == nil {
		t.Fatal("expected the missing voucher to be reported")
	}
	// The relay keys its recovery on this code: it forgets the stale id and
	// raises a fresh voucher rather than failing against a ghost for ever.
	if !strings.Contains(err.Error(), "ALTER_TARGET_MISSING") {
		t.Errorf("wrong error: %v", err)
	}
}

func TestAlterThatAlteredIsSuccess(t *testing.T) {
	c := clientAnswering(t, `<RESPONSE>
 <CREATED>0</CREATED><ALTERED>1</ALTERED><DELETED>0</DELETED>
 <LASTVCHID>39</LASTVCHID><ERRORS>0</ERRORS></RESPONSE>`)

	res, err := c.Import(context.Background(), physicalStockFor("STT-sess-0", true))
	if err != nil {
		t.Fatalf("a successful alter was reported as a failure: %v", err)
	}
	if res.LastVchID != "39" {
		t.Errorf("voucher id = %q, want the voucher that was altered", res.LastVchID)
	}
}

func TestAlterWithoutVerifiedIdentityDoesNotSend(t *testing.T) {
	for _, id := range []string{"", "0", "-1", "REMOTE-ID"} {
		v := physicalStockFor("exported-remote-id", true)
		v.AlterMasterID = id
		if _, err := BuildImport("ACME", v); err == nil {
			t.Errorf("accepted invalid target %q", id)
		}
	}
	v := physicalStockFor("exported-remote-id", true)
	v.AlterDate = time.Time{}
	if _, err := BuildImport("ACME", v); err == nil {
		t.Fatal("accepted missing target date")
	}
}
