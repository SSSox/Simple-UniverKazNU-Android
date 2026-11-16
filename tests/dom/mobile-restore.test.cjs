const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');

// Original layout after the mobile one: the site's own nodes come back exactly as they were, on any day of the week.
const kotlin = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
const scripts = JSON.parse('[' + kotlin.match(/val SCRIPTS = listOf\(([^)]*)\)/)[1] + ']');

// A clock moves the page's Date by clock.offset milliseconds.
function page(path, body, clock) {
  const html = '<html><head></head><body><table class="wrappertable"><tr id="l4"><td class="center">' + body + '</td></tr></table></body></html>';
  const jsdom = new JSDOM(html, { url: 'https://univer.kaznu.kz' + path, runScripts: 'outside-only', virtualConsole: new VirtualConsole(), pretendToBeVisual: true });
  const w = jsdom.window;
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
  if (clock) {
    const Real = w.Date;
    w.Date = class extends Real {
      constructor(...a) { if (a.length) super(...a); else super(Real.now() + clock.offset); }
      static now() { return Real.now() + clock.offset; }
    };
  }
  scripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));
  return w;
}
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

const days = ['ПН', 'ВТ', 'СР', 'ЧТ', 'ПТ', 'СБ', 'ВС'];
function schedule(header = d => '<th>' + d + '</th>') {
  return '<table class="schedule"><tr><th></th>' + days.map(header).join('') + '</tr>' +
    '<tr><td>09:00–09:50</td>' + days.map(d => '<td><div class="groups"><div title="Период: 01.09.2026 – 12.12.2026"><p>Предмет ' + d +
    '</p><p>Преподаватель</p></div></div></td>').join('') + '</tr></table>';
}

test('a schedule column marked as today leaves no empty class attribute in Original', async () => {
  // Seven day columns: today always has one, so the marker is placed whatever the date.
  const w = page('/student/myschedule/', schedule());
  const source = w.document.querySelector('table.schedule');
  const original = source.innerHTML;
  w.__univerMobileV1.setEnabled(true, 'ru');
  await wait(300);
  assert.ok(source.querySelector('th.univer-app-today-col'), 'the mobile layout marks today');
  w.__univerMobileV1.setEnabled(false, 'ru');
  await wait(300);
  assert.equal(source.innerHTML, original);
  w.close();
});

test('today moving to the next column at midnight still restores the schedule, and a site class="" stays', async () => {
  // Tuesday 23:59:50, then a minute later: the marker moves from ВТ to СР, whose cell had its own empty class.
  const clock = { offset: new Date(2026, 10, 17, 23, 59, 50).getTime() - Date.now() };
  const w = page('/student/myschedule/', schedule(d => d === 'СР' ? '<th class="">СР</th>' : '<th>' + d + '</th>'), clock);
  const source = w.document.querySelector('table.schedule');
  const original = source.innerHTML;
  w.__univerMobileV1.setEnabled(true, 'ru');
  await wait(300);
  assert.equal(source.querySelector('th.univer-app-today-col')?.textContent, 'ВТ');
  clock.offset += 60_000;
  w.__univerMobileV1.setEnabled(true, 'en');
  await wait(300);
  assert.equal(source.querySelector('th.univer-app-today-col')?.textContent, 'СР');
  w.__univerMobileV1.setEnabled(false, 'en');
  await wait(300);
  assert.equal(source.innerHTML, original);
  assert.equal(w.document.documentElement.hasAttribute('class'), false, 'the root gets no empty class either');
  w.close();
});

test('the grouped menu returns its footer rows without an empty class', async () => {
  const group = (id, link, title) => '<table class="tool" id="' + id + '"><tr><td class="top-center"><ul><li><a href="/fixture/' + id + '">' + link +
    '</a></li></ul></td></tr><tr><td class="bottom-center">' + title + '</td></tr></table>';
  const w = page('/fixture/', '<table id="tools"><tr><td>' + group('group1', 'Расписание занятий', 'Обучение') + group('group2', 'Приказы', 'Документы') + '</td></tr></table>');
  const tools = w.document.getElementById('tools');
  const original = tools.outerHTML;
  w.__univerMobileV1.setEnabled(true, 'ru');
  await wait(300);
  assert.equal(w.document.querySelectorAll('tr.univer-app-menu-footer').length, 2, 'the mobile layout marks both footers');
  w.__univerMobileV1.setEnabled(false, 'ru');
  await wait(300);
  assert.equal(w.document.getElementById('tools').outerHTML, original);
  w.close();
});
