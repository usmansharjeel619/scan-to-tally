/**
 * The link stays put; the filename carries the version.
 *
 * The URL is written down, pasted into chats and typed off a screen onto a
 * warehouse PC, so it must never change. But an APK called app.apk tells
 * nobody which version it is once it is in Downloads beside three others.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

// The same rule the route applies, kept here so its edges are pinned.
function downloadName(file: string, version: string): string {
  if (!version) return file;
  const dot = file.lastIndexOf('.');
  if (dot <= 0) return file;
  return `${file.slice(0, dot)}-${version}${file.slice(dot)}`;
}

test('an apk arrives under its version', () => {
  assert.equal(downloadName('app.apk', '1.1.0'), 'app-1.1.0.apk');
  assert.equal(downloadName('connector.exe', '1.1.0'), 'connector-1.1.0.exe');
});

test('with no version recorded, nothing is renamed', () => {
  // Better a plain name than a wrong one.
  assert.equal(downloadName('app.apk', ''), 'app.apk');
});

test('a name with no extension is left alone', () => {
  assert.equal(downloadName('README', '1.1.0'), 'README');
});

test('only the last dot counts', () => {
  assert.equal(downloadName('bootstrap.ps1', '1.1.0'), 'bootstrap-1.1.0.ps1');
  assert.equal(downloadName('.hidden', '1.1.0'), '.hidden');
});
