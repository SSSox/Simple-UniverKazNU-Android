const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');

// «Новый раздел» opens a sheet from the bottom of the screen; a finger pulls it down to close it.
const script = ['univer-dom.js', 'univer-shortcuts.js'].map(name => fs.readFileSync('app/src/main/assets/' + name, 'utf8')).join('\n');
const rows = Array.from({ length: 12 }, (_, i) => `<li><a href="/student/section${i}/">Раздел ${i}</a></li>`).join('');
const html = '<table class="wrappertable"><tr id="l4"><td class="center"><details class="univer-app-menu" open><summary>Разделы</summary>' +
  `<div id="tools"><ul><li><a href="/student/myschedule/">Расписание</a></li>${rows}</ul></div></details><p>Новости</p></td></tr></table>`;

function sheet(motion = false) {
  let clock = 0;
  const impl = require('jsdom/lib/jsdom/living/generated/utils').implForWrapper;
  const dom = new JSDOM(html, { url: 'https://univer.kaznu.kz/news/student/1/', runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window, d = w.document, sent = [];
  w.HTMLElement.prototype.getClientRects = function () { return [{ width: 1, height: 1 }]; };
  w.HTMLElement.prototype.getBoundingClientRect = function () { return { top: 0, left: 0, width: 400, height: 600, right: 400, bottom: 600 }; };
  w.__univerMotion = motion;
  w.__univerNative = value => sent.push(value);
  w.eval(script);
  w.__univerShortcutsV1.apply('ru');
  // Only a real tap opens the sheet or pins a row; jsdom keeps an event trusted when it is sent through its internal dispatch.
  const tap = node => { const event = impl(new w.MouseEvent('click', { bubbles: true, cancelable: true, detail: 1 })); event.isTrusted = true; event.timeStamp = clock += 16; impl(node)._dispatch(event); };
  tap(d.querySelector('.univer-app-add-tile'));
  // jsdom has no Touch constructor: the module reads only touches[0].clientX/clientY and the event's time.
  const touch = (node, type, y, x = 100) => {
    const event = new w.Event(type, { bubbles: true, cancelable: true });
    const list = type === 'touchend' || type === 'touchcancel' ? [] : [{ clientX: x, clientY: y }];
    Object.defineProperty(event, 'touches', { value: list });
    Object.defineProperty(event, 'timeStamp', { value: clock += 16 });
    node.dispatchEvent(event);
    return event;
  };
  const pull = (node, ys, x) => { touch(node, 'touchstart', ys[0], x); const moves = ys.slice(1).map(y => touch(node, 'touchmove', y, x)); touch(node, 'touchend', ys.at(-1), x); return moves; };
  return { w, d, sent, get panel() { return d.querySelector('.univer-app-add-panel'); }, touch, pull, tap, reopen: () => tap(d.querySelector('.univer-app-add-tile')), clock: ms => { clock += ms; }, open: () => !!d.querySelector('.univer-app-add-layer'), close: () => w.close() };
}

test('the sheet has a grip and closes when pulled down a third of its height from the title', () => {
  const s = sheet();
  assert.ok(s.panel.querySelector(':scope>.univer-app-add-grip'), 'a visible grip shows the sheet can be pulled');
  const moves = s.pull(s.panel.querySelector('.univer-app-add-head'), [100, 112, 160, 240, 330]);
  assert.equal(moves.at(-1).defaultPrevented, true, 'the page behind does not scroll while the sheet follows the finger');
  assert.equal(s.open(), false);
  assert.deepEqual(s.sent, [], 'closing pins nothing');
  s.close();
});

test('a short slow pull springs back, a quick flick closes', () => {
  const s = sheet(), head = s.panel.querySelector('.univer-app-add-head');
  s.touch(head, 'touchstart', 100); s.touch(head, 'touchmove', 112); s.touch(head, 'touchmove', 150);
  assert.equal(s.panel.style.transform, 'translateY(38px)', 'the sheet follows the finger one to one after the slop');
  // Held still before lifting: no speed left, 38 of 600 px is not enough.
  for (let i = 0; i < 6; i++) s.touch(head, 'touchmove', 150);
  s.touch(head, 'touchend', 150);
  assert.equal(s.open(), true); assert.equal(s.panel.style.transform, '');
  s.pull(head, [100, 112, 140, 170]);
  assert.equal(s.open(), false, 'about 1.2 px/ms over 58 px closes it');
  s.close();
});

test('the list scrolls under the finger unless it is at its top, and a pulled row does not pin itself', () => {
  const s = sheet(), list = s.panel.querySelector('.univer-app-add-list'), row = list.querySelector('.univer-app-add-row');
  list.scrollTop = 120; Object.defineProperty(list, 'scrollTop', { configurable: true, value: 120 });
  const moves = s.pull(row, [100, 140, 260, 400]);
  assert.equal(moves.some(e => e.defaultPrevented), false); assert.equal(s.open(), true);
  Object.defineProperty(list, 'scrollTop', { configurable: true, value: 0 });
  s.touch(row, 'touchstart', 100); s.touch(row, 'touchmove', 112); s.touch(row, 'touchmove', 140);
  for (let i = 0; i < 6; i++) s.touch(row, 'touchmove', 140);
  s.touch(row, 'touchend', 140); s.tap(row);
  assert.equal(s.open(), true); assert.deepEqual(s.sent, [], 'the click that ends a pull is swallowed');
  s.clock(1000); s.tap(row);
  assert.equal(s.sent.length, 1, 'a later tap on a row still pins it');
  // Pushing up or sideways never moves the sheet: that is the list's or the page's own gesture.
  const up = s.pull(row, [300, 280, 200]);
  assert.equal(up.some(e => e.defaultPrevented), false);
  s.close();
});

test('a second finger on a moving sheet puts it back, it is not left halfway down', () => {
  const s = sheet(), head = s.panel.querySelector('.univer-app-add-head');
  s.touch(head, 'touchstart', 100); s.touch(head, 'touchmove', 112); s.touch(head, 'touchmove', 250);
  const two = new s.w.Event('touchstart', { bubbles: true, cancelable: true });
  Object.defineProperty(two, 'touches', { value: [{ clientX: 100, clientY: 250 }, { clientX: 200, clientY: 300 }] });
  head.dispatchEvent(two); s.touch(head, 'touchend', 250);
  assert.equal(s.open(), true); assert.equal(s.panel.style.transform, ''); assert.equal(s.d.querySelector('.univer-app-add-layer').style.backgroundColor, '');
  s.close();
});

test('a sheet reopened right after a flick stays open when the old close timer fires', async () => {
  const s = sheet(true), head = s.panel.querySelector('.univer-app-add-head');
  s.pull(head, [100, 112, 140, 170]);
  s.panel.dispatchEvent(new s.w.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  assert.equal(s.open(), false);
  s.reopen(); assert.equal(s.open(), true);
  await new Promise(resolve => setTimeout(resolve, 300));
  assert.equal(s.open(), true, 'the timer of the closed sheet does not close the new one');
  s.close();
});
