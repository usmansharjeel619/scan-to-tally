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

	// Everything that consumes a sales order: a delivery note, or a sales
	// invoice raised directly against it.
	tdlOrderFulfilment = `<COLLECTION NAME="STT_OrderFulfilment" ISMODIFY="No">
  <TYPE>Voucher</TYPE>
  <FILTER>STT_IsDespatch</FILTER>
  <FETCH>VOUCHERNUMBER, VOUCHERTYPENAME</FETCH>
  <FETCH>ALLINVENTORYENTRIES.*</FETCH>
</COLLECTION>
<SYSTEM TYPE="Formulae" NAME="STT_IsDespatch">$VOUCHERTYPENAME = "Delivery Note" OR $VOUCHERTYPENAME = "Sales"</SYSTEM>`

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
	Companies       string `json:"companies,omitempty"`
	StockItems      string `json:"stockItems,omitempty"`
	BatchBalances   string `json:"batchBalances,omitempty"`
	SalesOrders     string `json:"salesOrders,omitempty"`
	OrderFulfilment string `json:"orderFulfilment,omitempty"`
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
	dec := xml.NewDecoder(bytes.NewReader(sanitiseTallyXML(body)))
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

// ListGodowns returns the stock locations defined in the company.
//
// Read separately rather than inferred from the balances, because a company
// with no stock yet still has godowns, and the balance report does not name
// them.
func (c *Client) ListGodowns(ctx context.Context) ([]string, error) {
	payload, err := buildExport(c.cfg.Company, "STT_Godowns",
		`<COLLECTION NAME="STT_Godowns" ISMODIFY="No">
  <TYPE>Godown</TYPE>
  <NATIVEMETHOD>NAME</NATIVEMETHOD>
</COLLECTION>`, nil, nil)
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

	var out []string
	err = walk(body, "GODOWN", func(d *xml.Decoder, se xml.StartElement) error {
		var row struct {
			Name string `xml:"NAME"`
		}
		if err := d.DecodeElement(&row, &se); err != nil {
			return nil
		}
		name := strings.TrimSpace(attr(se, "NAME"))
		if name == "" {
			name = strings.TrimSpace(row.Name)
		}
		if name != "" {
			out = append(out, name)
		}
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
//
// Read from the Stock Summary report rather than the StockItem collection,
// because the collection does not carry it. BATCHALLOCATIONS on a stock item
// master is its OPENING allocation, so it comes back empty for an item whose
// stock arrived by voucher -- which is every item here. Confirmed against the
// live company: two receipts posted, correct batches on both vouchers, and the
// collection still reported no batches at all. Everything downstream of this
// depends on it -- a despatch cannot be checked against a box whose balance is
// unknown, and the stock lookup showed zero for a product that had just been
// received.
func (c *Client) ListBatchBalances(ctx context.Context) ([]BatchBalance, error) {
	// The report does not name the location its figures belong to, so it is
	// asked a location at a time.
	//
	// It used to be asked once, and the answer attributed to the only godown
	// when there was exactly one -- and to NOTHING when there were several,
	// which left every balance with an unknown location. That is honest, but
	// downstream it means a despatch can never find the box it is looking at:
	// a company with 89 shelf locations had outgoing silently disabled by it.
	gs, err := c.ListGodowns(ctx)
	if err != nil || len(gs) == 0 {
		// No list to work from. One request, unattributed, is still better than
		// none -- it is what a single-location company would get anyway.
		return c.stockSummaryFor(ctx, "")
	}
	if len(gs) == 1 {
		return c.stockSummaryFor(ctx, gs[0])
	}

	var out []BatchBalance
	for _, g := range gs {
		part, err := c.stockSummaryFor(ctx, g)
		if err != nil {
			// One unreadable location must not lose the other 88. The gap shows
			// up as no stock THERE, which is visible, rather than as no stock
			// anywhere, which looks like the feature is broken.
			c.log.Warn("stock summary failed for a location", "godown", g, "err", err)
			continue
		}
		out = append(out, part...)
	}
	return out, nil
}

// stockSummaryFor reads the exploded stock summary for one location, or for
// every location at once when godown is empty.
func (c *Client) stockSummaryFor(ctx context.Context, godown string) ([]BatchBalance, error) {
	payload, err := buildStockSummary(c.cfg.Company, godown)
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

	out, err := parseStockSummary(body, godown, time.Now())
	if err != nil {
		return nil, business("PARSE_FAILED", err.Error())
	}
	return out, nil
}

// buildStockSummary asks for the stock summary exploded down to batches.
//
// EXPLODEFLAG is what adds the per-batch rows; without it the report stops at
// the item. No date window: a CLOSING balance is cumulative from the start of
// the books, so a from-date would only narrow the columns this does not read,
// and picking one risks falling outside a company's period.
func buildStockSummary(company, godown string) ([]byte, error) {
	env := exportEnvelope{
		Header: exportHeader{
			Version: 1, TallyRequest: "Export", Type: "Data", ID: "Stock Summary",
		},
		Body: exportBody{
			Desc: exportDesc{
				StaticVariables: staticVariables{
					CurrentCompany: company,
					ExportFormat:   "$$SysName:XML",
					ExplodeFlag:    "Yes",
					// Empty asks for every location at once, which is right for
					// a company that has only one.
					Godown: godown,
				},
			},
		},
	}

	var buf bytes.Buffer
	enc := xml.NewEncoder(&buf)
	enc.Indent("", " ")
	if err := enc.Encode(env); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// parseStockSummary reads the exploded report.
//
// The report is a FLAT stream, not a nested structure: an item name, then that
// item's total, then a batch name and that batch's total for each batch. So
// position carries the meaning, and a quantity belongs to the batch only if a
// batch name has been seen since the last one was consumed.
func parseStockSummary(body []byte, godown string, now time.Time) ([]BatchBalance, error) {
	dec := xml.NewDecoder(bytes.NewReader(sanitiseTallyXML(body)))
	dec.Strict = false
	// Same as walk: Tally emits cp1252 bytes in some locales, and one
	// accented name must not fail the whole sync.
	dec.CharsetReader = func(_ string, r io.Reader) (io.Reader, error) { return r, nil }

	var (
		out   []BatchBalance
		item  string
		batch string
	)

	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, err
		}
		se, ok := tok.(xml.StartElement)
		if !ok {
			continue
		}

		switch se.Name.Local {
		case "DSPDISPNAME":
			var v string
			if err := dec.DecodeElement(&v, &se); err != nil {
				continue
			}
			item = strings.TrimSpace(v)
			// A new item cancels any batch left over from the previous one.
			batch = ""

		case "SSBATCH":
			var v string
			if err := dec.DecodeElement(&v, &se); err != nil {
				continue
			}
			batch = strings.TrimSpace(v)

		case "DSPCLQTY":
			var v string
			if err := dec.DecodeElement(&v, &se); err != nil {
				continue
			}
			// No batch pending means this is the item's own total, which is the
			// sum of the batch rows that follow it. Counting it would double
			// every figure.
			if batch == "" || item == "" {
				continue
			}
			qty, unit := parseTallyQty(v)
			out = append(out, BatchBalance{
				StockItemName: item,
				BatchName:     batch,
				GodownName:    godown,
				ClosingQty:    qty,
				Unit:          unit,
				AsOf:          now,
			})
			batch = ""
		}
	}
	return out, nil
}

// ListOrderFulfilment reports how much has already gone out against each sales
// order, keyed by order number and then stock item.
//
// Tally does not put an outstanding figure on the order itself -- the order
// line only ever reports what was ordered -- so fulfilment has to be summed
// from the vouchers that reference it. Every despatch carries ORDERNO, which is
// how they link back.
//
// Without this, delivered was always zero and every fresh session saw the whole
// order still outstanding: two despatches against one order for four sent six.
func (c *Client) ListOrderFulfilment(
	ctx context.Context, from, to time.Time,
) (map[string]map[string]float64, error) {
	payload, err := buildExport(c.cfg.Company, "STT_OrderFulfilment",
		pick(c.cfg.TDL.OrderFulfilment, tdlOrderFulfilment), &from, &to)
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

	out := map[string]map[string]float64{}
	err = walk(body, "VOUCHER", func(d *xml.Decoder, se xml.StartElement) error {
		var row struct {
			Entries []struct {
				StockItemName string `xml:"STOCKITEMNAME"`
				ActualQty     string `xml:"ACTUALQTY"`
				OrderNo       string `xml:"ORDERNO"`
				Batches       []struct {
					OrderNo   string `xml:"ORDERNO"`
					ActualQty string `xml:"ACTUALQTY"`
				} `xml:"BATCHALLOCATIONS.LIST"`
			} `xml:"ALLINVENTORYENTRIES.LIST"`
		}
		if err := d.DecodeElement(&row, &se); err != nil {
			return nil
		}

		for _, e := range row.Entries {
			item := strings.TrimSpace(e.StockItemName)
			if item == "" {
				continue
			}
			// The order number sits on the entry, or on its batch allocations
			// when the despatch was made box by box -- which is how this app
			// makes every one of them.
			for _, b := range e.Batches {
				order := cleanOrderNo(b.OrderNo)
				if order == "" {
					continue
				}
				qty, _ := parseTallyQty(b.ActualQty)
				addFulfilment(out, order, item, qty)
			}
			if order := cleanOrderNo(e.OrderNo); order != "" && len(e.Batches) == 0 {
				qty, _ := parseTallyQty(e.ActualQty)
				addFulfilment(out, order, item, qty)
			}
		}
		return nil
	})
	if err != nil {
		return nil, business("PARSE_FAILED", err.Error())
	}
	return out, nil
}

// cleanOrderNo drops Tally's "not applicable" placeholder, which means the line
// is not against an order at all.
func cleanOrderNo(s string) string {
	v := strings.TrimSpace(s)
	if v == "" || strings.EqualFold(v, "Not Applicable") {
		return ""
	}
	return v
}

func addFulfilment(m map[string]map[string]float64, order, item string, qty float64) {
	if qty < 0 {
		qty = -qty // a despatch is negative in some voucher views
	}
	if qty == 0 {
		return
	}
	if m[order] == nil {
		m[order] = map[string]float64{}
	}
	m[order][item] += qty
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

// --- reading a voucher back -------------------------------------------------

// VoucherIdentity contains the identity and ownership fields read from Tally.
type VoucherIdentity struct {
	// RemoteID is retained as export metadata; it does not select an alteration.
	RemoteID string
	// VchKey is the VCHKEY attribute, kept for diagnosis.
	VchKey string
	// MasterID is Tally's internal id, which is what an import reports back as
	// LASTVCHID. It is how a voucher we posted is found again here.
	MasterID      string
	Date          string
	VoucherNumber string
	VoucherType   string
	// Narration carries our [STT:...] marker, which is how a voucher is
	// confirmed to be OURS before it is replaced.
	Narration string
}

// ListVoucherIdentities reads the day book and returns how Tally names each
// voucher in it.
//
// Deliberately narrow: it returns identities and narrations, not contents. The
// connector has no business pulling whole vouchers out of an accounting system
// and nothing here needs them.
func (c *Client) ListVoucherIdentities(ctx context.Context, from, to time.Time) ([]VoucherIdentity, error) {
	// Built through the encoder rather than by formatting a string, so a
	// company name carrying an ampersand cannot produce a broken document.
	env := exportEnvelope{
		Header: exportHeader{Version: 1, TallyRequest: "Export", Type: "Data", ID: "DayBook"},
		Body: exportBody{Desc: exportDesc{StaticVariables: staticVariables{
			CurrentCompany: c.cfg.Company,
			ExportFormat:   "$$SysName:XML",
			FromDate:       from.Format(dateFmt),
			ToDate:         to.Format(dateFmt),
		}}},
	}
	var buf bytes.Buffer
	enc := xml.NewEncoder(&buf)
	enc.Indent("", " ")
	if err := enc.Encode(env); err != nil {
		return nil, business("BUILD_FAILED", err.Error())
	}

	body, err := c.post(ctx, buf.Bytes())
	if err != nil {
		return nil, err
	}
	if le := extractTag(string(body), "LINEERROR"); le != "" {
		return nil, c.noteAppError(classifyMessage(le))
	}

	var out []VoucherIdentity
	err = walk(body, "VOUCHER", func(d *xml.Decoder, se xml.StartElement) error {
		var row struct {
			MasterID      string `xml:"MASTERID"`
			Date          string `xml:"DATE"`
			VoucherNumber string `xml:"VOUCHERNUMBER"`
			VoucherType   string `xml:"VOUCHERTYPENAME"`
			Narration     string `xml:"NARRATION"`
		}
		if err := d.DecodeElement(&row, &se); err != nil {
			return nil // skip an unreadable voucher rather than losing the batch
		}
		out = append(out, VoucherIdentity{
			RemoteID:      strings.TrimSpace(attr(se, "REMOTEID")),
			VchKey:        strings.TrimSpace(attr(se, "VCHKEY")),
			MasterID:      strings.TrimSpace(row.MasterID),
			Date:          strings.TrimSpace(row.Date),
			VoucherNumber: strings.TrimSpace(row.VoucherNumber),
			VoucherType:   strings.TrimSpace(row.VoucherType),
			Narration:     strings.TrimSpace(row.Narration),
		})
		return nil
	})
	if err != nil {
		return nil, business("PARSE_FAILED", err.Error())
	}
	return out, nil
}
