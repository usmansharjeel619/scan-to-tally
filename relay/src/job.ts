/**
 * Turning a scan session into the voucher(s) it becomes.
 *
 * Kept out of the HTTP layer so it can be exercised directly: importing the
 * server starts a listener and opens a database, which is no way to test the
 * rule that decides whether stock reaches Tally.
 */

import type { DB } from './db.ts';
import { computeVariance, varianceToLines, type CountScope } from './stockcheck.ts';

export interface JobBox {
  boxSerial: string;
  qty: number;
  mfgDate?: string;
  rawPayload?: string;
  pid?: string;
  manual?: boolean;
}

export interface JobLine {
  stockItemName: string;
  unit: string;
  description?: string;
  boxes: JobBox[];
}

export interface PostJob {
  /**
   * The idempotency key, end to end. One voucher, one key.
   *
   * For a session that becomes several vouchers this is derived from the
   * session and the product, so each voucher is independently protected
   * against being posted twice.
   */
  sessionId: string;
  /**
   * Tally's id for the voucher this REPLACES, when the product already has
   * one. Empty means create a new voucher.
   *
   * The lines then carry every box the voucher must end up holding, old and
   * new together, because Tally replaces a voucher on alter rather than
   * merging into it.
   */
  alterMasterId?: string;
  /** The session the voucher came from, for attributing the result back. */
  parentSessionId: string;
  kind: string;
  company: string;
  godown: string;
  party?: string;
  salesOrder?: string;
  scope?: CountScope;
  date: string;
  operator?: string;
  deviceId?: string;
  narration?: string;
  lines: JobLine[];
}

/** Why a session produced no jobs. Told apart because they read differently. */
export type NotBuiltReason =
  | 'NO_SESSION'
  | 'NO_LINES'
  | 'WAITING_FOR_PRODUCT'
  | 'NO_VARIANCE';

export interface BuildResult {
  jobs: PostJob[];
  reason?: NotBuiltReason;
  /** How many boxes are still waiting for Tally to create their product. */
  unresolved: number;
}

/**
 * Whether a later receipt joins the voucher its product already has.
 *
 * OFF, because TallyPrime does not do what this needs. Asked to alter voucher
 * 999999 -- a number it cannot have -- it created voucher 51 instead of
 * reporting that there was nothing to alter. So ACTION="Alter" with a
 * <MASTERID> child is not how that version identifies a voucher, and every
 * merge would instead be a duplicate: the product's stock counted twice, in
 * two vouchers, from one pallet.
 *
 * The connector catches that and refuses (ALTER_BECAME_CREATE) -- but only
 * AFTER Tally has already made the voucher, so the refusal is a report, not a
 * defence. Until the right way to name a voucher is known, not asking is the
 * only safe position.
 *
 * Both halves are gated together on purpose. With merging off, a job must
 * carry ONLY the boxes just scanned: sending the earlier ones as well, to a
 * voucher that is now being created rather than replaced, would count every
 * previous carton a second time.
 */
const MERGE_INTO_ONE_VOUCHER = process.env.STT_MERGE_VOUCHERS === '1';

/**
 * Builds every voucher a session should become.
 *
 * ONE VOUCHER PER PRODUCT. A pallet of four products becomes four Physical
 * Stock vouchers, each holding every box of its own product however the
 * scanning was interleaved. A day book then reads one line per product, and a
 * product scanned, left, and come back to lands in the same voucher as the
 * rest of it rather than a second one.
 *
 * NOTHING PARTIAL. If any box is still waiting for Tally to create its product,
 * no voucher is built at all -- not even for the products that are ready.
 * Building the ready ones is what used to happen, and the waiting boxes were
 * then dropped from a session that went on to post and mark itself done: stock
 * physically on the shelf, absent from Tally, with nothing left to retry it.
 */
export function buildJobs(
  db: DB, sessionId: string, scope: CountScope = 'PARTIAL',
): BuildResult {
  const s = db.prepare(`SELECT * FROM sessions WHERE id = ?`).get(sessionId) as any;
  if (!s) return { jobs: [], reason: 'NO_SESSION', unresolved: 0 };

  const base = {
    parentSessionId: s.id,
    kind: s.kind,
    company: s.company,
    godown: s.godown,
    party: s.party,
    salesOrder: s.sales_order,
    date: new Date().toISOString(),
    operator: s.operator,
    deviceId: s.device_id,
  };

  // A stock check posts the VARIANCE, never the raw count, and it is one
  // adjustment rather than one per product: the whole point is the comparison,
  // and splitting it would turn one reconciliation into several.
  if (s.kind === 'STOCKCHECK') {
    const report = computeVariance(db, sessionId, s.godown, scope);
    const lines = varianceToLines(report);
    if (!lines.length) return { jobs: [], reason: 'NO_VARIANCE', unresolved: 0 };
    return {
      unresolved: 0,
      jobs: [{
        ...base,
        sessionId: s.id,
        scope,
        narration: s.narration ||
          `Stock check (${scope.toLowerCase()}) | ${s.godown} | ${report.counted} boxes counted`,
        lines: lines.map((l) => ({
          stockItemName: l.stockItemName,
          unit: l.unit,
          boxes: l.boxes.map((x) => ({ boxSerial: x.boxSerial, qty: x.qty })),
        })),
      }],
    };
  }

  const lines = db.prepare(
    `SELECT * FROM session_lines WHERE session_id = ? ORDER BY id`,
  ).all(sessionId) as any[];
  if (!lines.length) return { jobs: [], reason: 'NO_LINES', unresolved: 0 };

  const unresolved = lines.filter((l) => !l.stock_item_name).length;
  if (unresolved > 0) {
    return { jobs: [], reason: 'WAITING_FOR_PRODUCT', unresolved };
  }

  // Grouped by product, so interleaved scanning still lands one box beside the
  // rest of its own product.
  const byItem = new Map<string, JobLine>();
  for (const l of lines) {
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
  if (!byItem.size) return { jobs: [], reason: 'NO_LINES', unresolved: 0 };

  const products = [...byItem.values()];

  return {
    unresolved: 0,
    jobs: products.map((line, i) => {
      // ONE VOUCHER PER PRODUCT, KEPT AND ADDED TO. A product counted before
      // already has a voucher, and a new carton of it belongs in that voucher
      // rather than beside it.
      //
      // Tally REPLACES a voucher on alter, so this has to carry every box the
      // voucher should end up holding -- the ones it already has as well as
      // the ones just scanned. Sending only the new ones would delete the
      // earlier cartons from the books.
      const held = existing(db, s.kind, base.company, base.godown, line.stockItemName, s.id);

      return {
        ...base,
        // Derived, and STABLE: the same session and product always produce the
        // same key, so a redelivery is recognised as the voucher it already is
        // rather than posted again. Ordered by first appearance, so the index
        // is reproducible from the same session.
        sessionId: voucherKey(s.id, line.stockItemName, i),
        alterMasterId: held.masterId,
        narration: s.narration ||
          `Mobile scan | operator ${s.operator ?? ''} | session ${s.id}`,
        lines: [{ ...line, boxes: mergeBoxes(held.boxes, line.boxes) }],
      };
    }),
  };
}

/**
 * The voucher a product already has, and every box on it.
 *
 * Only for INCOMING. A despatch removes stock and a stock check adjusts it;
 * neither accumulates into a standing voucher, and altering somebody's
 * delivery note a week later would be indefensible.
 *
 * Boxes still in flight are included as well as boxes already posted. Two
 * receipts of the same product submitted seconds apart would otherwise each
 * carry only what the other had not yet finished recording, and the one that
 * landed second would erase the one that landed first.
 */
function existing(
  db: DB, kind: string, company: string, godown: string,
  stockItemName: string, exceptSessionId: string,
): { masterId?: string; boxes: JobBox[] } {
  if (kind !== 'INCOMING' || !MERGE_INTO_ONE_VOUCHER) return { boxes: [] };

  const v = db.prepare(
    `SELECT master_id FROM item_vouchers
      WHERE company = ? AND godown = ? AND stock_item_name = ?`,
  ).get(company, godown, stockItemName) as { master_id: string } | undefined;

  const posted = db.prepare(
    `SELECT box_serial, qty, mfg_date, pid FROM posted_batches
      WHERE company = ? AND godown = ? AND stock_item_name = ?
      ORDER BY posted_at, box_serial`,
  ).all(company, godown, stockItemName) as Array<
    { box_serial: string; qty: number; mfg_date: string | null; pid: string }>;

  const inFlight = db.prepare(
    `SELECT l.box_serial, l.qty, l.mfg_date, l.pid
       FROM session_vouchers v
       JOIN session_lines l ON l.session_id = v.session_id
      WHERE v.stock_item_name = ? AND v.state IN ('QUEUED','POSTING')
        AND v.session_id <> ? AND l.stock_item_name = ?
      ORDER BY l.id`,
  ).all(stockItemName, exceptSessionId, stockItemName) as Array<
    { box_serial: string; qty: number; mfg_date: string | null; pid: string }>;

  return {
    masterId: v?.master_id || undefined,
    boxes: [...posted, ...inFlight].map((b) => ({
      boxSerial: b.box_serial,
      qty: b.qty,
      mfgDate: b.mfg_date ?? undefined,
      pid: b.pid,
    })),
  };
}

/**
 * Old boxes then new ones, each box once.
 *
 * A box scanned again on a later receipt is the SAME carton -- the duplicate
 * check upstream is what makes that true -- so the later count wins rather
 * than being added to the earlier one. Anything else would double the stock of
 * a box that was merely re-counted.
 */
function mergeBoxes(held: JobBox[], fresh: JobBox[]): JobBox[] {
  const by = new Map<string, JobBox>();
  for (const b of [...held, ...fresh]) by.set(b.boxSerial, b);
  return [...by.values()];
}

/**
 * The idempotency key for one product's voucher out of a session.
 *
 * Carries the session so it is traceable, and the product so two vouchers from
 * one session can never collide. The index is a tiebreak only -- two distinct
 * products cannot share a name, but a name can contain anything, so the key
 * does not depend on it being well behaved.
 */
export function voucherKey(sessionId: string, stockItemName: string, index: number): string {
  return `${sessionId}#${index}`;
}

/** Whether a session has everything it needs to post. */
export function isReadyToPost(db: DB, sessionId: string): boolean {
  return buildJobs(db, sessionId).jobs.length > 0;
}
