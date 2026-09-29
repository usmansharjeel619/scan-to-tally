import { audit, type DB } from './db.ts';

export interface VoucherHistory {
  masterId: string;
  narration: string;
  batches: Array<{ item: string; godown: string; box: string }>;
}

/** Two complete, fresh snapshots must agree before deleting warehouse history. */
export function reconcileHistory(db: DB, company: string, startedAt: unknown, history: unknown, now = new Date()): number {
  if (!company || typeof startedAt !== 'string' || !Array.isArray(history)) return 0;
  const start = Date.parse(startedAt);
  // Reject stale snapshots, clock errors and snapshots predating a new posting.
  if (!Number.isFinite(start) || start > now.getTime() + 5000 || start < now.getTime() - 300_000) return 0;
  const ids = new Map<string, VoucherHistory>();
  for (const v of history) {
    if (!v || typeof v.masterId !== 'string' || !/^\d+$/.test(v.masterId) || ids.has(v.masterId) ||
        typeof v.narration !== 'string' || !Array.isArray(v.batches) ||
        v.batches.some((b: any) => !b || typeof b.item !== 'string' || !b.item ||
          typeof b.godown !== 'string' || !b.godown || typeof b.box !== 'string' || !b.box)) return 0;
    ids.set(v.masterId, v);
  }
  return db.transaction(() => {
    const previous = db.prepare('SELECT snapshot_at FROM tally_history_sync WHERE company=?').get(company) as any;
    if (previous && Date.parse(previous.snapshot_at) >= start) return 0;
    // A write may be in Tally but not yet acknowledged. Never reconcile through it.
    if (db.prepare("SELECT 1 FROM sessions WHERE company=? AND state IN ('QUEUED','POSTING') LIMIT 1").get(company)) return 0;
    db.prepare('INSERT INTO tally_history_sync(company,snapshot_at) VALUES (?,?) ON CONFLICT(company) DO UPDATE SET snapshot_at=excluded.snapshot_at').run(company, startedAt);
    const rows = db.prepare(`SELECT v.voucher_key,v.session_id,v.stock_item_name,v.tally_voucher_id,
      v.completed_at,s.device_id,s.godown FROM session_vouchers v JOIN sessions s ON s.id=v.session_id
      WHERE s.company=? AND v.state='POSTED' AND s.state='POSTED'`).all(company) as any[];
    let removed = 0;
    const active = new Set<string>();
    for (const row of rows) {
      if (!row.completed_at || Date.parse(row.completed_at) >= start - 30_000) continue;
      const standing = db.prepare(`SELECT updated_at,marker FROM item_vouchers WHERE company=? AND godown=?
        AND stock_item_name=? AND tally_master_id=?`).get(company,row.godown,row.stock_item_name,row.tally_voucher_id) as any;
      if (standing && Date.parse(standing.updated_at) >= start - 30_000) continue;
      const exported = ids.get(row.tally_voucher_id);
      // Reusing a numeric ID after a restore does not resurrect our old voucher.
      const live = exported && (!standing?.marker || exported.narration.includes(standing.marker)) ? exported : undefined;
      const lines = db.prepare('SELECT id,pid,box_serial,stock_item_name FROM session_lines WHERE session_id=? AND stock_item_name=?').all(row.session_id,row.stock_item_name) as any[];
      for (const line of lines) {
        const key = JSON.stringify([row.voucher_key,line.id]);
        active.add(key);
        // A present voucher without inventory details is not proof of box deletion.
        const missing = !live || (live.batches.length > 0 && !live.batches.some(b=>b.item===line.stock_item_name && b.godown===row.godown && b.box===line.box_serial));
        if (!missing) {
          db.prepare('DELETE FROM tally_history_missing WHERE company=? AND record_key=?').run(company,key);
          continue;
        }
        const first = db.prepare('SELECT observed_at FROM tally_history_missing WHERE company=? AND record_key=?').get(company,key) as any;
        if (!first) {
          db.prepare('INSERT INTO tally_history_missing(company,record_key,observed_at) VALUES (?,?,?)').run(company,key,startedAt);
          continue;
        }
        // Separate observations, not two messages for one export.
        if (start - Date.parse(first.observed_at) < 30_000) continue;
        db.prepare(`INSERT OR IGNORE INTO tally_history_removals(company,device_id,session_id,stock_item_name,box_serial,removed_at)
          VALUES (?,?,?,?,?,?)`).run(company,row.device_id,row.session_id,line.stock_item_name,line.box_serial,now.toISOString());
        db.prepare('DELETE FROM received_boxes WHERE session_id=? AND pid=? AND box_serial=?').run(row.session_id,line.pid,line.box_serial);
        db.prepare('DELETE FROM despatched_boxes WHERE session_id=? AND pid=? AND box_serial=?').run(row.session_id,line.pid,line.box_serial);
        // A newer standing voucher must not be changed by an older receipt's deletion.
        db.prepare(`DELETE FROM posted_batches WHERE company=? AND godown=? AND stock_item_name=? AND box_serial=?
          AND EXISTS (SELECT 1 FROM item_vouchers WHERE company=? AND godown=? AND stock_item_name=? AND tally_master_id=?)`)
          .run(company,row.godown,line.stock_item_name,line.box_serial,company,row.godown,line.stock_item_name,row.tally_voucher_id);
        db.prepare('DELETE FROM session_lines WHERE id=?').run(line.id);
        db.prepare('DELETE FROM tally_history_missing WHERE company=? AND record_key=?').run(company,key);
        removed++;
      }
      if (!db.prepare('SELECT 1 FROM session_lines WHERE session_id=? AND stock_item_name=?').get(row.session_id,row.stock_item_name)) {
        db.prepare('DELETE FROM session_vouchers WHERE voucher_key=?').run(row.voucher_key);
      }
      if (!db.prepare('SELECT 1 FROM session_lines WHERE session_id=?').get(row.session_id)) {
        db.prepare('DELETE FROM sessions WHERE id=?').run(row.session_id);
      }
    }
    // Discard confirmations for records that disappeared or were superseded.
    for (const r of db.prepare('SELECT record_key FROM tally_history_missing WHERE company=?').all(company) as any[]) {
      if (!active.has(r.record_key)) db.prepare('DELETE FROM tally_history_missing WHERE company=? AND record_key=?').run(company,r.record_key);
    }
    if (removed) {
      db.prepare(`DELETE FROM item_vouchers WHERE company=? AND NOT EXISTS
        (SELECT 1 FROM posted_batches p WHERE p.company=item_vouchers.company AND p.godown=item_vouchers.godown AND p.stock_item_name=item_vouchers.stock_item_name)`)
        .run(company);
      audit(db,'connector','TALLY_HISTORY_REMOVED',company,`${removed} scan line(s) removed after two complete Tally exports`);
    }
    return removed;
  })();
}
