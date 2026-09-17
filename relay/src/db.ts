/**
 * Relay storage.
 *
 * SQLite rather than Postgres, deliberately. The load is a handful of devices
 * and a few hundred sessions a day; WAL-mode SQLite absorbs that without
 * noticing, and it removes an entire operational component from a deployment
 * that has to be maintainable by whoever is on site. If this ever outgrows it,
 * the SQL here is portable enough to move.
 */

import Database from 'better-sqlite3';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

export type SessionKind = 'INCOMING' | 'OUTGOING';

export type SessionState =
  | 'DRAFT'      // open on the device
  | 'QUEUED'     // accepted by the relay, waiting for the connector
  | 'POSTING'    // handed to the connector
  | 'POSTED'     // in Tally
  | 'FAILED'     // business error; needs a person. NEVER disappears.
  | 'CANCELLED';

/**
 * Flags ride on a scan line instead of blocking the operator. Anything the
 * system cannot resolve is accepted, counted, and marked -- the dock never
 * stops for data entry.
 */
export type LineFlag =
  | 'UNRESOLVED_PID'      // no binding to a Tally stock item yet
  | 'AMBIGUOUS_PID'       // several Tally items share this PID; a human must pick
  | 'DUPLICATE_OVERRIDE'  // box was received before; operator deliberately accepted
  | 'MANUAL'              // typed, not scanned
  | 'QTY_EDITED'          // operator changed the quantity from the label
  | 'NO_BATCH_SUPPORT';   // resolved item is not batch-wise in Tally

const SCHEMA = `
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS devices (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  company     TEXT NOT NULL,
  godown      TEXT NOT NULL,
  token_hash  TEXT NOT NULL,
  operator    TEXT NOT NULL DEFAULT '',
  created_at  TEXT NOT NULL,
  last_seen   TEXT
);

-- Mirrors of Tally master data, pushed up by the connector and fanned out to
-- devices so scanning and validation keep working with no signal at the dock.
CREATE TABLE IF NOT EXISTS stock_items (
  name        TEXT PRIMARY KEY,
  alias       TEXT NOT NULL DEFAULT '',
  part_no     TEXT NOT NULL DEFAULT '',
  base_units  TEXT NOT NULL DEFAULT '',
  has_batches INTEGER NOT NULL DEFAULT 0,
  synced_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_items_partno ON stock_items(part_no);

-- The learned PID -> Tally stock item map. This is the heart of resolution:
-- the product is usually already IN Tally; it is the barcode mapping that is
-- missing. Creating the product binds it once and every device learns it.
CREATE TABLE IF NOT EXISTS pid_bindings (
  pid             TEXT PRIMARY KEY,
  stock_item_name TEXT NOT NULL,
  description     TEXT NOT NULL DEFAULT '',
  source          TEXT NOT NULL,          -- SEED | SUPERVISOR | AUTO_PARTNO
  bound_by        TEXT NOT NULL DEFAULT '',
  bound_at        TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS godowns (
  name      TEXT PRIMARY KEY,
  synced_at TEXT NOT NULL
);

-- The hard ceiling for outgoing quantity. NOT the printed box quantity:
-- a box that shipped 5 of 18 last week has 13 left.
CREATE TABLE IF NOT EXISTS batch_balances (
  stock_item_name TEXT NOT NULL,
  batch_name      TEXT NOT NULL,
  godown_name     TEXT NOT NULL,
  closing_qty     REAL NOT NULL,
  unit            TEXT NOT NULL DEFAULT '',
  synced_at       TEXT NOT NULL,
  PRIMARY KEY (stock_item_name, batch_name, godown_name)
);

CREATE TABLE IF NOT EXISTS sales_orders (
  voucher_number TEXT PRIMARY KEY,
  party_name     TEXT NOT NULL DEFAULT '',
  order_date     TEXT NOT NULL DEFAULT '',
  synced_at      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sales_order_lines (
  voucher_number  TEXT NOT NULL REFERENCES sales_orders(voucher_number) ON DELETE CASCADE,
  stock_item_name TEXT NOT NULL,
  ordered_qty     REAL NOT NULL DEFAULT 0,
  delivered_qty   REAL NOT NULL DEFAULT 0,
  unit            TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (voucher_number, stock_item_name)
);

-- Every box ever received, for historical duplicate detection. The key is
-- (pid, box_serial) -- never the serial alone, because Tally scopes a batch
-- under a stock item and the same box number under a different product is a
-- different box.
CREATE TABLE IF NOT EXISTS received_boxes (
  pid         TEXT NOT NULL,
  box_serial  TEXT NOT NULL,
  session_id  TEXT NOT NULL,
  qty         REAL NOT NULL,
  received_at TEXT NOT NULL,
  PRIMARY KEY (pid, box_serial)
);
CREATE INDEX IF NOT EXISTS idx_received_at ON received_boxes(received_at);

-- Boxes that have gone out, so an outgoing scan can warn "already despatched".
CREATE TABLE IF NOT EXISTS despatched_boxes (
  pid           TEXT NOT NULL,
  box_serial    TEXT NOT NULL,
  session_id    TEXT NOT NULL,
  qty           REAL NOT NULL,
  despatched_at TEXT NOT NULL,
  PRIMARY KEY (pid, box_serial, session_id)
);

CREATE TABLE IF NOT EXISTS sessions (
  id               TEXT PRIMARY KEY,   -- UUID minted ON THE DEVICE at creation
  kind             TEXT NOT NULL,
  device_id        TEXT NOT NULL DEFAULT '',
  operator         TEXT NOT NULL DEFAULT '',
  company          TEXT NOT NULL,
  godown           TEXT NOT NULL,
  party            TEXT NOT NULL DEFAULT '',
  sales_order      TEXT NOT NULL DEFAULT '',
  state            TEXT NOT NULL,
  narration        TEXT NOT NULL DEFAULT '',
  created_at       TEXT NOT NULL,
  submitted_at     TEXT,
  completed_at     TEXT,
  tally_voucher_id TEXT NOT NULL DEFAULT '',
  duplicate        INTEGER NOT NULL DEFAULT 0,
  error_class      TEXT NOT NULL DEFAULT '',
  error_code       TEXT NOT NULL DEFAULT '',
  error_message    TEXT NOT NULL DEFAULT '',
  attempts         INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sessions_state ON sessions(state, created_at);

CREATE TABLE IF NOT EXISTS session_lines (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id      TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  pid             TEXT NOT NULL,
  box_serial      TEXT NOT NULL,
  qty             REAL NOT NULL,
  unit            TEXT NOT NULL DEFAULT '',
  stock_item_name TEXT NOT NULL DEFAULT '',   -- '' while UNRESOLVED_PID
  description     TEXT NOT NULL DEFAULT '',
  mfg_date        TEXT,
  -- The untouched scanner output. Stored forever: it is the only evidence
  -- available when a scan is disputed, and it lets a new parser reinterpret
  -- history.
  raw_payload     TEXT NOT NULL DEFAULT '',
  symbology       TEXT NOT NULL DEFAULT '',
  flags           TEXT NOT NULL DEFAULT '',   -- comma-separated LineFlag
  scanned_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lines_session ON session_lines(session_id);
CREATE INDEX IF NOT EXISTS idx_lines_box ON session_lines(pid, box_serial);

-- What a product IS, independent of whether Tally has it.
--
-- Built from the Simplex price list and BOQ sheets: part number to
-- description. This is reference data, deliberately separate from
-- pid_bindings, which records what a PID resolves to IN TALLY.
--
-- When a scan finds no Tally item, this supplies the description so the
-- operator does not have to type it -- they only supply what Tally needs and
-- this cannot know: unit, stock group, batch tracking.
CREATE TABLE IF NOT EXISTS product_catalogue (
  pid         TEXT PRIMARY KEY,
  description TEXT NOT NULL,
  source      TEXT NOT NULL DEFAULT '',   -- where the wording came from
  -- Some part numbers appear with more than one wording across sources. Those
  -- are offered as choices rather than one being picked silently.
  alternates  TEXT NOT NULL DEFAULT '',   -- JSON array
  loaded_at   TEXT NOT NULL
);

-- Products an operator met on the dock that Tally has never heard of.
--
-- The operator fills these in once, with the carton in their hand and the
-- description printed on the label. It is created in Tally straight away, and
-- is anything written to Tally. A stock item cannot be deleted once it has
-- transactions, so approval is the last point at which a mistake is cheap.
CREATE TABLE IF NOT EXISTS proposed_items (
  pid          TEXT PRIMARY KEY,
  name         TEXT NOT NULL,          -- "<PID> <DESCRIPTION>", the live convention
  description  TEXT NOT NULL DEFAULT '',
  base_units   TEXT NOT NULL DEFAULT '',
  batchwise    INTEGER NOT NULL DEFAULT 1,
  track_mfg    INTEGER NOT NULL DEFAULT 1,
  state        TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING|APPROVED|REJECTED|FAILED
  proposed_by  TEXT NOT NULL DEFAULT '',
  proposed_at  TEXT NOT NULL,
  session_id   TEXT NOT NULL DEFAULT '',
  raw_payload  TEXT NOT NULL DEFAULT '',
  decided_by   TEXT NOT NULL DEFAULT '',
  decided_at   TEXT,
  error        TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_proposed_state ON proposed_items(state, proposed_at);

CREATE TABLE IF NOT EXISTS audit_log (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  at        TEXT NOT NULL,
  actor     TEXT NOT NULL DEFAULT '',
  action    TEXT NOT NULL,
  subject   TEXT NOT NULL DEFAULT '',
  detail    TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_audit_at ON audit_log(at);
`;

export type DB = Database.Database;

export function openDb(path: string): DB {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true });
  const db = new Database(path);
  db.pragma('busy_timeout = 5000');
  db.exec(SCHEMA);
  return db;
}

export function nowIso(): string {
  return new Date().toISOString();
}

export function audit(
  db: DB, actor: string, action: string, subject = '', detail = '',
): void {
  db.prepare(
    `INSERT INTO audit_log (at, actor, action, subject, detail) VALUES (?,?,?,?,?)`,
  ).run(nowIso(), actor, action, subject, detail);
}

// --- master data sync -------------------------------------------------------

export interface SyncItem {
  name: string; alias?: string; partNo?: string;
  baseUnits: string; hasBatches: boolean;
}
export interface SyncBalance {
  stockItemName: string; batchName: string; godownName: string;
  closingQty: number; unit: string;
}
export interface SyncOrderLine {
  stockItemName: string; orderedQty: number; deliveredQty: number; unit: string;
}
export interface SyncOrder {
  voucherNumber: string; partyName: string; date: string; lines: SyncOrderLine[];
}

/**
 * Replaces cached master data with what the connector read from Tally.
 *
 * Done in one transaction so a device never syncs against a half-written
 * master. Balances are DELETEd first because a batch dropping to zero must
 * disappear, not linger at its old value -- a stale non-zero balance would let
 * an operator scan a box that has nothing left in it.
 */
export function applySync(
  db: DB,
  data: { items?: SyncItem[]; godowns?: string[]; balances?: SyncBalance[]; orders?: SyncOrder[] },
): void {
  const at = nowIso();

  const tx = db.transaction(() => {
    if (data.items?.length) {
      const up = db.prepare(`
        INSERT INTO stock_items (name, alias, part_no, base_units, has_batches, synced_at)
        VALUES (?,?,?,?,?,?)
        ON CONFLICT(name) DO UPDATE SET
          alias=excluded.alias, part_no=excluded.part_no,
          base_units=excluded.base_units, has_batches=excluded.has_batches,
          synced_at=excluded.synced_at`);
      for (const i of data.items) {
        up.run(i.name, i.alias ?? '', i.partNo ?? '', i.baseUnits, i.hasBatches ? 1 : 0, at);
      }
    }

    if (data.godowns?.length) {
      const up = db.prepare(
        `INSERT INTO godowns (name, synced_at) VALUES (?,?)
         ON CONFLICT(name) DO UPDATE SET synced_at=excluded.synced_at`);
      for (const g of data.godowns) up.run(g, at);
    }

    if (data.balances) {
      db.prepare(`DELETE FROM batch_balances`).run();
      const ins = db.prepare(`
        INSERT INTO batch_balances
          (stock_item_name, batch_name, godown_name, closing_qty, unit, synced_at)
        VALUES (?,?,?,?,?,?)
        ON CONFLICT(stock_item_name, batch_name, godown_name)
        DO UPDATE SET closing_qty=excluded.closing_qty, synced_at=excluded.synced_at`);
      for (const b of data.balances) {
        ins.run(b.stockItemName, b.batchName, b.godownName, b.closingQty, b.unit, at);
      }
    }

    if (data.orders) {
      db.prepare(`DELETE FROM sales_order_lines`).run();
      db.prepare(`DELETE FROM sales_orders`).run();
      const so = db.prepare(
        `INSERT INTO sales_orders (voucher_number, party_name, order_date, synced_at)
         VALUES (?,?,?,?)`);
      const sol = db.prepare(`
        INSERT INTO sales_order_lines
          (voucher_number, stock_item_name, ordered_qty, delivered_qty, unit)
        VALUES (?,?,?,?,?)`);
      for (const o of data.orders) {
        so.run(o.voucherNumber, o.partyName, o.date, at);
        for (const l of o.lines) {
          sol.run(o.voucherNumber, l.stockItemName, l.orderedQty, l.deliveredQty, l.unit);
        }
      }
    }
  });

  tx();
}

// --- PID resolution ---------------------------------------------------------

export interface Resolved {
  stockItemName: string;
  unit: string;
  description: string;
  hasBatches: boolean;
  source: 'BINDING' | 'PART_NO' | 'NAME' | 'ALIAS';
}

/**
 * A PID that matches several Tally items.
 *
 * Real and common: in a live catalogue, one Simplex part number was entered
 * under EIGHT different item names, because each sale bundled it differently
 * ("with base", "with sounder base", "with remote LED"...).
 *
 * Picking one would post stock against the wrong item, and nothing downstream
 * would ever notice. So an ambiguous PID resolves to nothing and carries its
 * candidates rather than guessing, because guessing wrong moves real stock.
 */
/** A product the catalogue knows about but Tally does not. */
export interface CatalogueEntry {
  pid: string;
  description: string;
  alternates: string[];
  source: string;
}

/** Looks a part number up in the reference catalogue. */
export function catalogueLookup(db: DB, pid: string): CatalogueEntry | null {
  const row = db.prepare(
    `SELECT pid, description, alternates, source FROM product_catalogue WHERE pid = ?`,
  ).get(pid) as { pid: string; description: string; alternates: string; source: string } | undefined;
  if (!row) return null;
  let alternates: string[] = [];
  try { alternates = JSON.parse(row.alternates || '[]'); } catch { /* ignore */ }
  return { pid: row.pid, description: row.description, alternates, source: row.source };
}

export interface Ambiguous {
  pid: string;
  candidates: string[];
}

/**
 * Maps a scanned PID onto a Tally stock item.
 *
 * Order matters. An explicit binding always wins, because it is the one a human
 * confirmed. The fallbacks below it are conveniences that save a person
 * from confirming the obvious cases, and each records how it matched so a
 * wrong auto-match is traceable later.
 */
export function resolvePid(db: DB, pid: string): Resolved | null {
  return resolvePidDetailed(db, pid).resolved;
}

/**
 * Resolution, with the ambiguity made visible.
 *
 * Callers that can act on it (the scan path) use this;
 * everything else uses resolvePid and treats ambiguity as simply unresolved.
 */
export function resolvePidDetailed(
  db: DB, pid: string,
): { resolved: Resolved | null; ambiguous: Ambiguous | null } {
  const r = resolveOne(db, pid);
  if (r) return { resolved: r, ambiguous: null };

  // No single answer. Were there several?
  const candidates = (db.prepare(
    `SELECT name FROM stock_items WHERE name LIKE ? ORDER BY name LIMIT 20`,
  ).all(`${pid} %`) as Array<{ name: string }>).map((x) => x.name);

  if (candidates.length > 1) {
    return { resolved: null, ambiguous: { pid, candidates } };
  }
  return { resolved: null, ambiguous: null };
}

function resolveOne(db: DB, pid: string): Resolved | null {
  const binding = db.prepare(
    `SELECT stock_item_name, description FROM pid_bindings WHERE pid = ?`,
  ).get(pid) as { stock_item_name: string; description: string } | undefined;

  if (binding) {
    const item = db.prepare(
      `SELECT base_units, has_batches FROM stock_items WHERE name = ?`,
    ).get(binding.stock_item_name) as { base_units: string; has_batches: number } | undefined;
    return {
      stockItemName: binding.stock_item_name,
      unit: item?.base_units ?? '',
      description: binding.description,
      hasBatches: !!item?.has_batches,
      source: 'BINDING',
    };
  }

  // Convenience fallbacks, in descending confidence.
  for (const [sql, source] of [
    [`SELECT name, base_units, has_batches FROM stock_items WHERE part_no = ? LIMIT 1`, 'PART_NO'],
    [`SELECT name, base_units, has_batches FROM stock_items WHERE name = ? LIMIT 1`, 'NAME'],
    [`SELECT name, base_units, has_batches FROM stock_items WHERE alias = ? LIMIT 1`, 'ALIAS'],
  ] as const) {
    const row = db.prepare(sql).get(pid) as
      { name: string; base_units: string; has_batches: number } | undefined;
    if (row) {
      return {
        stockItemName: row.name, unit: row.base_units, description: '',
        hasBatches: !!row.has_batches, source,
      };
    }
  }

  // Many Tally item names begin with the PID ("4098-9792 SSD SENSOR BASE").
  // Deliberately requires a UNIQUE match: an ambiguous prefix is worse than no
  // match, because it silently picks the wrong product.
  const prefix = db.prepare(
    `SELECT name, base_units, has_batches FROM stock_items WHERE name LIKE ? LIMIT 2`,
  ).all(`${pid} %`) as Array<{ name: string; base_units: string; has_batches: number }>;
  if (prefix.length === 1) {
    const row = prefix[0]!;
    return {
      stockItemName: row.name, unit: row.base_units, description: '',
      hasBatches: !!row.has_batches, source: 'NAME',
    };
  }

  return null;
}
