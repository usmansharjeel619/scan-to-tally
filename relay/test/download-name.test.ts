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

test('every binary download declares a length and allows resuming', async () => {
  // Streaming without a Content-Length sends the body chunked with no length,
  // and Android's download manager shows a 44 MB APK as 0 KB and can sit there
  // never finishing. A browser copes; the handset downloading its own update
  // does not, and that is the client that matters.
  //
  // Asserted against the source, because the route only exists when a download
  // path is configured and this rule must not be able to regress unnoticed.
  const { readFileSync } = await import('node:fs');
  const src = readFileSync(new URL('../src/server.ts', import.meta.url), 'utf8');

  const route = src.slice(src.indexOf('/dl/${DOWNLOAD_PATH}/:file'));
  const body = route.slice(0, route.indexOf('createReadStream'));

  assert.match(body, /Content-Length/, 'a download must declare its size');
  assert.match(body, /statSync/, 'the size must come from the file itself');
  assert.match(body, /Accept-Ranges/, 'an interrupted download must be resumable');
});

test('a partial download resumes from where it stopped', async () => {
  // Advertising Accept-Ranges and then ignoring Range is a lie a browser
  // shrugs off and Android's download manager does not: it asks for a range
  // while resuming, is handed the whole file with a 200, and sits at
  // "44.06/44.06" without ever finishing.
  const { readFileSync } = await import('node:fs');
  const src = readFileSync(new URL('../src/server.ts', import.meta.url), 'utf8');
  const route = src.slice(src.indexOf('/dl/${DOWNLOAD_PATH}/:file'));
  const body = route.slice(0, route.indexOf('// --- connector socket'));

  assert.match(body, /Accept-Ranges/, 'ranges must be advertised');
  assert.match(body, /req\.headers\.range/, 'and the request header actually read');
  assert.match(body, /206/, 'a range must answer 206, not 200 with everything');
  assert.match(body, /Content-Range/, 'and say which bytes it is sending');
  assert.match(body, /416/, 'an impossible range must be refused, not silently ignored');
  assert.match(body, /createReadStream\(full, \{ start, end \}\)/,
    'and read only that slice of the file');
});
