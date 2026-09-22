/**
 * A product with no description does not reach Tally.
 *
 * This rule replaced its opposite, and the reason is worth keeping. Creating
 * an unnamed product under its bare part number was tried first, so that a
 * skipped prompt could never strand a carton. On the dock it produced items
 * called "0635484" and "2084000" in the day book with no description -- and
 * they were not new products at all. They were the PART NUMBER read where the
 * PID was meant, so real stock landed on products that do not exist.
 *
 * An unnamed product is nearly always a misread. Holding the receipt costs a
 * prompt; posting it costs an afternoon in Tally unpicking stock from an
 * invented item.
 *
 * Nothing is lost either way: the boxes stay on the session and it posts by
 * itself the moment a name arrives.
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
                   'product_catalogue', 'item_vouchers', 'posted_batches']) {
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

function catalogue(pid: string, description: string): void {
  db.prepare(`INSERT INTO product_catalogue (pid, description, source, alternates, loaded_at)
              VALUES (?,?,'test','[]',?)`).run(pid, description, nowIso());
}

test('a product nobody named is NOT created, and its receipt does not post', async () => {
  const id = await openSession();
  await line(id, { pid: NEW_PID, boxSerial: 'GUM145', qty: 20, raw: 'x', manual: true });

  const r = await submit(id);
  assert.equal(r.statusCode, 200, r.body);
  const body = r.json();

  assert.equal(body.state, 'QUEUED');
  assert.deepEqual(body.needName, [NEW_PID], 'the operator must be told which product to name');
  assert.match(body.message, /no description/);
  assert.equal(proposal(NEW_PID), undefined, 'nothing may be created under a bare part number');

  // And the boxes are still there, waiting rather than lost.
  const kept = db.prepare(`SELECT COUNT(*) c FROM session_lines WHERE session_id=?`).get(id) as any;
  assert.equal(kept.c, 1);
});

test('the price list is a name, so a part it knows goes straight through', async () => {
  catalogue(NEW_PID, 'PHOTO SENSOR W/REED');

  const id = await openSession();
  await line(id, { pid: NEW_PID, boxSerial: 'GUM146', qty: 20, raw: 'x' });
  const body = (await submit(id)).json();

  assert.deepEqual(body.needName, [], 'nobody needs to type what the price list already knows');
  const p = proposal(NEW_PID);
  assert.ok(p, 'it should have been created');
  assert.equal(p.name, `${NEW_PID} PHOTO SENSOR W/REED`);
  assert.equal(p.base_units, 'Nos', 'the unit must be learned from the company books');
  assert.equal(p.batchwise, 1, 'a box is a batch');
});

test('a part number carrying letters is held like any other unnamed product', async () => {
  const id = await openSession();
  const r = await line(id, { pid: LETTERED_PID, boxSerial: 'HFE927', qty: 24, raw: 'x' });
  assert.equal(r.statusCode, 200, r.body);

  const body = (await submit(id)).json();
  assert.deepEqual(body.needName, [LETTERED_PID]);
  assert.equal(proposal(LETTERED_PID), undefined);
});

test('what the operator typed is what gets created', async () => {
  const id = await openSession();
  await line(id, { pid: NEW_PID, boxSerial: 'GUM147', qty: 20, raw: 'x' });

  const described = await app.inject({
    method: 'POST', url: '/api/v1/proposed-items', headers: auth,
    payload: { pid: NEW_PID, description: 'PHOTO SENSOR W/REED', baseUnits: 'Nos' },
  });
  assert.equal(described.statusCode, 200, described.body);

  const body = (await submit(id)).json();
  assert.deepEqual(body.needName, []);
  assert.equal(proposal(NEW_PID).name, `${NEW_PID} PHOTO SENSOR W/REED`);
});

test('a creation Tally refused is tried again on the next submit', async () => {
  catalogue(NEW_PID, 'PHOTO SENSOR W/REED');
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
  catalogue(NEW_PID, 'PHOTO SENSOR W/REED');
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

test('a box with no part number or no box number is refused outright', async () => {
  // Not a box. A line without one posts a voucher that says nothing: stock
  // against no product, or a batch with no name.
  const id = await openSession();

  const noPid = await line(id, { pid: '  ', boxSerial: 'GUM151', qty: 5, raw: 'x' });
  assert.equal(noPid.statusCode, 400, noPid.body);
  assert.equal(noPid.json().error, 'pid_required');

  const noBox = await line(id, { pid: NEW_PID, boxSerial: '', qty: 5, raw: 'x' });
  assert.equal(noBox.statusCode, 400, noBox.body);
  assert.equal(noBox.json().error, 'box_required');

  const noQty = await line(id, { pid: NEW_PID, boxSerial: 'GUM152', qty: 0, raw: 'x' });
  assert.equal(noQty.statusCode, 400, noQty.body);

  assert.equal((db.prepare(`SELECT COUNT(*) c FROM session_lines WHERE session_id=?`)
    .get(id) as any).c, 0, 'none of them may be recorded');
});
