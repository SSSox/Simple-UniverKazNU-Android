const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');

// The reading layout gives the site's low-vision script a stand-in (web/BviStub.kt); Original loads the real one and
// starts it with the options the page gave the stand-in.
const kotlin = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
const scripts = JSON.parse('[' + kotlin.match(/val SCRIPTS = listOf\(([^)]*)\)/)[1] + ']');
const stub = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/BviStub.kt', 'utf8')
  .match(/SCRIPT = ([\s\S]*?)\n\n/)[1].split(/"\s*\+\s*"/).join('').replace(/^"|"$/g, '');

function page() {
  const w = new JSDOM('<html><head></head><body><table class="wrappertable"><tr id="l4"><td class="center"><a class="bvi-open" href="#">Версия для слабовидящих</a></td></tr></table></body></html>',
    { url: 'https://univer.kaznu.kz/news/student/1/', runScripts: 'outside-only', virtualConsole: new VirtualConsole(), pretendToBeVisual: true }).window;
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
  w.eval(stub);
  w.eval("new isvek.Bvi({ target: '.bvi-open' });"); // the page's own inline start
  scripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));
  return w;
}

test('the stand-in keeps the options; Original loads the real script and starts it with them', () => {
  const w = page();
  assert.equal(w.isvek.Bvi.univerStub, true);
  w.__univerMobileV1.setEnabled(true, 'ru');
  assert.equal(w.document.getElementById('univer-bvi-real'), null, 'nothing loads in the reading layout');
  w.__univerMobileV1.setEnabled(false, 'ru');
  const real = w.document.getElementById('univer-bvi-real');
  assert.ok(real, 'Original asks for the real script');
  assert.match(real.src, /\/Content\/js\/bvi\.min\.js\?univer-full$/);
  // The real script defines its constructor and is started once with the page's options.
  const started = [];
  w.isvek.Bvi = function (options) { started.push(options); };
  real.onload();
  assert.deepEqual(started.map(o => o.target), ['.bvi-open']);
  w.__univerMobileV1.setEnabled(true, 'ru'); w.__univerMobileV1.setEnabled(false, 'ru');
  assert.equal(w.document.querySelectorAll('#univer-bvi-real').length, 1, 'loaded once');
  assert.equal(started.length, 1);
  w.close();
});

test('a page with the real script is left alone', () => {
  const w = new JSDOM('<html><body></body></html>', { url: 'https://univer.kaznu.kz/news/', runScripts: 'outside-only', virtualConsole: new VirtualConsole(), pretendToBeVisual: true }).window;
  w.CSS = { supports: () => true }; w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
  w.isvek = { Bvi: function () {} };
  scripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));
  w.__univerMobileV1.setEnabled(true, 'ru'); w.__univerMobileV1.setEnabled(false, 'ru');
  assert.equal(w.document.getElementById('univer-bvi-real'), null);
  w.close();
});

test('Original chosen before the reading layout ever ran still gets the real script', () => {
  const w = page();
  w.__univerMobileV1.setEnabled(false, 'ru');
  assert.ok(w.document.getElementById('univer-bvi-real'));
  w.close();
});
