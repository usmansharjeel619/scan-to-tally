package tally

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// One Physical Stock voucher per product, added to rather than repeated.
//
// The dangerous edge is that ACTION="Alter" without a MASTERID does not fail --
// Tally creates ANOTHER voucher, the counters say success, and the stock is
// doubled on the books until somebody counts a shelf weeks later. So the
// request has to carry the id, and the answer has to be read rather than
// assumed.

func physicalStockFor(masterID string) Voucher {
	v := NewPhysicalStock("sess#0", "Mobile scan", time.Now(), []InventoryEntry{{
		StockItemName: "4090-5201 MINI IAM",
		Qty:           Qty{Value: 70, Unit: "Nos"},
		Batches: []BatchAllocation{
			{BatchName: "HIA634", GodownName: "Main Location", Qty: Qty{Value: 35, Unit: "Nos"}},
			{BatchName: "HLA133", GodownName: "Main Location", Qty: Qty{Value: 35, Unit: "Nos"}},
		},
	}})
	v.AlterMasterID = masterID
	return v
}

func TestBuildImportCreatesWhenNoVoucherIsNamed(t *testing.T) {
	xml, err := BuildImport("ACME", physicalStockFor(""))
	if err != nil {
		t.Fatal(err)
	}
	got := string(xml)
	if !strings.Contains(got, `ACTION="Create"`) {
		t.Errorf("expected a create; got:\n%s", got)
	}
	if strings.Contains(got, "MASTERID") {
		t.Errorf("a create must not name a voucher to replace; got:\n%s", got)
	}
}

func TestBuildImportAltersTheVoucherItNames(t *testing.T) {
	xml, err := BuildImport("ACME", physicalStockFor("39"))
	if err != nil {
		t.Fatal(err)
	}
	got := string(xml)
	if !strings.Contains(got, `ACTION="Alter"`) {
		t.Errorf("expected an alter; got:\n%s", got)
	}
	// Without this Tally creates a second voucher and reports success.
	if !strings.Contains(got, "<MASTERID>39</MASTERID>") {
		t.Errorf("an alter must name the voucher it replaces; got:\n%s", got)
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

	_, err := c.Import(context.Background(), physicalStockFor("39"))
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

	_, err := c.Import(context.Background(), physicalStockFor("39"))
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

	res, err := c.Import(context.Background(), physicalStockFor("39"))
	if err != nil {
		t.Fatalf("a successful alter was reported as a failure: %v", err)
	}
	if res.LastVchID != "39" {
		t.Errorf("voucher id = %q, want the voucher that was altered", res.LastVchID)
	}
}
