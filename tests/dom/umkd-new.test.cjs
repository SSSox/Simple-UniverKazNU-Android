const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

// Files new since the previous visit to a UMKD course page (univer-folds.js).
// A visit is an app session (sessionStorage mark) or 12 h; each visit below is a new document.
const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');
const SEEN = 'univer.app.umkd-seen.v1', VISIT = 'univer.app.umkd-visit.v1';
const file = id => '<tr class="file"><td></td><td><a class="downLoad" href="/student/umkd/get/' + id + '/321/2026/1">Лекция ' + id + '.pdf</a></td>' +
  '<td>-</td><td>1</td><td>1 Кб</td><td>21.09.2026</td><td></td></tr>';

function visit(ids, {storage, session = false, mark = 'a1', path = '/student/umkd/321/2026/1'} = {}) {
  const dom = new JSDOM('<!doctype html><html><body><div id="l4"><table class="mt univer-app-materials"><tbody><tr><th>Лекции</th></tr>' +
    ids.map(file).join('') + '</tbody></table></div></body></html>', {url: 'https://univer.kaznu.kz' + path, runScripts: 'outside-only'});
  const w = dom.window;
  if (mark) w.localStorage.setItem('univer.app.account.v1', mark);
  if (storage != null) w.localStorage.setItem(SEEN, storage);
  if (session) w.sessionStorage.setItem(VISIT, '1');
  w.eval(read('univer-dom.js')); w.eval(read('univer-folds.js'));
  const marked = () => Array.from(w.document.querySelectorAll('.univer-app-file-new')).map(n => n.previousElementSibling.getAttribute('href').split('/')[4]);
  return {w, d: w.document, folds: w.__univerFoldsV1, marked, stored: () => w.localStorage.getItem(SEEN)};
}

test('UMKD: files added since the previous visit are marked in words; the first visit marks nothing', () => {
  const first = visit([9001, 9002]); first.folds.apply('ru');
  assert.deepEqual(first.marked(), [], 'first visit');
  const s1 = first.stored(); first.w.close();
  assert.ok(!/Лекция|pdf/.test(s1), 'only file ids are kept');
  const next = visit([9001, 9002, 9003], {storage: s1}); next.folds.apply('ru'); next.folds.apply('ru');
  assert.deepEqual(next.marked(), ['9003'], 'one mark after repeated passes');
  const tag = next.d.querySelector('.univer-app-file-new');
  assert.equal(tag.textContent, 'Новый'); assert.equal(tag.title, 'Появился после вашего прошлого захода');
  assert.equal(tag.previousElementSibling.matches('a.downLoad'), true);
  assert.equal(next.d.querySelector('a[href*="/9003/"]').textContent, 'Лекция 9003.pdf', 'the link text stays the file name');
  next.folds.apply('kz'); assert.equal(tag.textContent, 'Жаңа');
  next.folds.apply('en'); assert.equal(tag.textContent, 'New');
  const s2 = next.stored();
  const reload = visit([9001, 9002, 9003], {storage: s2, session: true}); reload.folds.apply('ru');
  assert.deepEqual(reload.marked(), ['9003'], 'a reload within the visit keeps its marks');
  reload.w.close();
  next.folds.release(); assert.deepEqual(next.marked(), []);
  assert.ok(!next.d.body.innerHTML.includes('univer-app-file-new'), 'Original removes the marks');
  next.folds.apply('ru'); assert.deepEqual(next.marked(), ['9003'], 'reading mode brings the same marks back');
  next.w.close();
  const third = visit([9001, 9002, 9003], {storage: s2}); third.folds.apply('ru');
  assert.deepEqual(third.marked(), [], 'the next visit knows them all'); third.w.close();
});

test('UMKD: marks belong to the account and the page; no account mark keeps nothing; a stale visit starts again', () => {
  const first = visit([9001]); first.folds.apply('ru'); const s1 = first.stored(); first.w.close();
  const other = visit([9001, 9002], {storage: s1, mark: 'b2'}); other.folds.apply('ru');
  assert.deepEqual(other.marked(), []); assert.equal(JSON.parse(other.stored()).owner, 'b2'); other.w.close();
  const elsewhere = visit([9001, 9002], {storage: s1, path: '/student/umkd/322/2026/1'}); elsewhere.folds.apply('ru');
  assert.deepEqual(elsewhere.marked(), [], 'another course page has no list yet'); elsewhere.w.close();
  const nomark = visit([9001, 9002], {storage: s1, mark: ''}); nomark.folds.apply('ru');
  assert.deepEqual(nomark.marked(), []); assert.equal(nomark.stored(), s1, 'nothing is written'); nomark.w.close();
  const damaged = visit([9001, 9002], {storage: '{"owner":"a1","pages":[1]}'}); damaged.folds.apply('ru');
  assert.deepEqual(damaged.marked(), []); damaged.w.close();
  const old = JSON.parse(s1); old.at = Date.now() - 13 * 3600000;
  const late = visit([9001, 9002], {storage: JSON.stringify(old), session: true}); late.folds.apply('ru');
  assert.deepEqual(late.marked(), ['9002'], 'a visit older than 12 h moves the base even inside one session'); late.w.close();
});

test('UMKD: the record stays small: ids only, bounded pages and files', () => {
  let storage;
  for (let i = 0; i < 45; i++) {
    const p = visit([9001], {storage, path: '/student/umkd/' + (300 + i) + '/2026/1'}); p.folds.apply('ru'); storage = p.stored(); p.w.close();
  }
  assert.equal(Object.keys(JSON.parse(storage).pages).length, 40);
  const many = visit(Array.from({length: 450}, (_, i) => 10000 + i)); many.folds.apply('ru');
  assert.equal(JSON.parse(many.stored()).pages['/student/umkd/321/2026/1'].cur.length, 400); many.w.close();
});

test('UMKD: repeated passes do not rewrite the record, and a page past the kept 400 files marks nothing', () => {
  const first = visit([9001, 9002]); first.folds.apply('ru'); const s1 = first.stored(); first.w.close();
  const p = visit([9001, 9002], {storage: s1});
  let writes = 0; const set = p.w.localStorage.setItem.bind(p.w.localStorage);
  p.w.Storage.prototype.setItem = function (key, value) { if (key === SEEN) writes++; return set(key, value); };
  p.folds.apply('ru'); p.folds.apply('ru'); p.folds.apply('ru');
  assert.equal(writes, 1, 'written once for the document, not on every pass');
  p.w.close();
  // 450 files: the record keeps the last 400, so the first 50 would read as new on every later visit.
  const ids = Array.from({length: 450}, (_, i) => 10000 + i);
  const big = visit(ids); big.folds.apply('ru'); const s2 = big.stored(); big.w.close();
  const again = visit(ids.concat([20000]), {storage: s2}); again.folds.apply('ru');
  assert.deepEqual(again.marked(), []); again.w.close();
});
