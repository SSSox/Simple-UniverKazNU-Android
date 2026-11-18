const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const jsdomUtils = require('jsdom/lib/jsdom/living/generated/utils');

// A click the user made (isTrusted), which a page script cannot dispatch.
function trustedClick(w, node) {
  const event = new w.MouseEvent('click', {bubbles: true, cancelable: true});
  const impl = jsdomUtils.implForWrapper(event); impl.isTrusted = true;
  jsdomUtils.implForWrapper(node)._dispatch(impl);
}

const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const COURSE = '<div id="l4"><table class="mt univer-app-materials"><tbody>' +
  '<tr><th>Лекции</th></tr><tr class="file"><td>1</td><td>Лекция 1</td></tr><tr class="file"><td>2</td><td>Лекция 2</td></tr>' +
  '<tr><th>Практика</th></tr><tr class="file"><td>3</td><td>Задание</td></tr>' +
  '</tbody></table><table><tr><td class="tt"><h4>Силлабус</h4><p><a href="/syllabus/1">Силлабус 2026</a></p></td></tr></table></div>';

function page(path = '/student/umkd/123/2026/1', storage) {
  const dom = new JSDOM('<!doctype html><html><body>' + COURSE + '</body></html>', {url: 'https://univer.kaznu.kz' + path, runScripts: 'outside-only'});
  const w = dom.window;
  if (storage) for (const [k, v] of Object.entries(storage)) w.localStorage.setItem(k, v);
  w.eval(read('univer-dom.js')); w.eval(read('univer-folds.js'));
  const heads = () => Array.from(w.document.querySelectorAll('#l4 th, #l4 h4'));
  return {w, d: w.document, folds: w.__univerFoldsV1, heads};
}

test('every section heading becomes a button that shows how much it holds', () => {
  const p = page(); p.folds.apply();
  const [lectures, practice, syllabus] = p.heads();
  for (const head of [lectures, practice, syllabus]) {
    assert.equal(head.getAttribute('role'), 'button'); assert.equal(head.tabIndex, 0);
    assert.equal(head.getAttribute('aria-expanded'), 'true'); assert.equal(head.dataset.univerFold, 'open');
  }
  assert.equal(lectures.dataset.univerCount, '2'); assert.equal(practice.dataset.univerCount, '1'); assert.equal(syllabus.dataset.univerCount, '1');
  p.w.close();
});

test('a tap folds a section and a second tap opens it; the course opens unfolded the next time', () => {
  const p = page(); p.folds.apply();
  const lectures = p.heads()[0];
  lectures.click();
  assert.equal(lectures.getAttribute('aria-expanded'), 'false');
  assert.equal(p.d.querySelectorAll('[data-univer-folded-row]').length, 2);
  lectures.dispatchEvent(new p.w.KeyboardEvent('keydown', {key: 'Enter', bubbles: true}));
  assert.equal(lectures.dataset.univerFold, 'open', 'Enter works like a tap');
  assert.equal(p.d.querySelectorAll('[data-univer-folded-row]').length, 0);
  lectures.click();
  assert.equal(p.w.localStorage.length, 0, 'nothing about the folds is stored');
  p.w.close();

  // A record left by an earlier version is removed and no longer folds anything.
  const again = page('/student/umkd/123/2026/1', {'univer.mobile.umkd-folds.v1': JSON.stringify({'/student/umkd/123/2026/1': ['Лекции']})});
  again.folds.apply();
  assert.deepEqual(again.heads().map(h => h.dataset.univerFold), ['open', 'open', 'open']);
  assert.equal(again.w.localStorage.getItem('univer.mobile.umkd-folds.v1'), null);
  again.w.close();
});

test('a link inside a heading still opens instead of folding', () => {
  const p = page();
  p.d.querySelector('th').innerHTML = 'Лекции <a href="/x">все</a>';
  p.folds.apply();
  p.d.querySelector('th a').addEventListener('click', e => e.preventDefault());
  p.d.querySelector('th a').click();
  assert.equal(p.heads()[0].dataset.univerFold, 'open');
  p.w.close();
});

test('Original restores headings and rows as they were', () => {
  const p = page(); const before = p.d.body.innerHTML;
  p.folds.apply(); p.heads()[0].click(); p.folds.release();
  assert.equal(p.d.body.innerHTML, before);
  p.heads()[1].click();
  assert.equal(p.d.querySelectorAll('[data-univer-folded-row]').length, 0, 'no handlers are left behind');
  p.w.close();
});

const file = (id, name, size) => '<tr class="file"><td></td><td><a class="downLoad" href="/student/umkd/get/' + id + '/321/2026/1">' + name + '</a></td>' +
  '<td>-</td><td>1</td><td>' + size + '</td><td>21.09.2026</td><td></td></tr>';
const FILES = '<div id="l4"><table class="mt univer-app-materials"><tbody>' +
  '<tr><th id="lectures">Конспекты лекций</th></tr>' + file(9102, 'Лекция 1.pdf', '34,68 Кб') + file(9103, 'Лекция 2.pdf', '0 б') + file(9104, 'Видеозапись лекции', '0 б') +
  '<tr><th id="tasks">Задания для СРО</th></tr>' + file(9105, 'СРО 1.docx', '12 Кб') + file(9106, 'Ссылка на тест', '0 б') +
  '</tbody></table><table><tr><td class="tt"><h4>Силлабус</h4><p><a href="/syllabus/1">Силлабус 2026</a></p><p><a href="/syllabus/2">Әлеуметтану</a></p></td></tr></table></div>';

test('«Скачать все» stands in the heading row of a section with two or more files and sends only its files', () => {
  const dom = new JSDOM('<!doctype html><html><body>' + FILES + '</body></html>', {url: 'https://univer.kaznu.kz/student/umkd/321/2026/1', runScripts: 'outside-only'});
  const w = dom.window, d = w.document, sent = [];
  w.__univerNative = message => sent.push(message);
  w.eval(read('univer-dom.js')); w.eval(read('univer-folds.js'));
  const before = d.body.innerHTML;
  w.__univerFoldsV1.apply('ru'); w.__univerFoldsV1.apply('ru');
  const buttons = d.querySelectorAll('button.univer-app-fold-all'), head = d.getElementById('lectures');
  assert.equal(buttons.length, 1, 'once after repeated passes; a section with one file (and a link) and the syllabus block get none');
  const button = buttons[0];
  assert.equal(button.parentNode, head.parentNode, 'a child of the heading row'); assert.ok(!head.contains(button));
  assert.equal(head.textContent, 'Конспекты лекций', 'the heading text that names the fold and the folder is unchanged');
  assert.equal(button.textContent, ''); assert.equal(button.dataset.label, 'Скачать все'); assert.equal(button.getAttribute('aria-label'), 'Скачать все файлы раздела');
  button.click();
  assert.equal(sent.length, 0, 'a click a page script dispatches starts no downloads');
  trustedClick(w, button);
  assert.equal(head.getAttribute('aria-expanded'), 'true', 'the tap does not fold the section');
  assert.equal(sent.length, 1); assert.ok(sent[0].startsWith('univer-download-all:'));
  assert.deepEqual(JSON.parse(sent[0].slice('univer-download-all:'.length)), {section: 'Конспекты лекций',
    urls: ['https://univer.kaznu.kz/student/umkd/get/9102/321/2026/1', 'https://univer.kaznu.kz/student/umkd/get/9103/321/2026/1']},
    'a zero-size file with its type is a file; the teacher\'s link without a type is left out');
  trustedClick(w, button);
  assert.equal(sent.length, 1, 'a second tap right after the first (a double tap) sends nothing');
  head.click();
  assert.equal(head.getAttribute('aria-expanded'), 'false', 'the heading still folds');
  assert.ok(!button.hasAttribute('data-univer-folded-row'), 'the button stays visible while the section is folded');
  w.__univerFoldsV1.apply('kz');
  assert.equal(button.dataset.label, 'Барлығын жүктеу');
  w.__univerFoldsV1.release();
  assert.equal(d.body.innerHTML, before, 'Original removes the button and restores the page as it was');
  w.close();
});

test('a section with more files than the app takes in one message has no «Скачать все»', () => {
  for (const [count, shown] of [[200, 1], [201, 0]]) {
    const rows = Array.from({length: count}, (_, i) => file(20000 + i, 'Файл ' + i + '.pdf', '12 Кб')).join('');
    const dom = new JSDOM('<!doctype html><html><body><div id="l4"><table class="mt univer-app-materials"><tbody><tr><th>Лекции</th></tr>' + rows + '</tbody></table></div></body></html>',
      {url: 'https://univer.kaznu.kz/student/umkd/321/2026/1', runScripts: 'outside-only'});
    dom.window.eval(read('univer-dom.js')); dom.window.eval(read('univer-folds.js'));
    dom.window.__univerFoldsV1.apply('ru');
    assert.equal(dom.window.document.querySelectorAll('button.univer-app-fold-all').length, shown, count + ' files');
    dom.window.close();
  }
});

// jsdom has no animations or layout: the run, the row's box and its computed values are stand-ins.
test('a tapped fold slides its rows through --univer-slide, turns back from where it is, and Original stops a slide', () => {
  const p = page(); p.d.documentElement.classList.add('univer-mobile'); p.folds.apply();
  const runs = [], real = p.w.getComputedStyle.bind(p.w);
  let slide = '';
  p.w.CSS = {registerProperty() {}};
  p.w.Element.prototype.animate = function (frames, options) { const run = {frames, options, cancelled: false, cancel() { this.cancelled = true; }}; runs.push(run); return run; };
  p.w.getComputedStyle = node => node.matches('tr.file') ? {display: 'flex', paddingTop: '10px', paddingBottom: '4px', marginTop: '0px', marginBottom: '6px',
    borderTopWidth: '0px', borderBottomWidth: '0px', getPropertyValue: name => name === '--univer-slide' ? slide : ''} : real(node);
  p.w.Element.prototype.getBoundingClientRect = function () { return {top: 0, left: 0, width: 360, height: 64}; };
  const lectures = p.heads()[0], rows = Array.from(p.d.querySelectorAll('tr.file')).slice(0, 2);
  const tap = () => lectures.dispatchEvent(new p.w.MouseEvent('click', {bubbles: true, cancelable: true, detail: 1}));
  const ends = run => [run.frames[0]['--univer-slide'], run.frames[1]['--univer-slide']];
  tap();
  assert.equal(runs.length, 2);
  assert.ok(rows.every(r => r.hasAttribute('data-univer-folded-row') && r.hasAttribute('data-univer-sliding')), 'folded from the first frame, on screen while sliding');
  assert.deepEqual(ends(runs[0]), ['1', '0']);
  assert.equal(runs[0].frames[0]['--univer-slide-h'], '64px'); assert.equal(runs[0].frames[1]['--univer-slide-mb'], '6px'); assert.equal(runs[0].options.fill, 'forwards');
  assert.ok(rows.every(r => !r.hasAttribute('style')), 'no inline style is written');
  slide = '0.3'; tap();
  assert.ok(runs[0].cancelled && runs[1].cancelled); assert.deepEqual(ends(runs[2]), ['0.3', '1'], 'the unfold starts where the row was');
  runs[2].onfinish();
  assert.ok(!rows[0].hasAttribute('data-univer-sliding') && runs[2].cancelled, 'a finished run leaves no mark');
  p.folds.release();
  assert.ok(rows.every(r => !r.hasAttribute('data-univer-sliding') && !r.hasAttribute('data-univer-folded-row')) && runs[3].cancelled, 'Original stops a slide and leaves no marks');
  p.w.close();
  const still = page(); still.d.documentElement.classList.add('univer-mobile'); still.w.__univerMotion = false; still.folds.apply();
  let started = 0; still.w.Element.prototype.animate = () => { started++; return {cancel() {}}; };
  still.heads()[0].dispatchEvent(new still.w.MouseEvent('click', {bubbles: true, cancelable: true, detail: 1}));
  assert.equal(started, 0); assert.equal(still.d.querySelectorAll('[data-univer-folded-row]:not([data-univer-sliding])').length, 2, 'with animations off the fold is at once');
  still.w.close();
});

test('syllabus links that sit in a line of their block fold at once instead of sliding', () => {
  const dom = new JSDOM('<!doctype html><html class="univer-mobile"><body><div id="l4"><table><tr><td class="tt"><div><h4>Силлабус</h4><a href="/syllabus/1">Социология</a><a href="/syllabus/2">Әлеуметтану</a></div></td></tr></table></div></body></html>',
    {url: 'https://univer.kaznu.kz/student/umkd/321/2026/1', runScripts: 'outside-only'});
  const w = dom.window, real = w.getComputedStyle.bind(w);
  w.eval(read('univer-dom.js')); w.eval(read('univer-folds.js')); w.__univerFoldsV1.apply('ru');
  w.CSS = {registerProperty() {}};
  let started = 0; w.Element.prototype.animate = () => { started++; return {cancel() {}}; };
  w.Element.prototype.getBoundingClientRect = () => ({top: 0, left: 0, width: 120, height: 34});
  w.getComputedStyle = node => node.tagName === 'A' ? Object.assign(Object.create(real(node)), {display: 'inline-flex'}) : real(node);
  w.document.querySelector('h4').dispatchEvent(new w.MouseEvent('click', {bubbles: true, cancelable: true, detail: 1}));
  assert.equal(started, 0, 'their line keeps its height until they are hidden, so a slide would end with a jump');
  assert.equal(w.document.querySelectorAll('a[data-univer-folded-row]:not([data-univer-sliding])').length, 2);
  w.close();
});

test('other pages are left alone', () => {
  const p = page('/student/umkd/'); p.folds.apply();
  assert.equal(p.heads()[0].hasAttribute('role'), false);
  p.w.close();
});
