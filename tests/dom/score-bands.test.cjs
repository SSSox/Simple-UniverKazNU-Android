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

async function page(language = 'ru', journal = JOURNAL) {
  const dom = new JSDOM('<!doctype html><html><body>' + journal + '</body></html>', {url: 'https://univer.kaznu.kz/student/attendance/full/', runScripts: 'outside-only'});
  const w = dom.window;
  if (w.document.readyState === 'loading') await new Promise(done => w.document.addEventListener('DOMContentLoaded', done));
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
  assert.equal(first.querySelector('.univer-app-progress-band').textContent, 'Есть допуск');
  assert.equal(first.querySelector('.univer-app-progress-value').textContent, '63 из 100');
  assert.equal(first.querySelector('.univer-app-progress-next').textContent, 'До 70 не хватает 7');
  assert.deepEqual(Array.from(first.querySelectorAll('.univer-app-progress-scale>span'), s => s.textContent), ['50', '70', '90']);
  assert.equal(first.querySelectorAll('.univer-app-progress-track>i.univer-app-progress-passed').length, 1, 'only the 50 mark is passed');
  assert.equal(first.querySelector('.univer-app-progress-track').style.getPropertyValue('--univer-score'), '63%');
  assert.equal(first.getAttribute('aria-label'), 'РК1: 63 из 100. Есть допуск. До 70 не хватает 7');

  assert.equal(second.dataset.band, 'low');
  assert.equal(second.querySelector('.univer-app-progress-next').textContent, 'До 50 не хватает 50');
  assert.equal(third.dataset.band, 'top');
  assert.equal(third.querySelector('.univer-app-progress-next').textContent, 'Все пороги пройдены');
  // A period without a grade is one quiet line: no band, no bar.
  assert.equal(fourth.dataset.band, undefined);
  assert.equal(fourth.querySelector('.univer-app-progress-track'), null);
  assert.equal(fourth.getAttribute('aria-label'), 'РК2: нет');

  // The course summary's chips carry the same bands, and the legend says what each colour means.
  assert.deepEqual(p.all('.univer-app-attendance-overview .univer-app-overview-score').map(c => c.dataset.band ?? ''), ['pass', 'low', 'top', '']);
  assert.deepEqual(p.all('.univer-app-band-legend-item').map(i => i.dataset.band), ['low', 'pass', 'good', 'top']);
  assert.match(p.d.querySelector('.univer-app-band-legend').textContent, /50–69: допуск есть, стипендия под риском/);
  p.w.close();
});

test('the bands speak the chosen language', async () => {
  const kz = await page('kz');
  assert.equal(kz.d.querySelector('.univer-app-progress-band').textContent, 'Рұқсат бар');
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
  w.__univerNative = message => sent.push(message);
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
      {name: 'Социология', scores: [{label: 'РК1', max: '100', value: '63'}, {label: 'РК2', max: '100', value: '0'}]},
      {name: 'Психология', scores: [{label: 'РК1', max: '100', value: '92'}, {label: 'РК2', max: '100', value: ''}]},
    ],
  });
  w.close();

  // Without the app's channel (a plain browser, a document window) there is no button to press.
  const plain = await page();
  assert.equal(plain.d.querySelector('.univer-app-attendance-report').hidden, true);
  plain.w.close();
});
