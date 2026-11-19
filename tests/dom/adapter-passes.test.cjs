const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');

// The page goes through the adapter once before it is shown: its own stylesheets and «Наверх» are in place before the
// pass measures tables, and are not taken for a site change that starts a second pass.
const kotlin = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
const scripts = JSON.parse('[' + kotlin.match(/val SCRIPTS = listOf\(([^)]*)\)/)[1] + ']');

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
  // The probe MobilePageAdapter.applyTo evaluates, in its order.
  w.eval('window.__univerMotion=true;window.__univerDark=' + dark + ';window.__univerMobileV1.setNativeChrome(true);' +
    'window.__univerMobileV1.setEnabled(true,"ru");window.__univerStyleV1&&window.__univerStyleV1.apply(true);window.__univerPresentationV1?.release();');
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
