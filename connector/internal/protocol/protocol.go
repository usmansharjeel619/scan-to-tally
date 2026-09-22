// Package protocol is the wire contract between the relay and the connector.
//
// MIRRORED IN TYPESCRIPT at relay/src/protocol.ts. The two must stay in step;
// contracts/protocol.schema.json is generated from here and both sides validate
// against it.
//
// Division of responsibility, which matters for where bugs land:
//
//	relay      resolves PID -> Tally stock item name, resolves units, applies
//	           the duplicate rules and the sales-order ceiling. It holds the
//	           item master and the PID bindings, so identity lives there.
//	connector  posts what it is told, but independently re-checks the live
//	           batch balance before an outgoing import, because the device's
//	           cached figure is minutes old and stock may have moved.
package protocol

import "time"

// MsgType identifies a frame on the connector's WebSocket.
type MsgType string

const (
	MsgHello     MsgType = "hello"      // connector -> relay, on connect
	MsgHelloAck  MsgType = "hello_ack"  // relay -> connector
	MsgJob       MsgType = "job"        // relay -> connector
	MsgJobResult MsgType = "job_result" // connector -> relay
	MsgHeartbeat MsgType = "heartbeat"  // connector -> relay, carries health
	MsgSyncPush  MsgType = "sync_push"  // connector -> relay, master data
	MsgSyncReq   MsgType = "sync_req"   // relay -> connector, "resync now"

	// Phase 0 only. Lets the relay run a READ-ONLY Tally query so the XML
	// templates can be reconciled against a real installation. Refused unless
	// diagnostics are explicitly enabled in the connector's config, and refused
	// for anything that is not an Export.
	MsgDiagReq MsgType = "diag_req"
	MsgDiagRes MsgType = "diag_res"
)

// DiagRequest carries a raw Tally envelope for a read-only query.
type DiagRequest struct {
	ID  string `json:"id"`
	XML string `json:"xml"`
	// Label is what shows in the connector's log, so whoever owns the Tally
	// machine can see exactly what was asked and why.
	Label string `json:"label,omitempty"`
}

// DiagResponse is Tally's raw answer, unparsed.
type DiagResponse struct {
	ID     string `json:"id"`
	OK     bool   `json:"ok"`
	XML    string `json:"xml,omitempty"`
	Error  string `json:"error,omitempty"`
	Bytes  int    `json:"bytes,omitempty"`
	Millis int64  `json:"millis,omitempty"`
}

// Frame wraps every message. Payload is the type-specific body.
type Frame struct {
	Type    MsgType `json:"type"`
	ID      string  `json:"id,omitempty"`
	Payload any     `json:"payload,omitempty"`
}

// Hello identifies the connector and the Tally it fronts.
type Hello struct {
	ConnectorID     string `json:"connectorId"`
	Version         string `json:"version"`
	Company         string `json:"company"`
	Hostname        string `json:"hostname"`
	TallyVersion    string `json:"tallyVersion,omitempty"`
	ProtocolVersion int    `json:"protocolVersion"`
	// CanCreateItems reports whether this connector is permitted to add stock
	// items. Announced rather than discovered from a failure, so the relay can
	// say why a new product is not appearing instead of leaving it queued.
	CanCreateItems bool `json:"canCreateItems"`
}

// Heartbeat is what drives the device's status bar. Operators tolerate delay;
// they do not tolerate not knowing, so this is reported honestly rather than
// smoothed over.
type Heartbeat struct {
	// SyncError is why master data last failed to refresh, or empty.
	//
	// Reported because a sync that fails forever in silence is indistinguishable
	// from one that is working: stock figures simply stop moving, and the first
	// anyone knows is a despatch checked against a balance from hours ago.
	SyncError string `json:"syncError,omitempty"`

	Health     string    `json:"health"` // ONLINE|BUSY|COMPANY_CLOSED|OFFLINE|UNKNOWN
	LastSeen   time.Time `json:"lastSeen"`
	LastError  string    `json:"lastError,omitempty"`
	QueuedJobs int       `json:"queuedJobs"`
	FailedJobs int       `json:"failedJobs"`
	SentAt     time.Time `json:"sentAt"`
}

// JobKind distinguishes the two flows.
type JobKind string

const (
	KindIncoming   JobKind = "INCOMING"
	KindOutgoing   JobKind = "OUTGOING"
	KindStockCheck JobKind = "STOCKCHECK"
	// KindCreateStockItem adds a product to Tally's item master. Issued only
	// after a supervisor has approved it; an operator cannot cause one.
	KindCreateStockItem JobKind = "CREATE_STOCK_ITEM"
)

// CreateStockItemJob is a supervisor-approved new product.
//
// Typed rather than free-form XML on purpose: the connector builds the request
// itself, so no caller can hand Tally a document. A stock item cannot be
// deleted once it has transactions, so this is effectively permanent.
type CreateStockItemJob struct {
	PID          string `json:"pid"`
	Name         string `json:"name"`
	BaseUnits    string `json:"baseUnits"`
	Batchwise    bool   `json:"batchwise"`
	TrackMfgDate bool   `json:"trackMfgDate"`
	Company      string `json:"company"`
}

// CountScope decides what an inventory check is allowed to write.
//
// PARTIAL is the default and only ever touches boxes that were counted. FULL
// additionally zeroes boxes the book shows in the godown but nobody scanned,
// which is correct for a complete wall-to-wall count and catastrophic for a
// count somebody abandoned halfway. FULL therefore requires an explicit
// operator confirmation upstream.
type CountScope string

const (
	ScopePartial CountScope = "PARTIAL"
	ScopeFull    CountScope = "FULL"
)

// Box is one physical carton on a job line.
type Box struct {
	// BoxSerial becomes the Tally batch name verbatim. No transform, so an
	// outgoing scan reproduces the receipt's batch name byte for byte.
	BoxSerial string  `json:"boxSerial"`
	Qty       float64 `json:"qty"`
	// MfgDate derived from the serial's date prefix, when it parsed. A
	// calendar date, never an instant -- see the Date type.
	MfgDate *Date `json:"mfgDate,omitempty"`
	// RawPayload is the untouched scanner output, carried all the way through
	// and stored forever. It is the only evidence available when a scan is
	// later disputed.
	RawPayload string `json:"rawPayload,omitempty"`
	// PID as scanned, kept for audit even though the relay has already
	// resolved it to StockItemName.
	PID string `json:"pid,omitempty"`
	// Manual marks a line typed rather than scanned, so the rate is reportable.
	// A climbing rate means a label-quality or scanner problem at the source.
	Manual bool `json:"manual,omitempty"`
}

// Line is one stock item with every box of it in this session.
//
// One line per part number, N boxes beneath it. The relay aggregates; the
// connector asserts it again before posting, because splitting an item across
// lines is accepted by Tally and quietly ruins its stock reports.
type Line struct {
	StockItemName string `json:"stockItemName"`
	Unit          string `json:"unit"`
	Description   string `json:"description,omitempty"`
	Boxes         []Box  `json:"boxes"`
}

// TotalQty sums the boxes.
func (l Line) TotalQty() float64 {
	var t float64
	for _, b := range l.Boxes {
		t += b.Qty
	}
	return t
}

// PostVoucherJob is one scan session on its way to Tally.
type PostVoucherJob struct {
	// SessionID is the UUID generated ON THE PHONE when the session was
	// created, not when it was sent. It is the idempotency key end to end and
	// is written into Tally's REFERENCE field, so reconciliation survives total
	// loss of the connector's database.
	SessionID string  `json:"sessionId"`
	Kind      JobKind `json:"kind"`

	Company string `json:"company"`
	Godown  string `json:"godown"`
	Party   string `json:"party,omitempty"`

	// SalesOrder links an outgoing Delivery Note back to its Sales Order.
	SalesOrder string `json:"salesOrder,omitempty"`

	// Scope applies to STOCKCHECK only. Empty means PARTIAL.
	Scope CountScope `json:"scope,omitempty"`

	// VoucherType overrides the default for the kind, for companies that have
	// renamed their voucher types or prefer Purchase over Receipt Note.
	VoucherType string `json:"voucherType,omitempty"`

	Date      time.Time `json:"date"`
	Operator  string    `json:"operator,omitempty"`
	DeviceID  string    `json:"deviceId,omitempty"`
	Narration string    `json:"narration,omitempty"`

	// RemoteID is the relay's own name for the voucher, written into Tally's
	// REMOTEID attribute so a later receipt can find it again.
	RemoteID string `json:"remoteId,omitempty"`

	// Alter REPLACES the voucher carrying RemoteID rather than creating one,
	// so a product keeps ONE Physical Stock voucher and new cartons are added
	// to it.
	//
	// When it is set, Lines carry every box the voucher must end up holding --
	// the ones already on it as well as the ones just scanned -- because Tally
	// replaces a voucher on alter instead of merging into it.
	Alter bool `json:"alter,omitempty"`

	Lines []Line `json:"lines"`
}

// JobResult is what the connector reports back.
type JobResult struct {
	SessionID string `json:"sessionId"`
	JobID     string `json:"jobId"`
	OK        bool   `json:"ok"`

	// TallyVoucherID is Tally's own id for the created voucher.
	TallyVoucherID string `json:"tallyVoucherId,omitempty"`
	// Duplicate is true when this session had already been posted and the
	// connector returned the stored result WITHOUT touching Tally. It is a
	// success, not an error -- it means the safety net worked.
	Duplicate bool `json:"duplicate,omitempty"`

	ErrorClass   string    `json:"errorClass,omitempty"` // TRANSIENT|BUSINESS
	ErrorCode    string    `json:"errorCode,omitempty"`
	ErrorMessage string    `json:"errorMessage,omitempty"` // Tally's own words, verbatim
	Attempts     int       `json:"attempts,omitempty"`
	CompletedAt  time.Time `json:"completedAt"`
}

// --- master data pushed up to the relay -------------------------------------

// SyncPush carries what the relay caches and fans out to devices, so scanning
// and validation keep working with no signal at the dock.
type SyncPush struct {
	Company  string    `json:"company"`
	SyncedAt time.Time `json:"syncedAt"`
	// Deliberately NOT omitempty.
	//
	// An empty list is a fact -- "Tally has no stock items" -- and omitting it
	// makes that indistinguishable from "this message carries no master data".
	// The relay could then never tell that something had been deleted, so it
	// kept resolving part numbers to items Tally no longer had.
	Items    []SyncItem    `json:"items"`
	Godowns  []string      `json:"godowns"`
	Balances []SyncBalance `json:"balances"`
	Orders   []SyncOrder   `json:"orders"`
}

type SyncItem struct {
	Name       string `json:"name"`
	Alias      string `json:"alias,omitempty"`
	PartNo     string `json:"partNo,omitempty"`
	BaseUnits  string `json:"baseUnits"`
	HasBatches bool   `json:"hasBatches"`
}

type SyncBalance struct {
	StockItemName string  `json:"stockItemName"`
	BatchName     string  `json:"batchName"`
	GodownName    string  `json:"godownName"`
	ClosingQty    float64 `json:"closingQty"`
	Unit          string  `json:"unit"`
}

type SyncOrderLine struct {
	StockItemName string  `json:"stockItemName"`
	OrderedQty    float64 `json:"orderedQty"`
	DeliveredQty  float64 `json:"deliveredQty"`
	Unit          string  `json:"unit"`
}

type SyncOrder struct {
	VoucherNumber string          `json:"voucherNumber"`
	PartyName     string          `json:"partyName"`
	Date          time.Time       `json:"date"`
	Lines         []SyncOrderLine `json:"lines"`
}
