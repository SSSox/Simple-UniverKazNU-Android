const nodeTest = require('node:test');
const opened = [];
const test = (name, body) => nodeTest.test(name, async () => { try { await body(); } finally { opened.splice(0).forEach(w => { try { w.close(); } catch (_) {} }); } });
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');
const jsdomUtils = require('jsdom/lib/jsdom/living/generated/utils');
const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const scripts = ['univer-palette.js', 'univer-dom.js', 'univer-lesson.js', 'univer-mobile-css.js', 'univer-mobile.js', 'univer-style.js'].map(read);

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
const schedule = rows => `<select id="weekSelect"><option value="/student/myschedule/2026/1/${dmy(day(0))}/${dmy(day(6))}/" selected>Неделя 12. ${dmy(day(0))} - ${dmy(day(6))}</option></select>` +
  '<div class="inner"><table class="schedule"><tr><th>&nbsp;</th>' + ['ПН', 'ВТ', 'СР', 'ЧТ', 'ПТ', 'СБ'].map(d => '<th>' + d + '</th>').join('') + '</tr>' +
  rows.map((row, i) => `<tr><td class="time"><div class="divIE" style="display:none">${9 + i}:00-${9 + i}:50</div></td>` + [0, 1, 2, 3, 4, 5].map(d => lessonCell(row[d])).join('') + '</tr>').join('') + '</table></div>';

function page(content, { native = () => {} } = {}) {
  const html = '<table class="wrappertable"><tr id="l1"><td class="header">КазНУ</td></tr><tr id="l4"><td>' + content + '</td></tr></table>';
  const errors = [], virtualConsole = new VirtualConsole();
  virtualConsole.on('jsdomError', error => errors.push(String(error.message)));
  const dom = new JSDOM(html, { url: 'https://univer.kaznu.kz/student/myschedule/', runScripts: 'outside-only', virtualConsole });
  const w = dom.window; opened.push(w);
  Object.defineProperty(w.document, 'readyState', { value: 'complete', configurable: true });
  w.HTMLElement.prototype.getClientRects = function () { return this.closest('[hidden]') ? [] : [{ width: 100, height: 30 }]; };
  w.jQuery = () => ({ bind() {} });
  w.fetch = address => Promise.resolve({ ok: false, status: 404, url: 'https://univer.kaznu.kz' + address, text: async () => '' });
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

test('a class card hands its next meeting to the calendar, weekly through its course dates, only on a tap', () => {
  const sent = [];
  const p = page(schedule([[['Политология', 'лекция', 'Учителева А. П.', dmy(PERIOD_END)], null, ['Культурология', 'семинар', 'Незнакомов Н. Н.']]]),
    { native: m => sent.push(m) });
  const politics = card(p, 'Политология').querySelector('button.univer-app-calendar');
  assert.ok(politics, 'the class card has its calendar button');
  assert.equal(politics.textContent, 'В календарь');
  politics.click();
  assert.equal(sent.length, 0, 'a scripted click sends nothing');

  tap(p.w, politics);
  assert.equal(sent.length, 1);
  const lecture = message(sent[0]), start = next(0), end = new Date(start); end.setMinutes(50);
  assert.equal(lecture.title, 'Политология (лекция)');
  assert.equal(lecture.start, iso(start) + 'T09:00');
  assert.equal(lecture.end, iso(end) + 'T09:50');
  assert.equal(lecture.place, 'Ауд. 309, ФИТ');
  assert.equal(lecture.notes, 'Учителева А. П.');
  assert.equal(lecture.until, iso(PERIOD_END));

  tap(p.w, card(p, 'Культурология').querySelector('button.univer-app-calendar'));
  const seminar = message(sent[1]);
  assert.equal(seminar.start, iso(next(2)) + 'T09:00');
  assert.equal(seminar.until, null, 'without course dates the class goes in once');
  assert.deepEqual(p.errors, []);
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
