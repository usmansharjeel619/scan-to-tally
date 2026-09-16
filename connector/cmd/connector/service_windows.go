//go:build windows

package main

import (
	"context"
	"time"

	"golang.org/x/sys/windows/svc"
)

// Windows service support.
//
// A plain console binary registered with sc.exe does NOT work: the Service
// Control Manager starts the process and waits for it to call
// StartServiceCtrlDispatcher and report Running. A program that just gets on
// with its job never answers, so SCM kills it after 30 seconds with
//
//	Error 1053: The service did not respond to the start request in a timely fashion.
//
// which is exactly what happened on the first real install.
//
// The same binary still runs as an ordinary console program when launched by
// hand, which is what makes it debuggable on the Tally machine.

// runUnderServiceManager reports whether we were started by SCM and, if so,
// runs the work under the service protocol.
func runUnderServiceManager(work func(context.Context)) (handled bool, err error) {
	isService, err := svc.IsWindowsService()
	if err != nil || !isService {
		return false, err
	}
	return true, svc.Run(serviceName, &winService{work: work})
}

const serviceName = "ScanToTallyConnector"

type winService struct {
	work func(context.Context)
}

func (s *winService) Execute(
	_ []string, r <-chan svc.ChangeRequest, changes chan<- svc.Status,
) (bool, uint32) {
	changes <- svc.Status{State: svc.StartPending}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := make(chan struct{})
	go func() {
		defer close(done)
		s.work(ctx)
	}()

	// Report Running promptly. Everything the connector does -- reaching Tally,
	// dialling the relay -- is allowed to fail and retry, so none of it should
	// hold up the service start.
	changes <- svc.Status{
		State:   svc.Running,
		Accepts: svc.AcceptStop | svc.AcceptShutdown,
	}

	for {
		select {
		case c := <-r:
			switch c.Cmd {
			case svc.Interrogate:
				changes <- c.CurrentStatus
			case svc.Stop, svc.Shutdown:
				changes <- svc.Status{State: svc.StopPending}
				cancel()
				// Jobs are durable in SQLite, so a hard stop is safe: anything
				// left mid-flight is requeued on the next start and recognised
				// by the idempotency check rather than posted twice. Wait
				// briefly for a clean exit, then stop regardless.
				select {
				case <-done:
				case <-time.After(10 * time.Second):
				}
				return false, 0
			}
		case <-done:
			// The worker returned on its own; nothing left to supervise.
			return false, 0
		}
	}
}
