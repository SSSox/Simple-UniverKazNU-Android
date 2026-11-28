const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM, VirtualConsole} = require('jsdom');

const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const heading = name => `<tr class="top"><td class="ct">${name} ([РК1] (100)) 01.09.2026 - 12.12.2026</td></tr>`;
const lesson = (id, type, matrix) => `<tr class="mid"><td class="ts"><a class="lt" href="/student/attendance/show/${id}/2026/1">${type} - Teacher ${id}</a></td></tr>
  <tr><td><table id="matrix${id}" class="inner">${matrix}</table></td></tr>`;
const marks = `<tr><th rowspan="2">РК1</th><th>01.09</th><th></th><th></th><th>Сумма</th></tr>
  <tr><td></td><td>н</td><td class=""></td><td>7</td></tr>
  <tr><th rowspan="2">РК2</th><th></th><th>18.11</th><th></th><th>Сумма</th></tr>
  <tr><td></td><td></td><td>0</td><td>5</td></tr>`;
const html = '<html><body><table class="wrappertable"><tr id="l4"><td class="center"><table class="mt">' +
  heading('Социология') + lesson(1, 'лекция', marks) + lesson(2, 'семинар', marks) +
  '<tr id="lesson-note"><td>Сведения за сентябрь</td></tr>' + lesson(3, 'лекция', marks) +
  heading('Психология') + lesson(4, 'лекция', marks) +
  '<tr><td><a id="next-term" href="/student/attendance/full/2026/2">Следующий семестр</a></td></tr>' +
  '<tr id="journal-footer"><td>Конец журнала</td></tr>' +
  '</table></td></tr></table></body></html>';
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const classLog = '.univer-app-attendance-log';
const toggle = '.univer-app-attendance-details-toggle';
const block = '.univer-app-attendance-log-type';

async function fixture(t) {
  const w = new JSDOM(html, {url: 'https://univer.kaznu.kz/student/attendance/full/2026/1',
    runScripts: 'outside-only', pretendToBeVisual: true, virtualConsole: new VirtualConsole()}).window;
  t.after(() => w.close());
  if (w.document.readyState === 'loading') await new Promise(done => w.document.addEventListener('DOMContentLoaded', done));
  w.CSS = {supports: () => true};
  w.CSSLayerBlockRule = function () {};
  w.matchMedia = () => ({matches: false, addListener() {}, addEventListener() {}});
  w.fetch = async url => ({
    url: new URL(url, w.location.origin).href,
    status: 200,
    text: async () => url.includes('/0/') ?
      '<div id="l4"><table class="inner"><tr class="link" id="77"><td>1</td><td>Социология</td><td>Required</td></tr></table></div>' :
      '<div id="l4"><a href="/student/interactive/syllabus/print/5/1">Силлабус</a></div>',
  });
  return w;
}

test('the mobile adapter groups interleaved lesson types and hides only empty matrix columns, then restores the source', async t => {
  const w = await fixture(t), d = w.document;
  const source = d.querySelector('table.mt'), original = source.innerHTML, matrix = d.getElementById('matrix1');
  const originalCells = Array.from(matrix.rows[1].cells);
  const adapter = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
  JSON.parse('[' + adapter.match(/val SCRIPTS = listOf\(([^)]*)\)/)[1] + ']').forEach(name => w.eval(read(name)));
  w.__univerMobileV1.setEnabled(true, 'ru');
  await wait(300);
  const button = d.querySelector(toggle), log = d.getElementById(button.getAttribute('aria-controls'));
  assert.equal(log.hidden, true);
  assert.equal(log.closest('tr').hidden, true);
  assert.strictEqual(d.getElementById('lesson-note').closest('[hidden]'), log);
  assert.equal(d.getElementById('next-term').closest(classLog), null, 'term navigation remains outside the last lesson');
  assert.equal(d.getElementById('journal-footer').closest(classLog), null);
  button.focus();
  button.click();
  assert.equal(d.activeElement, button);
  assert.equal(button.getAttribute('aria-expanded'), 'true');
  assert.equal(log.hidden, false);
  assert.equal(log.closest('tr').hidden, false);
  assert.deepEqual(Array.from(log.querySelectorAll('h3'), node => node.textContent), ['Лекции', 'Семинары']);
  assert.deepEqual(Array.from(log.querySelectorAll(block), node => Array.from(node.querySelectorAll('table.inner'), table => table.id)),
    [['matrix1', 'matrix3'], ['matrix2']]);
  assert.strictEqual(matrix, d.getElementById('matrix1'), 'the matrix is the original site node');
  const absent = originalCells[1];
  assert.equal(absent.dataset.univerAbsence, 'unexcused');
  assert.equal(absent.getAttribute('aria-label'), 'Пропуски. Без уважительной причины (н)');
  assert.deepEqual(Array.from(matrix.rows, row => Array.from(row.cells, cell => cell.hidden)), [
    [false, false, false, true, false], [false, false, true, false],
    [false, true, false, false, false], [true, false, false, false],
  ]);
  originalCells[2].textContent = '6';
  w.__univerAttendanceV1.apply('ru');
  assert.equal(originalCells[2].hidden, false, 'a late mark brings back its column');
  originalCells[2].textContent = '';
  w.__univerAttendanceV1.apply('ru');
  assert.equal(originalCells[2].hidden, true);

  w.__univerMobileV1.setEnabled(false, 'ru');
  assert.equal(source.innerHTML, original);
  assert.equal(absent.getAttribute('aria-label'), null);
  assert.strictEqual(matrix.rows[1].cells[2], originalCells[2]);
});

test('a mobile scroll box around a standalone source matrix preserves that matrix on a redraw', async t => {
  const w = await fixture(t), d = w.document, center = d.querySelector('#l4 td.center');
  center.innerHTML = '<div class="ct">История 01.09.2026 - 12.12.2026</div>' +
    '<div class="ts"><a class="lt" href="/student/attendance/show/1/2026/1">Лекция - Teacher</a></div>' +
    '<table class="inner">' + marks + '</table>';
  const original = center.innerHTML, matrix = center.querySelector('table.inner');
  w.eval(read('univer-dom.js')); w.eval(read('univer-attendance.js'));
  const attendance = w.__univerAttendanceV1;
  attendance.apply('ru');
  assert.ok(matrix.closest(classLog));
  const scroll = d.createElement('div');
  scroll.className = 'univer-app-table-scroll';
  matrix.before(scroll);
  scroll.append(matrix);
  attendance.apply('en');
  assert.ok(matrix.closest(classLog));
  assert.equal(matrix.parentNode, scroll);
  // The shared table adapter releases its scroll boxes before the journal restores its moves.
  scroll.replaceWith(matrix);
  attendance.release();
  assert.equal(center.innerHTML, original);
});

test('touch animates only the log and leaves an open syllabus and the focused toggle in place', async t => {
  const w = await fixture(t), d = w.document;
  for (const script of ['univer-dom.js', 'univer-study-links.js', 'univer-attendance.js']) w.eval(read(script));
  d.documentElement.classList.add('univer-mobile');
  w.__univerStudyV1.apply('ru');
  w.__univerAttendanceV1.apply('ru');
  const button = d.querySelector(toggle), log = d.querySelector(classLog);
  const syllabus = d.querySelector('.univer-app-course-syllabus');
  syllabus.click();
  await wait(0);
  const panel = d.querySelector('.univer-app-syllabi'), animations = [];
  assert.equal(panel.querySelector('li a .univer-app-syllabus-title').textContent, 'Силлабус');
  assert.equal(panel.hidden, false);
  log.firstElementChild.getBoundingClientRect = () => ({height: 480});
  log.getBoundingClientRect = () => ({height: 320});
  log.animate = frames => {
    const animation = {frames: Array.from(frames, frame => frame.height), cancel() { this.oncancel?.(); }};
    animations.push(animation);
    return animation;
  };
  const tap = () => button.dispatchEvent(new w.MouseEvent('click', {detail: 1, bubbles: true}));
  button.focus();
  tap();
  assert.equal(log.hidden, false);
  assert.deepEqual(animations[0].frames, ['0px', '480px']);
  animations[0].onfinish();
  tap();
  assert.equal(log.hidden, false, 'the marks remain painted while the log shrinks');
  assert.equal(log.getAttribute('aria-hidden'), 'true');
  assert.equal(log.hasAttribute('inert'), true);
  assert.deepEqual(animations[1].frames, ['480px', '0px']);
  tap();
  assert.deepEqual(animations[2].frames, ['320px', '480px'], 'reversing a close uses the current height');
  animations[1].onfinish();
  assert.equal(log.hidden, false, 'an interrupted animation cannot hide a reopened log');
  animations[2].onfinish();
  tap();
  animations[3].onfinish();
  assert.equal(log.hidden, true, 'the finished close takes the entire details region out of layout');
  assert.equal(log.closest('tr').hidden, true, 'the table row leaves no spacer after the close');
  assert.equal(button.getAttribute('aria-expanded'), 'false');
  assert.equal(d.activeElement, button);
  assert.strictEqual(d.querySelector('.univer-app-syllabi'), panel);
  assert.equal(panel.hidden, false);
  assert.equal(syllabus.getAttribute('aria-expanded'), 'true');

  w.matchMedia = () => ({matches: true});
  tap();
  assert.equal(log.hidden, false);
  tap();
  assert.equal(log.hidden, true);
  assert.equal(animations.length, 4, 'reduced motion takes effect for opening and closing');
});

test('a single lesson page without a course heading keeps its marks in the log', async t => {
  const w = await fixture(t), d = w.document, table = d.querySelector('table.mt');
  table.innerHTML = lesson(1, 'Лекция', marks);
  const original = table.innerHTML;
  w.CSS = {supports: () => true};
  for (const script of ['univer-dom.js', 'univer-attendance.js']) w.eval(read(script));
  d.documentElement.classList.add('univer-mobile');
  w.__univerAttendanceV1.apply('ru');
  const matrix = d.getElementById('matrix1');
  assert.ok(matrix, 'the marks stay in the document');
  assert.ok(matrix.closest(classLog));
  assert.equal(matrix.closest('[hidden]'), null);
  w.__univerAttendanceV1.release();
  assert.equal(table.innerHTML, original);
});
