/**
 * A scan that is ONE FIELD of a multi-barcode label rather than a whole box.
 *
 * Mirrors connector/internal/barcode/fragment.go and the Kotlin of the same
 * name. All three run contracts/barcode-vectors.json; if they disagree, a
 * barcode means two different things in two places and stock drifts.
 */

export type FragmentKind = 'PRODUCT' | 'BOX' | 'NOT_MINE';
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
