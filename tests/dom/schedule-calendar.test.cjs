const nodeTest = require('node:test');
const opened = [];
const test = (name, body) => nodeTest.test(name, async () => { try { await body(); } finally { opened.splice(0).forEach(w => { try { w.close(); } catch (_) {} }); } });
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');
const jsdomUtils = require('jsdom/lib/jsdom/living/generated/utils');
const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const scripts = ['univer-palette.js', 'univer-dom.js', 'univer-lesson.js', 'univer-overview.js', 'univer-mobile-css.js', 'univer-mobile.js', 'univer-style.js'].map(read);

// «В календарь» on a class card of the schedule (structure as in syllabus-tasks.test.cjs; names are synthetic). The week
// shown is the real current one, so the expected start follows the clock the page itself reads.
const two = n => String(n).padStart(2, '0');
const dmy = d => two(d.getDate()) + '.' + two(d.getMonth() + 1) + '.' + d.getFullYear();
const iso = d => d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate());
const today = new Date(); today.setHours(0, 0, 0, 0);
const monday = new Date(today); monday.setDate(today.getDate() - (today.getDay() + 6) % 7);
const day = shift => { const d = new Date(monday); d.setDate(monday.getDate() + shift); return d; };
const PERIOD_END = day(70);
const lessonCell = l => l ? `<td class="field"><div class="groups"><div><p class="teacher">${l[0]}(${l[1]})</p><p class="teacher">${l[2]}</p>` +
  `<p class="params"><span class="aud_faculty">ФИТ</span><span>Ауд.: 309</span>${l[3] ? `<span>Период с ${dmy(day(-21))} по ${l[3]}</span>` : ''}</p></div></div></td>` : '<td class="field"></td>';
// The shown week is week 12 (`shift` 0) or 13 (7); the site lists both, so the neighbour can be read.
const schedule = (rows, shift = 0) => '<select id="weekSelect">' + [0, 7].map(n => `<option value="/student/myschedule/2026/1/${dmy(day(n))}/${dmy(day(n + 6))}/"${n === shift ? ' selected' : ''}>Неделя ${12 + n / 7}. ${dmy(day(n))} - ${dmy(day(n + 6))}</option>`).join('') + '</select>' +
  '<div class="inner"><table class="schedule"><tr><th>&nbsp;</th>' + ['ПН', 'ВТ', 'СР', 'ЧТ', 'ПТ', 'СБ'].map(d => '<th>' + d + '</th>').join('') + '</tr>' +
  rows.map((row, i) => `<tr><td class="time"><div class="divIE" style="display:none">${9 + i}:00-${9 + i}:50</div></td>` + [0, 1, 2, 3, 4, 5].map(d => lessonCell(row[d])).join('') + '</tr>').join('') + '</table></div>';
const weekUrl = n => `/student/myschedule/2026/1/${dmy(day(n))}/${dmy(day(n + 6))}/`;
const settle = async () => { for (let i = 0; i < 20; i++) await new Promise(r => setImmediate(r)); };

function page(content, { native = () => {}, routes = {} } = {}) {
  const html = '<table class="wrappertable"><tr id="l1"><td class="header">КазНУ</td></tr><tr id="l4"><td>' + content + '</td></tr></table>';
  const errors = [], virtualConsole = new VirtualConsole();
  virtualConsole.on('jsdomError', error => errors.push(String(error.message)));
  const dom = new JSDOM(html, { url: 'https://univer.kaznu.kz/student/myschedule/', runScripts: 'outside-only', virtualConsole });
  const w = dom.window; opened.push(w);
  Object.defineProperty(w.document, 'readyState', { value: 'complete', configurable: true });
  w.HTMLElement.prototype.getClientRects = function () { return this.closest('[hidden]') ? [] : [{ width: 100, height: 30 }]; };
  w.jQuery = () => ({ bind() {} });
  w.fetch = address => { const path = String(address).replace('https://univer.kaznu.kz', ''); const known = path in routes;
    return Promise.resolve({ ok: known, status: known ? 200 : 404, url: String(address), text: async () => routes[path] || '' }); };
  if (native) w.__univerNative = native;
  scripts.forEach(script => w.eval(script));
  w.__univerMobileV1.setEnabled(true, 'ru');
  w.__univerStyleV1.apply(true);
  return { w, d: w.document, errors };
}
// A tap the student made (isTrusted), which a page script cannot dispatch.
function tap(w, node) {
  const event = new w.MouseEvent('click', { bubbles: true, cancelable: true });
  jsdomUtils.implForWrapper(event).isTrusted = true;
  jsdomUtils.implForWrapper(node)._dispatch(jsdomUtils.implForWrapper(event));
}
const card = (p, subject) => Array.from(p.d.querySelectorAll('.univer-app-lesson')).find(c => c.querySelector('.univer-app-lesson-name').textContent === subject);
const message = sent => JSON.parse(sent.replace(/^univer-calendar:/, ''));
// The next time a class at 9:00 on [shift] (Monday = 0) begins: this week's, or next week's once it has begun.
const next = shift => { const d = day(shift); d.setHours(9, 0, 0, 0); while (d < Date.now()) d.setDate(d.getDate() + 7); return d; };

const POLITICS = ['Политология', 'лекция', 'Учителева А. П.', dmy(PERIOD_END)], CULTURE = ['Культурология', 'семинар', 'Незнакомов Н. Н.'];

test('a class card hands its next meeting to the calendar, weekly through its course dates when both weeks have it, only on a tap', async () => {
  const sent = [];
  // Both weeks hold both classes: the neighbour is read and nothing alternates.
  const same = schedule([[POLITICS, null, CULTURE]], 7);
  const p = page(schedule([[POLITICS, null, CULTURE]]), { native: m => sent.push(m), routes: { [weekUrl(7)]: same } });
  const politics = card(p, 'Политология').querySelector('button.univer-app-calendar');
  assert.ok(politics, 'the class card has its calendar button');
  assert.equal(politics.textContent, 'В календарь');
  politics.click(); await settle();
  assert.equal(sent.length, 0, 'a scripted click sends nothing');

  tap(p.w, politics); await settle();
  assert.equal(sent.length, 1);
  const lecture = message(sent[0]), start = next(0), end = new Date(start); end.setMinutes(50);
  assert.equal(lecture.title, 'Политология (лекция)');
  assert.equal(lecture.start, iso(start) + 'T09:00');
  assert.equal(lecture.end, iso(end) + 'T09:50');
  assert.equal(lecture.place, 'Ауд. 309, ФИТ');
  assert.equal(lecture.notes, 'Учителева А. П.');
  assert.equal(lecture.until, iso(PERIOD_END));
  assert.equal(lecture.interval, undefined, 'in both weeks: every week (no interval is sent)');
  assert.equal(lecture.partial, undefined);

  tap(p.w, card(p, 'Культурология').querySelector('button.univer-app-calendar')); await settle();
  const seminar = message(sent[1]);
  assert.equal(seminar.start, iso(next(2)) + 'T09:00');
  assert.equal(seminar.until, null, 'without course dates the class goes in once');
  assert.deepEqual(p.errors, []);
});

test('a class of only the shown week repeats every second week, from this week or the one after next', async () => {
  const sent = [];
  // The neighbour has only the seminar: the lecture alternates, the seminar does not.
  const p = page(schedule([[POLITICS, null, CULTURE]]), { native: m => sent.push(m), routes: { [weekUrl(7)]: schedule([[null, null, CULTURE]], 7) } });
  tap(p.w, card(p, 'Политология').querySelector('button.univer-app-calendar')); await settle();
  const lecture = message(sent[0]);
  assert.equal(lecture.interval, 2);
  assert.equal(lecture.partial, undefined);
  // The series' meetings are this Monday plus whole fortnights: the first one still ahead.
  const first = day(0); first.setHours(9, 0, 0, 0);
  while (first < Date.now()) first.setDate(first.getDate() + 14);
  assert.equal(lecture.start, iso(first) + 'T09:00');
  tap(p.w, card(p, 'Культурология').querySelector('button.univer-app-calendar')); await settle();
  assert.equal(message(sent[1]).interval, undefined, 'the same class in both weeks is weekly');
  assert.deepEqual(p.errors, []);
});

test('with the neighbouring week unreadable the class goes in every second week and the app is told', async () => {
  const unreadable = [{}, { [weekUrl(7)]: '<html><body>Вход <form id="login_frm"></form></body></html>' }, { [weekUrl(7)]: schedule([[POLITICS]]) /* names the shown week again */ }];
  for (const routes of unreadable) {
    const sent = [];
    const p = page(schedule([[POLITICS]]), { native: m => sent.push(m), routes });
    tap(p.w, card(p, 'Политология').querySelector('button.univer-app-calendar')); await settle();
    assert.equal(sent.length, 1);
    const lecture = message(sent[0]);
    assert.equal(lecture.interval, 2, 'only what is known is written: the shown week, every second week');
    assert.equal(lecture.partial, true);
  }
});

test('the calendar button needs the app\'s sender and leaves with the Original layout', () => {
  const lone = page(schedule([[['Политология', 'лекция', 'Учителева А. П.']]]), { native: null });
  assert.ok(card(lone, 'Политология'), 'the card is drawn');
  assert.equal(lone.d.querySelector('button.univer-app-calendar'), null, 'no sender, no button');

  const p = page(schedule([[['Политология', 'лекция', 'Учителева А. П.']]]));
  assert.ok(p.d.querySelector('button.univer-app-calendar'));
  p.w.__univerMobileV1.setEnabled(false, 'ru');
  assert.equal(p.d.querySelector('button.univer-app-calendar'), null);
});
