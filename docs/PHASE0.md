# Phase 0 — prove the Tally round-trip

Everything in `connector/internal/tally` is a **template**, written from
documentation rather than from your installation. Tally's XML schema drifts
between ERP 9 and Prime releases and between company configurations, and the
only authoritative source is the Tally you will actually post to.

This is ~2 days of work that de-risks most of the project. Do it before writing
anything that depends on the answers.

---

## The method

Do not try to get the XML right by reading about it. **Make Tally produce it.**

1. Key the voucher by hand in Tally.
2. Export it as XML.
3. Diff that against what the connector emits.
4. Make the connector match.

A voucher Tally created is ground truth. Nothing else is.

---

## Step 1 — a test company

`File > New Company`, or restore a backup of the real one under a different
name. Never do this against live books: the integration tests post real
vouchers.

## Step 2 — turn on the gateway

TallyPrime → `F1 Help` → `Settings` → `Connectivity` →
`Client/Server configuration`:

| | |
|---|---|
| TallyPrime acts as | **Both** |
| Enable ODBC | Yes |
| Port | **9000** |

Leave the company open.

## Step 3 — the two vouchers that matter

In the test company, dated **today**:

**A. A Receipt Note** — one stock item, **three different batches** (box
numbers), a different quantity on each, a godown set on each batch, plus a
reference and a narration.

This is the shape the whole incoming flow produces, and the nesting is the thing
most likely to be wrong: one `ALLINVENTORYENTRIES.LIST` per part number with N
`BATCHALLOCATIONS.LIST` beneath it. Emitting one entry per box is accepted by
Tally and quietly ruins its stock reports.

**B. A Sales Order, then a Delivery Note raised against it**, delivering only
*part* of the ordered quantity from a named batch.

This settles how the order link is carried — `ORDERNO` in the batch allocation,
`TRACKINGNUMBER`, or something else in your version.

If you use per-line descriptions, put one on a line in voucher A. That settles
whether it is `BASICUSERDESCRIPTION`.

## Step 4 — collect the evidence

Run `scripts/tally-probe.ps1` on that machine. It is export-only; it creates,
alters and deletes nothing. It drops a zip on the Desktop.

## Step 5 — read the answers

| Question | File |
|---|---|
| Exact XML for a multi-batch Receipt Note | `STT_DayBook.xml` |
| How a Delivery Note links to a Sales Order | `STT_DayBook.xml` |
| Whether per-line description is `BASICUSERDESCRIPTION` | `STT_DayBook.xml` |
| Which batch-balance query works | `STT_BatchBalances_A/B/C.xml` |
| How stock items are named, and whether batch-wise is on | `STT_StockItems.xml` |
| Godown and voucher-type names | `STT_Godowns.xml`, `STT_VoucherTypes.xml` |

The probe deliberately tries **three** different batch-balance collections,
because that is the query least certain to be right. Whichever comes back
populated is the one to use.

## Step 6 — make the connector match

Compare against what we emit:

```bash
cd connector
go test ./internal/tally/ -run TestGolden -v    # once golden files are in place
```

Two kinds of change come out of this:

**TDL queries** are overridable from the config file — no rebuild:

```json
{ "tally": { "tdl": { "batchBalances": "<COLLECTION NAME=...>...</COLLECTION>" } } }
```

**Voucher field names** live in `connector/internal/tally/import.go`. Every one
of them carries a `VERIFY` comment; those comments come off as each is confirmed.

## Step 7 — round-trip it

Post the connector's own XML back into the test company and confirm the stock
report moves the way it should.

```bash
curl -s -X POST http://<tally-machine>:9000 \
     -H 'Content-Type: text/xml' --data-binary @receipt-note.xml
```

A successful import answers with `<CREATED>1</CREATED>`. **Do not trust the HTTP
status** — Tally returns 200 with an error body, which is a bug this project has
already been bitten by once.

---

## Specific things to confirm

- [ ] `ISDEEMEDPOSITIVE` polarity on Receipt Note vs Delivery Note. A flipped
      sign posts the movement backwards and does not show up in the response,
      only in the stock report a week later.
- [ ] `PERSISTEDVIEW` — whether it is needed for inventory vouchers.
- [ ] `ORDERNO` vs `TRACKINGNUMBER` on a Delivery Note against a Sales Order.
- [ ] `MFDON` — whether the batch manufacturing date is accepted, and whether
      the item needs "Use expiry/manufacturing dates" enabled first.
- [ ] Whether Tally allows **negative stock** in this company. Do not rely on it
      refusing; the app is the gate either way, but it changes what a failed
      over-ship looks like.
- [ ] Whether a `REFERENCE` is preserved and searchable. It is the idempotency
      key and is what makes reconciliation survive losing the connector's
      database.
- [ ] Compound units ("Box of 24 Nos"), if you use them: is a scanned quantity
      always in base units?

## The fourth barcode field

Separately from Tally: get a label from a product that actually has firmware — a
panel card or network module, not a passive base. The reference label prints
`FIRMWARE: N/A` and its barcode ends with an empty fourth field, which is strong
but unconfirmed evidence that the format is `PID|SERIAL|QTY|FIRMWARE`.

The parser already accepts both three and four fields, so nothing breaks either
way. Confirming it just means we can use the value.
