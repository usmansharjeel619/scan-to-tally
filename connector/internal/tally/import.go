package tally

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"strconv"
	"time"
)

// dateFmt is Tally's wire format for dates.
const dateFmt = "20060102"

// --- wire structs -----------------------------------------------------------
//
// These are marshalled rather than string-templated so that item names,
// narrations and party names containing &, < or ' are escaped correctly.
// Tally rejects the whole envelope on a single malformed character, and an
// apostrophe in a party name is not a hypothetical.

type importEnvelope struct {
	XMLName xml.Name     `xml:"ENVELOPE"`
	Header  importHeader `xml:"HEADER"`
	Body    importBody   `xml:"BODY"`
}

type importHeader struct {
	TallyRequest string `xml:"TALLYREQUEST"`
}

type importBody struct {
	ImportData importData `xml:"IMPORTDATA"`
}

type importData struct {
	RequestDesc requestDesc `xml:"REQUESTDESC"`
	RequestData requestData `xml:"REQUESTDATA"`
}

type requestDesc struct {
	ReportName      string          `xml:"REPORTNAME"`
	StaticVariables staticVariables `xml:"STATICVARIABLES"`
}

type staticVariables struct {
	CurrentCompany string `xml:"SVCURRENTCOMPANY,omitempty"`
	ExportFormat   string `xml:"SVEXPORTFORMAT,omitempty"`
	FromDate       string `xml:"SVFROMDATE,omitempty"`
	ToDate         string `xml:"SVTODATE,omitempty"`
	// Explodes the stock summary down to its batch rows. Without it the report
	// stops at the item, which is the figure we do NOT want.
	ExplodeFlag string `xml:"EXPLODEFLAG,omitempty"`
	// Narrows a report to one location. The stock summary does not name the
	// godown its figures belong to, so the only way to know is to ask a
	// location at a time.
	Godown string `xml:"SVGODOWN,omitempty"`
}

type requestData struct {
	TallyMessage tallyMessage `xml:"TALLYMESSAGE"`
}

type tallyMessage struct {
	UDFNamespace string       `xml:"xmlns:UDF,attr"`
	Voucher      *wireVoucher `xml:"VOUCHER,omitempty"`
}

type wireVoucher struct {
	VchType string `xml:"VCHTYPE,attr"`
	Action  string `xml:"ACTION,attr"`

	// Tally's alteration selector is an attribute pair, not a MASTERID child
	// or REMOTEID. See https://help.tallysolutions.com/scenario-2/.
	TagName    string `xml:"TAGNAME,attr,omitempty"`
	TagValue   string `xml:"TAGVALUE,attr,omitempty"`
	TargetDate string `xml:"DATE,attr,omitempty"`
	RemoteID   string `xml:"REMOTEID,attr,omitempty"`

	Date            string `xml:"DATE"`
	EffectiveDate   string `xml:"EFFECTIVEDATE,omitempty"`
	VoucherTypeName string `xml:"VOUCHERTYPENAME"`
	VoucherNumber   string `xml:"VOUCHERNUMBER,omitempty"`
	Reference       string `xml:"REFERENCE,omitempty"`
	ReferenceDate   string `xml:"REFERENCEDATE,omitempty"`
	Narration       string `xml:"NARRATION,omitempty"`
	PartyLedgerName string `xml:"PARTYLEDGERNAME,omitempty"`

	// PersistedView tells Tally which entry screen the voucher belongs to.
	// Omitting it on an inventory voucher can make Tally interpret the lines
	// as accounting entries.
	PersistedView string `xml:"PERSISTEDVIEW,omitempty"`

	Entries []wireInventoryEntry `xml:"ALLINVENTORYENTRIES.LIST"`
}

// wireUserDescription is the nested wrapper Tally actually uses.
// Confirmed against a real Sales Invoice: the description sits inside
// <BASICUSERDESCRIPTION.LIST>, not as a direct child of the entry. A flat
// element is accepted and then silently dropped.
type wireUserDescription struct {
	Text string `xml:"BASICUSERDESCRIPTION"`
}

type wireInventoryEntry struct {
	StockItemName    string               `xml:"STOCKITEMNAME"`
	IsDeemedPositive string               `xml:"ISDEEMEDPOSITIVE"`
	Description      *wireUserDescription `xml:"BASICUSERDESCRIPTION.LIST,omitempty"`
	Rate             string               `xml:"RATE,omitempty"`
	Amount           string               `xml:"AMOUNT,omitempty"`
	ActualQty        string               `xml:"ACTUALQTY"`
	BilledQty        string               `xml:"BILLEDQTY"`
	Batches          []wireBatch          `xml:"BATCHALLOCATIONS.LIST"`
}

type wireBatch struct {
	GodownName     string `xml:"GODOWNNAME,omitempty"`
	BatchName      string `xml:"BATCHNAME"`
	MfdOn          string `xml:"MFDON,omitempty"`
	OrderNo        string `xml:"ORDERNO,omitempty"`
	TrackingNumber string `xml:"TRACKINGNUMBER,omitempty"`
	ActualQty      string `xml:"ACTUALQTY"`
	BilledQty      string `xml:"BILLEDQTY"`
	Amount         string `xml:"AMOUNT,omitempty"`
}

// --- builder ----------------------------------------------------------------

// BuildImport renders a voucher as a Tally "Import Data" envelope.
//
// company must be spelled exactly as Tally has it; it is passed through
// SVCURRENTCOMPANY, which is how a single connector serves several companies
// from one running Tally.
func BuildImport(company string, v Voucher) ([]byte, error) {
	if v.Alter {
		id, err := strconv.ParseUint(v.AlterMasterID, 10, 64)
		if err != nil || id == 0 || v.AlterDate.IsZero() {
			return nil, fmt.Errorf("alter requires a positive master ID and original voucher date")
		}
	}
	if err := v.Validate(); err != nil {
		return nil, fmt.Errorf("invalid voucher: %w", err)
	}

	// Incoming adds stock, outgoing removes it. VERIFY this polarity against a
	// hand-keyed export -- a flipped sign posts the movement backwards and is
	// not obvious in the response, only in the stock report a week later.
	deemedPositive := "No"
	if v.Type == ReceiptNote || v.Type == Purchase || v.Type == PhysicalStock {
		deemedPositive = "Yes"
	}

	entries := make([]wireInventoryEntry, 0, len(v.Entries))
	for _, e := range v.Entries {
		batches := make([]wireBatch, 0, len(e.Batches))
		for _, b := range e.Batches {
			wb := wireBatch{
				GodownName:     b.GodownName,
				BatchName:      b.BatchName,
				OrderNo:        b.OrderNo,
				TrackingNumber: b.TrackingNumber,
				ActualQty:      b.Qty.String(),
				BilledQty:      b.Qty.String(),
			}
			if b.MfgDate != nil {
				wb.MfdOn = b.MfgDate.Format(dateFmt)
			}
			batches = append(batches, wb)
		}

		we := wireInventoryEntry{
			StockItemName:    e.StockItemName,
			IsDeemedPositive: deemedPositive,
			ActualQty:        e.Qty.String(),
			BilledQty:        e.Qty.String(),
			Batches:          batches,
		}
		if e.Description != "" {
			we.Description = &wireUserDescription{Text: e.Description}
		}
		if e.Rate != nil {
			we.Rate = fmt.Sprintf("%g/%s", *e.Rate, e.Qty.Unit)
		}
		if e.Amount != nil {
			we.Amount = fmt.Sprintf("%g", *e.Amount)
		}
		entries = append(entries, we)
	}

	action := "Create"
	tagName, tagValue, targetDate := "", "", ""
	if v.Alter {
		action = "Alter"
		tagName, tagValue = "MASTER ID", v.AlterMasterID
		targetDate = v.AlterDate.Format("02-Jan-2006")
	}

	env := importEnvelope{
		Header: importHeader{TallyRequest: "Import Data"},
		Body: importBody{
			ImportData: importData{
				RequestDesc: requestDesc{
					ReportName:      "Vouchers",
					StaticVariables: staticVariables{CurrentCompany: company},
				},
				RequestData: requestData{
					TallyMessage: tallyMessage{
						UDFNamespace: "TallyUDF",
						Voucher: &wireVoucher{
							VchType: string(v.Type),
							// Altering keeps ONE voucher per product and adds
							// the new cartons to it. Entries already carry the
							// boxes it had, because Tally replaces rather than
							// merges.
							Action:          action,
							TagName:         tagName,
							TagValue:        tagValue,
							TargetDate:      targetDate,
							RemoteID:        v.RemoteID,
							Date:            v.Date.Format(dateFmt),
							EffectiveDate:   v.Date.Format(dateFmt),
							VoucherTypeName: string(v.Type),
							VoucherNumber:   v.VoucherNumber,
							Reference:       v.Reference,
							ReferenceDate:   v.Date.Format(dateFmt),
							Narration:       v.NarrationWithKey(),
							PartyLedgerName: v.PartyLedgerName,
							PersistedView:   "Invoice Voucher View",
							Entries:         entries,
						},
					},
				},
			},
		},
	}

	var buf bytes.Buffer
	enc := xml.NewEncoder(&buf)
	enc.Indent("", " ")
	if err := enc.Encode(env); err != nil {
		return nil, fmt.Errorf("encode envelope: %w", err)
	}
	return buf.Bytes(), nil
}

// NewReceiptNote assembles an incoming voucher from scanned boxes already
// grouped by stock item.
//
// ref is the session UUID and becomes the voucher's REFERENCE: the idempotency
// key, written into Tally itself so a retry can be recognised even if the
// connector's local map is lost.
func NewReceiptNote(key, narration, party string, date time.Time, entries []InventoryEntry) Voucher {
	return Voucher{
		Type:            ReceiptNote,
		Date:            date,
		IdempotencyKey:  key,
		Narration:       narration,
		PartyLedgerName: party,
		Entries:         entries,
	}
}

// NewPhysicalStock assembles an inventory-check adjustment.
//
// A Physical Stock voucher sets the ABSOLUTE quantity of each batch, it does
// not add or subtract. That is why a stock check must only ever carry the
// boxes that were actually counted: a batch omitted from a full-godown count is
// a batch nobody looked at, and writing it as zero would destroy real stock.
// Scope is enforced upstream, in the relay.
func NewPhysicalStock(key, narration string, date time.Time, entries []InventoryEntry) Voucher {
	return Voucher{
		Type:           PhysicalStock,
		Date:           date,
		IdempotencyKey: key,
		Narration:      narration,
		Entries:        entries,
	}
}

// NewDeliveryNote assembles an outgoing voucher against a sales order.
//
// orderNo is stamped onto every batch allocation, which is what links the
// Delivery Note back to its Sales Order in Tally.
func NewDeliveryNote(key, narration, party, orderNo string, date time.Time, entries []InventoryEntry) Voucher {
	for i := range entries {
		for j := range entries[i].Batches {
			entries[i].Batches[j].OrderNo = orderNo
		}
	}
	return Voucher{
		Type:            DeliveryNote,
		Date:            date,
		IdempotencyKey:  key,
		Narration:       narration,
		PartyLedgerName: party,
		Entries:         entries,
	}
}
