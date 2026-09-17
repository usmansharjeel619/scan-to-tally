/**
 * The device decides a scan locally and mirrors it here.
 *
 * That mirroring is what fills the relay's line table, and the voucher is built
 * from that table -- so when this endpoint refuses a line, the scan looks fine
 * on the phone, beeps, appears in the session, and then submits an empty
 * voucher. It failed exactly that way on the first live test, answering 400 to
 * every incoming scan, so these are locked down.
 */
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

process.env.STT_DB = ':memory:';
process.env.STT_PORT = '0';
process.env.STT_CONNECTOR_SECRET = 'test-secret';
process.env.LOG_LEVEL = 'silent';

const { app, db, hub } = await import('../src/server.ts');
const { applySync, nowIso } = await import('../src/db.ts');

const TOKEN = 'device-token-for-tests';
const GODOWN = 'Main Location';
const KNOWN_PID = '4098-9792';
const KNOWN_ITEM = '4098-9792 SSD SENSOR BASE';
const NEW_PID = '4098-0001';

const auth = { authorization: `Bearer ${TOKEN}` };

before(() => app.ready());
// The server listens on import, so without this the test process never exits.
after(() => app.close());

beforeEach(() => {
  for (const t of ['session_lines', 'sessions', 'proposed_items', 'pid_bindings', 'devices']) {
    db.prepare(`DELETE FROM ${t}`).run();
  }
  db.prepare(`INSERT INTO devices (id, name, company, godown, token_hash, operator, created_at)
              VALUES ('dock-1','Dock 1','New Test Company',?,?,'Tester',?)`)
    .run(GODOWN, createHash('sha256').update(TOKEN).digest('hex'), nowIso());

  applySync(db, {
    items: [{ name: KNOWN_ITEM, baseUnits: 'NO', hasBatches: true }],
    godowns: [GODOWN],
    balances: [],
  } as any);
});

async function openSession(kind: string): Promise<string> {
  const r = await app.inject({
    method: 'POST', url: '/api/v1/sessions', headers: auth,
    payload: { kind, godown: GODOWN, operator: 'Tester' },
  });
  assert.equal(r.statusCode, 200, r.body);
  return r.json().sessionId;
}

function line(id: string, body: Record<string, unknown>) {
  return app.inject({
    method: 'POST', url: `/api/v1/sessions/${id}/lines`, headers: auth, payload: body,
  });
}

test('an incoming scan of a known product is recorded', async () => {
  const id = await openSession('INCOMING');
  const r = await line(id, {
    pid: KNOWN_PID, boxSerial: '1124241658336425', qty: 18,
    raw: `${KNOWN_PID}|1124241658336425|18|`, symbology: 'CODE128',
  });
  assert.equal(r.statusCode, 200, r.body);

  const row = db.prepare(`SELECT * FROM session_lines WHERE session_id=?`).get(id) as any;
  assert.equal(row.stock_item_name, KNOWN_ITEM);
  assert.equal(row.qty, 18);
  assert.equal(row.unit, 'NO');
  assert.equal(row.flags, '');
});

test('an unknown part number is kept, not rejected', async () => {
  // The whole new-product flow depends on this. Refusing the line would lose
  // the scan, and the operator has already put the carton on the pallet.
  const id = await openSession('INCOMING');
  const r = await line(id, { pid: NEW_PID, boxSerial: '1124241658336499', qty: 6, raw: 'x' });
  assert.equal(r.statusCode, 200, r.body);

  const row = db.prepare(`SELECT * FROM session_lines WHERE session_id=?`).get(id) as any;
  assert.equal(row.stock_item_name, '');
  assert.equal(row.flags, 'UNRESOLVED_PID');
});

test('the same box mirrored twice makes one line', async () => {
  // A retry after a dropped reply must not double the stock.
  const id = await openSession('INCOMING');
  const body = { pid: KNOWN_PID, boxSerial: '1124241658336425', qty: 18, raw: 'x' };
  const first = await line(id, body);
  const second = await line(id, body);

  assert.equal(second.statusCode, 200, second.body);
  assert.equal(second.json().lineId, first.json().lineId);
  assert.equal(second.json().duplicate, true);

  const n = db.prepare(`SELECT COUNT(*) AS n FROM session_lines WHERE session_id=?`)
    .get(id) as { n: number };
  assert.equal(n.n, 1);
});

test('the same part number in a different box is a separate line', async () => {
  const id = await openSession('INCOMING');
  await line(id, { pid: KNOWN_PID, boxSerial: '1124241658336425', qty: 18, raw: 'x' });
  await line(id, { pid: KNOWN_PID, boxSerial: '1124241658336426', qty: 12, raw: 'x' });

  const n = db.prepare(`SELECT COUNT(*) AS n FROM session_lines WHERE session_id=?`)
    .get(id) as { n: number };
  assert.equal(n.n, 2);
});

test('approving a proposed item fills in the cartons already scanned', async () => {
  const id = await openSession('INCOMING');
  await line(id, { pid: NEW_PID, boxSerial: 'BOX-1', qty: 6, raw: 'x' });
  await line(id, { pid: NEW_PID, boxSerial: 'BOX-2', qty: 6, raw: 'x' });

  const proposed = await app.inject({
    method: 'POST', url: '/api/v1/proposed-items', headers: auth,
    payload: {
      pid: NEW_PID, description: 'FLOW SWITCH', baseUnits: 'NO',
      batchwise: true, sessionId: id,
    },
  });
  assert.equal(proposed.statusCode, 200, proposed.body);

  // Approval only dispatches when a connector is attached, and there is none in
  // a unit test, so stand in for one -- the point here is what happens to the
  // lines afterwards, which is where the real flow was broken.
  const realDispatch = hub.dispatch;
  (hub as any).dispatch = () => true;
  let approve;
  try {
    approve = await app.inject({
      method: 'POST', url: `/api/v1/proposed-items/${NEW_PID}/approve`, headers: auth,
      payload: {},
    });
  } finally {
    (hub as any).dispatch = realDispatch;
  }

  assert.equal(approve.statusCode, 200, approve.body);
  const rows = db.prepare(`SELECT * FROM session_lines WHERE pid=?`).all(NEW_PID) as any[];
  assert.equal(rows.length, 2);
  for (const r of rows) {
    assert.equal(r.stock_item_name, `${NEW_PID} FLOW SWITCH`);
    assert.equal(r.unit, 'NO');
    assert.equal(r.flags.includes('UNRESOLVED_PID'), false);
  }

  // And the next carton off the same pallet resolves without prompting again.
  const id2 = await openSession('INCOMING');
  await line(id2, { pid: NEW_PID, boxSerial: 'BOX-3', qty: 6, raw: 'x' });
  const fresh = db.prepare(`SELECT * FROM session_lines WHERE session_id=?`).get(id2) as any;
  assert.equal(fresh.stock_item_name, `${NEW_PID} FLOW SWITCH`);
  assert.equal(fresh.flags, '');
});

test('outgoing still refuses a part number Tally does not have', async () => {
  const id = await openSession('OUTGOING');
  const r = await line(id, { pid: NEW_PID, boxSerial: 'BOX-9', qty: 1, raw: 'x' });
  assert.equal(r.statusCode, 400);
  assert.equal(r.json().error, 'unresolved_pid');
});
