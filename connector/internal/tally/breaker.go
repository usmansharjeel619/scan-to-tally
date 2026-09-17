package tally

import (
	"sync"
	"time"
)

// Breaker keeps the connector from hammering a Tally that is unwell.
//
// Written after a real incident: Tally became unstable on the target machine
// and the connector carried on probing it every 15 seconds indefinitely. It
// did not cause the instability, but a client that keeps knocking on a door
// nobody is answering is badly behaved, and it makes the logs useless for
// working out what actually went wrong.
//
// The rules it enforces:
//
//   - Consecutive failures widen the gap between attempts, up to a long
//     ceiling. A Tally that has been shut for the night gets probed four times
//     an hour, not four times a minute.
//   - A connection refused -- nothing listening at all -- backs off faster than
//     a timeout, because there is nothing there to wait for.
//   - After enough consecutive failures it goes QUIESCENT: it keeps checking,
//     but rarely, and says plainly that it has stopped trying hard. This is the
//     state that should prompt someone to go and look at the machine.
//   - Any success resets everything immediately. Recovery must be fast; the
//     warehouse should not wait out a backoff after Tally comes back.
//
// It never stops entirely. A breaker that latches open needs a human to reset
// it, and there is no human near the relay at 6am.
type Breaker struct {
	mu sync.Mutex

	consecutiveFailures int
	lastAttempt         time.Time
	quiescent           bool

	// Base is the healthy probe interval.
	Base time.Duration
	// Max is the longest gap between attempts when things are bad.
	Max time.Duration
	// QuiescentAfter is how many consecutive failures before we admit that
	// something needs human attention.
	QuiescentAfter int
}

func NewBreaker(base, max time.Duration) *Breaker {
	if base <= 0 {
		base = 15 * time.Second
	}
	if max <= 0 {
		max = 15 * time.Minute
	}
	return &Breaker{Base: base, Max: max, QuiescentAfter: 20}
}

// Interval returns how long to wait before the next attempt.
func (b *Breaker) Interval() time.Duration {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.intervalLocked()
}

func (b *Breaker) intervalLocked() time.Duration {
	if b.consecutiveFailures == 0 {
		return b.Base
	}
	// Double per failure, from the second one onward, capped.
	d := b.Base
	for i := 1; i < b.consecutiveFailures && d < b.Max; i++ {
		d *= 2
	}
	if d > b.Max {
		d = b.Max
	}
	return d
}

// Allow reports whether enough time has passed to try again. Callers that are
// doing real work (posting a voucher) may bypass this; it governs polling.
func (b *Breaker) Allow(now time.Time) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.lastAttempt.IsZero() {
		return true
	}
	return now.Sub(b.lastAttempt) >= b.intervalLocked()
}

// Attempted records that a request was just made.
func (b *Breaker) Attempted(now time.Time) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.lastAttempt = now
}

// Success resets the breaker. Recovery is immediate by design.
func (b *Breaker) Success() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.consecutiveFailures = 0
	b.quiescent = false
}

// Failure widens the gap. notListening means nothing answered the port at all,
// which backs off faster than a timeout: there is no point waiting patiently
// for a process that is not running.
func (b *Breaker) Failure(notListening bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.consecutiveFailures++
	if notListening {
		// Skip a rung. A closed port is unambiguous.
		b.consecutiveFailures++
	}
	if b.consecutiveFailures >= b.QuiescentAfter {
		b.quiescent = true
	}
}

// State reports what to show a human.
func (b *Breaker) State() (failures int, interval time.Duration, quiescent bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.consecutiveFailures, b.intervalLocked(), b.quiescent
}

// Quiescent reports whether the connector has given up trying hard. The relay
// surfaces this so somebody knows to go and look at the machine, rather than
// it being buried in a log nobody reads.
func (b *Breaker) Quiescent() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.quiescent
}
