/**
 * Relay HTTP + WebSocket server.
 *
 * Three audiences:
 *   /api/v1/*        the warehouse device
 *   /api/v1/proposed-items   products scanned that Tally does not have yet
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
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
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

/**
 * Base unit given to a stock item the app creates.
 *
 * Tally refuses to create an item against a unit the company has not defined
 * ("Unit 'NO' does not exist!"), and the symbol differs between companies --
 * one live set of books uses "NO", another "Nos". Hardcoding it meant every
 * receipt of a new product failed the moment the relay was pointed at
 * different books, with the failure looking like a hang.
 */
const DEFAULT_UNIT = process.env.STT_DEFAULT_UNIT ?? 'NO';

const db: DB = openDb(DB_PATH);

const app = Fastify({
  logger: { level: process.env.LOG_LEVEL ?? 'info' },
  bodyLimit: 8 * 1024 * 1024,
});
await app.register(websocket);

/**
 * An empty body is not a malformed one.
 *
 * A DELETE carries no body, but plenty of HTTP clients still put a JSON
 * content type on one, and the default parser answers 400 to that. Every
 * discarded receipt came back refused for exactly this reason, silently,
 * because the phone had already deleted its own copy and never looked.
 *
 * Every handler here already reads `req.body ?? {}`, so an empty object is
 * what they expect anyway.
 */
app.addContentTypeParser(
  'application/json',
  { parseAs: 'string' },
  (_req, body, done) => {
    const text = String(body ?? '').trim();
    if (text === '') return done(null, {});
    try {
      done(null, JSON.parse(text));
    } catch (err) {
      (err as any).statusCode = 400;
      done(err as Error, undefined);
    }
  },
);

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
    const removed = applySync(db, {
      items: p?.items, godowns: p?.godowns, balances: p?.balances,
      orders: p?.orders?.map((o: any) => ({
        voucherNumber: o.voucherNumber, partyName: o.partyName,
        date: typeof o.date === 'string' ? o.date.slice(0, 10) : '',
        lines: o.lines ?? [],
      })),
    });
    app.log.info(
      { items: p?.items?.length, balances: p?.balances?.length, orders: p?.orders?.length },
      'master data synced',
    );
    if (removed) {
      // Master data going away is either a real deletion in Tally or a sign
      // that something is wrong. Both are worth seeing rather than inferring
      // later from a part number that stopped resolving.
      app.log.warn(removed, 'master data removed because Tally no longer has it');
      audit(db, 'connector', 'MASTERS_REMOVED', '',
        `${removed.items} item(s), ${removed.bindings} binding(s)`);
    }
  },
);

// Routing falls back to the only connector when a device names a company that
// is not attached. That convenience once hid a handset registered against the
// wrong company for a whole day, so it is no longer silent.
hub.onCompanyMismatch((wanted, got) => {
  app.log.warn({ deviceCompany: wanted, connectorCompany: got },
    'device company does not match the attached connector; routing fell back');
});


/**
 * Records what the connector did with a session.
 *
 * `duplicate` is a SUCCESS: it means the session had already reached Tally and
 * the connector returned the stored result without posting again. The safety
 * net working is not an error.
 */
function applyJobResult(r: JobResult): void {
  // Item creation reports back on the same channel, keyed by part number
  // rather than session -- it is not warehouse work and has no session. Its
  // result used to fall straight through this function and be discarded, so a
  // creation that Tally refused looked exactly like one that worked.
  if (r.jobId?.startsWith('mk-')) {
    applyItemCreationResult(r);
    return;
  }

  const session = db.prepare(`SELECT id, kind FROM sessions WHERE id = ?`)
    .get(r.sessionId) as { id: string; kind: string } | undefined;
  if (!session) {
    // A result nobody is waiting for is still a result. Dropping it silently
    // is how a post that Tally refused disappears without trace -- and the
    // whole point of this queue is that one never does.
    app.log.warn(
      { sessionId: r.sessionId, ok: r.ok, code: r.errorCode, message: r.errorMessage },
      'job result for an unknown session',
    );
    audit(db, 'connector', r.ok ? 'ORPHAN_POSTED' : 'ORPHAN_FAILED', r.sessionId,
      r.ok ? (r.tallyVoucherId ?? '') : `${r.errorCode}: ${r.errorMessage}`);
    return;
  }

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

/**
 * Creates a scanned-but-unknown product in Tally, there and then.
 *
 * There is deliberately no approval step. A carton on the dock is evidence the
 * product exists; making the operator wait for someone at a desk to agree
 * stops the unloading, which is the one thing this app must never do. The
 * operator has already supplied the description and unit at the scan, and the
 * name follows the live convention, so there is nothing for a second person to
 * add.
 *
 * What a human IS still needed for is a creation Tally refuses -- that lands in
 * the review queue with Tally's own words.
 */
function dispatchItemCreation(pid: string, company: string): boolean {
  const p = db.prepare(`SELECT * FROM proposed_items WHERE pid = ?`).get(pid) as any;
  if (!p || p.state === 'CREATED') return false;

  // A connector installed without master-creation rights will refuse this, and
  // the refusal is worth saying plainly and once rather than every time a
  // carton is scanned.
  const conn = hub.stateFor(company);
  if (conn && !conn.canCreateItems) {
    db.prepare(`UPDATE proposed_items SET state='FAILED', error=? WHERE pid=?`)
      .run('This Tally connector is not allowed to create stock items. ' +
           'Re-run the installer with -AllowNewProducts.', pid);
    return false;
  }

  const sent = hub.dispatch(company, `mk-${pid}`, {
    kind: 'CREATE_STOCK_ITEM',
    pid,
    name: p.name,
    baseUnits: p.base_units || DEFAULT_UNIT,
    batchwise: !!p.batchwise,
    trackMfgDate: !!p.batchwise && !!p.track_mfg,
    company,
  });

  db.prepare(`UPDATE proposed_items SET state = ? WHERE pid = ?`)
    .run(sent ? 'CREATING' : 'PENDING', pid);
  return sent;
}

/** Everything that could not be sent because Tally was unreachable. */
function dispatchPendingItems(company: string): number {
  const pending = db.prepare(
    `SELECT pid FROM proposed_items WHERE state IN ('PENDING','CREATING')`,
  ).all() as Array<{ pid: string }>;

  let sent = 0;
  for (const { pid } of pending) if (dispatchItemCreation(pid, company)) sent++;
  return sent;
}

function applyItemCreationResult(r: JobResult): void {
  const pid = r.sessionId;
  const p = db.prepare(`SELECT * FROM proposed_items WHERE pid = ?`).get(pid) as any;
  if (!p) return;

  if (!r.ok) {
    db.prepare(`UPDATE proposed_items SET state='FAILED', error=?, decided_at=? WHERE pid=?`)
      .run(`${r.errorCode ?? ''}: ${r.errorMessage ?? 'unknown error'}`.trim(), nowIso(), pid);
    audit(db, 'connector', 'ITEM_CREATE_FAILED', pid, r.errorMessage ?? '');
    return;
  }

  // Tally may have named it something slightly different; its answer wins.
  const name = r.tallyVoucherId || p.name;

  const tx = db.transaction(() => {
    db.prepare(`UPDATE proposed_items SET state='CREATED', name=?, error='', decided_at=?
                WHERE pid=?`).run(name, nowIso(), pid);

    // Resolvable immediately, rather than after the next master sync: the very
    // next carton off the same pallet must not prompt again.
    db.prepare(`INSERT INTO stock_items (name, part_no, base_units, has_batches, synced_at)
                VALUES (?,'',?,?,?)
                ON CONFLICT(name) DO UPDATE SET base_units=excluded.base_units,
                  has_batches=excluded.has_batches, synced_at=excluded.synced_at`)
      .run(name, p.base_units || DEFAULT_UNIT, p.batchwise ? 1 : 0, nowIso());

    db.prepare(`INSERT INTO pid_bindings (pid, stock_item_name, description, source, bound_by, bound_at)
                VALUES (?,?,?,'AUTO_CREATED','scanner',?)
                ON CONFLICT(pid) DO UPDATE SET stock_item_name=excluded.stock_item_name,
                  description=excluded.description, source='AUTO_CREATED',
                  bound_at=excluded.bound_at`)
      .run(pid, name, String(p.description || ''), nowIso());

    // The cartons scanned before Tally answered are the whole point: the
    // operator kept unloading. Without this they stay unresolved for ever and
    // the voucher is refused.
    db.prepare(`
      UPDATE session_lines SET stock_item_name=?, unit=?,
        description=CASE WHEN description='' THEN ? ELSE description END,
        flags=TRIM(REPLACE(','||flags||',', ',UNRESOLVED_PID,', ','), ',')
      WHERE pid=? AND stock_item_name=''
        AND session_id IN (SELECT id FROM sessions WHERE state IN ('DRAFT','QUEUED','FAILED'))`)
      .run(name, p.base_units || DEFAULT_UNIT, String(p.description || ''), pid);
  });
  tx();

  audit(db, 'connector', 'ITEM_CREATED', pid, name);
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
/**
 * Keeps a handset's company in step with the connector it is actually talking
 * to.
 *
 * A device is registered with a company name, and jobs are routed by matching
 * it to a connector. The pairing survived a wrong name only because routing
 * falls back to "there is exactly one connector, use that" -- so a handset
 * registered against one company was quietly driving another for a whole day.
 *
 * Attach a second connector and that fallback disappears: the name suddenly
 * decides everything, and the wrong one sends work to the wrong warehouse or
 * nowhere at all. So the name is corrected while it is still unambiguous.
 *
 * Only ever with ONE connector attached. With several the company is the only
 * thing telling them apart, and adopting one over the others would be guessing
 * at exactly the moment guessing is worst.
 *
 * This is about ROUTING, and it is not the safeguard against counting into the
 * wrong company. That lives in three places which do not depend on it: the
 * handset pins its company and refuses a sync from any other, the session
 * route above refuses a count raised against a different one, and the
 * connector refuses to post a job whose company is not the one it serves.
 */
function reconcileDeviceCompanies(company: string): void {
  if (!company) return;
  if (hub.all().length !== 1) return;

  const stale = db.prepare(
    `SELECT id, company FROM devices WHERE company != ?`,
  ).all(company) as Array<{ id: string; company: string }>;
  if (!stale.length) return;

  db.prepare(`UPDATE devices SET company = ? WHERE company != ?`).run(company, company);

  for (const d of stale) {
    app.log.warn(
      { device: d.id, was: d.company, now: company },
      'device company corrected to the attached connector',
    );
    audit(db, 'relay', 'DEVICE_COMPANY_CORRECTED', d.id, `${d.company} -> ${company}`);
  }
}

function resendOutstanding(company: string): void {
  // The connector has just said hello, so this is the moment the truth about
  // which company is open is freshest.
  reconcileDeviceCompanies(company);

  // Products scanned while Tally was unreachable go first: a voucher that
  // names an item which does not exist yet is refused, so the master has to
  // land before the vouchers that depend on it.
  const items = dispatchPendingItems(company);
  if (items) app.log.info({ count: items }, 'redelivered pending item creations');

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

  // What became of every product scanned that Tally did not have.
  //
  // The device binds a new product locally the moment the operator describes
  // it, so the rest of the pallet scans without prompting again. That binding
  // is a guess until Tally confirms it, and when the creation failed the phone
  // had no way to find out -- it kept a product that does not exist, stopped
  // prompting for it, and every receipt built on it was refused with nothing
  // on screen to say why.
  const proposals = db.prepare(
    `SELECT pid, name, description, base_units AS baseUnits, state, error
       FROM proposed_items`,
  ).all();

  const receivedBoxes = db.prepare(
    `SELECT pid, box_serial, received_at FROM received_boxes
      WHERE received_at > datetime('now','-12 months')`,
  ).all();

  return {
    syncedAt: nowIso(),
    godown: d.godown,
    company: d.company,
    items, bindings, balances, receivedBoxes, catalogue, proposals,
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

  // The handset says which company it counted for, and it has to be the one
  // this device is registered against.
  //
  // The phone already refuses to scan when it can see a mismatch -- but it can
  // only see one while it has signal, and a loading dock is exactly where it
  // does not. A phone that scanned a pallet offline, against the company it
  // last knew about, drains its outbox later into whatever this relay now
  // thinks it is. That is the one path where the operator gets no warning at
  // all, so it is refused here, where the session is actually created.
  const claimed = String(b.company ?? '').trim();
  if (claimed && d.company && claimed.toLowerCase() !== d.company.toLowerCase()) {
    audit(db, `device:${d.id}`, 'SESSION_REFUSED_WRONG_COMPANY', id,
      `${claimed} -> ${d.company}`);
    return reply.code(409).send({
      error: 'wrong_company',
      message: `This was counted for "${claimed}", but this phone is now ` +
        `registered to "${d.company}". Nothing has been saved.`,
    });
  }

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
      manual: !!b.manual,
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

  if (s.kind === 'OUTGOING') {
    // Outgoing has to despatch something Tally already knows about: you cannot
    // ship from a stock item that does not exist.
    if (!resolved) return reply.code(400).send({ error: 'unresolved_pid', pid });
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

  // Incoming and stock check.
  if (!(qty > 0)) return reply.code(400).send({ error: 'qty_rejected', message: 'Enter a quantity.' });

  // Correcting a short-shipped box: label says 18, the box holds 16.
  if (lineId) {
    db.prepare(`UPDATE session_lines SET qty=?, flags=? WHERE id=? AND session_id=?`)
      .run(qty, appendFlag(lineId, 'QTY_EDITED'), lineId, id);
    return { lineId, qty };
  }

  // Otherwise this is the device mirroring a scan it has already decided and
  // already stored locally. The phone owns the decision -- it must, because it
  // has to beep before the network has been anywhere near this -- so the relay
  // records the line rather than second-guessing it.
  //
  // It is NOT a rejection when the part number resolves to nothing: an unknown
  // product is the start of the new-item flow, and the line is kept with the
  // item name blank so submit blocks on it until Tally has created it.
  // Refusing here instead would have left the relay with no record of the scan
  // at all, and a voucher with nothing on it.
  const dup = db.prepare(
    `SELECT id, qty FROM session_lines WHERE session_id=? AND pid=? AND box_serial=?`,
  ).get(id, pid, boxSerial) as { id: number; qty: number } | undefined;

  // The same box arriving twice is the device retrying, not a second carton:
  // (part number, box number) is unique by definition, and the phone already
  // refused a genuine re-scan with a beep. So this is idempotent.
  if (dup) return { lineId: dup.id, qty: dup.qty, duplicate: true };

  const flags: string[] = [];
  if (!resolved) flags.push('UNRESOLVED_PID');
  if (b.manual) flags.push('MANUAL');

  const info = db.prepare(`
    INSERT INTO session_lines (session_id, pid, box_serial, qty, unit, stock_item_name,
                               description, mfg_date, raw_payload, symbology, flags, scanned_at)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`)
    .run(id, pid, boxSerial, qty, resolved?.unit ?? '', resolved?.stockItemName ?? '',
      resolved?.description ?? '', b.mfgDate ?? null, String(b.raw ?? ''),
      String(b.symbology ?? ''), flags.join(','), nowIso());

  return { lineId: Number(info.lastInsertRowid), qty, flags };
});

function appendFlag(lineId: number, flag: string): string {
  const row = db.prepare(`SELECT flags FROM session_lines WHERE id = ?`)
    .get(lineId) as { flags: string } | undefined;
  const set = new Set(String(row?.flags ?? '').split(',').filter(Boolean));
  set.add(flag);
  return [...set].join(',');
}

/**
 * Throws away a receipt that never reached Tally.
 *
 * Refused once it has posted: that row is the record of stock that moved, and
 * the voucher number on it is the only way back to what Tally was told.
 */
app.delete('/api/v1/sessions/:id', async (req, reply) => {
  const d = requireDevice(req, reply);
  if (!d) return;
  const { id } = req.params as { id: string };

  const s = db.prepare(`SELECT state FROM sessions WHERE id=?`).get(id) as
    { state: string } | undefined;
  if (!s) return { ok: true, alreadyGone: true };
  if (s.state === 'POSTED' || s.state === 'POSTING') {
    return reply.code(409).send({ error: 'already_posted', state: s.state });
  }

  const tx = db.transaction(() => {
    db.prepare(`DELETE FROM session_lines WHERE session_id=?`).run(id);
    db.prepare(`DELETE FROM sessions WHERE id=?`).run(id);
  });
  tx();

  audit(db, `device:${d.id}`, 'SESSION_DISCARDED', id, s.state);
  return { ok: true };
});

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

  const counted = db.prepare(
    `SELECT COUNT(*) AS n FROM session_lines WHERE session_id=?`,
  ).get(id) as { n: number };

  const job = buildJob(id, scope);
  if (!job) {
    // A count with nothing on it has not matched anything -- it has not
    // happened. Reporting "matches the book exactly" for an empty stock take
    // is a false statement about stock, and the worst kind: reassuring.
    if (s.kind === 'STOCKCHECK' && counted.n === 0) {
      return reply.code(400).send({
        error: 'nothing_counted',
        message: 'Nothing was counted, so there is nothing to compare.',
      });
    }
    // Counted, but nothing that could be compared: every line is a product
    // Tally does not have yet. "Matches the book" is exactly as false here as
    // it is for an empty count, and this is the case that actually happened.
    if (s.kind === 'STOCKCHECK' && unresolved.n > 0) {
      return reply.code(400).send({
        error: 'nothing_comparable',
        message: `${unresolved.n} counted ${unresolved.n === 1 ? 'box is' : 'boxes are'} ` +
          'waiting for Tally to create the product, so the count cannot be compared yet.',
      });
    }
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
        ? `All ${unresolved.n} line(s) are waiting for Tally to create the product.`
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
    .run(pid, name, description, String(b.baseUnits ?? DEFAULT_UNIT),
      b.batchwise === false ? 0 : 1, b.trackMfgDate === false ? 0 : 1,
      b.proposedBy ?? d.operator, nowIso(), String(b.sessionId ?? ''),
      String(b.raw ?? ''));

  audit(db, `device:${d.id}`, 'ITEM_PROPOSED', pid, name);

  // Straight to Tally. No queue, no approval, no waiting.
  const sent = dispatchItemCreation(pid, d.company);
  return {
    ok: true, pid, name,
    state: sent ? 'CREATING' : 'PENDING',
    message: sent
      ? `Creating "${name}" in Tally.`
      : 'Saved. It will be created as soon as Tally is reachable.',
  };
});

// --- failed receipts -------------------------------------------------------

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
  /**
   * The name the file arrives under, which is not the name it is served at.
   *
   * The link has to stay put: it is written down, pasted into chats and typed
   * off a screen onto a warehouse PC. But an APK called app.apk tells nobody
   * which version it is once it is sitting in Downloads next to three others.
   */
  const downloadName = (file: string): string => {
    const version = (() => {
      try {
        return readFileSync(join(DIST_DIR, 'app.version'), 'utf8').trim();
      } catch {
        return '';
      }
    })();
    if (!version) return file;
    const dot = file.lastIndexOf('.');
    if (dot <= 0) return file;
    return `${file.slice(0, dot)}-${version}${file.slice(dot)}`;
  };

  app.get(`/dl/${DOWNLOAD_PATH}/:file`, async (req, reply) => {
    const { file } = req.params as { file: string };
    // No path traversal: the name must be one plain filename.
    if (!/^[A-Za-z0-9._-]+$/.test(file) || file.includes('..')) {
      return reply.code(400).send({ error: 'bad filename' });
    }
    let full = join(DIST_DIR, file);

    // A versioned name resolves to the file it names.
    //
    // Content-Disposition already asks for the download to be saved under its
    // version, and plenty of Android downloaders ignore it and take the name
    // straight off the URL -- so every release landed as another "app.apk" and
    // nobody could tell four of them apart in a Downloads folder.
    //
    // So app-1.1.4.apk serves app.apk, and the version is in the part of the
    // request no client can disregard. The bare name keeps working, because it
    // is the link that gets written down and typed off a screen.
    if (!existsSync(full)) {
        const versioned = /^(.+?)-[0-9]+(?:\.[0-9]+)*(\.[A-Za-z0-9]+)$/.exec(file);
        if (versioned) {
            const bare = join(DIST_DIR, `${versioned[1]}${versioned[2]}`);
            if (existsSync(bare)) full = bare;
        }
    }
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
      // The URL never changes -- it is written down, pasted into chats and
      // typed off a screen -- but what lands in Downloads carries the version,
      // so two APKs on a phone can be told apart without installing them.
      reply.header('Content-Disposition', `attachment; filename="${downloadName(file)}"`);
    }
    // Cloudflare must not hand out a stale binary -- it once served a
    // connector.exe for hours after a fix had shipped.
    //
    // But no-store is too blunt for a phone: Android's download manager treats
    // it as "this may not be written to storage" and can sit on a completed
    // download without ever finalising it. no-cache with a validator gets the
    // same freshness -- every request is revalidated -- without telling the
    // client it may not keep what it just downloaded.
    const stamp = statSync(full);
    // "private" is the part that matters: it bars SHARED caches, which is
    // Cloudflare, while saying nothing about whether the client may keep the
    // file. Plain no-cache was rewritten upstream into max-age=14400 -- the
    // four-hour stale binary all over again -- and no-store stopped the phone
    // finalising the download. This says exactly what is meant: nobody in the
    // middle may hold it, the client may.
    reply.header('Cache-Control', 'private, no-cache, must-revalidate, max-age=0');
    reply.header('ETag', `"${stamp.size}-${Math.floor(stamp.mtimeMs)}"`);
    reply.header('Last-Modified', stamp.mtime.toUTCString());

    const size = stamp.size;

    // Ranges, actually honoured.
    //
    // Advertising Accept-Ranges and then ignoring Range is a lie a browser
    // shrugs off and Android's download manager does not: it asks for a range
    // while resuming, is handed the whole file with a 200, and sits at
    // "44.06/44.06" without ever finishing. Either support it or do not claim
    // to, and supporting it is what makes an interrupted download over a
    // warehouse connection resume instead of starting again.
    reply.header('Accept-Ranges', 'bytes');

    const rangeHeader = String(req.headers.range ?? '');
    const m = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader.trim());

    if (m) {
      const hasStart = m[1] !== '';
      const hasEnd = m[2] !== '';

      // "bytes=-500" means the LAST 500 bytes, not the first.
      let start = hasStart ? Number(m[1]) : size - Number(m[2]);
      let end = hasStart ? (hasEnd ? Number(m[2]) : size - 1) : size - 1;

      if (!Number.isFinite(start) || !Number.isFinite(end)) {
        return reply.code(416).header('Content-Range', `bytes */${size}`).send();
      }
      start = Math.max(0, start);
      end = Math.min(end, size - 1);
      if (start > end || start >= size) {
        return reply.code(416).header('Content-Range', `bytes */${size}`).send();
      }

      reply.code(206);
      reply.header('Content-Range', `bytes ${start}-${end}/${size}`);
      reply.header('Content-Length', end - start + 1);
      return reply.send(createReadStream(full, { start, end }));
    }

    // The size, declared.
    //
    // Streaming without it sends the body chunked with no length, and the
    // download manager shows a 44 MB APK as 0 KB and can sit there without
    // finishing.
    reply.header('Content-Length', size);
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

export { app, db, hub, buildJob, applyJobResult, reconcileDeviceCompanies };
