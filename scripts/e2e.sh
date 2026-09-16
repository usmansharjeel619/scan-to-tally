#!/usr/bin/env bash
# End-to-end proof: tallysim <- connector <- relay <- "device" (curl).
#
# Walks a real incoming session through the whole chain and then re-submits it
# to show the idempotency ledger refusing to post a second voucher.
#
# Deliberately modest on resources: this dev box is short on RAM.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH=/mnt/4TB_Storage/toolchains/go/bin:$PATH
export GOPATH=${GOPATH:-/mnt/4TB_Storage/toolchains/gopath}

WORK=$(mktemp -d)
SIM_PORT=${SIM_PORT:-9141}
RELAY_PORT=${RELAY_PORT:-8791}
SECRET="e2e-test-secret"
DEVICE_TOKEN="e2e-device-token"

cleanup() {
  kill ${SIM_PID:-} ${RELAY_PID:-} ${CONN_PID:-} 2>/dev/null || true
  wait 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

say() { printf '\n\033[36m== %s\033[0m\n' "$1"; }
fail() {
  printf '\033[31mFAIL: %s\033[0m\n' "$1"
  for f in connector relay sim; do
    if [ -s "$WORK/$f.log" ]; then
      printf '\n--- %s.log (tail) ---\n' "$f"; tail -25 "$WORK/$f.log"
    fi
  done
  exit 1
}

wait_for() {
  for _ in $(seq 1 60); do curl -fsS -o /dev/null "$1" 2>/dev/null && return 0; sleep 0.25; done
  fail "timed out waiting for $1"
}

# --- 1. Tally simulator -------------------------------------------------------
say "starting tallysim on :$SIM_PORT"
( cd "$ROOT/tools/tallysim" && go build -p 4 -o "$WORK/tallysim" . )
"$WORK/tallysim" -addr "127.0.0.1:$SIM_PORT" > "$WORK/sim.log" 2>&1 &
SIM_PID=$!
wait_for "http://127.0.0.1:$SIM_PORT/_sim/state"

# --- 2. relay -----------------------------------------------------------------
say "starting relay on :$RELAY_PORT"
cd "$ROOT/relay"
[ -d node_modules ] || npm install --no-audit --no-fund >/dev/null 2>&1
STT_PORT=$RELAY_PORT STT_DB="$WORK/relay.db" STT_CONNECTOR_SECRET="$SECRET" LOG_LEVEL=warn \
  node --experimental-strip-types src/server.ts > "$WORK/relay.log" 2>&1 &
RELAY_PID=$!
wait_for "http://127.0.0.1:$RELAY_PORT/health"

# A provisioned device. In production this is issued when the handset is
# enrolled; company and godown are device configuration, never a per-session
# picker the operator has to tap through.
node -e "
const Database = require('better-sqlite3');
const { createHash } = require('node:crypto');
const db = new Database('$WORK/relay.db');
db.prepare(\`INSERT INTO devices (id,name,company,godown,token_hash,operator,created_at)
            VALUES (?,?,?,?,?,?,?)\`).run(
  'dev-1','Dock scanner 1','ACME FIRE SYSTEMS','Main Store',
  createHash('sha256').update('$DEVICE_TOKEN').digest('hex'),'RK',new Date().toISOString());
db.close();
"

# --- 3. connector -------------------------------------------------------------
say "starting connector"
cd "$ROOT/connector"
go build -p 4 -o "$WORK/connector" ./cmd/connector
cat > "$WORK/connector.json" <<JSON
{
  "tally":  { "baseUrl": "http://127.0.0.1:$SIM_PORT", "company": "ACME FIRE SYSTEMS",
              "timeoutSec": 20, "probeSeconds": 5 },
  "relay":  { "url": "ws://127.0.0.1:$RELAY_PORT/connector/ws", "secret": "$SECRET",
              "connectorId": "e2e-connector", "syncSeconds": 5 },
  "dbPath": "$WORK/connector.db",
  "statusAddr": "127.0.0.1:9788",
  "logLevel": "info"
}
JSON
"$WORK/connector" -config "$WORK/connector.json" > "$WORK/connector.log" 2>&1 &
CONN_PID=$!

say "waiting for master data to sync from Tally"
for _ in $(seq 1 60); do
  n=$(curl -fsS -H "Authorization: Bearer $DEVICE_TOKEN" \
      "http://127.0.0.1:$RELAY_PORT/api/v1/sync" 2>/dev/null | NO_COLOR=1 node -e \
      'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
         let n=0; try{ n=JSON.parse(s).items.length }catch{}
         process.stdout.write(String(n)+"\n");
       })')
  [ "${n:-0}" -gt 0 ] && break
  sleep 0.5
done
if [ "${n:-0}" -le 0 ]; then
  printf '\n--- what /api/v1/sync actually returned ---\n'
  curl -sS -o "$WORK/sync.out" -w 'HTTP %{http_code}\n' \
    -H "Authorization: Bearer $DEVICE_TOKEN" \
    "http://127.0.0.1:$RELAY_PORT/api/v1/sync" || true
  head -c 400 "$WORK/sync.out"; printf '\n'
  printf -- '--- devices table ---\n'
  ( cd "$ROOT/relay" && node -e "
    const D=require('better-sqlite3');const db=new D('$WORK/relay.db');
    console.log(JSON.stringify(db.prepare('SELECT id,company,godown,substr(token_hash,1,12) h FROM devices').all()));
    console.log('stock_items rows:', db.prepare('SELECT COUNT(*) c FROM stock_items').get().c);
    db.close();" ) || true
  fail "master data never arrived (see $WORK/connector.log)"
fi
echo "   synced $n stock items from Tally"

api() {
  local method=$1 path=$2 body=${3:-}
  if [ -n "$body" ]; then
    curl -fsS -X "$method" -H "Authorization: Bearer $DEVICE_TOKEN" \
      -H 'Content-Type: application/json' -d "$body" "http://127.0.0.1:$RELAY_PORT$path"
  else
    curl -fsS -X "$method" -H "Authorization: Bearer $DEVICE_TOKEN" "http://127.0.0.1:$RELAY_PORT$path"
  fi
}
jqf() {
  NO_COLOR=1 node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
    const o=JSON.parse(s);
    const v=process.argv[1].split(".").reduce((a,k)=>(a==null?a:a[k]),o);
    process.stdout.write(String(v ?? "")+"\n");
  })' "$1"
}

# --- 4. an incoming session ---------------------------------------------------
say "INCOMING: three boxes of one part number"
SESSION="e2e-$(date +%s)"
api POST /api/v1/sessions "{\"sessionId\":\"$SESSION\",\"kind\":\"INCOMING\",\"party\":\"Simplex Supplies\"}" >/dev/null

for serial in 1124249900001001 1124249900001002 1124249900001003; do
  out=$(api POST "/api/v1/sessions/$SESSION/scan" \
      "{\"raw\":\"4098-9792|$serial|18|\",\"symbology\":\"CODE128\"}")
  printf '   scan %s -> %s / beep %s\n' "${serial: -6}" "$(echo "$out"|jqf outcome)" "$(echo "$out"|jqf beep)"
  [ "$(echo "$out"|jqf outcome)" = "ACCEPT" ] || fail "scan rejected: $out"
done

say "duplicate box must be refused"
dup=$(api POST "/api/v1/sessions/$SESSION/scan" \
     '{"raw":"4098-9792|1124249900001001|18|","symbology":"CODE128"}')
[ "$(echo "$dup"|jqf outcome)" = "DUPLICATE" ] || fail "duplicate not caught: $dup"
echo "   -> DUPLICATE / beep $(echo "$dup"|jqf beep)"

say "wrong barcode must say which one to scan"
wrong=$(api POST "/api/v1/sessions/$SESSION/scan" '{"raw":"4098-9792","symbology":"CODE128"}')
[ "$(echo "$wrong"|jqf outcome)" = "WRONG_BARCODE" ] || fail "wrong barcode not caught: $wrong"
echo "   -> $(echo "$wrong"|jqf message)"

say "session as the device shows it"
api GET "/api/v1/sessions/$SESSION" | node -e '
let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
  const o=JSON.parse(s);
  for (const g of o.groups) console.log(`   ${g.stockItemName} - ${g.boxCount} boxes - ${g.totalQty} ${g.unit}`);
});'

say "submitting"
api POST "/api/v1/sessions/$SESSION/submit" '{}' >/dev/null
for _ in $(seq 1 60); do
  state=$(api GET "/api/v1/sessions/$SESSION" | jqf session.state)
  [ "$state" = "POSTED" ] && break
  [ "$state" = "FAILED" ] && fail "session failed: $(api GET /api/v1/sessions/$SESSION)"
  sleep 0.5
done
[ "$state" = "POSTED" ] || fail "session stuck in $state"
VCH=$(api GET "/api/v1/sessions/$SESSION" | jqf session.tally_voucher_id)
echo "   POSTED as Tally voucher $VCH"

say "Tally now holds"
curl -fsS "http://127.0.0.1:$SIM_PORT/_sim/state" | grep -E "1124249900001|VOUCHERS|Receipt" | sed 's/^/   /'

# --- 5. the case that costs money --------------------------------------------
say "re-submitting the SAME session (a retry after a dropped connection)"
before=$(curl -fsS "http://127.0.0.1:$SIM_PORT/_sim/state" | grep -c "ref=" || true)
api POST "/api/v1/sessions/$SESSION/submit" '{}' >/dev/null
sleep 2
after=$(curl -fsS "http://127.0.0.1:$SIM_PORT/_sim/state" | grep -c "ref=" || true)
[ "$before" = "$after" ] || fail "DUPLICATE VOUCHER CREATED ($before -> $after)"
echo "   voucher count unchanged at $after -- no second voucher"

# --- 6. outgoing against a sales order ---------------------------------------
say "OUTGOING: box label says 18, Tally holds 13"
OUT_SESSION="e2e-out-$(date +%s)"
api POST /api/v1/sessions \
  "{\"sessionId\":\"$OUT_SESSION\",\"kind\":\"OUTGOING\",\"salesOrder\":\"SO-2026-0041\",\"party\":\"Example Project FZC\"}" >/dev/null

scan=$(api POST "/api/v1/sessions/$OUT_SESSION/scan" \
     '{"raw":"4098-9792|1124241658336425|18|","symbology":"CODE128"}')
avail=$(echo "$scan"|jqf available)
echo "   label qty $(echo "$scan"|jqf box.labelQty), available $avail"
[ "$avail" = "13" ] || fail "ceiling should be 13, got $avail"

say "entering 18 must be refused"
if api POST "/api/v1/sessions/$OUT_SESSION/lines" \
   '{"pid":"4098-9792","boxSerial":"1124241658336425","qty":18}' >/dev/null 2>&1; then
  fail "over-ship was accepted"
fi
echo "   refused, as it should be"

say "entering 13 is accepted, then posting"
api POST "/api/v1/sessions/$OUT_SESSION/lines" \
  '{"pid":"4098-9792","boxSerial":"1124241658336425","qty":13}' >/dev/null
api POST "/api/v1/sessions/$OUT_SESSION/submit" '{}' >/dev/null
for _ in $(seq 1 60); do
  state=$(api GET "/api/v1/sessions/$OUT_SESSION" | jqf session.state)
  [ "$state" = "POSTED" ] && break
  [ "$state" = "FAILED" ] && fail "outgoing failed: $(api GET /api/v1/sessions/$OUT_SESSION)"
  sleep 0.5
done
[ "$state" = "POSTED" ] || fail "outgoing stuck in $state"
echo "   Delivery Note posted; box now drawn down in Tally"
curl -fsS "http://127.0.0.1:$SIM_PORT/_sim/state" | grep "1124241658336425" | sed 's/^/   /'

# --- 7. Tally goes away -------------------------------------------------------
say "closing the company in Tally; the operator must not be blocked"
curl -fsS "http://127.0.0.1:$SIM_PORT/_sim/fault?mode=closed" >/dev/null
OFF_SESSION="e2e-offline-$(date +%s)"
api POST /api/v1/sessions "{\"sessionId\":\"$OFF_SESSION\",\"kind\":\"INCOMING\",\"party\":\"Simplex Supplies\"}" >/dev/null
api POST "/api/v1/sessions/$OFF_SESSION/scan" \
  '{"raw":"4098-9792|1124249900002001|18|","symbology":"CODE128"}' >/dev/null
api POST "/api/v1/sessions/$OFF_SESSION/submit" '{}' >/dev/null
sleep 2
state=$(api GET "/api/v1/sessions/$OFF_SESSION" | jqf session.state)
[ "$state" = "FAILED" ] && fail "a closed company must not fail the session"
echo "   session is $state and waiting -- the dock kept moving"

say "reopening the company"
curl -fsS "http://127.0.0.1:$SIM_PORT/_sim/fault?mode=none" >/dev/null
for _ in $(seq 1 80); do
  state=$(api GET "/api/v1/sessions/$OFF_SESSION" | jqf session.state)
  [ "$state" = "POSTED" ] && break
  sleep 0.5
done
[ "$state" = "POSTED" ] || fail "session did not recover, stuck in $state"
echo "   recovered and POSTED without anyone touching it"

printf '\n\033[32m== ALL END-TO-END CHECKS PASSED ==\033[0m\n\n'
