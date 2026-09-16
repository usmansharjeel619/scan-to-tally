# Deployment

Three pieces, deployed in this order. Each one is useful before the next exists.

```
Android handset ──HTTPS──▶ relay ──outbound WSS──▶ connector ──localhost:9000──▶ Tally
   warehouse             your server              Tally machine            Tally machine
```

---

## The rule that shapes all of it

**Tally's XML gateway has no authentication.** Anyone who reaches port 9000 can
read every ledger, party and price in the company and post vouchers into it.

So the connector talks to Tally over **localhost only** and dials **out** to the
relay. No inbound port, no firewall exception, no static IP on the warehouse
machine. Never port-forward 9000, and never put it on a public address.

---

## 1. Relay

Node 22 and a SQLite file. No Postgres, no container orchestration — the load is
a handful of handsets and a few hundred sessions a day, which WAL-mode SQLite
absorbs without noticing. Removing a database server removes a thing that can
break at 6am in a warehouse.

```bash
cd relay
npm ci
npm run build

export STT_PORT=8787
export STT_DB=/var/lib/scan-to-tally/relay.db
export STT_CONNECTOR_SECRET="$(openssl rand -hex 32)"   # keep this
node dist/server.js
```

Put it behind TLS. The handset sends a bearer token and the connector sends the
shared secret; both are worthless over plain HTTP.

```nginx
location /connector/ws {
    proxy_pass http://127.0.0.1:8787;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;   # the connector holds this socket open
}
location /api/ { proxy_pass http://127.0.0.1:8787; }
```

As a systemd unit:

```ini
[Unit]
Description=Scan to Tally relay
After=network.target

[Service]
Type=simple
User=scantotally
WorkingDirectory=/opt/scan-to-tally/relay
Environment=STT_DB=/var/lib/scan-to-tally/relay.db
Environment=STT_PORT=8787
EnvironmentFile=/etc/scan-to-tally/relay.env   # STT_CONNECTOR_SECRET lives here
ExecStart=/usr/bin/node dist/server.js
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

### Enrolling a handset

Each device gets a row and a token. The token is shown once; only its hash is
stored.

```bash
node -e '
const Database = require("better-sqlite3");
const { createHash, randomBytes } = require("node:crypto");
const token = randomBytes(24).toString("base64url");
const db = new Database(process.env.STT_DB);
db.prepare(`INSERT INTO devices (id,name,company,godown,token_hash,operator,created_at)
            VALUES (?,?,?,?,?,?,?)`).run(
  "dock-1", "Dock scanner 1", "ACME FIRE SYSTEMS", "Main Store",
  createHash("sha256").update(token).digest("hex"), "", new Date().toISOString());
console.log("device token:", token);
'
```

Company and godown are set **here**, not on the handset. The operator is never
asked to pick them.

### Backups

`relay.db` holds the received-box index, the PID bindings and the audit log.
Losing it does not lose stock — Tally has that — but it does lose duplicate
detection and every learned product mapping. Back it up.

```bash
sqlite3 /var/lib/scan-to-tally/relay.db ".backup '/backups/relay-$(date +%F).db'"
```

---

## 2. Connector

On the machine holding the Tally data. One static `.exe`, no runtime to install.

```bash
# built from Linux; no Windows machine needed to produce it
cd connector
GOOS=windows GOARCH=amd64 go build -ldflags="-s -w" -o connector.exe ./cmd/connector
```

Copy `connector.exe` and `scripts/install-connector.ps1` to the Tally machine,
then **as Administrator**:

```powershell
.\install-connector.ps1 -RelayUrl "wss://relay.example.com/connector/ws" `
                        -Secret   "<STT_CONNECTOR_SECRET>" `
                        -Company  "ACME FIRE SYSTEMS"
```

That registers an auto-starting service with restart-on-failure, locks the
config file (it holds the secret) to Administrators and SYSTEM, and disables
sleep and hibernate.

**Still to do by hand**, because Tally cannot be configured from a script:

- TallyPrime set to start at login
- the company set to load on startup (`tally.ini`)
- `F1 Help > Settings > Connectivity > Client/Server configuration`:
  `TallyPrime acts as = Both`, `Port = 9000`

Check it: `http://127.0.0.1:9787` on that machine shows Tally's health, the
queue, and anything waiting on a human.

### What Tally Prime Silver costs you

Silver is single-user and node-locked, so the connector runs on the employee's
own laptop and there is no second copy.

- **Tally has no headless mode.** Port 9000 exists only while Tally is running
  with the company open. Close it, sleep the laptop, or take it home, and the
  warehouse app goes read-only until it comes back.
- **One machine means one site.**
- **UI contention is real.** A modal dialog open in Tally can block an import.

None of these lose data — the queue waits and recovers on its own — but they do
stop vouchers reaching Tally in the meantime. A dedicated always-on machine plus
a Gold licence removes all three, and is the only path to a second warehouse.

---

## 3. Android

```bash
cd android
./gradlew assembleRelease
```

Needs JDK 17+ and an **x86_64** Linux, macOS or Windows host: Android's `aapt2`,
`d8` and `zipalign` have no Linux/arm64 builds, so an ARM server cannot produce
an APK however much memory it has.

Sign it, then install:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

First launch asks for the relay address, the device token, the godown and the
operator's name. After that the operator never sees a settings screen.

### Rugged devices

The app configures its own DataWedge profile at startup — **intent output**,
Code 128 and Code 39 only. Nothing to set up by hand per device.

If scans are not arriving:

1. DataWedge > Profiles > `ScanToTally` exists and is enabled
2. its app list contains `com.acme.scantotally`
3. Keystroke output is **off** and Intent output is **on**, action
   `com.acme.scantotally.SCAN`, delivery `Broadcast`

---

## Order of work

1. **Phase 0 first.** Run `scripts/tally-probe.ps1` on the real Tally and check
   the exported day book against what the connector emits. Everything in the
   Tally XML layer is a template until that is done — see `TALLY-ACCESS.md`.
2. Relay, with TLS.
3. Connector on the Tally machine; confirm master data syncs up.
4. One handset. Run one real receipt end to end before issuing any others.
5. Outgoing, then the inventory check.

## Verifying without Tally

The simulator covers everything except whether the XML matches your Tally:

```bash
./scripts/test-all.sh   # unit + integration
./scripts/e2e.sh        # the whole chain, with fault injection
```

`e2e.sh` asserts the cases that cost money: a duplicate box is refused, a
re-submitted session creates no second voucher, a box whose label says 18 cannot
ship 18 when Tally holds 13, and closing the company mid-shift leaves the
operator working while the queue waits and recovers.
