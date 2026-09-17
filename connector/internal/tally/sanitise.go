package tally

import (
	"bytes"
	"regexp"
	"strconv"
)

// Tally marks a field that has no value with U+0004 in front of the default
// label: "<PARENT>&#4; Primary</PARENT>", "<CATEGORY>&#4; Not Applicable</CATEGORY>".
//
// Go's XML parser refuses a character reference to a control character, and it
// refuses the whole document for it -- one such field and nothing parses. Every
// stock item sits under Primary, so the master sync worked only while the
// company had no stock items at all, and broke permanently one minute after the
// first one was created. It then failed in silence for half an hour, which is
// how a warehouse ends up despatching against figures from hours ago.
//
// The marker means "nothing here", so it is simply removed, leaving the label
// behind for the callers that trim it.
var (
	numericRef = regexp.MustCompile(`&#[xX]?[0-9a-fA-F]+;`)
	rawControl = regexp.MustCompile(`[\x00-\x08\x0b\x0c\x0e-\x1f]`)
)

// sanitiseTallyXML strips character references and raw bytes that XML 1.0 does
// not permit, leaving everything else untouched.
func sanitiseTallyXML(body []byte) []byte {
	if !bytes.Contains(body, []byte("&#")) && !rawControl.Match(body) {
		return body // the common case, and it copies nothing
	}

	out := numericRef.ReplaceAllFunc(body, func(ref []byte) []byte {
		digits := ref[2 : len(ref)-1]
		base := 10
		if digits[0] == 'x' || digits[0] == 'X' {
			digits, base = digits[1:], 16
		}
		n, err := strconv.ParseInt(string(digits), base, 32)
		if err != nil {
			return ref // not a number we understand; leave it for the parser
		}
		if isLegalXMLChar(rune(n)) {
			return ref
		}
		return nil
	})

	return rawControl.ReplaceAll(out, nil)
}

// isLegalXMLChar reports whether a code point may appear in an XML 1.0
// document at all. Tab, newline and carriage return are the only control
// characters that may.
func isLegalXMLChar(r rune) bool {
	switch {
	case r == 0x09, r == 0x0a, r == 0x0d:
		return true
	case r >= 0x20 && r <= 0xd7ff:
		return true
	case r >= 0xe000 && r <= 0xfffd:
		return true
	case r >= 0x10000 && r <= 0x10ffff:
		return true
	default:
		return false
	}
}
