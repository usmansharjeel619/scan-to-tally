/**
 * Creating a product nobody described.
 *
 * Kept out of the HTTP layer so it can be exercised directly: importing the
 * server starts a listener and opens a database, which is no way to test the
 * rule that decides whether a scanned carton reaches Tally at all.
 */

import { audit, catalogueLookup, nowIso, type DB } from './db.ts';
import { composeItemName, pidVariants } from './fragment.ts';

/**
 * The unit a new item is created with, learned from this company's own books.
 *
 * Tally refuses a unit the company has not defined, and the symbol differs
 * between sets of books -- "Nos" here, "NO" or "PCS" elsewhere. A hardcoded
 * guess has already failed in the field ("Unit 'NO' does not exist!"), taking
 * the whole voucher with it, so the default is whatever this company already
 * uses for most of its stock.
 */
export function unitForNewItems(db: DB, fallback: string): string {
  const row = db.prepare(
    `SELECT base_units AS u, COUNT(*) AS n FROM stock_items
      WHERE TRIM(base_units) <> '' GROUP BY base_units ORDER BY n DESC, u LIMIT 1`,
  ).get() as { u: string } | undefined;
  return row?.u || fallback;
}

/** What the price list calls this part number, under either spelling. */
export function catalogueDescriptionFor(db: DB, pid: string): string {
  for (const form of pidVariants(pid)) {
    const entry = catalogueLookup(db, form);
    if (entry?.description) return entry.description;
  }
  return '';
}

/**
 * Makes sure every unresolved line on a session has a product on its way.
 *
 * A SCANNED BOX ALWAYS BECOMES STOCK. That is the rule this enforces, and it
 * is not negotiable: the carton is on the shelf whether or not anybody typed a
 * description for it, so Tally has to be told about it either way.
 *
 * Until now an unknown product was only ever created if the operator answered
 * the "what is it?" prompt. Skip that -- at the end of a shift, on the
 * fifteenth carton, or with a stray tap -- and no proposal was made, so the
 * line stayed unresolved for ever and held its whole receipt back. Three boxes
 * (79 units) sat exactly like that in the live relay, waiting on something
 * that nothing in the system was ever going to do.
 *
 * So the submit makes the proposal itself, from the best name available: the
 * price list's wording when it knows the part, otherwise the part number
 * alone. A bare part number is an ugly item name and an honest one -- it says
 * exactly what was known -- and it can be described properly in Tally
 * afterwards, which is a five-second edit. Stock that never arrived cannot be
 * fixed at all.
 *
 * Returns the part numbers now waiting to be created, in scan order.
 */
export function ensureProposalsFor(
  db: DB, sessionId: string, by: string, fallbackUnit: string,
): string[] {
  const rows = db.prepare(
    `SELECT pid, MIN(id) AS first_seen FROM session_lines
      WHERE session_id = ? AND stock_item_name = '' AND TRIM(pid) <> ''
      GROUP BY pid ORDER BY first_seen`,
  ).all(sessionId) as Array<{ pid: string }>;

  const pids: string[] = [];
  for (const { pid } of rows) {
    const existing = db.prepare(`SELECT state FROM proposed_items WHERE pid = ?`)
      .get(pid) as { state: string } | undefined;

    if (!existing) {
      // Whatever is known, and nothing invented. An operator who described it
      // has already been through the propose endpoint and is not overwritten
      // here -- their wording is better than anything this can work out.
      const description = catalogueDescriptionFor(db, pid);
      const name = composeItemName(pid, description);
      db.prepare(`
        INSERT INTO proposed_items
          (pid, name, description, base_units, batchwise, track_mfg, state,
           proposed_by, proposed_at, session_id, raw_payload)
        VALUES (?,?,?,?,1,1,'PENDING',?,?,?,'')`)
        .run(pid, name, description, unitForNewItems(db, fallbackUnit), by,
          nowIso(), sessionId);
      audit(db, by, 'ITEM_AUTO_PROPOSED', pid, name);
    } else if (existing.state === 'FAILED') {
      // A submit is the operator asking again. A creation that failed because
      // Tally was mid-backup must not strand the boxes for ever; one that
      // fails for a real reason simply fails again, in Tally's own words.
      db.prepare(`UPDATE proposed_items SET state='PENDING', error='' WHERE pid = ?`)
        .run(pid);
    }

    pids.push(pid);
  }
  return pids;
}
