//go:build !windows

package main

import "context"

// runUnderServiceManager is a no-op off Windows: there is no SCM to answer to,
// so the caller just runs the work directly.
func runUnderServiceManager(_ func(context.Context)) (bool, error) { return false, nil }
