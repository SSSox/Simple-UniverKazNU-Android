const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');

// The news pager learns the last page by reading pages (the site shows only a next arrow): only when the student turns
// to the page navigation, and the count is kept for a day across app starts.
const kotlin = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
const scripts = JSON.parse('[' + kotlin.match(/val SCRIPTS = listOf\(([^)]*)\)/)[1] + ']');
const LAST = 3, KEY = 'univer.desktop.news-page-count.v1';

const shell = content => '<table class="wrappertable"><tr id="l4"><td class="center">' + content + '</td></tr></table>';
const news = page => shell('<table class="inner"><tr><th>Дата</th><th>Новость</th></tr><tr class="news"><td><div class="date"><div class="day">10</div>0' + page +
  '.2026</div></td><td><p>Синтетическая новость ' + page + '</p></td></tr></table><table><tr><td class="tb">Страница ' + page +
  (page < LAST ? ' <a href="/my/news/' + (page + 1) + '">&gt;</a>' : '') + '</td></tr></table>');

function page(local = {}) {
  const jsdom = new JSDOM('<html><head></head><body>' + news(1) + '</body></html>', { url: 'https://univer.kaznu.kz/news/student', runScripts: 'outside-only', virtualConsole: new VirtualConsole(), pretendToBeVisual: true });
  const w = jsdom.window, requests = [];
  Object.defineProperty(w.document, 'readyState', { value: 'complete', configurable: true });
  w.CSS = { supports: () => true };
  w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
  Object.entries(local).forEach(([key, value]) => w.localStorage.setItem(key, value));
  w.fetch = address => {
    requests.push(address);
    const number = +(address.match(/\/news\/student\/(\d+)$/)?.[1] || 1);
    const body = number <= LAST ? '<html><body>' + news(number) + '</body></html>' : '<html><body>' + shell('<table><tr><td class="tb">Страница ' + number + '</td></tr></table>') + '</body></html>';
    return Promise.resolve({ status: 200, url: 'https://univer.kaznu.kz' + address, headers: { get: () => null }, text: async () => body });
  };
  scripts.forEach(name => w.eval(fs.readFileSync('app/src/main/assets/' + name, 'utf8')));
  w.__univerMobileV1.setEnabled(true, 'ru'); w.__univerStyleV1.apply(true);
  return { w, d: w.document, requests };
}
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const until = async check => { for (let i = 0; i < 200 && !check(); i++) await wait(5); assert.ok(check(), 'the page did not reach the expected state'); };
const summary = p => p.d.querySelector('.univer-app-news-pages>summary').textContent;

test('opening the news reads no other page; opening the page navigation counts the pages once and keeps it open', async () => {
  const p = page();
  await wait(700);
  assert.deepEqual(p.requests, [], 'no page is read before the student turns to the pager');
  assert.equal(summary(p), 'Страница 1');
  const pages = p.d.querySelector('.univer-app-news-pages');
  pages.open = true; pages.dispatchEvent(new p.w.Event('toggle'));
  await until(() => summary(p) === 'Страница 1 / ' + LAST);
  assert.ok(p.requests.length > 0 && p.requests.every(address => /^\/news\/student\/\d+$/.test(address)));
  assert.equal(p.d.querySelector('.univer-app-news-pages').open, true, 'the navigation the student opened stays open');
  assert.equal(JSON.parse(p.w.localStorage.getItem(KEY)).count, LAST);
  const before = p.requests.length;
  p.d.querySelector('.univer-app-news-pager').dispatchEvent(new p.w.Event('pointerdown', { bubbles: true }));
  await wait(50);
  assert.equal(p.requests.length, before, 'a known count is not read again');
  p.w.__univerMobileV1.setEnabled(false, 'ru'); p.w.close();
});

test('a count from earlier today is used without reading pages; a day-old one is read again on the pager', async () => {
  const fresh = page({ [KEY]: JSON.stringify({ count: LAST, time: Date.now() - 3600000 }) });
  await wait(50);
  assert.equal(summary(fresh), 'Страница 1 / ' + LAST);
  fresh.d.querySelector('.univer-app-news-pages').dispatchEvent(new fresh.w.Event('toggle'));
  await wait(50);
  assert.deepEqual(fresh.requests, []);
  fresh.w.__univerMobileV1.setEnabled(false, 'ru'); fresh.w.close();
  const old = page({ [KEY]: JSON.stringify({ count: LAST, time: Date.now() - 25 * 3600000 }) });
  await wait(50);
  assert.equal(summary(old), 'Страница 1');
  old.d.querySelector('.univer-app-news-pager').dispatchEvent(new old.w.Event('focusin', { bubbles: true }));
  await until(() => summary(old) === 'Страница 1 / ' + LAST);
  old.w.__univerMobileV1.setEnabled(false, 'ru'); old.w.close();
});
