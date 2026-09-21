/**
 * A scanned box always becomes stock.
 *
 * The operator is asked what an unknown product is, and is free not to answer:
 * the dock does not stop for data entry, and at the end of a shift nobody
 * types. Until this was fixed, not answering meant the line stayed unresolved
 * for ever, holding its whole receipt back -- waiting for a proposal that
 * nothing in the system was ever going to make. Three boxes, 79 units, sat
 * exactly like that in the live relay.
 *
 * So these are about what happens when NOBODY answers.
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

const TOKEN = 'device-token-for-tests';
const GODOWN = 'Main Location';
const NEW_PID = '4098-5267';
const LETTERED_PID = '4090-9001B';

const auth = { authorization: `Bearer ${TOKEN}` };

before(() => app.ready());
after(() => app.close());

beforeEach(() => {
  for (const t of ['session_lines', 'sessions', 'session_vouchers', 'proposed_items',
                   'pid_bindings', 'stock_items', 'received_boxes', 'devices',
                   'product_catalogue']) {
    db.prepare(`DELETE FROM ${t}`).run();
  }
  db.prepare(`INSERT INTO devices (id, name, company, godown, token_hash, operator, created_at)
              VALUES ('dock-1','Dock 1','New Test Company',?,?,'Tester',?)`)
    .run(GODOWN, createHash('sha256').update(TOKEN).digest('hex'), nowIso());

  // The company's own books say "Nos". Nothing may invent "NO".
  applySync(db, {
    items: [{ name: '4098-9792 SSD SENSOR BASE', baseUnits: 'Nos', hasBatches: true }],
    godowns: [GODOWN],
    balances: [],
  } as any);
});

async function openSession(): Promise<string> {
  const r = await app.inject({
    method: 'POST', url: '/api/v1/sessions', headers: auth,
    payload: { kind: 'INCOMING', godown: GODOWN, operator: 'Tester' },
  });
  assert.equal(r.statusCode, 200, r.body);
  return r.json().sessionId;
}

function line(id: string, body: Record<string, unknown>) {
  return app.inject({
    method: 'POST', url: `/api/v1/sessions/${id}/lines`, headers: auth, payload: body,
  });
}

function submit(id: string) {
  return app.inject({
    method: 'POST', url: `/api/v1/sessions/${id}/submit`, headers: auth, payload: {},
  });
}

function proposal(pid: string): any {
  return db.prepare(`SELECT * FROM proposed_items WHERE pid = ?`).get(pid);
}

test('a box nobody described still gets a product, named after the part number', async () => {
  const id = await openSession();
  await line(id, { pid: NEW_PID, boxSerial: 'GUM145', qty: 20, raw: 'x', manual: true });

  // Nobody calls /proposed-items. This is the operator skipping the prompt.
  const r = await submit(id);
  assert.equal(r.statusCode, 200, r.body);

  const p = proposal(NEW_PID);
  assert.ok(p, 'no product was proposed for a scanned box');
  assert.equal(p.state, 'PENDING');
  assert.equal(p.name, NEW_PID, 'an undescribed product is named after its part number');
  assert.equal(p.base_units, 'Nos', 'the unit must be learned from the company books');
  assert.equal(p.batchwise, 1, 'a box is a batch');
});

test('the price list names it when it knows the part', async () => {
  db.prepare(`INSERT INTO product_catalogue (pid, description, source, alternates, loaded_at)
              VALUES (?,?,'test','[]',?)`).run(NEW_PID, 'PHOTO SENSOR W/REED', nowIso());

  const id = await openSession();
  await line(id, { pid: NEW_PID, boxSerial: 'GUM146', qty: 20, raw: 'x' });
  await submit(id);

  assert.equal(proposal(NEW_PID).name, `${NEW_PID} PHOTO SENSOR W/REED`);
});

test('a part number carrying letters is treated like any other', async () => {
  const id = await openSession();
  const r = await line(id, { pid: LETTERED_PID, boxSerial: 'HFE927', qty: 24, raw: 'x' });
  assert.equal(r.statusCode, 200, r.body);
  await submit(id);

  assert.equal(proposal(LETTERED_PID)?.name, LETTERED_PID);
});

test('what the operator typed is never overwritten by a guess', async () => {
  const id = await openSession();
  await line(id, { pid: NEW_PID, boxSerial: 'GUM147', qty: 20, raw: 'x' });

  const described = await app.inject({
    method: 'POST', url: '/api/v1/proposed-items', headers: auth,
    payload: { pid: NEW_PID, description: 'PHOTO SENSOR W/REED', baseUnits: 'Nos' },
  });
  assert.equal(described.statusCode, 200, described.body);

  await submit(id);
  assert.equal(proposal(NEW_PID).name, `${NEW_PID} PHOTO SENSOR W/REED`);
});

test('a creation Tally refused is tried again on the next submit', async () => {
  const id = await openSession();
  await line(id, { pid: NEW_PID, boxSerial: 'GUM148', qty: 20, raw: 'x' });
  await submit(id);

  db.prepare(`UPDATE proposed_items SET state='FAILED', error='Tally was busy' WHERE pid=?`)
    .run(NEW_PID);

  const id2 = await openSession();
  await line(id2, { pid: NEW_PID, boxSerial: 'GUM149', qty: 20, raw: 'x' });
  await submit(id2);

  const p = proposal(NEW_PID);
  assert.equal(p.state, 'PENDING', 'a failed creation must not strand the boxes for ever');
  assert.equal(p.error, '');
});

test('the receipt posts by itself once the product exists', async () => {
  const id = await openSession();
  await line(id, { pid: NEW_PID, boxSerial: 'GUM150', qty: 20, raw: 'x' });

  const held = await submit(id);
  assert.equal(held.json().state, 'QUEUED');
  assert.equal(held.json().unresolvedLines, 1);

  // Tally answers the creation the relay sent without being asked.
  applyJobResult({
    jobId: `mk-${NEW_PID}`, sessionId: NEW_PID, ok: true,
    tallyVoucherId: `${NEW_PID} PHOTO SENSOR W/REED`,
  } as any);

  const row = db.prepare(`SELECT stock_item_name, unit, flags FROM session_lines
                          WHERE session_id=?`).get(id) as any;
  assert.equal(row.stock_item_name, `${NEW_PID} PHOTO SENSOR W/REED`,
    'the box scanned before Tally answered must be filled in, not left behind');
  assert.equal(row.unit, 'Nos');
  assert.ok(!row.flags.includes('UNRESOLVED_PID'));
});
