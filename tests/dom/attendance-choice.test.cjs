const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const lesson = (id, sum) => `<tr class="mid"><td class="ts"><a class="lt" href="/student/attendance/show/${id}/2026/1">лекция - Teacher ${id}</a></td></tr>` +
  `<tr><td><table class="inner"><tr><th rowspan="2">РК1</th><th>01.09</th><th>Сумма</th></tr><tr><td>${sum}</td><td>${sum}</td></tr></table></td></tr>`;
const JOURNAL = '<table class="wrappertable"><tr id="l4"><td class="center"><table class="mt">' +
  '<tr class="top"><td class="ct">Социология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>' + lesson(1, 5) +
  '<tr class="top"><td class="ct">Психология ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>' + lesson(3, 3) +
  '</table></td></tr></table>';
const KEY = 'univer.mobile.attendance-course.v1:/student/attendance/full/';

// Each visit is a new document: the session storage of the tab carries over, like a reload in the app.
async function visit(storage) {
  const dom = new JSDOM('<!doctype html><html><body>' + JOURNAL + '</body></html>', {url: 'https://univer.kaznu.kz/student/attendance/full/', runScripts: 'outside-only'});
  const w = dom.window;
  for (const [k, v] of Object.entries(storage)) w.sessionStorage.setItem(k, v);
  if (w.document.readyState === 'loading') await new Promise(done => w.document.addEventListener('DOMContentLoaded', done));
  w.eval(read('univer-dom.js')); w.eval(read('univer-attendance.js'));
  w.__univerAttendanceV1.apply('ru');
  const pressed = () => w.document.querySelector('.univer-app-attendance-courses button[aria-pressed=true]');
  const kept = () => Object.fromEntries(Array.from({length: w.sessionStorage.length}, (_, i) => [w.sessionStorage.key(i), w.sessionStorage.getItem(w.sessionStorage.key(i))]));
  return {w, d: w.document, pressed, kept};
}

test('a course or class type the student picks is not kept: the next visit shows every course and type', async () => {
  const p = await visit({});
  assert.equal(p.pressed().dataset.course, 'all');
  Array.from(p.d.querySelectorAll('.univer-app-attendance-courses button')).find(b => b.textContent === 'Психология').click();
  assert.equal(p.pressed().textContent, 'Психология');
  p.d.querySelector('.univer-app-attendance-filters button[data-kind=lecture]').click();
  const left = p.kept();
  p.w.close();
  assert.deepEqual(left, {}, 'nothing about the choice is stored');
  const again = await visit(left);
  assert.equal(again.pressed().dataset.course, 'all');
  assert.equal(again.d.querySelector('.univer-app-attendance-filters button[aria-pressed=true]')?.dataset.kind ?? 'all', 'all');
  again.w.close();
});

test('a course handed over from another page opens once and is gone on the next visit', async () => {
  const p = await visit({[KEY]: 'name:психология', 'univer.mobile.attendance-course.v1:/student/attendance/full': 'name:социология',
    'univer.mobile.attendance-kind.v1': 'seminar'});
  assert.equal(p.pressed().textContent, 'Психология', 'the hand-off for this address wins');
  const left = p.kept();
  p.w.close();
  assert.deepEqual(left, {}, 'both spellings of the address and the old class type are removed');
  const again = await visit(left);
  assert.equal(again.pressed().dataset.course, 'all');
  again.w.close();
});
