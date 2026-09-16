package tally

import (
	"bytes"
	"context"
	"encoding/xml"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"
)

// TDL collection definitions.
//
// These are the least certain part of the whole system. Tally's TDL surface
// differs between ERP 9 and Prime releases and between company configurations,
// and no amount of care here substitutes for running them against the real
// installation (docs/PHASE0.md).
//
// They are therefore CONSTANTS WITH OVERRIDES: Config.TDL can replace any of
// them at runtime from the connector's config file, so a site can be corrected
// without recompiling and redeploying a Windows service.
const (
	tdlCompanies = `<COLLECTION NAME="STT_Companies" ISMODIFY="No">
  <TYPE>Company</TYPE>
  <NATIVEMETHOD>NAME</NATIVEMETHOD>
  <NATIVEMETHOD>STARTINGFROM</NATIVEMETHOD>
  <NATIVEMETHOD>GUID</NATIVEMETHOD>
</COLLECTION>`

	tdlStockItems = `<COLLECTION NAME="STT_StockItems" ISMODIFY="No">
  <TYPE>StockItem</TYPE>
  <NATIVEMETHOD>NAME</NATIVEMETHOD>
  <NATIVEMETHOD>PARENT</NATIVEMETHOD>
  <NATIVEMETHOD>BASEUNITS</NATIVEMETHOD>
  <NATIVEMETHOD>ISBATCHWISEON</NATIVEMETHOD>
  <NATIVEMETHOD>PARTNO</NATIVEMETHOD>
  <NATIVEMETHOD>GUID</NATIVEMETHOD>
  <FETCH>ALIASNAME</FETCH>
</COLLECTION>`

	tdlBatchBalances = `<COLLECTION NAME="STT_BatchBalances" ISMODIFY="No">
  <TYPE>StockItem</TYPE>
  <NATIVEMETHOD>NAME</NATIVEMETHOD>
  <NATIVEMETHOD>BASEUNITS</NATIVEMETHOD>
  <FETCH>BATCHALLOCATIONS.*</FETCH>
</COLLECTION>`

	tdlSalesOrders = `<COLLECTION NAME="STT_SalesOrders" ISMODIFY="No">
  <TYPE>Voucher</TYPE>
  <FILTER>STT_IsSalesOrder</FILTER>
  <FETCH>VOUCHERNUMBER, DATE, PARTYLEDGERNAME, REFERENCE, GUID</FETCH>
  <FETCH>ALLINVENTORYENTRIES.*</FETCH>
</COLLECTION>
<SYSTEM TYPE="Formulae" NAME="STT_IsSalesOrder">$VOUCHERTYPENAME = "Sales Order"</SYSTEM>`
)

// TDLOverrides lets a site correct a collection definition without a rebuild.
type TDLOverrides struct {
	Companies     string `json:"companies,omitempty"`
	StockItems    string `json:"stockItems,omitempty"`
	BatchBalances string `json:"batchBalances,omitempty"`
	SalesOrders   string `json:"salesOrders,omitempty"`
}

func pick(override, fallback string) string {
	if strings.TrimSpace(override) != "" {
		return override
	}
	return fallback
}

// --- request building -------------------------------------------------------

type exportEnvelope struct {
	XMLName xml.Name     `xml:"ENVELOPE"`
	Header  exportHeader `xml:"HEADER"`
	Body    exportBody   `xml:"BODY"`
}

type exportHeader struct {
	Version      int    `xml:"VERSION"`
	TallyRequest string `xml:"TALLYREQUEST"`
	Type         string `xml:"TYPE"`
	ID           string `xml:"ID"`
}

type exportBody struct {
	Desc exportDesc `xml:"DESC"`
}

type exportDesc struct {
	StaticVariables staticVariables `xml:"STATICVARIABLES"`
	TDL             innerXML        `xml:"TDL"`
}

// innerXML carries a TDL fragment through the encoder verbatim. The fragments
// are authored as TDL, not as Go structs, precisely so a site can paste a
// corrected one into the config file.
type innerXML struct {
	Raw string `xml:",innerxml"`
}

func buildExport(company, collection, tdl string, from, to *time.Time) ([]byte, error) {
	sv := staticVariables{
		CurrentCompany: company,
		ExportFormat:   "$$SysName:XML",
	}
	if from != nil {
		sv.FromDate = from.Format(dateFmt)
	}
	if to != nil {
		sv.ToDate = to.Format(dateFmt)
	}

	env := exportEnvelope{
		Header: exportHeader{
			Version:      1,
			TallyRequest: "Export",
			Type:         "Collection",
			ID:           collection,
		},
		Body: exportBody{
			Desc: exportDesc{
				StaticVariables: sv,
				TDL:             innerXML{Raw: "<TDLMESSAGE>" + tdl + "</TDLMESSAGE>"},
			},
		},
	}

	var buf bytes.Buffer
	enc := xml.NewEncoder(&buf)
	enc.Indent("", " ")
	if err := enc.Encode(env); err != nil {
		return nil, fmt.Errorf("encode export envelope: %w", err)
	}
	return buf.Bytes(), nil
}

// --- tolerant response walking ----------------------------------------------

// walk finds every element named tag at ANY depth and hands its subtree to fn.
//
// Tally wraps collection results differently depending on version and export
// format -- sometimes <ENVELOPE><STOCKITEM>, sometimes
// <ENVELOPE><BODY><DATA><COLLECTION><STOCKITEM>. Searching by name instead of
// asserting a path means a schema change in the wrapper cannot break us, only
// a change to the elements we actually read.
func walk(body []byte, tag string, fn func(d *xml.Decoder, start xml.StartElement) error) error {
	dec := xml.NewDecoder(bytes.NewReader(body))
	// Tally emits cp1252/latin-1 bytes in some locales; pass them through
	// rather than failing the whole sync on one accented party name.
	dec.CharsetReader = func(_ string, r io.Reader) (io.Reader, error) { return r, nil }
	dec.Strict = false

	for {
		tok, err := dec.Token()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return fmt.Errorf("walk %s: %w", tag, err)
		}
		if se, ok := tok.(xml.StartElement); ok && strings.EqualFold(se.Name.Local, tag) {
			if err := fn(dec, se); err != nil {
				return err
			}
		}
	}
}

func attr(se xml.StartElement, name string) string {
	for _, a := range se.Attr {
		if strings.EqualFold(a.Name.Local, name) {
			return a.Value
		}
	}
	return ""
}

// parseTallyQty reads "18 Nos", "18.000 Nos", "-4 Nos" or a bare number.
// Tally also writes compound quantities like "2 Box of 24 Nos"; we take the
// leading number and the first unit token, and the caller reconciles against
// the item master.
func parseTallyQty(s string) (float64, string) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, ""
	}
	i := 0
	for i < len(s) && (s[i] == '-' || s[i] == '+' || s[i] == '.' || (s[i] >= '0' && s[i] <= '9')) {
		i++
	}
	val, err := strconv.ParseFloat(strings.TrimSpace(s[:i]), 64)
	if err != nil {
		return 0, strings.TrimSpace(s)
	}
	return val, strings.TrimSpace(s[i:])
}

func parseTallyDate(s string) *time.Time {
	s = strings.TrimSpace(s)
	for _, layout := range []string{"20060102", "2-Jan-2006", "2-Jan-06", "2006-01-02"} {
		if t, err := time.Parse(layout, s); err == nil {
			return &t
		}
	}
	return nil
}

func yes(s string) bool {
	s = strings.TrimSpace(strings.ToLower(s))
	return s == "yes" || s == "1" || s == "true"
}

// --- queries ----------------------------------------------------------------

// ListCompanies returns the companies open in the running Tally instance.
// It doubles as the health probe: it is cheap and it fails in exactly the ways
// we care about (not running, company not open).
func (c *Client) ListCompanies(ctx context.Context) ([]Company, error) {
	payload, err := buildExport("", "STT_Companies", pick(c.cfg.TDL.Companies, tdlCompanies), nil, nil)
	if err != nil {
		return nil, business("BUILD_FAILED", err.Error())
	}
	body, err := c.post(ctx, payload)
	if err != nil {
		return nil, err
	}
	if le := extractTag(string(body), "LINEERROR"); le != "" {
		return nil, c.noteAppError(classifyMessage(le))
	}

	var out []Company
	err = walk(body, "COMPANY", func(d *xml.Decoder, se xml.StartElement) error {
		var row struct {
			Name         string `xml:"NAME"`
			StartingFrom string `xml:"STARTINGFROM"`
			GUID         string `xml:"GUID"`
		}
		if err := d.DecodeElement(&row, &se); err != nil {
			return nil // skip an unreadable row rather than losing the batch
		}
		name := strings.TrimSpace(attr(se, "NAME"))
		if name == "" {
			name = strings.TrimSpace(row.Name)
		}
		if name == "" {
			return nil
		}
		co := Company{Name: name, GUID: strings.TrimSpace(row.GUID)}
		if d := parseTallyDate(row.StartingFrom); d != nil {
			co.StartFrom = *d
		}
		out = append(out, co)
		return nil
	})
	if err != nil {
		return nil, business("PARSE_FAILED", err.Error())
	}
	if len(out) == 0 {
		// Tally answered but has nothing loaded. Transient: somebody will open
		// the company, and the queue should keep waiting rather than fail.
		return nil, c.noteAppError(transient("COMPANY_NOT_OPEN",
			"Tally is running but no company is open.", nil))
	}
	return out, nil
}

// ListStockItems returns the stock item master.
//
// The relay caches this and syncs it to devices, which is what makes PID
// resolution and unit handling work with no signal at the dock.
func (c *Client) ListStockItems(ctx context.Context) ([]StockItem, error) {
	payload, err := buildExport(c.cfg.Company, "STT_StockItems",
		pick(c.cfg.TDL.StockItems, tdlStockItems), nil, nil)
	if err != nil {
		return nil, business("BUILD_FAILED", err.Error())
	}
	body, err := c.post(ctx, payload)
	if err != nil {
		return nil, err
	}
	if le := extractTag(string(body), "LINEERROR"); le != "" {
		return nil, c.noteAppError(classifyMessage(le))
	}

	var out []StockItem
	err = walk(body, "STOCKITEM", func(d *xml.Decoder, se xml.StartElement) error {
		var row struct {
			Name          string `xml:"NAME"`
			BaseUnits     string `xml:"BASEUNITS"`
			PartNo        string `xml:"PARTNO"`
			GUID          string `xml:"GUID"`
			IsBatchWiseOn string `xml:"ISBATCHWISEON"`
			AliasName     string `xml:"LANGUAGENAME>LANGUAGEALIAS>NAME"`
		}
		if err := d.DecodeElement(&row, &se); err != nil {
			return nil
		}
		name := strings.TrimSpace(attr(se, "NAME"))
		if name == "" {
			name = strings.TrimSpace(row.Name)
		}
		if name == "" {
			return nil
		}
		out = append(out, StockItem{
			Name:       name,
			Alias:      strings.TrimSpace(row.AliasName),
			PartNo:     strings.TrimSpace(row.PartNo),
			BaseUnits:  strings.TrimSpace(row.BaseUnits),
			GUID:       strings.TrimSpace(row.GUID),
			HasBatches: yes(row.IsBatchWiseOn),
		})
		return nil
	})
	if err != nil {
		return nil, business("PARSE_FAILED", err.Error())
	}
	return out, nil
}

// ListBatchBalances returns closing stock per (item, batch, godown).
//
// This is the hard ceiling for outgoing quantity. It is NOT the printed box
// quantity: a box that shipped 5 of 18 last week has 13 left while its label
// still says 18.
func (c *Client) ListBatchBalances(ctx context.Context) ([]BatchBalance, error) {
	payload, err := buildExport(c.cfg.Company, "STT_BatchBalances",
		pick(c.cfg.TDL.BatchBalances, tdlBatchBalances), nil, nil)
	if err != nil {
		return nil, business("BUILD_FAILED", err.Error())
	}
	body, err := c.post(ctx, payload)
	if err != nil {
		return nil, err
	}
	if le := extractTag(string(body), "LINEERROR"); le != "" {
		return nil, c.noteAppError(classifyMessage(le))
	}

	now := time.Now()
	var out []BatchBalance
	err = walk(body, "STOCKITEM", func(d *xml.Decoder, se xml.StartElement) error {
		var row struct {
			Name      string `xml:"NAME"`
			BaseUnits string `xml:"BASEUNITS"`
			Batches   []struct {
				BatchName      string `xml:"BATCHNAME"`
				GodownName     string `xml:"GODOWNNAME"`
				ClosingBalance string `xml:"CLOSINGBALANCE"`
				ClosingQty     string `xml:"CLOSINGQTY"`
				ActualQty      string `xml:"ACTUALQTY"`
			} `xml:"BATCHALLOCATIONS.LIST"`
		}
		if err := d.DecodeElement(&row, &se); err != nil {
			return nil
		}
		item := strings.TrimSpace(attr(se, "NAME"))
		if item == "" {
			item = strings.TrimSpace(row.Name)
		}
		for _, b := range row.Batches {
			name := strings.TrimSpace(b.BatchName)
			if name == "" {
				continue
			}
			// Tally names the balance field differently across versions; take
			// whichever is populated rather than betting on one.
			raw := firstNonEmpty(b.ClosingBalance, b.ClosingQty, b.ActualQty)
			qty, unit := parseTallyQty(raw)
			if unit == "" {
				unit = strings.TrimSpace(row.BaseUnits)
			}
			out = append(out, BatchBalance{
				StockItemName: item,
				BatchName:     name,
				GodownName:    strings.TrimSpace(b.GodownName),
				ClosingQty:    qty,
				Unit:          unit,
				AsOf:          now,
			})
		}
		return nil
	})
	if err != nil {
		return nil, business("PARSE_FAILED", err.Error())
	}
	return out, nil
}

// ListSalesOrders returns sales orders in the window, with delivered quantities
// so the device can show and enforce what is still outstanding.
func (c *Client) ListSalesOrders(ctx context.Context, from, to time.Time) ([]SalesOrder, error) {
	payload, err := buildExport(c.cfg.Company, "STT_SalesOrders",
		pick(c.cfg.TDL.SalesOrders, tdlSalesOrders), &from, &to)
	if err != nil {
		return nil, business("BUILD_FAILED", err.Error())
	}
	body, err := c.post(ctx, payload)
	if err != nil {
		return nil, err
	}
	if le := extractTag(string(body), "LINEERROR"); le != "" {
		return nil, c.noteAppError(classifyMessage(le))
	}

	var out []SalesOrder
	err = walk(body, "VOUCHER", func(d *xml.Decoder, se xml.StartElement) error {
		var row struct {
			VoucherNumber   string `xml:"VOUCHERNUMBER"`
			Date            string `xml:"DATE"`
			PartyLedgerName string `xml:"PARTYLEDGERNAME"`
			Reference       string `xml:"REFERENCE"`
			GUID            string `xml:"GUID"`
			Entries         []struct {
				StockItemName string `xml:"STOCKITEMNAME"`
				ActualQty     string `xml:"ACTUALQTY"`
				BilledQty     string `xml:"BILLEDQTY"`
			} `xml:"ALLINVENTORYENTRIES.LIST"`
		}
		if err := d.DecodeElement(&row, &se); err != nil {
			return nil
		}
		if strings.TrimSpace(row.VoucherNumber) == "" {
			return nil
		}

		so := SalesOrder{
			VoucherNumber: strings.TrimSpace(row.VoucherNumber),
			OrderRef:      strings.TrimSpace(row.Reference),
			PartyName:     strings.TrimSpace(row.PartyLedgerName),
			GUID:          strings.TrimSpace(row.GUID),
		}
		if dt := parseTallyDate(row.Date); dt != nil {
			so.Date = *dt
		}
		for _, e := range row.Entries {
			item := strings.TrimSpace(e.StockItemName)
			if item == "" {
				continue
			}
			ordered, unit := parseTallyQty(firstNonEmpty(e.ActualQty, e.BilledQty))
			if ordered < 0 {
				ordered = -ordered
			}
			so.Lines = append(so.Lines, SalesOrderLine{
				StockItemName: item,
				OrderedQty:    ordered,
				Unit:          unit,
			})
		}
		if len(so.Lines) > 0 {
			out = append(out, so)
		}
		return nil
	})
	if err != nil {
		return nil, business("PARSE_FAILED", err.Error())
	}
	return out, nil
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
