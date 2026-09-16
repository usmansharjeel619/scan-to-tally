# Scan to Tally

Android barcode scanning for a warehouse, posting inventory vouchers straight
into TallyPrime. A box is scanned once and becomes a batch in Tally.

```
Android handset ──HTTPS──▶ relay ──outbound WSS──▶ connector ──localhost:9000──▶ Tally
```

The connector always dials **out**. Tally's XML gateway has no authentication of
any kind, so it is never reachable from a network.

| Component | Path | Stack |
|---|---|---|
| Shared parser fixtures | `contracts/` | JSON, one file, three implementations |
| On-prem connector | `connector/` | Go 1.23 → static Windows `.exe` |
| Relay server | `relay/` | Node 22, TypeScript, SQLite |
| Android app | `android/` | Kotlin, Compose, Room |
| Tally simulator (dev) | `tools/tallysim/` | Go 1.23 |

## Three flows

- **Incoming** — scan, scan, scan, Done. Quantity comes from the barcode.
- **Outgoing** — pick a sales order, scan, type the quantity, confirm.
- **Inventory check** — count blind, then adopt the variance.

Plus a **manual entry** tab for torn labels, and a **supervisor** queue for
everything the dock deferred.

## Getting started

```bash
export PATH=/mnt/4TB_Storage/toolchains/go/bin:$PATH

./scripts/test-all.sh    # unit + integration against the Tally simulator
./scripts/e2e.sh         # the whole chain, with fault injection
./scripts/dev-tally.sh   # just the simulator, on :9000
```

Nothing above needs access to a real Tally.

## Docs

| | |
|---|---|
| [`docs/RESUME-HERE.md`](docs/RESUME-HERE.md) | **Picking this back up? Start here.** |
| [`docs/PHASE0.md`](docs/PHASE0.md) | **Start here.** Proving the Tally round-trip. |
| [`docs/TALLY-ACCESS.md`](docs/TALLY-ACCESS.md) | How to get at a real Tally, safely. |
| [`docs/DEPLOY.md`](docs/DEPLOY.md) | Shipping all three components. |

## The one thing to know

Everything in `connector/internal/tally` is a **template**. Tally's XML schema
varies by version and company configuration, and the only authoritative source
is your own installation. `scripts/tally-probe.ps1` collects the evidence;
`docs/PHASE0.md` explains what to do with it.

The simulator proves the plumbing — aggregation, idempotency, the quantity
ceilings, error classification. It cannot prove the XML matches your Tally,
because it was written from the same assumptions.
