package tally

import (
	"context"
	"fmt"
	"regexp"
	"strings"
	"time"
)

// Diagnostics is the Phase 0 escape hatch: it lets a remote operator run a
// READ-ONLY Tally query so the XML templates in this package can be reconciled
// against a real installation.
//
// It exists because Tally's schema varies by version and company configuration,
// and the only authoritative source is the target machine -- which, in a typical
// deployment, nobody can reach inbound.
//
// It is deliberately narrow:
//
//   - OFF unless explicitly enabled in the connector's config file.
//   - Export requests ONLY. Anything that could create, alter or delete a
//     voucher is refused here, before it reaches Tally. Vouchers are iterated
//     through the normal job path instead, where they are validated and
//     recorded, so this hole does not need to exist for them.
//   - Every request is logged with its label, so whoever owns the machine can
//     see exactly what was asked.
//
// Turn it off when Phase 0 ends.
type Diagnostics struct {
	Enabled bool
	// MaxBytes caps a response so a mis-scoped collection cannot pull the whole
	// book across a slow link.
	MaxBytes int
}

var (
	// Matches the request verb wherever it sits in the envelope.
	reTallyRequest = regexp.MustCompile(`(?is)<TALLYREQUEST>\s*([^<]*)\s*</TALLYREQUEST>`)
	// Belt and braces: these must never appear in a read-only query, whatever
	// the declared verb says.
	forbidden = []string{
		"<IMPORTDATA", "ACTION=\"CREATE\"", "ACTION=\"ALTER\"", "ACTION=\"DELETE\"",
		"ACTION='CREATE'", "ACTION='ALTER'", "ACTION='DELETE'", "<VOUCHER ",
	}
)

// ErrDiagDisabled is returned when diagnostics are off.
type ErrDiagDisabled struct{}

func (ErrDiagDisabled) Error() string {
	return "diagnostics are disabled on this connector (set diagnostics.enabled in the config file)"
}

// ErrDiagRefused is returned when a payload is not a read-only query.
type ErrDiagRefused struct{ Reason string }

func (e ErrDiagRefused) Error() string {
	return "refused: " + e.Reason
}

// Vet decides whether a payload is a read-only query we are willing to relay.
//
// Fails closed: an envelope whose verb cannot be read at all is refused, rather
// than being given the benefit of the doubt.
func (d Diagnostics) Vet(xmlPayload string) error {
	if !d.Enabled {
		return ErrDiagDisabled{}
	}

	upper := strings.ToUpper(xmlPayload)
	for _, f := range forbidden {
		if strings.Contains(upper, f) {
			return ErrDiagRefused{Reason: fmt.Sprintf(
				"payload contains %q, which can modify data. Vouchers go through the normal job path.", f)}
		}
	}

	m := reTallyRequest.FindStringSubmatch(xmlPayload)
	if m == nil {
		return ErrDiagRefused{Reason: "no <TALLYREQUEST> element found; cannot confirm this is read-only"}
	}
	verb := strings.TrimSpace(strings.ToUpper(m[1]))
	if verb != "EXPORT" {
		return ErrDiagRefused{Reason: fmt.Sprintf(
			"TALLYREQUEST is %q; only Export is permitted here", verb)}
	}
	return nil
}

// Query runs a vetted read-only request against Tally and returns the raw
// response, unparsed. Parsing is the caller's problem -- the entire point is to
// see exactly what Tally says, including the parts we do not yet understand.
func (c *Client) Query(ctx context.Context, d Diagnostics, label, xmlPayload string) (string, time.Duration, error) {
	if err := d.Vet(xmlPayload); err != nil {
		return "", 0, err
	}

	c.log.Info("diagnostic query", "label", label, "bytes", len(xmlPayload))

	start := time.Now()
	body, err := c.post(ctx, []byte(xmlPayload))
	elapsed := time.Since(start)
	if err != nil {
		return "", elapsed, err
	}

	max := d.MaxBytes
	if max <= 0 {
		max = 8 << 20
	}
	out := string(body)
	if len(out) > max {
		out = out[:max] + fmt.Sprintf("\n<!-- truncated at %d bytes -->", max)
	}

	c.log.Info("diagnostic query done",
		"label", label, "bytes", len(body), "ms", elapsed.Milliseconds())
	return out, elapsed, nil
}
