#!/usr/bin/env node
/**
 * Clears the relay's cached data for a company changeover.
 *
 * WHEN THIS IS NEEDED
 *   The relay is pointed at a different Tally company — a test company being
 *   retired, a new machine with a fresh set of books. Most cached tables heal
 *   themselves on the next sync, but two do not:
 *
 *     received_boxes / despatched_boxes  accumulate forever by design, because
 *       that is what makes "this carton was already received" work months
 *       later. Carry them across a company change and every real carton scanned
 *       on the new books gets a DUPLICATE beep pointing at a receipt that no
 *       longer exists anywhere.
 *
 *     pid_bindings  map a scanned PID to a Tally stock item BY NAME. Under a
 *       different company those names may not exist, so a scan resolves to an
 *       item that cannot be posted to.
 *
 * WHAT IT KEEPS, ON PURPOSE
 *   devices    the handset stays enrolled; no re-provisioning, no new token.
 *   audit_log  the record of what happened. Wiping an audit log during a
 *              cleanup is precisely when you most want to still have it.
 *
 * The handset needs no separate step: it replaces its own box history wholesale
 * from each sync response, so clearing here propagates on the next sync.
 *
 * USAGE
 *   node scripts/reset-company.mjs                    # dry run, changes nothing
 *   node scripts/reset-company.mjs --confirm          # do it
 *   node scripts/reset-company.mjs --confirm --keep-bindings
 *   node scripts/reset-company.mjs --confirm --godown "Main Store"
 *
 *   STT_DB=/var/lib/scan-to-tally/relay.db  (default)
 */

import Database from 'better-sqlite3';
import { existsSync } from 'node:fs';

const DB_PATH = process.env.STT_DB ?? '/var/lib/scan-to-tally/relay.db';
const argv = process.argv.slice(2);

const has = (f) => argv.includes(f);
const valueOf = (f) => {
  const i = argv.indexOf(f);
  return i >= 0 ? argv[i + 1] : undefined;
};

const confirm = has('--confirm');
const keepBindings = has('--keep-bindings');
const newGodown = valueOf('--godown');

/**
 * Scan history. Never replaced by a sync, so it must be cleared explicitly.
 * This is the whole reason the script exists.
 */
const HISTORY = ['received_boxes', 'despatched_boxes'];

/** Test sessions and their lines. Noise on the new books. */
const SESSIONS = ['session_lines', 'sessions'];

/** Learned PID -> item mappings. Company-specific. */
const BINDINGS = ['pid_bindings'];

/**
 * Cached Tally masters. These DO heal on the next sync, but clearing them means
 * a supervisor searching the item master before that sync lands cannot pick an
 * item belonging to the company you just left.
 */
const MASTERS = [
  'batch_balances', 'sales_order_lines', 'sales_orders',
  'stock_items', 'godowns', 'catalogue', 'proposals',
];

const KEPT = ['devices', 'audit_log'];

if (!existsSync(DB_PATH)) {
  console.error(`No database at ${DB_PATH}. Set STT_DB if it lives elsewhere.`);
  process.exit(1);
}

const db = new Database(DB_PATH);
const present = new Set(
  db.prepare(`SELECT name FROM sqlite_master WHERE type='table'`).all().map((r) => r.name),
);

const count = (t) => {
  try {
    return db.prepare(`SELECT COUNT(*) c FROM ${t}`).get().c;
  } catch {
    return null;
  }
};

const targets = [
  ...HISTORY, ...SESSIONS,
  ...(keepBindings ? [] : BINDINGS),
  ...MASTERS,
].filter((t) => present.has(t));

console.log(`\ndatabase : ${DB_PATH}`);
console.log(`mode     : ${confirm ? 'APPLY' : 'dry run (nothing will change)'}\n`);

console.log('would clear:');
let total = 0;
for (const t of targets) {
  const n = count(t);
  total += n ?? 0;
  const note = HISTORY.includes(t) ? '   <- the one that causes false duplicates' : '';
  console.log(`  ${t.padEnd(20)} ${String(n).padStart(6)}${note}`);
}

console.log('\nkeeping:');
for (const t of KEPT) {
  if (present.has(t)) console.log(`  ${t.padEnd(20)} ${String(count(t)).padStart(6)}`);
}

if (keepBindings && present.has('pid_bindings')) {
  console.log(`  ${'pid_bindings'.padEnd(20)} ${String(count('pid_bindings')).padStart(6)}   (--keep-bindings)`);
}

const devices = present.has('devices')
  ? db.prepare(`SELECT id, company, godown FROM devices`).all()
  : [];
if (devices.length) {
  console.log('\ndevices:');
  for (const d of devices) console.log(`  ${d.id} | company=${d.company} | godown=${d.godown}`);
  console.log(
    '\n  The company corrects itself when the new connector says hello.\n' +
    '  The godown does NOT — pass --godown "<name>" if it has changed.',
  );
}

if (!confirm) {
  console.log(`\n${total} row(s) would be removed. Re-run with --confirm to apply.\n`);
  db.close();
  process.exit(0);
}

// Back up before touching anything. Reversible until it isn't.
const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
const backup = DB_PATH.replace(/\.db$/, '') + `-before-reset-${stamp}.db`;

await db.backup(backup);
console.log(`\nbackup   : ${backup}`);

// One transaction: a half-cleared relay is worse than an uncleared one.
const run = db.transaction(() => {
  for (const t of targets) db.prepare(`DELETE FROM ${t}`).run();

  if (newGodown && present.has('devices')) {
    db.prepare(`UPDATE devices SET godown = ?`).run(newGodown);
  }

  if (present.has('audit_log')) {
    db.prepare(
      `INSERT INTO audit_log (at, actor, action, subject, detail) VALUES (?,?,?,?,?)`,
    ).run(
      new Date().toISOString(), 'reset-company.mjs', 'COMPANY_RESET', '',
      `cleared ${targets.join(', ')}${keepBindings ? ' (bindings kept)' : ''}` +
      `${newGodown ? `; godown -> ${newGodown}` : ''}; backup ${backup}`,
    );
  }
});

run();
db.prepare('VACUUM').run();

console.log('cleared  :');
for (const t of targets) console.log(`  ${t.padEnd(20)} -> ${count(t)}`);

if (newGodown) console.log(`\ngodown set to "${newGodown}" on ${devices.length} device(s)`);

console.log(
  '\nDone. Next steps:\n' +
  '  1. Start the connector on the new Tally machine.\n' +
  '  2. Wait for the first master sync (watch the relay log).\n' +
  '  3. Sync the handset — its box history clears from the same response.\n',
);

db.close();
