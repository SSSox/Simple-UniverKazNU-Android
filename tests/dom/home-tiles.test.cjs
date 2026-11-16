const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');

const script = ['univer-dom.js', 'univer-shortcuts.js'].map(name => fs.readFileSync('app/src/main/assets/' + name, 'utf8')).join('\n');
// Whether the tiles script treats the address as the home page (the root class it sets before drawing the tiles).
function isHome(url) {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', { url, runScripts: 'outside-only' });
  const w = dom.window;
  try {
    w.eval(script);
    w.__univerShortcutsV1.apply('ru');
    return w.document.documentElement.classList.contains('univer-app-home');
  } finally { w.close(); }
}

// After sign-in the site lands on /news/student/1/ (seen on the live cabinet, 15 Nov 2026): that is still the home page.
test('the news page the site opens after sign-in is the home page, a later news page is not', () => {
  assert.equal(isHome('https://univer.kaznu.kz/news/student/1/'), true);
  assert.equal(isHome('https://univer.kaznu.kz/news/student/1'), true);
  assert.equal(isHome('https://univer.kaznu.kz/news/student'), true);
  assert.equal(isHome('https://univer.kaznu.kz/student/bachelor/'), true);
  assert.equal(isHome('https://univer.kaznu.kz/news/student/2/'), false);
  assert.equal(isHome('https://univer.kaznu.kz/news/student/12/'), false);
});
