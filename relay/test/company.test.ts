/**
 * Counting into the wrong company.
 *
 * Northwind runs more than one company in Tally. A handset is set up against one
 * of them, and everything it scans belongs there. If that pairing ever comes
 * apart -- the connector repointed, a second company opened, a phone brought
 * over from another site -- the goods still arrive, the scans still beep, and
 * the vouchers still post. They just post somewhere nobody is looking.
 *
 * Nothing about that is visible afterwards: the voucher looks entirely ordinary
 * in the company that received it, and the company that should have received it
 * simply has no record of the delivery. This is the endpoint that has to catch
 * the case the handset cannot -- a pallet scanned with no signal, drained from
 * the outbox long after the mismatch appeared.
 */
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

process.env.STT_DB = ':memory:';
process.env.STT_PORT = '0';
process.env.STT_CONNECTOR_SECRET = 'test-secret';
process.env.LOG_LEVEL = 'silent';

const { app, db } = await import('../src/server.ts');
const { nowIso } = await import('../src/db.ts');

const TOKEN = 'device-token-for-company-tests';
const GODOWN = 'Main Location';
const OURS = 'Northwind Trading LLC';
const auth = { authorization: `Bearer ${TOKEN}` };

before(() => app.ready());
after(() => app.close());

beforeEach(() => {
  for (const t of ['session_lines', 'sessions', 'devices']) {
    db.prepare(`DELETE FROM ${t}`).run();
  }
  db.prepare(`INSERT INTO devices (id, name, company, godown, token_hash, operator, created_at)
              VALUES ('dock-1','Dock 1',?,?,?,'Tester',?)`)
    .run(OURS, GODOWN, createHash('sha256').update(TOKEN).digest('hex'), nowIso());
});

const open = (body: Record<string, unknown>) =>
  app.inject({ method: 'POST', url: '/api/v1/sessions', headers: auth, body });

test('a count raised against another company is refused', async () => {
  const r = await open({ kind: 'INCOMING', company: 'Northwind Ltd' });

  assert.equal(r.statusCode, 409);
  const body = r.json();
  assert.equal(body.error, 'wrong_company');
  // Both names, or whoever reads it cannot tell which way round the mistake is.
  assert.match(body.message, /Northwind Ltd/);
  assert.match(body.message, new RegExp(OURS));

  assert.equal(
    (db.prepare(`SELECT COUNT(*) n FROM sessions`).get() as any).n, 0,
    'a refused count must leave nothing behind to be posted later',
  );
});

test('the right company opens normally', async () => {
  const r = await open({ kind: 'INCOMING', company: OURS });
  assert.equal(r.statusCode, 200);
  assert.equal((db.prepare(`SELECT COUNT(*) n FROM sessions`).get() as any).n, 1);
});

/**
 * Case is not a mismatch. Tally is not consistent about how it spells a name
 * back, and refusing over a capital letter would stop a warehouse for nothing
 * -- which is how a safeguard teaches people to work around it.
 */
test('case alone does not refuse a count', async () => {
  const r = await open({ kind: 'INCOMING', company: OURS.toUpperCase() });
  assert.equal(r.statusCode, 200);
});

/**
 * An older app does not send the company at all. It must keep working: a
 * safeguard that bricks every handset that has not been updated yet is not one
 * anybody can deploy.
 */
test('an app that sends no company still works', async () => {
  const r = await open({ kind: 'INCOMING' });
  assert.equal(r.statusCode, 200);
  assert.equal(
    (db.prepare(`SELECT company FROM sessions`).get() as any).company, OURS,
    'and the session is still filed under the company the device is registered to',
  );
});
