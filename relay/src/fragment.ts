/**
 * A scan that is ONE FIELD of a multi-barcode label rather than a whole box.
 *
 * Mirrors connector/internal/barcode/fragment.go and the Kotlin of the same
 * name. All three run contracts/barcode-vectors.json; if they disagree, a
 * barcode means two different things in two places and stock drifts.
 */

export type FragmentKind = 'PRODUCT' | 'BOX' | 'QUANTITY' | 'NOT_MINE';
export type FragmentHint = 'PART_NO' | 'QTY' | undefined;

export interface Fragment {
  kind: FragmentKind;
  /** The cleaned payload. Set for PRODUCT and BOX only. */
  value?: string;
  hint?: FragmentHint;
  raw: string;
}

/** Four digits, dash, four digits, on every label examined. */
const PRODUCT = /^[0-9]{4}-[0-9]{4}$/;

/** A Simplex serial scanned on its own rather than inside the long code. */
const SERIAL16 = /^[0-9]{16}$/;

/**
 * A Tyco box id: letters THEN digits -- HFE283, HKT710, HJO761.
 *
 * The ordering is load bearing. A Simplex part no also mixes letters and
 * digits ("0677197CN") but digits first, so a looser "contains both" rule
 * would file a part number as a box id and invent a batch no carton carries.
 */
const BOX_ID = /^[A-Z]{2,4}[0-9]{3,6}$/;

const DIGITS = /^[0-9]+$/;

/**
 * Decides which slot a lone barcode belongs in.
 *
 * It never guesses. On a Tyco label "Week No 17" and "Quantity 20" are both
 * two-digit barcodes and nothing in the payload separates them, so every bare
 * number is refused and the quantity is typed instead.
 */
export function classifyFragment(raw: string): Fragment {
  const v = String(raw ?? '').trim().toUpperCase();

  if (v === '') return { kind: 'NOT_MINE', raw };
  if (PRODUCT.test(v)) return { kind: 'PRODUCT', value: v, raw };
  if (SERIAL16.test(v)) return { kind: 'BOX', value: v, raw };
  if (BOX_ID.test(v)) return { kind: 'BOX', value: v, raw };

  if (DIGITS.test(v)) {
    // Length only picks the wording of the refusal, never whether to accept.
    return { kind: 'NOT_MINE', hint: v.length <= 4 ? 'QTY' : 'PART_NO', raw };
  }
  if (/[0-9]/.test(v)) return { kind: 'NOT_MINE', hint: 'PART_NO', raw };

  return { kind: 'NOT_MINE', raw };
}

// --- targeted scanning ------------------------------------------------------

/**
 * The field the operator asked for BEFORE scanning.
 *
 * Blind scanning must place a barcode by shape alone, which is why so much of a
 * label is refused: a quantity, a week number and an issue number are the same
 * shape, and an 8-digit part number is the same shape as a date code.
 *
 * When the operator taps "scan the product number" first, the slot stops being
 * a guess. Only the shape has to fit, and fields that can never be placed
 * blind become safe to scan.
 */
export type Slot = 'PRODUCT' | 'BOX' | 'QUANTITY';

const EIGHT_DIGITS = /^[0-9]{8}$/;

/** A scanned carton count beyond this is a misread or a serial. */
const MAX_SCANNED_QTY = 9999;

const DASHED_PID = /^[0-9]{4}-[0-9]{4}$/;

/**
 * The spellings a part number might be filed under.
 *
 * Some cartons drop the dash. It is the same product either way, so a lookup
 * tries both -- but the SCANNED form is what gets stored. Rewriting the payload
 * would put a part number in the audit trail that was never on the carton, and
 * would stop matching an item already in Tally under the other spelling.
 *
 * The scanned form is always first.
 */
export function pidVariants(v: string): string[] {
  const s = String(v ?? '').trim();
  if (EIGHT_DIGITS.test(s)) return [s, `${s.slice(0, 4)}-${s.slice(4)}`];
  if (DASHED_PID.test(s)) return [s, s.slice(0, 4) + s.slice(5)];
  return [s];
}

/**
 * Places a barcode into a slot the operator named.
 *
 * A payload that does not fit is still refused rather than coerced: scanning a
 * box serial into the quantity slot must fail, not become a quantity of
 * 1124241658336425.
 */
export function classifyFragmentFor(raw: string, want: Slot): Fragment {
  const v = String(raw ?? '').trim().toUpperCase();
  if (v === '') return { kind: 'NOT_MINE', raw };

  switch (want) {
    case 'PRODUCT':
      if (PRODUCT.test(v) || EIGHT_DIGITS.test(v)) {
        // Stored as scanned; reconciling the dash is the lookup's job.
        return { kind: 'PRODUCT', value: v, raw };
      }
      return { kind: 'NOT_MINE', hint: 'PART_NO', raw };

    case 'BOX':
      if (SERIAL16.test(v) || BOX_ID.test(v)) return { kind: 'BOX', value: v, raw };
      return { kind: 'NOT_MINE', raw };

    case 'QUANTITY': {
      // Deliberately narrow: a quantity is a small positive number, and
      // anything longer is a serial or a date pointed at by mistake.
      if (!DIGITS.test(v) || v.length > 4) return { kind: 'NOT_MINE', hint: 'QTY', raw };
      const n = Number(v);
      if (!(n > 0) || n > MAX_SCANNED_QTY) return { kind: 'NOT_MINE', hint: 'QTY', raw };
      return { kind: 'QUANTITY', value: v, raw };
    }

    default:
      // An unknown slot falls back to blind rather than accepting on a typo.
      return classifyFragment(raw);
  }
}

/** Why a targeted scan did not fit the field the operator asked for. */
export function refusedForSlot(want: Slot): string {
  switch (want) {
    case 'PRODUCT': return 'That is not a part number. Scan the code printed under PID, Type or Part.';
    case 'BOX': return 'That is not a box number. Scan the long serial, or the box id.';
    case 'QUANTITY': return 'That is not a quantity. Scan the number printed under QTY, or type it.';
    default: return 'That barcode does not belong in this field.';
  }
}
