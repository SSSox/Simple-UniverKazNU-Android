const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

// The journal's totals as the app keeps them for background grade checks: the page sends what it shows, and the same
// reader gives the same totals from the raw HTML on about:blank. Courses and scores are made up; the markup follows
// the observed /student/attendance/full/ journal.
const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const plain = value => JSON.parse(JSON.stringify(value));
const lesson = (id, sum) => `<tr class="mid"><td class="ts"><a class="lt" href="/student/attendance/show/${id}/2026/1">лекция - Teacher ${id}</a></td></tr>` +
  `<tr><td><table class="inner"><tr><th rowspan="2">РК1</th><th>01.09</th><th>Сумма</th></tr><tr><td>${sum}</td><td>${sum}</td></tr></table></td></tr>`;
const course = (name, totals, id) => `<tr class="top"><td class="ct">${name} ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>` +
  `<tr class="mid"><td class="ct"><b>${totals}</b></td></tr>` + lesson(id, 5);
const nav = '<tr><td><a href="/student/attendance/full/2025/2">Предыдущий</a><a href="/student/attendance/full/2026/2">Следующий</a></td></tr>';
const JOURNAL = '<!doctype html><html lang="ru"><body><table class="wrappertable"><tr id="l4"><td class="center"><table class="mt">' + nav +
  course('Социология', 'РК1 (100): 63 РК2 (100): 0', 1) + course('Психология', 'РК1 (100): 92 РК2 (100):', 3) +
  '</table></td></tr></table></body></html>';

async function journal(html = JOURNAL, path = '/student/attendance/full/') {
  const w = new JSDOM(html, {url: 'https://univer.kaznu.kz' + path, runScripts: 'outside-only'}).window;
  if (w.document.readyState === 'loading') await new Promise(done => w.document.addEventListener('DOMContentLoaded', done));
  w.eval(read('univer-dom.js')); w.eval(read('univer-attendance.js'));
  return w;
}
const EXPECTED = {
  v: 1, term: {year: 2026, term: 1}, lang: 'ru',
  courses: [
    {name: 'Социология', scores: [{label: 'РК1', max: '100', value: '63'}, {label: 'РК2', max: '100', value: '0'}]},
    {name: 'Психология', scores: [{label: 'РК1', max: '100', value: '92'}, {label: 'РК2', max: '100', value: ''}]},
  ],
};
// What web/GradesMessage.READ evaluates on the journal page.
const pageRead = w => w.eval("(function(){var r=window.__univerGradeReadV1;var t=r&&r.read(document,location.pathname);return t?JSON.stringify(t):null})()");

test('the journal reads the same totals in the mobile layout, in Original and from the raw page on about:blank', async () => {
  const w = await journal();
  assert.deepEqual(JSON.parse(pageRead(w)), EXPECTED, 'Original: the site\'s own markup');
  w.__univerAttendanceV1.apply('ru');
  assert.ok(w.document.querySelector('.univer-app-attendance-summary'), 'the mobile layout is on');
  assert.deepEqual(JSON.parse(pageRead(w)), EXPECTED, 'the adapted page reads the same');

  // The background copy: the reader alone, on a page with no origin and no adapter, over the HTML as fetched.
  const blank = new JSDOM('<!doctype html><html><body></body></html>', {url: 'about:blank', runScripts: 'outside-only'}).window;
  blank.eval(read('univer-attendance.js'));
  assert.equal(blank.__univerAttendanceV1, undefined, 'the journal module itself stays off outside Univer');
  const raw = new blank.DOMParser().parseFromString(JOURNAL, 'text/html');
  assert.deepEqual(plain(blank.__univerGradeReadV1.read(raw, '/student/attendance/full/')), EXPECTED, 'adapted page and raw HTML agree');
  assert.equal(blank.__univerGradeReadV1.read(new blank.DOMParser().parseFromString('<p>Вход</p>', 'text/html'), '/'), null);
  blank.close();
  w.close();
});

test('Кабинет hands the app the totals its grades panel shows, once, and nothing without grades', async () => {
  async function bachelor(page) {
    const w = new JSDOM('<body><div id="l4"><table class="mt"></table></div></body>', {url: 'https://univer.kaznu.kz/student/bachelor/', runScripts: 'outside-only', pretendToBeVisual: true}).window;
    w.TextDecoder = TextDecoder;
    const sent = [];
    w.__univerNative = message => { if (message.startsWith('univer-grades:')) sent.push(JSON.parse(message.slice('univer-grades:'.length))); };
    w.fetch = async url => ({status: 200, url: 'https://univer.kaznu.kz' + url, text: async () => (/attendance/.test(url) ? page : '<html><body></body></html>')});
    w.eval(read('univer-dom.js')); w.eval(read('univer-attendance.js')); w.eval(read('univer-overview.js'));
    w.__univerOverviewV1.apply('ru');
    const chips = () => w.document.querySelector('.univer-app-overview-grades .univer-app-overview-body');
    for (let i = 0; i < 200 && !sent.length && !(chips() && chips().textContent.trim()); i++) await new Promise(resolve => setTimeout(resolve, 5));
    await new Promise(resolve => setTimeout(resolve, 20));
    return {w, sent};
  }
  const shown = await bachelor(JOURNAL);
  assert.equal(shown.sent.length, 1);
  assert.deepEqual(shown.sent[0].courses, EXPECTED.courses);
  shown.w.__univerOverviewV1.release(); shown.w.close();
  const none = await bachelor(JOURNAL.replace(/<tr class="mid"><td class="ct"><b>[^<]*<\/b><\/td><\/tr>/g, ''));
  assert.equal(none.sent.length, 0, 'no totals at all: nothing to compare later');
  none.w.__univerOverviewV1.release(); none.w.close();
});
