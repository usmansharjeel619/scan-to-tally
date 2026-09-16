package barcode

import "strings"

// Registry holds the known label dialects and picks the one that recognises a
// payload most confidently. Adding a supplier means appending a Parser here;
// no call site changes.
type Registry struct {
	parsers []Parser
}

// NewRegistry returns the production parser set.
func NewRegistry() *Registry {
	return &Registry{parsers: []Parser{
		SimplexPipe{},
		SimplexShort{},
	}}
}

// Register appends a parser. Order does not matter -- confidence decides.
func (r *Registry) Register(p Parser) { r.parsers = append(r.parsers, p) }

// Parse routes a raw scanner payload to the most confident parser.
//
// An unrecognised payload comes back as Unknown rather than being coerced into
// a best guess. Raw is preserved on every result so the caller can store it
// against the scan line regardless of outcome.
func (r *Registry) Parse(symbology, raw string) Result {
	// Scanners routinely append CR/LF, and manual entry brings its own spaces.
	cleaned := strings.TrimSpace(raw)
	if cleaned == "" {
		return Result{Outcome: Unknown, Raw: raw, Symbology: symbology}
	}

	var best Parser
	var bestScore float64
	for _, p := range r.parsers {
		if s := p.Probe(symbology, cleaned); s > bestScore {
			best, bestScore = p, s
		}
	}
	if best == nil {
		return Result{Outcome: Unknown, Raw: raw, Symbology: symbology}
	}

	res := best.Parse(symbology, cleaned)
	res.Raw = raw // the untouched payload, always
	return res
}
