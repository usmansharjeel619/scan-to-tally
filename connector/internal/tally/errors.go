package tally

import (
	"encoding/xml"
	"errors"
	"fmt"
	"net"
	"strings"
)

// Class separates errors we should keep retrying from errors that need a human.
//
// Conflating these is the most common way an integration like this rots: retry
// a business error forever and the queue silently stalls; surface a transient
// one and you page somebody because a laptop went to sleep.
type Class string

const (
	// Transient means the world will probably fix itself: Tally not running,
	// company not open, laptop asleep, network gone, request timed out.
	// Retry with backoff. The operator sees a status badge, nothing more.
	Transient Class = "TRANSIENT"

	// Business means the data is wrong and retrying cannot help: unknown stock
	// item, negative stock, batch already exists, balance moved underneath us.
	// Stop retrying and put it in front of a supervisor.
	Business Class = "BUSINESS"
)

// Error is a classified failure from Tally.
type Error struct {
	Class   Class
	Code    string // stable identifier for the UI and metrics
	Message string // Tally's own words, shown verbatim to the supervisor
	Err     error
}

func (e *Error) Error() string {
	if e.Code != "" {
		return fmt.Sprintf("%s/%s: %s", e.Class, e.Code, e.Message)
	}
	return fmt.Sprintf("%s: %s", e.Class, e.Message)
}

func (e *Error) Unwrap() error { return e.Err }

// IsTransient reports whether err is worth retrying.
func IsTransient(err error) bool {
	var te *Error
	if errors.As(err, &te) {
		return te.Class == Transient
	}
	// An unclassified error from the transport layer is almost always a
	// connection problem, so let it retry.
	return true
}

func transient(code, msg string, err error) *Error {
	return &Error{Class: Transient, Code: code, Message: msg, Err: err}
}

// NewBusiness builds an error that will not be retried, for callers outside
// this package. The runner needs it to report a voucher it refuses to replace.
func NewBusiness(code, msg string) *Error { return business(code, msg) }

func business(code, msg string) *Error {
	return &Error{Class: Business, Code: msg2code(code), Message: msg}
}

func msg2code(c string) string { return c }

// crashSignatures are what a process dying mid-request looks like from the
// client side, on Windows and on Unix.
//
// This is not the same as "Tally is closed". The port answered, the request was
// accepted, and then the process went away underneath it -- which is what a
// crash looks like. Observed for real: TallyPrime died during a plain read-only
// Unit collection, and the connector's instinct was to retry 0.2s later.
var crashSignatures = []string{
	"forcibly closed",          // Windows: WSAECONNRESET
	"connection reset by peer", // Unix
	"wsarecv",                  // Windows socket read failure
	"unexpected eof",
	"eof",
	"broken pipe",
}

// classifyTransport maps a Go network error onto our two classes. Anything at
// this layer is transient by definition -- we never reached Tally's logic.
func classifyTransport(err error) *Error {
	if err == nil {
		return nil
	}
	var nerr net.Error
	if errors.As(err, &nerr) && nerr.Timeout() {
		return transient("TIMEOUT",
			"Tally did not respond in time. It may be busy or have a dialog open.", err)
	}
	if strings.Contains(err.Error(), "connection refused") {
		return transient("TALLY_NOT_RUNNING",
			"Nothing is listening on the Tally gateway port. Tally is probably not running.", err)
	}

	// The connection died mid-request. Treat this as Tally having crashed:
	// retrying straight into a process that just fell over is the worst
	// available move, and a repeat of it is what a damaged company looks like.
	low := strings.ToLower(err.Error())
	for _, sig := range crashSignatures {
		if strings.Contains(low, sig) {
			return transient("TALLY_CRASHED",
				"Tally accepted the request and then stopped responding, which usually means "+
					"the process crashed. Backing off; this machine needs looking at.", err)
		}
	}
	return transient("TRANSPORT", err.Error(), err)
}

// businessPatterns are Tally's own phrasings for conditions a human must fix.
// Matched case-insensitively against LINEERROR text.
//
// Keep this list narrow and deliberate. Anything unmatched is treated as a
// business error, because an unrecognised failure deserves a human look rather
// than a silent retry loop.
var businessPatterns = []struct {
	substr string
	code   string
}{
	{"could not find stock item", "UNKNOWN_STOCK_ITEM"},
	{"could not find ledger", "UNKNOWN_LEDGER"},
	{"could not find godown", "UNKNOWN_GODOWN"},
	{"could not find voucher type", "UNKNOWN_VOUCHER_TYPE"},
	{"negative stock", "NEGATIVE_STOCK"},
	{"negative balance", "NEGATIVE_STOCK"},
	{"insufficient", "INSUFFICIENT_STOCK"},
	{"batch", "BATCH_PROBLEM"},
	{"duplicate", "DUPLICATE"},
	{"already exists", "DUPLICATE"},
	{"date is out of range", "DATE_OUT_OF_RANGE"},
	{"period", "DATE_OUT_OF_RANGE"},
	{"mandatory", "MISSING_MANDATORY_FIELD"},
}

// transientPatterns are the few Tally messages that really do resolve on their
// own, all of them variations of "the company isn't open right now".
var transientPatterns = []struct {
	substr string
	code   string
}{
	{"could not set 'svcurrentcompany'", "COMPANY_NOT_OPEN"},
	{"company is not open", "COMPANY_NOT_OPEN"},
	{"no company", "COMPANY_NOT_OPEN"},
	{"is not loaded", "COMPANY_NOT_OPEN"},
	{"busy", "TALLY_BUSY"},
	{"another process", "TALLY_BUSY"},
}

// classifyMessage turns a LINEERROR string into a classified error.
func classifyMessage(msg string) *Error {
	l := strings.ToLower(msg)

	for _, p := range transientPatterns {
		if strings.Contains(l, p.substr) {
			return transient(p.code, msg, nil)
		}
	}
	for _, p := range businessPatterns {
		if strings.Contains(l, p.substr) {
			return business(p.code, msg)
		}
	}
	return business("TALLY_ERROR", msg)
}

// --- response parsing -------------------------------------------------------

// ImportResult is Tally's accounting of what an import did.
type ImportResult struct {
	Created   int    `xml:"CREATED"`
	Altered   int    `xml:"ALTERED"`
	Deleted   int    `xml:"DELETED"`
	Ignored   int    `xml:"IGNORED"`
	Errors    int    `xml:"ERRORS"`
	Cancelled int    `xml:"CANCELLED"`
	LastVchID string `xml:"LASTVCHID"`
	LastMID   string `xml:"LASTMID"`
	LineError string `xml:"LINEERROR"`
}

// ParseImportResponse reads an import reply and decides whether it worked.
//
// Tally is cheerfully willing to return HTTP 200 with a body that says nothing
// was created, so success is asserted from the counters, never from the status
// code.
func ParseImportResponse(body []byte) (*ImportResult, error) {
	trimmed := strings.TrimSpace(string(body))
	if trimmed == "" {
		return nil, transient("EMPTY_RESPONSE",
			"Tally returned an empty response. It may have been closed mid-request.", nil)
	}

	var res ImportResult
	if err := xml.Unmarshal([]byte(trimmed), &res); err != nil {
		// Some failures come back as a bare <ENVELOPE> with a status, and some
		// as plain text. Surface whatever was said rather than an XML error.
		if le := extractTag(trimmed, "LINEERROR"); le != "" {
			return nil, classifyMessage(le)
		}
		return nil, business("UNPARSEABLE_RESPONSE",
			fmt.Sprintf("Could not read Tally's response: %s", truncate(trimmed, 400)))
	}

	if res.LineError != "" {
		return &res, classifyMessage(res.LineError)
	}
	if res.Errors > 0 {
		return &res, business("TALLY_ERROR",
			fmt.Sprintf("Tally reported %d error(s) and created %d voucher(s).", res.Errors, res.Created))
	}
	if res.Created == 0 && res.Altered == 0 {
		return &res, business("NOTHING_CREATED",
			"Tally accepted the request but created nothing. The voucher type or company name is probably wrong.")
	}
	return &res, nil
}

// extractTag pulls the text of the first <tag>...</tag> out of a response that
// would not unmarshal.
func extractTag(s, tag string) string {
	open, close := "<"+tag+">", "</"+tag+">"
	i := strings.Index(s, open)
	if i < 0 {
		return ""
	}
	j := strings.Index(s[i:], close)
	if j < 0 {
		return ""
	}
	return strings.TrimSpace(s[i+len(open) : i+j])
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}
