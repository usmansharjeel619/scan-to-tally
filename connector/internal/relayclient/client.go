// Package relayclient holds the outbound WebSocket to the relay.
//
// The connection is always dialled OUT. That is the entire point of the
// architecture: Tally's gateway has no authentication, so it must never be
// reachable from a network. An outbound socket means no inbound port, no
// firewall exception, no static IP, and nothing on the warehouse machine that
// the internet can reach.
package relayclient

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"math"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/acme/scan-to-tally/connector/internal/protocol"
	"github.com/acme/scan-to-tally/connector/internal/store"
)

// HealthSource reports Tally's state for the heartbeat.
type HealthSource interface {
	Health() string
	LastSeen() time.Time
	LastError() string
}

type Config struct {
	URL         string // wss://relay.example.com/connector/ws
	Secret      string
	ConnectorID string
	Company     string
	Version     string

	HeartbeatInterval time.Duration
	// SyncInterval controls how often master data and batch balances are
	// pushed up. Balances drive the outgoing quantity ceiling, so this is the
	// knob that decides how stale a device's figure can be.
	SyncInterval time.Duration
}

func (c Config) withDefaults() Config {
	if c.HeartbeatInterval == 0 {
		c.HeartbeatInterval = 15 * time.Second
	}
	if c.SyncInterval == 0 {
		c.SyncInterval = 2 * time.Minute
	}
	if c.Version == "" {
		c.Version = "0.1.0"
	}
	if c.ConnectorID == "" {
		host, _ := os.Hostname()
		c.ConnectorID = "connector-" + host
	}
	return c
}

// Syncer produces the master-data snapshot pushed to the relay.
type Syncer func(ctx context.Context) (*protocol.SyncPush, error)

// Querier runs a vetted read-only Tally query. Nil disables diagnostics
// entirely, which is the default.
type Querier func(ctx context.Context, label, xml string) (string, time.Duration, error)

type Client struct {
	cfg    Config
	st     *store.Store
	health HealthSource
	sync       Syncer
	query      Querier
	createItem ItemCreator
	log        *slog.Logger

	mu   sync.Mutex
	conn *websocket.Conn
}

func New(cfg Config, st *store.Store, health HealthSource, sync Syncer, log *slog.Logger) *Client {
	if log == nil {
		log = slog.Default()
	}
	return &Client{cfg: cfg.withDefaults(), st: st, health: health, sync: sync, log: log}
}

// SetQuerier enables the Phase 0 read-only diagnostic channel. Leaving it unset
// means a diag request is refused outright.
func (c *Client) SetQuerier(q Querier) { c.query = q }

// Run keeps a connection up until ctx is cancelled, reconnecting with backoff.
//
// A disconnected relay is never fatal: jobs already in SQLite keep draining
// into Tally, and new ones are redelivered on reconnect. The warehouse does not
// stop because the internet did.
func (c *Client) Run(ctx context.Context) {
	attempt := 0
	for {
		if ctx.Err() != nil {
			return
		}
		err := c.session(ctx)
		if ctx.Err() != nil {
			return
		}

		attempt++
		backoff := time.Duration(math.Min(
			float64(time.Second)*math.Pow(2, math.Min(float64(attempt), 6)),
			float64(60*time.Second)))
		c.log.Warn("relay disconnected; reconnecting", "err", err, "in", backoff)

		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
	}
}

func (c *Client) session(ctx context.Context) error {
	dialCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()

	conn, _, err := websocket.Dial(dialCtx, c.cfg.URL, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": []string{"Bearer " + c.cfg.Secret}},
	})
	if err != nil {
		return fmt.Errorf("dial relay: %w", err)
	}
	// Master-data pushes can be large on a big item master.
	conn.SetReadLimit(64 << 20)
	defer conn.CloseNow()

	c.mu.Lock()
	c.conn = conn
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		c.conn = nil
		c.mu.Unlock()
	}()

	host, _ := os.Hostname()
	if err := c.send(ctx, protocol.Frame{
		Type: protocol.MsgHello,
		Payload: protocol.Hello{
			ConnectorID: c.cfg.ConnectorID, Version: c.cfg.Version,
			Company: c.cfg.Company, Hostname: host, ProtocolVersion: 1,
		},
	}); err != nil {
		return fmt.Errorf("hello: %w", err)
	}
	c.log.Info("connected to relay", "url", c.cfg.URL, "connector", c.cfg.ConnectorID)

	sessCtx, stop := context.WithCancel(ctx)
	defer stop()
	go c.heartbeatLoop(sessCtx)
	go c.syncLoop(sessCtx)

	for {
		_, data, err := conn.Read(sessCtx)
		if err != nil {
			return err
		}
		var frame struct {
			Type    protocol.MsgType `json:"type"`
			ID      string           `json:"id"`
			Payload json.RawMessage  `json:"payload"`
		}
		if err := json.Unmarshal(data, &frame); err != nil {
			c.log.Warn("unreadable frame from relay", "err", err)
			continue
		}

		switch frame.Type {
		case protocol.MsgJob:
			// A master creation is administrative, not warehouse work, so it
			// does not go through the durable voucher queue.
			if bytes.Contains(frame.Payload, []byte(`"CREATE_STOCK_ITEM"`)) {
				go c.handleCreateItem(sessCtx, frame.ID, frame.Payload)
				continue
			}
			c.acceptJob(sessCtx, frame.ID, frame.Payload)
		case protocol.MsgHelloAck:
			// nothing to do
		case protocol.MsgSyncReq:
			c.pushSync(sessCtx)
		case protocol.MsgDiagReq:
			go c.handleDiag(sessCtx, frame.Payload)
		}
	}
}

// ItemCreator creates a stock item in Tally. Nil means this connector is not
// permitted to write to the item master, which is the default.
type ItemCreator func(ctx context.Context, j protocol.CreateStockItemJob) (string, error)

// SetItemCreator enables supervisor-approved master creation.
func (c *Client) SetItemCreator(f ItemCreator) { c.createItem = f }

// handleCreateItem runs a supervisor-approved stock item creation.
//
// Kept off the job queue deliberately: this is not warehouse work that must
// survive a restart and drain in order. It is a one-off administrative act
// that a human is waiting on, and if it fails they should be told now.
func (c *Client) handleCreateItem(ctx context.Context, jobID string, payload json.RawMessage) {
	var j protocol.CreateStockItemJob
	if err := json.Unmarshal(payload, &j); err != nil {
		c.log.Error("unreadable create-item job", "job", jobID, "err", err)
		return
	}
	res := protocol.JobResult{SessionID: j.PID, JobID: jobID, CompletedAt: time.Now()}

	if c.createItem == nil {
		res.ErrorClass, res.ErrorCode = "BUSINESS", "MASTER_CREATE_DISABLED"
		res.ErrorMessage = "This connector is not permitted to create stock items."
		c.log.Warn("refused a create-item job: master creation is disabled", "pid", j.PID)
	} else if name, err := c.createItem(ctx, j); err != nil {
		res.ErrorClass, res.ErrorCode = "BUSINESS", "ITEM_CREATE_FAILED"
		res.ErrorMessage = err.Error()
	} else {
		res.OK = true
		res.TallyVoucherID = name
	}
	if err := c.send(ctx, protocol.Frame{Type: protocol.MsgJobResult, Payload: res}); err != nil {
		c.log.Warn("could not report create-item result", "pid", j.PID, "err", err)
	}
}

// acceptJob puts a job in SQLite and returns. It deliberately does NOT post to
// Tally inline: durability first, so a job survives a crash between accepting
// and posting, and so the single Tally worker stays the only thing talking to
// Tally.
func (c *Client) acceptJob(ctx context.Context, jobID string, payload json.RawMessage) {
	var pj protocol.PostVoucherJob
	if err := json.Unmarshal(payload, &pj); err != nil {
		c.log.Error("unreadable job", "job", jobID, "err", err)
		return
	}
	if jobID == "" {
		jobID = pj.SessionID
	}

	if err := c.st.Enqueue(ctx, store.Job{
		ID: jobID, SessionID: pj.SessionID, Kind: string(pj.Kind), Payload: payload,
	}); err != nil {
		c.log.Error("could not enqueue job", "job", jobID, "err", err)
		return
	}
	c.log.Info("job accepted", "job", jobID, "session", pj.SessionID, "lines", len(pj.Lines))
}

// handleDiag answers a read-only Tally query from the relay.
//
// Runs in its own goroutine so a slow query does not stall the read loop and
// make the relay think the connector has gone away. The Tally client serialises
// internally, so this still cannot collide with a voucher being posted.
func (c *Client) handleDiag(ctx context.Context, payload json.RawMessage) {
	var req protocol.DiagRequest
	if err := json.Unmarshal(payload, &req); err != nil {
		c.log.Warn("unreadable diag request", "err", err)
		return
	}

	res := protocol.DiagResponse{ID: req.ID}
	if c.query == nil {
		res.Error = "diagnostics are disabled on this connector"
		c.log.Warn("diag request refused: diagnostics disabled", "label", req.Label)
	} else {
		qctx, cancel := context.WithTimeout(ctx, 120*time.Second)
		xml, elapsed, err := c.query(qctx, req.Label, req.XML)
		cancel()
		if err != nil {
			res.Error = err.Error()
		} else {
			res.OK = true
			res.XML = xml
			res.Bytes = len(xml)
			res.Millis = elapsed.Milliseconds()
		}
	}

	if err := c.send(ctx, protocol.Frame{Type: protocol.MsgDiagRes, Payload: res}); err != nil {
		c.log.Warn("could not return diag result", "id", req.ID, "err", err)
	}
}

// Report implements runner.Reporter.
func (c *Client) Report(ctx context.Context, res protocol.JobResult) error {
	return c.send(ctx, protocol.Frame{Type: protocol.MsgJobResult, Payload: res})
}

func (c *Client) send(ctx context.Context, frame protocol.Frame) error {
	c.mu.Lock()
	conn := c.conn
	c.mu.Unlock()
	if conn == nil {
		return fmt.Errorf("not connected to relay")
	}
	data, err := json.Marshal(frame)
	if err != nil {
		return err
	}
	wctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	return conn.Write(wctx, websocket.MessageText, data)
}

func (c *Client) heartbeatLoop(ctx context.Context) {
	t := time.NewTicker(c.cfg.HeartbeatInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			counts, _ := c.st.Counts(ctx)
			hb := protocol.Heartbeat{
				Health:     c.health.Health(),
				LastSeen:   c.health.LastSeen(),
				LastError:  c.health.LastError(),
				QueuedJobs: counts[store.JobQueued],
				FailedJobs: counts[store.JobFailed],
				SentAt:     time.Now(),
			}
			if err := c.send(ctx, protocol.Frame{Type: protocol.MsgHeartbeat, Payload: hb}); err != nil {
				return // the read loop will notice and reconnect
			}
		}
	}
}

func (c *Client) syncLoop(ctx context.Context) {
	c.pushSync(ctx)
	t := time.NewTicker(c.cfg.SyncInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			c.pushSync(ctx)
		}
	}
}

func (c *Client) pushSync(ctx context.Context) {
	if c.sync == nil {
		return
	}
	snap, err := c.sync(ctx)
	if err != nil {
		// Almost always "Tally is closed". Not worth more than a debug line;
		// the heartbeat already tells the relay what state Tally is in.
		c.log.Debug("master sync skipped", "err", err)
		return
	}
	if err := c.send(ctx, protocol.Frame{Type: protocol.MsgSyncPush, Payload: snap}); err != nil {
		c.log.Warn("could not push master data", "err", err)
		return
	}
	c.log.Info("master data pushed",
		"items", len(snap.Items), "balances", len(snap.Balances), "orders", len(snap.Orders))
}
