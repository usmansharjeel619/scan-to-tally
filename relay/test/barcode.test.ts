import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registry, boxKey } from '../src/barcode.ts';

/**
 * The SAME fixtures the Go suite runs. Both must agree, or a label means two
 * different things in two places.
 */
const vectors = JSON.parse(
  readFileSync(new URL('../../contracts/barcode-vectors.json', import.meta.url), 'utf8'),
) as {
  vectors: Array<{
    name: string; raw: string; symbology: string; outcome: string;
    expect: Record<string, unknown>;
  }>;
};

test('shared vectors', async (t) => {
  assert.ok(vectors.vectors.length > 0, 'no vectors loaded');

  for (const v of vectors.vectors) {
    await t.test(v.name, () => {
      const got = registry.parse(v.symbology, v.raw);
      assert.equal(got.outcome, v.outcome, `outcome for ${JSON.stringify(v.raw)}`);
      // Raw survives untouched on every outcome -- it is the only evidence
      // available when a scan is disputed in the field.
      assert.equal(got.raw, v.raw, 'raw payload must be preserved');

      if (got.outcome === 'ACCEPT') {
        assert.ok(got.box, 'ACCEPT with no box');
        assert.equal(got.box!.pid, v.expect.pid);
        assert.equal(got.box!.boxSerial, v.expect.boxSerial);
        assert.equal(got.box!.qty, v.expect.qty);
        assert.equal(got.box!.firmware, v.expect.firmware ?? null);
        assert.equal(got.box!.mfgDate, v.expect.mfgDate ?? null);
      } else if (got.outcome === 'WRONG_BARCODE') {
        assert.equal(got.hint, v.expect.hint);
      } else if (got.outcome === 'REJECT') {
        assert.equal(got.reason, v.expect.reason);
      }
    });
  }
});

test('duplicate key is scoped to the product', () => {
  // Tally scopes a batch under a stock item, so the same box number under a
  // different product is a different box.
  assert.notEqual(boxKey('4098-9792', '1124241658336425'), boxKey('4090-9001', '1124241658336425'));
  assert.notEqual(boxKey('4098-9792', '1124241658336425'), boxKey('4098-9792', '1124241658336426'));
  assert.equal(boxKey('4098-9792', '1124241658336425'), boxKey('4098-9792', '1124241658336425'));
});

test('junk never throws', () => {
  const junk = ['', '|', '||', '|||', '||||', '\r\n', '   ', '||18|', 'a|b|c',
    `4098-9792|${'x'.repeat(4096)}|1|`, '----|----|----|'];
  for (const s of junk) {
    assert.doesNotThrow(() => registry.parse('CODE128', s), `threw on ${JSON.stringify(s)}`);
  }
});
