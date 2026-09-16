// Package store is the connector's durable memory.
//
// Its most important job is preventing double-posted vouchers. Warehouse Wi-Fi
// drops mid-post, the app retries, and without this Tally ends up holding two
// Receipt Notes for one pallet -- stock doubled, and nobody notices for three
// weeks. Tally itself offers no protection: it will cheerfully create a second
// voucher with an identical REFERENCE.
package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

// ErrConflictingRetry means a session id came back with different content than
// when it was posted. That is never a network retry -- it is a bug in the app
// or the relay, and posting it would silently create a second, different
// voucher under the same identity.
var ErrConflictingRetry = errors.New("session replayed with different content")

// JobState tracks a unit of work through the queue.
type JobState string

const (
	JobQueued  JobState = "queued"
	JobRunning JobState = "running"
	JobDone    JobState = "done"
	JobFailed  JobState = "failed" // business error: needs a human, no more retries
)

// Posted records a voucher that reached Tally.
type Posted struct {
	SessionID   string
	Reference   string
	VoucherType string
	TallyVchID  string
	Company     string
	PostedAt    time.Time
	PayloadHash string
}

// Job is one posting attempt, durable across connector restarts.
type Job struct {
	ID          string
	SessionID   string
	Kind        string
	Payload     []byte
	State       JobState
	Attempts    int
	LastError   string
	ErrorClass  string
	CreatedAt   time.Time
	UpdatedAt   time.Time
	NextRetryAt time.Time
}

type Store struct{ db *sql.DB }

const schema = `
CREATE TABLE IF NOT EXISTS posted_vouchers (
  session_id   TEXT PRIMARY KEY,
  reference    TEXT NOT NULL,
  voucher_type TEXT NOT NULL,
  tally_vch_id TEXT NOT NULL,
  company      TEXT NOT NULL,
  posted_at    DATETIME NOT NULL,
  payload_hash TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_posted_reference ON posted_vouchers(reference);

CREATE TABLE IF NOT EXISTS jobs (
  id            TEXT PRIMARY KEY,
  session_id    TEXT NOT NULL,
  kind          TEXT NOT NULL,
  payload       BLOB NOT NULL,
  state         TEXT NOT NULL,
  attempts      INTEGER NOT NULL DEFAULT 0,
  last_error    TEXT NOT NULL DEFAULT '',
  error_class   TEXT NOT NULL DEFAULT '',
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  next_retry_at DATETIME NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_jobs_state ON jobs(state, next_retry_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_jobs_session ON jobs(session_id);
`

// Open prepares the database, creating it if needed.
func Open(path string) (*Store, error) {
	// WAL keeps a reader (the status endpoint) from blocking the job worker.
	db, err := sql.Open("sqlite", path+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)")
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	// SQLite tolerates one writer. Keeping the pool at 1 avoids SQLITE_BUSY
	// entirely, and there is no concurrency to gain: Tally is serial anyway.
	db.SetMaxOpenConns(1)

	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

// HashPayload fingerprints a job body so a retry can be distinguished from a
// different voucher wearing the same session id.
func HashPayload(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// --- idempotency ------------------------------------------------------------

// AlreadyPosted reports whether this session reached Tally, and returns what it
// became. This is checked before every single import; it is the whole defence
// against duplicate vouchers.
//
// A hash mismatch returns ErrConflictingRetry rather than posting: same
// identity with different content is a bug, and the safe move is to refuse and
// let a human look.
func (s *Store) AlreadyPosted(ctx context.Context, sessionID, payloadHash string) (*Posted, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT session_id, reference, voucher_type, tally_vch_id, company, posted_at, payload_hash
		  FROM posted_vouchers WHERE session_id = ?`, sessionID)

	var p Posted
	err := row.Scan(&p.SessionID, &p.Reference, &p.VoucherType, &p.TallyVchID,
		&p.Company, &p.PostedAt, &p.PayloadHash)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("lookup posted: %w", err)
	}
	if payloadHash != "" && p.PayloadHash != payloadHash {
		return &p, fmt.Errorf("%w: session %s was posted as voucher %s",
			ErrConflictingRetry, sessionID, p.TallyVchID)
	}
	return &p, nil
}

// RecordPosted saves the outcome of a successful import.
//
// Write this IMMEDIATELY after Tally confirms, before acknowledging anything
// upstream. A crash between the two leaves a posted voucher we do not know
// about, which is what ReconcileFromTally exists to repair.
func (s *Store) RecordPosted(ctx context.Context, p Posted) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO posted_vouchers
		  (session_id, reference, voucher_type, tally_vch_id, company, posted_at, payload_hash)
		VALUES (?,?,?,?,?,?,?)
		ON CONFLICT(session_id) DO NOTHING`,
		p.SessionID, p.Reference, p.VoucherType, p.TallyVchID, p.Company, p.PostedAt, p.PayloadHash)
	if err != nil {
		return fmt.Errorf("record posted: %w", err)
	}
	return nil
}

// FindByReference supports startup reconciliation: given a REFERENCE read back
// out of Tally, do we already know about it?
func (s *Store) FindByReference(ctx context.Context, reference string) (*Posted, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT session_id, reference, voucher_type, tally_vch_id, company, posted_at, payload_hash
		  FROM posted_vouchers WHERE reference = ? LIMIT 1`, reference)
	var p Posted
	err := row.Scan(&p.SessionID, &p.Reference, &p.VoucherType, &p.TallyVchID,
		&p.Company, &p.PostedAt, &p.PayloadHash)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &p, nil
}

// --- job queue --------------------------------------------------------------

// Enqueue adds a job, ignoring a session we have already queued. The relay may
// redeliver the same job after a reconnect; that must not create a second one.
func (s *Store) Enqueue(ctx context.Context, j Job) error {
	now := time.Now()
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO jobs (id, session_id, kind, payload, state, attempts,
		                  created_at, updated_at, next_retry_at)
		VALUES (?,?,?,?,?,0,?,?,?)
		ON CONFLICT(session_id) DO NOTHING`,
		j.ID, j.SessionID, j.Kind, j.Payload, JobQueued, now, now, now)
	if err != nil {
		return fmt.Errorf("enqueue: %w", err)
	}
	return nil
}

// NextDue claims the oldest job whose retry time has arrived.
//
// There is exactly one worker, so this is a simple claim rather than a
// contended one -- concurrency here would only queue up behind Tally's serial
// gateway anyway.
func (s *Store) NextDue(ctx context.Context, now time.Time) (*Job, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, session_id, kind, payload, state, attempts, last_error,
		       error_class, created_at, updated_at, next_retry_at
		  FROM jobs
		 WHERE state = ? AND next_retry_at <= ?
		 ORDER BY created_at ASC LIMIT 1`, JobQueued, now)

	var j Job
	err := row.Scan(&j.ID, &j.SessionID, &j.Kind, &j.Payload, &j.State, &j.Attempts,
		&j.LastError, &j.ErrorClass, &j.CreatedAt, &j.UpdatedAt, &j.NextRetryAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("next due: %w", err)
	}

	if _, err := s.db.ExecContext(ctx,
		`UPDATE jobs SET state = ?, updated_at = ? WHERE id = ?`,
		JobRunning, time.Now(), j.ID); err != nil {
		return nil, fmt.Errorf("claim job: %w", err)
	}
	j.State = JobRunning
	return &j, nil
}

// CompleteJob marks a job done.
func (s *Store) CompleteJob(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE jobs SET state = ?, updated_at = ?, last_error = '', error_class = '' WHERE id = ?`,
		JobDone, time.Now(), id)
	return err
}

// RetryJob puts a job back with a backoff. Used for TRANSIENT failures only:
// Tally closed, laptop asleep, network gone. These fix themselves, so the
// operator should never see them as anything but a status badge.
func (s *Store) RetryJob(ctx context.Context, id string, attempts int, errMsg string, after time.Duration) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE jobs SET state = ?, attempts = ?, last_error = ?, error_class = ?,
		                updated_at = ?, next_retry_at = ? WHERE id = ?`,
		JobQueued, attempts, errMsg, "TRANSIENT", time.Now(), time.Now().Add(after), id)
	return err
}

// FailJob stops retrying. Used for BUSINESS failures: unknown stock item,
// negative stock, a balance that moved underneath us. Retrying cannot help and
// silently retrying forever is how a queue stalls unnoticed, so this goes in
// front of a supervisor instead.
func (s *Store) FailJob(ctx context.Context, id string, attempts int, errMsg, class string) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE jobs SET state = ?, attempts = ?, last_error = ?, error_class = ?, updated_at = ?
		 WHERE id = ?`, JobFailed, attempts, errMsg, class, time.Now(), id)
	return err
}

// RequeueStuck returns jobs left in 'running' by a crash or a kill mid-import.
//
// These are the dangerous ones: the voucher may or may not have reached Tally.
// They are safe to requeue only because the worker checks AlreadyPosted before
// every import, so a job that did land is recognised rather than repeated.
func (s *Store) RequeueStuck(ctx context.Context) (int, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE jobs SET state = ?, updated_at = ?, next_retry_at = ? WHERE state = ?`,
		JobQueued, time.Now(), time.Now(), JobRunning)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// Counts summarises the queue for the status endpoint and the device's badge.
func (s *Store) Counts(ctx context.Context) (map[JobState]int, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT state, COUNT(*) FROM jobs GROUP BY state`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := map[JobState]int{}
	for rows.Next() {
		var st string
		var n int
		if err := rows.Scan(&st, &n); err != nil {
			return nil, err
		}
		out[JobState(st)] = n
	}
	return out, rows.Err()
}

// FailedJobs lists what needs a supervisor. A failed post must never vanish.
func (s *Store) FailedJobs(ctx context.Context, limit int) ([]Job, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, session_id, kind, payload, state, attempts, last_error,
		       error_class, created_at, updated_at, next_retry_at
		  FROM jobs WHERE state = ? ORDER BY updated_at DESC LIMIT ?`, JobFailed, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Job
	for rows.Next() {
		var j Job
		if err := rows.Scan(&j.ID, &j.SessionID, &j.Kind, &j.Payload, &j.State, &j.Attempts,
			&j.LastError, &j.ErrorClass, &j.CreatedAt, &j.UpdatedAt, &j.NextRetryAt); err != nil {
			return nil, err
		}
		out = append(out, j)
	}
	return out, rows.Err()
}
