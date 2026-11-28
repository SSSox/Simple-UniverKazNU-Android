const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

// The sealed reauthentication call carries its own deadline and the session the controller checked: a call the renderer
// runs late, or after the page's cookies changed, puts nothing into the dialog.
const SEAL = '0123456789abcdef0123456789abcdef';
const SALT = 'fedcba9876543210fedcba9876543210';
const sealed = fs.readFileSync('app/src/main/assets/univer-automation.js', 'utf8').replace('__UNIVER_AUTOMATION_SEAL__', SEAL);
const kt = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/AutomationController.kt', 'utf8');
const onSiteBody = /fun onSite\(seal: String, call: String\): String \{[\s\S]*?return ((?:"[^"]*"\s*\+?\s*)+)/.exec(kt)[1]
  .match(/"([^"]*)"/g).map(part => part.slice(1, -1)).join('');
const onSite = (seal, call) => onSiteBody.replace('${jsString(SitePolicy.ORIGIN)}', '"https://univer.kaznu.kz"')
  .replace('$seal', seal).replace('$UNPROTECTED', 'unprotected').replace('$call', call);

// The mark AutomationController.cookieMark computes; AutomationSealTest pins the same vector on the Kotlin side.
function mark(salt, name, value) {
  const text = salt + '\n' + name + '=' + value; let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) { hash ^= text.charCodeAt(i); hash = Math.imul(hash, 0x01000193) >>> 0; }
  return hash.toString(16);
}
// AutomationController.handoff for a Cookie header as CookieManager gives it.
function handoff(cookies, deadline) {
  const pairs = cookies.split(';').map(s => s.trim()).filter(Boolean).map(part => {
    const at = part.indexOf('='); return at < 0 ? [part, ''] : [part.slice(0, at), part.slice(at + 1)];
  });
  return `{deadline:${deadline},salt:${JSON.stringify(SALT)},session:${JSON.stringify(pairs.map(([n, v]) => [n, mark(SALT, n, v)]))}}`;
}

const PAGE = '<!doctype html><html><body><p>Dashboard</p><div id="auth_modal"><form id="modal_auth_frm" method="post" action="#">' +
  'Введите ваш логин и пароль (№ <b id="auth_try">1</b>)' +
  '<input readonly name="login" id="modal_auth_login" type="text" value="">' +
  '<input name="password" id="modal_auth_pass" type="password"><input id="modal_auth_ajl_btn" type="submit" value="Войти">' +
  '<input type="hidden" name="key" id="modal_auth_key" value="nonce"></form></div></body></html>';

async function page(cookies, beforeStart) {
  const dom = new JSDOM(PAGE, {url: 'https://univer.kaznu.kz/student/', runScripts: 'outside-only', pretendToBeVisual: true});
  const w = dom.window;
  if (w.document.readyState !== 'complete') await new Promise(resolve => w.addEventListener('load', resolve));
  w.Element.prototype.getClientRects = function () { return [{}]; };
  w.jQuery = function () { return {bind() {}}; };
  w.sent = [];
  w.ajl = function () {};
  w.document.getElementById('modal_auth_frm').addEventListener('submit', event => {
    event.preventDefault();
    w.sent.push(w.document.getElementById('modal_auth_pass').value);
  });
  for (const c of cookies) w.document.cookie = c;
  if (beforeStart) beforeStart(w);
  w.eval(sealed); // Document start: before any site script.
  return w;
}

function call(w, guard) {
  const probe = w.eval('window.__univerAutomationV1.probe()');
  assert.equal(probe.reauthReady, true);
  return w.eval(onSite(SEAL, `reauthenticate(${JSON.stringify(probe.document)},${JSON.stringify(probe.reauthChallenge)},` +
    `"student","s3cret-password",${guard})`));
}

test('the mark matches the Kotlin side', () => {
  assert.equal(mark('0123456789abcdef0123456789abcdef', 'PHPSESSID', 'abc'), 'f9776d75');
});

test('a current call for the checked session is sent', async () => {
  const w = await page(['PHPSESSID=abc', 'lang=ru']);
  assert.equal(call(w, handoff('PHPSESSID=abc; lang=ru; hidden=httponly', Date.now() + 2000)), 'submitted');
  assert.deepEqual(w.sent, ['s3cret-password']);
  w.close();
});

test('a call the renderer runs after its deadline enters nothing, even if the page swapped Date.now', async () => {
  const w = await page(['PHPSESSID=abc']);
  w.eval('Date.now = function () { return 0; }');
  assert.equal(call(w, handoff('PHPSESSID=abc', Date.now() - 1)), 'expired');
  assert.deepEqual(w.sent, []);
  assert.equal(w.document.getElementById('modal_auth_login').value, '', 'not even the login went in');
  w.close();
});

test('a call without a deadline is refused', async () => {
  const w = await page(['PHPSESSID=abc']);
  assert.equal(call(w, 'undefined'), 'expired');
  assert.deepEqual(w.sent, []);
  w.close();
});

test('cookies that changed after the check refuse the call: a new value, a new cookie, a removed one', async () => {
  for (const [checked, now] of [
    ['PHPSESSID=abc', ['PHPSESSID=renewed']],
    ['PHPSESSID=abc', ['PHPSESSID=abc', 'other=1']],
  ]) {
    const w = await page(now);
    assert.equal(call(w, handoff(checked, Date.now() + 2000)), 'session-changed', now.join('; '));
    assert.deepEqual(w.sent, []);
    w.close();
  }
  const w = await page(['PHPSESSID=abc']);
  w.eval('window.__univerAutomationV1.probe()'); // The document has shown the cookie.
  w.document.cookie = 'PHPSESSID=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  assert.equal(call(w, handoff('PHPSESSID=abc', Date.now() + 2000)), 'session-changed');
  assert.deepEqual(w.sent, []);
  w.close();
});

test('a cookie the login\'s events set right before the password goes in refuses the call', async () => {
  const w = await page(['PHPSESSID=abc']);
  w.document.getElementById('modal_auth_login').addEventListener('input', () => { w.document.cookie = 'PHPSESSID=renewed'; });
  assert.equal(call(w, handoff('PHPSESSID=abc', Date.now() + 2000)), 'session-changed');
  assert.deepEqual(w.sent, []);
  assert.equal(w.document.getElementById('modal_auth_pass').value, '');
  w.close();
});

test('a site that patches the built-ins it can reach cannot make a changed cookie pass', async () => {
  const w = await page(['PHPSESSID=renewed']);
  w.eval(`(function () {
    var push = Array.prototype.push, create = Object.create, split = String.prototype.split;
    Array.prototype.push = function () {
      var args = Array.prototype.slice.call(arguments).map(function (v) {
        if (Array.isArray(v) && v[1] === 'renewed') return [v[0], 'abc'];
        return v === 'renewed' ? 'abc' : v;
      });
      return push.apply(this, args);
    };
    Object.create = function () { return {}; };
    String.prototype[Symbol.split] = function () { return ['PHPSESSID=abc']; };
    Object.defineProperty(Array.prototype, '1', {configurable: true, set: function () {}, get: function () { return 'abc'; }});
  })()`);
  const probe = w.eval('window.__univerAutomationV1.probe()');
  const result = w.eval(onSite(SEAL, `reauthenticate(${JSON.stringify(probe.document)},${JSON.stringify(probe.reauthChallenge)},` +
    `"student","s3cret-password",${handoff('PHPSESSID=abc', Date.now() + 2000)})`));
  assert.equal(result, 'session-changed');
  assert.deepEqual(w.sent, []);
  assert.equal(w.document.getElementById('modal_auth_pass').value, '');
  w.close();
});

test('a deadline that passes while the cookies are read refuses the call, at the first and at the final check', async () => {
  for (const stalled of [1, 2]) {
    let clock = 1_000_000, reads = 0, armed = false;
    const w = await page(['PHPSESSID=abc'], w => {
      w.Date.now = () => clock;
      const getter = Object.getOwnPropertyDescriptor(w.Document.prototype, 'cookie').get;
      Object.defineProperty(w.Document.prototype, 'cookie', {configurable: true,
        set: Object.getOwnPropertyDescriptor(w.Document.prototype, 'cookie').set,
        get() { if (armed && ++reads === stalled) clock += 5_000; return getter.call(this); }});
    });
    const probe = w.eval('window.__univerAutomationV1.probe()');
    armed = true;
    const result = w.eval(onSite(SEAL, `reauthenticate(${JSON.stringify(probe.document)},${JSON.stringify(probe.reauthChallenge)},` +
      `"student","s3cret-password",${handoff('PHPSESSID=abc', clock + 2000)})`));
    assert.equal(result, 'expired', `stall at read ${stalled}`);
    assert.equal(reads, stalled, 'refused at the check that stalled');
    assert.deepEqual(w.sent, []);
    assert.equal(w.document.getElementById('modal_auth_pass').value, '');
    w.close();
  }
});
