/**
 * Relay HTTP + WebSocket server.
 *
 * Three audiences:
 *   /api/v1/*        the warehouse device
 *   /api/v1/review/* the supervisor
 *   /connector/ws    the on-prem connector
 *
 * The relay owns IDENTITY -- PID resolution, duplicate rules, order ceilings --
 * because it holds the masters. The connector owns the LIVE check against Tally
 * immediately before posting. Neither trusts the device: a scan line arriving
 * over HTTP is untrusted input, and the quantity on it becomes stock.
 */

import Fastify from 'fastify';
import websocket from '@fastify/websocket';
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { openDb, applySync, audit, nowIso, resolvePid, type DB } from './db.ts';
import { decideIncomingScan, decideOutgoingScan, validateOutgoingQty } from './validation.ts';
import { ConnectorHub, type JobResult } from './hub.ts';

const PORT = Number(process.env.STT_PORT ?? 8787);
const HOST = process.env.STT_HOST ?? '0.0.0.0';
const DB_PATH = process.env.STT_DB ?? './data/relay.db';
const CONNECTOR_SECRET = process.env.STT_CONNECTOR_SECRET ?? '';

const db: DB = openDb(DB_PATH);

const app = Fastify({
  logger: { level: process.env.LOG_LEVEL ?? 'info' },
  bodyLimit: 8 * 1024 * 1024,
});
await app.register(websocket);

function sha256(s: string): string {
  return createHash('sha256').update(s).digest('hex');
}

/** Constant-time compare so a token cannot be guessed a byte at a time. */
function secretsMatch(a: string, b: string): boolean {
  const ab = Buffer.from(sha256(a));
  const bb = Buffer.from(sha256(b));
  return ab.length === bb.length && timingSafeEqual(ab, bb);
}

// --- connector hub ----------------------------------------------------------

const hub = new ConnectorHub(
  db,
  (r: JobResult) => applyJobResult(r),
  (company, payload) => {
    const p = payload as any;
    if (p?.resend) {
      resendOutstanding(company);
      return;
    }
    applySync(db, {
      items: p?.items, godowns: p?.godowns, balances: p?.balances,
      orders: p?.orders?.map((o: any) => ({
        voucherNumber: o.voucherNumber, partyName: o.partyName,
        date: typeof o.date === 'string' ? o.date.slice(0, 10) : '',
        lines: o.lines ?? [],
      })),
    });
    app.log.info({ items: p?.items?.length, balances: p?.balances?.length }, 'master data synced');
  },
);

/**
 * Records what the connector did with a session.
 *
 * `duplicate` is a SUCCESS: it means the session had already reached Tally and
 * the connector returned the stored result without posting again. The safety
 * net working is not an error.
 */
function applyJobResult(r: JobResult): void {
  const session = db.prepare(`SELECT id, kind FROM sessions WHERE id = ?`)
    .get(r.sessionId) as { id: string; kind: string } | undefined;
  if (!session) return;

  if (r.ok) {
    db.prepare(`
      UPDATE sessions SET state='POSTED', tally_voucher_id=?, duplicate=?,
             completed_at=?, attempts=?, error_class='', error_code='', error_message=''
       WHERE id=?`)
      .run(r.tallyVoucherId ?? '', r.duplicate ? 1 : 0, nowIso(), r.attempts ?? 0, r.sessionId);

    recordBoxHistory(r.sessionId, session.kind);
    audit(db, 'connector', 'POSTED', r.sessionId,
      `voucher ${r.tallyVoucherId}${r.duplicate ? ' (duplicate, not reposted)' : ''}`);
  } else {
    // A failed post NEVER vanishes. It goes to the review queue with Tally's
    // own words shown verbatim.
    db.prepare(`
      UPDATE sessions SET state='FAILED', error_class=?, error_code=?, error_message=?,
             attempts=?, completed_at=? WHERE id=?`)
      .run(r.errorClass ?? '', r.errorCode ?? '', r.errorMessage ?? '',
        r.attempts ?? 0, nowIso(), r.sessionId);
    audit(db, 'connector', 'FAILED', r.sessionId, `${r.errorCode}: ${r.errorMessage}`);
  }
}

/** After a successful post, remember every box so future scans can detect it. */
function recordBoxHistory(sessionId: string, kind: string): void {
  const lines = db.prepare(
    `SELECT pid, box_serial, qty FROM session_lines WHERE session_id = ?`,
  ).all(sessionId) as Array<{ pid: string; box_serial: string; qty: number }>;

  const tx = db.transaction(() => {
    for (const l of lines) {
      if (kind === 'INCOMING') {
        db.prepare(`
          INSERT INTO received_boxes (pid, box_serial, session_id, qty, received_at)
          VALUES (?,?,?,?,?) ON CONFLICT(pid, box_serial) DO NOTHING`)
          .run(l.pid, l.box_serial, sessionId, l.qty, nowIso());
      } else {
        db.prepare(`
          INSERT INTO despatched_boxes (pid, box_serial, session_id, qty, despatched_at)
          VALUES (?,?,?,?,?) ON CONFLICT(pid, box_serial, session_id) DO NOTHING`)
          .run(l.pid, l.box_serial, sessionId, l.qty, nowIso());
      }
    }
  });
  tx();
}

/** Redelivers anything the connector may have missed while disconnected. */
function resendOutstanding(company: string): void {
  const rows = db.prepare(
    `SELECT id FROM sessions WHERE state IN ('QUEUED','POSTING') ORDER BY created_at`,
  ).all() as Array<{ id: string }>;
  for (const row of rows) {
    const job = buildJob(row.id);
    if (job && hub.dispatch(company, row.id, job)) {
      db.prepare(`UPDATE sessions SET state='POSTING' WHERE id=?`).run(row.id);
    }
  }
  if (rows.length) app.log.info({ count: rows.length }, 'redelivered outstanding sessions');
}

// --- job assembly -----------------------------------------------------------

/**
 * Aggregates a session's scans into ONE line per part number with N boxes
 * beneath it. The connector asserts this again before posting, because
 * splitting an item across entries is accepted by Tally and quietly ruins its
 * stock reports.
 */
function buildJob(sessionId: string): Record<string, unknown> | null {
  const s = db.prepare(`SELECT * FROM sessions WHERE id = ?`).get(sessionId) as any;
  if (!s) return null;

  const lines = db.prepare(
    `SELECT * FROM session_lines WHERE session_id = ? ORDER BY id`,
  ).all(sessionId) as any[];
  if (!lines.length) return null;

  const byItem = new Map<string, any>();
  for (const l of lines) {
    if (!l.stock_item_name) continue; // unresolved lines cannot be posted
    let entry = byItem.get(l.stock_item_name);
    if (!entry) {
      entry = {
        stockItemName: l.stock_item_name,
        unit: l.unit,
        description: l.description ?? '',
        boxes: [],
      };
      byItem.set(l.stock_item_name, entry);
    }
    entry.boxes.push({
      boxSerial: l.box_serial,
      qty: l.qty,
      mfgDate: l.mfg_date ?? undefined,
      rawPayload: l.raw_payload,
      pid: l.pid,
      manual: String(l.flags ?? '').includes('MANUAL'),
    });
  }
  if (!byItem.size) return null;

  return {
    sessionId: s.id,
    kind: s.kind,
    company: s.company,
    godown: s.godown,
    party: s.party,
    salesOrder: s.sales_order,
    date: new Date().toISOString(),
    operator: s.operator,
    deviceId: s.device_id,
    narration: s.narration,
    lines: [...byItem.values()],
  };
}

// --- auth -------------------------------------------------------------------

interface Device { id: string; name: string; company: string; godown: string; operator: string }

function authDevice(req: any): Device | null {
  const header = String(req.headers.authorization ?? '');
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (!token) return null;
  const row = db.prepare(
    `SELECT id, name, company, godown, operator FROM devices WHERE token_hash = ?`,
  ).get(sha256(token)) as Device | undefined;
  if (row) {
    db.prepare(`UPDATE devices SET last_seen = ? WHERE id = ?`).run(nowIso(), row.id);
  }
  return row ?? null;
}

function requireDevice(req: any, reply: any): Device | null {
  const d = authDevice(req);
  if (!d) {
    reply.code(401).send({ error: 'unauthorized', message: 'Device token missing or invalid.' });
    return null;
  }
  return d;
}

// --- device API -------------------------------------------------------------

app.get('/health', async () => ({ ok: true, at: nowIso() }));

/** Connector + queue state for the device's status bar. */
app.get('/api/v1/status', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const pending = db.prepare(
    `SELECT COUNT(*) AS n FROM sessions WHERE state IN ('QUEUED','POSTING')`,
  ).get() as { n: number };
  const failed = db.prepare(
    `SELECT COUNT(*) AS n FROM sessions WHERE state='FAILED'`,
  ).get() as { n: number };
  return { connector: hub.status(d.company), pending: pending.n, failed: failed.n };
});

/** Everything the device caches so scanning works with no signal. */
app.get('/api/v1/sync', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;

  const items = db.prepare(
    `SELECT name, alias, part_no, base_units, has_batches FROM stock_items ORDER BY name`,
  ).all();
  const bindings = db.prepare(
    `SELECT pid, stock_item_name, description FROM pid_bindings`,
  ).all();
  const balances = db.prepare(
    `SELECT stock_item_name, batch_name, godown_name, closing_qty, unit, synced_at
       FROM batch_balances WHERE godown_name = ?`,
  ).all(d.godown);
  const orders = db.prepare(`SELECT voucher_number, party_name, order_date FROM sales_orders`).all() as any[];
  const orderLines = db.prepare(`SELECT * FROM sales_order_lines`).all() as any[];
  // The received-box index: what makes historical duplicate detection work
  // offline. A rolling window keeps it small enough to hold on a device.
  const receivedBoxes = db.prepare(
    `SELECT pid, box_serial, received_at FROM received_boxes
      WHERE received_at > datetime('now','-12 months')`,
  ).all();

  return {
    syncedAt: nowIso(),
    godown: d.godown,
    company: d.company,
    items, bindings, balances, receivedBoxes,
    orders: orders.map((o) => ({
      ...o,
      lines: orderLines.filter((l) => l.voucher_number === o.voucher_number),
    })),
  };
});

/** Opens a session. The id is minted BY THE DEVICE, so it survives offline. */
app.post('/api/v1/sessions', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const b = (req.body ?? {}) as any;

  const id = String(b.sessionId ?? randomUUID());
  const kind = b.kind === 'OUTGOING' ? 'OUTGOING' : 'INCOMING';

  const existing = db.prepare(`SELECT id, state FROM sessions WHERE id = ?`).get(id);
  if (existing) return { sessionId: id, resumed: true, ...(existing as object) };

  db.prepare(`
    INSERT INTO sessions (id, kind, device_id, operator, company, godown, party,
                          sales_order, state, narration, created_at)
    VALUES (?,?,?,?,?,?,?,?,?,?,?)`)
    .run(id, kind, d.id, b.operator ?? d.operator, d.company, b.godown ?? d.godown,
      b.party ?? '', b.salesOrder ?? '', 'DRAFT', b.narration ?? '', nowIso());

  audit(db, `device:${d.id}`, 'SESSION_OPENED', id, kind);
  return { sessionId: id, kind, state: 'DRAFT' };
});

/** One scan. Returns the decision AND the beep to make. */
app.post('/api/v1/sessions/:id/scan', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const { id } = req.params as { id: string };
  const b = (req.body ?? {}) as any;

  const s = db.prepare(`SELECT * FROM sessions WHERE id = ?`).get(id) as any;
  if (!s) return reply.code(404).send({ error: 'no_such_session' });
  if (s.state !== 'DRAFT') {
    return reply.code(409).send({ error: 'session_closed', state: s.state });
  }

  const raw = String(b.raw ?? '');
  const symbology = String(b.symbology ?? 'UNKNOWN');

  const decision = s.kind === 'OUTGOING'
    ? decideOutgoingScan(db, {
      sessionId: id, salesOrder: s.sales_order, godown: s.godown,
      raw, symbology, manual: !!b.manual,
    })
    : decideIncomingScan(db, {
      sessionId: id, raw, symbology,
      manual: !!b.manual, overrideDuplicate: !!b.overrideDuplicate,
    });

  // Incoming commits the line immediately -- quantity comes from the label, so
  // one scan is one complete line. Outgoing waits for the typed quantity.
  if (s.kind === 'INCOMING' && (decision.outcome === 'ACCEPT' || decision.outcome === 'FLAGGED')) {
    const box = decision.box!;
    const info = db.prepare(`
      INSERT INTO session_lines (session_id, pid, box_serial, qty, unit, stock_item_name,
                                 description, mfg_date, raw_payload, symbology, flags, scanned_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`)
      .run(id, box.pid, box.boxSerial, box.labelQty, box.unit, box.stockItemName,
        box.description, box.mfgDate, raw, symbology, decision.flags.join(','), nowIso());
    return { ...decision, lineId: Number(info.lastInsertRowid) };
  }

  return decision;
});

/** Commits or edits an outgoing line once the employee has typed a quantity. */
app.post('/api/v1/sessions/:id/lines', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const { id } = req.params as { id: string };
  const b = (req.body ?? {}) as any;

  const s = db.prepare(`SELECT * FROM sessions WHERE id = ?`).get(id) as any;
  if (!s) return reply.code(404).send({ error: 'no_such_session' });
  if (s.state !== 'DRAFT') return reply.code(409).send({ error: 'session_closed', state: s.state });

  const pid = String(b.pid ?? '');
  const boxSerial = String(b.boxSerial ?? '');
  const qty = Number(b.qty);
  const lineId = b.lineId ? Number(b.lineId) : undefined;

  const resolved = resolvePid(db, pid);
  if (!resolved) return reply.code(400).send({ error: 'unresolved_pid', pid });

  if (s.kind === 'OUTGOING') {
    const v = validateOutgoingQty(db, {
      sessionId: id, salesOrder: s.sales_order, godown: s.godown,
      pid, boxSerial, stockItemName: resolved.stockItemName, qty, excludeLineId: lineId,
    });
    // There must be no way to submit an invalid quantity.
    if (!v.ok) return reply.code(400).send({ error: 'qty_rejected', ...v });

    if (lineId) {
      db.prepare(`UPDATE session_lines SET qty=?, flags=? WHERE id=? AND session_id=?`)
        .run(qty, appendFlag(lineId, 'QTY_EDITED'), lineId, id);
      return { lineId, qty, ...v };
    }
    const info = db.prepare(`
      INSERT INTO session_lines (session_id, pid, box_serial, qty, unit, stock_item_name,
                                 description, mfg_date, raw_payload, symbology, flags, scanned_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`)
      .run(id, pid, boxSerial, qty, resolved.unit, resolved.stockItemName,
        resolved.description, b.mfgDate ?? null, String(b.raw ?? ''),
        String(b.symbology ?? ''), b.manual ? 'MANUAL' : '', nowIso());
    return { lineId: Number(info.lastInsertRowid), qty, ...v };
  }

  // Incoming: correcting a short-shipped box (label says 18, box holds 16).
  if (!(qty > 0)) return reply.code(400).send({ error: 'qty_rejected', message: 'Enter a quantity.' });
  if (lineId) {
    db.prepare(`UPDATE session_lines SET qty=?, flags=? WHERE id=? AND session_id=?`)
      .run(qty, appendFlag(lineId, 'QTY_EDITED'), lineId, id);
    return { lineId, qty };
  }
  return reply.code(400).send({ error: 'no_line', message: 'Scan the box first.' });
});

function appendFlag(lineId: number, flag: string): string {
  const row = db.prepare(`SELECT flags FROM session_lines WHERE id = ?`)
    .get(lineId) as { flags: string } | undefined;
  const set = new Set(String(row?.flags ?? '').split(',').filter(Boolean));
  set.add(flag);
  return [...set].join(',');
}

app.delete('/api/v1/sessions/:id/lines/:lineId', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const { id, lineId } = req.params as { id: string; lineId: string };
  db.prepare(`DELETE FROM session_lines WHERE id=? AND session_id=?`).run(Number(lineId), id);
  audit(db, `device:${d.id}`, 'LINE_REMOVED', id, lineId);
  return { ok: true };
});

/** The session as the device shows it: grouped by part number. */
app.get('/api/v1/sessions/:id', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const { id } = req.params as { id: string };
  const s = db.prepare(`SELECT * FROM sessions WHERE id=?`).get(id);
  if (!s) return reply.code(404).send({ error: 'no_such_session' });

  const lines = db.prepare(
    `SELECT * FROM session_lines WHERE session_id=? ORDER BY id`,
  ).all(id) as any[];

  // Operators think in pallets: "SSD SENSOR BASE, 3 boxes, 54 pcs".
  const groups = new Map<string, any>();
  for (const l of lines) {
    const key = l.stock_item_name || `?${l.pid}`;
    let g = groups.get(key);
    if (!g) {
      g = {
        stockItemName: l.stock_item_name, pid: l.pid,
        description: l.description, unit: l.unit,
        boxCount: 0, totalQty: 0, boxes: [] as any[],
      };
      groups.set(key, g);
    }
    g.boxCount += 1;
    g.totalQty += l.qty;
    g.boxes.push({
      lineId: l.id, boxSerial: l.box_serial, qty: l.qty,
      flags: String(l.flags ?? '').split(',').filter(Boolean),
    });
  }

  return { session: s, groups: [...groups.values()], lineCount: lines.length };
});

/** Closes the session and hands it to the connector. */
app.post('/api/v1/sessions/:id/submit', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const { id } = req.params as { id: string };

  const s = db.prepare(`SELECT * FROM sessions WHERE id=?`).get(id) as any;
  if (!s) return reply.code(404).send({ error: 'no_such_session' });

  // Submitting an already-submitted session is a retry, not an error -- the
  // device may not have seen our first answer.
  if (s.state !== 'DRAFT') {
    return { sessionId: id, state: s.state, tallyVoucherId: s.tally_voucher_id, resubmitted: true };
  }

  const unresolved = db.prepare(
    `SELECT COUNT(*) AS n FROM session_lines WHERE session_id=? AND stock_item_name=''`,
  ).get(id) as { n: number };

  const job = buildJob(id);
  if (!job) {
    return reply.code(400).send({
      error: 'nothing_to_post',
      message: unresolved.n > 0
        ? `All ${unresolved.n} line(s) need a supervisor to map the product first.`
        : 'This session has no lines.',
    });
  }

  db.prepare(`UPDATE sessions SET state='QUEUED', submitted_at=? WHERE id=?`).run(nowIso(), id);
  audit(db, `device:${d.id}`, 'SESSION_SUBMITTED', id,
    `${(job.lines as any[]).length} item(s)`);

  const sent = hub.dispatch(s.company, id, job);
  if (sent) db.prepare(`UPDATE sessions SET state='POSTING' WHERE id=?`).run(id);

  // Not being sent is fine and expected: the connector may be offline. The
  // session is durable and will be redelivered on reconnect. The operator's
  // day is never blocked by a sleeping laptop.
  return {
    sessionId: id,
    state: sent ? 'POSTING' : 'QUEUED',
    dispatched: sent,
    unresolvedLines: unresolved.n,
  };
});

// --- supervisor API ---------------------------------------------------------

/** Everything waiting on a human. A failed post must never vanish. */
app.get('/api/v1/review', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;

  const failed = db.prepare(
    `SELECT id, kind, created_at, error_class, error_code, error_message, attempts
       FROM sessions WHERE state='FAILED' ORDER BY completed_at DESC LIMIT 200`,
  ).all();

  // Unresolved PIDs, most frequent first: binding the commonest one clears the
  // most lines.
  const unresolved = db.prepare(`
    SELECT pid, COUNT(*) AS lines, SUM(qty) AS qty, MAX(scanned_at) AS last_seen,
           MIN(raw_payload) AS sample
      FROM session_lines WHERE stock_item_name = ''
     GROUP BY pid ORDER BY lines DESC LIMIT 200`).all();

  const flagged = db.prepare(`
    SELECT sl.id, sl.session_id, sl.pid, sl.box_serial, sl.qty, sl.flags, sl.scanned_at
      FROM session_lines sl WHERE sl.flags != '' ORDER BY sl.id DESC LIMIT 200`).all();

  return { failed, unresolvedPids: unresolved, flagged };
});

/**
 * Binds a PID to an existing Tally item.
 *
 * This is the common case and it creates nothing in Tally: the product is
 * usually already there, and it is the barcode mapping that was missing. The
 * binding is retroactively applied to every line waiting on it, so submitting
 * a stuck session needs no re-scan.
 */
app.post('/api/v1/bindings', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const b = (req.body ?? {}) as any;

  const pid = String(b.pid ?? '').trim();
  const stockItemName = String(b.stockItemName ?? '').trim();
  if (!pid || !stockItemName) {
    return reply.code(400).send({ error: 'pid and stockItemName are required' });
  }

  const item = db.prepare(
    `SELECT name, base_units, has_batches FROM stock_items WHERE name = ?`,
  ).get(stockItemName) as { base_units: string; has_batches: number } | undefined;
  if (!item) {
    return reply.code(400).send({
      error: 'unknown_stock_item',
      message: `"${stockItemName}" is not in the synced Tally item master.`,
    });
  }

  db.prepare(`
    INSERT INTO pid_bindings (pid, stock_item_name, description, source, bound_by, bound_at)
    VALUES (?,?,?,?,?,?)
    ON CONFLICT(pid) DO UPDATE SET
      stock_item_name=excluded.stock_item_name, description=excluded.description,
      source=excluded.source, bound_by=excluded.bound_by, bound_at=excluded.bound_at`)
    .run(pid, stockItemName, String(b.description ?? ''), 'SUPERVISOR',
      b.boundBy ?? d.operator, nowIso());

  const updated = db.prepare(`
    UPDATE session_lines
       SET stock_item_name=?, unit=?, description=?,
           flags=REPLACE(REPLACE(flags,'UNRESOLVED_PID,',''),'UNRESOLVED_PID','')
     WHERE pid=? AND stock_item_name=''`)
    .run(stockItemName, item.base_units, String(b.description ?? ''), pid);

  audit(db, d.operator || `device:${d.id}`, 'PID_BOUND', pid,
    `-> ${stockItemName} (${updated.changes} line(s) resolved)`);

  return {
    ok: true, pid, stockItemName,
    linesResolved: updated.changes,
    warning: item.has_batches ? undefined
      : `"${stockItemName}" is not batch-wise in Tally, so box numbers cannot be tracked on it.`,
  };
});

/** Retries a failed session after the underlying problem was fixed. */
app.post('/api/v1/sessions/:id/retry', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const { id } = req.params as { id: string };

  const s = db.prepare(`SELECT * FROM sessions WHERE id=?`).get(id) as any;
  if (!s) return reply.code(404).send({ error: 'no_such_session' });
  if (s.state !== 'FAILED') return reply.code(409).send({ error: 'not_failed', state: s.state });

  const job = buildJob(id);
  if (!job) return reply.code(400).send({ error: 'nothing_to_post' });

  db.prepare(`UPDATE sessions SET state='QUEUED', error_message='' WHERE id=?`).run(id);
  const sent = hub.dispatch(s.company, id, job);
  if (sent) db.prepare(`UPDATE sessions SET state='POSTING' WHERE id=?`).run(id);

  audit(db, d.operator || `device:${d.id}`, 'SESSION_RETRIED', id);
  return { sessionId: id, state: sent ? 'POSTING' : 'QUEUED' };
});

app.get('/api/v1/items', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const q = String((req.query as any)?.q ?? '').trim();
  if (!q) {
    return db.prepare(`SELECT name, part_no, base_units, has_batches FROM stock_items
                        ORDER BY name LIMIT 100`).all();
  }
  return db.prepare(`
    SELECT name, part_no, base_units, has_batches FROM stock_items
     WHERE name LIKE ? OR part_no LIKE ? OR alias LIKE ?
     ORDER BY name LIMIT 100`).all(`%${q}%`, `%${q}%`, `%${q}%`);
});

// --- connector socket -------------------------------------------------------

app.get('/connector/ws', { websocket: true }, (socket, req) => {
  const auth = String(req.headers.authorization ?? '');
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';

  if (!CONNECTOR_SECRET || !token || !secretsMatch(token, CONNECTOR_SECRET)) {
    app.log.warn({ ip: req.ip }, 'connector rejected: bad secret');
    socket.close(4401, 'unauthorized');
    return;
  }
  hub.register(socket as unknown as import('ws').WebSocket);
});

// --- boot -------------------------------------------------------------------

if (!CONNECTOR_SECRET) {
  app.log.error('STT_CONNECTOR_SECRET is not set; no connector will be able to attach.');
}

await app.listen({ port: PORT, host: HOST });
app.log.info(`relay listening on ${HOST}:${PORT}, db ${DB_PATH}`);

export { app, db, hub, buildJob, applyJobResult };
