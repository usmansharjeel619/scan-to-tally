package tally

import (
	"bytes"
	"context"
	"encoding/xml"
	"fmt"
	"strings"
)

// Creating a stock item in Tally.
//
// This is the ONE write path to the item master, and it is deliberately not a
// general one. It takes typed fields and builds the XML itself; no caller can
// hand it a document. That distinction matters: an arbitrary-XML channel into
// an accounting system is a liability, and a malformed master payload has
// already been observed taking TallyPrime down with a memory access violation.
//
// It is also gated twice before reaching here:
//
//   - The relay only issues one after a supervisor has explicitly approved the
//     proposed item. An operator cannot cause a master to be created.
//   - The connector refuses unless masters.allowCreate is set in its config, so
//     a site that does not want its books written to from a warehouse simply
//     never turns it on.
//
// A stock item cannot be deleted once it has transactions, so every creation is
// effectively permanent. Everything here errs towards refusing.

// NewStockItem is a product about to be added to the item master.
type NewStockItem struct {
	// Name is what Tally will call it. The convention in the live catalogue is
	// "<PID> <DESCRIPTION>", which is also what makes a scanned PID resolvable
	// later, so the caller is expected to have composed it that way.
	Name string
	// BaseUnits must already exist in Tally. Creating a unit is not something
	// this will do -- getting a unit wrong makes every quantity for the item
	// wrong, permanently.
	BaseUnits string
	// Parent is the stock group. Empty means Tally's Primary.
	Parent string
	// Batchwise enables box-level tracking. It cannot be changed once the item
	// has transactions, so it is a decision, not a default.
	Batchwise bool
	// TrackMfgDate lets a batch carry the manufacturing date read off the label.
	TrackMfgDate bool
	// PartNo is optional and purely informational here; the live catalogue
	// populates it on none of its 574 items.
	PartNo string
}

// Validate refuses anything that would produce a master somebody later regrets.
func (n NewStockItem) Validate() error {
	name := strings.TrimSpace(n.Name)
	switch {
	case name == "":
		return fmt.Errorf("stock item name is required")
	case len(name) < 4:
		return fmt.Errorf("stock item name %q is too short to be meaningful", name)
	case len(name) > 200:
		return fmt.Errorf("stock item name is too long (%d chars)", len(name))
	case strings.TrimSpace(n.BaseUnits) == "":
		return fmt.Errorf("a base unit is required; a wrong unit makes every quantity wrong")
	}
	// Control characters are how Tally marks its own internal sentinels
	// ("\x04 Not Applicable"). A name carrying one is a parsing accident, not a
	// product.
	for _, r := range name {
		if r < 0x20 {
			return fmt.Errorf("stock item name contains a control character")
		}
	}
	return nil
}

type masterEnvelope struct {
	XMLName xml.Name     `xml:"ENVELOPE"`
	Header  importHeader `xml:"HEADER"`
	Body    masterBody   `xml:"BODY"`
}

type masterBody struct {
	ImportData masterImport `xml:"IMPORTDATA"`
}

type masterImport struct {
	RequestDesc requestDesc   `xml:"REQUESTDESC"`
	RequestData masterReqData `xml:"REQUESTDATA"`
}

type masterReqData struct {
	TallyMessage masterMessage `xml:"TALLYMESSAGE"`
}

type masterMessage struct {
	UDFNamespace string         `xml:"xmlns:UDF,attr"`
	StockItem    *wireStockItem `xml:"STOCKITEM,omitempty"`
}

// wireStockItem mirrors the element order Tally itself emits for a stock item.
//
// The NAME attribute is present deliberately: a UNIT create without one was
// what crashed TallyPrime, and while the cause was never confirmed, there is
// nothing to gain from omitting it.
type wireStockItem struct {
	NameAttr string `xml:"NAME,attr"`
	Action   string `xml:"ACTION,attr"`

	Name           string `xml:"NAME"`
	Parent         string `xml:"PARENT"`
	BaseUnits      string `xml:"BASEUNITS"`
	IsBatchWiseOn  string `xml:"ISBATCHWISEON"`
	IsPerishableOn string `xml:"ISPERISHABLEON"`
	PartNo         string `xml:"PARTNO,omitempty"`
}

// BuildCreateStockItem renders the create request.
//
// Exported so it can be inspected, diffed and tested without a Tally anywhere
// near it -- which is the only way this should be developed.
func BuildCreateStockItem(company string, item NewStockItem) ([]byte, error) {
	if err := item.Validate(); err != nil {
		return nil, fmt.Errorf("invalid stock item: %w", err)
	}
	if strings.TrimSpace(company) == "" {
		return nil, fmt.Errorf("company is required; a master must never be created in an unnamed company")
	}

	name := strings.TrimSpace(item.Name)
	parent := strings.TrimSpace(item.Parent)

	env := masterEnvelope{
		Header: importHeader{TallyRequest: "Import Data"},
		Body: masterBody{
			ImportData: masterImport{
				RequestDesc: requestDesc{
					ReportName:      "All Masters",
					StaticVariables: staticVariables{CurrentCompany: company},
				},
				RequestData: masterReqData{
					TallyMessage: masterMessage{
						UDFNamespace: "TallyUDF",
						StockItem: &wireStockItem{
							NameAttr:       name,
							Action:         "Create",
							Name:           name,
							Parent:         parent,
							BaseUnits:      strings.TrimSpace(item.BaseUnits),
							IsBatchWiseOn:  yesNo(item.Batchwise),
							IsPerishableOn: yesNo(item.Batchwise && item.TrackMfgDate),
							PartNo:         strings.TrimSpace(item.PartNo),
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
		return nil, fmt.Errorf("encode stock item: %w", err)
	}
	return buf.Bytes(), nil
}

func yesNo(b bool) string {
	if b {
		return "Yes"
	}
	return "No"
}

// MasterPolicy decides whether this connector may write to the item master.
type MasterPolicy struct {
	// AllowCreate is off unless a site explicitly turns it on. A warehouse
	// scanner writing to the books is a choice, not a default.
	AllowCreate bool
	// DefaultParent is the stock group new items land in, so they can be found
	// and reviewed rather than scattered through Primary.
	DefaultParent string
}

// CreateStockItem adds one item to the master.
//
// Returns the created name on success. Tally reporting CREATED=0 is treated as
// a failure even on HTTP 200, because it answers 200 for almost everything.
func (c *Client) CreateStockItem(
	ctx context.Context, policy MasterPolicy, item NewStockItem,
) (string, error) {
	if !policy.AllowCreate {
		return "", business("MASTER_CREATE_DISABLED",
			"This connector is not permitted to create stock items. "+
				"Set masters.allowCreate in its config if that is intended.")
	}
	if item.Parent == "" {
		item.Parent = policy.DefaultParent
	}

	payload, err := BuildCreateStockItem(c.cfg.Company, item)
	if err != nil {
		return "", business("INVALID_STOCK_ITEM", err.Error())
	}

	// Loud on purpose. This is a permanent change to an accounting master and
	// whoever owns the machine should be able to see every one of them.
	c.log.Warn("CREATING A STOCK ITEM IN TALLY",
		"name", item.Name, "unit", item.BaseUnits,
		"batchwise", item.Batchwise, "company", c.cfg.Company)

	body, err := c.post(ctx, payload)
	if err != nil {
		return "", err
	}

	res, err := ParseImportResponse(body)
	if err != nil {
		return "", c.noteAppError(err)
	}
	if res.Created == 0 && res.Altered == 0 {
		return "", business("ITEM_NOT_CREATED",
			"Tally accepted the request but created nothing.")
	}

	c.log.Warn("stock item created", "name", item.Name, "created", res.Created)
	return item.Name, nil
}
