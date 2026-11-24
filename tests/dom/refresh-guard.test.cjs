const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');

// Pull to refresh asks the page first: typed text and an open window of the site keep the page, the app's own
// searches and switches do not.
const kotlin = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/PageScripts.kt', 'utf8');
const GUARD = /val REFRESH_GUARD = """([\s\S]*?)"""/.exec(kotlin)[1].replace(/\$\{SitePolicy\.ORIGIN\}/g, 'https://univer.kaznu.kz');

function page(body, url = 'https://univer.kaznu.kz/student/myschedule/') {
  const jsdom = new JSDOM('<html class="univer-app-mobile"><body class="univer-app-page">' + body + '</body></html>',
    { url, runScripts: 'outside-only', virtualConsole: new VirtualConsole() });
  // jsdom has no layout: a box is drawn unless it or a parent is display:none.
  jsdom.window.Element.prototype.getClientRects = function () {
    for (let e = this; e; e = e.parentElement) if (e.style && e.style.display === 'none') return [];
    return [{}];
  };
  return jsdom.window;
}
const ask = w => w.eval(GUARD);

test('a page as it came is loaded again', () => {
  const w = page('<form><input name="q" value="site"><select><option>1</option><option selected>2</option></select>' +
    '<select id="plain"><option>1</option><option>2</option></select>' +
    '<select id="lead-disabled"><option disabled>--</option><option>A</option><option>B</option></select>' +
    '<select id="group-disabled"><optgroup disabled><option>x</option></optgroup><option>y</option></select>' +
    '<input type="checkbox" checked><input type="hidden" name="k" value="1"></form>');
  assert.equal(ask(w), 'ok');
  w.document.querySelector('[type=hidden]').value = 'changed by the site';
  assert.equal(ask(w), 'ok', 'a hidden field is the site\'s own');
});

test('single and multiple selects compare against the effective default of the original markup', () => {
  // Single select without a selected attribute defaults to the first enabled option.
  let w = page('<select><option>1</option><option>2</option></select>');
  assert.equal(ask(w), 'ok', 'untouched single select without selected attribute is not edited');
  w.document.querySelector('select').selectedIndex = 1;
  assert.equal(ask(w), 'edited');
  w.document.querySelector('select').selectedIndex = 0;
  assert.equal(ask(w), 'ok', 'returning to the implicit default selection is ok again');

  // Leading disabled option or disabled optgroup is skipped for the effective default.
  w = page('<select><option disabled>Choose</option><option>A</option><option>B</option></select>');
  assert.equal(ask(w), 'ok');
  w.document.querySelector('select').selectedIndex = 2;
  assert.equal(ask(w), 'edited');

  w = page('<select><optgroup disabled><option>X</option></optgroup><option>Y</option><option>Z</option></select>');
  assert.equal(ask(w), 'ok');
  w.document.querySelector('select').selectedIndex = 2;
  assert.equal(ask(w), 'edited');

  // Empty select, all-disabled select, size > 1 without selected, disabled selected, multiple selected attributes.
  assert.equal(ask(page('<select></select>')), 'ok');
  assert.equal(ask(page('<select><option disabled>1</option><option disabled>2</option></select>')), 'ok');
  w = page('<select size="3"><option>1</option><option>2</option></select>');
  assert.equal(ask(w), 'ok', 'listbox single select without selected defaults to none');
  w.document.querySelector('select').selectedIndex = 0;
  assert.equal(ask(w), 'edited');

  w = page('<select><option disabled selected>Choose</option><option>A</option></select>');
  assert.equal(ask(w), 'ok', 'explicit selected on a disabled option is still the default');
  w.document.querySelector('select').selectedIndex = 1;
  assert.equal(ask(w), 'edited');

  w = page('<select><option selected>1</option><option selected>2</option></select>');
  assert.equal(ask(w), 'ok', 'last selected attribute is the effective default in single select');
  w.document.querySelector('select').selectedIndex = 0;
  assert.equal(ask(w), 'edited');

  // Multi-select has no implicit default selection.
  w = page('<select multiple><option>1</option><option selected>2</option></select>');
  assert.equal(ask(w), 'ok');
  w.document.querySelectorAll('option')[0].selected = true;
  assert.equal(ask(w), 'edited');
});

test('a draft field still answers edited when evaluated after a delay', async () => {
  const w = page('<form><textarea name="reply"></textarea></form>');
  w.document.querySelector('textarea').value = 'черновик ответа';
  const delayed = await new Promise(resolve => setTimeout(() => resolve(ask(w)), 10));
  assert.equal(delayed, 'edited');
});

test('what the student typed or chose in a form of the site keeps the page', () => {
  let w = page('<form><input name="q"></form>');
  w.document.querySelector('input').value = 'черновик';
  assert.equal(ask(w), 'edited');
  w = page('<div class="univer-app-card"><textarea></textarea></div>');
  w.document.querySelector('textarea').value = 'ответ';
  assert.equal(ask(w), 'edited', 'a site field inside a box the layout drew is still the site\'s');
  w = page('<select><option>1</option><option>2</option></select>');
  w.document.querySelector('select').selectedIndex = 1;
  assert.equal(ask(w), 'edited');
  w = page('<input type="radio" name="r" value="a"><input type="radio" name="r" value="b">');
  w.document.querySelectorAll('input')[1].checked = true;
  assert.equal(ask(w), 'edited');
  w = page('<input name="q" readonly><input name="d" disabled>');
  w.document.querySelectorAll('input').forEach(input => { input.value = 'set by the site'; });
  assert.equal(ask(w), 'ok', 'read-only and disabled fields are not the student\'s');
});

test('the app\'s own searches and switches do not hold the page', () => {
  const w = page('<input type="search" class="univer-app-news-query">' +
    '<label class="univer-app-schedule-mode"><input type="checkbox"><span>Подробно</span></label>');
  w.document.querySelector('.univer-app-news-query').value = 'стипендия';
  w.document.querySelector('[type=checkbox]').checked = true;
  assert.equal(ask(w), 'ok');
});

test('an open window of the site keeps the page; a closed one does not', () => {
  let w = page('<div id="simplemodal-container"><p>Окно</p></div>');
  assert.equal(ask(w), 'dialog');
  w = page('<div id="auth_modal" class="modal" style="display:none"><div role="dialog"></div></div>');
  assert.equal(ask(w), 'ok');
  w = page('<div class="modal show" role="dialog"></div>');
  assert.equal(ask(w), 'dialog');
});

test('another origin answers ok without reading its fields', () => {
  const w = page('<input>', 'https://example.com/');
  w.document.querySelector('input').value = 'x';
  assert.equal(ask(w), 'ok');
});
