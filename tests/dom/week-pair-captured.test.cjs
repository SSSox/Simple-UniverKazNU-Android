const nodeTest = require('node:test');
const opened = [];
const test = (name, body) => nodeTest.test(name, async () => { try { await body(); } finally { opened.splice(0).forEach(w => { try { w.close(); } catch (_) {} }); } });
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');
const jsdomUtils = require('jsdom/lib/jsdom/living/generated/utils');

// The neighbouring week «В календарь» and the schedule export read goes through the keyed channel's reader
// (univer-channel.js clock.page): fetch, Response, URL, DOMParser and Promise as they were at document start. A site
// script that swaps them at page load, before the modules draw anything, neither blocks the neighbour (which would turn a
// weekly class into «every second week, other week unread») nor puts its own pair into the keyed message.
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
const settle = async () => { for (let i = 0; i < 40; i++) await new Promise(r => setImmediate(r)); };

// The WebView's own fetch and Response, there before any script of the page: the neighbouring week has the same class.
const WEBVIEW = `
  window.__reads = 0;
  window.Response = function Response(url, html) { this._url = url; this._html = html; };
  Object.defineProperty(Response.prototype, 'status', { get: function () { return 200; }, configurable: true });
  Object.defineProperty(Response.prototype, 'url', { get: function () { return this._url; }, configurable: true });
  Response.prototype.text = function () { return Promise.resolve(this._html); };
  window.fetch = function (address) { window.__reads++; return Promise.resolve(new Response(String(address), window.__neighbour)); };
`;
// What a site script does at load: every reader the module used to take from window answers for the page.
const SWAP = `
  var fake = {v:1, term:'2026/1', until:'2026-01-01', weeks:[{lessons:[]}, {lessons:[]}], missing:[]};
  window.fetch = function () { return new Promise(function () { }); };
  Response.prototype.text = function () { return Promise.resolve('<p>nothing</p>'); };
  DOMParser.prototype.parseFromString = function () { return document.implementation.createHTMLDocument(''); };
`;
// A then that answers every promise with the page's pair (swapped once the cards are drawn: the site's other modules
// in the same pass chain promises of their own).
const THEN = `
  var fake = {v:1, term:'2026/1', until:'2026-01-01', weeks:[{lessons:[]}, {lessons:[]}], missing:[]}, then = Promise.prototype.then;
  Promise.prototype.then = function (done, failed) { return then.call(Promise.resolve(fake), done, failed); };
`;

function page(modules, start, before, neighbour = 7) {
  const html = '<table class="wrappertable"><tr id="l1"><td class="header">КазНУ</td></tr><tr id="l4"><td>' + schedule([POLITICS]) + '</td></tr></table>';
  const w = new JSDOM('<!doctype html><html><body>' + html + '</body></html>', { url: 'https://univer.kaznu.kz/student/myschedule/', runScripts: 'outside-only', virtualConsole: new VirtualConsole() }).window;
  opened.push(w);
  const app = [];
  w.__univerAppEvents = { postMessage: value => app.push(value) };
  w.__univerNoticeEvents = { postMessage: () => {} };
  w.__neighbour = '<table><tr id="l4"><td>' + schedule([POLITICS], neighbour) + '</td></tr></table>';
  w.eval(WEBVIEW);
  w.eval(channel);
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  if (before) w.eval(before);
  Object.defineProperty(w.document, 'readyState', { value: 'complete', configurable: true });
  w.HTMLElement.prototype.getClientRects = function () { return this.closest('[hidden]') ? [] : [{ width: 100, height: 30 }]; };
  w.jQuery = () => ({ bind() {} });
  w.TextDecoder = TextDecoder;
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
const OVERVIEW = ['univer-dom.js', 'univer-lesson.js', 'univer-overview.js'];

test('without a swap the calendar reads the neighbour through the channel\'s reader: weekly', async () => {
  const p = page(MOBILE, "window.__univerMobileV1.setEnabled(true,'ru');window.__univerStyleV1.apply(true);");
  tap(p.w, p.d.querySelector('button.univer-app-calendar')); await settle();
  const [event] = sent(p, 'univer-calendar:');
  assert.ok(event);
  assert.ok(p.w.__reads >= 1, 'the WebView\'s fetch read the neighbour');
  assert.equal(event.interval, undefined);
  assert.equal(event.partial, undefined);
});

test('fetch, Response, DOMParser and Promise swapped at load neither block the neighbour nor change the calendar event', async () => {
  const p = page(MOBILE, "window.__univerMobileV1.setEnabled(true,'ru');window.__univerStyleV1.apply(true);", SWAP);
  const button = p.d.querySelector('button.univer-app-calendar');
  assert.ok(button, 'the card has its button');
  p.w.eval(THEN);
  tap(p.w, button); await settle();
  const [event] = sent(p, 'univer-calendar:');
  assert.ok(event, 'the tap sends the event');
  assert.ok(p.w.__reads >= 1, 'the fetch taken at document start read the neighbour');
  assert.equal(event.title, 'Политология (лекция)');
  assert.equal(event.interval, undefined, 'the neighbour was read and has the class: weekly');
  assert.equal(event.partial, undefined, 'the neighbour is not reported unread');
});

test('the same swaps at load do not put another pair into the keyed export', async () => {
  const p = page(OVERVIEW, "window.__univerOverviewV1.apply('ru');", SWAP + THEN);
  const ics = p.d.querySelector('.univer-app-schedule-export button[data-univer-export=ics]');
  assert.ok(ics);
  tap(p.w, ics); await settle();
  const exports = sent(p, 'univer-export:');
  assert.equal(exports.length, 1);
  const pair = exports[0];
  assert.equal(pair.v, 1);
  assert.equal(pair.kind, 'ics');
  assert.equal(pair.term, '2026/1');
  assert.deepEqual(pair.missing, []);
  assert.equal(pair.weeks.length, 2, 'both weeks, the neighbour read by the channel');
  assert.equal(pair.weeks[1].monday, `${day(7).getFullYear()}-${two(day(7).getMonth() + 1)}-${two(day(7).getDate())}`);
  assert.equal(pair.weeks[1].lessons[0].subject, 'Политология');
  assert.equal('then' in pair, false, 'the pair\'s guard field is not sent');
});

test('a neighbour page the WebView answers with another week is still reported unread, not taken', async () => {
  // The address of the next week answers with the shown week's page.
  const p = page(OVERVIEW, "window.__univerOverviewV1.apply('ru');", SWAP + THEN, 0);
  tap(p.w, p.d.querySelector('.univer-app-schedule-export button[data-univer-export=ics]')); await settle();
  const [pair] = sent(p, 'univer-export:');
  assert.equal(pair.weeks.length, 1);
  assert.deepEqual(pair.missing, [13]);
});
