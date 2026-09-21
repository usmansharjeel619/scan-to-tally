import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { openDb, applySync, nowIso, type DB } from '../src/db.ts';
import { composeItemName } from '../src/fragment.ts';
import { decideIncomingScan, decideOutgoingScan, validateOutgoingQty } from '../src/validation.ts';

const ITEM = '4098-9792 SSD SENSOR BASE';
const PID = '4098-9792';
const BOX_A = '1124241658336425'; // seeded at 13 -- label says 18
const BOX_B = '1124241658336426'; // seeded at 18
const SESSION = 'sess-test-1';
const SO = 'SO-2026-0041';
const GODOWN = 'Main Store';

/** The exact payload the photographed carton produces. */
function label(pid: string, serial: string, qty: number): string {
  return `${pid}|${serial}|${qty}|`;
}

let db: DB;

beforeEach(() => {
  db = openDb(':memory:');

  applySync(db, {
    items: [
      { name: ITEM, partNo: '0677197CN', baseUnits: 'Nos', hasBatches: true },
      { name: '4090-9001 ADDRESSABLE HEAT DETECTOR', partNo: '0655011AB', baseUnits: 'Nos', hasBatches: true },
      { name: 'MISC-CABLE-2C', baseUnits: 'Mtr', hasBatches: false },
    ],
    godowns: [GODOWN],
    balances: [
      { stockItemName: ITEM, batchName: BOX_A, godownName: GODOWN, closingQty: 13, unit: 'Nos' },
      { stockItemName: ITEM, batchName: BOX_B, godownName: GODOWN, closingQty: 18, unit: 'Nos' },
      { stockItemName: ITEM, batchName: '1124241658336427', godownName: GODOWN, closingQty: 0, unit: 'Nos' },
    ],
    orders: [{
      voucherNumber: SO, partyName: 'Example Project FZC', date: '2026-09-10',
      lines: [{ stockItemName: ITEM, orderedQty: 30, deliveredQty: 0, unit: 'Nos' }],
    }],
  });

  db.prepare(
    `INSERT INTO sessions (id, kind, company, godown, sales_order, state, created_at)
     VALUES (?,?,?,?,?,?,?)`,
  ).run(SESSION, 'INCOMING', 'ACME', GODOWN, SO, 'DRAFT', nowIso());
});

/** Mirrors what the relay does after accepting a scan. */
function addLine(sessionId: string, pid: string, serial: string, qty: number, item = ITEM): void {
  db.prepare(
    `INSERT INTO session_lines
       (session_id, pid, box_serial, qty, unit, stock_item_name, raw_payload, scanned_at)
     VALUES (?,?,?,?,?,?,?,?)`,
  ).run(sessionId, pid, serial, qty, 'Nos', item, label(pid, serial, qty), nowIso());
}

// --- incoming: the duplicate rule -------------------------------------------

test('same part number across different boxes is the normal case', () => {
  const a = decideIncomingScan(db, { sessionId: SESSION, raw: label(PID, BOX_A, 18), symbology: 'CODE128' });
  assert.equal(a.outcome, 'ACCEPT');
  assert.equal(a.beep, 'ACCEPT');
  assert.equal(a.box?.stockItemName, ITEM);
  addLine(SESSION, PID, BOX_A, 18);

  const b = decideIncomingScan(db, { sessionId: SESSION, raw: label(PID, BOX_B, 18), symbology: 'CODE128' });
  assert.equal(b.outcome, 'ACCEPT', 'a different box of the same part must be accepted');
  assert.equal(b.beep, 'ACCEPT');
});

test('same part number and same box is a duplicate, hard blocked', () => {
  addLine(SESSION, PID, BOX_A, 18);

  const dup = decideIncomingScan(db, { sessionId: SESSION, raw: label(PID, BOX_A, 18), symbology: 'CODE128' });
  assert.equal(dup.outcome, 'DUPLICATE');
  assert.equal(dup.beep, 'DUPLICATE');
  assert.ok(!dup.overridable, 'an in-session duplicate must not be overridable');
  assert.ok(dup.message.includes('already on this receipt'));
});

test('the same box number under a different product is a different box', () => {
  addLine(SESSION, PID, BOX_A, 18);
  // Tally scopes a batch under a stock item, so this must NOT collide.
  const other = decideIncomingScan(db, {
    sessionId: SESSION, raw: label('4090-9001', BOX_A, 24), symbology: 'CODE128',
  });
  assert.equal(other.outcome, 'ACCEPT');
});

test('a box received in an earlier session cannot be received again', () => {
  db.prepare(
    `INSERT INTO received_boxes (pid, box_serial, session_id, qty, received_at) VALUES (?,?,?,?,?)`,
  ).run(PID, BOX_A, 'old-session', 18, '2026-08-01T10:00:00.000Z');

  const dup = decideIncomingScan(db, {
    sessionId: SESSION, raw: label(PID, BOX_A, 18), symbology: 'CODE128',
  });

  assert.equal(dup.outcome, 'DUPLICATE');
  assert.ok(dup.message.includes('2026-08-01'), 'say when, so it can be checked');
  assert.ok(dup.message.includes('cannot be received twice'));

  // It used to offer "accept again if this is a return". An override on the one
  // rule that keeps stock honest is an override that gets used -- at the end of
  // a shift, on the box that will not scan, by whoever is in a hurry.
  assert.equal((dup as Record<string, unknown>).overridable, undefined,
    'there must be nothing to dismiss');

  // And no argument can talk it round.
  const forced = decideIncomingScan(db, {
    sessionId: SESSION, raw: label(PID, BOX_A, 18), symbology: 'CODE128',
    overrideDuplicate: true,
  } as Record<string, unknown> as never);
  assert.equal(forced.outcome, 'DUPLICATE', 'no input may turn this into an accept');
});

// --- incoming: never block the dock -----------------------------------------

test('an unknown product is counted and flagged, never refused', () => {
  const d = decideIncomingScan(db, {
    sessionId: SESSION, raw: label('9999-0000', '1124249900000001', 12), symbology: 'CODE128',
  });
  assert.equal(d.outcome, 'FLAGGED');
  assert.equal(d.beep, 'FLAGGED');
  assert.ok(d.flags.includes('UNRESOLVED_PID'));
  assert.equal(d.box?.labelQty, 12, 'the count is right even when the identity is not');
});

test('an item without batch support is flagged, because a box cannot be tracked on it', () => {
  db.prepare(
    `INSERT INTO pid_bindings (pid, stock_item_name, source, bound_at) VALUES (?,?,?,?)`,
  ).run('CABLE-01', 'MISC-CABLE-2C', 'SUPERVISOR', nowIso());

  const d = decideIncomingScan(db, {
    sessionId: SESSION, raw: label('CABLE-01', '1124249900000002', 5), symbology: 'CODE128',
  });
  assert.equal(d.outcome, 'FLAGGED');
  assert.ok(d.flags.includes('NO_BATCH_SUPPORT'));
});

test('scanning one of the label\'s other barcodes says which one to use', () => {
  const pidCode = decideIncomingScan(db, { sessionId: SESSION, raw: PID, symbology: 'CODE128' });
  assert.equal(pidCode.outcome, 'WRONG_BARCODE');
  assert.ok(pidCode.message.includes('long serial barcode'));

  const partNo = decideIncomingScan(db, { sessionId: SESSION, raw: '0677197CN', symbology: 'CODE128' });
  assert.equal(partNo.outcome, 'WRONG_BARCODE');
  assert.ok(partNo.message.includes('part-number'));
});

// --- outgoing: the ceiling --------------------------------------------------

test('the printed box quantity is not the ceiling -- Tally is', () => {
  const d = decideOutgoingScan(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    raw: label(PID, BOX_A, 18), symbology: 'CODE128',
  });
  assert.equal(d.outcome, 'ACCEPT');
  assert.equal(d.box?.labelQty, 18, 'the label still says 18');
  assert.equal(d.available, 13, 'but only 13 remain');
  assert.ok(d.availableAsOf, 'staleness must be visible to the operator');
});

test('a box with nothing left is refused before a quantity screen appears', () => {
  const d = decideOutgoingScan(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    raw: label(PID, '1124241658336427', 18), symbology: 'CODE128',
  });
  assert.equal(d.outcome, 'REJECT');
  assert.equal(d.beep, 'REJECT');
  assert.equal(d.available, 0);
});

test('an item not on the selected order is refused -- the point of the whole check', () => {
  const d = decideOutgoingScan(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    raw: label('4090-9001', '0701240099887766', 24), symbology: 'CODE128',
  });
  assert.equal(d.outcome, 'REJECT');
  assert.equal(d.beep, 'REJECT');
  assert.ok(d.message.includes('not on order'));
});

test('an unmapped product cannot be despatched', () => {
  const d = decideOutgoingScan(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    raw: label('9999-0000', '1124249900000003', 5), symbology: 'CODE128',
  });
  assert.equal(d.outcome, 'REJECT');
  assert.ok(d.message.includes('not in Tally'), d.message);
});

test('rescanning a box on the same despatch re-opens its line instead of adding another', () => {
  addLine(SESSION, PID, BOX_A, 5);
  const d = decideOutgoingScan(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    raw: label(PID, BOX_A, 18), symbology: 'CODE128',
  });
  assert.equal(d.outcome, 'ACCEPT');
  assert.ok(d.editLineId, 'should point at the existing line');
  assert.equal(d.available, 8, '13 on hand minus 5 already entered');
});

// --- outgoing: typed quantity -----------------------------------------------

test('quantity above what the box holds is a hard error', () => {
  const v = validateOutgoingQty(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    pid: PID, boxSerial: BOX_A, stockItemName: ITEM, qty: 18,
  });
  assert.equal(v.ok, false);
  assert.equal(v.available, 13);
  assert.ok(v.error?.includes('Only 13 left'));
});

test('quantity equal to what remains is accepted', () => {
  const v = validateOutgoingQty(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    pid: PID, boxSerial: BOX_A, stockItemName: ITEM, qty: 13,
  });
  assert.equal(v.ok, true);
  assert.equal(v.error, undefined);
});

test('zero or negative is refused', () => {
  for (const qty of [0, -1]) {
    const v = validateOutgoingQty(db, {
      sessionId: SESSION, salesOrder: SO, godown: GODOWN,
      pid: PID, boxSerial: BOX_A, stockItemName: ITEM, qty,
    });
    assert.equal(v.ok, false, `qty ${qty} must be refused`);
  }
});

test('quantities across several lines of one box cannot exceed the box', () => {
  addLine(SESSION, PID, BOX_A, 10);
  const v = validateOutgoingQty(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    pid: PID, boxSerial: BOX_A, stockItemName: ITEM, qty: 5,
  });
  assert.equal(v.ok, false, '10 + 5 exceeds the 13 on hand');
  assert.equal(v.available, 3);
});

test('exceeding the order is refused', () => {
  // Order is for 20 and 18 are already on this session, so only 2 remain. The
  // box has plenty, which is what makes this purely the order ceiling.
  addLine(SESSION, PID, BOX_B, 18);
  db.prepare(`UPDATE sales_order_lines SET ordered_qty = 20 WHERE voucher_number = ?`).run(SO);

  const v = validateOutgoingQty(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    pid: PID, boxSerial: BOX_A, stockItemName: ITEM, qty: 5,
  });
  // Was a warning, on the reasoning that over-shipping within tolerance is a
  // business decision. It let six go out against an order for four, so the
  // business asked for it refused.
  assert.equal(v.ok, false, 'more than the order asks for must not be sendable');
  assert.match(String(v.error), /outstanding/);
  assert.equal(v.orderPending, 2);
});

test('up to what the order still has outstanding is accepted', () => {
  addLine(SESSION, PID, BOX_B, 18);
  db.prepare(`UPDATE sales_order_lines SET ordered_qty = 20 WHERE voucher_number = ?`).run(SO);

  const v = validateOutgoingQty(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    pid: PID, boxSerial: BOX_A, stockItemName: ITEM, qty: 2,
  });
  assert.equal(v.ok, true, v.error ?? '');
});

test('editing an existing line does not count that line against itself', () => {
  addLine(SESSION, PID, BOX_A, 13);
  const lineId = (db.prepare(
    `SELECT id FROM session_lines WHERE session_id = ? AND box_serial = ?`,
  ).get(SESSION, BOX_A) as { id: number }).id;

  const v = validateOutgoingQty(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    pid: PID, boxSerial: BOX_A, stockItemName: ITEM, qty: 13, excludeLineId: lineId,
  });
  assert.equal(v.ok, true, 'changing 13 to 13 must not report the box as full');
  assert.equal(v.available, 13);
});

// --- ambiguous PIDs ---------------------------------------------------------
//
// Found in a live catalogue: one Simplex part number entered under EIGHT
// different item names, because each sale bundled it differently. 17 PIDs were
// affected. Auto-picking one posts stock against the wrong item and nothing
// downstream ever notices.

test('a PID matching several items never auto-resolves', () => {
  applySync(db, {
    items: [
      { name: '4098-9714 SSD SMOKE SENSOR', baseUnits: 'NO', hasBatches: true },
      { name: '4098-9714 Smoke Detector with Sounder Base', baseUnits: 'NO', hasBatches: true },
      { name: '4098-9714 SIMPLEX SMOKE DETECTOR WITH BASE', baseUnits: 'NO', hasBatches: true },
    ],
  });

  const d = decideIncomingScan(db, {
    sessionId: SESSION, raw: '4098-9714|1124249900007001|18|', symbology: 'CODE128',
  });

  assert.equal(d.outcome, 'FLAGGED', 'counted, but not resolved');
  assert.ok(d.flags.includes('AMBIGUOUS_PID'), 'must be flagged as ambiguous, not unknown');
  assert.ok(!d.flags.includes('UNRESOLVED_PID'), 'ambiguous is a different problem from unknown');
  assert.equal(d.box?.stockItemName, '', 'must NOT have silently picked one of the three');
  assert.equal(d.box?.labelQty, 18, 'the count is still right');
  assert.ok(/matches 3 products/.test(d.message), `message should name the count: ${d.message}`);
});

test('an ambiguous PID cannot be despatched', () => {
  applySync(db, {
    items: [
      { name: '4090-9001 IAM MODULE', baseUnits: 'NO', hasBatches: true },
      { name: '4090-9001 IAM RELAY IDENT', baseUnits: 'NO', hasBatches: true },
    ],
  });

  const d = decideOutgoingScan(db, {
    sessionId: SESSION, salesOrder: SO, godown: GODOWN,
    raw: '4090-9001|1124249900007002|5|', symbology: 'CODE128',
  });

  assert.equal(d.outcome, 'REJECT', 'outgoing has nothing unambiguous to deduct from');
  assert.equal(d.beep, 'REJECT');
  assert.ok(/matches \d+ different items/.test(d.message), d.message);
  assert.ok(/cannot be despatched/.test(d.message), d.message);
});

test('a PID matching exactly one item still resolves by name prefix', () => {
  // The live catalogue names items "<PID> <DESCRIPTION>" and populates no
  // PARTNO at all, so this prefix match is the path that actually works.
  applySync(db, {
    items: [{ name: '4099-9006 DS PUSH PULL TYPE MPS', baseUnits: 'NO', hasBatches: true }],
  });

  const d = decideIncomingScan(db, {
    sessionId: SESSION, raw: '4099-9006|1124249900007003|4|', symbology: 'CODE128',
  });

  assert.equal(d.outcome, 'ACCEPT');
  assert.equal(d.box?.stockItemName, '4099-9006 DS PUSH PULL TYPE MPS');
});

// --- the same model printed two ways ----------------------------------------

test('a box is a duplicate whether or not the carton printed the dash', () => {
  // Real: cartons of 4100-9701 arrive printed both "41009701" and "4100-9701".
  // Stored verbatim -- the audit trail should say what was on the box -- those
  // are two different strings, and a duplicate check matching them literally
  // would let the SAME physical box be received twice, once under each
  // spelling. Stock doubled, nothing on screen.
  const serial = '1124249900007001';
  db.prepare(
    `INSERT INTO stock_items (name, base_units, has_batches, synced_at) VALUES (?,?,?,?)`,
  ).run('4100-9701 4100ES MASTER CONTROL PANEL', 'Nos', 1, nowIso());

  addLine(SESSION, '41009701', serial, 4, '4100-9701 4100ES MASTER CONTROL PANEL');

  const dashed = decideIncomingScan(db, {
    sessionId: SESSION, raw: `4100-9701|${serial}|4|`, symbology: 'CODE128',
  });
  assert.equal(dashed.outcome, 'DUPLICATE',
    'the dashed spelling must find the box received under the undashed one');

  const undashed = decideIncomingScan(db, {
    sessionId: SESSION, raw: `41009701|${serial}|4|`, symbology: 'CODE128',
  });
  assert.equal(undashed.outcome, 'DUPLICATE');
});

test('the part number is stored exactly as the carton printed it', () => {
  // The reconciling happens at lookup time. Nothing rewrites the payload: a
  // carton that printed 40989792 must leave 40989792 behind, not a number that
  // was never on the box.
  const d = decideIncomingScan(db, {
    sessionId: SESSION, raw: '40989792|1124249900008001|18|', symbology: 'CODE128',
  });
  assert.equal(d.box?.pid, '40989792');
  assert.equal(d.parse.box?.pid, '40989792');
});

test('both spellings resolve to the one Tally item', () => {
  db.prepare(
    `INSERT INTO stock_items (name, base_units, has_batches, synced_at) VALUES (?,?,?,?)`,
  ).run('4100-9702 IDNAC REPEATER', 'Nos', 1, nowIso());

  for (const printed of ['4100-9702', '41009702']) {
    const d = decideIncomingScan(db, {
      sessionId: `sess-${printed}`, raw: `${printed}|112424990000900${printed.length}|2|`,
      symbology: 'CODE128',
    });
    assert.equal(d.box?.stockItemName, '4100-9702 IDNAC REPEATER',
      `${printed} should find the item`);
  }
});

// --- naming a product that has never been seen ------------------------------

test('the part number is not put into the name twice', () => {
  // Real, from the live day book: items were created as
  // "4098-5266 4098-5266 PHOTO SENSOR W/REED". The description already carried
  // the part number and the composer prefixed it regardless. Two spellings of
  // one product is two products as far as Tally is concerned.
  assert.equal(composeItemName('4098-5266', 'PHOTO SENSOR W/REED'),
    '4098-5266 PHOTO SENSOR W/REED');
  assert.equal(composeItemName('4098-5266', '4098-5266 PHOTO SENSOR W/REED'),
    '4098-5266 PHOTO SENSOR W/REED', 'already prefixed');
  // Across spellings, because a carton and the price list need not agree.
  assert.equal(composeItemName('4098-5266', '40985266 PHOTO SENSOR W/REED'),
    '40985266 PHOTO SENSOR W/REED', 'undashed prefix still counts as present');
  assert.equal(composeItemName('40985266', '4098-5266 PHOTO SENSOR W/REED'),
    '4098-5266 PHOTO SENSOR W/REED', 'dashed prefix still counts as present');
  // A part number that merely looks similar is NOT a prefix.
  assert.equal(composeItemName('4098-5266', '4098-5267 SOMETHING ELSE'),
    '4098-5266 4098-5267 SOMETHING ELSE');
});
