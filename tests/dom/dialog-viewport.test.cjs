const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');

// A site dialog (and the frame inside it) fits in what is visible: with the keyboard open or in landscape the visual
// viewport can be shorter than 144 CSS px, and the dialog's bottom must still be on screen.
const kotlin = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
const scripts = JSON.parse('[' + kotlin.match(/val SCRIPTS = listOf\(([^)]*)\)/)[1] + ']');
const probe = kotlin.match(/val apply = ([\s\S]*?release\(\);")/)[1].split(/"\s*\+\s*"/).join('').replace(/^"|"$/g, '');
// The dialog's padding (64 px for its bar, 12 px below) and its 1 px border, from the stylesheet.
const DIALOG_CHROME = 64 + 12 + 2;

function page(height, offsetTop = 0) {
  const html = '<html><head></head><body><table class="wrappertable"><tr id="l4"><td class="center">' +
    '<div id="simplemodal-container"><p>Окно</p></div></td></tr></table></body></html>';
  const w = new JSDOM(html, { url: 'https://univer.kaznu.kz/student/attendance/full/', runScripts: 'outside-only', virtualConsole: new VirtualConsole(), pretendToBeVisual: true }).window;
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
  const view = new w.EventTarget();
  const size = { height, offsetTop };
  Object.defineProperty(view, 'height', { get: () => size.height });
  Object.defineProperty(view, 'offsetTop', { get: () => size.offsetTop });
  Object.defineProperty(w, 'visualViewport', { configurable: true, value: view });
  scripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));
  w.eval(probe.replace(/\$\{quote\(language\.siteCode\)\}/g, '"ru"').replace(/\$animations/g, 'true').replace(/\$dark/g, 'false')
    .replace(/\$shortcuts/g, 'null').replace(/\$nativeChrome/g, 'true').replace(/\$enabled/g, 'true'));
  const px = name => parseFloat(w.document.documentElement.style.getPropertyValue(name));
  const read = () => ({ top: px('--univer-dialog-top'), dialog: px('--univer-dialog-height'), frame: px('--univer-frame-height') });
  const move = (h, top = 0) => { size.height = h; size.offsetTop = top; view.dispatchEvent(new w.Event('resize')); };
  const close = async () => { w.__univerMobileV1.setEnabled(false, 'ru'); await wait(150); w.close(); };
  return { w, read, move, close };
}
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

function assertFits(sizes, height, offsetTop) {
  assert.ok(sizes.top >= offsetTop, 'dialog starts above the visible area: ' + JSON.stringify(sizes));
  assert.ok(sizes.top + sizes.dialog <= offsetTop + height, 'dialog bottom leaves the screen at ' + height + ' px: ' + JSON.stringify(sizes));
  assert.ok(sizes.frame >= 0 && sizes.frame <= Math.max(0, sizes.dialog - DIALOG_CHROME),
    'the frame is taller than what is left in the dialog at ' + height + ' px: ' + JSON.stringify(sizes));
}

test('a tall screen keeps the 12 px margins and the frame fills the dialog below its bar', async () => {
  const p = page(640);
  await wait(250);
  assert.deepEqual(p.read(), { top: 12, dialog: 616, frame: 616 - DIALOG_CHROME });
  await p.close();
});

for (const height of [143, 130, 120, 100, 60]) {
  test('with only ' + height + ' px visible the whole dialog and its frame stay on screen', async () => {
    const p = page(640);
    await wait(250);
    // The keyboard opens over the lower part of a scrolled page.
    p.move(height, 200);
    assertFits(p.read(), height, 200);
    await p.close();
  });
}

test('the dialog grows back to its margins when the keyboard closes', async () => {
  const p = page(110, 40);
  await wait(250);
  assertFits(p.read(), 110, 40);
  p.move(700);
  assert.deepEqual(p.read(), { top: 12, dialog: 676, frame: 676 - DIALOG_CHROME });
  await p.close();
});
