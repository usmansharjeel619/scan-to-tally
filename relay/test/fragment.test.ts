/**
 * The SAME golden file the Go and Kotlin suites run. If one disagrees, a
 * barcode means two different things in two places and stock drifts.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { classifyFragment, classifyFragmentFor } from '../src/fragment.ts';

const contract = JSON.parse(
  readFileSync(new URL('../../contracts/barcode-vectors.json', import.meta.url), 'utf8'),
);

test('fragment golden vectors', () => {
  assert.ok(contract.fragments?.length, 'no fragment vectors; the contract is not being read');

  for (const v of contract.fragments) {
    const got = v.want ? classifyFragmentFor(v.raw, v.want) : classifyFragment(v.raw);
    assert.equal(got.kind, v.kind, `${v.name}: kind for ${JSON.stringify(v.raw)}`);
    if (v.value) assert.equal(got.value, v.value, `${v.name}: value`);
    if (v.kind !== 'NOT_MINE') assert.ok(got.value, `${v.name}: a placed fragment must carry its value`);
    if (v.hint) assert.equal(got.hint, v.hint, `${v.name}: hint`);
  }
});

test('a part number is never filed as a box id', () => {
  // Both can mix letters and digits; only the ordering separates them. Getting
  // this wrong invents a batch that no carton physically carries.
  for (const partNo of ['0677197CN', '0677104', '0746234', '742-949']) {
    assert.notEqual(classifyFragment(partNo).kind, 'BOX', partNo);
  }
});

test('no bare number is ever accepted', () => {
  // Week No 17 and Quantity 20 are the same shape. Accepting either would put
  // stock wrong with nothing on screen to show for it.
  for (const n of ['1', '5', '17', '20', '24', '35', '100', '2825', '23205', '26154']) {
    assert.equal(classifyFragment(n).kind, 'NOT_MINE', n);
  }
});

test('every box id seen on a real carton is recognised', () => {
  for (const [raw, want] of [
    ['HFE283', 'HFE283'], ['HKT710', 'HKT710'], ['HJO761', 'HJO761'],
    ['1120181448458237', '1120181448458237'], ['  hfe283 ', 'HFE283'],
  ] as const) {
    const got = classifyFragment(raw);
    assert.equal(got.kind, 'BOX', raw);
    assert.equal(got.value, want);
  }
});

test('every part number seen on a real carton is recognised', () => {
  for (const pid of ['4098-9788', '4100-3206', '4098-5266', '4098-5220',
                     '4098-5209', '4099-5208', '4098-9019']) {
    const got = classifyFragment(pid);
    assert.equal(got.kind, 'PRODUCT', pid);
    assert.equal(got.value, pid);
  }
});
