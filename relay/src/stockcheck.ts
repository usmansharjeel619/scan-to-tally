/**
 * Inventory check.
 *
 * The scanning half is nearly identical to incoming, which is why incoming was
 * built to be reusable. What is different is what happens at the end: a count
 * produces a VARIANCE, and only the variance gets written back.
 */

import type { DB, LineFlag } from './db.ts';
import { resolvePid } from './db.ts';
import { registry } from './barcode.ts';
import { canonicalPid } from './fragment.ts';
import type { ScanDecision } from './validation.ts';
import { wrongBarcodeMessage, rejectMessage, tail, fmt, EPS } from './validation.ts';

export type CountScope = 'PARTIAL' | 'FULL';

export interface StockCheckScanInput {
  sessionId: string;
  godown: string;
  raw: string;
  symbology: string;
  manual?: boolean;
  /**
   * Blind counting hides the book quantity while the operator counts. On by
   * default: showing the expected figure anchors the count to it, and a count
   * that only confirms the book is worth nothing.
   */
  blind?: boolean;
}

/**
 * A scan during a stock count.
 *
 * Unlike outgoing, an unknown product does not stop the operator. A box on the
 * floor that Tally has never heard of is exactly the kind of finding a stock
 * check exists to surface, so it is counted, flagged, and shown in the variance.
 */
export function decideStockCheckScan(db: DB, input: StockCheckScanInput): ScanDecision {
  const parse = registry.parse(input.symbology, input.raw);

  if (parse.outcome === 'WRONG_BARCODE') {
    return {
      outcome: 'WRONG_BARCODE', beep: 'REJECT', flags: [], parse,
      message: wrongBarcodeMessage(parse.hint),
    };
  }
  if (parse.outcome !== 'ACCEPT' || !parse.box) {
    return {
      outcome: 'REJECT', beep: 'REJECT', flags: [], parse,
      message: parse.outcome === 'REJECT'
        ? rejectMessage(parse.reason)
        : 'Label not recognised. Use manual entry if it is damaged.',
    };
  }

  const { pid, boxSerial, qty, mfgDate } = parse.box;
  const flags: LineFlag[] = [];
  if (input.manual) flags.push('MANUAL');

  // A box is a physical object, counted once. Scanning it twice is a
  // double-scan, not a second box.
  const already = db.prepare(
    `SELECT id, qty FROM session_lines
      WHERE session_id = ? AND REPLACE(UPPER(pid),'-','') = ? AND box_serial = ?`,
  ).get(input.sessionId, canonicalPid(pid), boxSerial) as { id: number; qty: number } | undefined;

  if (already) {
    return {
      outcome: 'DUPLICATE', beep: 'DUPLICATE', flags: [], parse, editLineId: already.id,
      message: `Box ${tail(boxSerial)} is already counted (${fmt(already.qty)}).`,
    };
  }

  const resolved = resolvePid(db, pid);
  if (!resolved) flags.push('UNRESOLVED_PID');

  // Only reveal the book figure when blind counting is explicitly turned off.
  const blind = input.blind !== false;
  let onHand: number | undefined;
  if (resolved && !blind) {
    const bal = db.prepare(
      `SELECT closing_qty FROM batch_balances
        WHERE stock_item_name = ? AND batch_name = ? AND godown_name = ?`,
    ).get(resolved.stockItemName, boxSerial, input.godown) as { closing_qty: number } | undefined;
    onHand = bal?.closing_qty ?? 0;
  }

  return {
    outcome: resolved ? 'ACCEPT' : 'FLAGGED',
    beep: resolved ? 'ACCEPT' : 'FLAGGED',
    flags,
    parse,
    available: onHand,
    box: {
      pid, boxSerial, labelQty: qty, mfgDate,
      stockItemName: resolved?.stockItemName ?? '',
      description: resolved?.description ?? '',
      unit: resolved?.unit ?? '',
    },
    message: resolved
      ? `${resolved.description || resolved.stockItemName} - counted ${fmt(qty)}`
      : `Box ${tail(boxSerial)} of ${pid} is not in Tally at all - counted ${fmt(qty)}`,
  };
}

export type VarianceKind = 'MATCH' | 'SHORT' | 'OVER' | 'NOT_IN_BOOK' | 'NOT_COUNTED';

export interface VarianceRow {
  stockItemName: string;
  pid: string;
  boxSerial: string;
  unit: string;
  countedQty: number;
  bookQty: number;
  variance: number;
  kind: VarianceKind;
}

export interface VarianceReport {
  rows: VarianceRow[];
  scope: CountScope;
  counted: number;
  matched: number;
  discrepancies: number;
  /** Boxes the book shows in this godown that nobody scanned. */
  notCounted: number;
  /** True when adopting this count would write a zero onto uncounted stock. */
  willZeroUncounted: boolean;
}

const SEP = String.fromCharCode(31);

function key(item: string, batch: string): string {
  return item + SEP + batch;
}

/**
 * Compares a count against the book.
 *
 * Scope is the dangerous knob:
 *
 *   PARTIAL adjusts only the boxes that were scanned. Safe, and the default.
 *   FULL also treats an uncounted box as zero. Correct for a complete
 *   wall-to-wall count; destroys real stock if the count was abandoned halfway.
 *
 * NOT_COUNTED rows are reported under BOTH scopes, so the operator can always
 * see what they missed. Only FULL turns them into adjustments.
 */
export function computeVariance(
  db: DB, sessionId: string, godown: string, scope: CountScope = 'PARTIAL',
): VarianceReport {
  const lines = db.prepare(
    `SELECT pid, box_serial, stock_item_name, unit, SUM(qty) AS qty
       FROM session_lines WHERE session_id = ?
      GROUP BY pid, box_serial, stock_item_name, unit`,
  ).all(sessionId) as Array<{
    pid: string; box_serial: string; stock_item_name: string; unit: string; qty: number;
  }>;

  const rows: VarianceRow[] = [];
  const seen = new Set<string>();

  for (const l of lines) {
    seen.add(key(l.stock_item_name, l.box_serial));

    const bal = l.stock_item_name
      ? db.prepare(
        `SELECT closing_qty FROM batch_balances
          WHERE stock_item_name = ? AND batch_name = ? AND godown_name = ?`,
      ).get(l.stock_item_name, l.box_serial, godown) as { closing_qty: number } | undefined
      : undefined;

    const bookQty = bal?.closing_qty ?? 0;
    const variance = l.qty - bookQty;

    let kind: VarianceKind;
    if (!l.stock_item_name || !bal) kind = 'NOT_IN_BOOK';
    else if (Math.abs(variance) < EPS) kind = 'MATCH';
    else kind = variance < 0 ? 'SHORT' : 'OVER';

    rows.push({
      stockItemName: l.stock_item_name, pid: l.pid, boxSerial: l.box_serial,
      unit: l.unit, countedQty: l.qty, bookQty, variance, kind,
    });
  }

  const book = db.prepare(
    `SELECT stock_item_name, batch_name, closing_qty, unit FROM batch_balances
      WHERE godown_name = ? AND closing_qty > 0`,
  ).all(godown) as Array<{
    stock_item_name: string; batch_name: string; closing_qty: number; unit: string;
  }>;

  let notCounted = 0;
  for (const b of book) {
    if (seen.has(key(b.stock_item_name, b.batch_name))) continue;
    notCounted += 1;
    rows.push({
      stockItemName: b.stock_item_name, pid: '', boxSerial: b.batch_name, unit: b.unit,
      countedQty: 0, bookQty: b.closing_qty, variance: -b.closing_qty, kind: 'NOT_COUNTED',
    });
  }

  // Biggest discrepancies first: that is the order somebody investigating wants.
  rows.sort((a, b) => Math.abs(b.variance) - Math.abs(a.variance));

  return {
    rows,
    scope,
    counted: lines.length,
    matched: rows.filter((r) => r.kind === 'MATCH').length,
    discrepancies: rows.filter(
      (r) => r.kind === 'SHORT' || r.kind === 'OVER' || r.kind === 'NOT_IN_BOOK',
    ).length,
    notCounted,
    willZeroUncounted: scope === 'FULL' && notCounted > 0,
  };
}

/**
 * The lines a Physical Stock voucher should carry.
 *
 * Two deliberate exclusions:
 *   MATCH rows, because writing back a figure Tally already holds is noise in
 *   the stock report for no gain.
 *   NOT_IN_BOOK rows, because they have no stock item to post against; they go
 *   for review instead.
 */
export function varianceToLines(
  report: VarianceReport,
): Array<{ stockItemName: string; unit: string; boxes: Array<{ boxSerial: string; qty: number }> }> {
  const byItem = new Map<string, { stockItemName: string; unit: string; boxes: Array<{ boxSerial: string; qty: number }> }>();

  for (const r of report.rows) {
    if (r.kind === 'MATCH' || r.kind === 'NOT_IN_BOOK') continue;
    if (r.kind === 'NOT_COUNTED' && report.scope !== 'FULL') continue;
    if (!r.stockItemName) continue;

    let entry = byItem.get(r.stockItemName);
    if (!entry) {
      entry = { stockItemName: r.stockItemName, unit: r.unit, boxes: [] };
      byItem.set(r.stockItemName, entry);
    }
    // Physical Stock sets the ABSOLUTE quantity, so the counted figure is what
    // goes on the voucher -- not the difference.
    entry.boxes.push({ boxSerial: r.boxSerial, qty: r.countedQty });
  }

  return [...byItem.values()];
}
