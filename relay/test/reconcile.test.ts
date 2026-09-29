import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openDb } from '../src/db.ts';
import { reconcileHistory } from '../src/reconcile.ts';
const old='2026-09-29T08:00:00.000Z';
const t1='2026-09-29T09:00:00.000Z', t2='2026-09-29T09:02:00.000Z';
function fixture() {
 const db=openDb(':memory:');
 db.prepare(`INSERT INTO sessions(id,kind,company,godown,state,created_at,device_id) VALUES ('s','INCOMING','ACME','Main','POSTED',?,'phone')`).run(old);
 db.prepare(`INSERT INTO session_vouchers(voucher_key,session_id,stock_item_name,state,tally_voucher_id,created_at,completed_at) VALUES ('s#0','s','ITEM','POSTED','74',?,?)`).run(old,old);
 db.prepare(`INSERT INTO item_vouchers(company,godown,stock_item_name,tally_master_id,created_at,updated_at) VALUES ('ACME','Main','ITEM','74',?,?)`).run(old,old);
 for(const box of ['A','B']) {
  db.prepare('INSERT INTO session_lines(session_id,pid,box_serial,qty,stock_item_name,scanned_at) VALUES (?,?,?,?,?,?)').run('s','PID',box,1,'ITEM',old);
  db.prepare('INSERT INTO received_boxes VALUES (?,?,?,?,?)').run('PID',box,'s',1,old);
  db.prepare('INSERT INTO posted_batches(company,godown,stock_item_name,box_serial,qty,posted_at) VALUES (?,?,?,?,?,?)').run('ACME','Main','ITEM',box,1,old);
 }
 return db;
}
function apply(db:any,h:unknown,time=t1,company='ACME'){return reconcileHistory(db,company,time,h,new Date(time));}
function count(db:any,table:string){return db.prepare('SELECT count(*) n FROM '+table).get().n;}
test('deleted voucher is removed only after two exports; duplicate memory and mobile history are cleared',()=>{
 const db=fixture();try {
 assert.equal(apply(db,[]),0);assert.equal(count(db,'received_boxes'),2);
 assert.equal(apply(db,[]),0); // replay isn't a second observation
 assert.equal(apply(db,[],t2),2);
 for(const table of ['sessions','session_lines','session_vouchers','received_boxes','posted_batches','item_vouchers']) assert.equal(count(db,table),0,table);
 assert.equal(count(db,'tally_history_removals'),2);
 }finally{db.close();}
});
test('deleting one batch keeps the voucher, other box and receipt',()=>{
 const db=fixture();try{
 const h=[{masterId:'74',narration:'',batches:[{item:'ITEM',godown:'Main',box:'B'}]}];
 apply(db,h);assert.equal(apply(db,h,t2),1);
 assert.equal(count(db,'sessions'),1);assert.equal(count(db,'item_vouchers'),1);
 assert.deepEqual(db.prepare('SELECT box_serial FROM received_boxes').all(),[{box_serial:'B'}]);
 }finally{db.close();}
});
test('unavailable, malformed, stale, wrong-company and in-flight snapshots cannot erase history',()=>{
 for(const mode of ['absent','malformed','stale','wrong-company','pending','recent']) {
 const db=fixture();try{
 let h:any=[],company='ACME',time=t1;
 if(mode==='absent') h=undefined;
 if(mode==='malformed')h=[{masterId:'74'}];
 if(mode==='wrong-company') company='OTHER';
 if(mode==='pending')db.prepare("UPDATE sessions SET state='POSTING'").run();
 if(mode==='recent')db.prepare('UPDATE session_vouchers SET completed_at=?').run(t2);
 if(mode==='stale')time=old;
 reconcileHistory(db,company,time,h,new Date(t1));
 reconcileHistory(db,company,mode==='stale'?old:t2,h,new Date(t2));
 assert.equal(count(db,'received_boxes'),2,mode);
 }finally{db.close();}
 }
});
test('a voucher reappearing cancels the missing confirmation; absent inventory details do not imply deletion',()=>{
 const db=fixture();try{
 apply(db,[]);
 apply(db,[{masterId:'74',narration:'',batches:[]}],t2);
 assert.equal(count(db,'tally_history_missing'),0);
 assert.equal(apply(db,[],'2026-09-29T09:04:00.000Z'),0);
 assert.equal(count(db,'received_boxes'),2);
 }finally{db.close();}
});
test('a recently changed standing voucher cannot erase new work',()=>{
 const db=fixture();try{
 apply(db,[]);
 db.prepare('UPDATE item_vouchers SET updated_at=?').run(t2);
 assert.equal(apply(db,[],t2),0);
 assert.equal(count(db,'received_boxes'),2);
 }finally{db.close();}
});
