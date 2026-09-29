package tally

import (
	"context"
	"encoding/xml"
	"fmt"
	"strings"
	"time"
)

// VoucherHistory is an authoritative export used only after a successful,
// complete collection response. Absence in a failed export is never deletion.
type VoucherHistory struct {
	MasterID  string         `json:"masterId"`
	Narration string         `json:"narration"`
	Batches   []HistoryBatch `json:"batches"`
}
type HistoryBatch struct {
	Item   string `json:"item"`
	Godown string `json:"godown"`
	Box    string `json:"box"`
}

const historyTDL = `<COLLECTION NAME="STT_VoucherHistory" ISMODIFY="No">
<TYPE>Voucher</TYPE>
<FETCH>MASTERID,NARRATION,DATE,VOUCHERTYPENAME</FETCH>
<FETCH>ALLINVENTORYENTRIES.*</FETCH>
</COLLECTION>`

func (c *Client) ListVoucherHistory(ctx context.Context) ([]VoucherHistory, error) {
	if err := c.Probe(ctx); err != nil {
		return nil, err
	}
	from := time.Date(1900, 1, 1, 0, 0, 0, 0, time.UTC)
	to := time.Date(2099, 12, 31, 0, 0, 0, 0, time.UTC)
	request, err := buildExport(c.Company(), "STT_VoucherHistory", historyTDL, &from, &to)
	if err != nil {
		return nil, err
	}
	body, err := c.post(ctx, request)
	if err != nil {
		return nil, err
	}
	result, err := parseVoucherHistory(body)
	if err != nil {
		return nil, err
	}
	// The company must still be available after the export as well.
	if err = c.Probe(ctx); err != nil {
		return nil, err
	}
	return result, nil
}

func parseVoucherHistory(body []byte) ([]VoucherHistory, error) {
	type batch struct {
		Godown string `xml:"GODOWNNAME"`
		Box    string `xml:"BATCHNAME"`
	}
	type entry struct {
		Item    string  `xml:"STOCKITEMNAME"`
		Batches []batch `xml:"BATCHALLOCATIONS.LIST"`
	}
	type voucher struct {
		MasterID  string  `xml:"MASTERID"`
		Narration string  `xml:"NARRATION"`
		Entries   []entry `xml:"ALLINVENTORYENTRIES.LIST"`
		Inventory []entry `xml:"INVENTORYENTRIES.LIST"`
	}
	var env struct {
		XMLName    xml.Name `xml:"ENVELOPE"`
		Status     int      `xml:"HEADER>STATUS"`
		Collection *struct {
			Vouchers []voucher `xml:"VOUCHER"`
		} `xml:"BODY>DATA>COLLECTION"`
	}
	if err := xml.Unmarshal(sanitiseTallyXML(body), &env); err != nil {
		return nil, fmt.Errorf("history export malformed: %w", err)
	}
	if env.Status != 1 || env.Collection == nil || strings.Contains(string(body), "<LINEERROR>") {
		return nil, fmt.Errorf("history export incomplete or unsuccessful; retaining previous history")
	}
	out := []VoucherHistory{}
	seen := map[string]bool{}
	for _, v := range env.Collection.Vouchers {
		id := strings.TrimSpace(v.MasterID)
		if id == "" || seen[id] {
			return nil, fmt.Errorf("history export has missing or duplicate master ID")
		}
		seen[id] = true
		h := VoucherHistory{MasterID: id, Narration: strings.TrimSpace(v.Narration), Batches: []HistoryBatch{}}
		for _, e := range append(v.Entries, v.Inventory...) {
			if strings.TrimSpace(e.Item) == "" {
				return nil, fmt.Errorf("history export has unnamed inventory entry")
			}
			for _, b := range e.Batches {
				if strings.TrimSpace(b.Box) == "" || strings.TrimSpace(b.Godown) == "" {
					return nil, fmt.Errorf("history export has incomplete batch")
				}
				h.Batches = append(h.Batches, HistoryBatch{strings.TrimSpace(e.Item), strings.TrimSpace(b.Godown), strings.TrimSpace(b.Box)})
			}
		}
		out = append(out, h)
	}
	return out, nil
}
