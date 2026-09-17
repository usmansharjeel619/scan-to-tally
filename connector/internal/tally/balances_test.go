package tally

import (
	"testing"
	"time"
)

// The exact bytes the live company returned, which is the only reason this
// parser is shaped the way it is: a flat stream where position carries meaning.
const liveStockSummary = `<ENVELOPE>
 <DSPACCNAME>
  <DSPDISPNAME>4098-9789 LIFEALARM SENS BASE W/LED OUT</DSPDISPNAME>
</DSPACCNAME>
 <DSPSTKINFO>
  <DSPSTKCL>
   <DSPCLQTY>18 NO</DSPCLQTY>
   <DSPCLRATE></DSPCLRATE>
   <DSPCLAMTA></DSPCLAMTA>
</DSPSTKCL>
</DSPSTKINFO>
 <SSBATCHNAME>
  <SSBATCH>0725252247422272</SSBATCH>
</SSBATCHNAME>
 <DSPSTKINFO>
  <DSPSTKCL>
   <DSPCLQTY>18 NO</DSPCLQTY>
</DSPSTKCL>
</DSPSTKINFO>
 <DSPACCNAME>
  <DSPDISPNAME>4190-9822 WIRED MEDIA CARD</DSPDISPNAME>
</DSPACCNAME>
 <DSPSTKINFO>
  <DSPSTKCL>
   <DSPCLQTY>1 NO</DSPCLQTY>
</DSPSTKCL>
</DSPSTKINFO>
 <SSBATCHNAME>
  <SSBATCH>0403252250597164</SSBATCH>
</SSBATCHNAME>
 <DSPSTKINFO>
  <DSPSTKCL>
   <DSPCLQTY>1 NO</DSPCLQTY>
</DSPSTKCL>
</DSPSTKINFO>
</ENVELOPE>`

func TestParseStockSummaryReadsBatchRows(t *testing.T) {
	got, err := parseStockSummary([]byte(liveStockSummary), "Main Location", time.Now())
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("want 2 batch balances, got %d: %+v", len(got), got)
	}

	// The item total that precedes each batch must NOT be counted: doing so
	// doubles every figure and would let twice the stock be despatched.
	for _, b := range got {
		if b.BatchName == "" {
			t.Fatalf("a row with no batch name got through: %+v", b)
		}
		if b.GodownName != "Main Location" {
			t.Errorf("godown = %q, want Main Location", b.GodownName)
		}
		if b.Unit != "NO" {
			t.Errorf("unit = %q, want NO", b.Unit)
		}
	}

	byBatch := map[string]float64{}
	for _, b := range got {
		byBatch[b.BatchName] = b.ClosingQty
	}
	if byBatch["0725252247422272"] != 18 {
		t.Errorf("box ...2272 = %v, want 18", byBatch["0725252247422272"])
	}
	if byBatch["0403252250597164"] != 1 {
		t.Errorf("box ...7164 = %v, want 1", byBatch["0403252250597164"])
	}

	if got[0].StockItemName != "4098-9789 LIFEALARM SENS BASE W/LED OUT" {
		t.Errorf("item = %q", got[0].StockItemName)
	}
	if got[1].StockItemName != "4190-9822 WIRED MEDIA CARD" {
		t.Errorf("item = %q", got[1].StockItemName)
	}
}

// An item with no batches must contribute nothing rather than a phantom row,
// or a despatch could be checked against a batch that does not exist.
func TestParseStockSummarySkipsItemsWithoutBatches(t *testing.T) {
	const noBatches = `<ENVELOPE>
 <DSPACCNAME><DSPDISPNAME>PLAIN ITEM</DSPDISPNAME></DSPACCNAME>
 <DSPSTKINFO><DSPSTKCL><DSPCLQTY>42 NO</DSPCLQTY></DSPSTKCL></DSPSTKINFO>
</ENVELOPE>`
	got, err := parseStockSummary([]byte(noBatches), "Main Location", time.Now())
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(got) != 0 {
		t.Fatalf("want nothing, got %+v", got)
	}
}
