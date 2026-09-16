#!/usr/bin/env bash
# Start the Tally simulator for local development.
#   ./scripts/dev-tally.sh            -> listens on :9000
#   STT_TALLY_URL=http://127.0.0.1:9000 go test ./internal/tally/ -run Integration -v
set -e
export PATH=/mnt/4TB_Storage/toolchains/go/bin:$PATH
export GOPATH=${GOPATH:-/mnt/4TB_Storage/toolchains/gopath}
cd "$(dirname "$0")/../tools/tallysim"
exec go run -p 4 . -addr "${1:-:9000}"
