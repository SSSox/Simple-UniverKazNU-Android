const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');

// The dark text of an adapter stylesheet is worked out once per app session, not on every page.
const palette = fs.readFileSync('app/src/main/assets/univer-palette.js', 'utf8');
const CSS = '.a{color:#1b5fa6;background:#ffffff}.b{border-color:#d7e0ea}';
function page(saved = {}) {
  const w = new JSDOM('<html><head></head><body></body></html>', { url: 'https://univer.kaznu.kz/news/student', runScripts: 'outside-only' }).window;
  Object.entries(saved).forEach(([key, value]) => w.sessionStorage.setItem(key, value));
  w.eval(palette); w.__univerDark = true;
  return w;
}
const fill = (w, css) => { const style = w.document.createElement('style'); w.__univerPaletteV1.fill(style, css); return style.textContent; };
const kept = w => Object.fromEntries(Object.keys(w.sessionStorage).map(key => [key, w.sessionStorage.getItem(key)]));

test('a stylesheet darkened on one page is taken from the session on the next; a changed one is darkened anew', () => {
  const first = page(), dark = fill(first, CSS);
  assert.notEqual(dark, CSS);
  assert.equal(dark, first.__univerPaletteV1.darken(CSS));
  const saved = kept(first), key = Object.keys(saved).find(name => name.startsWith('univer.app.dark.v1:'));
  assert.equal(saved[key], dark);
  first.close();
  // The next page reads the stored text (marked here to tell it from a fresh one) instead of darkening again.
  const next = page({ ...saved, [key]: dark + '/*kept*/' });
  assert.equal(fill(next, CSS), dark + '/*kept*/');
  const changed = CSS.replace('#d7e0ea', '#e6ecf3');
  assert.equal(fill(next, changed), next.__univerPaletteV1.darken(changed));
  assert.equal(JSON.parse(next.sessionStorage.getItem('univer.app.dark-index.v1')).length, 2);
  // Light pages store nothing.
  next.__univerDark = false;
  assert.equal(fill(next, '.c{color:#000}'), '.c{color:#000}');
  assert.equal(JSON.parse(next.sessionStorage.getItem('univer.app.dark-index.v1')).length, 2);
  next.close();
});

test('without storage the stylesheet is still darkened', () => {
  const w = page();
  Object.defineProperty(w, 'sessionStorage', { get() { throw new Error('denied'); } });
  assert.equal(fill(w, CSS), w.__univerPaletteV1.darken(CSS));
  w.close();
});
