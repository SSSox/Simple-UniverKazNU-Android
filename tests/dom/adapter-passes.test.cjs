const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');

// The page goes through the adapter once before it is shown: its own stylesheets and «Наверх» are in place before the
// pass measures tables, and are not taken for a site change that starts a second pass.
const kotlin = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
const scripts = JSON.parse('[' + kotlin.match(/val SCRIPTS = listOf\(([^)]*)\)/)[1] + ']');
// The probe MobilePageAdapter.applyTo evaluates, in its order (its Kotlin templates filled in).
const probe = kotlin.match(/val apply = ([\s\S]*?release\(\);")/)[1].split(/"\s*\+\s*"/).join('').replace(/^"|"$/g, '');

function page(path, body, { dark = false } = {}) {
  const html = '<html><head><style>table.mt td{background:#fff;color:#000}</style></head><body><table class="wrappertable"><tr id="l4"><td class="center">' + body + '</td></tr></table></body></html>';
  const jsdom = new JSDOM(html, { url: 'https://univer.kaznu.kz' + path, runScripts: 'outside-only', virtualConsole: new VirtualConsole(), pretendToBeVisual: true });
  const w = jsdom.window;
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
  scripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));
  // Every pass ends by fitting the tables: one call is one pass.
  const tables = w.__univerTablesV1, fit = tables.fit, passes = [];
  tables.fit = function () { passes.push({ style: !!w.document.getElementById('univer-style'), top: !!w.document.getElementById('univer-app-top') }); return fit.apply(this, arguments); };
  w.eval(probe.replace(/\$\{quote\(language\.siteCode\)\}/g, '"ru"').replace(/\$animations/g, 'true').replace(/\$dark/g, String(dark))
    .replace(/\$shortcuts/g, 'null').replace(/\$nativeChrome/g, 'true').replace(/\$enabled/g, 'true'));
  return { w, passes };
}
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const news = '<table class="mt"><tr><td class="ct">Новости</td></tr><tr class="news"><td>Синтетическая новость</td></tr></table>' +
  '<table class="inner"><tr><th>Дисциплина</th><th>Оценка</th></tr><tr><td>Синтетика</td><td>90</td></tr></table>';

for (const dark of [false, true]) {
  test('a page is adapted in one pass, with its styles and the top button already in place' + (dark ? ' (dark theme)' : ''), async () => {
    const { w, passes } = page('/student/attendance/full/', news, { dark });
    await wait(250);
    assert.equal(passes.length, 1, 'passes: ' + JSON.stringify(passes));
    assert.deepEqual(passes[0], { style: true, top: true });
    // A real site change still starts a pass.
    w.document.querySelector('#l4 td.center').appendChild(w.document.createElement('p')).textContent = 'AJAX';
    await wait(250);
    assert.equal(passes.length, 2);
    w.__univerMobileV1.setEnabled(false, 'ru'); w.__univerStyleV1.apply(false);
    assert.equal(w.document.getElementById('univer-style'), null);
    assert.equal(w.document.getElementById('univer-app-top'), null);
    w.close();
  });
}

test('wide tables are all measured before any of them is fitted: one layout per pass, not one per table', async () => {
  const table = n => '<table class="inner"><tr><th>Дисциплина ' + n + '</th><th>Оценка</th></tr><tr><td>Синтетика</td><td>90</td></tr></table>';
  const html = '<html><head></head><body><table class="wrappertable"><tr id="l4"><td class="center">' + table(1) + table(2) + table(3) + '</td></tr></table></body></html>';
  const w = new JSDOM(html, { url: 'https://univer.kaznu.kz/student/attendance/full/', runScripts: 'outside-only', virtualConsole: new VirtualConsole(), pretendToBeVisual: true }).window;
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
  w.HTMLElement.prototype.getClientRects = function () { return [{ width: 100, height: 30 }]; };
  // Each box is 90 % as wide as its table; a read made after a table was fitted would force another layout.
  let fitting = false;
  const reads = [], fittedNow = () => w.document.querySelectorAll('.univer-app-table-fitted').length;
  Object.defineProperty(w.HTMLElement.prototype, 'clientWidth', { configurable: true, get() { if (fitting && this.classList.contains('univer-app-table-scroll')) reads.push(fittedNow()); return 360; } });
  Object.defineProperty(w.HTMLElement.prototype, 'scrollWidth', { configurable: true, get() { return 400; } });
  scripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));
  // The whole pass is watched: the boxes are measured in its single read at the end (the scroll hints too), and no
  // box may be read after a table was fitted.
  // «Наверх» reads where the page stands with the next frame, not inside the pass.
  let scrollReads = 0;
  Object.defineProperty(w, 'scrollY', { configurable: true, get() { if (fitting) scrollReads++; return 0; } });
  fitting = true;
  try { w.__univerMobileV1.setEnabled(true, 'ru'); } finally { fitting = false; }
  assert.equal(scrollReads, 0, 'the top button measured inside the pass');
  assert.ok(w.document.getElementById('univer-app-top'), 'the top button is in place for the pass');
  w.__univerStyleV1.apply(true);
  assert.equal(fittedNow(), 3);
  assert.equal(w.document.querySelector('.univer-app-table-fitted').style.zoom, '0.9');
  assert.ok(reads.length >= 6 && reads.every(count => count === 0), 'tables fitted at each read: ' + reads);
  // Fitted tables fit their boxes: no swipe hint for them.
  assert.equal(w.document.querySelectorAll('.univer-app-scroll-hint:not([hidden])').length, 0);
  w.__univerMobileV1.setEnabled(false, 'ru');
  assert.equal(w.document.querySelectorAll('.univer-app-table-fitted').length, 0);
  w.close();
});

test('the help box folds; other boxes are read for its addresses once, not on every pass', async () => {
  const help = '<table class="mt"><tr><td>it@kaznu.kz wifi-support@kaznu.kz help@kaznu.kz</td></tr></table>';
  const { w } = page('/student/attendance/full/', help + '<table class="mt"><tr><td class="plain">Синтетика</td></tr></table>');
  await wait(250);
  assert.equal(w.document.querySelector('.univer-app-info[data-univer-info=contacts] table.mt').textContent.includes('help@kaznu.kz'), true);
  const plain = w.document.querySelector('td.plain').closest('table'), get = Object.getOwnPropertyDescriptor(w.Node.prototype, 'textContent').get;
  let reads = 0;
  Object.defineProperty(plain, 'textContent', { configurable: true, get() { reads++; return get.call(this); } });
  w.document.querySelector('#l4 td.center').appendChild(w.document.createElement('p')).textContent = 'AJAX';
  await wait(250);
  assert.equal(reads, 0);
  w.__univerMobileV1.setEnabled(false, 'ru'); w.close();
});

test('a resize that keeps the width (the keyboard, the viewport the adapter set) starts no pass; a new width does', async () => {
  const { w, passes } = page('/student/attendance/full/', news);
  await wait(250);
  assert.equal(passes.length, 1);
  w.dispatchEvent(new w.Event('resize'));
  await wait(250);
  assert.equal(passes.length, 1);
  Object.defineProperty(w, 'innerWidth', { configurable: true, value: 952 });
  w.dispatchEvent(new w.Event('resize'));
  await wait(250);
  assert.equal(passes.length, 2);
  w.__univerMobileV1.setEnabled(false, 'ru'); w.close();
});
