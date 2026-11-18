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
  senders.app('univer-pin:{}'); senders.notice('{"g":1}');
  assert.deepEqual(p.app, [SECRET + 'univer-pin:{}']); assert.deepEqual(p.notice, [SECRET + '{"g":1}']);
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
  p.w.eval(`window.__univerClaim('${SECRET}')`).app('univer-pin:{}');
  assert.deepEqual(caught, []); assert.deepEqual(p.app, [SECRET + 'univer-pin:{}']);
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
    (function(){ 'use strict'; window.appTap = function () { __univerNative('univer-download-all:' + JSON.stringify({section:'A', urls:[]})); }; })();
  })(window.__univerClaim('${SECRET}'));`);
  p.w.eval(`
    window.stolen = [];
    var I = String.prototype.indexOf, J = JSON.stringify;
    String.prototype.indexOf = function f() { try { stolen.push(f.caller); } catch (_) {} return I.apply(this, arguments); };
    JSON.stringify = function g(x) { try { stolen.push(g.caller); } catch (_) {} return J(x); };
  `);
  p.w.appTap();
  assert.deepEqual(p.app, [SECRET + 'univer-download-all:{"section":"A","urls":[]}']);
  p.w.stolen.filter(Boolean).forEach(fn => { try { fn('univer-pin:{"pin":true,"url":"https://evil.example/"}'); } catch (_) {} });
  assert.equal(p.app.length, 1, 'nothing the site called sent a keyed message');
  p.w.eval(`(function(__univerSend){${NATIVE} window.direct = __univerNative; })(window.__univerClaim('${SECRET}'));`);
  p.w.direct('univer-other:x'); p.w.direct('univer-pin:{}'); p.w.direct('univer-report:{"v":1}'); p.w.direct('univer-reports:{}');
  p.w.direct('univer-report:' + 'x'.repeat(65536));
  assert.deepEqual(p.app.slice(1), [SECRET + 'univer-pin:{}', SECRET + 'univer-report:{"v":1}'], 'only pin, download-all and bounded report messages pass');
  p.close();
});

test('the install call keeps the secret out of every function the site can reach (review L2, second pass)', () => {
  const template = /fun withSenders\(body: String, senders: String\) = "(.*)"/.exec(kt)[1].replace(/\\n/g, '\n');
  const p = page(), body = "if(!window.__univerMobileV1.ready)return false;\n(function(){'use strict';window.appTap=function(){__univerNative('univer-pin:{}');};})();\nreturn true;";
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
  assert.deepEqual(p.app, [SECRET + 'univer-pin:{}']);
  p.close();
});
