const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const jsdomUtils = require('jsdom/lib/jsdom/living/generated/utils');

// A click the user made (isTrusted), which a page script cannot dispatch.
function trustedClick(w, node) {
  const event = new w.MouseEvent('click', {bubbles: true, cancelable: true});
  const impl = jsdomUtils.implForWrapper(event); impl.isTrusted = true;
  jsdomUtils.implForWrapper(node)._dispatch(impl);
}

// Score bands (univer-dom.js scoreBand) and the journal's totals drawn against the marks 50, 70 and 90. Courses and
// scores are made up; the markup follows the observed /student/attendance/full/ journal.
const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const lesson = (id, sum) => `<tr class="mid"><td class="ts"><a class="lt" href="/student/attendance/show/${id}/2026/1">лекция - Teacher ${id}</a></td></tr>` +
  `<tr><td><table class="inner"><tr><th rowspan="2">РК1</th><th>01.09</th><th>Сумма</th></tr><tr><td>${sum}</td><td>${sum}</td></tr></table></td></tr>`;
const course = (name, totals, id) => `<tr class="top"><td class="ct">${name} ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>` +
  `<tr class="mid"><td class="ct"><b>${totals}</b></td></tr>` + lesson(id, 5);
const JOURNAL = '<table class="wrappertable"><tr id="l4"><td class="center"><table class="mt">' +
  course('Социология', 'РК1 (100): 63 РК2 (100): 0', 1) + course('Психология', 'РК1 (100): 92 РК2 (100):', 3) +
  '</table></td></tr></table>';

async function page(language = 'ru', journal = JOURNAL, {local = {}, session = {}} = {}) {
  const dom = new JSDOM('<!doctype html><html><body>' + journal + '</body></html>', {url: 'https://univer.kaznu.kz/student/attendance/full/', runScripts: 'outside-only'});
  const w = dom.window;
  if (w.document.readyState === 'loading') await new Promise(done => w.document.addEventListener('DOMContentLoaded', done));
  Object.entries(local).forEach(([key, value]) => w.localStorage.setItem(key, value));
  Object.entries(session).forEach(([key, value]) => w.sessionStorage.setItem(key, value));
  w.eval(read('univer-dom.js')); w.eval(read('univer-attendance.js'));
  w.__univerAttendanceV1.apply(language);
  return {w, d: w.document, all: s => Array.from(w.document.querySelectorAll(s))};
}

test('a score falls in its band by its share of the maximum, with the next mark and the points still missing', () => {
  const w = new JSDOM('', {url: 'https://univer.kaznu.kz/', runScripts: 'outside-only'}).window;
  w.eval(read('univer-dom.js'));
  const band = (value, max) => { const found = w.__univerDomV1.scoreBand(value, max); return found && {band: found.band, next: found.next, left: found.left}; };
  assert.deepEqual(band('49.9', '100'), {band: 'low', next: 50, left: 0.1});
  assert.deepEqual(band('0', '100'), {band: 'low', next: 50, left: 50});
  assert.deepEqual(band('50', '100'), {band: 'pass', next: 70, left: 20});
  assert.deepEqual(band('69', '100'), {band: 'pass', next: 70, left: 1});
  assert.deepEqual(band('70', '100'), {band: 'good', next: 90, left: 20});
  assert.deepEqual(band('89,5', '100'), {band: 'good', next: 90, left: 0.5});
  assert.deepEqual(band('90', '100'), {band: 'top', next: null, left: 0});
  assert.deepEqual(band('100', '100'), {band: 'top', next: null, left: 0});
  // Another maximum keeps the same shares: 12,5 of 25 is half, the marks are 12,5, 17,5 and 22,5 points.
  assert.deepEqual(band('12,5', '25'), {band: 'pass', next: 17.5, left: 5});
  assert.deepEqual(Array.from(w.__univerDomV1.scoreBand('12,5', '25').marks), [12.5, 17.5, 22.5]);
  for (const [value, max] of [['', '100'], ['–', '100'], ['5', ''], ['5', '0'], ['5 баллов', '100'], [undefined, '100']])
    assert.equal(w.__univerDomV1.scoreBand(value, max), null, `${value} of ${max}`);
  w.close();
});

test('each course total shows its band in words, a bar with the marks and how far the next mark is', async () => {
  const p = await page();
  const [first, second, third, fourth] = p.all('.univer-app-progress');
  assert.equal(p.all('.univer-app-progress').length, 4, 'two periods for each of two courses');
  assert.equal(p.all('.univer-app-score-total').length, 0, 'the total is no longer a table row');

  assert.equal(first.dataset.band, 'pass');
  assert.equal(first.querySelector('.univer-app-progress-band').textContent, 'Удовлетворительно');
  assert.equal(first.querySelector('.univer-app-progress-value').textContent, '63 из 100');
  assert.equal(first.querySelector('.univer-app-progress-next').textContent, 'ещё 7 до 70');
  assert.deepEqual(Array.from(first.querySelectorAll('.univer-app-progress-scale>span'), s => s.textContent), ['50', '70', '90']);
  assert.equal(first.querySelectorAll('.univer-app-progress-track>i.univer-app-progress-passed').length, 1, 'only the 50 mark is passed');
  assert.equal(first.querySelector('.univer-app-progress-track').style.getPropertyValue('--univer-score'), '63%');
  assert.equal(first.getAttribute('aria-label'), 'РК1: 63 из 100. Удовлетворительно. ещё 7 до 70');

  assert.equal(second.dataset.band, 'low');
  assert.equal(second.querySelector('.univer-app-progress-next').textContent, 'ещё 50 до 50');
  assert.equal(third.dataset.band, 'top');
  assert.equal(third.querySelector('.univer-app-progress-next').textContent, 'Все пороги пройдены');
  // A period without a grade is one quiet line: no band, no bar.
  assert.equal(fourth.dataset.band, undefined);
  assert.equal(fourth.querySelector('.univer-app-progress-track'), null);
  assert.equal(fourth.getAttribute('aria-label'), 'РК2: нет');

  // The course summary's chips carry the same bands, and the legend says what each colour means.
  assert.deepEqual(p.all('.univer-app-attendance-overview .univer-app-overview-score').map(c => c.dataset.band ?? ''), ['pass', 'low', 'top', '']);
  assert.deepEqual(p.all('.univer-app-band-legend-item').map(i => i.dataset.band), ['low', 'pass', 'good', 'top']);
  assert.match(p.d.querySelector('.univer-app-band-legend').textContent, /50–69: удовлетворительно, стипендия под риском/);
  p.w.close();
});

test('the bands speak the chosen language', async () => {
  const kz = await page('kz');
  assert.equal(kz.d.querySelector('.univer-app-progress-band').textContent, 'Қанағаттанарлық');
  assert.equal(kz.d.querySelector('.univer-app-progress-next').textContent, '70 шегіне 7 жетпейді');
  kz.w.close();
  const en = await page('en');
  assert.equal(en.d.querySelector('.univer-app-progress-next').textContent, '7 short of 70');
  assert.equal(en.d.querySelector('.univer-app-progress-value').textContent, '63 of 100');
  en.w.close();
  // The site writes a decimal comma; English shows a point in both the total and what is missing.
  const half = await page('en', JOURNAL.replace('РК1 (100): 63 РК2 (100): 0', 'РК1 (25): 12,5'));
  assert.equal(half.d.querySelector('.univer-app-progress-value').textContent, '12.5 of 25');
  assert.equal(half.d.querySelector('.univer-app-progress-next').textContent, '5 short of 17.5');
  half.w.close();
});

test('the report button sends every course\'s totals, the term and the student name to the app, whatever is chosen', async () => {
  const nav = '<a href="/student/attendance/full/2025/2">Предыдущий</a><a href="https://univer.kaznu.kz/student/attendance/full/2026/2">Следующий</a>';
  const html = '<!doctype html><html><body><table><tr id="l2"><td><a href="/user/pass/">Иванова  Айгерим (ivanova.a: 123456)</a></td></tr></table>' +
    JOURNAL.replace('<table class="mt">', '<table class="mt"><tr><td>' + nav + '</td></tr>') + '</body></html>';
  const w = new JSDOM(html, {url: 'https://univer.kaznu.kz/student/attendance/full/', runScripts: 'outside-only'}).window;
  if (w.document.readyState === 'loading') await new Promise(done => w.document.addEventListener('DOMContentLoaded', done));
  const sent = [];
  w.__univerNative = message => { if (message.startsWith('univer-report:')) sent.push(message); };
  w.eval(read('univer-dom.js')); w.eval(read('univer-attendance.js'));
  w.__univerAttendanceV1.apply('ru');
  const button = w.document.querySelector('.univer-app-attendance-report');
  assert.equal(button.hidden, false);
  assert.equal(button.textContent, 'Поделиться успеваемостью: PDF или картинка');
  // A chosen course does not narrow the report.
  Array.from(w.document.querySelectorAll('.univer-app-attendance-courses button')).find(b => b.textContent === 'Психология').click();
  button.click();
  assert.equal(sent.length, 0, "a page script's click opens nothing");
  trustedClick(w, button);
  assert.equal(sent.length, 1);
  assert.ok(sent[0].startsWith('univer-report:'));
  assert.deepEqual(JSON.parse(sent[0].slice('univer-report:'.length)), {
    v: 1, term: {year: 2026, term: 1}, student: 'Иванова Айгерим',
    courses: [
      {name: 'Социология', scores: [{label: 'РК1', max: '100', value: '63'}, {label: 'РК2', max: '100', value: '0'}], absences: 0},
      {name: 'Психология', scores: [{label: 'РК1', max: '100', value: '92'}, {label: 'РК2', max: '100', value: ''}], absences: 0},
    ],
    goal: null,
  });
  w.close();

  // Without the app's channel (a plain browser, a document window) there is no button to press.
  const plain = await page();
  assert.equal(plain.d.querySelector('.univer-app-attendance-report').hidden, true);
  plain.w.close();
});

test('a goal counts what is left to its own mark, in each score\'s own points, and says when it is reached', () => {
  const w = new JSDOM('', {url: 'https://univer.kaznu.kz/', runScripts: 'outside-only'}).window;
  w.eval(read('univer-dom.js'));
  const band = (value, max, goal) => { const found = w.__univerDomV1.scoreBand(value, max, goal); return {band: found.band, next: found.next, left: found.left, reached: found.reached}; };
  assert.deepEqual(band('63', '100', 'top'), {band: 'pass', next: 90, left: 27, reached: false});
  assert.deepEqual(band('63', '100', 'good'), {band: 'pass', next: 70, left: 7, reached: false});
  assert.deepEqual(band('63', '100', 'pass'), {band: 'pass', next: null, left: 0, reached: true});
  assert.deepEqual(band('92', '100', 'top'), {band: 'top', next: null, left: 0, reached: true});
  assert.deepEqual(band('12', '25', 'top'), {band: 'low', next: 22.5, left: 10.5, reached: false});
  // No goal (or a word that is not one) keeps the next mark.
  assert.deepEqual(band('63', '100', null), {band: 'pass', next: 70, left: 7, reached: false});
  assert.deepEqual(band('63', '100', 'great'), {band: 'pass', next: 70, left: 7, reached: false});
  w.close();
});

test('the goal chips change every hint at once, are kept for the next visit and go into the report', async () => {
  const p = await page();
  const chips = () => p.all('.univer-app-goal-list>button');
  assert.equal(p.d.querySelector('.univer-app-goal').hidden, false);
  assert.deepEqual(chips().map(b => b.textContent), ['Нет', 'Удовлетворительно', 'Хорошо', 'Отлично']);
  assert.deepEqual(chips().map(b => b.getAttribute('aria-pressed')), ['true', 'false', 'false', 'false']);
  const hints = () => p.all('.univer-app-progress-next').map(n => n.textContent + (n.dataset.univerLeft ? ' *' : ''));
  assert.deepEqual(hints(), ['ещё 7 до 70 *', 'ещё 50 до 50 *', 'Все пороги пройдены']);
  chips()[3].click();
  assert.deepEqual(chips().map(b => b.getAttribute('aria-pressed')), ['false', 'false', 'false', 'true']);
  assert.deepEqual(hints(), ['ещё 27 до 90 *', 'ещё 90 до 90 *', 'Цель достигнута']);
  assert.match(p.all('.univer-app-progress')[0].getAttribute('aria-label'), /Удовлетворительно\. ещё 27 до 90$/);
  assert.equal(p.w.localStorage.getItem('univer.app.grade-goal.v1'), 'top');
  // A class-type filter shows no totals, so the goal waits.
  p.all('.univer-app-attendance-filters button').find(b => b.dataset.kind === 'lecture').click();
  assert.equal(p.d.querySelector('.univer-app-goal').hidden, true);
  p.w.close();

  const again = await page('ru', JOURNAL, {local: {'univer.app.grade-goal.v1': 'good'}});
  assert.equal(again.all('.univer-app-progress-next')[1].textContent, 'ещё 70 до 70');
  assert.equal(again.d.querySelector('.univer-app-goal-list>button[aria-pressed=true]').textContent, 'Хорошо');
  again.all('.univer-app-goal-list>button')[0].click();
  assert.equal(again.w.localStorage.getItem('univer.app.grade-goal.v1'), null);
  assert.equal(again.all('.univer-app-progress-next')[1].textContent, 'ещё 50 до 50');
  again.w.close();
});

// A journal whose classes carry «н» marks in their date cells: lectures, a seminar and a lab.
const marked = (id, type, cells) => `<tr class="mid"><td class="ts"><a class="lt" href="/student/attendance/show/${id}/2026/1">${type} - Teacher ${id}</a></td></tr>` +
  `<tr><td><table class="inner"><tr><th rowspan="2">РК1</th>${cells.map((_, i) => `<th>0${i + 1}.09</th>`).join('')}<th>Сумма</th></tr>` +
  `<tr>${cells.map(c => `<td>${c}</td>`).join('')}<td>5</td></tr></table></td></tr>`;
const MISSED = '<table class="wrappertable"><tr id="l4"><td class="center"><table class="mt">' +
  '<tr class="top"><td class="ct">Социология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr><tr class="mid"><td class="ct"><b>РК1 (100): 63</b></td></tr>' +
  marked(1, 'лекция', ['н', 'Н', '5', 'нб']) + marked(2, 'семинар', ['н', '4']) + marked(3, 'лабораторная', ['н']) +
  '<tr class="top"><td class="ct">Психология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr><tr class="mid"><td class="ct"><b>РК1 (100): 80</b></td></tr>' +
  marked(4, 'лекция', ['5', '5']) + '</table></td></tr></table>';

test('each course counts its «н» marks by class type; a course without any says nothing', async () => {
  const p = await page('ru', MISSED);
  const lines = () => p.all('.univer-app-attendance-absences').map(n => n.textContent);
  assert.deepEqual(lines(), ['Пропуски: 4 · лекции 2, семинары 1, другие 1']);
  p.all('.univer-app-attendance-filters button').find(b => b.dataset.kind === 'lecture').click();
  assert.deepEqual(lines(), ['Пропуски: 2']);
  p.w.close();
  const en = await page('en', MISSED);
  assert.deepEqual(en.all('.univer-app-attendance-absences').map(n => n.textContent), ['Absences: 4 · lectures 2, seminars 1, other 1']);
  en.w.close();
});

const missedTerm = hours => '<table class="wrappertable"><tr id="l4"><td class="center"><table class="mt">' +
  '<tr class="top"><td class="ct">Социология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr><tr class="mid"><td class="ct"><b>РК1 (100): 63</b></td></tr>' +
  marked(1, 'лекция', ['н', 'Н', '5', 'нб']) + marked(2, 'семинар', ['н', '4']) + marked(3, 'лабораторная', ['н']) +
  '<tr class="top"><td class="ct">Психология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr><tr class="mid"><td class="ct"><b>РК1 (100): 80</b></td></tr>' +
  marked(4, 'лекция', Array(hours - 4).fill('н').concat(['5'])) + '</table></td></tr></table>';

test('more than 25 missed hours in the term warns over every course, whichever course is shown', async () => {
  const shown = p => p.all('.univer-app-attendance-limit').filter(n => !n.hidden).map(n => n.textContent);
  const quiet = await page('ru', missedTerm(25));
  assert.deepEqual(shown(quiet), []);
  quiet.w.close();
  const p = await page('ru', missedTerm(26));
  assert.deepEqual(shown(p), ['Пропущено 26 ч за семестр: это больше 25 ч']);
  p.all('.univer-app-attendance-course-list button').find(b => b.textContent === 'Социология').click();
  p.all('.univer-app-attendance-filters button').find(b => b.dataset.kind === 'seminar').click();
  assert.deepEqual(shown(p), ['Пропущено 26 ч за семестр: это больше 25 ч']);
  p.w.close();
  const kz = await page('kz', missedTerm(30));
  assert.deepEqual(shown(kz), ['Семестрде 30 сағат қатыспадыңыз: бұл 25 сағаттан көп']);
  kz.w.close();
});

test('the report carries each course\'s absences and the chosen goal', async () => {
  const w = new JSDOM('<!doctype html><html><body>' + MISSED + '</body></html>', {url: 'https://univer.kaznu.kz/student/attendance/full/', runScripts: 'outside-only'}).window;
  if (w.document.readyState === 'loading') await new Promise(done => w.document.addEventListener('DOMContentLoaded', done));
  const sent = [];
  w.__univerNative = message => { if (message.startsWith('univer-report:')) sent.push(message); };
  w.localStorage.setItem('univer.app.grade-goal.v1', 'top');
  w.eval(read('univer-dom.js')); w.eval(read('univer-attendance.js'));
  w.__univerAttendanceV1.apply('ru');
  trustedClick(w, w.document.querySelector('.univer-app-attendance-report'));
  const data = JSON.parse(sent[0].slice('univer-report:'.length));
  assert.equal(data.goal, 'top');
  assert.deepEqual(data.courses.map(c => [c.name, c.absences]), [['Социология', 4], ['Психология', 0]]);
  w.close();
});

test('«новое» marks the totals that changed since the previous visit, and only then', async () => {
  const SEEN = 'univer.app.grades-seen.v1', VISIT = {'univer.app.grades-visit.v1': '1'}, ACCOUNT = {'univer.app.account.v1': 'a1'};
  const later = JOURNAL.replace('РК1 (100): 63 РК2 (100): 0', 'РК1 (100): 63 РК2 (100): 45').replace('РК1 (100): 92 РК2 (100):', 'РК1 (100): 92 РК2 (100): 88');
  const marks = p => ({tiles: p.all('.univer-app-progress .univer-app-fresh').length, chips: p.all('.univer-app-overview-score[data-univer-fresh]').length});
  const visit = async (journal, local, session = {}) => { const p = await page('ru', journal, {local, session}); const result = {...marks(p), kept: p.w.localStorage.getItem(SEEN)}; p.w.close(); return result; };

  // The first visit has nothing to compare with; a reload in the same visit neither.
  const first = await visit(JOURNAL, ACCOUNT);
  assert.deepEqual([first.tiles, first.chips], [0, 0]);
  const reload = await visit(later, {...ACCOUNT, [SEEN]: first.kept}, VISIT);
  assert.deepEqual([reload.tiles, reload.chips], [0, 0]);
  // The next visit compares with what the first one showed: two totals got a grade.
  const next = await visit(later, {...ACCOUNT, [SEEN]: first.kept});
  assert.deepEqual([next.tiles, next.chips], [2, 2]);
  const p = await page('ru', later, {local: {...ACCOUNT, [SEEN]: first.kept}});
  const tile = p.all('.univer-app-progress')[1];
  assert.equal(tile.querySelector('.univer-app-fresh').textContent, 'новое');
  assert.match(tile.getAttribute('aria-label'), /новое с прошлого захода$/);
  p.w.close();
  // A reload within that visit keeps the marks; a visit after it no longer shows them.
  const kept = await visit(later, {...ACCOUNT, [SEEN]: next.kept}, VISIT);
  assert.deepEqual([kept.tiles, kept.chips], [2, 2]);
  const after = await visit(later, {...ACCOUNT, [SEEN]: kept.kept});
  assert.deepEqual([after.tiles, after.chips], [0, 0]);
  // Twelve hours count as a new visit even in one app session.
  const old = JSON.parse(next.kept); old.at = Date.now() - 13 * 3600000;
  const stale = await visit(later, {...ACCOUNT, [SEEN]: JSON.stringify(old)}, VISIT);
  assert.deepEqual([stale.tiles, stale.chips], [0, 0]);
  // Another account starts over.
  const other = await visit(later, {'univer.app.account.v1': 'b2', [SEEN]: first.kept});
  assert.deepEqual([other.tiles, other.chips], [0, 0]);
});
