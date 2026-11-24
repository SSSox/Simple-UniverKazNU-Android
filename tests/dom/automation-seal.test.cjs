const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

// The saved account goes only to the automation AutomationController registers at document start with its seal.
const SEAL = '0123456789abcdef0123456789abcdef';
const library = fs.readFileSync('app/src/main/assets/univer-automation.js', 'utf8');
const sealed = library.replace('__UNIVER_AUTOMATION_SEAL__', SEAL);

// The credential call exactly as AutomationController.onSite builds it.
const kt = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/AutomationController.kt', 'utf8');
const onSiteBody = /fun onSite\(seal: String, call: String\): String \{[\s\S]*?return ((?:"[^"]*"\s*\+?\s*)+)/.exec(kt)[1]
  .match(/"([^"]*)"/g).map(part => part.slice(1, -1)).join('');
function onSite(seal, call) {
  return onSiteBody.replace('${jsString(SitePolicy.ORIGIN)}', '"https://univer.kaznu.kz"')
    .replace('$seal', seal).replace('$UNPROTECTED', 'unprotected').replace('$call', call);
}

const LOGIN = '<!doctype html><html><body><form id="login_frm" action="/user/login" method="post">' +
  '<input type="text" name="login"><input type="password" name="password"><input type="submit" name="btn" value="Войти"></form></body></html>';

// [start] runs where the WebView runs document-start scripts: before any site script of the document.
async function page(start) {
  const dom = new JSDOM(LOGIN, {url: 'https://univer.kaznu.kz/user/login', runScripts: 'outside-only', pretendToBeVisual: true});
  const w = dom.window;
  if (w.document.readyState !== 'complete') await new Promise(resolve => w.addEventListener('load', resolve));
  // jsdom has no layout: every element counts as shown. The site's jQuery is a stand-in.
  w.Element.prototype.getClientRects = function () { return [{}]; };
  w.jQuery = function () { return {bind() {}}; };
  const clicks = [], sent = [];
  // The site's submit handler reads the fields as they are when it runs (jQuery 1.2's attr('value') reads the property).
  w.document.getElementById('login_frm').addEventListener('submit', event => {
    event.preventDefault(); clicks.push('submit');
    sent.push([event.target.querySelector('input[type=text]').value, event.target.querySelector('input[type=password]').value]);
  });
  if (start) w.eval(start);
  return {w, d: w.document, clicks, sent, close: () => w.close()};
}

function signIn(p, seal) {
  const token = p.w.eval('window.__univerAutomationV1 && window.__univerAutomationV1.probe().document');
  return p.w.eval(onSite(seal, `login(${JSON.stringify(String(token))},"student","s3cret-password")`));
}

test('the sealed automation fills the real form and sends it through the site\'s own button', async () => {
  const p = await page(sealed);
  assert.equal(signIn(p, SEAL), 'submitted');
  assert.deepEqual(p.sent, [['student', 's3cret-password']], 'the site’s submit handler reads the account');
  assert.deepEqual(p.clicks, ['submit']);
  p.close();
});

test('the password is in the field only while the site’s submit handler runs: no attribute, no event, emptied after', async () => {
  const p = await page(sealed);
  p.w.eval(`
    window.seen = [];
    var field = document.querySelector('input[type=password]');
    ['input', 'change', 'keydown', 'keyup'].forEach(function (type) {
      document.addEventListener(type, function (e) { seen.push(type + ':' + e.target.value); }, true);
      field.addEventListener(type, function (e) { seen.push(type + ':' + e.target.value); });
    });
    new MutationObserver(function (records) {
      records.forEach(function (r) { seen.push('attr:' + r.target.getAttribute(r.attributeName)); });
    }).observe(document, {subtree: true, attributes: true});
  `);
  assert.equal(signIn(p, SEAL), 'submitted');
  await new Promise(resolve => setTimeout(resolve, 0)); // Mutation records arrive in a microtask.
  assert.deepEqual(p.sent, [['student', 's3cret-password']]);
  const field = p.d.querySelector('input[type=password]');
  assert.equal(field.value, '', 'emptied right after the submit handler ran');
  assert.equal(field.getAttribute('value'), null, 'never written into the markup');
  assert.ok(!JSON.stringify(p.w.seen).includes('s3cret-password'), 'no page listener or observer saw it');
  p.close();
});

test('the password is emptied even when the site’s submit handler throws', async () => {
  const p = await page(sealed);
  p.d.getElementById('login_frm').addEventListener('submit', () => { throw new Error('site bug'); });
  p.w.addEventListener('error', event => event.preventDefault());
  try { signIn(p, SEAL); } catch (_) { /* jsdom reports listener errors, it does not rethrow them */ }
  assert.equal(p.d.querySelector('input[type=password]').value, '');
  p.close();
});

test('a site script cannot replace the registered automation or catch the password through swapped built-ins', async () => {
  const p = await page(sealed);
  p.w.eval(`
    window.stolen = [];
    var fake = {seal: '${SEAL}', probe: function () { return {document: 'x'}; }, login: function () { stolen.push([].slice.call(arguments)); return 'submitted'; }};
    try { window.__univerAutomationV1 = fake; } catch (_) {}
    try { Object.defineProperty(window, '__univerAutomationV1', {value: fake}); } catch (_) {}
    try { delete window.__univerAutomationV1; } catch (_) {}
    var set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    Object.defineProperty(HTMLInputElement.prototype, 'value', {configurable: true,
      get: function () { return this.getAttribute('data-v') || ''; }, set: function (v) { stolen.push(v); set.call(this, v); }});
    var attr = Element.prototype.setAttribute;
    Element.prototype.setAttribute = function (k, v) { stolen.push(v); return attr.call(this, k, v); };
    var send = EventTarget.prototype.dispatchEvent;
    EventTarget.prototype.dispatchEvent = function (e) { stolen.push(e && e.target && e.target.value); return send.call(this, e); };
    Function.prototype.call = function () { stolen.push([].slice.call(arguments)); };
    Reflect.apply = function () { stolen.push([].slice.call(arguments)); };
  `);
  assert.equal(p.w.eval('window.__univerAutomationV1.seal'), SEAL, 'still the registered object');
  assert.equal(signIn(p, SEAL), 'submitted');
  assert.ok(!JSON.stringify(p.w.stolen).includes('s3cret-password'), 'no swapped built-in saw the password');
  assert.deepEqual(p.clicks, ['submit']);
  p.close();
});

test('a form a site script passes off through querySelector gets nothing', async () => {
  const p = await page(sealed);
  p.w.eval(`
    var decoy = document.createElement('form'); decoy.id = 'login_frm'; decoy.action = '/user/login';
    decoy.innerHTML = '<input type="text"><input type="password"><input type="submit" name="btn">';
    var find = Document.prototype.querySelector;
    Document.prototype.querySelector = function (s) { return s === 'form#login_frm' ? decoy : find.call(this, s); };
    window.decoy = decoy;
  `);
  assert.equal(signIn(p, SEAL), 'wait');
  assert.equal(p.w.decoy.querySelector('input[type=password]').value, '');
  assert.equal(p.d.querySelector('input[type=password]').value, '');
  p.close();
});

test('without the sealed registration the password is never handed to the page', async () => {
  // The copy evaluated late (no document-start scripts) keeps its placeholder and refuses credentials itself.
  const late = await page(library);
  assert.equal(late.w.eval('window.__univerAutomationV1.seal'), '');
  assert.equal(signIn(late, SEAL), 'unprotected');
  assert.equal(late.w.eval(`window.__univerAutomationV1.login(window.__univerAutomationV1.probe().document, "student", "p")`), 'unprotected');
  assert.equal(late.d.querySelector('input[type=password]').value, '');
  late.close();
  // A site object standing in for the automation, even one that guesses at the seal, is never called.
  const forged = await page(null);
  forged.w.eval(`window.stolen = [];
    window.__univerAutomationV1 = {get seal() { stolen.push('read'); return 'guess'; }, probe: function () { return {document: 'x'}; },
      login: function () { stolen.push([].slice.call(arguments)); return 'submitted'; }};`);
  assert.equal(signIn(forged, SEAL), 'unprotected');
  assert.ok(!JSON.stringify(forged.w.stolen).includes('s3cret-password'));
  assert.ok(!JSON.stringify(forged.w.stolen).includes(SEAL));
  forged.close();
});

test('a login event listener that turns the password field into another input stops the sign-in before the password goes in', async () => {
  for (const event of ['input', 'change']) {
    const p = await page(sealed);
    p.w.eval(`
      window.seen = [];
      var field = document.querySelector('input[type=password]');
      document.querySelector('input[type=text]').addEventListener('${event}', function () { field.type = 'hidden'; });
      new MutationObserver(function (records) {
        records.forEach(function (r) { seen.push(String(r.oldValue) + '>' + r.target.getAttribute(r.attributeName)); });
      }).observe(document, {subtree: true, attributes: true, attributeOldValue: true});
    `);
    assert.equal(signIn(p, SEAL), 'wait', `${event}: not sent`);
    await new Promise(resolve => setTimeout(resolve, 0));
    const field = p.d.querySelector('input[name=password]');
    assert.equal(field.value, '', `${event}: no password assigned`);
    assert.equal(field.getAttribute('value'), null, `${event}: nothing in the markup`);
    assert.deepEqual(p.clicks, [], `${event}: the form was not submitted`);
    assert.ok(!JSON.stringify(p.w.seen).includes('s3cret-password'), `${event}: no mutation record carries it`);
    p.close();
  }
});
