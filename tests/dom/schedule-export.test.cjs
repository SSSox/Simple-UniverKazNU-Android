const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const jsdomUtils = require('jsdom/lib/jsdom/living/generated/utils');

// The schedule page's «В календарь»: on the student's own press the page reads the shown week and its neighbour (the week
// right after, else right before) by GET and sends both to the app, which writes the calendar file. The site's structure is
// as in timetable.test.cjs; names are synthetic.
const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const scripts = ['univer-dom.js', 'univer-lesson.js', 'univer-overview.js'].map(read);
const BASE = 'https://univer.kaznu.kz';
const weeks = [
  { n: 11, a: '09.11.2026', b: '15.11.2026' },
  { n: 12, a: '16.11.2026', b: '22.11.2026' },
  { n: 13, a: '23.11.2026', b: '29.11.2026' }];
const value = w => '/student/myschedule/2026/1/' + w.a + '/' + w.b + '/';
const options = (selected, list = weeks) => list.map((w, i) => `<option value="${value(w)}"${i === selected ? ' selected' : ''}>Неделя ${w.n}. ${w.a} - ${w.b}</option>`).join('');
const cell = (subject, type, room) => `<td class="field"><div class="groups"><div><p class="teacher">${subject}(${type})</p><p class="teacher">Учителева А. П.</p>` +
  `<p class="params"><span class="aud_faculty">ФИТ</span><span>Ауд.: ${room}</span></p></div></div></td>`;
const time = t => `<td class="time"><div class="divIE" style="display:none">${t}</div></td>`;
// Monday 9:00 [monday]; Wednesday 10:00 [wednesday].
const table = (monday, wednesday) => '<table class="schedule"><tr><th>&nbsp;</th><th>ПН</th><th>ВТ</th><th>СР</th></tr>' +
  `<tr>${time('09:00-09:50')}${monday ? cell(monday, 'лекция', '309') : '<td class="field"></td>'}<td class="field"></td><td class="field"></td></tr>` +
  `<tr>${time('10:00-10:50')}<td class="field"></td><td class="field"></td>${wednesday ? cell(wednesday, 'семинар', '233') : '<td class="field"></td>'}</tr></table>`;
const html = (selected, monday, wednesday, list) => `<table><tr id="l4"><td><select id="weekSelect">${options(selected, list)}</select>${table(monday, wednesday)}</td></tr></table>`;

function page({ selected = 1, list, fetch, lang = 'ru', native = true, mark = 'Политология', second = 'Культурология' } = {}) {
  const dom = new JSDOM(html(selected, mark, null, list), { url: BASE + '/student/myschedule/', runScripts: 'outside-only' });
  const w = dom.window, sent = [], requests = [];
  w.TextDecoder = TextDecoder;
  if (native) w.__univerNative = m => sent.push(m);
  w.fetch = async (url, init) => {
    requests.push({ url: String(url), init });
    if (fetch) return fetch(String(url), init);
    const at = (list || weeks).findIndex(x => String(url).includes(x.a));
    return { status: 200, url: String(url), text: async () => html(at, second, mark, list) };
  };
  scripts.forEach(script => w.eval(script));
  w.__univerOverviewV1.apply(lang);
  return { w, d: w.document, sent, requests };
}
function trusted(w, node) {
  const event = new w.MouseEvent('click', { bubbles: true, cancelable: true });
  const impl = jsdomUtils.implForWrapper(event); impl.isTrusted = true;
  jsdomUtils.implForWrapper(node)._dispatch(impl);
}
const button = p => p.d.querySelector('.univer-app-schedule-export button[data-univer-export=ics]');
const settle = async () => { for (let i = 0; i < 30; i++) await new Promise(r => setImmediate(r)); };
// The week the page keeps for the widget is sent too when it is the current one; only the export messages count here.
const sentExports = p => p.sent.filter(m => m.startsWith('univer-export:'));
const exported = p => { assert.equal(sentExports(p).length, 1); return JSON.parse(sentExports(p)[0].slice('univer-export:'.length)); };
const subjects = week => week.lessons.map(l => [l.day, l.start, l.end, l.subject]);

test('the schedule page has the calendar button in the page language, and none without the app channel', () => {
  for (const [lang, label] of [['ru', 'В календарь'], ['en', 'To calendar'], ['kz', 'Күнтізбеге']]) {
    const p = page({ lang });
    const group = p.d.querySelector('.univer-app-schedule-export');
    assert.equal(group.getAttribute('role'), 'group');
    assert.equal(button(p).textContent, label);
    assert.equal(button(p).type, 'button');
    p.w.__univerOverviewV1.apply(lang);
    assert.equal(p.d.querySelectorAll('.univer-app-schedule-export').length, 1, 'a second pass draws nothing again');
    p.w.__univerOverviewV1.release();
    assert.equal(p.d.querySelector('.univer-app-schedule-export'), null, 'the Original layout takes it away');
    p.w.close();
  }
  const lone = page({ native: false });
  assert.equal(lone.d.querySelector('.univer-app-schedule-export'), null);
  lone.w.close();
});

test('only the student\'s own press reads the neighbour, and a second press while busy does nothing', async () => {
  const p = page();
  button(p).click(); await settle();
  assert.deepEqual(sentExports(p), []); assert.equal(p.requests.length, 0, 'a scripted click reads nothing');
  trusted(p.w, button(p)); trusted(p.w, button(p));
  assert.equal(button(p).getAttribute('aria-busy'), 'true');
  assert.equal(button(p).disabled, false, 'never disabled: the pressed button keeps the keyboard focus');
  await settle();
  assert.equal(p.requests.length, 1);
  assert.equal(sentExports(p).length, 1);
  assert.equal(button(p).hasAttribute('aria-busy'), false);
  p.w.close();
});

test('a press reads the week right after, and sends both weeks with their classes', async () => {
  const p = page();
  trusted(p.w, button(p)); await settle();
  assert.equal(p.requests[0].url, BASE + value(weeks[2]));
  assert.equal(p.requests[0].init.redirect, 'error', 'a redirect is never followed');
  assert.equal(p.requests[0].init.credentials, 'same-origin');
  const data = exported(p);
  assert.equal(data.v, 1); assert.equal(data.kind, 'ics'); assert.equal(data.term, '2026/1');
  assert.equal(data.until, '2026-11-29', 'the last day the site lists');
  assert.deepEqual(data.missing, []);
  assert.deepEqual(data.weeks.map(w => [w.number, w.labelled, w.monday, w.first, w.last]),
    [[12, true, '2026-11-16', '2026-11-16', '2026-11-22'], [13, true, '2026-11-23', '2026-11-23', '2026-11-29']]);
  assert.deepEqual(subjects(data.weeks[0]), [[0, '09:00', '09:50', 'Политология']]);
  assert.deepEqual(subjects(data.weeks[1]), [[0, '09:00', '09:50', 'Культурология'], [2, '10:00', '10:50', 'Политология']]);
  p.w.close();
});

test('the last week of the list is paired with the week before it, and a gap is not a neighbour', async () => {
  const last = page({ selected: 2 });
  trusted(last.w, button(last)); await settle();
  assert.equal(last.requests[0].url, BASE + value(weeks[1]));
  assert.deepEqual(exported(last).weeks.map(w => w.number), [13, 12]);
  last.w.close();
  // Weeks 11 and 13 only: a week after a gap may be of the same parity, so it is not read at all.
  const gap = page({ selected: 0, list: [weeks[0], weeks[2]] });
  trusted(gap.w, button(gap)); await settle();
  assert.equal(gap.requests.length, 0);
  const data = exported(gap);
  assert.deepEqual([data.weeks.length, data.missing], [1, []]);
  gap.w.close();
});

test('a neighbour that cannot be read is named in missing and nothing stands in for it', async () => {
  const bad = {
    'a failing request': () => { throw new TypeError('redirect'); },
    'a server error': url => ({ status: 500, url, text: async () => '' }),
    'a sign-in page': url => ({ status: 200, url, text: async () => '<form id="login_frm"></form>' }),
    'a redirect to another page': () => ({ status: 200, url: BASE + '/user/login', text: async () => html(2, 'X', null) }),
    'another origin': () => ({ status: 200, url: 'https://evil.example/student/myschedule/', text: async () => html(2, 'X', null) }),
    'the shown week again': url => ({ status: 200, url, text: async () => html(1, 'X', null) }),
    'a page without the table': url => ({ status: 200, url, text: async () => '<select id="weekSelect"></select>' })
  };
  for (const [name, fetch] of Object.entries(bad)) {
    const p = page({ fetch });
    trusted(p.w, button(p)); await settle();
    const data = exported(p);
    assert.deepEqual(data.weeks.map(w => w.number), [12], name);
    assert.deepEqual(data.missing, [13], name);
    p.w.close();
  }
});

test('a page that cannot be read at all is reported to the app, not dropped', async () => {
  const p = page();
  p.d.querySelector('table.schedule').remove();
  trusted(p.w, button(p)); await settle();
  assert.deepEqual(sentExports(p), ['univer-export:{"v":0,"kind":"ics"}']);
  assert.equal(button(p).hasAttribute('aria-busy'), false);
  p.w.close();
});

test('weeks the site does not number are numbered by their place in the list', async () => {
  const dom = page({ list: weeks.map(w => ({ ...w, n: '' })) });
  trusted(dom.w, button(dom)); await settle();
  assert.deepEqual(exported(dom).weeks.map(w => [w.number, w.labelled]), [[2, false], [3, false]]);
  dom.w.close();
});
