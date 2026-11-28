const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

// The reauthentication dialog's readonly login is compared with the saved one only through the built-ins the sealed
// automation took at document start: a site script that swaps them later neither changes the answer nor sees the login.
const SEAL = '0123456789abcdef0123456789abcdef';
const sealed = fs.readFileSync('app/src/main/assets/univer-automation.js', 'utf8').replace('__UNIVER_AUTOMATION_SEAL__', SEAL);
const kt = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/AutomationController.kt', 'utf8');
const onSiteBody = /fun onSite\(seal: String, call: String\): String \{[\s\S]*?return ((?:"[^"]*"\s*\+?\s*)+)/.exec(kt)[1]
  .match(/"([^"]*)"/g).map(part => part.slice(1, -1)).join('');
const onSite = (seal, call) => onSiteBody.replace('${jsString(SitePolicy.ORIGIN)}', '"https://univer.kaznu.kz"')
  .replace('$seal', seal).replace('$UNPROTECTED', 'unprotected').replace('$call', call);

const PAGE = '<!doctype html><html><body><p>Dashboard</p><div id="auth_modal"><form id="modal_auth_frm" method="post" action="#">' +
  'Введите ваш логин и пароль (№ <b id="auth_try">1</b>)' +
  '<input readonly name="login" id="modal_auth_login" type="text" value="Bound.User ">' +
  '<input name="password" id="modal_auth_pass" type="password"><input id="modal_auth_ajl_btn" type="submit" value="Войти">' +
  '<input type="hidden" name="key" id="modal_auth_key" value="nonce"></form></div></body></html>';

async function page() {
  const dom = new JSDOM(PAGE, {url: 'https://univer.kaznu.kz/student/', runScripts: 'outside-only', pretendToBeVisual: true});
  const w = dom.window;
  if (w.document.readyState !== 'complete') await new Promise(resolve => w.addEventListener('load', resolve));
  w.Element.prototype.getClientRects = function () { return [{}]; };
  w.jQuery = function () { return {bind() {}}; };
  w.sent = [];
  w.ajl = function () {};
  w.document.getElementById('modal_auth_frm').addEventListener('submit', event => {
    event.preventDefault();
    w.sent.push([w.document.getElementById('modal_auth_login').value, w.document.getElementById('modal_auth_pass').value]);
  });
  w.eval(sealed); // Document start: before any site script.
  return w;
}

function reauthenticate(w, login) {
  const probe = w.eval('window.__univerAutomationV1.probe()');
  assert.equal(probe.reauthReady, true, 'the dialog is ready');
  return w.eval(onSite(SEAL, `reauthenticate(${JSON.stringify(probe.document)},${JSON.stringify(probe.reauthChallenge)},` +
    `${JSON.stringify(login)},"s3cret-password",{deadline:${Date.now() + 2000},salt:"0123456789abcdef0123456789abcdef",session:[]})`));
}

// What a hostile page script does after the document started: every built-in the old comparison went through answers
// for it and records what it is shown.
const SWAP = `
  window.seen = [];
  var input = HTMLInputElement.prototype, value = Object.getOwnPropertyDescriptor(input, 'value');
  Object.defineProperty(input, 'readOnly', {configurable: true, get: function () { seen.push('readOnly'); return false; }});
  Object.defineProperty(input, 'value', {configurable: true, set: value.set,
    get: function () { seen.push('value'); return 'saved.login'; }});
  var trim = String.prototype.trim, lower = String.prototype.toLowerCase;
  String.prototype.trim = function () { seen.push('trim:' + this); return trim.call(this); };
  String.prototype.toLowerCase = function () { seen.push('lower:' + this); return 'same'; };
`;
// jsdom's own value setter converts through the window's String (a browser's native setter does not), so the global
// is swapped only where no password is set.
const SWAP_STRING = `var Text = String; window.String = function (v) { seen.push('String:' + v); return Text(v); };`;

test('swapped readOnly, value, String, trim and toLowerCase cannot make another account\'s dialog take the saved login', async () => {
  const w = await page();
  w.eval(SWAP); w.eval(SWAP_STRING);
  assert.equal(reauthenticate(w, 'Saved.Login'), 'account-mismatch');
  assert.deepEqual(w.sent, [], 'nothing was sent');
  const field = w.document.getElementById('modal_auth_login');
  assert.equal(field.getAttribute('value'), 'Bound.User ', 'the bound account stays in the field');
  assert.ok(!JSON.stringify(w.seen).includes('Saved.Login'), 'no swapped built-in saw the saved login');
  assert.ok(!JSON.stringify(w.seen).includes('s3cret-password'));
  w.close();
});

test('with the built-ins swapped, the bound account itself is still matched case- and space-insensitively and sent', async () => {
  const w = await page();
  w.eval(SWAP);
  assert.equal(reauthenticate(w, ' bound.user'), 'submitted');
  assert.equal(w.sent.length, 1, 'the site\'s own submit handler ran once');
  assert.ok(!JSON.stringify(w.seen).includes('bound.user'), 'the saved login went through no swapped built-in');
  assert.ok(!JSON.stringify(w.seen).includes('s3cret-password'));
  w.close();
});
