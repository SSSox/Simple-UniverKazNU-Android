const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
// Monday 9:00-10:00 and Friday 9:00-10:00; the rest of the week is free.
const row = (time, cells) => '<tr><td>' + time + '</td>' + cells.map(c => '<td>' + c + '</td>').join('') + '</tr>';
const SCHEDULE = '<html><body><select id="weekSelect"></select><table class="schedule"><tr><th></th><th>Пн</th><th>Вт</th><th>Ср</th><th>Чт</th><th>Пт</th><th>Сб</th></tr>' +
    row('09:00 - 10:00', ['<p>Политология</p>', '', '', '', '<p>Культурология</p>', '']) + '</table></body></html>';

// A schedule page on a chosen day (2026-11-09 is a Monday) and a minute of that day.
async function bachelor(day, hour, language = 'ru') {
  const dom = new JSDOM('<body><div id="l4"><table class="mt"></table></div></body>', {url: 'https://univer.kaznu.kz/student/bachelor/', runScripts: 'outside-only', pretendToBeVisual: true});
  const w = dom.window, RealDate = w.Date;
  w.Date = class extends RealDate { constructor(...args) { super(...(args.length ? args : [2026, 10, day, hour, 30])); } };
  w.TextDecoder = TextDecoder;
  w.fetch = async url => ({status: 200, url: 'https://univer.kaznu.kz' + url, text: async () => (/myschedule/.test(url) ? SCHEDULE : '<html><body></body></html>')});
  w.eval(read('univer-dom.js')); w.eval(read('univer-overview.js'));
  w.__univerOverviewV1.apply(language);
  const body = () => w.document.querySelector('.univer-app-overview-today .univer-app-overview-body');
  for (let i = 0; i < 200 && !body().querySelector('.univer-app-overview-caption'); i++) await new Promise(resolve => setTimeout(resolve, 5));
  return {w, body, close: () => { w.__univerOverviewV1.release(); w.close(); }};
}
const lines = b => Array.from(b.querySelectorAll('p')).map(p => p.textContent);

test('after the last class of the day the panel says so with the date, then names the next classes', async () => {
  const p = await bachelor(9, 18);
  try {
    assert.deepEqual(lines(p.body()), ['Сегодня, понедельник, 9 ноября', 'Сегодняшние пары закончились', 'Ближайшие пары · Пятница, 13 ноября']);
    assert.equal(p.body().querySelectorAll('.univer-app-overview-lesson').length, 1);
  } finally { p.close(); }
});

test('the date is written in the chosen language', async () => {
  for (const [language, caption] of [['kz', 'Бүгін, дүйсенбі, 9 қараша'], ['en', 'Today, Monday, 9 November']]) {
    const p = await bachelor(9, 8, language);
    try { assert.equal(lines(p.body())[0], caption); } finally { p.close(); }
  }
});

test('a day without classes says there are none, with the date', async () => {
  const p = await bachelor(10, 12);
  try {
    assert.equal(lines(p.body())[0], 'Сегодня, вторник, 10 ноября');
    assert.equal(lines(p.body())[1], 'Сегодня пар нет');
  } finally { p.close(); }
});

test('while a class is still ahead the panel shows today with its date and no finished line', async () => {
  const p = await bachelor(9, 8);
  try {
    assert.deepEqual(lines(p.body()), ['Сегодня, понедельник, 9 ноября']);
    assert.equal(p.body().querySelectorAll('.univer-app-overview-lesson').length, 1);
  } finally { p.close(); }
});

const upnext = b => { const n = b.querySelector('.univer-app-overview-upnext'); return n ? [n.dataset.state, n.querySelector('b').textContent, n.querySelector('span').textContent] : null; };

// The line above the list is the one place that counts down: the class's row is highlighted, never a second plaque.
const counted = b => Array.from(b.querySelectorAll('.univer-app-overview-lessons *')).filter(n => /через|ещё/.test(n.textContent) && !n.children.length).map(n => n.textContent);

test('before the first class one line says when it starts and its row does not repeat it', async () => {
  const p = await bachelor(9, 8);
  try {
    assert.deepEqual(upnext(p.body()), ['next', 'Следующая пара через 30 мин', 'Политология · 09:00–10:00']);
    assert.equal(p.body().querySelectorAll('.univer-app-overview-state').length, 0);
    assert.deepEqual(counted(p.body()), []);
    assert.ok(p.body().querySelector('.univer-app-overview-lesson.univer-app-overview-next'));
  } finally { p.close(); }
});

test('during a class one line says how long is left and that it is the last one, and the row stays highlighted', async () => {
  const p = await bachelor(9, 9);
  try {
    assert.deepEqual(upnext(p.body()), ['now', 'Идёт пара · ещё 30 мин', 'Это последняя пара сегодня']);
    assert.equal(p.body().querySelectorAll('.univer-app-overview-state').length, 0);
    assert.deepEqual(counted(p.body()), []);
    assert.ok(p.body().querySelector('.univer-app-overview-lesson.univer-app-overview-now'));
  } finally { p.close(); }
});

test('after the day is over the card counts down to the next classes on their day', async () => {
  const p = await bachelor(9, 18);
  try {
    assert.deepEqual(upnext(p.body()), ['next', 'Следующая пара через 3 д 14 ч', 'Культурология · 09:00–10:00']);
  } finally { p.close(); }
});

test('coming back to Бакалавр within minutes reads the schedule and grades from this session, for the same account only', async () => {
  const visit = async (saved, account) => {
    const w = new JSDOM('<body><div id="l4"><table class="mt"></table></div></body>', {url: 'https://univer.kaznu.kz/student/bachelor/', runScripts: 'outside-only', pretendToBeVisual: true}).window;
    const fetched = [];
    Object.entries(saved).forEach(([key, value]) => w.sessionStorage.setItem(key, value));
    w.localStorage.setItem('univer.app.account.v1', account);
    w.fetch = async url => { fetched.push(url); return {status: 200, url: 'https://univer.kaznu.kz' + url, text: async () => (/myschedule/.test(url) ? SCHEDULE : '<html><body></body></html>')}; };
    w.eval(read('univer-dom.js')); w.eval(read('univer-overview.js'));
    w.__univerOverviewV1.apply('ru');
    const body = () => w.document.querySelector('.univer-app-overview-today .univer-app-overview-body');
    for (let i = 0; i < 200 && !body().querySelector('.univer-app-overview-caption'); i++) await new Promise(resolve => setTimeout(resolve, 5));
    const kept = Object.fromEntries(Object.keys(w.sessionStorage).map(key => [key, w.sessionStorage.getItem(key)]));
    const lessons = body().querySelectorAll('.univer-app-overview-lesson').length;
    w.__univerOverviewV1.release(); w.close();
    return {fetched, kept, lessons};
  };
  const first = await visit({}, 'student1');
  assert.deepEqual(first.fetched.sort(), ['/student/attendance/full/', '/student/myschedule/']);
  const again = await visit(first.kept, 'student1');
  assert.deepEqual(again.fetched, []);
  assert.equal(again.lessons, first.lessons);
  assert.equal((await visit(first.kept, 'student2')).fetched.length, 2, 'another account reads its own pages');
  const aged = Object.fromEntries(Object.entries(first.kept).map(([key, value]) => [key, JSON.stringify({...JSON.parse(value), at: Date.now() - 11 * 60000})]));
  assert.equal((await visit(aged, 'student1')).fetched.length, 2, 'pages older than ten minutes are read again');
});
