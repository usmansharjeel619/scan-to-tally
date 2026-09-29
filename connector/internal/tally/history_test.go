package tally

import "testing"

func TestHistoryRejectsIncompleteExports(t *testing.T) {
	for _, s := range []string{
		"", "<RESPONSE>Company closed</RESPONSE>",
		`<ENVELOPE><HEADER><STATUS>0</STATUS></HEADER><BODY><DATA><COLLECTION/></DATA></BODY></ENVELOPE>`,
		`<ENVELOPE><HEADER><STATUS>1</STATUS></HEADER></ENVELOPE>`,
		`<ENVELOPE><HEADER><STATUS>1</STATUS></HEADER><BODY><DATA><COLLECTION><VOUCHER>`,
		`<ENVELOPE><HEADER><STATUS>1</STATUS></HEADER><BODY><DATA><COLLECTION><VOUCHER/></COLLECTION></DATA></BODY></ENVELOPE>`,
	} {
		if _, err := parseVoucherHistory([]byte(s)); err == nil {
			t.Fatalf("accepted incomplete snapshot: %s", s)
		}
	}
}
func TestHistoryEmptyAndBatches(t *testing.T) {
	prefix := `<ENVELOPE><HEADER><STATUS>1</STATUS></HEADER><BODY><DATA><COLLECTION>`
	suffix := `</COLLECTION></DATA></BODY></ENVELOPE>`
	rows, err := parseVoucherHistory([]byte(prefix + suffix))
	if err != nil || rows == nil || len(rows) != 0 {
		t.Fatalf("empty snapshot: %v %v", rows, err)
	}
	rows, err = parseVoucherHistory([]byte(prefix + `<VOUCHER><MASTERID> 74</MASTERID><NARRATION>[STT:s#0]</NARRATION><ALLINVENTORYENTRIES.LIST><STOCKITEMNAME>ITEM</STOCKITEMNAME><BATCHALLOCATIONS.LIST><GODOWNNAME>Main</GODOWNNAME><BATCHNAME>BOX</BATCHNAME></BATCHALLOCATIONS.LIST></ALLINVENTORYENTRIES.LIST></VOUCHER>` + suffix))
	if err != nil || len(rows) != 1 || rows[0].MasterID != "74" || len(rows[0].Batches) != 1 || rows[0].Batches[0].Box != "BOX" {
		t.Fatalf("snapshot: %+v %v", rows, err)
	}
}
