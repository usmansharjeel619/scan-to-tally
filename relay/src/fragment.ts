/**
 * A scan that is ONE FIELD of a multi-barcode label rather than a whole box.
 *
 * Mirrors connector/internal/barcode/fragment.go and the Kotlin of the same
 * name. All three run contracts/barcode-vectors.json; if they disagree, a
 * barcode means two different things in two places and stock drifts.
 */

import { MAX_SERIAL_LEN } from './barcode.ts';

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
/**
 * A dashed part number.
 *
 * Simplex's own are nnnn-nnnn, but the same shelves carry JCI, Tyco, Apollo
 * and KAC cartons, and theirs carry LETTERS: a revision suffix (4090-9001B),
 * Apollo's 55000-390APO. A pattern that accepts only nnnn-nnnn refuses those
 * outright, and a part number the app refuses is a carton that does not get
 * counted -- which is the one outcome worth avoiding.
 *
 * Still only the DASHED form here, because this is the blind path, which never
 * guesses. Undashed codes stay ambiguous against a box id until the operator
 * says which field they are filling. A three-digit head stays out too: the
 * Simplex Mexico supplier ref "742-949" is nnn-nnn and is not a part number.
 */
const PRODUCT = /^[0-9]{4,6}-[0-9]{2,5}[A-Z]{0,4}$/;

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

/**
 * The part number, which is NOT the PID and must never be taken for one.
 *
 * A Simplex carton prints both: PID 2084-9009 and part no 0635484, inches
 * apart, and only the PID identifies the product in Tally. Reading the part
 * number instead creates a second product called "0635484" with no
 * description, holding stock that belongs to the real one -- which is exactly
 * what happened on the dock.
 *
 * Eight bare digits are the one genuine ambiguity, and those go to the PID:
 * that spelling is on real cartons and is what resolves.
 */
const DASHLESS_PID = /^[0-9]{8}$/;
const PART_NUMBER = /^[0-9]{5,9}[A-Z]{0,4}$/;

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
 * The form a part number is MATCHED on, never the form it is stored in.
 *
 * The same model arrives on cartons printed both 41009701 and 4100-9701.
 * Stored verbatim -- which is right, the audit trail should say what was on the
 * box -- those are two different strings, and a duplicate check keyed on them
 * would let the same physical box be received twice under the two spellings.
 * Identity collapses the dash; display and storage do not.
 */
export function canonicalPid(v: string): string {
  return String(v ?? '').trim().toUpperCase().replace(/-/g, '');
}

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
      // The operator has SAID this is the part number, so it is taken as one.
      // Whatever a supplier prints -- dashed, undashed, lettered, a code no
      // pattern here has ever seen -- is a part number if that is the field
      // being filled.
      //
      // Only the three things that certainly are NOT one are refused, and they
      // are refused because each is a barcode printed inches away on the same
      // label: the 16-digit box serial, the small bare number under QTY, and
      // the two-letter country of origin.
      // The eight-digit spelling of a PID, before anything mistakes it for a
      // part number.
      if (DASHLESS_PID.test(v)) return { kind: 'PRODUCT', value: v, raw };
      // The barcode printed beside the one they wanted. Redirected rather than
      // accepted: taking it would invent a product.
      if (PART_NUMBER.test(v)) return { kind: 'NOT_MINE', hint: 'PART_NO', raw };
      if (SERIAL16.test(v) || (DIGITS.test(v) && v.length <= 4) ||
          v.length < 3 || v.length > MAX_SERIAL_LEN) {
        return { kind: 'NOT_MINE', hint: 'PART_NO', raw };
      }
      // Stored as scanned; reconciling the dash is the lookup's job.
      return { kind: 'PRODUCT', value: v, raw };

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

/**
 * The name a newly created stock item gets.
 *
 * The live catalogue names items "<PID> <DESCRIPTION>", and that convention is
 * what makes a scanned part number resolvable later -- so it is composed the
 * same way rather than left to drift.
 *
 * But only ONCE. The description often already carries the part number, from
 * the price list or read off a carton that prints it above the name, and
 * prefixing regardless produced items called
 * "4098-5266 4098-5266 PHOTO SENSOR W/REED". Two spellings of one product is
 * two products as far as Tally is concerned, which is how one carton ends up on
 * two separate vouchers.
 *
 * Compared canonically: a carton may print 4098-5266 where the price list holds
 * 40985266, and either way the number is already there.
 */
export function composeItemName(pid: string, description: string): string {
  const desc = String(description ?? '').trim();
  const head = canonicalPid(desc.split(/\s+/)[0] ?? '');
  if (head !== '' && head === canonicalPid(pid)) return desc;
  return desc === '' ? String(pid ?? '').trim() : `${String(pid ?? '').trim()} ${desc}`;
}
