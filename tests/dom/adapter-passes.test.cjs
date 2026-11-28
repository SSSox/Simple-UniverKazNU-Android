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

test('memos and dialog toolbars batch all layout reads before any writes via afterLayout', () => {
  const longText = 'Памятка студенту. '.repeat(50);
  const body =
    '<table class="mt"><tr class="top"><td class="ct">Памятка 1</td></tr><tr class="mid"><td class="ct memo-long">' + longText + '</td></tr></table>' +
    '<table class="mt"><tr class="top"><td class="ct">Памятка 2</td></tr><tr class="mid"><td class="ct memo-short">' + longText + '</td></tr></table>' +
    '<table class="mt"><tr class="top"><td class="ct">Памятка 3</td></tr><tr class="mid"><td class="ct memo-long">' + longText + '</td></tr></table>' +
    '<div id="simplemodal-container" class="dlg-open" style="z-index:1010"><a class="simplemodal-close">x</a></div>' +
    '<div id="simplemodal-container" class="dlg-hidden" style="z-index:1020"><a class="simplemodal-close">x</a></div>';
  const html = '<html><head></head><body><table class="wrappertable"><tr id="l4"><td class="center">' + body + '</td></tr></table></body></html>';
  const w = new JSDOM(html, { url: 'https://univer.kaznu.kz/student/failfx/', runScripts: 'outside-only', virtualConsole: new VirtualConsole(), pretendToBeVisual: true }).window;
  Object.defineProperty(w.document, 'readyState', { value: 'complete', configurable: true });
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });

  let watching = false;
  const memoReadButtons = [], dialogReadWrites = [];
  w.Element.prototype.getClientRects = function () {
    if (watching && this.id === 'simplemodal-container') {
      const written = Array.from(w.document.querySelectorAll('.univer-app-modal-toolbar')).filter(b => b.style.zIndex !== '').length;
      dialogReadWrites.push(written);
    }
    return this.classList.contains('dlg-hidden') ? [] : [{ width: 320, height: 160 }];
  };
  Object.defineProperty(w.HTMLElement.prototype, 'scrollHeight', {
    configurable: true,
    get() {
      if (watching && this.classList.contains('univer-scr-clamp')) memoReadButtons.push(w.document.querySelectorAll('button.univer-scr-more').length);
      return this.parentElement?.classList.contains('memo-long') ? 900 : 120;
    }
  });
  Object.defineProperty(w.HTMLElement.prototype, 'clientHeight', {
    configurable: true,
    get() { return this.classList.contains('univer-scr-clamp') ? 160 : 0; }
  });
  scripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));

  watching = true;
  try { w.__univerMobileV1.setEnabled(true, 'ru'); } finally { watching = false; }

  assert.equal(memoReadButtons.length, 3, 'all three memos were measured');
  assert.ok(memoReadButtons.every(count => count === 0), 'no memo button was written before all memos were read: ' + memoReadButtons);
  assert.equal(w.document.querySelectorAll('button.univer-scr-more').length, 2, 'only overflowing memos folded');
  assert.equal(w.document.querySelector('.memo-short .univer-scr-clamp'), null, 'short memo was unwrapped');

  assert.equal(dialogReadWrites.length, 2, 'both dialogs were measured');
  assert.ok(dialogReadWrites.every(count => count === 0), 'no toolbar z-index was written before all dialog reads: ' + dialogReadWrites);
  const toolbars = Array.from(w.document.querySelectorAll('.univer-app-modal-toolbar'));
  assert.equal(toolbars.length, 2);
  assert.deepEqual(toolbars.map(b => [b.style.zIndex, b.hidden]), [['1011', false], ['1021', true]]);

  w.__univerMobileV1.setEnabled(false, 'ru');
  w.close();
});

test('schedule minute ticks update countdowns in place without a full pass, and refresh on midnight or week change', async () => {
  const days = ['ПН', 'ВТ', 'СР', 'ЧТ', 'ПТ', 'СБ', 'ВС'];
  const sched = '<select id="weekSelect"><option value="1" selected>Неделя 12. 16.11.2026 - 22.11.2026</option>' +
    '<option value="2">Неделя 13. 23.11.2026 - 29.11.2026</option></select>' +
    '<table class="schedule"><tr><th></th>' + days.map(d => '<th>' + d + '</th>').join('') + '</tr>' +
    '<tr><td class="time">09:00–09:50</td>' + days.map(d => '<td><div class="groups"><div><p>Предмет ' + d + '</p><p>Преподаватель</p></div></div></td>').join('') + '</tr></table>';
  const html = '<html><head></head><body><table class="wrappertable"><tr id="l4"><td class="center">' + sched + '</td></tr></table></body></html>';
  const w = new JSDOM(html, { url: 'https://univer.kaznu.kz/student/myschedule/', runScripts: 'outside-only', virtualConsole: new VirtualConsole(), pretendToBeVisual: true }).window;
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });

  // Fake clock starting Tuesday 17.11.2026 09:10:00.
  let nowMs = new Date(2026, 10, 17, 9, 10, 0).getTime();
  const RealDate = w.Date;
  w.Date = class extends RealDate {
    constructor(...a) { super(...(a.length ? a : [nowMs])); }
    static now() { return nowMs; }
  };
  // Capture only the minute-boundary timer (~60 s, ignoring shorter module timeouts) so we can fire it deterministically.
  const realSetTimeout = w.setTimeout.bind(w);
  let minuteTick = null;
  w.setTimeout = (fn, ms, ...args) => {
    if (ms >= 30000) { minuteTick = () => fn(...args); return 9999; }
    return realSetTimeout(fn, ms, ...args);
  };
  w.clearTimeout = () => {};

  scripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));
  const tables = w.__univerTablesV1, fit = tables.fit;
  let passes = 0;
  tables.fit = function () { passes++; return fit.apply(this, arguments); };

  w.__univerMobileV1.setEnabled(true, 'ru');
  await wait(150);
  const afterInitial = passes;
  assert.ok(afterInitial >= 1);
  assert.equal(w.document.querySelector('th.univer-app-today-col')?.textContent, 'ВТ');
  assert.match(w.document.querySelector('.univer-app-now-count')?.textContent || '', /40 мин/);
  assert.ok(minuteTick, 'minute timer was scheduled');

  // One minute later on the same day: countdown updates, zero full adapter passes run.
  nowMs += 60_000;
  const tick1 = minuteTick; minuteTick = null;
  tick1();
  await wait(150);
  assert.equal(passes, afterInitial, 'minute tick on unchanged day/DOM skipped the full adapter pass');
  assert.match(w.document.querySelector('.univer-app-now-count')?.textContent || '', /39 мин/);
  assert.ok(minuteTick, 'minute timer rescheduled itself');

  // Crossing midnight to Wednesday 18.11.2026 00:01:00 refreshes today's column via a pass.
  nowMs = new Date(2026, 10, 18, 0, 1, 0).getTime();
  const tick2 = minuteTick; minuteTick = null;
  tick2();
  await wait(150);
  const afterMidnight = passes;
  assert.ok(afterMidnight > afterInitial, 'midnight date change triggered a refresh pass');
  assert.equal(w.document.querySelector('th.univer-app-today-col')?.textContent, 'СР');

  // Changing the selected week option also triggers a refresh pass on the next tick.
  w.document.getElementById('weekSelect').selectedIndex = 1;
  const tick3 = minuteTick; minuteTick = null;
  tick3();
  await wait(150);
  assert.ok(passes > afterMidnight, 'week selection change triggered a refresh pass');
  assert.equal(w.document.querySelector('th.univer-app-today-col'), null, 'next week does not contain today');

  w.__univerMobileV1.setEnabled(false, 'ru');
  w.close();
});

test('schedule refresh detects rowspan and colspan changes while preserving text and row count', async () => {
  const sched = '<select id="weekSelect"><option value="1" selected>Неделя 12. 16.11.2026 - 22.11.2026</option></select>' +
    '<table class="schedule">' +
    '<tr><th></th><th>ПН</th><th>ВТ</th><th>СР</th></tr>' +
    '<tr><td class="time">09:00–09:50</td>' +
      '<td class="mon-slot"><div class="groups"><div><p>Алгебра</p><p>Иванов</p></div></div></td>' +
      '<td class="tue-slot"><div class="groups"><div><p>Физика</p><p>Петров</p></div></div></td>' +
      '<td></td></tr>' +
    '<tr><td class="time">10:00–10:50</td><td></td><td></td><td></td></tr>' +
    '</table>';
  const html = '<html><head></head><body><table class="wrappertable"><tr id="l4"><td class="center">' + sched + '</td></tr></table></body></html>';
  const w = new JSDOM(html, { url: 'https://univer.kaznu.kz/student/myschedule/', runScripts: 'outside-only', virtualConsole: new VirtualConsole(), pretendToBeVisual: true }).window;
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });

  let nowMs = new Date(2026, 10, 16, 9, 10, 0).getTime();
  const RealDate = w.Date;
  w.Date = class extends RealDate {
    constructor(...a) { super(...(a.length ? a : [nowMs])); }
    static now() { return nowMs; }
  };
  const realSetTimeout = w.setTimeout.bind(w);
  let minuteTick = null;
  w.setTimeout = (fn, ms, ...args) => {
    if (ms >= 30000) { minuteTick = () => fn(...args); return 9999; }
    return realSetTimeout(fn, ms, ...args);
  };
  w.clearTimeout = () => {};

  scripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));
  const tables = w.__univerTablesV1, fit = tables.fit;
  let passes = 0;
  tables.fit = function () { passes++; return fit.apply(this, arguments); };

  w.__univerMobileV1.setEnabled(true, 'ru');
  await wait(150);

  const table = w.document.querySelector('table.schedule');
  const monSlot = table.querySelector('td.mon-slot');
  const initialRows = table.rows.length;
  const initialText = table.textContent;
  const dayCards = i => Array.from(w.document.querySelectorAll('.univer-app-day')[i].querySelectorAll('.univer-app-lesson'))
    .map(c => ({
      time: c.querySelector('time')?.getAttribute('aria-label') || '',
      subject: c.querySelector('.univer-app-lesson-name')?.textContent || '',
    }));

  assert.deepEqual(dayCards(0), [{ time: '09:00–09:50', subject: 'Алгебра' }]);
  assert.deepEqual(dayCards(1), [{ time: '09:00–09:50', subject: 'Физика' }]);
  assert.deepEqual(dayCards(2), []);

  // 1) Mutate rowspan silently (bypassing observer via quiet) so only the minute-tick fingerprint can catch it.
  const beforeRowspan = passes;
  w.__univerMobileV1.quiet(() => monSlot.setAttribute('rowspan', '2'));
  assert.equal(table.rows.length, initialRows, 'row count unchanged');
  assert.equal(table.textContent, initialText, 'table text unchanged');
  await wait(150);
  assert.equal(passes, beforeRowspan, 'quiet change did not trigger observer pass');

  nowMs += 60_000;
  const tick1 = minuteTick; minuteTick = null;
  tick1();
  await wait(150);
  assert.ok(passes > beforeRowspan, 'minute-tick fingerprint detected rowspan change');
  assert.deepEqual(dayCards(0), [{ time: '09:00–10:50', subject: 'Алгебра' }], 'agenda updated lesson end time after rowspan change');

  // 2) Mutate colspan directly: MutationObserver detects it immediately while preserving row count and text.
  const beforeColspan = passes;
  monSlot.setAttribute('colspan', '2');
  assert.equal(table.rows.length, initialRows, 'row count still unchanged');
  assert.equal(table.textContent, initialText, 'table text still unchanged');
  await wait(150);
  assert.ok(passes > beforeColspan, 'observer detected colspan change');
  assert.deepEqual(dayCards(2), [{ time: '09:00–09:50', subject: 'Физика' }], 'colspan shifted the following lesson to Wednesday');

  w.__univerMobileV1.setEnabled(false, 'ru');
  w.close();
});

test('an href added to an existing lesson anchor reaches the agenda on the next minute tick and through the observer', async () => {
  const sched = '<select id="weekSelect"><option value="1" selected>Неделя 12. 16.11.2026 - 22.11.2026</option></select>' +
    '<table class="schedule">' +
    '<tr><th></th><th>ПН</th><th>ВТ</th></tr>' +
    '<tr><td class="time">09:00–09:50</td>' +
      '<td><div class="groups"><div><p>Алгебра</p><p>Иванов</p><p><a class="room">Аудитория</a></p></div></div></td>' +
      '<td><div class="groups"><div><p>Физика</p><p>Петров</p><p><a class="room">Аудитория</a></p></div></div></td></tr>' +
    '</table>';
  const html = '<html><head></head><body><table class="wrappertable"><tr id="l4"><td class="center">' + sched + '</td></tr></table></body></html>';
  const w = new JSDOM(html, { url: 'https://univer.kaznu.kz/student/myschedule/', runScripts: 'outside-only', virtualConsole: new VirtualConsole(), pretendToBeVisual: true }).window;
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
  let nowMs = new Date(2026, 10, 16, 9, 10, 0).getTime();
  const RealDate = w.Date;
  w.Date = class extends RealDate {
    constructor(...a) { super(...(a.length ? a : [nowMs])); }
    static now() { return nowMs; }
  };
  const realSetTimeout = w.setTimeout.bind(w);
  let minuteTick = null;
  w.setTimeout = (fn, ms, ...args) => {
    if (ms >= 30000) { minuteTick = () => fn(...args); return 9999; }
    return realSetTimeout(fn, ms, ...args);
  };
  w.clearTimeout = () => {};
  scripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));
  w.__univerMobileV1.setEnabled(true, 'ru');
  await wait(150);
  const links = i => Array.from(w.document.querySelectorAll('.univer-app-day')[i].querySelectorAll('.univer-app-page-link')).map(a => a.getAttribute('href'));
  assert.deepEqual(links(0), []);
  assert.deepEqual(links(1), []);
  const anchors = w.document.querySelectorAll('table.schedule a.room');

  // Added silently: only the minute fingerprint can notice it.
  w.__univerMobileV1.quiet(() => anchors[0].setAttribute('href', 'https://univer.kaznu.kz/room/1'));
  await wait(150);
  assert.deepEqual(links(0), [], 'quiet change alone does not rebuild');
  nowMs += 60_000;
  const tick = minuteTick; minuteTick = null; tick();
  await wait(150);
  assert.deepEqual(links(0), ['https://univer.kaznu.kz/room/1'], 'minute tick rebuilt the agenda with the new link');

  // Added by the site: the observer follows href changes.
  anchors[1].setAttribute('href', 'https://univer.kaznu.kz/room/2');
  await wait(250);
  assert.deepEqual(links(1), ['https://univer.kaznu.kz/room/2'], 'observer rebuilt the agenda with the new link');

  w.__univerMobileV1.setEnabled(false, 'ru');
  w.close();
});
