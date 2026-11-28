const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');

// univer-notices.js is evaluated by NoticeBridge as `script(fetchSections, session, senders, refresh)`.
const script = fs.readFileSync('app/src/main/assets/univer-notices.js', 'utf8');
const kotlin = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
const mobileScripts = JSON.parse('[' + kotlin.match(/val SCRIPTS = listOf\(([^)]*)\)/)[1] + ']');

const menu = (count = '<b style="color:Red; font-size:10px;">&nbsp;(2)</b>') =>
  '<table id="tools"><tr><td class="center"><table class="tool"><tr><td><ul>' +
  `<li><a href="/student/rating/">Анкетирование студентов</a>${count}</li>` +
  '<li><a href="/student/contract/" style="color:red">Договоры</a></li><li><a href="/student/group/">Группа (2)</a></li>' +
  '<li><a href="/student/umkd/">УМКД</a></li><li><a href="/user/logout">Выход</a></li></ul></td></tr></table></td></tr></table>';
const tabs = '<tr id="l3"><td class="center"><a class="active" href="/student/bachelor/"><span class="center">Бакалавр</span></a><a href="/ssc/"><span class="center">Е-услуги</span></a><a href="/">Главная</a></td></tr>';
const shell = (content, count) => '<table class="wrappertable"><tr id="l1"><td class="header">КазНУ</td></tr><tr id="l2"><td></td></tr>' + tabs +
  `<tr id="l4"><td class="center">${menu(count)}${content}</td></tr></table>`;
const rows = '<table class="inner"><tr><th>Название</th><th>Тип</th><th>Начало</th><th>Окончание</th></tr>' +
  '<tr class="link" id="208"><td>Удовлетворенность обучающихся качеством преподавания</td><td>Анкетирование преподавателя</td><td>30.10.2026</td><td>30.11.2026</td></tr>' +
  '<tr class="link" id="207"><td>Восприятие коррупции - 2026, осенний сем.</td><td>Анкетирование</td><td>29.10.2026</td><td>11.11.2026</td></tr></table>';
const answers = (...marks) => shell('<table class="inner">' + marks.map(mark =>
  `<tr class="link${mark === 'done' ? ' done' : ''}"><td>Дисциплина</td><td>Преподаватель</td><td>${mark === 'done' ? '' : mark}</td><td></td></tr>`).join('') + '</table>');
const routesFor = (...marks) => ({
  '/student/rating/': shell(rows),
  '/student/rating/208': answers('done', '<img src="/content/icons/add.png" alt=" [+] ">'),
  '/student/rating/207': answers(...marks)
});

function page(html, url = 'https://univer.kaznu.kz/student/bachelor/', { routes = {}, idleQueue = false, withMobile = false } = {}) {
  const errors = [], virtualConsole = new VirtualConsole();
  virtualConsole.on('jsdomError', error => errors.push(String(error.message)));
  const dom = new JSDOM('<!doctype html><html><head></head><body>' + html + '</body></html>', {
    url, runScripts: 'outside-only', virtualConsole, pretendToBeVisual: withMobile
  });
  const w = dom.window, requests = [], sent = [], idle = [];
  Object.defineProperty(w.document, 'readyState', { value: 'complete', configurable: true });
  w.TextDecoder = TextDecoder;
  if (withMobile) {
    w.CSS = { supports: () => true };
    w.CSSLayerBlockRule = function () {};
    w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
    w.HTMLElement.prototype.getClientRects = function () { return [{ width: 100, height: 30 }]; };
    mobileScripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));
  }
  w.requestIdleCallback = work => {
    if (idleQueue) { idle.push(work); return idle.length; }
    setTimeout(work, 0);
    return 1;
  };
  w.fetch = address => {
    requests.push(address);
    const body = routes[address];
    return Promise.resolve({
      ok: body !== undefined, status: body === undefined ? 404 : 200,
      url: 'https://univer.kaznu.kz' + address,
      headers: { get: () => 'text/html; charset=utf-8' },
      arrayBuffer: async () => new TextEncoder().encode(body || '').buffer
    });
  };
  const sender = { notice: value => sent.push(value) };
  return {
    w, d: w.document, requests, sent, idle, errors, sender,
    run(fetch = false, refresh = false, send = sender) {
      w.__send = send;
      return JSON.parse(w.eval('(' + script + ')(' + fetch + ', 1, window.__send, ' + JSON.stringify(refresh) + ')'));
    }
  };
}
const settle = async () => { for (let i = 0; i < 30; i++) await new Promise(resolve => setTimeout(resolve, 0)); };
const rating = result => result.links.find(link => link.u === '/student/rating/');
const messages = p => p.sent.map(value => JSON.parse(value));
const checkedRating = p => messages(p).flatMap(message => message.links || []).find(link => link.u === '/student/rating/' && link.n > 0 && !link.c);
const badge = p => p.d.querySelector('#tools li > b');

test('a message asking for a questionnaire is found; a page that asks for nothing yields none', () => {
  const asks = page(shell('<div class="message">Необходимо пройти анкетирование преподавателей до 20.12.2026</div><p>Расписание</p>', ''), 'https://univer.kaznu.kz/news/student');
  assert.deepEqual(asks.run(false, false, null).blocks.map(block => block.x), ['Необходимо пройти анкетирование преподавателей до 20.12.2026']);
  asks.w.close();
  const quiet = page(shell('<div class="message">Оценки за рубежный контроль выставлены</div><p>Тест по физике прошёл</p>', ''), 'https://univer.kaznu.kz/news/student');
  assert.deepEqual(quiet.run(false, false, null).blocks, []);
  quiet.w.close();
});

test('the sections are read only once the page is idle', async () => {
  const p = page(shell('<p>Новости</p>', ''), 'https://univer.kaznu.kz/news/student', { idleQueue: true });
  const result = p.run(true, false);
  assert.equal(result.fetching, true);
  assert.deepEqual(p.requests, [], 'no section is read while the page is being shown');
  assert.equal(p.idle.length, 1);
  p.idle[0]();
  await settle();
  assert.deepEqual(p.requests, ['/student/bachelor/', '/ssc/']);
  p.w.close();
});

test('the red count next to a menu link is a notification; a red link or an unmarked number is not', () => {
  const p = page(shell('<p>Бакалавр</p>'));
  const before = p.d.body.innerHTML;
  const result = p.run(false, false, null);
  assert.deepEqual(p.errors, []);
  assert.equal(result.g, 1);
  assert.equal(result.kind, 'page');
  assert.equal(result.menu, true);
  assert.deepEqual({ t: rating(result).t, n: rating(result).n, m: rating(result).m, c: rating(result).c },
    { t: 'Анкетирование студентов', n: 2, m: 1, c: 1 });
  assert.equal(result.links.find(link => link.u === '/student/contract/').n, 0);
  assert.equal(result.links.find(link => link.u === '/student/group/').n, 0);
  assert.equal(result.links.some(link => /logout/.test(link.u)), false);
  assert.equal(rating(result).o, undefined);
  assert.equal(p.d.body.innerHTML, before, 'reading never changes the page');
  p.w.close();
});

test('a questionnaire stays open if any of its detail rows is unanswered and updates the red badge', async () => {
  const p = page(shell(rows), 'https://univer.kaznu.kz/student/rating/', { routes: routesFor('done', '') });
  const origFetch = p.w.fetch;
  let release;
  const firstDetail = new Promise(resolve => { release = resolve; });
  p.w.fetch = async (address, options) => {
    assert.equal(options.method || 'GET', 'GET');
    const response = await origFetch(address);
    if (address.endsWith('/208')) await firstDetail;
    return response;
  };
  const initial = rating(p.run(false, true));
  p.w.__univerNoticesV1.present(true);
  assert.equal(initial.n, 2);
  assert.equal(initial.c, 1);
  assert.equal(initial.o, undefined);
  await settle();
  assert.deepEqual(p.requests, ['/student/rating/208']);
  assert.deepEqual(p.sent, [], 'no count is sent while a detail page is still pending');
  release();
  await settle();
  assert.deepEqual(p.requests, ['/student/rating/208', '/student/rating/207'], 'the open list is reused');
  const checked = checkedRating(p);
  assert.equal(checked.o, 1);
  assert.deepEqual(checked.d, [{ t: 'Восприятие коррупции - 2026, осенний сем.', e: '11.11.2026' }]);
  assert.equal(badge(p).textContent.trim(), '(1)');
  assert.equal(badge(p).hidden, false);
  p.w.close();
});

test('a verified result is reused across pages until count changes, expires, or refresh is requested', async () => {
  const routes = routesFor(''), url = 'https://univer.kaznu.kz/student/rating/';
  const p = page(shell(rows), url, { routes }), warm = page(shell(rows), url, { routes });
  const key = 'univer.app.survey-count.v1', account = 'univer.app.account.v1';
  const details = ['/student/rating/208', '/student/rating/207'];
  try {
    let now = 1000000;
    p.w.Date.now = warm.w.Date.now = () => now;
    p.w.localStorage.setItem(account, 'a1');
    p.run(false, false);
    await settle();
    assert.deepEqual(p.requests, details);

    warm.w.localStorage.setItem(key, p.w.localStorage.getItem(key));
    warm.w.localStorage.setItem(account, 'b2');
    warm.run(false, false, null);
    warm.w.__univerNoticesV1.present(true);
    assert.equal(badge(warm).textContent.trim(), '(2)', 'saved check belongs to its account');

    warm.w.localStorage.setItem(account, 'a1');
    const reused = rating(warm.run(false, false));
    assert.equal(reused.o, 1);
    assert.deepEqual(reused.d, checkedRating(p).d);
    assert.deepEqual(warm.requests, []);

    badge(warm).firstChild.data = '(3)';
    assert.equal(rating(warm.run(false, false)).c, 1);
    await settle();
    assert.deepEqual(warm.requests.splice(0), details);
    assert.equal(rating(warm.run(false, false)).o, 1);

    badge(warm).firstChild.data = '(0)';
    assert.equal(rating(warm.run(false, false)).n, 0);
    assert.equal(warm.w.localStorage.getItem(key), null);
    badge(warm).firstChild.data = '(3)';
    assert.equal(rating(warm.run(false, false)).c, 1, 'a count returning after zero needs another read');
    await settle();
    assert.deepEqual(warm.requests.splice(0), details);

    now += 3 * 60 * 1000;
    assert.equal(rating(warm.run(false, false)).c, 1);
    await settle();
    assert.deepEqual(warm.requests.splice(0), details);
    assert.equal(rating(warm.run(false, false)).o, 1);

    warm.run(false, 1);
    await settle();
    assert.deepEqual(warm.requests, details);
    assert.equal(messages(warm)[messages(warm).length - 1].refresh, 1);
  } finally { p.w.close(); warm.w.close(); }
});

test('a verified count stays in the menu for up to 1 hour, hides at zero, and restores in Original layout', async () => {
  const routes = routesFor('&nbsp;+&nbsp;');
  const p = page(shell(rows), 'https://univer.kaznu.kz/student/rating/', { routes, withMobile: true });
  try {
    let now = 1000000;
    p.w.Date.now = () => now;
    p.w.localStorage.setItem('univer.app.account.v1', 'a1');
    const original = badge(p), originalText = original.firstChild, originalMarkup = original.outerHTML;
    p.w.__univerMobileV1.setEnabled(true, 'ru');
    p.run(false, false);
    await settle();
    assert.equal(badge(p).hidden, true, 'red count disappears at zero');
    p.requests.splice(0);

    now += 5 * 60 * 1000;
    p.w.__univerNoticesV1.present(true);
    assert.equal(badge(p).hidden, true, 'still hidden between checks');
    assert.deepEqual(p.requests, []);

    now += 60 * 60 * 1000;
    p.w.__univerNoticesV1.present(true);
    assert.equal(badge(p).hidden, false, 'after 1 hour the cached check no longer hides the site badge');

    // Back within 1 hour: hide again, then switch to Original mode and verify exact DOM restoration.
    now -= 50 * 60 * 1000;
    p.w.__univerNoticesV1.present(true);
    assert.equal(badge(p).hidden, true);
    p.w.__univerMobileV1.setEnabled(false, 'ru');
    assert.equal(badge(p), original);
    assert.equal(original.firstChild, originalText);
    assert.equal(original.outerHTML, originalMarkup);
  } finally { p.w.close(); }
});

test('mobile layout applies cached survey count before notices script loads, hands off cleanly, and restores in Original', async () => {
  const p = page(shell(rows), 'https://univer.kaznu.kz/student/rating/', { routes: routesFor('done'), withMobile: true });
  try {
    let now = 2000000;
    p.w.Date.now = () => now;
    p.w.localStorage.setItem('univer.app.account.v1', 'a1');
    p.w.localStorage.setItem('univer.app.survey-count.v1', JSON.stringify({
      owner: 'a1', count: 2, open: 1, details: [{ t: 'Анкета', e: '30.11.2026' }], at: now
    }));
    const original = badge(p), originalText = original.firstChild, originalMarkup = original.outerHTML;

    // Mobile adapts before univer-notices.js is ever injected.
    p.w.__univerMobileV1.setEnabled(true, 'ru');
    assert.equal(badge(p).textContent.trim(), '(1)', 'cached unanswered count shows on the first adapted frame');
    assert.equal(badge(p).hidden, false);

    // Switching to Original restores even without univer-notices.js loaded.
    p.w.__univerMobileV1.setEnabled(false, 'ru');
    assert.equal(badge(p), original);
    assert.equal(original.firstChild, originalText);
    assert.equal(original.outerHTML, originalMarkup);

    // Re-enable Mobile, then inject univer-notices.js with refresh: handoff preserves site count (2) and hides at 0.
    p.w.__univerMobileV1.setEnabled(true, 'ru');
    assert.equal(badge(p).textContent.trim(), '(1)');
    const initial = rating(p.run(false, true));
    assert.equal(initial.n, 2, 'notices script reads original site count after handoff');
    await settle();
    assert.equal(checkedRating(p).o, 0);
    assert.equal(badge(p).hidden, true, 'badge hides once all questionnaires are verified answered');

    p.w.__univerMobileV1.setEnabled(false, 'ru');
    assert.equal(badge(p), original);
    assert.equal(original.firstChild, originalText);
    assert.equal(original.outerHTML, originalMarkup);
  } finally { p.w.close(); }
});

test('ten questionnaires are checked in full, and eleven fall back without a partial count', async () => {
  const list = count => '<table>' + Array.from({ length: count }, (_, i) =>
    `<tr class="link" id="${i + 1}"><td>Анкета ${i + 1}</td><td>Тип</td></tr>`).join('') + '</table>';
  const routes = Object.fromEntries(Array.from({ length: 10 }, (_, i) => [`/student/rating/${i + 1}`, answers('')]));
  const exact = page(shell(list(10)), 'https://univer.kaznu.kz/student/rating/', { routes });
  exact.run(false, false, null);
  exact.w.__univerNoticesV1.present(true);
  exact.run(false, false);
  await settle();
  assert.equal(exact.requests.length, 10);
  assert.equal(checkedRating(exact).o, 10);
  assert.equal(checkedRating(exact).d.length, 10);
  assert.equal(badge(exact).textContent.trim(), '(10)');
  exact.w.close();

  const over = page(shell(list(11)), 'https://univer.kaznu.kz/student/rating/');
  over.run(false, false);
  await settle();
  assert.deepEqual(over.requests, []);
  assert.equal(checkedRating(over).o, undefined);
  assert.equal(checkedRating(over).n, 2);
  over.w.close();
});

test('a missing id, duplicate id, slow check, or unreadable detail page makes the check inconclusive', async () => {
  const noId = page(shell(rows.replace(' id="207"', '')), 'https://univer.kaznu.kz/student/rating/', { routes: routesFor('done') });
  noId.run(false, false);
  await settle();
  assert.deepEqual(noId.requests, []);
  assert.equal(checkedRating(noId).o, undefined);
  noId.w.close();

  const dupId = page(shell(rows.replace(' id="207"', ' id="208"')), 'https://univer.kaznu.kz/student/rating/', { routes: routesFor('done') });
  dupId.run(false, false);
  await settle();
  assert.deepEqual(dupId.requests, ['/student/rating/208']);
  assert.equal(checkedRating(dupId).o, undefined);
  dupId.w.close();

  const slow = page(shell(rows), 'https://univer.kaznu.kz/student/rating/', { routes: routesFor('') });
  const origFetch = slow.w.fetch;
  let time = 0;
  slow.w.performance.now = () => time;
  slow.w.fetch = address => { time = 15001; return origFetch(address); };
  slow.run(false, false);
  await settle();
  assert.deepEqual(slow.requests, ['/student/rating/208']);
  assert.equal(checkedRating(slow).o, undefined);
  slow.w.close();

  const routes = routesFor('done');
  delete routes['/student/rating/207'];
  const broken = page(shell(rows), 'https://univer.kaznu.kz/student/rating/', { routes });
  broken.w.localStorage.setItem('univer.app.account.v1', 'a1');
  broken.w.localStorage.setItem('univer.app.survey-count.v1', JSON.stringify({ owner: 'a1', count: 2, open: 0, details: [], at: broken.w.Date.now() }));
  broken.run(false, false, null);
  broken.w.__univerNoticesV1.present(true);
  assert.equal(badge(broken).hidden, true);
  broken.run(false, 1);
  await settle();
  assert.equal(checkedRating(broken).o, undefined);
  assert.deepEqual(checkedRating(broken).d, []);
  assert.equal(badge(broken).hidden, false);
  assert.equal(badge(broken).textContent.trim(), '(2)');
  broken.w.close();
});

test('queued refreshes run in order and fail cleanly if the account changes before starting', async () => {
  const p = page(shell(rows), 'https://univer.kaznu.kz/student/rating/', { routes: routesFor('') });
  try {
    const account = 'univer.app.account.v1', origFetch = p.w.fetch;
    let releaseFirst;
    const first = new Promise(resolve => { releaseFirst = resolve; });
    p.w.fetch = async (address, options) => {
      const response = await origFetch(address, options);
      if (address.endsWith('/208')) await first;
      return response;
    };
    p.w.localStorage.setItem(account, 'a1');
    assert.equal(p.run(false, true).fetching, true);
    assert.equal(p.run(false, 7).fetching, true);
    assert.equal(p.run(false, 8).fetching, true);
    p.w.localStorage.setItem(account, 'b2');
    releaseFirst();
    await settle();
    assert.deepEqual(p.requests, ['/student/rating/208', '/student/rating/207']);
    assert.deepEqual(messages(p).filter(m => m.refresh === 7 || m.refresh === 8), [7, 8].map(r =>
      ({ v: 1, g: 1, kind: 'done', page: '/student/rating/', read: 0, failed: 1, refresh: r })));
  } finally { p.w.close(); }
});

test('background reads decode response charset and cancel oversized streams at 3 MB', async () => {
  // Windows-1251 encoded shell for /ssc/ containing "Анкетирование" with no count.
  const winBytes = new Uint8Array([0x3c,0x74,0x61,0x62,0x6c,0x65,0x20,0x69,0x64,0x3d,0x22,0x74,0x6f,0x6f,0x6c,0x73,0x22,0x3e,0x3c,0x74,0x72,0x3e,0x3c,0x74,0x64,0x3e,0x3c,0x75,0x6c,0x3e,0x3c,0x6c,0x69,0x3e,0x3c,0x61,0x20,0x68,0x72,0x65,0x66,0x3d,0x22,0x2f,0x73,0x74,0x75,0x64,0x65,0x6e,0x74,0x2f,0x72,0x61,0x74,0x69,0x6e,0x67,0x2f,0x22,0x3e,0xc0,0xed,0xea,0xe5,0xf2,0xe8,0xf0,0xee,0xe2,0xe0,0xed,0xe8,0xe5,0x3c,0x2f,0x61,0x3e,0x3c,0x2f,0x6c,0x69,0x3e,0x3c,0x2f,0x75,0x6c,0x3e,0x3c,0x2f,0x74,0x64,0x3e,0x3c,0x2f,0x74,0x72,0x3e,0x3c,0x2f,0x74,0x61,0x62,0x6c,0x65,0x3e]);
  const p = page(shell('<p>Бакалавр</p>'));
  let reads = 0, cancels = 0;
  const bigChunk = new Uint8Array(1024 * 1024);
  p.w.fetch = address => {
    p.requests.push(address);
    if (address === '/ssc/') {
      const pieces = [winBytes.slice(0, 40), winBytes.slice(40)];
      return Promise.resolve({
        ok: true, status: 200, url: 'https://univer.kaznu.kz' + address,
        headers: { get: name => /content-type/i.test(name) ? 'text/html; charset=windows-1251' : null },
        body: { getReader: () => ({ read: async () => pieces.length ? { value: pieces.shift(), done: false } : { done: true }, cancel: async () => {}, releaseLock() {} }) }
      });
    }
    return Promise.resolve({
      ok: true, status: 200, url: 'https://univer.kaznu.kz' + address,
      headers: { get: name => /content-type/i.test(name) ? 'text/html; charset=utf-8' : null },
      body: { getReader: () => ({ read: async () => { reads++; return { value: bigChunk, done: false }; }, cancel: async () => { cancels++; }, releaseLock() {} }) }
    });
  };
  p.run(true, true);
  await settle();
  const list = messages(p);
  const ssc = list.find(m => m.page === '/ssc/');
  assert.ok(ssc, 'windows-1251 streamed page parsed');
  assert.equal(rating(ssc).t, 'Анкетирование');
  assert.equal(reads, 3, 'oversized /student/rating/ stream stops at the 3rd 1 MB chunk (> 3000000 bytes)');
  assert.equal(cancels, 1, 'oversized stream is cancelled');
  const done = list[list.length - 1];
  assert.deepEqual([done.kind, done.read, done.failed], ['done', 1, 1]);
  p.w.close();
});

test('the checked questionnaire report keeps the current page message, which it would otherwise erase', async () => {
  const p = page(shell('<div class="message">Необходимо пройти анкетирование преподавателей до 20.12.2026</div>'),
    'https://univer.kaznu.kz/student/attendance/full/', { routes: routesFor('') });
  const first = p.run(false);
  assert.equal(first.blocks.length, 1);
  await settle();
  const checked = messages(p).find(message => message.page === first.page && message.kind === 'section');
  assert.equal(checked.links[0].o, 1);
  assert.deepEqual(checked.blocks, first.blocks);
  p.w.close();
});
