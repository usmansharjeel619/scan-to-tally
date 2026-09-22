package tally

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Health is what the device's status bar shows. Operators tolerate delay; they
// do not tolerate not knowing, so this is surfaced honestly all the way to the
// top of the app rather than hidden behind a spinner.
type Health string

const (
	HealthOnline        Health = "ONLINE"
	HealthBusy          Health = "BUSY"           // responding, but slowly
	HealthCompanyClosed Health = "COMPANY_CLOSED" // running, company not loaded
	HealthOffline       Health = "OFFLINE"        // not running / unreachable
	HealthUnknown       Health = "UNKNOWN"        // no probe yet
)

// Config describes the Tally instance on this machine.
type Config struct {
	// BaseURL is always a loopback address. The gateway has no authentication
	// whatsoever, so binding it to anything routable would publish the whole
	// book to the network.
	BaseURL string
	// Company is the exact company name as Tally spells it.
	Company string
	// Timeout bounds a single request. Tally blocks while a modal dialog is
	// open in its UI, so this must be generous but finite.
	Timeout time.Duration
	// ProbeInterval is how often the health heartbeat runs.
	ProbeInterval time.Duration
	// TDL overrides the built-in collection definitions. Tally's TDL surface
	// varies by version and company configuration, so a site must be able to
	// correct a query from its config file rather than waiting on a rebuild of
	// the Windows service.
	TDL TDLOverrides
}

func (c Config) withDefaults() Config {
	if c.BaseURL == "" {
		c.BaseURL = "http://127.0.0.1:9000"
	}
	if c.Timeout == 0 {
		c.Timeout = 30 * time.Second
	}
	if c.ProbeInterval == 0 {
		c.ProbeInterval = 15 * time.Second
	}
	return c
}

// Client is the only thing in the system that talks to Tally.
//
// Every request goes through a single mutex. Tally's gateway processes requests
// serially, and firing concurrent imports at it produces timeouts and, worse,
// partially applied vouchers.
type Client struct {
	cfg  Config
	http *http.Client
	log  *slog.Logger

	// mu serialises ALL traffic to Tally. Do not remove it for throughput;
	// there is none to gain.
	mu sync.Mutex

	health   atomic.Value // Health
	lastSeen atomic.Value // time.Time
	lastErr  atomic.Value // string

	// breaker widens the gap between probes when Tally is unwell, so an
	// unhealthy machine is not polled every 15 seconds all night.
	breaker *Breaker
	// crashes counts how often Tally has died mid-request.
	crashes atomic.Int64
}

// NewClient builds a client. It does not contact Tally; call Probe or Run.
func NewClient(cfg Config, log *slog.Logger) *Client {
	cfg = cfg.withDefaults()
	if log == nil {
		log = slog.Default()
	}
	c := &Client{
		cfg:     cfg,
		log:     log,
		http:    &http.Client{Timeout: cfg.Timeout},
		breaker: NewBreaker(cfg.ProbeInterval, 15*time.Minute),
	}
	c.health.Store(HealthUnknown)
	c.lastSeen.Store(time.Time{})
	c.lastErr.Store("")
	return c
}

// Company returns the configured company name.
func (c *Client) Company() string { return c.cfg.Company }

// Health reports the last observed state.
func (c *Client) Health() Health { return c.health.Load().(Health) }

// LastSeen reports when Tally last answered successfully. The device shows this
// next to cached balances so a stale figure is visibly stale.
func (c *Client) LastSeen() time.Time { return c.lastSeen.Load().(time.Time) }

// LastError reports the most recent probe failure, for the status screen.
func (c *Client) LastError() string { return c.lastErr.Load().(string) }

// post sends one XML envelope. Callers never reach the network concurrently.
func (c *Client) post(ctx context.Context, payload []byte) ([]byte, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.postLocked(ctx, payload)
}

func (c *Client) postLocked(ctx context.Context, payload []byte) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.cfg.BaseURL, bytes.NewReader(payload))
	if err != nil {
		return nil, transient("BAD_REQUEST", err.Error(), err)
	}
	// Tally is content-type agnostic but some builds are fussy about charset.
	req.Header.Set("Content-Type", "text/xml; charset=utf-8")

	start := time.Now()
	resp, err := c.http.Do(req)
	if err != nil {
		e := classifyTransport(err)
		c.noteFailure(e)
		return nil, e
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		e := transient("READ_FAILED", err.Error(), err)
		c.noteFailure(e)
		return nil, e
	}

	elapsed := time.Since(start)
	if resp.StatusCode != http.StatusOK {
		e := transient("HTTP_"+fmt.Sprint(resp.StatusCode),
			fmt.Sprintf("Tally returned HTTP %d", resp.StatusCode), nil)
		c.noteFailure(e)
		return nil, e
	}

	c.noteSuccess(elapsed)
	c.log.Debug("tally request ok", "bytes", len(body), "ms", elapsed.Milliseconds())
	return body, nil
}

func (c *Client) noteSuccess(elapsed time.Duration) {
	c.breaker.Success()
	c.lastSeen.Store(time.Now())
	c.lastErr.Store("")
	// A response that takes most of the timeout usually means somebody is
	// sitting in a Tally dialog. Report it rather than pretending all is well.
	if elapsed > c.cfg.Timeout/2 {
		c.health.Store(HealthBusy)
	} else {
		c.health.Store(HealthOnline)
	}
}

func (c *Client) noteFailure(e *Error) {
	c.lastErr.Store(e.Message)

	switch e.Code {
	case "TALLY_CRASHED":
		// A crash is not an ordinary transient. Go straight to the longest
		// backoff rather than climbing there one failure at a time, because
		// every further request lands on a process that has just fallen over.
		c.crashes.Add(1)
		c.breaker.Trip()
		c.health.Store(HealthOffline)
		c.log.Error("Tally appears to have CRASHED during a request. "+
			"Backing right off and not retrying quickly. This machine needs attention.",
			"crashesSeen", c.crashes.Load(), "err", e.Message)
		return
	case "COMPANY_NOT_OPEN":
		c.health.Store(HealthCompanyClosed)
	case "TALLY_BUSY", "TIMEOUT":
		c.health.Store(HealthBusy)
	case "TALLY_NOT_RUNNING", "TRANSPORT", "EMPTY_RESPONSE":
		c.health.Store(HealthOffline)
	}

	// A closed port is unambiguous and backs off faster than a timeout.
	c.breaker.Failure(e.Code == "TALLY_NOT_RUNNING")
}

// Crashes reports how many times Tally has died mid-request since start.
// Surfaced in the heartbeat: repeated crashes mean the installation is damaged
// and no amount of retrying will help.
func (c *Client) Crashes() int64 { return c.crashes.Load() }

// noteAppError updates health from an application-level error that arrived
// inside a *successful* HTTP response.
//
// Tally answers 200 OK with a <LINEERROR> body when the company is not open, so
// the transport layer sees a perfectly healthy exchange while the warehouse is
// in fact cut off. Without this the status bar would read ONLINE while every
// job failed.
func (c *Client) noteAppError(err error) error {
	var e *Error
	if errors.As(err, &e) {
		c.noteFailure(e)
	}
	return err
}

// Import posts a voucher and returns Tally's accounting of what it did.
//
// This does NOT implement idempotency. Retry safety lives one layer up in the
// job runner, which checks the local uuid -> voucher map before ever calling
// here. A blind retry of this method creates a second voucher.
func (c *Client) Import(ctx context.Context, v Voucher) (*ImportResult, error) {
	payload, err := BuildImport(c.cfg.Company, v)
	if err != nil {
		// A malformed voucher is our bug, not Tally's; retrying cannot help.
		return nil, business("INVALID_VOUCHER", err.Error())
	}
	c.log.Info("importing voucher",
		"type", v.Type, "ref", v.Reference, "entries", len(v.Entries),
		"remoteId", v.RemoteID, "altering", v.Alter)

	body, err := c.post(ctx, payload)
	if err != nil {
		return nil, err
	}
	res, err := ParseImportResponse(body)

	// Reading the answer rather than assuming the request was honoured.
	//
	// An ALTER that CREATED something is the one outcome worse than failing:
	// asking Tally to replace voucher 39 and having it raise voucher 43
	// instead doubles the stock on the books, looks like success in every
	// counter, and is found weeks later by somebody counting a shelf.
	//
	// And an alter that did nothing means the voucher is gone -- deleted by
	// hand, or the books restored from an older backup. That is not the
	// generic "created nothing" it would otherwise be reported as: it has its
	// own recovery, which is to forget the id and raise a fresh voucher.
	if v.Alter && res != nil {
		switch {
		case res.Created > 0:
			return res, c.noteAppError(business("ALTER_BECAME_CREATE", fmt.Sprintf(
				"Asked Tally to update voucher %s and it created a new one (%s) instead. "+
					"That would double the stock, so this has been stopped. The new "+
					"voucher needs deleting by hand.", v.RemoteID, res.LastVchID)))
		case res.Altered == 0 && res.Errors == 0 && res.LineError == "":
			return res, c.noteAppError(business("ALTER_TARGET_MISSING", fmt.Sprintf(
				"Voucher %s is not in Tally any more, so there was nothing to add to. "+
					"A new voucher will be raised for this product instead.",
				v.RemoteID)))
		}
	}

	if err != nil {
		return res, c.noteAppError(err)
	}
	return res, nil
}

// Probe asks Tally for something cheap to establish whether it is alive and has
// the company open.
//
// It checks the NAME, not merely that Tally answered. The list was fetched and
// thrown away before, so a connector configured against a company nobody had
// loaded -- or against a name that is one character off the real one, which is
// the same thing to Tally -- reported ONLINE and went on reporting it. Every
// job then failed one at a time, far from the cause, and the status bar the
// warehouse actually looks at said all was well.
func (c *Client) Probe(ctx context.Context) error {
	companies, err := c.ListCompanies(ctx)
	if err != nil {
		return err
	}

	want := strings.TrimSpace(c.cfg.Company)
	if want == "" {
		return nil // unconfigured; nothing to check against
	}
	open := make([]string, 0, len(companies))
	for _, co := range companies {
		name := strings.TrimSpace(co.Name)
		if strings.EqualFold(name, want) {
			return nil
		}
		open = append(open, name)
	}

	// Transient by deliberate choice: opening the company fixes it, and the
	// next probe will see that without anybody restarting the service.
	return c.noteAppError(transient("COMPANY_NOT_OPEN", fmt.Sprintf(
		"Tally is running but %q is not open. Open now: %s.",
		want, joinOr(open, "nothing")), nil))
}

// joinOr lists names for a person to read, or a stand-in when there are none.
func joinOr(names []string, empty string) string {
	if len(names) == 0 {
		return empty
	}
	return strings.Join(names, ", ")
}

// Run drives the health heartbeat until ctx is cancelled.
//
// The interval is adaptive rather than fixed: a healthy Tally is checked every
// ProbeInterval, and an unhealthy one progressively less often, up to fifteen
// minutes. Any success snaps straight back to the fast interval, so a Tally
// that comes back is noticed within one probe rather than after a long wait.
func (c *Client) Run(ctx context.Context) {
	probe := func() {
		c.breaker.Attempted(time.Now())
		pctx, cancel := context.WithTimeout(ctx, c.cfg.Timeout)
		defer cancel()

		wasQuiescent := c.breaker.Quiescent()
		if err := c.Probe(pctx); err != nil {
			fails, next, quiet := c.breaker.State()
			if quiet && !wasQuiescent {
				// Say this once, loudly. It is the line that should send
				// somebody to look at the machine.
				c.log.Error("Tally has not answered for a long time; backing right off. "+
					"Something on that machine needs attention.",
					"consecutiveFailures", fails, "nextAttemptIn", next)
			} else if !quiet {
				c.log.Warn("tally probe failed",
					"err", err, "health", c.Health(), "failures", fails, "retryIn", next)
			}
			return
		}
		if wasQuiescent {
			c.log.Info("Tally is answering again")
		}
	}

	probe()
	for {
		wait := c.breaker.Interval()
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
			probe()
		}
	}
}

// BreakerState exposes the backoff for the status page and the heartbeat.
func (c *Client) BreakerState() (failures int, nextIn time.Duration, quiescent bool) {
	return c.breaker.State()
}
