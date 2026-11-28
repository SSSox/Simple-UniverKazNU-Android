const nodeTest = require('node:test');
const opened = [];
const test = (name, body) => nodeTest.test(name, async () => { try { await body(); } finally { opened.splice(0).forEach(w => { try { w.close(); } catch (_) {} }); } });
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');
const jsdomUtils = require('jsdom/lib/jsdom/living/generated/utils');

// «В календарь» on a class card and the schedule export, with the keyed channel at document start and the modules inside
// MobilePageAdapter's wrapper (as in download-all-channel.test.cjs). A site script that, after the student's tap, swaps the
// overview's week reader or Date, String and Promise built-ins does not change the calendar event; the export starts only
// on a press of its own button, whatever closest or the button's attributes say.
const SECRET = '0123456789abcdef0123456789abcdef';
const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const channel = read('univer-channel.js').replace('__UNIVER_CHANNEL_SECRET__', SECRET);
const adapter = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
const NATIVE = /NATIVE = ((?:"[^"]*"\s*\+?\s*)+)/.exec(adapter)[1].match(/"([^"]*)"/g).map(part => part.slice(1, -1)).join('');
const template = /fun withSenders\(body: String, senders: String\) = "(.*)"/.exec(adapter)[1].replace(/\\n/g, '\n');
const KEYED = `window.__univerClaim('${SECRET}')`;

const two = n => String(n).padStart(2, '0');
const dmy = d => two(d.getDate()) + '.' + two(d.getMonth() + 1) + '.' + d.getFullYear();
const today = new Date(); today.setHours(0, 0, 0, 0);
const monday = new Date(today); monday.setDate(today.getDate() - (today.getDay() + 6) % 7);
const day = shift => { const d = new Date(monday); d.setDate(monday.getDate() + shift); return d; };
const cell = l => l ? `<td class="field"><div class="groups"><div><p class="teacher">${l[0]}(${l[1]})</p><p class="teacher">${l[2]}</p>` +
  `<p class="params"><span class="aud_faculty">ФИТ</span><span>Ауд.: 309</span><span>Период с ${dmy(day(-21))} по ${dmy(day(70))}</span></p></div></div></td>` : '<td class="field"></td>';
const weekUrl = n => `/student/myschedule/2026/1/${dmy(day(n))}/${dmy(day(n + 6))}/`;
const schedule = (row, shift = 0) => '<div class="ts"><select id="weekSelect">' + [0, 7].map(n => `<option value="${weekUrl(n)}"${n === shift ? ' selected' : ''}>Неделя ${12 + n / 7}. ${dmy(day(n))} - ${dmy(day(n + 6))}</option>`).join('') + '</select></div>' +
  '<div class="inner"><table class="schedule"><tr><th>&nbsp;</th>' + ['ПН', 'ВТ', 'СР'].map(d => '<th>' + d + '</th>').join('') + '</tr>' +
  '<tr><td class="time"><div class="divIE" style="display:none">9:00-9:50</div></td>' + [0, 1, 2].map(d => cell(row[d])).join('') + '</tr></table></div>';
const POLITICS = ['Политология', 'лекция', 'Учителева А. П.'];
const settle = async () => { for (let i = 0; i < 30; i++) await new Promise(r => setImmediate(r)); };

// [before] is a site script that runs after the channel's document-start part and before the modules draw anything.
function page(modules, start, { content = schedule([POLITICS]), path = '/student/myschedule/', before = '' } = {}) {
  const html = '<table class="wrappertable"><tr id="l1"><td class="header">КазНУ</td></tr><tr id="l4"><td>' + content +
    '<a id="site-link" href="#x">Сайт</a></td></tr></table>';
  const w = new JSDOM('<!doctype html><html><body>' + html + '</body></html>', { url: 'https://univer.kaznu.kz' + path, runScripts: 'outside-only', virtualConsole: new VirtualConsole() }).window;
  opened.push(w);
  const app = [];
  w.__univerAppEvents = { postMessage: value => app.push(value) };
  w.__univerNoticeEvents = { postMessage: () => {} };
  w.eval(channel);
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  if (before) w.eval(before);
  Object.defineProperty(w.document, 'readyState', { value: 'complete', configurable: true });
  w.HTMLElement.prototype.getClientRects = function () { return this.closest('[hidden]') ? [] : [{ width: 100, height: 30 }]; };
  w.jQuery = () => ({ bind() {} });
  w.TextDecoder = TextDecoder;
  // The neighbouring week has the same class: it is weekly.
  w.fetch = address => Promise.resolve({ ok: true, status: 200, url: String(address), text: async () => '<table><tr id="l4"><td>' + schedule([POLITICS], 7) + '</td></tr></table>' });
  const body = modules.map(read).join('\n') + '\n' + start + '\nreturn true;';
  assert.equal(w.eval(template.replace('$NATIVE', NATIVE).replace('$body', body).replace('$senders', KEYED)), true);
  return { w, d: w.document, app };
}
function tap(w, node) {
  const event = new w.MouseEvent('click', { bubbles: true, cancelable: true });
  jsdomUtils.implForWrapper(event).isTrusted = true;
  jsdomUtils.implForWrapper(node)._dispatch(jsdomUtils.implForWrapper(event));
}
const sent = (p, prefix) => p.app.filter(m => m.startsWith(SECRET + prefix)).map(m => JSON.parse(m.slice((SECRET + prefix).length)));

const MOBILE = ['univer-palette.js', 'univer-dom.js', 'univer-lesson.js', 'univer-overview.js', 'univer-mobile-css.js', 'univer-mobile.js', 'univer-style.js'];
// What the site does right after the tap: another week reader, a moved clock, other text and a then that answers for the reader.
const TAMPER = `
  window.__univerOverviewV1.weekPair = function () { return Promise.resolve({v:1, weeks:[{lessons:[]}, {lessons:[]}], missing:[]}); };
  window.__univerOverviewV1.readPair = window.__univerOverviewV1.weekPair;
  var shift = 3 * 86400000, realNow = Date.now;
  Date.now = function () { return realNow() + shift; };
  Date.prototype.getHours = function () { return 23; };
  Date.prototype.getMinutes = function () { return 59; };
  Date.prototype.getFullYear = function () { return 1999; };
  Date.prototype.getTime = function () { return 0; };
  String.prototype.padStart = function () { return '77'; };
  String.prototype.replace = function () { return 'Другое событие'; };
  String.prototype.slice = function () { return 'Другое событие'; };
  String.prototype.trim = function () { return 'Другое событие'; };
  String.prototype.split = function () { return ['23', '59']; };
  Array.prototype.some = function () { return false; };
  Array.prototype.filter = function () { return ['Где-то']; };
  var then = Promise.prototype.then;
  Promise.prototype.then = function (done, failed) { return then.call(Promise.resolve({v:1, weeks:[{lessons:[]}, {lessons:[]}], missing:[]}), done, failed); };
  document.querySelector('.univer-app-lesson-name').textContent = 'Другое событие';
`;

test('swapping the week reader and Date, String or Promise built-ins after a real tap does not change the calendar event', async () => {
  const p = page(MOBILE, "window.__univerMobileV1.setEnabled(true,'ru');window.__univerStyleV1.apply(true);");
  const button = p.d.querySelector('button.univer-app-calendar');
  assert.ok(button, 'the keyed channel lets the card have its button');
  tap(p.w, button); await settle();
  const [first] = sent(p, 'univer-calendar:');
  assert.equal(first.title, 'Политология (лекция)');
  assert.equal(first.interval, undefined, 'the neighbour has the class: weekly');
  assert.match(first.start, /T09:00$/);

  // The same tap again, and at once the page swaps the reader, the clock, the text and then.
  tap(p.w, button); p.w.eval(TAMPER); await settle();
  const all = sent(p, 'univer-calendar:');
  assert.equal(all.length, 2);
  assert.deepEqual(all[1], first, 'the event is the one the student saw');
});

test('the week reader swapped on the overview object before the tap is not the one the calendar reads', async () => {
  const p = page(MOBILE, "window.__univerOverviewV1.weekPair=function(){return Promise.resolve({v:1,weeks:[{lessons:[]},{lessons:[]}],missing:[]});};" +
    "window.__univerMobileV1.setEnabled(true,'ru');window.__univerStyleV1.apply(true);");
  tap(p.w, p.d.querySelector('button.univer-app-calendar')); await settle();
  const [event] = sent(p, 'univer-calendar:');
  assert.equal(event.interval, undefined, 'the overview\'s own reader found the class in both weeks');
  assert.equal(event.partial, undefined);
});

test('content the page moves into the calendar button is not a tap on it', async () => {
  const p = page(MOBILE, "window.__univerMobileV1.setEnabled(true,'ru');window.__univerStyleV1.apply(true);");
  const button = p.d.querySelector('button.univer-app-calendar'), link = p.d.getElementById('site-link');
  button.appendChild(link);
  tap(p.w, link); await settle();
  assert.deepEqual(sent(p, 'univer-calendar:'), []);
});

const OVERVIEW = ['univer-dom.js', 'univer-lesson.js', 'univer-overview.js'];
test('a click elsewhere does not start an export, whatever closest answers, and the press keeps its button\'s kind', async () => {
  const p = page(OVERVIEW, "window.__univerOverviewV1.apply('ru');");
  const ics = p.d.querySelector('.univer-app-schedule-export button[data-univer-export=ics]');
  assert.ok(ics);
  // closest now finds the export button for any element, and the student taps the site's own link and the week list.
  p.w.eval("Element.prototype.closest = function () { return document.querySelector('.univer-app-schedule-export button[data-univer-export=ics]'); };");
  tap(p.w, p.d.getElementById('site-link')); tap(p.w, p.d.getElementById('weekSelect')); await settle();
  assert.deepEqual(sent(p, 'univer-export:'), [], 'no export from a click on another element');
  // The site's link moved inside the button is still not the button.
  const link = p.d.getElementById('site-link'); ics.appendChild(link);
  tap(p.w, link); await settle();
  assert.deepEqual(sent(p, 'univer-export:'), []);
  // A real press on the button itself exports, as the kind the button was made with.
  ics.setAttribute('data-univer-export', 'png');
  tap(p.w, ics); await settle();
  const exports = sent(p, 'univer-export:');
  assert.equal(exports.length, 1);
  assert.equal(exports[0].kind, 'ics');
  assert.equal(exports[0].v, 1);
});

// Date readers (and split) a site script swaps at page load, after the channel's document-start part and before the card is
// drawn: the class's Monday, slot times and course end are read through the clock taken at document start.
const EARLY_DATES = `
  (function () {
    var realNow = Date.now, shift = 3 * 86400000;
    Date.now = function () { return realNow() + shift; };
    Date.prototype.getFullYear = function () { return 1999; };
    Date.prototype.getMonth = function () { return 0; };
    Date.prototype.getDate = function () { return 1; };
    Date.prototype.getDay = function () { return 3; };
    Date.prototype.getHours = function () { return 23; };
    Date.prototype.getMinutes = function () { return 59; };
  })();
`;
const iso = d => d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate());
test('Date readers swapped before the card is drawn do not move the class the calendar gets', async () => {
  const p = page(MOBILE, "window.__univerMobileV1.setEnabled(true,'ru');window.__univerStyleV1.apply(true);", { before: EARLY_DATES });
  const button = p.d.querySelector('button.univer-app-calendar');
  assert.ok(button, 'the card has its button');
  p.w.eval("String.prototype.split = function () { return ['23', '59']; };");
  tap(p.w, button); await settle();
  const [event] = sent(p, 'univer-calendar:');
  assert.ok(event, 'the tap sends the event');
  // The next 9:00 Monday class by the real clock: this week's, or a later one (every week or every second week).
  const start = day(0); start.setHours(9, 0, 0, 0);
  const step = event.interval === 2 ? 14 : 7;
  while (start < Date.now()) start.setDate(start.getDate() + step);
  assert.equal(event.start, iso(start) + 'T09:00');
  assert.equal(event.end, iso(start) + 'T09:50');
  assert.equal(event.until, iso(day(70)));
  assert.equal(event.title, 'Политология (лекция)');
});

// The exam days (univer-screens.js) with one coming exam.
const examAt = (() => { const at = new Date(); at.setDate(at.getDate() + 3); at.setHours(11, 0, 0, 0); return at; })();
const EXAMS = (() => {
  const when = `${two(examAt.getDate())}.${two(examAt.getMonth() + 1)}.${examAt.getFullYear()} 11:00`;
  const headings = '<table class="inner"><tr><th>Название дисциплины</th><th>Преподаватель</th><th>Тип</th><th>Аудитория факультета</th><th>Тип группы</th><th>Ссылки</th><th>&nbsp;</th></tr></table>';
  const rows = `<tr><th colspan="7" class="last timerTicked">${when}</th></tr>` +
    `<tr class="link" id="${when}:00"><td>Первая дисциплина </td><td>Тестов А. А.</td><td>Экзамен</td><td>Корп: ФИТ | Ауд.: Онлайн</td><td>сем</td><td></td><td>&nbsp;</td></tr>`;
  return '<table class="mt"><tr class="top"><td class="ct">Расписание экзаменов</td></tr><tr class="mid"><td class="ts"></td></tr>' +
    '<tr class="mid"><td class="tt" id="host">' + headings + '<div class="inner"><table class="inner" id="scheduleList">' + rows + '</table></div></td></tr></table>';
})();
test('the exam calendar event is made with the document-start clock and kept from the drawing, not rebuilt at the tap', async () => {
  // Date readers swapped before the exam days are drawn, String and Date ones again before the tap.
  const before = EARLY_DATES.replace('Date.now = function () { return realNow() + shift; };', '');
  const p = page(['univer-dom.js', 'univer-screens.js'], "document.documentElement.className='univer-mobile';window.__univerScreensV1.apply('ru');", { content: EXAMS, path: '/student/myexam/schedule/', before });
  const button = p.d.querySelector('.univer-scr-calendar');
  assert.ok(button, 'the coming exam has its button');
  p.w.eval(TAMPER.replace(/window\.__univerOverviewV1[^\n]*\n/g, '').replace(/document\.querySelector\('\.univer-app-lesson-name'\)[^\n]*\n/, ''));
  tap(p.w, button); await settle();
  const [event] = sent(p, 'univer-calendar:');
  assert.deepEqual({ ...event }, { v: 1, title: 'Экзамен: Первая дисциплина', start: iso(examAt) + 'T11:00', end: iso(examAt) + 'T13:00',
    place: 'ФИТ · Онлайн', notes: 'Тестов А. А. · сем', until: null });
});

test('the week pair is not on window and a Date.now the page swaps does not make the tap read the neighbour again', async () => {
  const p = page(MOBILE, "window.__univerMobileV1.setEnabled(true,'ru');window.__univerStyleV1.apply(true);");
  assert.equal(p.w.__univerOverviewV1.weekPair, undefined, 'only univer-mobile.js holds the reader');
  let reads = 0; const fetch = p.w.fetch; p.w.fetch = address => { reads++; return fetch(address); };
  const button = p.d.querySelector('button.univer-app-calendar');
  tap(p.w, button); await settle();
  assert.equal(reads, 1);
  p.w.eval('Date.now = function () { return 4102444800000; };');
  tap(p.w, button); await settle();
  assert.equal(reads, 1, 'the cached reading is still fresh by the document-start clock');
  const events = sent(p, 'univer-calendar:');
  assert.equal(events.length, 2);
  assert.deepEqual(events[1], events[0]);
});
