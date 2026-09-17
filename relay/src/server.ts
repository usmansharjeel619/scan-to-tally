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
import { createReadStream, existsSync } from 'node:fs';
import { join } from 'node:path';
import { openDb, applySync, audit, nowIso, resolvePid, type DB } from './db.ts';
import { decideIncomingScan, decideOutgoingScan, validateOutgoingQty } from './validation.ts';
import { decideStockCheckScan, computeVariance, varianceToLines, type CountScope } from './stockcheck.ts';
import { ConnectorHub, type JobResult } from './hub.ts';

const PORT = Number(process.env.STT_PORT ?? 8787);
const HOST = process.env.STT_HOST ?? '0.0.0.0';
const DB_PATH = process.env.STT_DB ?? './data/relay.db';
const CONNECTOR_SECRET = process.env.STT_CONNECTOR_SECRET ?? '';
/** Random path segment guarding the installer download. Empty disables it. */
const DOWNLOAD_PATH = process.env.STT_DOWNLOAD_PATH ?? '';
const DIST_DIR = process.env.STT_DIST_DIR ?? '/opt/scan-to-tally/dist';

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

  if (kind === 'STOCKCHECK') return; // a count moves nothing in or out

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
function buildJob(sessionId: string, scope: CountScope = 'PARTIAL'): Record<string, unknown> | null {
  const s = db.prepare(`SELECT * FROM sessions WHERE id = ?`).get(sessionId) as any;
  if (!s) return null;

  // A stock check posts the VARIANCE, never the raw count: writing back
  // figures Tally already holds is noise in the stock report for no gain.
  if (s.kind === 'STOCKCHECK') {
    const report = computeVariance(db, sessionId, s.godown, scope);
    const lines = varianceToLines(report);
    if (!lines.length) return null;
    return {
      sessionId: s.id, kind: 'STOCKCHECK', company: s.company, godown: s.godown,
      scope, date: new Date().toISOString(), operator: s.operator, deviceId: s.device_id,
      narration: s.narration || `Stock check (${scope.toLowerCase()}) | ${s.godown} | ${report.counted} boxes counted`,
      lines: lines.map((l) => ({
        stockItemName: l.stockItemName, unit: l.unit,
        boxes: l.boxes.map((x) => ({ boxSerial: x.boxSerial, qty: x.qty })),
      })),
    };
  }

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
  // What products ARE, independent of Tally. Small enough to ship whole, and
  // the device needs it offline: the prompt fires at the scan, which may be in
  // a warehouse with no signal.
  const catalogue = db.prepare(
    `SELECT pid, description, alternates FROM product_catalogue`,
  ).all();

  const receivedBoxes = db.prepare(
    `SELECT pid, box_serial, received_at FROM received_boxes
      WHERE received_at > datetime('now','-12 months')`,
  ).all();

  return {
    syncedAt: nowIso(),
    godown: d.godown,
    company: d.company,
    items, bindings, balances, receivedBoxes, catalogue,
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

  // A session exists from the moment a screen opens, so abandoned drafts
  // accumulate. Clear the ones nobody scanned into, or the review and queue
  // views fill with work that was never work.
  db.prepare(`
    DELETE FROM sessions
     WHERE state = 'DRAFT'
       AND created_at < datetime('now','-5 minutes')
       AND NOT EXISTS (SELECT 1 FROM session_lines l WHERE l.session_id = sessions.id)`).run();

  const id = String(b.sessionId ?? randomUUID());
  const kind = b.kind === 'OUTGOING' ? 'OUTGOING'
    : b.kind === 'STOCKCHECK' ? 'STOCKCHECK' : 'INCOMING';

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

  let decision;
  if (s.kind === 'OUTGOING') {
    decision = decideOutgoingScan(db, {
      sessionId: id, salesOrder: s.sales_order, godown: s.godown,
      raw, symbology, manual: !!b.manual,
    });
  } else if (s.kind === 'STOCKCHECK') {
    decision = decideStockCheckScan(db, {
      sessionId: id, godown: s.godown, raw, symbology,
      manual: !!b.manual, blind: b.blind,
    });
  } else {
    decision = decideIncomingScan(db, {
      sessionId: id, raw, symbology,
      manual: !!b.manual, overrideDuplicate: !!b.overrideDuplicate,
    });
  }

  // Incoming commits the line immediately -- quantity comes from the label, so
  // one scan is one complete line. Outgoing waits for the typed quantity.
  if ((s.kind === 'INCOMING' || s.kind === 'STOCKCHECK')
      && (decision.outcome === 'ACCEPT' || decision.outcome === 'FLAGGED')) {
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
  const body = (req.body ?? {}) as any;
  // FULL scope writes zeroes onto uncounted stock, so it is never the default
  // and the device must ask for it explicitly after showing the warning.
  const scope: CountScope = body.scope === 'FULL' ? 'FULL' : 'PARTIAL';

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

  const job = buildJob(id, scope);
  if (!job) {
    if (s.kind === 'STOCKCHECK') {
      // Nothing to write is a good outcome for a count, not an error.
      db.prepare(`UPDATE sessions SET state='POSTED', completed_at=? WHERE id=?`)
        .run(nowIso(), id);
      audit(db, `device:${d.id}`, 'STOCK_CHECK_NO_VARIANCE', id);
      return { sessionId: id, state: 'POSTED', noVariance: true,
        message: 'Count matches the book exactly. Nothing to adjust.' };
    }
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

/**
 * The variance report, shown before a count is adopted.
 *
 * Always returns the NOT_COUNTED rows so the operator can see what they missed,
 * under either scope. `willZeroUncounted` is the warning the UI must act on:
 * under FULL scope those rows become zeroes in Tally.
 */
app.get('/api/v1/sessions/:id/variance', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const { id } = req.params as { id: string };
  const scope = ((req.query as any)?.scope === 'FULL' ? 'FULL' : 'PARTIAL') as CountScope;

  const s = db.prepare(`SELECT * FROM sessions WHERE id=?`).get(id) as any;
  if (!s) return reply.code(404).send({ error: 'no_such_session' });
  if (s.kind !== 'STOCKCHECK') {
    return reply.code(400).send({ error: 'not_a_stock_check', kind: s.kind });
  }
  return computeVariance(db, id, s.godown, scope);
});

// --- new products -----------------------------------------------------------

/**
 * An operator describing a product Tally has never seen.
 *
 * Captured at the END of a session, never mid-scan: the dock does not stop for
 * data entry. The operator has the carton in hand and the description printed
 * on the label, which makes them the right person to ask -- but at the right
 * moment, not while they are holding a box.
 */
app.post('/api/v1/proposed-items', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const b = (req.body ?? {}) as any;

  const pid = String(b.pid ?? '').trim();
  const description = String(b.description ?? '').trim();
  if (!pid || !description) {
    return reply.code(400).send({ error: 'pid and description are required' });
  }

  // Already known? Then this is not a new product and nothing should be created.
  const existing = resolvePid(db, pid);
  if (existing) {
    return reply.code(409).send({
      error: 'already_known', stockItemName: existing.stockItemName,
      message: `${pid} already resolves to "${existing.stockItemName}".`,
    });
  }

  // The live catalogue names items "<PID> <DESCRIPTION>", and that convention
  // is exactly what makes a scanned PID resolvable later. Compose it the same
  // way rather than letting the name drift.
  const name = `${pid} ${description}`;

  db.prepare(`
    INSERT INTO proposed_items
      (pid, name, description, base_units, batchwise, track_mfg, state,
       proposed_by, proposed_at, session_id, raw_payload)
    VALUES (?,?,?,?,?,?,'PENDING',?,?,?,?)
    ON CONFLICT(pid) DO UPDATE SET
      name=excluded.name, description=excluded.description,
      base_units=excluded.base_units, batchwise=excluded.batchwise,
      track_mfg=excluded.track_mfg, state='PENDING',
      proposed_by=excluded.proposed_by, proposed_at=excluded.proposed_at,
      error=''`)
    .run(pid, name, description, String(b.baseUnits ?? 'NO'),
      b.batchwise === false ? 0 : 1, b.trackMfgDate === false ? 0 : 1,
      b.proposedBy ?? d.operator, nowIso(), String(b.sessionId ?? ''),
      String(b.raw ?? ''));

  audit(db, `device:${d.id}`, 'ITEM_PROPOSED', pid, name);
  return { ok: true, pid, name, state: 'PENDING' };
});

app.get('/api/v1/proposed-items', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  return db.prepare(
    `SELECT * FROM proposed_items WHERE state = 'PENDING' ORDER BY proposed_at`,
  ).all();
});

/**
 * The supervisor's tap. This is the only thing that causes a write to Tally's
 * item master, and it is irreversible in practice -- Tally will not delete a
 * stock item once it has transactions.
 */
app.post('/api/v1/proposed-items/:pid/approve', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const { pid } = req.params as { pid: string };
  const b = (req.body ?? {}) as any;

  const p = db.prepare(`SELECT * FROM proposed_items WHERE pid = ?`).get(pid) as any;
  if (!p) return reply.code(404).send({ error: 'no_such_proposal' });
  if (p.state === 'APPROVED') return { pid, state: 'APPROVED', alreadyDone: true };

  // Last chance to notice it is not actually new.
  const existing = resolvePid(db, pid);
  if (existing) {
    db.prepare(`UPDATE proposed_items SET state='REJECTED', decided_by=?, decided_at=?,
                error='already resolves' WHERE pid=?`)
      .run(d.operator || d.id, nowIso(), pid);
    return reply.code(409).send({
      error: 'already_known', stockItemName: existing.stockItemName,
    });
  }

  // The supervisor may correct anything before it becomes permanent.
  const name = String(b.name ?? p.name).trim();
  const units = String(b.baseUnits ?? (p.base_units || 'NO')).trim();
  const batchwise = b.batchwise === undefined ? !!p.batchwise : !!b.batchwise;

  const sent = hub.dispatch(d.company, `mk-${pid}`, {
    kind: 'CREATE_STOCK_ITEM',
    pid, name, baseUnits: units, batchwise,
    trackMfgDate: batchwise && !!p.track_mfg,
    company: d.company,
  });

  if (!sent) {
    return reply.code(503).send({
      error: 'connector_offline',
      message: 'The Tally connector is not connected, so nothing can be created yet. ' +
        'The proposal is kept; approve again when it is back.',
    });
  }

  db.prepare(`UPDATE proposed_items SET state='APPROVED', decided_by=?, decided_at=?,
              name=?, base_units=?, batchwise=? WHERE pid=?`)
    .run(d.operator || d.id, nowIso(), name, units, batchwise ? 1 : 0, pid);
  audit(db, d.operator || `device:${d.id}`, 'ITEM_APPROVED', pid, name);

  return { pid, name, state: 'APPROVED', dispatched: true };
});

app.post('/api/v1/proposed-items/:pid/reject', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const { pid } = req.params as { pid: string };
  db.prepare(`UPDATE proposed_items SET state='REJECTED', decided_by=?, decided_at=? WHERE pid=?`)
    .run(d.operator || d.id, nowIso(), pid);
  audit(db, d.operator || `device:${d.id}`, 'ITEM_REJECTED', pid);
  return { pid, state: 'REJECTED' };
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

  const proposed = db.prepare(
    `SELECT pid, name, description, base_units, batchwise, proposed_by, proposed_at
       FROM proposed_items WHERE state='PENDING' ORDER BY proposed_at`).all();

  return { failed, unresolvedPids: unresolved, flagged, proposedItems: proposed };
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

/**
 * Phase 0: run a read-only Tally query through the connector.
 *
 * This exists to reconcile the XML templates in the connector against a real
 * Tally that nobody can reach inbound. The connector refuses anything that is
 * not an Export, so this endpoint cannot modify data.
 */
app.post('/api/v1/diag', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const b = (req.body ?? {}) as any;

  const xml = String(b.xml ?? '');
  if (!xml.trim()) return reply.code(400).send({ error: 'xml is required' });

  const res = await hub.diag(d.company, xml, String(b.label ?? ''));
  audit(db, `device:${d.id}`, 'DIAG_QUERY', String(b.label ?? ''),
    res.ok ? `${res.bytes} bytes in ${res.millis}ms` : (res.error ?? 'failed'));
  return res;
});

/** Connector state, for checking whether the Tally machine has come online. */
app.get('/api/v1/connectors', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  return { connectors: hub.all() };
});

/**
 * Serves the connector installer to the Tally machine.
 *
 * Getting a binary onto a warehouse PC on a different network is a recurring
 * problem: it has outbound internet and little else. The path carries a random
 * segment from the server's env rather than a login, because whoever is
 * standing at that machine has no account here -- it is a capability URL, and
 * it is rotated by changing STT_DOWNLOAD_PATH.
 */
if (DOWNLOAD_PATH) {
  app.get(`/dl/${DOWNLOAD_PATH}/:file`, async (req, reply) => {
    const { file } = req.params as { file: string };
    // No path traversal: the name must be one plain filename.
    if (!/^[A-Za-z0-9._-]+$/.test(file) || file.includes('..')) {
      return reply.code(400).send({ error: 'bad filename' });
    }
    const full = join(DIST_DIR, file);
    if (!existsSync(full)) return reply.code(404).send({ error: 'not found' });

    audit(db, 'download', 'CONNECTOR_DOWNLOAD', file, String(req.ip));
    const type = file.endsWith('.exe') ? 'application/octet-stream'
      : file.endsWith('.apk') ? 'application/vnd.android.package-archive'
      : file.endsWith('.html') ? 'text/html; charset=utf-8'
      : file.endsWith('.ps1') || file.endsWith('.txt') ? 'text/plain; charset=utf-8'
      : file.endsWith('.json') ? 'application/json'
      : 'application/octet-stream';
    reply.header('Content-Type', type);
    // Text should open in the browser, not land in Downloads -- someone is
    // reading these off a phone while typing them into the app.
    if (!file.endsWith('.txt') && !file.endsWith('.html')) {
      reply.header('Content-Disposition', `attachment; filename="${file}"`);
    }
    // Without this Cloudflare caches the binary for hours and hands out a
    // stale connector.exe long after a fix has shipped -- which it did.
    reply.header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0');
    reply.header('Pragma', 'no-cache');
    return reply.send(createReadStream(full));
  });
  app.log.info(`installer download path enabled at /dl/${DOWNLOAD_PATH}/`);
}

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
