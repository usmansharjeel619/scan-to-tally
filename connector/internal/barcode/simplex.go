package barcode

import (
	"regexp"
	"strconv"
	"strings"
	"time"
)

// SimplexPipe reads the long SERIAL# code on a Simplex carton label:
//
//	4098-9792|1124241658336425|18|
//	   PID          SERIAL      QTY  FIRMWARE (empty on passive devices)
//
// It validates *structure*, not the PID's shape. "4098-9792" happens to look
// like \d{4}-\d{4}, but other Simplex and JCI product lines do not all match
// that, and an over-strict pattern rejects good boxes at the dock.
type SimplexPipe struct{}

func (SimplexPipe) Name() string { return "simplex-pipe" }

func (SimplexPipe) Probe(_, raw string) float64 {
	if strings.Contains(raw, "|") {
		return 0.9
	}
	return 0
}

func (p SimplexPipe) Parse(symbology, raw string) Result {
	res := Result{Parser: p.Name(), Raw: raw, Symbology: symbology, Confidence: 0.9}

	fields := strings.Split(strings.TrimSpace(raw), "|")
	for i := range fields {
		fields[i] = strings.TrimSpace(fields[i])
	}

	// Three fields (no trailing delimiter) or four (with it, firmware possibly
	// empty). Anything else is a dialect we do not know, and guessing at a
	// quantity is the one mistake we must never make.
	if len(fields) < 3 || len(fields) > 4 {
		res.Outcome, res.Reason = Reject, ReasonFieldCount
		return res
	}

	pid, serial, qtyStr := fields[0], fields[1], fields[2]

	switch {
	case pid == "":
		res.Outcome, res.Reason = Reject, ReasonEmptyPID
		return res
	case serial == "":
		res.Outcome, res.Reason = Reject, ReasonEmptySerial
		return res
	case len(serial) < MinSerialLen || len(serial) > MaxSerialLen:
		res.Outcome, res.Reason = Reject, ReasonSerialLength
		return res
	}

	qty, err := strconv.Atoi(qtyStr)
	if err != nil || qty <= 0 {
		res.Outcome, res.Reason = Reject, ReasonQtyNotPositiveInt
		return res
	}
	if qty > MaxQty {
		res.Outcome, res.Reason = Reject, ReasonQtyOutOfRange
		return res
	}

	// The part number is kept exactly as the carton printed it, dash or no
	// dash. Matching it to a product is tolerant of the difference; the audit
	// trail is not rewritten.
	box := ParsedBox{PID: pid, BoxSerial: serial, Qty: qty, MfgDate: mfgDateFromSerial(serial)}
	if len(fields) == 4 && fields[3] != "" {
		fw := fields[3]
		box.Firmware = &fw
	}

	res.Outcome, res.Box = Accept, &box
	return res
}

// mfgDateFromSerial reads the serial's leading MMDDYY as a manufacturing date.
//
// On the reference label the serial 1124241658336425 begins 112424 = 24 Nov
// 2024, which the same label independently prints as the Julian "24 329".
// Two fields agreeing is why we trust the structure -- but only as far as
// returning nil the moment it is not a real date. A wrong date is worse than
// no date, so anything questionable yields nothing at all.
func mfgDateFromSerial(serial string) *time.Time {
	if len(serial) < 6 {
		return nil
	}
	head := serial[:6]
	if _, err := strconv.Atoi(head); err != nil {
		return nil
	}

	mm, _ := strconv.Atoi(head[0:2])
	dd, _ := strconv.Atoi(head[2:4])
	yy, _ := strconv.Atoi(head[4:6])
	if mm < 1 || mm > 12 || dd < 1 || dd > 31 {
		return nil
	}

	t := time.Date(2000+yy, time.Month(mm), dd, 0, 0, 0, 0, time.UTC)
	// time.Date normalises overflow (31 Feb becomes 2 Mar), so round-trip the
	// components to reject dates that never existed.
	if t.Day() != dd || int(t.Month()) != mm {
		return nil
	}
	return &t
}

// --- the label's other four barcodes ---------------------------------------

var (
	rePID    = regexp.MustCompile(`^\d{4}-\d{4}$`)
	rePartNo = regexp.MustCompile(`^\d{6,8}[A-Z]{2}$`)
	reCOO    = regexp.MustCompile(`^[A-Z]{2}$`)
	reQty    = regexp.MustCompile(`^\d{1,4}$`)
)

// SimplexShort recognises the PID, part-number, country-of-origin and quantity
// barcodes printed alongside the long one. It never produces a box. Its whole
// job is to let the app say "that's the product barcode -- scan the long serial
// barcode at the bottom" rather than failing generically, because there are
// five codes on that label and operators will sometimes hit the wrong one.
type SimplexShort struct{}

func (SimplexShort) Name() string { return "simplex-short" }

func (SimplexShort) Probe(_, raw string) float64 {
	if hintFor(strings.TrimSpace(raw)) == "" {
		return 0
	}
	return 0.5
}

func (p SimplexShort) Parse(symbology, raw string) Result {
	return Result{
		Outcome:    WrongBarcode,
		Parser:     p.Name(),
		Raw:        raw,
		Symbology:  symbology,
		Hint:       hintFor(strings.TrimSpace(raw)),
		Confidence: 0.5,
	}
}

func hintFor(s string) Hint {
	switch {
	case rePID.MatchString(s):
		return HintPID
	case rePartNo.MatchString(s):
		return HintPartNo
	case reCOO.MatchString(s):
		return HintCOO
	case reQty.MatchString(s):
		return HintQty
	default:
		return ""
	}
}
