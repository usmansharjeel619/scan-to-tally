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
	MsgHello      MsgType = "hello"       // connector -> relay, on connect
	MsgHelloAck   MsgType = "hello_ack"   // relay -> connector
	MsgJob        MsgType = "job"         // relay -> connector
	MsgJobResult  MsgType = "job_result"  // connector -> relay
	MsgHeartbeat  MsgType = "heartbeat"   // connector -> relay, carries health
	MsgSyncPush   MsgType = "sync_push"   // connector -> relay, master data
	MsgSyncReq    MsgType = "sync_req"    // relay -> connector, "resync now"
)

// Frame wraps every message. Payload is the type-specific body.
type Frame struct {
	Type    MsgType `json:"type"`
	ID      string  `json:"id,omitempty"`
	Payload any     `json:"payload,omitempty"`
}

// Hello identifies the connector and the Tally it fronts.
type Hello struct {
	ConnectorID      string `json:"connectorId"`
	Version          string `json:"version"`
	Company          string `json:"company"`
	Hostname         string `json:"hostname"`
	TallyVersion     string `json:"tallyVersion,omitempty"`
	ProtocolVersion  int    `json:"protocolVersion"`
}

// Heartbeat is what drives the device's status bar. Operators tolerate delay;
// they do not tolerate not knowing, so this is reported honestly rather than
// smoothed over.
type Heartbeat struct {
	Health      string    `json:"health"` // ONLINE|BUSY|COMPANY_CLOSED|OFFLINE|UNKNOWN
	LastSeen    time.Time `json:"lastSeen"`
	LastError   string    `json:"lastError,omitempty"`
	QueuedJobs  int       `json:"queuedJobs"`
	FailedJobs  int       `json:"failedJobs"`
	SentAt      time.Time `json:"sentAt"`
}

// JobKind distinguishes the two flows.
type JobKind string

const (
	KindIncoming JobKind = "INCOMING"
	KindOutgoing JobKind = "OUTGOING"
)

// Box is one physical carton on a job line.
type Box struct {
	// BoxSerial becomes the Tally batch name verbatim. No transform, so an
	// outgoing scan reproduces the receipt's batch name byte for byte.
	BoxSerial string `json:"boxSerial"`
	Qty       float64 `json:"qty"`
	// MfgDate derived from the serial's date prefix, when it parsed.
	MfgDate *time.Time `json:"mfgDate,omitempty"`
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
	StockItemName string  `json:"stockItemName"`
	Unit          string  `json:"unit"`
	Description   string  `json:"description,omitempty"`
	Boxes         []Box   `json:"boxes"`
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

	// VoucherType overrides the default for the kind, for companies that have
	// renamed their voucher types or prefer Purchase over Receipt Note.
	VoucherType string `json:"voucherType,omitempty"`

	Date      time.Time `json:"date"`
	Operator  string    `json:"operator,omitempty"`
	DeviceID  string    `json:"deviceId,omitempty"`
	Narration string    `json:"narration,omitempty"`

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
	Company   string         `json:"company"`
	SyncedAt  time.Time      `json:"syncedAt"`
	Items     []SyncItem     `json:"items,omitempty"`
	Godowns   []string       `json:"godowns,omitempty"`
	Balances  []SyncBalance  `json:"balances,omitempty"`
	Orders    []SyncOrder    `json:"orders,omitempty"`
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
