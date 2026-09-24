# Resume here

**24 September 2026:** The notes below are historical. Current voucher-merge
work and verification status: [MERGE-FIX.md](MERGE-FIX.md).

Last worked: 16 Sep 2026. Everything is built and running; one thing is
unverified. This is the five-minute version.

---

## Where it stands

| | |
|---|---|
| Relay | live, systemd `scan-to-tally-relay`; address in `scripts/deploy.env` |
| Connector | Installed on `TALLY-PC`, auto-starting Windows service |
| Android APK | Built, 3/3 tests, `dist/scan-to-tally-debug.apk` |
| Test suites | Go + TypeScript + Kotlin, all green; `scripts/e2e.sh` passes end to end |
| **Unverified** | **The Tally voucher XML.** See below. |

Nothing is queued, nothing has failed, and no session is waiting to post.

## The one thing left

Every field name in `connector/internal/tally/import.go` carries a `VERIFY`
comment. They were written from documentation, not from a real installation.
The simulator agrees with them only because I wrote both from the same
assumptions — so the green tests prove the plumbing, not the schema.

Specifically still unproven:

- the multi-batch Receipt Note shape (one inventory entry, N batch allocations)
- how a Delivery Note links to a Sales Order — `ORDERNO` vs `TRACKINGNUMBER`
- `ISDEEMEDPOSITIVE` polarity in vs out
- `MFDON` (batch manufacturing date)
- `BASICUSERDESCRIPTION` (per-line description)
- which of three batch-balance queries returns closing stock
- **how the real stock items are named** — this decides the whole PID-resolution
  design and is still a guess

## Tomorrow: open a company that already has data

The plan agreed on 16 Sep. Rather than seeding an empty company, open an
existing one with real stock items and some real vouchers, and read it.

1. On the Tally machine, open a company that has **stock items and at least one
   Receipt Note / Purchase, and one Delivery Note / Sales Order**.
2. Say which company it is (or it can be discovered).
3. Everything after that is read-only queries through the connector.

### Why reading is safe

The connector's write path is pinned to `tally.company` in
`C:\ScanToTally\connector.json`, which says `New Test Company`. It **cannot**
post a voucher into any other company — a write against a company that is not
open fails with "company not open". The diagnostic channel refuses anything
that is not an `Export`.

So any company can be opened for reading without risk. Do not change
`tally.company` in that config unless you intend to write to that company.

### Worth ten minutes of someone Tally-literate

Confirm **Maintain Batches** is on in the real company (`F11` → Storage and
Classification). The entire box-as-batch design rests on it, and it was OFF in
the test company.

## Things learned the hard way

- **Tally can crash on malformed XML**, not merely reject it. A `<UNIT>` create
  without a `NAME` attribute took the process down with a memory access
  violation. Never brute-force XML shapes at a live Tally. The script that did
  is withdrawn (`docs/withdrawn/`).
- **Tally answers HTTP 200 with an error body.** Status codes mean nothing;
  read the body. This is why the connector currently reports `COMPANY_CLOSED`
  rather than a false `ONLINE`.
- **The default godown is `Main Location`**, not `Main Store`. A batch balance
  is keyed on (item, batch, godown), so the wrong name refuses every despatch.
- **Entity names live in the `NAME` attribute**; the `<NAME>` element is often
  buried under `LANGUAGENAME.LIST`.

More detail in `PHASE0-FINDINGS.md`.

## Turning the diagnostic channel off

It is read-only, but it is a capability and should not outlive Phase 0. On the
Tally machine, as Administrator:

Take `RELAY_ORIGIN` and `DOWNLOAD_PATH` from `scripts/deploy.env`, which is
deliberately not in the repository — the download path is the only thing
between a stranger and the installer, and the installer carries the connector
secret.

```powershell
irm <RELAY_ORIGIN>/dl/<DOWNLOAD_PATH>/bootstrap.ps1 -OutFile $env:TEMP\stt.ps1
& $env:TEMP\stt.ps1 -NoDiagnostics
```

## Housekeeping when the project ends

- Tally machine: `.\install-connector.ps1 -Uninstall` (keep `connector.db` — it
  is the record of which sessions already reached Tally; deleting it risks
  duplicate vouchers on a reinstall)
- Relay host: `systemctl disable --now scan-to-tally-relay`, remove
  `/opt/scan-to-tally`, `/var/lib/scan-to-tally`, `/etc/scan-to-tally`
- Build toolchain: `rm -rf /opt/stt-build` (~4 GB)
- Cloudflare tunnel: remove the relay hostname's block from
  `/opt/rgm-crm/cloudflared/config.yml` (backups alongside it) and **restart**
  the container — `docker restart rgm-crm-cloudflared-1`. Do **not** send it
  SIGHUP; that kills it and takes the CRM down with it.
- Firewall: `ufw delete allow from 172.21.0.0/16 to 172.21.0.1 port 8787 proto tcp`
