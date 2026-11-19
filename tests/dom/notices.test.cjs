const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');

// univer-notices.js is evaluated by NoticeBridge as `script(fetchSections, session, senders)`.
const script = fs.readFileSync('app/src/main/assets/univer-notices.js', 'utf8');
const shell = content => '<div id="l3"><a href="/student/bachelor/">Бакалавр</a><a class="active" href="/news/student">Главная</a></div>' +
  '<div id="tools"><ul><li><a href="/student/rating/">Анкетирование</a></li></ul></div><div id="l4">' + content + '</div>';
function page(content) {
  const w = new JSDOM('<!doctype html><html><body>' + shell(content) + '</body></html>', { url: 'https://univer.kaznu.kz/news/student', runScripts: 'outside-only' }).window;
  Object.defineProperty(w.document, 'readyState', { value: 'complete', configurable: true });
  const fetched = [], idle = [];
  w.fetch = address => { fetched.push(address); return Promise.reject(new Error('offline')); };
  w.requestIdleCallback = work => { idle.push(work); return idle.length; };
  return { w, fetched, idle, run: (fetch, send) => { w.__send = send; return JSON.parse(w.eval('(' + script + ')(' + fetch + ', 1, window.__send)')); } };
}

test('a message asking for a questionnaire is found; a page that asks for nothing yields none', () => {
  const asks = page('<div class="message">Необходимо пройти анкетирование преподавателей до 20.12.2026</div><p>Расписание</p>');
  assert.deepEqual(asks.run(false).blocks.map(block => block.x), ['Необходимо пройти анкетирование преподавателей до 20.12.2026']);
  asks.w.close();
  const quiet = page('<div class="message">Оценки за рубежный контроль выставлены</div><p>Тест по физике прошёл</p>');
  assert.deepEqual(quiet.run(false).blocks, []);
  quiet.w.close();
});

test('the sections are read only once the page is idle', async () => {
  const p = page('<p>Новости</p>');
  const result = p.run(true, { notice() {} });
  assert.equal(result.fetching, true);
  assert.deepEqual(p.fetched, [], 'no section is read while the page is being shown');
  assert.equal(p.idle.length, 1);
  p.idle[0]();
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.deepEqual(p.fetched, ['/student/bachelor/']);
  p.w.close();
});
