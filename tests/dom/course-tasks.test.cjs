const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM, VirtualConsole} = require('jsdom');

const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');

// Distance-course pages rebuilt from the structure observed on the site (course list, curriculum, weeks and a week's
// task list), with synthetic ids, names and dates. Routes and script templates follow the live handlers.
const pad = n => String(n).padStart(2, '0');
const day = offset => { const d = new Date(); d.setDate(d.getDate() + offset); return pad(d.getDate()) + '.' + pad(d.getMonth() + 1) + '.' + d.getFullYear(); };
const shell = content => '<table class="wrappertable"><tr id="l1"><td class="header">КазНУ</td></tr><tr id="l4"><td>' + content + '</td></tr></table>';

const disc = (id, name) => '<tr class="link" id="' + id + '"><td><img src="/img/folder.png"></td><td>' + name + '  (0)</td></tr>';
const indexPage = (names) => shell('<table class="mt"><tr class="top"><td class="lt"></td><td class="ct"><span><a href="">Список дисциплин 2026-2027 год Осенний Семестр</a></span></td><td class="rt"></td></tr>' +
  '<tr class="mid"><td class="lt"></td><td class="tt"><div class="inner"><table class="inner" id="disciplines"><tr><th>&nbsp;</th><th>Дисциплина</th></tr>' +
  names.map(entry => disc(entry.id, entry.name)).join('') + '</table></div></td><td class="rt"></td></tr></table>');

const plansPage = (plan) => shell('<table class="mt"><tr class="top"><td class="lt"></td><td class="ct"><span><a href="/student/discussion/index/2026/1/">Список дисциплин</a></span></td><td class="rt"></td></tr>' +
  '<tr class="mid"><td class="lt"></td><td class="tt"><div class="inner"><table class="inner" id="plans"><tr><th>&nbsp;</th><th>Специальность</th>' +
  '<th>Дата начала (дд/мм/гггг)</th><th>Дата окончания (дд/мм/гггг)</th></tr>' +
  '<tr class="link" id="' + plan + '"><td><img></td><td>6B06301-Системы (0)</td><td>' + day(-60) + '</td><td>' + day(40) + '</td></tr>' +
  '</table></div></td><td class="rt"></td></tr></table>');

const weekRow = (id, from, tasks, current) => '<tr class="link' + (current ? ' normal' : '') + '" id="' + id + '"><td><img></td><td>' + id + '</td>' +
  '<td>' + day(from) + ' 00:00-' + day(from + 6) + ' 00:00</td><td>0</td><td>' + tasks + '</td></tr>';
const weeksPage = (rows) => shell('<table class="mt"><tr class="mid"><td class="lt"></td><td class="tt">' +
  '<table class="inner"><tr><th><img></th><th>Неделя</th><th>Период</th><th>Количество ресурсов</th><th>Количество заданий</th><th>&nbsp;</th></tr></table>' +
  '<div class="inner scrool"><table class="inner" id="weeks">' + rows + '</table></div></td><td class="rt"></td></tr></table>');

const taskRow = (id, title, start, end) => '<tr class="link" id="' + id + '" title="Описание"><td><img></td><td>' + title + ' (0). Автор: Преподаватель</td>' +
  '<td>' + day(start) + '</td><td>' + day(end) + '</td><td>' + day(start) + '</td><td>42</td></tr>';
const taskListPage = (rows) => shell('<table class="mt"><tr class="mid"><td class="lt"></td><td class="tt"><div class="inner"><table class="inner" id="tasks">' +
  '<tr><th>&nbsp;</th><th>Название</th><th>Дата начала (дд/мм/гггг)</th><th>Дата окончания (дд/мм/гггг)</th><th>Дата созд.</th><th>Участников</th></tr>' +
  rows + '</table></div></td><td class="rt"></td></tr></table>');

// An expired session answers with the sign-in form: that discipline is "not checked", never "no tasks".
const login = '<html><body><form id="login_frm" action="/user/login"><input type="text"><input type="password"></form></body></html>';

function page(content, url, {routes = {}, storage} = {}) {
  const errors = [], virtualConsole = new VirtualConsole();
  virtualConsole.on('jsdomError', error => errors.push(String(error.message)));
  const dom = new JSDOM('<!doctype html><html><body>' + content + '</body></html>', {url, runScripts: 'outside-only', virtualConsole});
  const w = dom.window;
  Object.defineProperty(w.document, 'readyState', {value: 'complete', configurable: true});
  if (storage) Object.entries(storage).forEach(([key, value]) => w.localStorage.setItem(key, value));
  const requests = [];
  w.fetch = (address, options) => {
    requests.push({address, options});
    const body = routes[address];
    if (body === undefined) return Promise.resolve({status: 404, url: 'https://univer.kaznu.kz' + address, text: async () => ''});
    return Promise.resolve({status: 200, url: 'https://univer.kaznu.kz' + address, text: async () => body});
  };
  w.eval(read('univer-dom.js'));
  w.eval(read('univer-discussion.js'));
  return {w, d: w.document, requests, errors, close: () => w.close()};
}
const apply = (p, language = 'ru') => p.w.__univerDiscussionV1.apply(language);
const settle = async () => { for (let i = 0; i < 30; i++) await new Promise(resolve => setTimeout(resolve, 0)); };
const until = async (done, turns = 400) => { for (let i = 0; i < turns && !done(); i++) await new Promise(resolve => setTimeout(resolve, 5)); return done(); };

const INDEX = 'https://univer.kaznu.kz/student/discussion/index/2026/1/';
const WEEK_URL = 'https://univer.kaznu.kz/student/discussion/acpos/week/list/500/102/2026/1/';
const ACCOUNT = 'univer.app.account.v1', CACHE = 'univer.app.discussion-tasks.v1';
const PLAN_101 = '/student/discussion/acpos/list/101/2026/1/', PLAN_102 = '/student/discussion/acpos/list/102/2026/1/';
const WEEKS_500_101 = '/student/discussion/acpos/week/list/500/101/2026/1/', WEEKS_501_102 = '/student/discussion/acpos/week/list/501/102/2026/1/';
const TASKS_500_101_2 = '/student/discussion/task/list/500/101/2026/1/2/', TASKS_501_102_1 = '/student/discussion/task/list/501/102/2026/1/1/';
const TASKS_500_102_1 = '/student/discussion/task/list/500/102/2026/1/1/', TASKS_500_102_2 = '/student/discussion/task/list/500/102/2026/1/2/';
const PREVIEW_9001 = '/student/discussion/task/preview/9001/500/101/2026/1/2/0/0/0/';
const PREVIEW_9002 = '/student/discussion/task/preview/9002/500/101/2026/1/2/0/0/0/';
const PREVIEW_9101 = '/student/discussion/task/preview/9101/501/102/2026/1/1/0/0/0/';
const PREVIEW_9102 = '/student/discussion/task/preview/9102/501/102/2026/1/1/0/0/0/';

// 101: a running week with an open and a closed task. 102: an ended week of two closed tasks. Both are cached.
const routes = {
  [PLAN_101]: plansPage(500),
  [WEEKS_500_101]: weeksPage(weekRow(1, -9, 0) + weekRow(2, -2, 2, true)),
  [TASKS_500_101_2]: taskListPage(taskRow(9001, 'Эссе по истории', -3, 3) + taskRow(9002, 'Конспект', -20, -10)),
  [PLAN_102]: plansPage(501),
  [WEEKS_501_102]: weeksPage(weekRow(1, -16, 2) + weekRow(2, 5, 0)),
  [TASKS_501_102_1]: taskListPage(taskRow(9101, 'Реферат', -24, -9) + taskRow(9102, 'Доклад', -20, -5)),
  // The same course opened as its own weeks page (plan 500): one ended week and one running.
  [TASKS_500_102_1]: taskListPage(taskRow(9101, 'Реферат', -24, -9) + taskRow(9102, 'Доклад', -20, -5)),
  [TASKS_500_102_2]: taskListPage(taskRow(9001, 'Эссе по истории', -3, 3) + taskRow(9002, 'Конспект', -20, -10))
};
const all2026 = [{id: 101, name: 'История Казахстана'}, {id: 102, name: 'Социология'}, {id: 103, name: 'Политология'}];

test('the course list keeps one panel with open and closed tabs and a single Refresh', async () => {
  const failure = {'/student/discussion/acpos/list/103/2026/1/': login};
  const p = page(indexPage(all2026), INDEX, {routes: Object.assign({}, routes, failure), storage: {[ACCOUNT]: 'a1'}});
  apply(p);
  const box = p.d.querySelector('.univer-app-dist-due');
  assert.ok(box, 'an assignments panel above the course list');
  assert.equal(p.d.querySelectorAll('.univer-app-dist-due').length, 1);
  assert.equal(box.dataset.state, 'loading');
  assert.match(box.querySelector('.univer-app-dist-due-status').textContent, /^Проверяем дисциплины: 0 из 3$/);
  await settle();
  assert.equal(box.dataset.state, 'failed');
  assert.equal(box.querySelector('.univer-app-dist-due-status').textContent, 'Не удалось проверить часть дисциплин');
  assert.equal(box.querySelector('.univer-app-dist-failed').textContent, 'Политология');
  assert.equal(box.querySelector('.univer-app-dist-refresh').disabled, false, 'a failed read leaves a retry, not a spinner');

  const tabs = Array.from(box.querySelectorAll('.univer-app-dist-tabs button'));
  assert.deepEqual(tabs.map(b => [b.dataset.view, b.textContent, b.getAttribute('aria-pressed')]),
    [['open', 'Открытые1', 'true'], ['closed', 'Закрытые3', 'false']]);
  assert.equal(box.querySelector('h2').textContent, 'Открытые задания · 1');
  const openPanel = box.querySelector('.univer-app-dist-tab-panel[data-view=open]');
  const closedPanel = box.querySelector('.univer-app-dist-tab-panel[data-view=closed]');
  assert.equal(openPanel.hidden, false); assert.equal(closedPanel.hidden, true, 'the other tab is out of the way');
  assert.deepEqual(Array.from(openPanel.querySelectorAll('a')).map(a => new URL(a.href).pathname), [PREVIEW_9001],
    'the open tab lists only what can still be handed in');

  tabs[1].click();
  assert.deepEqual(Array.from(box.querySelectorAll('.univer-app-dist-tabs button')).map(b => b.getAttribute('aria-pressed')), ['false', 'true']);
  assert.equal(box.querySelector('h2').textContent, 'Закрытые задания · 3');
  assert.equal(closedPanel.hidden, false); assert.equal(openPanel.hidden, true);
  assert.deepEqual(Array.from(closedPanel.querySelectorAll('a')).map(a => new URL(a.href).pathname), [PREVIEW_9102, PREVIEW_9101, PREVIEW_9002],
    'closed tasks: the most recently closed first');
  assert.deepEqual(Array.from(closedPanel.querySelectorAll('a')).map(a => a.dataset.closed), ['true', 'true', 'true']);

  // The cards count their discipline's tasks; the failed one shows no count rather than a wrong zero.
  const cards = Array.from(p.d.querySelectorAll('.univer-app-dist-tasks'));
  assert.equal(cards[0].hidden, false); assert.match(cards[0].textContent, /^2 задания · срок \d\d\.\d\d$/);
  assert.equal(cards[1].hidden, false); assert.equal(cards[1].textContent, '2 задания');
  assert.equal(cards[2].hidden, true, 'a course that could not be checked shows no count');
  assert.equal(p.d.querySelector('.univer-app-dist-count[data-state=closed]').textContent, '2', 'all of this discipline is closed');

  const stored = JSON.parse(p.w.localStorage.getItem(CACHE));
  assert.equal(stored.owner, 'a1'); assert.equal(stored.owner, p.w.localStorage.getItem(ACCOUNT));
  assert.ok(stored.courses['ru|' + PLAN_101] && stored.courses['ru|' + PLAN_102], 'read courses are kept under the account');
  assert.ok(stored.weeks['ru|' + TASKS_501_102_1], 'an ended week of closed tasks keeps them for a day');
  assert.ok(!stored.courses['ru|/student/discussion/acpos/list/103/2026/1/'], 'a failed course is not cached');

  // Refresh rereads everything in the same panel: no second panel, no second row of tabs.
  p.requests.length = 0;
  box.querySelector('.univer-app-dist-refresh').click();
  assert.equal(p.d.querySelectorAll('.univer-app-dist-due').length, 1);
  assert.equal(box.dataset.state, 'loading');
  assert.equal(box.querySelectorAll('.univer-app-dist-tabs').length, 1);
  await settle();
  assert.equal(p.d.querySelectorAll('.univer-app-dist-tabs').length, 1);
  assert.equal(box.querySelectorAll('.univer-app-dist-tabs button').length, 2);
  assert.ok(p.requests.some(r => r.address === PLAN_101) && p.requests.some(r => r.address === TASKS_501_102_1), 'Refresh rereads, cached or not');
  assert.equal(box.querySelector('h2').textContent, 'Закрытые задания · 3', 'the chosen tab survives a refresh');

  apply(p, 'en');
  assert.equal(p.d.querySelectorAll('.univer-app-dist-due').length, 1, 'a new language replaces the panel, never adds one');
  await settle();
  assert.equal(p.d.querySelector('.univer-app-dist-due h2').textContent, 'Open tasks · 1');
  assert.deepEqual(Array.from(p.d.querySelectorAll('.univer-app-dist-tabs button')).map(b => b.textContent), ['Open1', 'Closed3']);

  apply(p, 'ru'); apply(p, 'ru');
  assert.equal(p.d.querySelectorAll('.univer-app-dist-due').length, 1, 'a repeated pass keeps one panel');
  assert.equal(p.d.querySelectorAll('.univer-app-dist-tabs').length, 1);
  await settle();

  // The site redraws the discipline table (a changed row description): the panel stays where it was and its Refresh
  // still reads, rather than going dead with the replaced list.
  p.d.querySelector('#disciplines tr.link').setAttribute('title', 'Новое описание');
  apply(p, 'ru');
  await settle();
  const redrawn = p.d.querySelector('.univer-app-dist-due');
  assert.equal(p.d.querySelectorAll('.univer-app-dist-due').length, 1);
  assert.equal(redrawn.nextElementSibling, p.d.querySelector('.univer-app-dist-list'));
  p.requests.length = 0;
  redrawn.querySelector('.univer-app-dist-refresh').click();
  await settle();
  assert.ok(p.requests.some(r => r.address === PLAN_101), 'the Refresh of a kept panel still works');
  assert.equal(p.d.querySelectorAll('.univer-app-dist-due').length, 1);

  apply(p, 'ru');
  p.w.__univerDiscussionV1.release();
  assert.equal(p.d.querySelectorAll('.univer-app-dist-due,.univer-app-dist-tabs,.univer-app-dist-list').length, 0);
  assert.equal(p.d.getElementById('disciplines').closest('div').getAttribute('class'), 'inner');
  p.close();
});

test('a fully read course list makes no requests on revisit and shows the cached tasks at once', async () => {
  const names = [{id: 101, name: 'История Казахстана'}, {id: 102, name: 'Социология'}];
  const first = page(indexPage(names), INDEX, {routes, storage: {[ACCOUNT]: 'a1'}});
  apply(first); await settle();
  assert.ok(first.requests.length > 0);
  const kept = first.w.localStorage.getItem(CACHE);
  first.close();

  const again = page(indexPage(names), INDEX, {routes, storage: {[ACCOUNT]: 'a1', [CACHE]: kept}});
  apply(again);
  const box = again.d.querySelector('.univer-app-dist-due');
  assert.equal(box.dataset.state, 'ready', 'cached results need no "checking" state');
  assert.deepEqual(again.requests, [], 'nothing to reread');
  assert.equal(box.querySelector('h2').textContent, 'Открытые задания · 1');
  assert.equal(box.querySelectorAll('.univer-app-dist-tab-panel[data-view=closed] a').length, 3);
  again.close();

  // The course lists expire after ten minutes; a week that ended days ago with closed tasks keeps them for a day.
  const cache = JSON.parse(kept);
  Object.values(cache.courses).forEach(record => { record.at -= 11 * 60 * 1000; });
  const expired = page(indexPage(names), INDEX, {routes, storage: {[ACCOUNT]: 'a1', [CACHE]: JSON.stringify(cache)}});
  apply(expired); await settle();
  const asked = expired.requests.map(r => r.address);
  assert.ok(asked.includes(PLAN_102) && asked.includes(WEEKS_501_102), 'expired course lists are read again');
  assert.ok(!asked.includes(TASKS_501_102_1), 'the ended week of closed tasks is served from its day cache');
  assert.ok(asked.includes(TASKS_500_101_2), 'a running week is always checked');
  expired.close();

  const stranger = page(indexPage(names), INDEX, {routes, storage: {[ACCOUNT]: 'b2', [CACHE]: kept}});
  apply(stranger); await settle();
  assert.ok(stranger.requests.some(r => r.address === PLAN_101), 'another account reads for itself');
  assert.equal(JSON.parse(stranger.w.localStorage.getItem(CACHE)).owner, 'b2');
  stranger.close();

  const anonymous = page(indexPage(names), INDEX, {routes});
  apply(anonymous); await settle();
  assert.ok(anonymous.requests.some(r => r.address === PLAN_101));
  assert.equal(anonymous.w.localStorage.getItem(CACHE), null, 'without the account mark nothing is cached');
  anonymous.close();
});

test('the course page lists its tasks in open and closed tabs and its Refresh keeps one panel', async () => {
  const content = weeksPage(weekRow(1, -16, 2) + weekRow(2, -2, 2, true) + weekRow(3, 5, 0));
  const p = page(content, WEEK_URL, {routes, storage: {[ACCOUNT]: 'a1'}});
  apply(p);
  const box = p.d.querySelector('.univer-app-dist-week-tasks');
  assert.ok(box); assert.equal(p.d.querySelectorAll('.univer-app-dist-week-tasks').length, 1);
  await settle();
  assert.equal(box.querySelector('h2').textContent, 'Задания предмета · 4');
  const tabs = Array.from(box.querySelectorAll('.univer-app-dist-tabs button'));
  assert.deepEqual(tabs.map(b => [b.dataset.view, b.textContent]), [['open', 'Открытые1'], ['closed', 'Закрытые3']]);
  assert.equal(box.querySelectorAll('.univer-app-dist-due-item').length, 4, 'both tabs hold their list');
  assert.equal(box.querySelector('.univer-app-dist-tab-panel[data-view=open]').hidden, false);
  assert.equal(box.querySelector('.univer-app-dist-tab-panel[data-view=closed]').hidden, true);
  tabs[1].click();
  assert.equal(box.querySelector('.univer-app-dist-tab-panel[data-view=open]').hidden, true);
  const closed = Array.from(box.querySelectorAll('.univer-app-dist-tab-panel[data-view=closed] a'));
  assert.equal(closed.length, 3);
  assert.deepEqual(closed.map(a => a.querySelector('.univer-app-dist-due-meta').textContent.split(' · ')[0]), ['Неделя 1', 'Неделя 1', 'Неделя 2']);

  p.requests.length = 0;
  box.querySelector('.univer-app-dist-refresh').click();
  assert.equal(box.dataset.state, 'loading');
  assert.equal(p.d.querySelectorAll('.univer-app-dist-week-tasks').length, 1);
  assert.equal(box.querySelectorAll('.univer-app-dist-tabs').length, 1);
  await settle();
  assert.equal(p.d.querySelectorAll('.univer-app-dist-week-tasks').length, 1);
  assert.ok(p.requests.some(r => r.address === TASKS_500_102_1));
  p.w.__univerDiscussionV1.release();
  assert.equal(p.d.querySelectorAll('.univer-app-dist-week-tasks').length, 0);
  p.close();
});

test('a course page served from the cache asks only for its running week', async () => {
  const content = weeksPage(weekRow(1, -16, 2) + weekRow(2, -2, 2, true));
  const first = page(content, WEEK_URL, {routes, storage: {[ACCOUNT]: 'a1'}});
  apply(first); await settle();
  const kept = first.w.localStorage.getItem(CACHE);
  assert.ok(JSON.parse(kept).weeks['ru|' + TASKS_500_102_1], 'the ended week is kept');
  first.close();
  const again = page(content, WEEK_URL, {routes, storage: {[ACCOUNT]: 'a1', [CACHE]: kept}});
  apply(again); await settle();
  assert.deepEqual(again.requests.map(r => r.address), [TASKS_500_102_2], 'the ended week is not read again; the current one still is');
  assert.equal(again.d.querySelectorAll('.univer-app-dist-week-tasks .univer-app-dist-due-item').length, 4);
  again.close();
});

test('a read the server never answers ends in a named retry state, not endless loading', async () => {
  const p = page(indexPage([{id: 101, name: 'История Казахстана'}]), INDEX, {routes: {}});
  // The fifteen-second deadline, shortened for the test.
  const timer = p.w.setTimeout.bind(p.w);
  p.w.setTimeout = (run, ms, ...rest) => timer(run, ms >= 15000 ? 30 : ms, ...rest);
  p.w.fetch = (address, options) => new Promise((resolve, reject) => {
    p.requests.push({address, options});
    if (options.signal) options.signal.addEventListener('abort', () => reject(new Error('aborted')));
  });
  apply(p);
  const box = p.d.querySelector('.univer-app-dist-due');
  assert.ok(await until(() => box.dataset.state === 'failed'), 'the deadline ends the loading state');
  assert.equal(box.querySelector('.univer-app-dist-due-status').textContent, 'Не удалось проверить часть дисциплин');
  assert.equal(box.querySelector('.univer-app-dist-failed').textContent, 'История Казахстана');
  assert.equal(box.querySelector('.univer-app-dist-refresh').disabled, false, 'the retry is the one Refresh');
  assert.equal(p.d.querySelectorAll('.univer-app-dist-refresh').length, 1);
  apply(p, 'ru');
  assert.equal(p.d.querySelectorAll('.univer-app-dist-due').length, 1);
  p.close();
});
