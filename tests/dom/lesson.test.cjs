const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

const read = name => fs.readFileSync('app/src/main/assets/' + name, 'utf8');

// The schedule cell's params line in each site language keeps only the room itself.
test('the room is read from the Russian, Kazakh and English schedule', () => {
  const dom = new JSDOM('<body></body>', {url: 'https://univer.kaznu.kz/student/myschedule/', runScripts: 'outside-only'});
  const w = dom.window;
  try {
    w.eval(read('univer-dom.js')); w.eval(read('univer-lesson.js'));
    for (const label of ['Ауд.: 309', 'Аудитория 309', 'Дәрісхана: 309', 'Room 309']) {
      const cell = w.document.createElement('td');
      cell.innerHTML = '<p>Политология</p><p class="params"><span>' + label + '</span></p>';
      const info = w.__univerLesson.read(cell);
      assert.equal(info.room, '309', label);
      assert.equal(info.extra.length, 0, label);
    }
  } finally { w.close(); }
});
