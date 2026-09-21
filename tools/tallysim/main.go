// Command tallysim is a stand-in for Tally Prime's HTTP-XML gateway.
//
// WHAT IT IS FOR: developing and testing the connector, relay and app without
// access to the employee's laptop. It speaks the same request shapes the
// connector sends, keeps real batch balances, and applies vouchers to them, so
// the idempotency logic, the quantity ceilings, the duplicate rules and the
// per-item batch aggregation can all be exercised end to end.
//
// WHAT IT IS NOT: evidence that our XML is correct. The simulator was written
// from the same assumptions as the client, so the two agreeing proves only that
// the plumbing works. Only Phase 0 against a real Tally proves the schema.
// See docs/PHASE0.md.
//
// Fault injection, for exercising the error paths that matter in a warehouse:
//
//	curl 'localhost:9000/_sim/fault?mode=closed'   # company not open
//	curl 'localhost:9000/_sim/fault?mode=busy'     # slow, like a modal dialog
//	curl 'localhost:9000/_sim/fault?mode=refuse'   # error on every import
//	curl 'localhost:9000/_sim/fault?mode=none'     # back to healthy
//	curl 'localhost:9000/_sim/state'              # dump balances and vouchers
package main

import (
	"encoding/xml"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

type batchKey struct{ Item, Batch, Godown string }

type sim struct {
	mu sync.Mutex

	// The locations this company has. Kept explicitly rather than inferred
	// from stock, because a company has godowns before it has anything in them.
	godowns []string

	company   string
	items     []item
	balances  map[batchKey]float64
	units     map[string]string // item -> base unit
	orders    []salesOrder
	vouchers  []postedVoucher
	seenRefs  map[string]string // REFERENCE -> voucher id, the idempotency view
	nextVchID int

	fault string
}

type item struct {
	Name, PartNo, Units string
	Batchwise           bool
}

type soLine struct {
	Item  string
	Qty   float64
	Units string
}

type salesOrder struct {
	Number, Party string
	Date          time.Time
	Lines         []soLine
}

type postedVoucher struct {
	ID, Type, Reference, Narration string
	Date                           time.Time
	Lines                          int
	// What went out, so a later "how much of this order is already gone"
	// question can be answered the way Tally answers it.
	Despatched []despatchedLine
}

type despatchedLine struct {
	Item    string
	Qty     float64
	OrderNo string
}

func newSim() *sim {
	s := &sim{
		company:   "ACME FIRE SYSTEMS",
		balances:  map[batchKey]float64{},
		units:     map[string]string{},
		seenRefs:  map[string]string{},
		nextVchID: 1000,
	}

	// Seeded from the photographed carton so the fixtures match a real label:
	// PID 4098-9792, part no 0677197CN, SSD SENSOR BASE, QTY 18.
	s.items = []item{
		{"4098-9792 SSD SENSOR BASE", "0677197CN", "Nos", true},
		{"4090-9001 ADDRESSABLE HEAT DETECTOR", "0655011AB", "Nos", true},
		{"4100-1234 IDNAC REPEATER MODULE", "0677444CN", "Nos", true},
		{"2081-9027 CONTROL RELAY", "0612900XX", "Nos", true},
		{"MISC-CABLE-2C", "", "Mtr", false}, // deliberately NOT batch-wise
	}
	for _, it := range s.items {
		s.units[it.Name] = it.Units
	}

	// Opening stock. The first box is deliberately part-shipped: the label says
	// 18 but only 13 remain, which is exactly the case the outgoing ceiling has
	// to catch and the printed quantity would get wrong.
	// Two locations, not one. The single-location case hid a real bug: balances
	// were only attributed to a godown when there was exactly one, so a company
	// with several got "location unknown" on every box and outgoing could never
	// find the carton in front of the operator.
	s.godowns = []string{"Main Store", "Rack 12"}

	s.balances[batchKey{"4098-9792 SSD SENSOR BASE", "1124241658336425", "Main Store"}] = 13
	s.balances[batchKey{"4098-9792 SSD SENSOR BASE", "1124241658336426", "Main Store"}] = 18
	s.balances[batchKey{"4098-9792 SSD SENSOR BASE", "1124241658336427", "Main Store"}] = 18
	s.balances[batchKey{"4090-9001 ADDRESSABLE HEAT DETECTOR", "0701240099887766", "Main Store"}] = 24
	// A fully despatched box: still a known batch, but nothing left to give.
	s.balances[batchKey{"2081-9027 CONTROL RELAY", "0315251122334455", "Main Store"}] = 0
	s.balances[batchKey{"4090-9001 ADDRESSABLE HEAT DETECTOR", "0701249900000001", "Rack 12"}] = 7

	s.orders = []salesOrder{
		{
			Number: "SO-2026-0041", Party: "Example Project FZC",
			Date: time.Now().AddDate(0, 0, -6),
			Lines: []soLine{
				{"4098-9792 SSD SENSOR BASE", 30, "Nos"},
				{"4090-9001 ADDRESSABLE HEAT DETECTOR", 12, "Nos"},
			},
		},
		{
			Number: "SO-2026-0042", Party: "Edition Hotel MDDC",
			Date: time.Now().AddDate(0, 0, -2),
			Lines: []soLine{
				{"4100-1234 IDNAC REPEATER MODULE", 4, "Nos"},
			},
		},
	}
	return s
}

// --- request routing --------------------------------------------------------

type reqEnvelope struct {
	Header struct {
		TallyRequest string `xml:"TALLYREQUEST"`
		Type         string `xml:"TYPE"`
		ID           string `xml:"ID"`
	} `xml:"HEADER"`
	Body struct {
		Desc struct {
			StaticVariables struct {
				CurrentCompany string `xml:"SVCURRENTCOMPANY"`
				Godown         string `xml:"SVGODOWN"`
			} `xml:"STATICVARIABLES"`
		} `xml:"DESC"`
		ImportData struct {
			RequestDesc struct {
				ReportName      string `xml:"REPORTNAME"`
				StaticVariables struct {
					CurrentCompany string `xml:"SVCURRENTCOMPANY"`
				} `xml:"STATICVARIABLES"`
			} `xml:"REQUESTDESC"`
			RequestData struct {
				TallyMessage struct {
					Voucher *simVoucher `xml:"VOUCHER"`
				} `xml:"TALLYMESSAGE"`
			} `xml:"REQUESTDATA"`
		} `xml:"IMPORTDATA"`
	} `xml:"BODY"`
}

type simVoucher struct {
	VchType         string `xml:"VCHTYPE,attr"`
	Date            string `xml:"DATE"`
	VoucherTypeName string `xml:"VOUCHERTYPENAME"`
	Reference       string `xml:"REFERENCE"`
	Narration       string `xml:"NARRATION"`
	PartyLedgerName string `xml:"PARTYLEDGERNAME"`
	Entries         []struct {
		StockItemName    string `xml:"STOCKITEMNAME"`
		IsDeemedPositive string `xml:"ISDEEMEDPOSITIVE"`
		ActualQty        string `xml:"ACTUALQTY"`
		Batches          []struct {
			GodownName string `xml:"GODOWNNAME"`
			BatchName  string `xml:"BATCHNAME"`
			OrderNo    string `xml:"ORDERNO"`
			ActualQty  string `xml:"ACTUALQTY"`
		} `xml:"BATCHALLOCATIONS.LIST"`
	} `xml:"ALLINVENTORYENTRIES.LIST"`
}

func (s *sim) handle(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(io.LimitReader(r.Body, 32<<20))

	s.mu.Lock()
	fault := s.fault
	s.mu.Unlock()

	switch fault {
	case "busy":
		time.Sleep(25 * time.Second)
	case "offline":
		// Close without answering, the way a killed Tally process behaves.
		if hj, ok := w.(http.Hijacker); ok {
			if c, _, err := hj.Hijack(); err == nil {
				c.Close()
				return
			}
		}
		return
	}

	var env reqEnvelope
	if err := xml.Unmarshal(body, &env); err != nil {
		lineError(w, "Could not read the request XML.")
		return
	}

	if fault == "closed" {
		lineError(w, "Could not set 'SVCURRENTCOMPANY' to the requested company. The company is not open.")
		return
	}

	switch {
	case strings.EqualFold(env.Header.TallyRequest, "Export"):
		s.export(w, env.Header.ID, env.Body.Desc.StaticVariables.Godown)
	case strings.EqualFold(env.Header.TallyRequest, "Import Data"):
		s.importVoucher(w, env, fault)
	default:
		lineError(w, "Unknown TALLYREQUEST.")
	}
}

// godown narrows a report to one location, the way SVGODOWN does in Tally.
// Empty means every location, which is what a single-location company asks for.
func (s *sim) export(w http.ResponseWriter, id string, godown string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	// Shape confirmed against a real TallyPrime in Phase 0: a CMPINFO preamble
	// inside DESC, then the rows under BODY > DATA > COLLECTION. Entity names
	// arrive in the NAME attribute; the <NAME> element is often buried under
	// LANGUAGENAME.LIST and a flat struct tag will not reach it.
	var b strings.Builder
	b.WriteString("<ENVELOPE>\n <HEADER>\n  <VERSION>1</VERSION>\n  <STATUS>1</STATUS>\n </HEADER>\n")
	b.WriteString(" <BODY>\n  <DESC>\n   <CMPINFO>\n    <COMPANY>0</COMPANY>\n    <STOCKITEM>0</STOCKITEM>\n   </CMPINFO>\n  </DESC>\n  <DATA>\n   <COLLECTION>\n")

	switch id {
	case "STT_Companies":
		fmt.Fprintf(&b, "<COMPANY NAME=%q><NAME>%s</NAME><STARTINGFROM>20250401</STARTINGFROM><GUID>sim-co-1</GUID></COMPANY>\n",
			s.company, esc(s.company))

	case "STT_StockItems":
		for i, it := range s.items {
			fmt.Fprintf(&b, "<STOCKITEM NAME=%q><NAME>%s</NAME><BASEUNITS>%s</BASEUNITS>"+
				"<PARTNO>%s</PARTNO><ISBATCHWISEON>%s</ISBATCHWISEON><GUID>sim-si-%d</GUID></STOCKITEM>\n",
				it.Name, esc(it.Name), it.Units, esc(it.PartNo), yn(it.Batchwise), i)
		}

	case "STT_Godowns":
		// Without this the connector cannot tell which locations exist, and
		// every balance comes back with an unknown one -- which is exactly the
		// shape of the bug that disabled outgoing in a live company.
		for i, g := range s.godowns {
			fmt.Fprintf(&b, "<GODOWN NAME=%q><NAME>%s</NAME><PARENT></PARENT>"+
				"<GUID>sim-gd-%d</GUID></GODOWN>\n", g, esc(g), i)
		}

	case "STT_BatchBalances":
		// Group balances by item, the way Tally nests them.
		byItem := map[string][]batchKey{}
		for k := range s.balances {
			byItem[k.Item] = append(byItem[k.Item], k)
		}
		for itemName, keys := range byItem {
			fmt.Fprintf(&b, "<STOCKITEM NAME=%q><NAME>%s</NAME><BASEUNITS>%s</BASEUNITS>\n",
				itemName, esc(itemName), s.units[itemName])
			for _, k := range keys {
				fmt.Fprintf(&b, " <BATCHALLOCATIONS.LIST><BATCHNAME>%s</BATCHNAME>"+
					"<GODOWNNAME>%s</GODOWNNAME><CLOSINGBALANCE>%s %s</CLOSINGBALANCE></BATCHALLOCATIONS.LIST>\n",
					esc(k.Batch), esc(k.Godown), trimNum(s.balances[k]), s.units[itemName])
			}
			b.WriteString("</STOCKITEM>\n")
		}

	// The Stock Summary report, which is where batch closing balances really
	// come from.
	//
	// The obvious-looking source -- BATCHALLOCATIONS on the stock item master,
	// served as STT_BatchBalances above -- is the OPENING allocation, and it
	// does not move when a voucher posts. Reading it meant the app showed a
	// product it had just received as having none. This report is what the
	// connector asks for now, so the simulator has to answer it or the two
	// integration tests that check stock actually moves cannot run at all.
	//
	// The shape matters as much as the numbers: a flat stream of item, then
	// that item's own total, then each batch followed by ITS total. The item
	// total has to be here even though the parser skips it, because skipping it
	// correctly is the whole reason the parser is written the way it is.
	case "Stock Summary":
		// The real report carries no godown of its own -- it reports whatever
		// SVGODOWN narrowed it to. Honouring that here is what lets the tests
		// catch a connector that asks the wrong way: returning everything
		// regardless would make a broken per-location read look correct.
		byItem := map[string][]batchKey{}
		for k := range s.balances {
			if godown != "" && k.Godown != godown {
				continue
			}
			byItem[k.Item] = append(byItem[k.Item], k)
		}
		// Sorted so a run is reproducible; Go randomises map order and a test
		// that passes four times in five is worse than one that fails.
		items := make([]string, 0, len(byItem))
		for name := range byItem {
			items = append(items, name)
		}
		sort.Strings(items)

		for _, itemName := range items {
			keys := byItem[itemName]
			sort.Slice(keys, func(i, j int) bool { return keys[i].Batch < keys[j].Batch })

			total := 0.0
			for _, k := range keys {
				total += s.balances[k]
			}
			unit := s.units[itemName]
			fmt.Fprintf(&b, "<STOCKSUMMARY><DSPDISPNAME>%s</DSPDISPNAME>"+
				"<DSPSTKINFO><DSPSTKCL><DSPCLQTY>%s %s</DSPCLQTY></DSPSTKCL></DSPSTKINFO>\n",
				esc(itemName), trimNum(total), unit)
			for _, k := range keys {
				fmt.Fprintf(&b, " <SSBATCH>%s</SSBATCH>"+
					"<DSPSTKINFO><DSPSTKCL><DSPCLQTY>%s %s</DSPCLQTY></DSPSTKCL></DSPSTKINFO>\n",
					esc(k.Batch), trimNum(s.balances[k]), unit)
			}
			b.WriteString("</STOCKSUMMARY>\n")
		}

	case "STT_SalesOrders":
		for _, o := range s.orders {
			fmt.Fprintf(&b, "<VOUCHER><VOUCHERNUMBER>%s</VOUCHERNUMBER><DATE>%s</DATE>"+
				"<PARTYLEDGERNAME>%s</PARTYLEDGERNAME><VOUCHERTYPENAME>Sales Order</VOUCHERTYPENAME><GUID>sim-so-%s</GUID>\n",
				esc(o.Number), o.Date.Format("20060102"), esc(o.Party), esc(o.Number))
			for _, l := range o.Lines {
				fmt.Fprintf(&b, " <ALLINVENTORYENTRIES.LIST><STOCKITEMNAME>%s</STOCKITEMNAME>"+
					"<ACTUALQTY>%s %s</ACTUALQTY></ALLINVENTORYENTRIES.LIST>\n",
					esc(l.Item), trimNum(l.Qty), l.Units)
			}
			b.WriteString("</VOUCHER>\n")
		}

	case "STT_OrderFulfilment":
		// What has already gone out against each sales order, so the app can
		// show what is still outstanding.
		//
		// The simulator keeps despatches as vouchers with an ORDERNO on each
		// batch allocation, which is how the connector reads them back.
		for _, v := range s.vouchers {
			if v.Type != "Delivery Note" {
				continue
			}
			fmt.Fprintf(&b, "<VOUCHER><VOUCHERNUMBER>%s</VOUCHERNUMBER>"+
				"<VOUCHERTYPENAME>%s</VOUCHERTYPENAME>\n", esc(v.ID), esc(v.Type))
			for _, l := range v.Despatched {
				fmt.Fprintf(&b, " <ALLINVENTORYENTRIES.LIST><STOCKITEMNAME>%s</STOCKITEMNAME>"+
					"<ACTUALQTY>%s %s</ACTUALQTY><ORDERNO>%s</ORDERNO>"+
					"</ALLINVENTORYENTRIES.LIST>\n",
					esc(l.Item), trimNum(l.Qty), s.units[l.Item], esc(l.OrderNo))
			}
			b.WriteString("</VOUCHER>\n")
		}

	default:
		b.Reset()
		lineError(w, fmt.Sprintf("Collection %q is not defined.", id))
		return
	}

	b.WriteString("   </COLLECTION>\n  </DATA>\n </BODY>\n</ENVELOPE>\n")
	w.Header().Set("Content-Type", "text/xml")
	io.WriteString(w, b.String())
}

func (s *sim) importVoucher(w http.ResponseWriter, env reqEnvelope, fault string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	v := env.Body.ImportData.RequestData.TallyMessage.Voucher
	if v == nil {
		lineError(w, "No voucher in the request.")
		return
	}
	if fault == "refuse" {
		lineError(w, "Could not find Stock Item 'SIMULATED FAILURE'!")
		return
	}

	// NOTE: real Tally does NOT do this. It will happily create a second
	// voucher with the same REFERENCE. The simulator reports the collision so
	// tests can assert the connector never gets this far on a retry -- dedupe
	// is the connector's job, not Tally's.
	if prev, ok := s.seenRefs[v.Reference]; ok && v.Reference != "" {
		log.Printf("WARNING duplicate REFERENCE %s (already voucher %s) -- "+
			"real Tally would have created a SECOND voucher here", v.Reference, prev)
	}

	incoming := strings.EqualFold(v.IsDeemedPositiveOfFirstEntry(), "yes")

	// Validate everything before mutating anything, so a rejected voucher
	// leaves no partial stock movement behind.
	type delta struct {
		key batchKey
		qty float64
	}
	var deltas []delta

	for _, e := range v.Entries {
		known := false
		for _, it := range s.items {
			if it.Name == e.StockItemName {
				known = true
				break
			}
		}
		if !known {
			lineError(w, fmt.Sprintf("Could not find Stock Item '%s'!", e.StockItemName))
			return
		}
		for _, b := range e.Batches {
			qty, _ := parseQty(b.ActualQty)
			k := batchKey{e.StockItemName, b.BatchName, orDefault(b.GodownName, "Main Store")}
			if incoming {
				deltas = append(deltas, delta{k, qty})
			} else {
				if s.balances[k]-qty < -0.0001 {
					lineError(w, fmt.Sprintf(
						"Negative stock for Stock Item '%s' batch '%s': have %s, asked %s.",
						e.StockItemName, b.BatchName, trimNum(s.balances[k]), trimNum(qty)))
					return
				}
				deltas = append(deltas, delta{k, -qty})
			}
		}
	}

	for _, d := range deltas {
		s.balances[d.key] += d.qty
	}

	s.nextVchID++
	id := fmt.Sprint(s.nextVchID)
	s.seenRefs[v.Reference] = id
	var despatched []despatchedLine
	if !incoming {
		for _, e := range v.Entries {
			for _, b := range e.Batches {
				q, _ := parseQty(b.ActualQty)
				despatched = append(despatched, despatchedLine{
					Item: e.StockItemName, Qty: q, OrderNo: b.OrderNo,
				})
			}
		}
	}

	s.vouchers = append(s.vouchers, postedVoucher{
		ID: id, Type: v.VoucherTypeName, Reference: v.Reference,
		Narration: v.Narration, Date: time.Now(), Lines: len(v.Entries),
		Despatched: despatched,
	})
	log.Printf("created %s %s ref=%s entries=%d", v.VoucherTypeName, id, v.Reference, len(v.Entries))

	w.Header().Set("Content-Type", "text/xml")
	fmt.Fprintf(w, `<RESPONSE>
 <CREATED>1</CREATED><ALTERED>0</ALTERED><DELETED>0</DELETED>
 <LASTVCHID>%s</LASTVCHID><LASTMID>0</LASTMID>
 <COMBINED>0</COMBINED><IGNORED>0</IGNORED><ERRORS>0</ERRORS><CANCELLED>0</CANCELLED>
</RESPONSE>`, id)
}

func (v *simVoucher) IsDeemedPositiveOfFirstEntry() string {
	if len(v.Entries) == 0 {
		return "No"
	}
	return v.Entries[0].IsDeemedPositive
}

// --- helpers ----------------------------------------------------------------

func lineError(w http.ResponseWriter, msg string) {
	w.Header().Set("Content-Type", "text/xml")
	fmt.Fprintf(w, "<RESPONSE>\n <LINEERROR>%s</LINEERROR>\n</RESPONSE>", esc(msg))
}

func esc(s string) string {
	var b strings.Builder
	xml.EscapeText(&b, []byte(s))
	return b.String()
}

func yn(b bool) string {
	if b {
		return "Yes"
	}
	return "No"
}

func orDefault(s, d string) string {
	if strings.TrimSpace(s) == "" {
		return d
	}
	return s
}

func trimNum(f float64) string {
	if f == float64(int64(f)) {
		return fmt.Sprintf("%d", int64(f))
	}
	return fmt.Sprintf("%g", f)
}

func parseQty(s string) (float64, string) {
	s = strings.TrimSpace(s)
	i := 0
	for i < len(s) && (s[i] == '-' || s[i] == '.' || (s[i] >= '0' && s[i] <= '9')) {
		i++
	}
	var f float64
	fmt.Sscanf(strings.TrimSpace(s[:i]), "%g", &f)
	return f, strings.TrimSpace(s[i:])
}

func main() {
	addr := flag.String("addr", ":9000", "listen address")
	flag.Parse()

	s := newSim()
	mux := http.NewServeMux()
	mux.HandleFunc("/", s.handle)

	mux.HandleFunc("/_sim/fault", func(w http.ResponseWriter, r *http.Request) {
		mode := r.URL.Query().Get("mode")
		s.mu.Lock()
		if mode == "none" {
			mode = ""
		}
		s.fault = mode
		s.mu.Unlock()
		fmt.Fprintf(w, "fault=%q\n", mode)
		log.Printf("fault mode set to %q", mode)
	})

	mux.HandleFunc("/_sim/state", func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		defer s.mu.Unlock()
		fmt.Fprintf(w, "company: %s\nfault: %q\n\nBATCH BALANCES\n", s.company, s.fault)
		for k, v := range s.balances {
			fmt.Fprintf(w, "  %-40s %-20s %-12s %s\n", k.Item, k.Batch, k.Godown, trimNum(v))
		}
		fmt.Fprintf(w, "\nVOUCHERS (%d)\n", len(s.vouchers))
		for _, v := range s.vouchers {
			fmt.Fprintf(w, "  %-6s %-16s ref=%-24s lines=%d\n", v.ID, v.Type, v.Reference, v.Lines)
		}
	})

	log.Printf("tallysim listening on %s (company %q)", *addr, s.company)
	log.Printf("  fault injection: curl 'localhost%s/_sim/fault?mode=closed|busy|refuse|offline|none'", *addr)
	log.Printf("  state dump:      curl 'localhost%s/_sim/state'", *addr)
	if err := http.ListenAndServe(*addr, mux); err != nil {
		log.Fatal(err)
	}
}
