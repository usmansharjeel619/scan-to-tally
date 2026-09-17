package tally

import (
	"testing"
	"time"
)

// These pin the behaviour that was missing during a real incident: Tally became
// unstable and the connector kept probing it every 15 seconds indefinitely.

func TestBreakerBacksOffOnRepeatedFailure(t *testing.T) {
	b := NewBreaker(15*time.Second, 15*time.Minute)

	if got := b.Interval(); got != 15*time.Second {
		t.Fatalf("healthy interval = %v, want 15s", got)
	}

	var prev time.Duration
	for i := 1; i <= 12; i++ {
		b.Failure(false)
		got := b.Interval()
		if got < prev {
			t.Fatalf("interval went backwards at failure %d: %v then %v", i, prev, got)
		}
		if got > 15*time.Minute {
			t.Fatalf("interval %v exceeded the ceiling", got)
		}
		prev = got
	}

	if prev <= 15*time.Second {
		t.Fatalf("after 12 failures the interval is still %v; it never backed off", prev)
	}
	t.Logf("after 12 consecutive failures the gap is %v", prev)
}

// A Tally that comes back must be noticed straight away. Making the warehouse
// wait out a fifteen-minute backoff after the machine recovers would be worse
// than the problem the backoff solves.
func TestBreakerRecoversImmediately(t *testing.T) {
	b := NewBreaker(15*time.Second, 15*time.Minute)
	for i := 0; i < 30; i++ {
		b.Failure(false)
	}
	if !b.Quiescent() {
		t.Fatal("30 failures should have gone quiescent")
	}

	b.Success()

	if b.Quiescent() {
		t.Error("a success must clear the quiescent state")
	}
	if got := b.Interval(); got != 15*time.Second {
		t.Errorf("interval after recovery = %v, want the healthy 15s", got)
	}
}

// Nothing listening on the port is unambiguous: there is no process to wait
// for, so it should back off faster than an ambiguous timeout.
func TestBreakerBacksOffFasterWhenNothingIsListening(t *testing.T) {
	timeouts := NewBreaker(15*time.Second, 15*time.Minute)
	refused := NewBreaker(15*time.Second, 15*time.Minute)

	for i := 0; i < 4; i++ {
		timeouts.Failure(false)
		refused.Failure(true)
	}

	if refused.Interval() <= timeouts.Interval() {
		t.Errorf("connection-refused interval %v should exceed timeout interval %v",
			refused.Interval(), timeouts.Interval())
	}
}

// It must never latch fully open. There is nobody beside the relay at 6am to
// reset it, so it has to keep checking -- just rarely.
func TestBreakerNeverStopsCompletely(t *testing.T) {
	b := NewBreaker(15*time.Second, 15*time.Minute)
	for i := 0; i < 500; i++ {
		b.Failure(true)
	}
	got := b.Interval()
	if got > 15*time.Minute {
		t.Fatalf("interval %v exceeds the ceiling; it has effectively given up", got)
	}
	if !b.Allow(time.Now().Add(20 * time.Minute)) {
		t.Fatal("must still allow an attempt once the interval has elapsed")
	}
}

func TestBreakerAllowRespectsInterval(t *testing.T) {
	b := NewBreaker(15*time.Second, 15*time.Minute)
	now := time.Now()

	if !b.Allow(now) {
		t.Fatal("a fresh breaker must allow the first attempt")
	}
	b.Attempted(now)

	if b.Allow(now.Add(5 * time.Second)) {
		t.Error("allowed an attempt before the interval elapsed")
	}
	if !b.Allow(now.Add(16 * time.Second)) {
		t.Error("should allow once the interval has elapsed")
	}
}
