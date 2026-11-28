const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

const SECRET = '0123456789abcdef0123456789abcdef';
const source = fs.readFileSync('app/src/main/assets/univer-channel.js', 'utf8').replace('__UNIVER_CHANNEL_SECRET__', SECRET);

// The WebView injects the listener objects, then runs the document-start key before any site script.
function page(url = 'https://univer.kaznu.kz/student/myschedule/') {
  const w = new JSDOM('', {url, runScripts: 'outside-only'}).window, app = [], notice = [];
  w.__univerAppEvents = {postMessage: value => app.push(value)};
  w.__univerNoticeEvents = {postMessage: value => notice.push(value)};
  w.eval(source);
  return {w, app, notice, close: () => w.close()};
}

test('only a caller that knows the secret gets the senders, and every message they send carries it', () => {
  const p = page();
  assert.equal(p.w.eval('window.__univerClaim("guess")'), null);
  const senders = p.w.eval(`window.__univerClaim('${SECRET}')`);
  senders.app('univer-grades:{"v":1}'); senders.notice('{"g":1}');
  senders.app('univer-pin:{"pin":true,"url":"https://evil.example/"}');
  senders.post('univer-pin:', {pin: true, url: 'https://univer.kaznu.kz/student/rating/'});
  assert.deepEqual(p.app, [SECRET + 'univer-grades:{"v":1}', SECRET + 'univer-pin:{"pin":true,"url":"https://univer.kaznu.kz/student/rating/"}']);
  assert.deepEqual(p.notice, [SECRET + '{"g":1}']);
  p.close();
});

test('a site script cannot replace the claim, read the secret, or catch the app\'s messages by hooking the channel', () => {
  const p = page(), caught = [];
  p.w.caught = caught;
  p.w.eval(`
    try { Object.defineProperty(window, '__univerClaim', {value: function () { return 'forged'; }}); } catch (_) {}
    window.__univerClaim = function () { return 'forged'; };
    window.__univerAppEvents.postMessage = function (value) { caught.push(value); };
    Reflect.apply = function () { caught.push('apply'); };
  `);
  assert.equal(p.w.eval('window.__univerClaim("x")'), null, 'the claim is the app\'s own');
  assert.ok(!p.w.eval('String(window.__univerClaim)').includes(SECRET), 'the function text does not hold the secret');
  p.w.eval(`window.__univerClaim('${SECRET}')`).app('univer-grades:{"v":1}');
  assert.deepEqual(caught, []); assert.deepEqual(p.app, [SECRET + 'univer-grades:{"v":1}']);
  // What the site posts itself has no key, and the app drops it (ChannelGuard.unwrap).
  p.w.eval('window.__univerNoticeEvents.postMessage("{}")');
  assert.ok(!p.notice.at(-1).startsWith(SECRET));
  p.close();
});

test('other origins get no claim', () => {
  const p = page('https://example.com/');
  assert.equal(p.w.__univerClaim, undefined);
  p.close();
});

// The modules' wrapper exactly as MobilePageAdapter.withSenders builds it.
const kt = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
const NATIVE = /NATIVE = ((?:"[^"]*"\s*\+?\s*)+)/.exec(kt)[1].match(/"([^"]*)"/g).map(part => part.slice(1, -1)).join('');

test('a built-in swapped by the site cannot hand it the modules\' sender through .caller (review L2 bypass)', () => {
  const p = page();
  p.w.eval(`(function(__univerSend){${NATIVE}
    (function(){ 'use strict'; window.appTap = function () { __univerPost('univer-download-all:', {section:'A', urls:[], head:0, count:1, mark:1}); }; })();
  })(window.__univerClaim('${SECRET}'));`);
  p.w.eval(`
    window.stolen = [];
    var I = String.prototype.indexOf, J = JSON.stringify;
    String.prototype.indexOf = function f() { try { stolen.push(f.caller); } catch (_) {} return I.apply(this, arguments); };
    JSON.stringify = function g(x) { try { stolen.push(g.caller); } catch (_) {} return J(x); };
  `);
  p.w.appTap();
  assert.deepEqual(p.app, [SECRET + 'univer-download-all:{"section":"A","urls":[],"head":0,"count":1,"mark":1}']);
  p.w.stolen.filter(Boolean).forEach(fn => { try { fn('univer-pin:{"pin":true,"url":"https://evil.example/"}'); } catch (_) {} });
  assert.equal(p.app.length, 1, 'nothing the site called sent a keyed message');
  p.w.eval(`(function(__univerSend){${NATIVE} window.direct = __univerNative; })(window.__univerClaim('${SECRET}'));`);
  p.w.direct('univer-other:x'); p.w.direct('univer-pin:{}'); p.w.direct('univer-download-all:{}');
  p.w.direct('univer-report:{"v":1}'); p.w.direct('univer-calendar:{}'); p.w.direct('univer-export:{}');
  p.w.direct('univer-reports:{}');
  p.w.direct('univer-report:' + 'x'.repeat(65536));
  p.w.direct('univer-schedule:{"v":1}'); p.w.direct('univer-grades:{"v":1}'); p.w.direct('univer-exams:{"v":1}');
  assert.deepEqual(p.app.slice(1), [SECRET + 'univer-schedule:{"v":1}', SECRET + 'univer-grades:{"v":1}', SECRET + 'univer-exams:{"v":1}'], 'a raw string keys only the week, the totals and the exams');
  p.close();
});

test('the install call keeps the secret out of every function the site can reach (review L2, second pass)', () => {
  const template = /fun withSenders\(body: String, senders: String\) = "(.*)"/.exec(kt)[1].replace(/\\n/g, '\n');
  const p = page(), body = "if(!window.__univerMobileV1.ready)return false;\n(function(){'use strict';window.appTap=function(){__univerNative('univer-grades:{\"v\":1}');};})();\nreturn true;";
  p.w.eval(`window.taken = []; Object.defineProperty(window, '__univerMobileV1', {get: function g() {
    try { taken.push(g.caller); } catch (_) {} try { taken.push(arguments.callee.caller); } catch (_) {}
    try { taken.push(g.caller.arguments[0]); } catch (_) {}
    try { var e = {}; Error.captureStackTrace(e); taken.push(e.stack); } catch (_) {}
    return {ready: true}; }});`);
  const source = template.replace('$NATIVE', NATIVE).replace('$body', body).replace('${senders}', '').replace('$senders', `window.__univerClaim('${SECRET}')`);
  assert.equal(p.w.eval(source), true);
  const text = p.w.taken.filter(Boolean).map(x => typeof x === 'function' ? Function.prototype.toString.call(x) : String(x)).join('\n');
  assert.ok(!text.includes(SECRET), 'no reachable function or stack shows the secret');
  p.w.taken.filter(x => typeof x === 'function').forEach(fn => { try { fn(); } catch (_) {} });
  p.w.taken.filter(x => x && typeof x.app === 'function').forEach(s => s.app('univer-pin:{"url":"https://evil.example/"}'));
  p.w.appTap();
  assert.deepEqual(p.app, [SECRET + 'univer-grades:{"v":1}']);
  p.close();
});

test('a calendar value cannot be keyed as a pin, and replacing the channel does not reveal the secret', () => {
  const p = page();
  p.w.eval(`(function(__univerSend){${NATIVE} window.post = __univerPost; window.raw = __univerNative; window.pipe = __univerSend.app; })(window.__univerClaim('${SECRET}'));`);
  const calendar = {v:1, title:'Экзамен', start:'2026-12-01T09:00', end:'2026-12-01T11:00', place:'309', notes:'note'};
  p.w.post('univer-calendar:', calendar);
  p.w.post('univer-calendar:', {pin:true, url:'https://evil.example/', order:['https://evil.example/']});
  p.w.post('univer-pin:', calendar);
  p.w.post('univer-calendar:', 'univer-pin:{"pin":true,"url":"https://evil.example/"}');
  p.w.raw('univer-pin:{"pin":true,"url":"https://evil.example/"}');
  p.w.raw('univer-calendar:' + JSON.stringify(calendar));
  p.w.pipe('univer-export:{"v":0,"kind":"ics"}');
  const stolen = [];
  const real = p.w.__univerAppEvents;
  p.w.__univerAppEvents = {postMessage: value => { stolen.push(value); real.postMessage(value); }};
  p.w.post('univer-pin:', {pin:true, url:'https://univer.kaznu.kz/student/rating/'});
  assert.equal(stolen.length, 0, 'the page\'s replacement never receives the keyed text');
  assert.equal(p.app.length, 2);
  assert.ok(p.app[0].startsWith(SECRET + 'univer-calendar:'), p.app[0]);
  assert.equal(JSON.parse(p.app[0].slice((SECRET + 'univer-calendar:').length)).title, 'Экзамен');
  assert.ok(!p.app[0].slice(SECRET.length).startsWith('univer-pin:'));
  assert.equal(p.app[1], SECRET + 'univer-pin:{"pin":true,"url":"https://univer.kaznu.kz/student/rating/"}');
  p.close();
});

test('without the keyed channel every tap is dropped and reports still pass', () => {
  const guard = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/ChannelGuard.kt', 'utf8');
  const unkeyed = /else ("\(function\(a,n\)[\s\S]*?NoticeBridge\.CHANNEL\}\)")/.exec(guard)[1]
    .match(/"([^"]*)"/g).map(part => part.slice(1, -1)).join('')
    .replace('${ShortcutBridge.CHANNEL}', '__univerAppEvents').replace('${NoticeBridge.CHANNEL}', '__univerNoticeEvents');
  const p = page();
  p.w.eval(`(function(__univerSend){${NATIVE} window.direct = __univerNative; window.post = __univerPost; })(${unkeyed});`);
  ['univer-pin:{}', 'univer-download-all:{}', 'univer-report:{}', 'univer-calendar:{}', 'univer-export:{}'].forEach(message => p.w.direct(message));
  p.w.post('univer-calendar:', {v:1, title:'Экзамен', start:'2026-12-01T09:00', end:'2026-12-01T11:00', place:'', notes:''});
  p.w.post('univer-pin:', {pin:true, url:'https://evil.example/'});
  p.w.direct('univer-schedule:{"v":1}');
  p.w.direct('univer-grades:{"v":1}');
  p.w.direct('univer-exams:{"v":1}');
  p.w.post('univer-schedule:', {v:1, week:'2026-11-16', lessons:[]});
  assert.deepEqual(p.app, [
    'univer-schedule:{"v":1}',
    'univer-grades:{"v":1}',
    'univer-exams:{"v":1}',
    'univer-schedule:{"v":1,"week":"2026-11-16","lessons":[]}',
  ]);
  p.close();
});
