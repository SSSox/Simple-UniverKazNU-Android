const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');

// The week the app keeps for the widget, class reminders and the offline schedule (univer-schedule:). The schedule
// follows the site's structure (td.time with the hidden div.divIE, td.field > .groups > div, rowspans); names are synthetic.
const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const scripts = ['univer-dom.js', 'univer-lesson.js', 'univer-overview.js'].map(read);
const cell = (subject, type, teacher, room, period, span = 1) => `<td class="field"${span > 1 ? ` rowspan="${span}"` : ''}><div class="groups"><div><p class="teacher">${subject}(${type})</p>` +
  `<p class="teacher">${teacher}</p><p class="params"><span class="aud_faculty">ФИТ</span><span>Ауд.: ${room}</span>${period ? `<span>Период с ${period[0]} по ${period[1]}</span>` : ''}</p></div></div></td>`;
const time = t => `<td class="time"><div class="divIE" style="display:none">${t}</div></td>`;
const week = (selected = 'Неделя 12. 16.11.2026 - 22.11.2026', value = '/student/myschedule/2026/1/16.11.2026/22.11.2026/') =>
  `<select id="weekSelect"><option value="/student/myschedule/2026/1/09.11.2026/15.11.2026/">Неделя 11. 09.11.2026 - 15.11.2026</option><option value="${value}" selected>${selected}</option></select>`;
// Monday 9:00 Политология for two rows (9:00–10:50) with course dates; Wednesday 10:00 Культурология without them;
// Saturday 9:00 two subgroups.
const SCHEDULE = '<table class="schedule"><tr><th>&nbsp;</th>' + ['ПН', 'ВТ', 'СР', 'ЧТ', 'ПТ', 'СБ'].map(d => '<th>' + d + '</th>').join('') + '</tr>' +
  '<tr>' + time('09:00-09:50') + cell('Политология', 'лекция', 'Учителева А. П.', '309', ['01.09.2026', '15.12.2026'], 2) + '<td class="field"></td><td class="field"></td><td class="field"></td><td class="field"></td>' +
  '<td class="field"><div class="groups"><div><p class="teacher">Иностранный язык(практика)</p><p class="teacher">Языкова Я. Я.</p><p class="params"><span>Ауд.: 101</span></p></div>' +
  '<div><p class="teacher">Иностранный язык(практика)</p><p class="teacher">Другова Д. Д.</p><p class="params"><span>Ауд.: 102</span></p></div></div></td></tr>' +
  '<tr>' + time('10:00-10:50') + '<td class="field"></td>' + cell('Культурология', 'семинар', 'Незнакомов Н. Н.', '233') + '<td class="field"></td><td class="field"></td><td class="field"></td></tr></table>';

function page(path, content, { native = () => {}, day = 18, hour = 10, routes = {} } = {}) {
  const dom = new JSDOM('<body><div id="l4"><table class="mt"></table>' + content + '</div></body>', { url: 'https://univer.kaznu.kz' + path, runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window, RealDate = w.Date;
  w.Date = class extends RealDate { constructor(...args) { super(...(args.length ? args : [2026, 10, day, hour, 30])); } static now() { return new RealDate(2026, 10, day, hour, 30).getTime(); } };
  w.TextDecoder = TextDecoder;
  w.localStorage.setItem('univer.app.account.v1', 'acc-1');
  w.fetch = async url => { const path = String(url).replace('https://univer.kaznu.kz', ''); return { status: 200, url: 'https://univer.kaznu.kz' + path, text: async () => routes[path] ?? '<html><body></body></html>' }; };
  if (native) w.__univerNative = native;
  scripts.forEach(script => w.eval(script));
  return w;
}
const settle = async () => { for (let i = 0; i < 30; i++) await new Promise(resolve => setImmediate(resolve)); };
const parse = html => new JSDOM(html).window.document;
const sentWeeks = sent => sent.map(m => { assert.match(m, /^univer-schedule:/); return JSON.parse(m.slice('univer-schedule:'.length)); });

test('the week holding today is read with weekdays, clock times, names and course dates', () => {
  const w = page('/student/myschedule/', week() + SCHEDULE, { native: null });
  const t = JSON.parse(JSON.stringify(w.__univerOverviewV1.timetable(w.document, new w.Date())));
  assert.equal(t.v, 1);
  assert.equal(t.week, '2026-11-16');
  assert.equal(t.owner, 'acc-1');
  assert.deepEqual(t.lessons, [
    { day: 0, start: '09:00', end: '10:50', subject: 'Политология', type: 'лекция', room: '309', faculty: 'ФИТ', teacher: 'Учителева А. П.', from: '2026-09-01', until: '2026-12-15' },
    { day: 2, start: '10:00', end: '10:50', subject: 'Культурология', type: 'семинар', room: '233', faculty: 'ФИТ', teacher: 'Незнакомов Н. Н.', from: null, until: null },
    { day: 5, start: '09:00', end: '09:50', subject: 'Иностранный язык', type: 'практика', room: '101', faculty: '', teacher: 'Языкова Я. Я.', from: null, until: null },
    { day: 5, start: '09:00', end: '09:50', subject: 'Иностранный язык', type: 'практика', room: '102', faculty: '', teacher: 'Другова Д. Д.', from: null, until: null }]);
  w.close();
});

test('course dates without a year span the week they are shown in', () => {
  const w = page('/student/myschedule/', '', { native: null });
  const dates = (period, selected, value) => JSON.parse(JSON.stringify(w.__univerOverviewV1.timetable(parse(week(selected, value) +
    '<table class="schedule"><tr><th>&nbsp;</th><th>ПН</th></tr><tr>' + time('09:00-09:50') + cell('Политология', 'лекция', 'Учителева А. П.', '309', period) + '</tr></table>'), new w.Date()))).lessons[0];
  const autumn = dates(['01.09', '12.12']);
  assert.deepEqual([autumn.from, autumn.until], ['2026-09-01', '2026-12-12']);
  const reversed = dates(['12.12.2026', '01.09.2026']);
  assert.deepEqual([reversed.from, reversed.until], [null, null], 'a span the phone cannot read is left out');
  w.close();
  // In January the autumn course's first day is last year's.
  const winter = page('/student/myschedule/', '', { native: null, day: 13 });
  winter.Date = class extends winter.Date { constructor(...args) { super(...(args.length ? args : [2027, 0, 13, 10, 30])); } };
  const january = JSON.parse(JSON.stringify(winter.__univerOverviewV1.timetable(parse(week('Неделя 20. 11.01.2027 - 17.01.2027', '/student/myschedule/2026/1/11.01.2027/17.01.2027/') +
    '<table class="schedule"><tr><th>&nbsp;</th><th>ПН</th></tr><tr>' + time('09:00-09:50') + cell('Политология', 'лекция', 'Учителева А. П.', '309', ['01.09', '20.01']) + '</tr></table>'), new winter.Date()))).lessons[0];
  assert.deepEqual([january.from, january.until], ['2026-09-01', '2027-01-20']);
  winter.close();
});

test('another week, a page without week dates and a page without the table give nothing', () => {
  const w = page('/student/myschedule/', '', { native: null });
  const read = html => w.__univerOverviewV1.timetable(parse(html), new w.Date());
  assert.equal(read(week('Неделя 11. 09.11.2026 - 15.11.2026', '/student/myschedule/2026/1/09.11.2026/15.11.2026/') + SCHEDULE), null);
  assert.equal(read('<select id="weekSelect"><option selected>Неделя 12</option></select>' + SCHEDULE), null);
  assert.equal(read(SCHEDULE), null, 'no week selector: the week cannot be told');
  assert.equal(read(week()), null);
  w.close();
});

test('the schedule page hands its week to the app once per page', async () => {
  const sent = [];
  const w = page('/student/myschedule/', week() + SCHEDULE, { native: m => sent.push(m) });
  w.__univerOverviewV1.apply('ru');
  w.__univerOverviewV1.apply('ru');
  await settle();
  const weeks = sentWeeks(sent);
  assert.equal(weeks.length, 1, 'a second pass over the same page sends nothing');
  assert.equal(weeks[0].lessons.length, 4);
  w.close();
  const other = [];
  const old = page('/student/myschedule/2026/1/09.11.2026/15.11.2026/', week('Неделя 11. 09.11.2026 - 15.11.2026', '/student/myschedule/2026/1/09.11.2026/15.11.2026/') + SCHEDULE, { native: m => other.push(m) });
  old.__univerOverviewV1.apply('ru');
  await settle();
  assert.deepEqual(other, [], 'a past week the student looks at is not saved as this week');
  old.close();
});

test('Бакалавр hands over the week it reads for its Today panel; without the app\'s sender nothing is sent', async () => {
  const sent = [];
  const routes = { '/student/myschedule/': '<html><body>' + week() + SCHEDULE + '</body></html>' };
  const w = page('/student/bachelor/', '', { native: m => sent.push(m), routes });
  w.__univerOverviewV1.apply('ru');
  for (let i = 0; i < 200 && !sent.length; i++) await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(sentWeeks(sent)[0].week, '2026-11-16');
  w.__univerOverviewV1.release(); w.close();
  const lone = page('/student/myschedule/', week() + SCHEDULE, { native: null });
  assert.doesNotThrow(() => lone.__univerOverviewV1.apply('ru'));
  lone.close();
});

// The neighbouring week (the site alternates odd and even weeks) goes along, so the widget and reminders follow the parity.
const NEIGHBOUR = '/student/myschedule/2026/1/09.11.2026/15.11.2026/';
const neighbourPage = '<html><body><select id="weekSelect"><option value="' + NEIGHBOUR + '" selected>Неделя 11. 09.11.2026 - 15.11.2026</option>' +
  '<option value="/student/myschedule/2026/1/16.11.2026/22.11.2026/">Неделя 12. 16.11.2026 - 22.11.2026</option></select>' +
  '<table class="schedule"><tr><th>&nbsp;</th><th>ПН</th><th>ВТ</th></tr><tr>' + time('09:00-09:50') + '<td class="field"></td>' + cell('Физика', 'лекция', 'Физиков Ф. Ф.', '101') + '</tr></table></body></html>';

test('the schedule page sends the week at once and its neighbour once a session; an unread neighbour is never guessed', async () => {
  const sent = [];
  const w = page('/student/myschedule/', week() + SCHEDULE, { native: m => sent.push(m), routes: { [NEIGHBOUR]: neighbourPage } });
  w.__univerOverviewV1.apply('ru');
  assert.equal(sent.length, 1, 'the week goes before the neighbour is read');
  assert.equal('second' in sentWeeks(sent)[0], false);
  await settle();
  const saved = sentWeeks(sent)[1];
  assert.equal(sent.length, 2, 'then once more with its neighbour');
  assert.equal(saved.week, '2026-11-16');
  assert.equal(saved.lessons.length, 4);
  assert.equal(saved.second.week, '2026-11-09', 'the week right before: nothing is listed after');
  assert.deepEqual(saved.second.lessons.map(l => [l.day, l.start, l.end, l.subject, l.room]), [[1, '09:00', '09:50', 'Физика', '101']]);
  const paired = Object.fromEntries(Object.keys(w.sessionStorage).map(k => [k, w.sessionStorage.getItem(k)]));
  w.close();
  // The next visit in the same app session sends the week alone and asks the site for nothing more.
  const again = [], asked = [];
  const q = page('/student/myschedule/', week() + SCHEDULE, { native: m => again.push(m), routes: { [NEIGHBOUR]: neighbourPage } });
  for (const [k, v] of Object.entries(paired)) q.sessionStorage.setItem(k, v);
  const fetch = q.fetch; q.fetch = (...args) => { asked.push(String(args[0])); return fetch(...args); };
  q.__univerOverviewV1.apply('ru');
  await settle();
  assert.equal(again.length, 1); assert.equal('second' in sentWeeks(again)[0], false);
  assert.deepEqual(asked.filter(u => u.includes('09.11.2026')), [], 'no second request');
  q.close();
  for (const routes of [{}, { [NEIGHBOUR]: SCHEDULE }, { [NEIGHBOUR]: '<form id="login_frm"></form>' }]) {
    const alone = [];
    const p = page('/student/myschedule/', week() + SCHEDULE, { native: m => alone.push(m), routes });
    p.__univerOverviewV1.apply('ru');
    await settle();
    assert.equal(sentWeeks(alone).length, 1);
    assert.equal('second' in sentWeeks(alone)[0], false, 'an unread neighbour is left out, not guessed');
    p.close();
  }
});

test('a schedule that lists no neighbouring week says so once, so the app drops the neighbour it kept', async () => {
  const sent = [];
  const lone = '<select id="weekSelect"><option value="/student/myschedule/2026/1/16.11.2026/22.11.2026/" selected>Неделя 12. 16.11.2026 - 22.11.2026</option></select>';
  const w = page('/student/myschedule/', lone + SCHEDULE, { native: m => sent.push(m) });
  w.__univerOverviewV1.apply('ru');
  await settle();
  const weeks = sentWeeks(sent);
  assert.equal(weeks.length, 2);
  assert.equal(weeks[0].single, undefined, 'the week at once, before the list is read');
  assert.equal(weeks[1].single, true);
  assert.equal('second' in weeks[1], false);
  w.close();
});

test('both weeks past the channel\'s limit are not sent; the week already sent stays and is not read again', async () => {
  // 80 classes a week, every field at its length cap and made of quotes, which double in JSON: one week fits, both do not.
  const q = n => '"'.repeat(n), two = n => String(n).padStart(2, '0');
  const rows = Array.from({ length: 40 }, (_, i) => { const m = 8 * 60 + i * 15, at = two(m / 60 | 0) + ':' + two(m % 60) + '-' + two((m + 10) / 60 | 0) + ':' + two((m + 10) % 60);
    return '<tr>' + time(at) + cell(q(200), q(60), q(200), q(100)) + cell(q(200), q(60), q(200), q(100)) + '</tr>'; }).join('');
  const table = '<table class="schedule"><tr><th>&nbsp;</th><th>ПН</th><th>ВТ</th></tr>' + rows + '</table>';
  const huge = neighbourPage.replace(/<table class="schedule">[\s\S]*<\/table>/, table);
  const sent = [];
  const w = page('/student/myschedule/', week() + table, { native: m => sent.push(m), routes: { [NEIGHBOUR]: huge } });
  w.__univerOverviewV1.apply('ru');
  await settle();
  assert.equal(sent.length, 1, 'only the week alone');
  assert.ok(sent[0].length > 65536 && sent[0].length <= 131072, 'the week alone is near the limit: ' + sent[0].length);
  assert.notEqual(w.sessionStorage.getItem('univer.app.week-paired.v1'), null, 'not read again this session');
  w.close();
});

test('any other page reads the schedule once a day an app session, so the widget gets the week without a visit to it', async () => {
  const routes = { '/student/myschedule/': '<html><body>' + week() + SCHEDULE + '</body></html>' };
  const now = w => { const real = w.setTimeout; w.setTimeout = (f, ms, ...a) => real(f, ms === 4000 ? 0 : ms, ...a); };
  const sent = [], asked = [];
  const w = page('/news/', '<p>Новости</p>', { native: m => sent.push(m), routes });
  now(w); const fetch = w.fetch; w.fetch = (...a) => { asked.push(String(a[0])); return fetch(...a); };
  w.__univerOverviewV1.apply('ru');
  for (let i = 0; i < 200 && !sent.length; i++) await new Promise(r => setTimeout(r, 5));
  assert.equal(sentWeeks(sent)[0].week, '2026-11-16');
  const marks = Object.fromEntries(Object.keys(w.sessionStorage).map(k => [k, w.sessionStorage.getItem(k)]));
  w.close();
  // Another page the same day and app session asks for nothing.
  const again = [], asked2 = [];
  const q = page('/student/umkd/', '<p>УМКД</p>', { native: m => again.push(m), routes });
  for (const [k, v] of Object.entries(marks)) q.sessionStorage.setItem(k, v);
  now(q); const fetch2 = q.fetch; q.fetch = (...a) => { asked2.push(String(a[0])); return fetch2(...a); };
  q.__univerOverviewV1.apply('ru'); await settle();
  assert.deepEqual([again.length, asked2.length], [0, 0]);
  q.close();
  // The login page, or a page left before the read starts, reads nothing and keeps the day for the next page.
  const login = [], left = page('/news/', '<form id="login_frm"></form>', { native: m => login.push(m), routes });
  now(left); left.__univerOverviewV1.apply('ru'); await settle();
  assert.equal(login.length, 0); left.close();
  const early = page('/news/', '<p>Новости</p>', { native: m => login.push(m), routes });
  early.__univerOverviewV1.apply('ru'); early.__univerOverviewV1.release(); await new Promise(r => setTimeout(r, 4100));
  assert.equal(login.length, 0); assert.equal(early.sessionStorage.getItem('univer.app.week-fresh.v1'), null);
  early.close();
});

test('after the schedule page saved today\'s week another page does not read it again', async () => {
  const s = page('/student/myschedule/', week() + SCHEDULE, { native: () => {} });
  s.__univerOverviewV1.apply('ru');
  const marks = Object.fromEntries(Object.keys(s.sessionStorage).map(k => [k, s.sessionStorage.getItem(k)]));
  s.close();
  const asked = [], q = page('/news/', '<p>Новости</p>', { native: () => {} });
  for (const [k, v] of Object.entries(marks)) q.sessionStorage.setItem(k, v);
  const real = q.setTimeout; q.setTimeout = (f, ms, ...a) => real(f, ms === 4000 ? 0 : ms, ...a);
  const fetch = q.fetch; q.fetch = (...a) => { asked.push(String(a[0])); return fetch(...a); };
  // A real timer, not setImmediate rounds: the shortened 4 s delay must have run for the check to mean anything.
  q.__univerOverviewV1.apply('ru'); await new Promise(r => setTimeout(r, 50)); await settle();
  assert.deepEqual(asked.filter(u => /\/student\/myschedule\/$/.test(u)), []);
  q.close();
});
