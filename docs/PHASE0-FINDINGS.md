# Phase 0 findings

Recorded against a live TallyPrime, company `New Test Company`, reached through
the connector's read-only diagnostic channel on 2026-09-16.

## Confirmed

**Response envelope.** Every Export answers with a `CMPINFO` preamble inside
`DESC`, then the rows under `BODY > DATA > COLLECTION`:

```xml
<ENVELOPE><HEADER><VERSION>1</VERSION><STATUS>1</STATUS></HEADER>
 <BODY>
  <DESC><CMPINFO>...counters...</CMPINFO></DESC>
  <DATA><COLLECTION><COMPANY NAME="New Test Company"> ... </COMPANY></COLLECTION></DATA>
 </BODY></ENVELOPE>
```

The connector searches for elements **by name at any depth** rather than
asserting a path, so this nesting needed no change. That decision paid off.

**All four TDL collections execute** and return `STATUS 1`: companies, stock
items, godowns, voucher types. They were guesses written from documentation;
they are now confirmed against a real installation.

**Every voucher type we need exists** in a default company: `Receipt Note`,
`Delivery Note`, `Physical Stock`, `Sales Order`, `Purchase Order`,
`Stock Journal`.

## Corrected

**The entity name lives in the `NAME` attribute**, not reliably in a child
element. For godowns and voucher types the `<NAME>` element is buried under
`LANGUAGENAME.LIST > NAME.LIST > NAME`, which a flat Go struct tag never
reaches. Only `COMPANY` had a usable top-level `<NAME>`.

The parsers now read the attribute first and fall back to the element. Had the
fallback not already been there, the item master would have synced as a list of
blank names.

**The default godown is `Main Location`, not `Main Store`.** Everything written
before this -- device config, simulator seed data, docs -- used `Main Store`.
Since a batch balance is keyed on (item, batch, **godown**), every outgoing
quantity check would have looked up a godown that does not exist, found no
balance, and refused every despatch as "not in stock".

**Control characters appear in values.** `PARENT` came back as `&#4; Primary`.
The decoder already runs non-strict, so this passes through.

## Still unproven

The test company is **empty** -- no stock items, no ledgers, no orders. So
nothing below has been exercised against real Tally yet, and every one of them
is a `VERIFY` comment still standing in `internal/tally`:

- the multi-batch Receipt Note shape (one inventory entry, N batch allocations)
- how a Delivery Note links to a Sales Order (`ORDERNO` / `TRACKINGNUMBER`)
- `ISDEEMEDPOSITIVE` polarity in vs out
- `MFDON` (batch manufacturing date)
- `BASICUSERDESCRIPTION` (per-line description)
- which of the three batch-balance queries returns closing stock
- whether the company allows negative stock

These need data in the company. See the note at the end of `PHASE0.md`.

## Tally crashes on malformed master XML

Sending `<UNIT ACTION="Create">` with no `NAME` attribute did not produce an
error response. It crashed TallyPrime outright:

```
Internal Error. Contact Tally Solutions.
Software Exception c0000005 (Memory Access Violation)
```

This matters well beyond the seeding script.

**Tally cannot be treated as a system that validates its input.** A malformed
request may be rejected with a `LINEERROR`, or it may take the process down and
with it the whole warehouse's connection. The connector must therefore only ever
send shapes that are known to work, which is what it does -- it builds every
voucher from typed structures and never passes through XML it did not construct.

It also vindicates keeping the diagnostic channel **read-only**. Had the
speculative write path been in place when this happened, the crash would have
arrived over the network from the relay rather than from someone standing at
the machine.

**Do not brute-force XML shapes against a live Tally.** The earlier seeding
script did exactly that and is withdrawn (`docs/withdrawn/`). Learn the schema
the way PHASE0.md already prescribed: create the record in Tally's UI, export
it, and read what Tally wrote.
