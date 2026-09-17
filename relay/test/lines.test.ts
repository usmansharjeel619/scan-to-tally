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

const { app, db, hub, applyJobResult } = await import('../src/server.ts');
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
  // stock_items included: a test that creates an item would otherwise leave it
  // resolvable for the next one, which quietly turns "new product" tests into
  // "already known" ones.
  for (const t of ['session_lines', 'sessions', 'proposed_items', 'pid_bindings',
                   'stock_items', 'received_boxes', 'devices']) {
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

test('a new product goes straight to Tally, with no approval step', async () => {
  // The dock must never wait for someone at a desk. A carton in an operator's
  // hands is evidence the product exists.
  const id = await openSession('INCOMING');
  await line(id, { pid: NEW_PID, boxSerial: 'BOX-1', qty: 6, raw: 'x' });
  await line(id, { pid: NEW_PID, boxSerial: 'BOX-2', qty: 6, raw: 'x' });

  const sent: any[] = [];
  const realDispatch = hub.dispatch;
  (hub as any).dispatch = (_c: string, jobId: string, job: any) => {
    sent.push({ jobId, job });
    return true;
  };
  let proposed;
  try {
    proposed = await app.inject({
      method: 'POST', url: '/api/v1/proposed-items', headers: auth,
      payload: { pid: NEW_PID, description: 'FLOW SWITCH', baseUnits: 'NO', batchwise: true },
    });
  } finally {
    (hub as any).dispatch = realDispatch;
  }

  assert.equal(proposed.statusCode, 200, proposed.body);
  assert.equal(proposed.json().state, 'CREATING');
  assert.equal(sent.length, 1);
  assert.equal(sent[0].jobId, `mk-${NEW_PID}`);
  assert.equal(sent[0].job.kind, 'CREATE_STOCK_ITEM');
  assert.equal(sent[0].job.name, `${NEW_PID} FLOW SWITCH`);
});

test('once Tally confirms it, the cartons already scanned are filled in', async () => {
  const id = await openSession('INCOMING');
  await line(id, { pid: NEW_PID, boxSerial: 'BOX-1', qty: 6, raw: 'x' });
  await line(id, { pid: NEW_PID, boxSerial: 'BOX-2', qty: 6, raw: 'x' });

  const realDispatch = hub.dispatch;
  (hub as any).dispatch = () => true;
  try {
    await app.inject({
      method: 'POST', url: '/api/v1/proposed-items', headers: auth,
      payload: { pid: NEW_PID, description: 'FLOW SWITCH', baseUnits: 'NO', batchwise: true },
    });
  } finally {
    (hub as any).dispatch = realDispatch;
  }

  applyJobResult({
    sessionId: NEW_PID, jobId: `mk-${NEW_PID}`, ok: true,
    tallyVoucherId: `${NEW_PID} FLOW SWITCH`,
  });

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

test('a creation Tally refuses is recorded, not silently forgotten', async () => {
  const id = await openSession('INCOMING');
  await line(id, { pid: NEW_PID, boxSerial: 'BOX-1', qty: 6, raw: 'x' });

  const realDispatch = hub.dispatch;
  (hub as any).dispatch = () => true;
  try {
    await app.inject({
      method: 'POST', url: '/api/v1/proposed-items', headers: auth,
      payload: { pid: NEW_PID, description: 'FLOW SWITCH', baseUnits: 'NO' },
    });
  } finally {
    (hub as any).dispatch = realDispatch;
  }

  applyJobResult({
    sessionId: NEW_PID, jobId: `mk-${NEW_PID}`, ok: false,
    errorClass: 'BUSINESS', errorCode: 'ITEM_CREATE_FAILED',
    errorMessage: 'Unit NO does not exist',
  });

  const p = db.prepare(`SELECT * FROM proposed_items WHERE pid=?`).get(NEW_PID) as any;
  assert.equal(p.state, 'FAILED');
  assert.match(p.error, /Unit NO does not exist/);

  // The carton stays counted and unresolved, so submit still blocks on it.
  const r = db.prepare(`SELECT * FROM session_lines WHERE pid=?`).get(NEW_PID) as any;
  assert.equal(r.qty, 6);
  assert.equal(r.stock_item_name, '');
});

test('a receipt that never reached Tally can be discarded', async () => {
  // This failed silently for a while: the phone sends DELETE with a JSON
  // content type and no body, and the default parser called that malformed.
  const id = await openSession('INCOMING');
  await line(id, { pid: KNOWN_PID, boxSerial: 'BOX-1', qty: 18, raw: 'x' });

  const r = await app.inject({
    method: 'DELETE', url: `/api/v1/sessions/${id}`,
    headers: { ...auth, 'content-type': 'application/json' },
  });
  assert.equal(r.statusCode, 200, r.body);

  assert.equal(db.prepare(`SELECT COUNT(*) n FROM sessions WHERE id=?`).get(id).n, 0);
  assert.equal(db.prepare(`SELECT COUNT(*) n FROM session_lines WHERE session_id=?`).get(id).n, 0);
});

test('a receipt that reached Tally cannot be discarded', async () => {
  const id = await openSession('INCOMING');
  await line(id, { pid: KNOWN_PID, boxSerial: 'BOX-1', qty: 18, raw: 'x' });
  db.prepare(`UPDATE sessions SET state='POSTED', tally_voucher_id='RN-1' WHERE id=?`).run(id);

  const r = await app.inject({
    method: 'DELETE', url: `/api/v1/sessions/${id}`,
    headers: { ...auth, 'content-type': 'application/json' },
  });
  assert.equal(r.statusCode, 409);
  assert.equal(db.prepare(`SELECT COUNT(*) n FROM sessions WHERE id=?`).get(id).n, 1);
});

test('a stock take with nothing counted is refused, not called a match', async () => {
  // It used to answer "Count matches the book exactly", which is a false
  // statement about stock and a reassuring one, which is worse.
  const id = await openSession('STOCKCHECK');
  const r = await app.inject({
    method: 'POST', url: `/api/v1/sessions/${id}/submit`, headers: auth, payload: {},
  });
  assert.equal(r.statusCode, 400, r.body);
  assert.equal(r.json().error, 'nothing_counted');
  assert.equal(db.prepare(`SELECT state FROM sessions WHERE id=?`).get(id).state, 'DRAFT');
});

test('a stock take of only unknown products is refused, not called a match', async () => {
  // The case that actually happened: one counted box, product not in Tally,
  // reported back as "Count matches the book exactly".
  const id = await openSession('STOCKCHECK');
  await line(id, { pid: NEW_PID, boxSerial: 'BOX-1', qty: 1, raw: 'x' });

  const r = await app.inject({
    method: 'POST', url: `/api/v1/sessions/${id}/submit`, headers: auth, payload: {},
  });
  assert.equal(r.statusCode, 400, r.body);
  assert.equal(r.json().error, 'nothing_comparable');
  assert.equal(db.prepare(`SELECT state FROM sessions WHERE id=?`).get(id).state, 'DRAFT');
});

test('outgoing still refuses a part number Tally does not have', async () => {
  const id = await openSession('OUTGOING');
  const r = await line(id, { pid: NEW_PID, boxSerial: 'BOX-9', qty: 1, raw: 'x' });
  assert.equal(r.statusCode, 400);
  assert.equal(r.json().error, 'unresolved_pid');
});

test('a second despatch cannot exceed what the order still has outstanding', async () => {
  // Six went out against an order for four: the box ceiling held, the order
  // ceiling did not, because Tally's delivered figure is a sync behind and
  // nothing counted what this relay had itself just sent.
  const { validateOutgoingQty } = await import('../src/validation.ts');

  db.prepare(`INSERT INTO sales_orders (voucher_number, party_name, order_date, synced_at)
              VALUES ('SO-1','A Customer','2026-01-01', ?)`).run(nowIso());
  db.prepare(`INSERT INTO sales_order_lines
                (voucher_number, stock_item_name, ordered_qty, delivered_qty, unit)
              VALUES ('SO-1', ?, 4, 0, 'NO')`).run(KNOWN_ITEM);
  db.prepare(`INSERT INTO batch_balances
                (stock_item_name, batch_name, godown_name, closing_qty, unit, synced_at)
              VALUES (?, 'BOX-1', ?, 8, 'NO', ?)`).run(KNOWN_ITEM, GODOWN, nowIso());

  // First despatch: the whole order, already submitted.
  const first = 'sess-out-1';
  db.prepare(`INSERT INTO sessions (id, kind, device_id, operator, company, godown, party,
                                    sales_order, state, narration, created_at, submitted_at)
              VALUES (?, 'OUTGOING','dock-1','Tester','New Test Company', ?, '', 'SO-1',
                      'POSTED','', ?, ?)`)
    .run(first, GODOWN, nowIso(), new Date(Date.now() + 1000).toISOString());
  db.prepare(`INSERT INTO session_lines (session_id, pid, box_serial, qty, unit,
                                         stock_item_name, description, raw_payload,
                                         symbology, flags, scanned_at)
              VALUES (?, ?, 'BOX-1', 4, 'NO', ?, '', '', '', '', ?)`)
    .run(first, KNOWN_PID, KNOWN_ITEM, nowIso());

  // Second despatch, before Tally has reported the first.
  const v = validateOutgoingQty(db, {
    sessionId: 'sess-out-2', salesOrder: 'SO-1', godown: GODOWN,
    pid: KNOWN_PID, boxSerial: 'BOX-1', stockItemName: KNOWN_ITEM, qty: 2,
  });

  assert.equal(v.orderPending, 0, 'the order has nothing left outstanding');
  assert.equal(v.ok, false, 'it must be refused, not merely flagged');
  assert.match(String(v.error), /nothing left outstanding/);

  // The box itself still has room, so this is purely the order ceiling.
  assert.equal(v.available, 8);
});

test('one order can be filled from several boxes, and stops at the order total', async () => {
  // The real case: an order for 6, a box holding 2 and a box holding 10.
  // Send 2 from the small one, then 4 from the big one. The box ceiling is per
  // box; the order ceiling has to accumulate ACROSS boxes or the second scan
  // would happily send another 6.
  const { validateOutgoingQty } = await import('../src/validation.ts');

  db.prepare(`INSERT INTO sales_orders (voucher_number, party_name, order_date, synced_at)
              VALUES ('SO-9','A Customer','2026-01-01', ?)`).run(nowIso());
  db.prepare(`INSERT INTO sales_order_lines
                (voucher_number, stock_item_name, ordered_qty, delivered_qty, unit)
              VALUES ('SO-9', ?, 6, 0, 'NO')`).run(KNOWN_ITEM);

  const ins = db.prepare(`INSERT INTO batch_balances
      (stock_item_name, batch_name, godown_name, closing_qty, unit, synced_at)
      VALUES (?,?,?,?, 'NO', ?)`);
  ins.run(KNOWN_ITEM, 'SMALL-BOX', GODOWN, 2, nowIso());
  ins.run(KNOWN_ITEM, 'BIG-BOX', GODOWN, 10, nowIso());

  const sid = await openSession('OUTGOING');
  db.prepare(`UPDATE sessions SET sales_order='SO-9' WHERE id=?`).run(sid);

  const check = (box: string, qty: number) => validateOutgoingQty(db, {
    sessionId: sid, salesOrder: 'SO-9', godown: GODOWN,
    pid: KNOWN_PID, boxSerial: box, stockItemName: KNOWN_ITEM, qty,
  });

  // The small box, all of it.
  let v = check('SMALL-BOX', 2);
  assert.equal(v.ok, true, v.error ?? '');
  assert.equal(v.available, 2, 'the small box holds 2');
  assert.equal(v.orderPending, 6, 'nothing sent yet');

  // Its own ceiling still applies: it does not hold 3.
  assert.equal(check('SMALL-BOX', 3).ok, false, 'a box cannot give up more than it holds');

  await line(sid, { pid: KNOWN_PID, boxSerial: 'SMALL-BOX', qty: 2, raw: 'x' });

  // Now the big box. It holds 10, but the order only wants 4 more.
  v = check('BIG-BOX', 4);
  assert.equal(v.ok, true, v.error ?? '');
  assert.equal(v.available, 10, 'the big box is untouched');
  assert.equal(v.orderPending, 4, 'the order must count the 2 already scanned from the other box');

  // One more than the order has left, from a box with plenty in it.
  v = check('BIG-BOX', 5);
  assert.equal(v.ok, false, 'the order ceiling must bind even when the box has room');
  assert.match(String(v.error), /outstanding/);

  await line(sid, { pid: KNOWN_PID, boxSerial: 'BIG-BOX', qty: 4, raw: 'x' });

  // Order complete: nothing further may go out on it, from any box.
  v = check('BIG-BOX', 1);
  assert.equal(v.ok, false, 'a filled order must take nothing more');
  assert.equal(v.orderPending, 0);
});

test('two boxes of one product post as ONE voucher line with two batches', async () => {
  // Splitting a part number across entries is accepted by Tally and quietly
  // ruins its stock reports, so the job must nest the boxes under one line.
  const { buildJob } = await import('../src/server.ts');

  const sid = await openSession('INCOMING');
  await line(sid, { pid: KNOWN_PID, boxSerial: 'BOX-A', qty: 2, raw: 'x' });
  await line(sid, { pid: KNOWN_PID, boxSerial: 'BOX-B', qty: 4, raw: 'x' });

  const job = buildJob(sid) as any;
  assert.equal(job.lines.length, 1, 'one line for the part number');
  assert.equal(job.lines[0].stockItemName, KNOWN_ITEM);
  assert.equal(job.lines[0].boxes.length, 2, 'both boxes beneath it');
  assert.deepEqual(
    job.lines[0].boxes.map((b: any) => [b.boxSerial, b.qty]).sort(),
    [['BOX-A', 2], ['BOX-B', 4]],
  );
});

test('an item deleted in Tally stops resolving here', async () => {
  // It used to live on for ever: masters were upserted and never cleared, so a
  // part number kept resolving to a product Tally no longer had, and the
  // voucher built from it could only be refused.
  const { applySync, resolvePid } = await import('../src/db.ts');

  applySync(db, {
    items: [
      { name: KNOWN_ITEM, baseUnits: 'NO', hasBatches: true },
      { name: '4190-0001 DOOMED PRODUCT', baseUnits: 'NO', hasBatches: true },
    ],
    godowns: [GODOWN],
    balances: [],
  } as any);
  assert.ok(resolvePid(db, '4190-0001'), 'resolves while Tally has it');

  // Tally now reports only the survivor.
  const removed = applySync(db, {
    items: [{ name: KNOWN_ITEM, baseUnits: 'NO', hasBatches: true }],
    godowns: [GODOWN],
    balances: [],
  } as any);

  assert.equal(resolvePid(db, '4190-0001'), null, 'must stop resolving once deleted');
  assert.ok(resolvePid(db, KNOWN_PID), 'the survivor is untouched');
  assert.equal(removed?.items, 1, 'the removal is reported, not silent');
});

test('a binding to a deleted item goes with it', async () => {
  const { applySync, resolvePid } = await import('../src/db.ts');

  applySync(db, {
    items: [{ name: '4190-0002 ALSO DOOMED', baseUnits: 'NO', hasBatches: true }],
    godowns: [GODOWN], balances: [],
  } as any);
  db.prepare(`INSERT INTO pid_bindings (pid, stock_item_name, description, source, bound_by, bound_at)
              VALUES ('4190-0002','4190-0002 ALSO DOOMED','','AUTO_CREATED','test', ?)`).run(nowIso());
  assert.ok(resolvePid(db, '4190-0002'));

  // A binding outlives its item unless it is cleaned up, and then it resolves
  // a part number straight to something Tally will refuse.
  const removed = applySync(db, { items: [], godowns: [GODOWN], balances: [] } as any);

  assert.equal(resolvePid(db, '4190-0002'), null);
  assert.equal(removed?.bindings, 1);
});

test('a sync that carries no item list at all leaves the cache alone', async () => {
  // Absent is not the same as empty: a message that says nothing about items
  // must not be read as "Tally has none".
  const { applySync, resolvePid } = await import('../src/db.ts');

  applySync(db, {
    items: [{ name: KNOWN_ITEM, baseUnits: 'NO', hasBatches: true }],
    godowns: [GODOWN], balances: [],
  } as any);

  applySync(db, { balances: [] } as any);
  assert.ok(resolvePid(db, KNOWN_PID), 'items must survive a message that omits them');
});

test('the order ceiling holds even when Tally reports nothing delivered', async () => {
  // An older connector on the Tally machine does not report how much of an
  // order has gone out, so delivered_qty stays 0 for ever. A rule that stops
  // stock leaving must not weaken because a Windows box is running last week's
  // binary, so this relay's own record of what it posted is used instead
  // whenever it is the larger of the two.
  const { validateOutgoingQty } = await import('../src/validation.ts');

  db.prepare(`INSERT INTO sales_orders (voucher_number, party_name, order_date, synced_at)
              VALUES ('SO-OLD','A Customer','2026-01-01', ?)`).run(nowIso());
  db.prepare(`INSERT INTO sales_order_lines
                (voucher_number, stock_item_name, ordered_qty, delivered_qty, unit)
              VALUES ('SO-OLD', ?, 4, 0, 'NO')`).run(KNOWN_ITEM);
  db.prepare(`INSERT INTO batch_balances
                (stock_item_name, batch_name, godown_name, closing_qty, unit, synced_at)
              VALUES (?, 'BOX-1', ?, 50, 'NO', ?)`).run(KNOWN_ITEM, GODOWN, nowIso());

  // A despatch posted long ago -- well before the order's last sync, which is
  // what the previous version of this rule used to decide whether to count it.
  const old = 'sess-old-1';
  db.prepare(`INSERT INTO sessions (id, kind, device_id, operator, company, godown, party,
                                    sales_order, state, narration, created_at, submitted_at)
              VALUES (?, 'OUTGOING','dock-1','Tester','New Test Company', ?, '', 'SO-OLD',
                      'POSTED','', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')`)
    .run(old, GODOWN);
  db.prepare(`INSERT INTO session_lines (session_id, pid, box_serial, qty, unit,
                                         stock_item_name, description, raw_payload,
                                         symbology, flags, scanned_at)
              VALUES (?, ?, 'BOX-1', 4, 'NO', ?, '', '', '', '', ?)`)
    .run(old, KNOWN_PID, KNOWN_ITEM, nowIso());

  const v = validateOutgoingQty(db, {
    sessionId: 'sess-new', salesOrder: 'SO-OLD', godown: GODOWN,
    pid: KNOWN_PID, boxSerial: 'BOX-1', stockItemName: KNOWN_ITEM, qty: 1,
  });

  assert.equal(v.orderPending, 0, 'the order is already filled by what we posted');
  assert.equal(v.ok, false, 'and nothing more may go out on it');
  assert.equal(v.available, 50, 'the box is nowhere near the limit here');
});

test('what Tally reports delivered is not double counted against our own record', async () => {
  // Both sources describe the SAME despatch. Adding them would make an order
  // for 4 look 8 over-delivered and refuse a legitimate second pick.
  const { validateOutgoingQty } = await import('../src/validation.ts');

  db.prepare(`INSERT INTO sales_orders (voucher_number, party_name, order_date, synced_at)
              VALUES ('SO-BOTH','A Customer','2026-01-01', ?)`).run(nowIso());
  db.prepare(`INSERT INTO sales_order_lines
                (voucher_number, stock_item_name, ordered_qty, delivered_qty, unit)
              VALUES ('SO-BOTH', ?, 10, 4, 'NO')`).run(KNOWN_ITEM);
  db.prepare(`INSERT INTO batch_balances
                (stock_item_name, batch_name, godown_name, closing_qty, unit, synced_at)
              VALUES (?, 'BOX-1', ?, 50, 'NO', ?)`).run(KNOWN_ITEM, GODOWN, nowIso());

  const posted = 'sess-both-1';
  db.prepare(`INSERT INTO sessions (id, kind, device_id, operator, company, godown, party,
                                    sales_order, state, narration, created_at, submitted_at)
              VALUES (?, 'OUTGOING','dock-1','Tester','New Test Company', ?, '', 'SO-BOTH',
                      'POSTED','', ?, ?)`).run(posted, GODOWN, nowIso(), nowIso());
  db.prepare(`INSERT INTO session_lines (session_id, pid, box_serial, qty, unit,
                                         stock_item_name, description, raw_payload,
                                         symbology, flags, scanned_at)
              VALUES (?, ?, 'BOX-1', 4, 'NO', ?, '', '', '', '', ?)`)
    .run(posted, KNOWN_PID, KNOWN_ITEM, nowIso());

  const v = validateOutgoingQty(db, {
    sessionId: 'sess-both-2', salesOrder: 'SO-BOTH', godown: GODOWN,
    pid: KNOWN_PID, boxSerial: 'BOX-1', stockItemName: KNOWN_ITEM, qty: 6,
  });

  assert.equal(v.orderPending, 6, 'ten ordered, four gone once -- not twice');
  assert.equal(v.ok, true, v.error ?? '');
});
