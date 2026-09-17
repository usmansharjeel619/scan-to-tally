/**
 * The rules that decide what happens when a box is scanned.
 *
 * Two principles run through all of it:
 *
 *   Never block the dock. Anything unresolvable is accepted, counted and
 *   flagged for review afterwards. The one exception is outgoing: a box
 *   that is not on the order, or has no stock left, IS refused, because
 *   catching that at the dock instead of at the customer is the whole point.
 *
 *   The beep is the interface. The operator is not looking at the screen for
 *   every box, so every decision carries the sound to make. It is a first-class
 *   field, not a UI detail.
 */

import type { DB, LineFlag, Resolved } from './db.ts';
import { resolvePid, resolvePidDetailed, catalogueLookup } from './db.ts';
import { registry, boxKey, type ParseResult } from './barcode.ts';

/** What the device does with the scan. */
export type ScanOutcome =
  | 'ACCEPT'        // counted
  | 'FLAGGED'       // counted, needs review later
  | 'DUPLICATE'     // same (pid, box) already present
  | 'WRONG_BARCODE' // one of the label's other codes
  | 'REJECT';       // set the box aside

/** The sound to make. Must be distinguishable through ear defenders. */
export type Beep = 'ACCEPT' | 'DUPLICATE' | 'REJECT' | 'FLAGGED';

export interface ScanDecision {
  outcome: ScanOutcome;
  beep: Beep;
  /** Short line for the screen. Written for an operator, not a developer. */
  message: string;
  /** Present when the scan yielded a usable box. */
  box?: {
    pid: string;
    boxSerial: string;
    labelQty: number;
    mfgDate: string | null;
    stockItemName: string;
    description: string;
    unit: string;
  };
  /** Outgoing: the hard ceiling and how fresh it is. */
  available?: number;
  availableAsOf?: string;
  /** Outgoing: what the sales order still has outstanding for this item. */
  orderPending?: number;
  /** Set when an already-scanned box should be re-opened for editing. */
  editLineId?: number;
  flags: LineFlag[];
  /** Can the operator deliberately override? Only ever for historical dupes. */
  overridable?: boolean;
  /**
   * What this product is, from the reference catalogue, when Tally has no item
   * for it. The operator confirms rather than types -- they supply only what
   * the catalogue cannot know: unit, group, batch tracking.
   */
  catalogue?: { description: string; alternates: string[]; source: string };
  parse: ParseResult;
}

export const EPS = 1e-4;

export function fmt(n: number): string {
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(3)));
}

// --- incoming ---------------------------------------------------------------

export interface IncomingScanInput {
  sessionId: string;
  raw: string;
  symbology: string;
  manual?: boolean;
  /** Set when the operator has deliberately accepted a historical duplicate. */
  overrideDuplicate?: boolean;
}

/**
 * Incoming is scan-only: quantity comes from the barcode, so one scan is one
 * complete line.
 */
export function decideIncomingScan(db: DB, input: IncomingScanInput): ScanDecision {
  const parse = registry.parse(input.symbology, input.raw);

  if (parse.outcome === 'WRONG_BARCODE') {
    return {
      outcome: 'WRONG_BARCODE', beep: 'REJECT', flags: [], parse,
      message: wrongBarcodeMessage(parse.hint),
    };
  }
  if (parse.outcome === 'REJECT') {
    return {
      outcome: 'REJECT', beep: 'REJECT', flags: [], parse,
      message: rejectMessage(parse.reason),
    };
  }
  if (parse.outcome !== 'ACCEPT' || !parse.box) {
    return {
      outcome: 'REJECT', beep: 'REJECT', flags: [], parse,
      message: 'Label not recognised. Use manual entry if it is damaged.',
    };
  }

  const { pid, boxSerial, qty, mfgDate } = parse.box;
  const flags: LineFlag[] = [];
  if (input.manual) flags.push('MANUAL');

  // 1. Duplicate within this session -- a hard block, nothing to dismiss.
  const inSession = db.prepare(
    `SELECT id, qty FROM session_lines WHERE session_id = ? AND pid = ? AND box_serial = ?`,
  ).get(input.sessionId, pid, boxSerial) as { id: number; qty: number } | undefined;

  if (inSession) {
    return {
      outcome: 'DUPLICATE', beep: 'DUPLICATE', flags: [], parse,
      editLineId: inSession.id,
      message: `Box ${tail(boxSerial)} is already on this receipt (${fmt(inSession.qty)}).`,
    };
  }

  // 2. Received in an earlier session. Still a duplicate beep, but returns and
  //    reprinted labels are real, so the operator may deliberately override.
  const historical = db.prepare(
    `SELECT session_id, received_at, qty FROM received_boxes WHERE pid = ? AND box_serial = ?`,
  ).get(pid, boxSerial) as { session_id: string; received_at: string; qty: number } | undefined;

  if (historical && !input.overrideDuplicate) {
    return {
      outcome: 'DUPLICATE', beep: 'DUPLICATE', flags: [], parse, overridable: true,
      message: `Box ${tail(boxSerial)} was already received on ${
        historical.received_at.slice(0, 10)}. Accept again only if this is a return.`,
    };
  }
  if (historical && input.overrideDuplicate) flags.push('DUPLICATE_OVERRIDE');

  // 3. Resolve the product. Neither an unknown NOR an ambiguous PID stops the
  //    operator -- the count is right either way, only the identity is pending.
  const { resolved, ambiguous } = resolvePidDetailed(db, pid);
  if (ambiguous) flags.push('AMBIGUOUS_PID');
  else if (!resolved) flags.push('UNRESOLVED_PID');
  if (resolved && !resolved.hasBatches) flags.push('NO_BATCH_SUPPORT');

  // Not in Tally -- but the catalogue may still know what it is.
  const cat = (!resolved && !ambiguous) ? catalogueLookup(db, pid) : null;

  const flagged = flags.some((f) => f === 'UNRESOLVED_PID' || f === 'AMBIGUOUS_PID'
    || f === 'NO_BATCH_SUPPORT' || f === 'DUPLICATE_OVERRIDE');

  return {
    outcome: flagged ? 'FLAGGED' : 'ACCEPT',
    beep: flagged ? 'FLAGGED' : 'ACCEPT',
    flags,
    parse,
    box: {
      pid, boxSerial, labelQty: qty, mfgDate,
      stockItemName: resolved?.stockItemName ?? '',
      description: resolved?.description ?? '',
      unit: resolved?.unit ?? '',
    },
    catalogue: cat
      ? { description: cat.description, alternates: cat.alternates, source: cat.source }
      : undefined,
    message: resolved
      ? `${resolved.description || resolved.stockItemName} - ${fmt(qty)}`
      : ambiguous
        ? `${pid} matches ${ambiguous.candidates.length} products in Tally - ${fmt(qty)} counted, but which one is unclear`
        : cat
          ? `${cat.description} - ${fmt(qty)} counted, not yet a Tally item`
          : `Unknown product ${pid} - ${fmt(qty)} counted, needs review`,
  };
}

// --- outgoing ---------------------------------------------------------------

export interface OutgoingScanInput {
  sessionId: string;
  salesOrder: string;
  godown: string;
  raw: string;
  symbology: string;
  manual?: boolean;
}

/**
 * Outgoing refuses far more than incoming does, on purpose. Quantity is typed
 * by the employee afterwards; this call establishes the ceiling they type
 * against.
 */
export function decideOutgoingScan(db: DB, input: OutgoingScanInput): ScanDecision {
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

  // Outgoing cannot proceed on an unknown product: there is nothing to
  // deduct stock from.
  const { resolved, ambiguous } = resolvePidDetailed(db, pid);
  if (!resolved) {
    return {
      outcome: 'REJECT', beep: 'REJECT', flags, parse,
      message: ambiguous
        ? `${pid} matches ${ambiguous.candidates.length} different items in Tally. ` +
          `It cannot be despatched until those names are sorted out in Tally.`
        : `Product ${pid} is not in Tally, so it cannot be despatched.`,
    };
  }

  // Is this item even on the order? This check is the entire reason the
  // validation exists -- catching it here rather than at the customer.
  const line = db.prepare(
    `SELECT ordered_qty, delivered_qty, unit FROM sales_order_lines
      WHERE voucher_number = ? AND stock_item_name = ?`,
  ).get(input.salesOrder, resolved.stockItemName) as
    { ordered_qty: number; delivered_qty: number; unit: string } | undefined;

  if (!line) {
    return {
      outcome: 'REJECT', beep: 'REJECT', flags, parse,
      message: `${resolved.description || resolved.stockItemName} is not on order ${input.salesOrder}.`,
    };
  }

  const balance = db.prepare(
    `SELECT closing_qty, unit, synced_at FROM batch_balances
      WHERE stock_item_name = ? AND batch_name = ? AND godown_name = ?`,
  ).get(resolved.stockItemName, boxSerial, input.godown) as
    { closing_qty: number; unit: string; synced_at: string } | undefined;

  // The ceiling is Tally's balance, NOT the printed box quantity: a box that
  // shipped 5 of 18 last week has 13 left while its label still says 18.
  const onHand = balance?.closing_qty ?? 0;
  if (onHand <= EPS) {
    return {
      outcome: 'REJECT', beep: 'REJECT', flags, parse,
      available: 0, availableAsOf: balance?.synced_at,
      message: balance
        ? `Box ${tail(boxSerial)} is empty in ${input.godown}.`
        : `Box ${tail(boxSerial)} is not in stock in ${input.godown}.`,
    };
  }

  // Already committed on this session -- a second line off the same box must
  // fit inside what is left.
  const committed = (db.prepare(
    `SELECT COALESCE(SUM(qty),0) AS q FROM session_lines
      WHERE session_id = ? AND pid = ? AND box_serial = ?`,
  ).get(input.sessionId, pid, boxSerial) as { q: number }).q;

  const existing = db.prepare(
    `SELECT id FROM session_lines WHERE session_id = ? AND pid = ? AND box_serial = ?`,
  ).get(input.sessionId, pid, boxSerial) as { id: number } | undefined;

  const available = Math.max(0, onHand - committed);
  if (available <= EPS) {
    return {
      outcome: 'REJECT', beep: 'REJECT', flags, parse,
      available: 0, availableAsOf: balance?.synced_at, editLineId: existing?.id,
      message: `All ${fmt(onHand)} of box ${tail(boxSerial)} is already on this despatch.`,
    };
  }

  const orderPending = Math.max(0, line.ordered_qty - line.delivered_qty - committedForItem(
    db, input.sessionId, resolved.stockItemName,
  ));

  return {
    // Scanning an already-scanned box re-opens that line rather than adding a
    // second one.
    outcome: 'ACCEPT', beep: 'ACCEPT', flags, parse,
    editLineId: existing?.id,
    available,
    availableAsOf: balance?.synced_at,
    orderPending,
    box: {
      pid, boxSerial, labelQty: qty, mfgDate,
      stockItemName: resolved.stockItemName,
      description: resolved.description,
      unit: resolved.unit || line.unit,
    },
    message: `${resolved.description || resolved.stockItemName} - enter quantity`,
  };
}

function committedForItem(db: DB, sessionId: string, stockItemName: string): number {
  return (db.prepare(
    `SELECT COALESCE(SUM(qty),0) AS q FROM session_lines
      WHERE session_id = ? AND stock_item_name = ?`,
  ).get(sessionId, stockItemName) as { q: number }).q;
}

// --- quantity entry ---------------------------------------------------------

export interface QtyDecision {
  ok: boolean;
  /** Warnings do not block. Errors do. */
  error?: string;
  warning?: string;
  available: number;
  orderPending: number;
}

/**
 * Validates a typed outgoing quantity against the three ceilings.
 *
 * Called live as the operator types, so Confirm can be disabled rather than the
 * number being rejected after the fact. There must be no way to submit an
 * invalid quantity.
 */
export function validateOutgoingQty(
  db: DB,
  opts: {
    sessionId: string; salesOrder: string; godown: string;
    pid: string; boxSerial: string; stockItemName: string;
    qty: number; excludeLineId?: number;
  },
): QtyDecision {
  const balance = db.prepare(
    `SELECT closing_qty FROM batch_balances
      WHERE stock_item_name = ? AND batch_name = ? AND godown_name = ?`,
  ).get(opts.stockItemName, opts.boxSerial, opts.godown) as { closing_qty: number } | undefined;

  const onHand = balance?.closing_qty ?? 0;

  // Ceiling 2: other lines in this session drawing on the same box.
  const committed = (db.prepare(
    `SELECT COALESCE(SUM(qty),0) AS q FROM session_lines
      WHERE session_id = ? AND pid = ? AND box_serial = ? AND id IS NOT ?`,
  ).get(opts.sessionId, opts.pid, opts.boxSerial, opts.excludeLineId ?? -1) as { q: number }).q;

  const available = Math.max(0, onHand - committed);

  const soLine = db.prepare(
    `SELECT ordered_qty, delivered_qty FROM sales_order_lines
      WHERE voucher_number = ? AND stock_item_name = ?`,
  ).get(opts.salesOrder, opts.stockItemName) as
    { ordered_qty: number; delivered_qty: number } | undefined;

  const itemCommitted = (db.prepare(
    `SELECT COALESCE(SUM(qty),0) AS q FROM session_lines
      WHERE session_id = ? AND stock_item_name = ? AND id IS NOT ?`,
  ).get(opts.sessionId, opts.stockItemName, opts.excludeLineId ?? -1) as { q: number }).q;

  const orderPending = soLine
    ? Math.max(0, soLine.ordered_qty - soLine.delivered_qty - itemCommitted)
    : 0;

  if (!(opts.qty > 0)) {
    return { ok: false, error: 'Enter a quantity.', available, orderPending };
  }

  // Ceiling 1: the box. A hard block -- this is the one that must never pass.
  if (opts.qty - available > EPS) {
    return {
      ok: false,
      error: `Only ${fmt(available)} left in box ${tail(opts.boxSerial)}.`,
      available, orderPending,
    };
  }

  // Ceiling 3: the sales order. A warning, not a block -- deliberate
  // over-shipping within tolerance is a real thing. Flip to an error here if
  // the business wants it hard-blocked.
  if (opts.qty - orderPending > EPS) {
    return {
      ok: true,
      warning: `This exceeds what order ${opts.salesOrder} still has outstanding (${fmt(orderPending)}).`,
      available, orderPending,
    };
  }

  return { ok: true, available, orderPending };
}

// --- operator-facing copy ---------------------------------------------------

export function wrongBarcodeMessage(hint?: string): string {
  switch (hint) {
    case 'PID':
      return 'That is the product barcode. Scan the long serial barcode at the bottom.';
    case 'PART_NO':
      return 'That is the part-number barcode. Scan the long serial barcode at the bottom.';
    case 'QTY':
      return 'That is the quantity barcode. Scan the long serial barcode at the bottom.';
    case 'COO':
      return 'That is the country-of-origin barcode. Scan the long serial barcode at the bottom.';
    default:
      return 'Wrong barcode. Scan the long serial barcode at the bottom of the label.';
  }
}

export function rejectMessage(reason?: string): string {
  switch (reason) {
    case 'QTY_NOT_A_POSITIVE_INTEGER':
    case 'QTY_OUT_OF_RANGE':
      return 'The quantity on this label could not be read. Use manual entry.';
    case 'EMPTY_SERIAL':
    case 'SERIAL_LENGTH':
      return 'The box number on this label could not be read. Use manual entry.';
    case 'EMPTY_PID':
      return 'The product code on this label could not be read. Use manual entry.';
    case 'FIELD_COUNT':
      return 'This label is in an unfamiliar format. Use manual entry and report it.';
    default:
      return 'Label not recognised. Use manual entry if it is damaged.';
  }
}

/** Box serials are 16 digits; operators read and say the last few. */
export function tail(serial: string): string {
  return serial.length > 7 ? `…${serial.slice(-7)}` : serial;
}

export { boxKey };
export type { Resolved };
