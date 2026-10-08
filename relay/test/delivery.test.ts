import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deliveryId, supportsDurableRetry } from '../src/delivery.ts';
test('retry delivery is stable across reconnects and separate from voucher identity', () => {
  assert.equal(deliveryId('s#0', 0), 's#0');
  assert.equal(deliveryId('s#0', 1), 's#0:retry:1');
  assert.equal(deliveryId('s#0', 2), 's#0:retry:2');
});
test('old and unrecognised connectors cannot silently accept unsupported retries', () => {
  for (const v of ['', 'dev', '1.14.0', '1.15.0']) assert.equal(supportsDurableRetry(v), false, v);
  for (const v of ['1.15.1', '1.16.0', '2.0.0']) assert.equal(supportsDurableRetry(v), true, v);
});
