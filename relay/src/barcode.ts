/**
 * Barcode parsing, mirrored from connector/internal/barcode.
 *
 * Three implementations exist (Go here in the connector, TypeScript here,
 * Kotlin on the device) and all three are driven by the SAME fixture file,
 * contracts/barcode-vectors.json. If they ever disagree, a label means two
 * different things in two places and stock quietly drifts.
 *
 * The relay parses independently rather than trusting the device: a scan line
 * arriving over HTTP is untrusted input, and the quantity on it becomes stock.
 */

import { normalisePid } from './fragment.ts';

export type Outcome = 'ACCEPT' | 'WRONG_BARCODE' | 'REJECT' | 'UNKNOWN';

/** Which of the label's other barcodes was scanned, so the UI can redirect. */
export type Hint = 'PID' | 'PART_NO' | 'COO' | 'QTY';

export type Reason =
  | 'QTY_NOT_A_POSITIVE_INTEGER'
  | 'QTY_OUT_OF_RANGE'
  | 'EMPTY_SERIAL'
  | 'EMPTY_PID'
  | 'SERIAL_LENGTH'
  | 'FIELD_COUNT';

export interface ParsedBox {
  /** Join key to a Tally stock item, resolved via the item master. */
  pid: string;
  /** Becomes the Tally batch name verbatim -- no transform. */
  boxSerial: string;
  qty: number;
  firmware: string | null;
  /** ISO date derived from the serial prefix, or null. Advisory. */
  mfgDate: string | null;
}

export interface ParseResult {
  outcome: Outcome;
  parser?: string;
  /** The untouched payload. Stored on every scan line, forever. */
  raw: string;
  symbology?: string;
  box?: ParsedBox;
  hint?: Hint;
  reason?: Reason;
  confidence?: number;
}

/** Sanity bounds against a misread, not business rules. */
export const MIN_SERIAL_LEN = 8;
export const MAX_SERIAL_LEN = 32;
export const MAX_QTY = 10000;

/**
 * The duplicate key is (product, box), never the serial alone.
 *
 * Tally scopes a batch under a stock item, so this pair is its natural key too:
 * the same box number under a different product is a different box, and the
 * same part across different boxes is the normal case, not an error.
 */
export function boxKey(pid: string, boxSerial: string): string {
  return `${pid}${boxSerial}`;
}

/**
 * Reads the serial's leading MMDDYY as a manufacturing date.
 *
 * On the reference label, serial 1124241658336425 begins 112424 = 24 Nov 2024,
 * which the same label independently prints as the Julian "24 329". Two fields
 * agreeing is why the structure is trustworthy -- but only as far as returning
 * null the instant it is not a real date. A wrong date is worse than no date.
 */
export function mfgDateFromSerial(serial: string): string | null {
  if (serial.length < 6) return null;
  const head = serial.slice(0, 6);
  if (!/^\d{6}$/.test(head)) return null;

  const mm = Number(head.slice(0, 2));
  const dd = Number(head.slice(2, 4));
  const yy = Number(head.slice(4, 6));
  if (mm < 1 || mm > 12 || dd < 1 || dd > 31) return null;

  const year = 2000 + yy;
  const d = new Date(Date.UTC(year, mm - 1, dd));
  // Date normalises overflow (31 Feb -> 2 Mar), so round-trip to reject dates
  // that never existed.
  if (d.getUTCMonth() !== mm - 1 || d.getUTCDate() !== dd) return null;
  return d.toISOString().slice(0, 10);
}

export interface Parser {
  name: string;
  /** 0 = not recognised; otherwise a confidence in (0,1]. Must not throw. */
  probe(symbology: string, raw: string): number;
  parse(symbology: string, raw: string): ParseResult;
}

/**
 * The long SERIAL# code on a Simplex carton:
 *
 *   4098-9792|1124241658336425|18|
 *      PID          SERIAL      QTY  FIRMWARE (empty on passive devices)
 *
 * Validates STRUCTURE, not the PID's shape. "4098-9792" happens to look like
 * \d{4}-\d{4}, but other Simplex and JCI lines do not all match, and an
 * over-strict pattern rejects good boxes at the dock.
 */
export const simplexPipe: Parser = {
  name: 'simplex-pipe',

  probe(_symbology, raw) {
    return raw.includes('|') ? 0.9 : 0;
  },

  parse(symbology, raw) {
    const base: ParseResult = {
      outcome: 'REJECT', parser: 'simplex-pipe', raw, symbology, confidence: 0.9,
    };

    const fields = raw.trim().split('|').map((f) => f.trim());

    // Three fields (no trailing delimiter) or four (with it, firmware possibly
    // empty). Anything else is a dialect we do not know, and guessing at a
    // quantity is the one mistake that must never be made.
    if (fields.length < 3 || fields.length > 4) {
      return { ...base, reason: 'FIELD_COUNT' };
    }

    const pid = fields[0] ?? '';
    const serial = fields[1] ?? '';
    const qtyStr = fields[2] ?? '';

    if (pid === '') return { ...base, reason: 'EMPTY_PID' };
    if (serial === '') return { ...base, reason: 'EMPTY_SERIAL' };
    if (serial.length < MIN_SERIAL_LEN || serial.length > MAX_SERIAL_LEN) {
      return { ...base, reason: 'SERIAL_LENGTH' };
    }

    // Number() accepts "1.5" and " 18 "; require a plain positive integer.
    if (!/^\d+$/.test(qtyStr)) return { ...base, reason: 'QTY_NOT_A_POSITIVE_INTEGER' };
    const qty = Number(qtyStr);
    if (!Number.isInteger(qty) || qty <= 0) {
      return { ...base, reason: 'QTY_NOT_A_POSITIVE_INTEGER' };
    }
    if (qty > MAX_QTY) return { ...base, reason: 'QTY_OUT_OF_RANGE' };

    const firmware = fields.length === 4 && fields[3] !== '' ? (fields[3] as string) : null;

    return {
      outcome: 'ACCEPT', parser: 'simplex-pipe', raw, symbology, confidence: 0.9,
      // Some cartons drop the dash from the part number. Its position in the
      // payload makes it unambiguous here, so it normalises to the canonical
      // dashed form and resolves to one catalogue entry either way.
      box: { pid: normalisePid(pid), boxSerial: serial, qty, firmware,
             mfgDate: mfgDateFromSerial(serial) },
    };
  },
};

const RE_PID = /^\d{4}-\d{4}$/;
const RE_PART_NO = /^\d{6,8}[A-Z]{2}$/;
const RE_COO = /^[A-Z]{2}$/;
const RE_QTY = /^\d{1,4}$/;

function hintFor(s: string): Hint | null {
  if (RE_PID.test(s)) return 'PID';
  if (RE_PART_NO.test(s)) return 'PART_NO';
  if (RE_COO.test(s)) return 'COO';
  if (RE_QTY.test(s)) return 'QTY';
  return null;
}

/**
 * Recognises the PID, part-number, country-of-origin and quantity barcodes
 * printed alongside the long one.
 *
 * It never produces a box. Its entire job is to let the app say "that's the
 * product barcode -- scan the long serial barcode at the bottom" instead of
 * failing generically. There are five codes on that label and operators will
 * sometimes hit the wrong one.
 */
export const simplexShort: Parser = {
  name: 'simplex-short',
  probe(_symbology, raw) {
    return hintFor(raw.trim()) === null ? 0 : 0.5;
  },
  parse(symbology, raw) {
    return {
      outcome: 'WRONG_BARCODE', parser: 'simplex-short', raw, symbology,
      hint: hintFor(raw.trim()) ?? undefined, confidence: 0.5,
    };
  },
};

export class BarcodeRegistry {
  private parsers: Parser[];

  constructor(parsers: Parser[] = [simplexPipe, simplexShort]) {
    this.parsers = parsers;
  }

  register(p: Parser): void {
    this.parsers.push(p);
  }

  /**
   * Routes a raw payload to the most confident parser. An unrecognised payload
   * comes back UNKNOWN rather than being coerced into a best guess.
   */
  parse(symbology: string, raw: string): ParseResult {
    // Scanners routinely append CR/LF; manual entry brings its own spaces.
    const cleaned = raw.trim();
    if (cleaned === '') return { outcome: 'UNKNOWN', raw, symbology };

    let best: Parser | null = null;
    let bestScore = 0;
    for (const p of this.parsers) {
      const s = p.probe(symbology, cleaned);
      if (s > bestScore) {
        best = p;
        bestScore = s;
      }
    }
    if (!best) return { outcome: 'UNKNOWN', raw, symbology };

    return { ...best.parse(symbology, cleaned), raw }; // the untouched payload, always
  }
}

export const registry = new BarcodeRegistry();
