const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const jsdomUtils = require('jsdom/lib/jsdom/living/generated/utils');

// «Скачать все» and home-tile pins act by themselves: they cross only through the keyed channel, the tap that sends them
// is taken through the channel's listener registration (a site script cannot call the handler with a lookalike event),
// what a real tap sends is serialized with the channel's own copy, and the app checks the addresses with the channel's
// own lookup. All of these were taken at document start, before any site script.
const SECRET = '0123456789abcdef0123456789abcdef';
const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const channel = read('univer-channel.js').replace('__UNIVER_CHANNEL_SECRET__', SECRET);
const adapter = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
const NATIVE = /NATIVE = ((?:"[^"]*"\s*\+?\s*)+)/.exec(adapter)[1].match(/"([^"]*)"/g).map(part => part.slice(1, -1)).join('');
const template = /fun withSenders\(body: String, senders: String\) = "(.*)"/.exec(adapter)[1].replace(/\\n/g, '\n');
// ChannelGuard.senders for a WebView without document-start scripts.
const guard = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/ChannelGuard.kt', 'utf8');
const UNKEYED = /else ("\(function\(a,n\)[\s\S]*?NoticeBridge\.CHANNEL\}\)")/.exec(guard)[1]
  .match(/"([^"]*)"/g).map(part => part.slice(1, -1)).join('')
  .replace('${ShortcutBridge.CHANNEL}', '__univerAppEvents').replace('${NoticeBridge.CHANNEL}', '__univerNoticeEvents');
const KEYED = `window.__univerClaim('${SECRET}')`;
// PageScripts.sectionPlaces, the lookup the app evaluates before it fetches anything.
const scripts = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/PageScripts.kt', 'utf8');
const PLACES = /fun sectionPlaces\([\s\S]*?return ((?:"[^"]*"\s*\+?\s*)+)/.exec(scripts)[1]
  .match(/"([^"]*)"/g).map(part => part.slice(1, -1)).join('');
function sectionPlaces(senders, page, targets, mark) {
  return PLACES.replace('$senders', senders).replace('${jsString(page)}', JSON.stringify(page))
    .replace('$list', targets.map(t => JSON.stringify(t)).join(',')).replace('$mark', String(mark));
}
// The message a real tap sent, decoded after the secret and the prefix.
const sentOf = (p, i = 0) => JSON.parse(p.app[i].slice((SECRET + 'univer-download-all:').length));

function trustedClick(w, node) {
  const event = new w.MouseEvent('click', {bubbles: true, cancelable: true});
  const impl = jsdomUtils.implForWrapper(event); impl.isTrusted = true;
  jsdomUtils.implForWrapper(node)._dispatch(impl);
}

const URL_OF = id => 'https://univer.kaznu.kz/student/umkd/get/' + id + '/321/2026/1';
const file = id => '<tr class="file"><td></td><td><a class="downLoad" href="/student/umkd/get/' + id + '/321/2026/1">Лекция ' + id + '.pdf</a></td>' +
  '<td>-</td><td>1</td><td>12 Кб</td><td>21.09.2026</td><td></td></tr>';
const COURSE = '<div id="l4"><table class="mt univer-app-materials"><tbody><tr><th>Лекции</th></tr>' + file(9102) + file(9103) + '</tbody></table></div>';
// Two sections with the same heading: the second one's files are not the first one's.
const TWINS = '<div id="l4"><table class="mt"><tr class="top"><td class="ct"><a href="/student/umkd/">УМКД</a> / <a href="/student/umkd/321/2026/1">Социология</a></td></tr></table>' +
  '<table class="mt univer-app-materials"><tbody><tr><th>Лекции</th></tr>' + file(9102) + file(9103) +
  '<tr><th>Лекции</th></tr>' + file(9202) + file(9203) + '</tbody></table></div>';
const PAGE = 'https://univer.kaznu.kz/student/umkd/321/2026/1';
const MENU = '<details class="univer-app-menu"><summary>Все разделы</summary><div id="tools"><ul><li><a href="/student/rating/">Анкетирование</a></li></ul></div></details>';

/**
 * A Univer page: [keyed] runs the channel at document start, [site] runs as the site's script, then [modules] go in
 * through MobilePageAdapter's wrapper and [start] runs inside it.
 */
function page(keyed, site, options = {}) {
  const {html = COURSE, url = PAGE, modules = ['univer-dom.js', 'univer-folds.js'], start = "window.__univerFoldsV1.apply('ru');"} = options;
  const w = new JSDOM('<!doctype html><html><body>' + html + '</body></html>', {url, runScripts: 'outside-only'}).window;
  const app = [];
  w.__univerAppEvents = {postMessage: value => app.push(value)};
  w.__univerNoticeEvents = {postMessage: () => {}};
  if (keyed) w.eval(channel);
  if (site) w.eval(site);
  const body = modules.map(read).join('\n') + '\n' + start + '\nwindow.direct=__univerNative;return true;';
  assert.equal(w.eval(template.replace('$NATIVE', NATIVE).replace('$body', body).replace('$senders', keyed ? KEYED : UNKEYED)), true);
  return {w, d: w.document, app};
}

const EXPECTED = SECRET + 'univer-download-all:{"section":"Лекции","head":0,"count":1,"mark":1,"urls":["' + URL_OF(9102) + '","' + URL_OF(9103) + '"]}';

test('a swapped JSON.stringify or an inherited toJSON cannot change what a real tap on «Скачать все» sends', () => {
  const p = page(true);
  p.w.eval(`
    var evil = 'https://univer.kaznu.kz/student/umkd/get/1/1/2026/1';
    JSON.stringify = function () { return '{"section":"Лекции","head":0,"urls":["' + evil + '"]}'; };
    Object.prototype.toJSON = function () { return {section: 'Лекции', urls: [evil]}; };
    Array.prototype.toJSON = function () { return [evil]; };
    String.prototype.toJSON = function () { return evil; };
    Object.keys = function () { return ['section']; };
  `);
  trustedClick(p.w, p.d.querySelector('button.univer-app-fold-all'));
  assert.deepEqual(p.app, [EXPECTED]);
  p.w.close();
});

// The site's script runs before the modules: it swaps addEventListener to catch the handler of «Скачать все» and calls
// it with an object that claims to be a trusted click.
const CATCH_HANDLERS = `
  window.caught = [];
  var real = EventTarget.prototype.addEventListener;
  EventTarget.prototype.addEventListener = function (type, handler, options) {
    window.caught.push({target: this, type: type, handler: handler});
    return real.call(this, type, handler, options);
  };
  window.fake = function (target, type) {
    var lookalike = {type: type, isTrusted: true, detail: 1, target: target, currentTarget: target, key: 'Enter', touches: [],
      pointerType: 'touch', isPrimary: true, pointerId: 1, preventDefault: function () {}, stopPropagation: function () {},
      stopImmediatePropagation: function () {}};
    window.caught.forEach(function (entry) {
      if (entry.target === target && entry.type === type) try { entry.handler.call(target, lookalike); } catch (_) {}
    });
  };`;

test('a site script that swapped addEventListener never gets the handler of «Скачать все» to call with a lookalike tap', () => {
  const p = page(true, CATCH_HANDLERS);
  const button = p.d.querySelector('button.univer-app-fold-all');
  assert.ok(button, 'the button is there');
  assert.equal(p.w.eval('window.caught.filter(function (e) { return e.target.tagName === "BUTTON"; }).length'), 0,
    'the button\'s handler went through the channel\'s own registration');
  p.w.fake(button, 'click');
  button.click();
  button.dispatchEvent(new p.w.MouseEvent('click', {bubbles: true}));
  assert.deepEqual(p.app, [], 'neither a lookalike object nor a scripted click sends anything');
  trustedClick(p.w, button);
  assert.deepEqual(p.app, [EXPECTED], 'the student\'s own tap still downloads');
  p.w.close();
});

test('a site script that swapped addEventListener cannot pin a link with a lookalike tap either', () => {
  const pin = SECRET + 'univer-pin:{"pin":true,"title":"Анкетирование","url":"https://univer.kaznu.kz/student/rating/"}';
  const p = page(true, CATCH_HANDLERS + 'JSON.stringify = function () { return \'{"pin":true,"url":"https://evil.example/"}\'; };',
    {html: MENU, url: 'https://univer.kaznu.kz/student/bachelor/', modules: ['univer-dom.js', 'univer-shortcuts.js'], start: "window.__univerShortcutsV1.apply('ru');"});
  const button = p.d.querySelector('#tools .univer-app-pin');
  assert.ok(button, 'the pin button is there');
  p.w.fake(button, 'click');
  button.click();
  assert.deepEqual(p.app, [], 'a lookalike object or a scripted click pins nothing');
  trustedClick(p.w, button);
  assert.deepEqual(p.app, [pin], 'the student\'s own tap pins, serialized with the channel\'s own JSON');
  p.w.close();
});

test('without the keyed channel there is no «Скачать все», and neither it nor a pin reaches the app', () => {
  const p = page(false);
  assert.equal(p.d.querySelectorAll('button.univer-app-fold-all').length, 0, 'no button that would do nothing');
  p.w.direct('univer-download-all:{"section":"Лекции","head":0,"count":1,"mark":1,"urls":["' + URL_OF(9102) + '"]}');
  p.w.direct('univer-pin:{"pin":true,"url":"https://evil.example/"}');
  p.w.direct('univer-grades:{}');
  assert.deepEqual(p.app, ['univer-grades:{}'], 'what the pages report still goes; nothing that acts by itself');
  assert.equal(p.w.eval(sectionPlaces(UNKEYED, PAGE, [URL_OF(9102)], 1)), null, 'and no lookup vouches for an address');
  p.w.close();
});

test('the channel\'s serializer keeps plain data only and is the same whatever the site set up before the modules', () => {
  const p = page(true, 'Object.prototype.toJSON = function () { return "x"; };');
  const json = p.w.eval(`window.__univerClaim('${SECRET}').json`);
  assert.equal(json({a: 'b', n: 1, list: ['c', null, true], skip: function () {}, nan: NaN}), '{"a":"b","n":1,"list":["c",null,true],"nan":null}');
  assert.equal(p.w.eval('window.__univerClaim("guess")'), null);
  trustedClick(p.w, p.d.querySelector('button.univer-app-fold-all'));
  assert.deepEqual(p.app, [EXPECTED]);
  p.w.close();
});

test('two sections with the same heading: a tap sends its own heading number and the lookup keeps them apart', () => {
  const p = page(true, null, {html: TWINS});
  const buttons = p.d.querySelectorAll('button.univer-app-fold-all');
  assert.equal(buttons.length, 2);
  trustedClick(p.w, buttons[1]);
  assert.deepEqual(p.app, [SECRET + 'univer-download-all:{"section":"Лекции","head":1,"count":2,"mark":1,"urls":["' + URL_OF(9202) + '","' + URL_OF(9203) + '"]}']);
  const all = [URL_OF(9102), URL_OF(9202), URL_OF(9203), URL_OF(1)];
  const answer = JSON.parse(p.w.eval(sectionPlaces(KEYED, PAGE, all, 1)));
  assert.deepEqual(Object.keys(answer), ['count', 'files']);
  assert.equal(answer.count, 2);
  assert.deepEqual(Object.keys(answer.files), [URL_OF(9102), URL_OF(9202), URL_OF(9203)], 'an address that is not on the page is not in the answer');
  assert.deepEqual(answer.files[URL_OF(9102)], {course: 'Социология', section: 'Лекции', head: 0, tapped: false});
  assert.deepEqual(answer.files[URL_OF(9202)], {course: 'Социология', section: 'Лекции', head: 1, tapped: true});
  assert.equal(p.w.eval(sectionPlaces(KEYED, PAGE + '?other', all, 1)), null, 'another page answers nothing');
  for (const bad of ['0', '-1', '1.5', '"1"', 'null', 'undefined']) {
    assert.equal(p.w.eval(sectionPlaces(KEYED, PAGE, all, bad)), null, 'no lookup without a mark: ' + bad);
  }
  const unknown = JSON.parse(p.w.eval(sectionPlaces(KEYED, PAGE, all, 99)));
  assert.ok(Object.values(unknown.files).every(place => place.tapped === false), 'a mark the channel never gave names no row');
  p.w.close();
});

test('the lookup answers on a course page of any term and with a query or fragment, and only on a course page of the site', () => {
  const kotlin = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/DownloadBatch.kt', 'utf8');
  const shape = /COURSE_PAGE = Regex\("\^" \+ Regex\.escape\(SitePolicy\.ORIGIN\) \+ """([^"]*)"""\)/.exec(kotlin);
  assert.ok(shape, 'DownloadBatch.coursePage keeps its pattern next to the origin');
  const native = new RegExp('^https://univer\\.kaznu\\.kz' + shape[1]);
  const good = [PAGE, PAGE + '/', 'https://univer.kaznu.kz/student/umkd/321/2026/3', PAGE + '?lang=ru', PAGE + '/?a=1&b=2#files', PAGE + '#top'];
  for (const url of good) {
    const p = page(true, null, {url});
    trustedClick(p.w, p.d.querySelector('button.univer-app-fold-all'));
    assert.equal(p.app.length, 1, 'a tap sends: ' + url);
    const answer = JSON.parse(p.w.eval(sectionPlaces(KEYED, p.d.URL, [URL_OF(9102)], 1)));
    assert.deepEqual(answer.files[URL_OF(9102)], {course: '', section: 'Лекции', head: 0, tapped: true}, url);
    assert.ok(native.test(p.d.URL), 'the app accepts the same page: ' + url);
    p.w.close();
  }
  const bad = ['https://univer.kaznu.kz/student/umkd/', 'https://univer.kaznu.kz/student/umkd/x1/2026/1', 'https://univer.kaznu.kz/student/umkd/321/26/1',
    'https://univer.kaznu.kz/student/umkd/321/2026/1/2', 'https://univer.kaznu.kz/student/umkd/321/2026/', 'https://univer.kaznu.kz/student/attendance/321/2026/1',
    'https://univer.kaznu.kz/student/umkd/get/9102/321/2026/1'];
  for (const url of bad) {
    const p = page(true, null, {url});
    assert.equal(p.w.eval(sectionPlaces(KEYED, p.d.URL, [URL_OF(9102)], 1)), null, 'no answer on ' + url);
    assert.ok(!native.test(p.d.URL), 'nor does the app ask there: ' + url);
    p.w.close();
  }
  // Another origin or plain http is no Univer page for the app either.
  assert.ok(!native.test('https://univer.kaznu.kz.example.org/student/umkd/321/2026/1'));
  assert.ok(!native.test('http://univer.kaznu.kz/student/umkd/321/2026/1'));
});

// The tapped «Лекции» (the second of three headings) is removed after the tap and the files asked for are moved under
// the other «Лекции», which now has the tapped heading's number; a heading added at the end brings the count back to
// three: only the kept identity of the tapped row tells them apart, and the app refuses the batch on it
// (SectionDownloadTest.aNamesakeThatInheritedTheTappedNumberIsNotTheTappedHeading).
const THREE = '<div id="l4"><table class="mt"><tr class="top"><td class="ct"><a href="/student/umkd/">УМКД</a> / <a href="/student/umkd/321/2026/1">Социология</a></td></tr></table>' +
  '<table class="mt univer-app-materials"><tbody><tr><th>Практика</th></tr>' + file(9002) + file(9003) +
  '<tr><th id="tapped">Лекции</th></tr>' + file(9102) + file(9103) +
  '<tr><th id="namesake">Лекции</th></tr>' + file(9202) + file(9203) + '</tbody></table></div>';

test('a namesake heading that took the tapped heading\'s number after it was removed is not the tapped heading', () => {
  const p = page(true, null, {html: THREE});
  const button = p.d.getElementById('tapped').parentNode.querySelector('button.univer-app-fold-all');
  trustedClick(p.w, button);
  const sent = sentOf(p);
  assert.deepEqual([sent.head, sent.count, sent.mark], [1, 3, 1]);
  const before = JSON.parse(p.w.eval(sectionPlaces(KEYED, PAGE, sent.urls, sent.mark)));
  assert.equal(before.count, 3);
  assert.ok(sent.urls.every(url => before.files[url].tapped && before.files[url].head === 1), 'untouched, the tapped row holds its files');
  // The site's script after the tap: the tapped heading goes, the asked files move to the namesake, and a heading comes at the end.
  p.w.eval(`
    var body = document.querySelector('.univer-app-materials tbody'), tapped = document.getElementById('tapped').parentNode;
    var namesake = document.getElementById('namesake').parentNode;
    var moved = [].slice.call(body.querySelectorAll('a.downLoad')).filter(function (a) { return /\\/91\\d\\d\\//.test(a.getAttribute('href')); })
      .map(function (a) { return a.closest('tr'); });
    tapped.remove();
    moved.forEach(function (row) { namesake.parentNode.insertBefore(row, namesake.nextSibling); });
    var extra = document.createElement('tr'); extra.innerHTML = '<th>Новое</th>'; body.appendChild(extra);
  `);
  const after = JSON.parse(p.w.eval(sectionPlaces(KEYED, PAGE, sent.urls, sent.mark)));
  assert.equal(after.count, 3, 'the count is the same as at the tap');
  sent.urls.forEach(url => {
    const place = after.files[url];
    assert.deepEqual([place.section, place.head], ['Лекции', 1], 'number and text are the tapped one\'s');
    assert.equal(place.tapped, false, 'but the row is not the one tapped');
  });
  p.w.close();
});

test('the heading count and number a tap sends come from the channel, however the site changed the page\'s built-ins', () => {
  const p = page(true, null, {html: THREE});
  p.w.eval(`
    Document.prototype.querySelectorAll = function () { return []; };
    Object.defineProperty(HTMLTableRowElement.prototype, 'cells', {get: function () { return {length: 1, 0: {tagName: 'TH'}}; }});
    Object.defineProperty(Node.prototype, 'parentNode', {get: function () { return null; }});
  `);
  const tapped = p.d.getElementById('tapped');
  trustedClick(p.w, tapped.parentElement.querySelector('button.univer-app-fold-all'));
  const sent = sentOf(p);
  assert.deepEqual([sent.head, sent.count], [1, 3], 'counted with the built-ins taken at document start');
  p.w.close();
});

test('a site script that swapped the DOM getters and JSON.stringify cannot make up the lookup\'s answer', () => {
  const p = page(true, null, {html: TWINS});
  const all = [URL_OF(9102), URL_OF(9202), URL_OF(1)];
  const honest = p.w.eval(sectionPlaces(KEYED, PAGE, all, 1));
  p.w.eval(`
    var forged = '{"count":2,"files":{"https://univer.kaznu.kz/student/umkd/get/1/321/2026/1":{"course":"x","section":"Лекции","head":1,"tapped":true}}}';
    JSON.stringify = function () { return forged; };
    Document.prototype.querySelectorAll = function () { return []; };
    Element.prototype.querySelectorAll = function () { return []; };
    Element.prototype.closest = function () { return null; };
    Object.defineProperty(HTMLAnchorElement.prototype, 'href', {get: function () { return 'https://univer.kaznu.kz/student/umkd/get/1/321/2026/1'; }});
    Object.defineProperty(Node.prototype, 'textContent', {get: function () { return 'Лекции'; }});
    Object.defineProperty(NodeList.prototype, 'length', {get: function () { return 0; }});
    Object.defineProperty(HTMLTableRowElement.prototype, 'cells', {get: function () { return {length: 1, 0: {tagName: 'TH'}}; }});
    Object.defineProperty(Element.prototype, 'previousElementSibling', {get: function () { return null; }});
    RegExp.prototype.test = function () { return true; };
    Reflect.apply = function () { return forged; };
    Function.prototype.call = Function.prototype.apply = function () { return forged; };
  `);
  assert.equal(p.w.eval(sectionPlaces(KEYED, PAGE, all, 1)), honest, 'the answer is the page as it is');
  assert.deepEqual(Object.keys(JSON.parse(honest).files), [URL_OF(9102), URL_OF(9202)]);
  // The claim cannot be replaced either: the lookup the app evaluates always reaches the channel's own.
  p.w.eval('try { window.__univerClaim = function () { return {keyed: true, places: function () { return forged; }}; }; } catch (_) {}');
  assert.equal(p.w.eval(sectionPlaces(KEYED, PAGE, all, 1)), honest);
  p.w.close();
});
