package protocol

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// Date is a calendar date with no time and no timezone.
//
// A manufacturing date read off a carton label is exactly that: 24 Nov 2024,
// not an instant. Carrying it as a timestamp invites a timezone conversion to
// shift it across midnight and report the wrong day in Tally, which is the sort
// of bug that surfaces months later in an expiry report.
type Date struct{ time.Time }

const dateLayout = "2006-01-02"

func NewDate(t time.Time) Date {
	return Date{time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)}
}

func (d Date) MarshalJSON() ([]byte, error) {
	if d.IsZero() {
		return []byte("null"), nil
	}
	return json.Marshal(d.Format(dateLayout))
}

func (d *Date) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	if s == "" || s == "null" {
		d.Time = time.Time{}
		return nil
	}
	// Accept a full timestamp too: older clients and hand-written payloads send
	// one, and the date part is all we keep either way.
	for _, layout := range []string{dateLayout, time.RFC3339, "2006-01-02T15:04:05.000Z"} {
		if t, err := time.Parse(layout, s); err == nil {
			d.Time = time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
			return nil
		}
	}
	return fmt.Errorf("%q is not a calendar date (want YYYY-MM-DD)", s)
}

// AsTime returns the date as a UTC midnight instant, or nil when unset.
func (d *Date) AsTime() *time.Time {
	if d == nil || d.IsZero() {
		return nil
	}
	t := d.Time
	return &t
}
