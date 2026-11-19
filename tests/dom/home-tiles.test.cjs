const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');

const script = ['univer-dom.js', 'univer-shortcuts.js'].map(name => fs.readFileSync('app/src/main/assets/' + name, 'utf8')).join('\n');
// Whether the tiles script treats the address as the home page (the root class it sets before drawing the tiles).
function isHome(url) {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', { url, runScripts: 'outside-only' });
  const w = dom.window;
  try {
    w.eval(script);
    w.__univerShortcutsV1.apply('ru');
    return w.document.documentElement.classList.contains('univer-app-home');
  } finally { w.close(); }
}

// After sign-in the site lands on /news/student/1/ (seen on the live cabinet, 15 Nov 2026): that is still the home page.
test('the news page the site opens after sign-in is the home page, a later news page is not', () => {
  assert.equal(isHome('https://univer.kaznu.kz/news/student/1/'), true);
  assert.equal(isHome('https://univer.kaznu.kz/news/student/1'), true);
  assert.equal(isHome('https://univer.kaznu.kz/news/student'), true);
  assert.equal(isHome('https://univer.kaznu.kz/student/bachelor/'), true);
  assert.equal(isHome('https://univer.kaznu.kz/news/student/2/'), false);
  assert.equal(isHome('https://univer.kaznu.kz/news/student/12/'), false);
});

test('menu pin buttons follow the pins and the menu links, and an unchanged menu is not redrawn', () => {
  const menu = '<details class="univer-app-menu"><summary>Все разделы</summary><div id="tools"><ul>' +
    '<li><a href="/student/rating/">Анкетирование</a></li><li><a href="/student/schedule/exam/">Экзамены</a></li></ul></div></details>';
  const w = new JSDOM('<!doctype html><html><body>' + menu + '</body></html>', { url: 'https://univer.kaznu.kz/student/bachelor/', runScripts: 'outside-only' }).window;
  try {
    w.eval(script);
    const shortcuts = w.__univerShortcutsV1, d = w.document, buttons = () => Array.from(d.querySelectorAll('#tools .univer-app-pin'));
    shortcuts.apply('ru');
    assert.equal(buttons().length, 2);
    assert.deepEqual(buttons().map(b => b.getAttribute('aria-pressed')), ['false', 'false']);
    shortcuts.configure([{ title: 'Анкетирование', url: 'https://univer.kaznu.kz/student/rating/' }]);
    assert.deepEqual(buttons().map(b => b.getAttribute('aria-pressed')), ['true', 'false']);
    // Another pass over the same menu leaves it as it is, even a label the page changed meanwhile.
    const first = buttons()[0]; first.setAttribute('aria-label', 'changed');
    shortcuts.apply('ru');
    assert.equal(buttons()[0], first); assert.equal(first.getAttribute('aria-label'), 'changed');
    // A link the site adds gets its button on the next pass; a language change relabels all of them.
    const li = d.createElement('li'); li.innerHTML = '<a href="/student/umkd/">УМКД</a>'; d.querySelector('#tools ul').appendChild(li);
    shortcuts.apply('ru');
    assert.equal(buttons().length, 3);
    shortcuts.apply('en');
    assert.ok(buttons()[0].getAttribute('aria-label').startsWith('Unpin'), buttons()[0].getAttribute('aria-label'));
    shortcuts.release();
    assert.equal(buttons().length, 0);
  } finally { w.close(); }
});
