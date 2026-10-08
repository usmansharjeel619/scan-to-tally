package store

import (
	"context"
	"path/filepath"
	"testing"
	"time"
)

func TestExplicitRetryIsDurableAndIdempotent(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "jobs.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	ctx := context.Background()
	job := Job{ID: "s#0", SessionID: "s#0", Kind: "INCOMING", Payload: []byte(`{"old":true}`)}
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	check := func(want JobState) {
		t.Helper()
		j, e := s.DeliveryState(ctx, job.SessionID)
		must(e)
		if j.State != want {
			t.Fatalf("got %s want %s", j.State, want)
		}
	}
	must(s.Enqueue(ctx, job))
	must(s.FailJob(ctx, job.ID, 1, "TALLY_HISTORY_CHANGED: history changed", "BUSINESS"))
	must(s.Enqueue(ctx, job))
	check(JobFailed)
	job.ID = "s#0:retry:1"
	job.Payload = []byte(`{"corrected":true}`)
	must(s.Enqueue(ctx, job))
	check(JobQueued)
	claimed, err := s.NextDue(ctx, time.Now().Add(time.Second))
	must(err)
	if claimed == nil || string(claimed.Payload) != string(job.Payload) || claimed.SessionID != "s#0" {
		t.Fatal("retry did not preserve identity and replace payload")
	}
	must(s.Enqueue(ctx, job))
	check(JobRunning)
	must(s.FailJob(ctx, job.ID, 1, "OTHER: still invalid", "BUSINESS"))
	must(s.Enqueue(ctx, job))
	check(JobFailed) // reconnect is not a new Retry tap
	job.ID = "s#0:retry:2"
	must(s.Enqueue(ctx, job))
	check(JobQueued)
	must(s.CompleteJob(ctx, job.ID))
	job.ID = "s#0:retry:3"
	must(s.Enqueue(ctx, job))
	check(JobDone) // success can never be reopened
}
