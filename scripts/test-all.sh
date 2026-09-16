#!/usr/bin/env bash
# Run every Go suite, including the integration tests against a throwaway sim.
# Deliberately low-parallelism: this dev box is short on RAM.
set -e
export PATH=/mnt/4TB_Storage/toolchains/go/bin:$PATH
export GOPATH=${GOPATH:-/mnt/4TB_Storage/toolchains/gopath}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT=${SIM_PORT:-9131}
BIN=$(mktemp -d)/tallysim
( cd "$ROOT/tools/tallysim" && go build -p 4 -o "$BIN" . )
"$BIN" -addr "127.0.0.1:$PORT" >/dev/null 2>&1 &
SIM=$!
trap 'kill $SIM 2>/dev/null || true' EXIT
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$PORT/_sim/state" && break; sleep 0.1; done
cd "$ROOT/connector"
STT_TALLY_URL="http://127.0.0.1:$PORT" go test -p 4 ./...
