# Phase 0 findings

Read from the live **Northwind Trading Company** (574 stock items,
books from Jan 2021) on 17 Sep 2026, direct from this machine to the Tally PC
at `192.168.50.20:9000`. Read-only throughout; nothing was created or altered.

---

## The one that decides the design

### Batch tracking is OFF, company-wide

```
ISBATCHWISEON (company)   No
items with batches on     0 of 574
```

**A box cannot be a batch in this company as it stands.** The whole box-level
traceability design — scan a box, deduct from that exact box — needs
`Maintain Batches` on at company level and `Maintain in batches` on each item
that will be scanned.

This is a business decision, not a technical one. Options:

1. **Enable batches** on the items the warehouse handles. Full box-level
   traceability, as designed. Needs whoever owns the books to agree.
2. **Ship without batches.** Quantities per item still work — the app still
   posts Receipt Notes and Delivery Notes, still validates against sales
   orders, still prevents over-shipping at the item level. What is lost is
   knowing *which box* a unit came from.

Everything else below holds either way.

---

## Confirmed against live vouchers

**Item naming is `<PID> <DESCRIPTION>`** — exactly what the Simplex labels
carry:

```
4099-9006 DS PUSH PULL TYPE MPS
2081-9044 SURGE PROTECTOR
2080-9057 ABORT SWITCH, SURFACE
```

So a scanned PID resolves by **name prefix**, which is what the resolver
already does. **No item has `PARTNO` populated** (0 of 574), so part-number
matching is dead weight here — the prefix match is the one that works.

**`BATCHALLOCATIONS.LIST` is emitted even with batch tracking off.** It is the
carrier for godown and order linkage, not just batch names. Field names
confirmed verbatim from a live voucher:

```xml
<BATCHALLOCATIONS.LIST>
  <MFDON/>
  <GODOWNNAME>Main Location</GODOWNNAME>
  <BATCHNAME>Primary Batch</BATCHNAME>
  <ORDERNO>&#4; Not Applicable</ORDERNO>
  <TRACKINGNUMBER>&#4; Not Applicable</TRACKINGNUMBER>
  <ACTUALQTY> 3.00 NO</ACTUALQTY>
  <BILLEDQTY> 3.00 NO</BILLEDQTY>
</BATCHALLOCATIONS.LIST>
```

`GODOWNNAME`, `BATCHNAME`, `ORDERNO`, `TRACKINGNUMBER`, `MFDON`, `ACTUALQTY`,
`BILLEDQTY` — every name we emit is correct.

With batches off, Tally writes `Primary Batch`. `&#4; Not Applicable` is its
empty sentinel, not an empty element.

**`ISDEEMEDPOSITIVE` polarity**: `No` on a live SALES INVOICE — goods leaving.
Incoming is `Yes`. As implemented.

**All required voucher types exist** with standard names: `Receipt Note`,
`Delivery Note`, `Physical Stock`, `Sales Order`, `Purchase Order`,
`Stock Journal`. The company has additionally renamed its sales types
(`SALES INVOICE`, `Tax Invoice`, `TAZ INVOICE`, all parented to `Sales A/c`),
which is why voucher type is configurable.

**Godown**: one, `Main Location`.

**Units**: `NO` (551 items), `mts` (5), `EA` (1), 17 with none. Not "Nos" —
which had been assumed everywhere.

---

## Two bugs this found

### The per-line description was being silently dropped

Tally nests it:

```xml
<BASICUSERDESCRIPTION.LIST>
  <BASICUSERDESCRIPTION>4099-9006 Manual Pull Station / ...</BASICUSERDESCRIPTION>
</BASICUSERDESCRIPTION.LIST>
```

A flat `<BASICUSERDESCRIPTION>` — what we emitted — is accepted and then
ignored. No error; the description would simply never appear. Fixed.

The live data also shows they write **bilingual** descriptions (English and
Arabic) on invoice lines, so this field matters to them.

### The idempotency key was about to overwrite a business field

A live invoice carries `<REFERENCE>MI/203-C/09/2026</REFERENCE>` — a real
document number. The design put the session UUID there.

The key now goes in the narration as `[STT:<uuid>]`, leaving `REFERENCE` for
the business. Reconciliation still finds it by reading the day book back.

---

## Also worth knowing

**Tally puts a GUID on every voucher** (`REMOTEID`, plus `MASTERID` and
`ALTERID`). Useful for reconciliation later.

**Tally crashed twice** during this work — once on malformed import XML I sent,
once inside an ordinary read-only `Unit` collection. It cannot be treated as
something that validates its input: a bad request may be rejected, or may take
the process down. The connector now treats a mid-request disconnect as
`TALLY_CRASHED` and backs off hard rather than retrying.

**Never brute-force XML shapes at a live Tally.** Create the record in the UI,
export it, read what Tally wrote.
