/**
 * One Physical Stock voucher per product, kept and added to.
 *
 * A product counted on Monday and again on Friday is ONE entry in the day book
 * with both weeks' boxes on it, not two entries side by side. That was asked
 * for directly, and it puts a sharp edge on the posting path: Tally REPLACES a
 * voucher on alter rather than merging into it, so every later receipt must
 * re-send every box the voucher already had. Send only the new ones and the
 * earlier cartons are deleted from the books.
 *
 * That is the failure these exist to catch.
 */
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

process.env.STT_DB = ':memory:';
process.env.STT_PORT = '0';
process.env.STT_CONNECTOR_SECRET = 'test-secret';
process.env.LOG_LEVEL = 'silent';

const { app, db, applyJobResult } = await import('../src/server.ts');
const { applySync, nowIso } = await import('../src/db.ts');
const { buildJobs } = await import('../src/job.ts');

const TOKEN = 'device-token-for-tests';
const GODOWN = 'Main Location';
const COMPANY = 'New Test Company';
const PID = '4090-5201';
const ITEM = '4090-5201 MINI IAM';
const OTHER_PID = '4098-9792';
const OTHER_ITEM = '4098-9792 SSD SENSOR BASE';

const auth = { authorization: `Bearer ${TOKEN}` };

before(() => app.ready());
after(() => app.close());

beforeEach(() => {
  for (const t of ['session_lines', 'sessions', 'session_vouchers', 'proposed_items',
                   'pid_bindings', 'stock_items', 'received_boxes', 'devices',
                   'item_vouchers', 'posted_batches']) {
    db.prepare(`DELETE FROM ${t}`).run();
  }
  db.prepare(`INSERT INTO devices (id, name, company, godown, token_hash, operator, created_at)
              VALUES ('dock-1','Dock 1',?,?,?,'Tester',?)`)
    .run(COMPANY, GODOWN, createHash('sha256').update(TOKEN).digest('hex'), nowIso());

  applySync(db, {
    items: [
      { name: ITEM, baseUnits: 'Nos', hasBatches: true },
      { name: OTHER_ITEM, baseUnits: 'Nos', hasBatches: true },
    ],
    godowns: [GODOWN],
    balances: [],
  } as any);
});

async function receipt(boxes: Array<{ pid: string; box: string; qty: number }>): Promise<string> {
  const r = await app.inject({
    method: 'POST', url: '/api/v1/sessions', headers: auth,
    payload: { kind: 'INCOMING', godown: GODOWN, operator: 'Tester' },
  });
  const id = r.json().sessionId as string;
  for (const b of boxes) {
    const line = await app.inject({
      method: 'POST', url: `/api/v1/sessions/${id}/lines`, headers: auth,
      payload: { pid: b.pid, boxSerial: b.box, qty: b.qty, raw: 'x' },
    });
    assert.equal(line.statusCode, 200, line.body);
  }
  return id;
}

/** Posts a session the way the connector would: one result per voucher. */
function tallyAccepts(sessionId: string, masterIdFor: (item: string) => string): void {
  const vouchers = db.prepare(
    `SELECT voucher_key, stock_item_name FROM session_vouchers WHERE session_id=?`,
  ).all(sessionId) as Array<{ voucher_key: string; stock_item_name: string }>;
  for (const v of vouchers) {
    applyJobResult({
      jobId: v.voucher_key, sessionId: v.voucher_key, ok: true,
      tallyVoucherId: masterIdFor(v.stock_item_name),
    } as any);
  }
}

async function submit(id: string) {
  return app.inject({
    method: 'POST', url: `/api/v1/sessions/${id}/submit`, headers: auth, payload: {},
  });
}

test('the second receipt of a product alters the first voucher instead of making another', async () => {
  const first = await receipt([{ pid: PID, box: 'HIA634', qty: 35 }]);
  await submit(first);
  tallyAccepts(first, () => '39');

  const second = await receipt([{ pid: PID, box: 'HLA133', qty: 35 }]);
  const { jobs } = buildJobs(db, second);

  assert.equal(jobs.length, 1);
  assert.equal(jobs[0]!.alterMasterId, '39', 'the new carton must go into the voucher that exists');
});

test('an alter carries the boxes the voucher already has, or Tally deletes them', async () => {
  const first = await receipt([
    { pid: PID, box: 'HIA634', qty: 35 },
    { pid: PID, box: 'HIA739', qty: 35 },
  ]);
  await submit(first);
  tallyAccepts(first, () => '39');

  const second = await receipt([{ pid: PID, box: 'HLA133', qty: 20 }]);
  const { jobs } = buildJobs(db, second);

  const boxes = jobs[0]!.lines[0]!.boxes;
  assert.deepEqual(
    boxes.map((b) => b.boxSerial).sort(),
    ['HIA634', 'HIA739', 'HLA133'],
    'every box on the voucher must be re-sent; Tally replaces rather than merges',
  );
  assert.equal(boxes.find((b) => b.boxSerial === 'HIA634')!.qty, 35);
  assert.equal(boxes.find((b) => b.boxSerial === 'HLA133')!.qty, 20);
});

test('each product keeps its own voucher', async () => {
  const first = await receipt([{ pid: PID, box: 'HIA634', qty: 35 }]);
  await submit(first);
  tallyAccepts(first, () => '39');

  const second = await receipt([{ pid: OTHER_PID, box: 'HKT710', qty: 12 }]);
  await submit(second);
  tallyAccepts(second, () => '40');

  const third = await receipt([
    { pid: PID, box: 'HLA133', qty: 35 },
    { pid: OTHER_PID, box: 'HKT711', qty: 12 },
  ]);
  const { jobs } = buildJobs(db, third);

  const byItem = new Map(jobs.map((j) => [j.lines[0]!.stockItemName, j]));
  assert.equal(byItem.get(ITEM)!.alterMasterId, '39');
  assert.equal(byItem.get(OTHER_ITEM)!.alterMasterId, '40');
  assert.equal(byItem.get(ITEM)!.lines[0]!.boxes.length, 2);
  assert.equal(byItem.get(OTHER_ITEM)!.lines[0]!.boxes.length, 2);
});

test('a box recounted on a later receipt replaces its earlier count, never doubles it', async () => {
  const first = await receipt([{ pid: PID, box: 'HIA634', qty: 35 }]);
  await submit(first);
  tallyAccepts(first, () => '39');

  // The same carton, counted again and found to hold less.
  db.prepare(`DELETE FROM received_boxes`).run();
  const second = await receipt([{ pid: PID, box: 'HIA634', qty: 30 }]);
  const { jobs } = buildJobs(db, second);

  const boxes = jobs[0]!.lines[0]!.boxes;
  assert.equal(boxes.length, 1, 'one carton is one batch, however many times it is counted');
  assert.equal(boxes[0]!.qty, 30, 'the later count wins');
});

test('a voucher Tally no longer has is forgotten, so the retry raises a new one', async () => {
  const first = await receipt([{ pid: PID, box: 'HIA634', qty: 35 }]);
  await submit(first);
  tallyAccepts(first, () => '39');

  const second = await receipt([{ pid: PID, box: 'HLA133', qty: 35 }]);
  await submit(second);

  const key = (db.prepare(`SELECT voucher_key FROM session_vouchers WHERE session_id=?`)
    .get(second) as { voucher_key: string }).voucher_key;
  applyJobResult({
    jobId: key, sessionId: key, ok: false,
    errorCode: 'ALTER_TARGET_MISSING', errorMessage: 'Voucher 39 is not in Tally any more.',
  } as any);

  assert.equal(
    db.prepare(`SELECT COUNT(*) c FROM item_vouchers WHERE stock_item_name=?`).get(ITEM).c, 0,
    'a stale voucher id must not fail every future receipt of this product',
  );

  // The boxes are kept, so the new voucher still gets all of them.
  const { jobs } = buildJobs(db, second);
  assert.equal(jobs[0]!.alterMasterId, undefined, 'nothing to alter now -- create');
  assert.deepEqual(jobs[0]!.lines[0]!.boxes.map((b) => b.boxSerial).sort(),
    ['HIA634', 'HLA133']);
});

test('a despatch is never folded into a standing voucher', async () => {
  // Altering somebody's delivery note a week later would be indefensible.
  const first = await receipt([{ pid: PID, box: 'HIA634', qty: 35 }]);
  await submit(first);
  tallyAccepts(first, () => '39');

  const out = await app.inject({
    method: 'POST', url: '/api/v1/sessions', headers: auth,
    payload: { kind: 'OUTGOING', godown: GODOWN, operator: 'Tester', party: 'A Customer' },
  });
  const outId = out.json().sessionId as string;
  db.prepare(`INSERT INTO session_lines (session_id, pid, box_serial, qty, unit,
              stock_item_name, description, raw_payload, symbology, flags, scanned_at)
              VALUES (?,?,?,?,?,?,'','','','',?)`)
    .run(outId, PID, 'HIA634', 5, 'Nos', ITEM, nowIso());

  const { jobs } = buildJobs(db, outId);
  assert.equal(jobs[0]!.alterMasterId, undefined);
  assert.equal(jobs[0]!.lines[0]!.boxes.length, 1, 'a despatch sends only what is going out');
});
