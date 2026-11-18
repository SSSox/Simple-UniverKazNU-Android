const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');

// Phone screens (assets/univer-screens.js) on synthetic pages shaped like the observed Univer markup. Names and dates are made up.
const dom = fs.readFileSync('app/src/main/assets/univer-dom.js', 'utf8');
const source = fs.readFileSync('app/src/main/assets/univer-screens.js', 'utf8');

function page(path, body, { capable = true, language = 'ru', size } = {}) {
  const html = '<table class="wrappertable"><tr id="l4"><td>' + body + '</td></tr></table>';
  const jsdom = new JSDOM(html, { url: 'https://univer.kaznu.kz' + path, runScripts: 'outside-only', virtualConsole: new VirtualConsole() });
  const w = jsdom.window, d = w.document;
  Object.defineProperty(d, 'readyState', { value: 'complete', configurable: true });
  d.documentElement.className = 'univer-mobile';
  w.CSS = { supports: () => capable };
  w.CSSLayerBlockRule = function () {};
  // jsdom has no layout: a folded memo reports the height a long text would have, and every box is on screen.
  if (size) {
    w.Element.prototype.getClientRects = () => [{}];
    Object.defineProperty(w.HTMLElement.prototype, 'scrollHeight', { configurable: true, get() { return this.classList.contains('univer-scr-clamp') ? size : 0; } });
    Object.defineProperty(w.HTMLElement.prototype, 'clientHeight', { configurable: true, get() { return this.classList.contains('univer-scr-clamp') ? 160 : 0; } });
  }
  w.eval(dom);
  w.eval(source);
  const screens = w.__univerScreensV1;
  return { w, d, q: s => d.querySelector(s), all: s => Array.from(d.querySelectorAll(s)), apply: () => screens.apply(language), release: () => screens.release(), close: () => w.close() };
}
const tick = () => new Promise(resolve => setTimeout(resolve, 5));
function stamp(days, hours) {
  const at = new Date(); at.setDate(at.getDate() + days); at.setHours(hours, 0, 0, 0);
  const two = n => String(n).padStart(2, '0');
  return `${two(at.getDate())}.${two(at.getMonth() + 1)}.${at.getFullYear()} ${two(at.getHours())}:00`;
}
function slot(when, name, kind, extra = '', last = '&nbsp;') {
  return `<tr><th colspan="7" class="last timerTicked">${when}${/Экзамен/.test(kind) ? ' <br><b>До начало экзамена </b> <span data-timer="1">Осталось 3 дня</span>' : ''}</th></tr>` +
    `<tr class="link" id="${when}:00"><td>${name} </td><td>Тестов А. А.</td><td>${kind}</td><td>Корп: ФИТ | Ауд.: Онлайн</td><td>сем</td><td>${extra}</td><td>${last}</td></tr>`;
}
const headings = '<table class="inner"><tr><th>Название дисциплины</th><th>Преподаватель</th><th>Тип</th><th>Аудитория факультета</th><th>Тип группы</th><th>Ссылки</th><th>&nbsp;</th></tr></table>';
function schedule(rows) {
  return '<table class="mt"><tr class="top"><td class="ct">Инструкция</td></tr><tr class="mid"><td class="ct">' +
    '<a href="/Content/instructions/a.pdf">Инструкция A</a><a href="/Content/instructions/b.pdf">Инструкция B</a></td></tr><tr class="bot"><td class="ct"></td></tr></table>' +
    '<table class="mt"><tr class="top"><td class="ct">Расписание экзаменов</td></tr><tr class="mid"><td class="ts"><a href="/student/myexam/schedule/2025/2">Прошлый семестр</a></td></tr>' +
    '<tr class="mid"><td class="tt" id="host">' + headings + '<div class="inner"><table class="inner" id="scheduleList">' + rows + '</table></div></td></tr></table>';
}

test('the exam timetable becomes exam days with the next exam first, and release brings the site table back', () => {
  const rows = slot(stamp(-2, 10), 'Прошедшая дисциплина', 'Экзамен <i>(Письменный)</i>') +
    slot(stamp(3, 9), 'Первая дисциплина', 'Консультация') +
    slot(stamp(3, 11), 'Первая дисциплина', 'Экзамен <i>(Тестовый, ИС Univer)</i>', '<a href="https://meet.example.org/room">Встреча</a>',
      '<a href="#" id="take" class="take-exam activeExam"></a>') +
    slot(stamp(10, 13), 'Вторая дисциплина', 'Экзамен', '', '<a href="#" class="take-exam unactiveExam"></a>');
  const p = page('/student/myexam/schedule/', schedule(rows));
  const before = p.q('#host').innerHTML;
  p.apply();
  const view = p.q('#host>.univer-scr-exams');
  // Only the heading table and the list's box are hidden; the view takes their place in the same cell.
  assert.ok(view && view.nextElementSibling === p.q('#host>table.inner'));
  assert.ok(p.q('#host>table.inner').classList.contains('univer-scr-source') && p.q('#host>div.inner').classList.contains('univer-scr-source'));
  assert.equal(p.q('#host').className, 'tt');
  assert.ok(p.d.documentElement.classList.contains('univer-scr') && p.d.documentElement.classList.contains('univer-scr-page-exams'));
  assert.equal(p.q('.univer-scr-next-name').textContent, 'Первая дисциплина');
  assert.equal(p.q('.univer-scr-eyebrow').textContent, 'Ближайший экзамен');
  assert.equal(p.q('.univer-scr-count-value').textContent, '3');
  assert.equal(p.q('.univer-scr-count-unit').textContent, 'дня');
  const days = p.all('.univer-scr-day');
  assert.equal(days.length, 3);
  assert.equal(days[0].dataset.state, 'past');
  assert.equal(days[1].querySelector('.univer-scr-day-rel').textContent, 'через 3 дня');
  assert.deepEqual(Array.from(days[1].querySelectorAll('.univer-scr-kind')).map(k => k.dataset.kind), ['consult', 'exam']);
  assert.equal(days[1].querySelectorAll('.univer-scr-slot')[1].querySelector('.univer-scr-slot-note').textContent, 'Тестовый, ИС Univer');
  assert.equal(p.q('.univer-scr-where').textContent, 'ФИТ · Онлайн');
  assert.equal(p.q('a.univer-scr-link').href, 'https://meet.example.org/room');
  // Only an open exam gets a start button, and it runs the site's own link.
  const starts = p.all('button.univer-scr-start');
  assert.equal(starts.length, 1);
  assert.equal(starts[0].textContent, 'Начать экзамен');
  let started = 0; p.q('#take').addEventListener('click', () => started++);
  starts[0].click();
  assert.equal(started, 1);
  // A list the site re-rendered still starts the exam through its new link, and a closed one is not clicked.
  const row = p.q('#take').closest('tr');
  row.querySelector('td:last-child').innerHTML = '<a href="#" id="take2" class="take-exam activeExam"></a>';
  let restarted = 0; p.q('#take2').addEventListener('click', () => restarted++);
  starts[0].click();
  assert.equal(restarted, 1);
  p.q('#take2').className = 'take-exam unactiveExam';
  starts[0].click();
  assert.equal(restarted, 1);
  p.q('#take2').className = 'take-exam activeExam';
  // The instructions fold under their heading with a file count.
  const fold = p.q('button.univer-scr-fold');
  assert.equal(fold.getAttribute('aria-expanded'), 'false');
  assert.equal(fold.querySelector('.univer-scr-fold-count').textContent, '2 файла');
  assert.ok(fold.closest('table').classList.contains('univer-scr-folded'));
  fold.click();
  assert.ok(!fold.closest('table').classList.contains('univer-scr-folded'));
  assert.equal(fold.getAttribute('aria-expanded'), 'true');
  // The site's countdown ticking every second does not rebuild the view.
  p.q('[data-timer]').textContent = 'Осталось 2 дня';
  p.apply();
  assert.equal(p.q('#host>.univer-scr-exams'), view);
  // A node the site takes out and later puts back shows as the site drew it, and release leaves nothing on it.
  const box = p.q('#host>div.inner'), anchor = box.nextSibling, parent = box.parentNode;
  box.remove(); p.apply();
  assert.ok(!box.classList.contains('univer-scr-source'));
  parent.insertBefore(box, anchor); p.apply();
  assert.ok(box.classList.contains('univer-scr-source'));
  p.release();
  assert.equal(p.q('#host').innerHTML, before.replace('Осталось 3 дня', 'Осталось 2 дня')
    .replace('<a href="#" id="take" class="take-exam activeExam"></a>', '<a href="#" id="take2" class="take-exam activeExam"></a>'));
  assert.equal(p.all('[class*="univer-scr"]').length, 0);
  assert.equal(p.d.documentElement.className, 'univer-mobile');
  assert.equal(p.q('#univer-screens-style'), null);
  p.close();
});

test('an empty midterm timetable says it is not published, in the chosen language, and hides nothing else', () => {
  const body = '<table class="mt"><tr class="top"><td class="ct">Расписание Midterm</td></tr><tr class="mid"><td class="tt" id="host">' +
    '<p id="note">Сообщение кафедры</p>' + headings +
    '<div class="inner"><table class="inner"><tr class="empty"><td colspan="5">Не заполнено расписание Midterm</td></tr></table></div></td></tr></table>';
  const p = page('/student/mymidterm/schedule/', body, { language: 'kz' });
  p.apply();
  const box = p.q('#host>.univer-scr-empty');
  assert.equal(box.querySelector('.univer-scr-empty-title').textContent, 'Midterm кестесі әлі жарияланбаған');
  assert.equal(box.nextElementSibling, p.q('#host>table.inner'));
  assert.ok(p.q('#host>table.inner').classList.contains('univer-scr-source') && p.q('#host>div.inner').classList.contains('univer-scr-source'));
  assert.ok(!p.q('#note').className, 'the department note stays visible');
  p.apply();
  assert.equal(p.q('#host>.univer-scr-empty'), box, 'a second pass keeps the same box');
  p.release();
  assert.equal(p.all('[class*="univer-scr"]').length, 0);
  p.close();
});

const memo = 'Памятка студенту. '.repeat(50);
function orders(requests) {
  return `<table class="mt"><tr class="top"><td class="ct">Памятка</td></tr><tr class="mid"><td class="ct" id="memo"><b>Важно.</b> ${memo}</td></tr></table>` +
    '<form method="post" id="order_add" action="/student/failfxorder/add/"><table class="inner" id="order"><tr><th><input type="radio" class="order" name="order"> Заявка</th><th>Дисциплина</th></tr>' +
    '<tr class="empty"><td colspan="2">Нет данных</td></tr></table></form>' +
    '<table class="inner" id="requests"><tr><th colspan="8">Мои заявки на пересдачу FX</th></tr>' + requests + '</table>';
}

test('empty FX lists get titled empty states, a list with rows stays, and the long memo folds', () => {
  const p = page('/student/failfx/', orders('<tr class="empty"><td colspan="8">Нет данных</td></tr>'), { size: 900 });
  const memoBefore = p.q('#memo').innerHTML;
  p.apply();
  const states = p.all('.univer-scr-empty');
  assert.equal(states.length, 2);
  assert.equal(states[0].dataset.tone, 'good');
  assert.equal(states[0].querySelector('h3').textContent, 'Дисциплин для пересдачи FX нет');
  assert.equal(states[1].querySelector('h3').textContent, 'Мои заявки на пересдачу FX');
  assert.equal(states[1].querySelector('p').textContent, 'Заявок пока нет');
  assert.ok(p.q('#order').classList.contains('univer-scr-source') && p.q('#requests').classList.contains('univer-scr-source'));
  const more = p.q('#memo>button.univer-scr-more');
  assert.equal(more.textContent, 'Показать полностью');
  more.click();
  assert.ok(p.q('#memo>.univer-scr-clamp').classList.contains('univer-scr-open'));
  assert.equal(more.getAttribute('aria-expanded'), 'true');
  assert.equal(more.textContent, 'Свернуть');
  p.apply();
  assert.equal(p.all('.univer-scr-empty').length, 2, 'a second pass keeps the same states');
  // A language change relabels the button it already made.
  p.w.__univerScreensV1.apply('en');
  assert.equal(more.textContent, 'Collapse');
  p.release();
  assert.equal(p.q('#memo').innerHTML, memoBefore);
  assert.equal(p.all('[class*="univer-scr"]').length, 0);
  p.close();

  const filled = page('/student/failfx/', orders('<tr class="link"><td>1</td><td>Дисциплина</td><td>Подана</td></tr>'));
  filled.apply();
  assert.equal(filled.all('.univer-scr-empty').length, 1);
  assert.ok(!filled.q('#requests').classList.contains('univer-scr-source'));
  filled.close();
});

test('the survey shows its answer scale and how many questionnaires are answered', () => {
  const intro = '<table class="mt"><tr class="top"><td class="ct">Анкетирование</td></tr><tr class="mid"><td class="ct" id="intro">Оцените работу преподавателя. ' +
    '1 – Неудовлетворительно; 2 – Удовлетворительно; 3 – Хорошо; 4 – Очень хорошо; 5 – Отлично;</td></tr></table>';
  const root = page('/student/rating/', intro);
  root.apply();
  const scale = root.q('#intro>.univer-scr-scale');
  assert.equal(scale.querySelectorAll('.univer-scr-scale-step').length, 5);
  assert.deepEqual(Array.from(scale.querySelectorAll('.univer-scr-scale-ends>span')).map(s => s.textContent), ['Неудовлетворительно', 'Отлично']);
  assert.match(scale.getAttribute('aria-label'), /^Шкала ответов: 1 – Неудовлетворительно, .*5 – Отлично$/);
  root.release();
  assert.equal(root.all('.univer-scr-scale').length, 0);
  root.close();

  const list = '<table class="mt"><tr class="mid"><td class="tt"><div class="inner"><table class="inner" id="subjects">' +
    '<tr><th>Дисциплина</th><th>ФИО преподавателя</th></tr><tr class="link done" id="1"><td>А</td><td>Б</td></tr>' +
    '<tr class="link done" id="2"><td>В</td><td>Г</td></tr><tr class="link" id="3"><td>Д</td><td>Е</td></tr></table></div></td></tr></table>';
  const subjects = page('/student/rating/208/', '<table class="inner" id="outer"><tr><td>Переводной GPA = 0</td></tr><tr><td><h3>Список анкет</h3>' + list + '</td></tr></table>');
  subjects.apply();
  const progress = subjects.q('.univer-scr-progress');
  assert.equal(progress.querySelector('.univer-scr-progress-value').textContent, '2 / 3');
  assert.equal(progress.querySelector('.univer-scr-progress-note').textContent, 'Осталось ответить: 1');
  assert.match(progress.querySelector('.univer-scr-progress-fill').style.transform, /^scaleX\(0\.66/);
  assert.equal(progress.nextElementSibling, subjects.q('div.inner'));
  subjects.close();
});

function advisor(email, phone, more = '') {
  return '<table class="mt"><tr id="group_list"><td class="tt" id="host"><div class="inner"><table class="inner">' +
    // As on the site: two label/value pairs per row, the last pair left blank.
    `<tr><td><li>Ф.И.О.</li></td><td><b>Тестова Анна Сергеевна</b></td><td><li>E-mail</li></td><td>${email}</td></tr>` +
    `<tr><td><li>Рабочий телефон</li></td><td>${phone}</td><td></td><td></td></tr>${more}</table></div></td></tr></table>`;
}

test('the advisor becomes a contact card that offers only the contacts the site gives', () => {
  const none = page('/student/advicer/', advisor('', ''));
  none.apply();
  assert.equal(none.q('.univer-scr-avatar').textContent, 'ТА');
  assert.equal(none.q('.univer-scr-contact-name').textContent, 'Тестова Анна Сергеевна');
  assert.equal(none.q('.univer-scr-contact-none').textContent, 'Контакты не указаны');
  assert.equal(none.all('a.univer-scr-action').length, 0);
  none.release();
  assert.equal(none.all('[class*="univer-scr"]').length, 0);
  none.close();

  const both = page('/student/advicer/', advisor('test.advisor@example.org', '+7 (727) 000-00-00',
    '<tr><td><li>Кафедра</li></td><td>Кафедра информатики</td></tr>'));
  both.apply();
  assert.deepEqual(both.all('a.univer-scr-action').map(a => a.getAttribute('href')), ['mailto:test.advisor@example.org', 'tel:+77270000000']);
  // A line the card has no slot for is still shown with its label.
  const extra = both.q('.univer-scr-contact-extra');
  assert.equal(extra.querySelector('.univer-scr-contact-label').textContent, 'Кафедра');
  assert.equal(extra.textContent, 'КафедраКафедра информатики');
  // Only the site's table is hidden, not the panel around it.
  assert.ok(both.q('#host>div.inner').classList.contains('univer-scr-source') && both.q('#host').className === 'tt');
  both.close();

  // The facts grid page polish made from the same table is replaced too; a note beside it stays.
  const polished = page('/student/advicer/', advisor('', '').replace('<div class="inner">', '<div class="inner"><p id="note">Заметка</p><div class="up-facts" id="facts"></div>'));
  polished.apply();
  assert.ok(polished.q('#facts').classList.contains('univer-scr-source') && polished.q('#host table.inner').classList.contains('univer-scr-source'));
  assert.equal(polished.q('.univer-scr-contact').nextElementSibling, polished.q('#facts'));
  assert.ok(!polished.q('#note').className);
  polished.apply();
  assert.equal(polished.all('.univer-scr-contact').length, 1);
  polished.release();
  assert.equal(polished.all('[class*="univer-scr"]').length, 0);
  assert.equal(polished.q('#facts').className, 'up-facts');
  polished.close();

  // The lines keep what the site wrote; buttons take the first address and number, and a department's name is not the advisor's.
  const rough = page('/student/advicer/', advisor('a.one@example.org, b.two@example.org', 'вн. 1234',
    '<tr><td><li>Кафедраның аты</li></td><td>Ақпараттық жүйелер</td><td><li>Телефон</li></td><td>8 (727) 111-22-33, 8 (727) 222-33-44</td></tr>'));
  rough.apply();
  assert.equal(rough.q('.univer-scr-contact-name').textContent, 'Тестова Анна Сергеевна');
  assert.equal(rough.q('.univer-scr-contact-phone').textContent, 'вн. 1234');
  assert.deepEqual(rough.all('a.univer-scr-action').map(a => a.getAttribute('href')), ['mailto:a.one@example.org']);
  assert.equal(rough.q('.univer-scr-contact-mail').textContent, 'a.one@example.org, b.two@example.org');
  // A second number is kept as its own labelled line.
  assert.equal(rough.q('.univer-scr-contact-extra:last-child').textContent, 'Телефон8 (727) 111-22-33, 8 (727) 222-33-44');
  rough.close();

  // A table of another shape stays as the site drew it.
  const odd = page('/student/advicer/', advisor('', '', '<tr><td>1</td><td>2</td><td>3</td></tr>'));
  odd.apply();
  assert.equal(odd.all('.univer-scr-contact').length, 0);
  assert.equal(odd.all('.univer-scr-source').length, 0);
  odd.close();
});

test('the GPA calculator folds its grade table into a scale and counts courses and credits', async () => {
  const grade = (letter, points, percent, name, span) => `<tr><td>${letter}</td><td>${points}</td><td>${percent}</td>${name ? `<td rowspan="${span}">${name}</td>` : ''}</tr>`;
  const body = '<table class="mt"><tr class="mid"><td class="tt"><table id="layout"><tr><td id="calc">' +
    '<table id="gpa-table"><tr><th>Дисциплина</th><th>Кредиты</th><th>Оценка</th></tr>' +
    '<tr class="univer-app-card-row"><td><input type="text"></td><td><input class="credit-input" value="5"></td><td><select class="mark-select"><option value="">-</option><option value="4">A</option></select></td></tr>' +
    '<tr class="univer-app-card-row"><td><input type="text"></td><td><input class="credit-input" value=""></td><td><select class="mark-select"><option value="">-</option><option value="4">A</option></select></td></tr></table>' +
    '<div id="bar"><b>Общий GPA:</b> <span id="resultCell">0.00</span></div></td><td id="spacer"></td><td id="reference"><table>' +
    '<tr><th>Оценка по буквенной системе</th><th>Цифровой эквивалент</th><th>%</th><th>Традиционная</th></tr>' +
    grade('A', '4,0', '95-100', 'Отлично', 2) + grade('A-', '3,67', '90-94') + grade('B+', '3,33', '85-89', 'Хорошо&nbsp;', 1) +
    '<tr><td>I (Incomplete)</td><td>-</td><td>-</td><td> ""Дисциплина не завершена""<br> (Не учитывается при вычислении GPA)</td></tr>' +
    '<tr><td>Атт.</td><td>-</td><td>30-60<br>50-100</td><td>"Аттестован"</td></tr></table></td></tr></table></td></tr></table>';
  const p = page('/my/calculatorgpa/', body);
  p.apply();
  assert.ok(p.q('#reference').classList.contains('univer-scr-source') && p.q('#spacer').classList.contains('univer-scr-source'));
  const scale = p.q('.univer-scr-grades');
  assert.deepEqual(Array.from(scale.querySelectorAll('.univer-scr-grade-name')).map(n => n.textContent), ['Отлично', 'Хорошо', 'Другие отметки']);
  assert.deepEqual(Array.from(scale.querySelectorAll('.univer-scr-grade>b')).map(n => n.textContent), ['A', 'A-', 'B+']);
  assert.deepEqual(Array.from(scale.querySelectorAll('dd')).map(n => n.textContent),
    ['Дисциплина не завершена (Не учитывается при вычислении GPA)', 'Аттестован · 30-60 / 50-100']);
  const toggle = scale.querySelector('button');
  assert.ok(scale.querySelector('.univer-scr-grades-body').hidden);
  toggle.click();
  assert.ok(!scale.querySelector('.univer-scr-grades-body').hidden);
  assert.equal(toggle.getAttribute('aria-expanded'), 'true');
  // Only rows with a grade and credits count.
  const meta = p.q('#bar>.univer-scr-gpa-meta');
  assert.equal(meta.textContent, '');
  p.q('.mark-select').selectedIndex = 1;
  p.q('.mark-select').dispatchEvent(new p.w.Event('change', { bubbles: true }));
  await tick();
  assert.equal(meta.textContent, '1 дисциплина · 5 кредитов');
  p.release();
  assert.equal(p.all('[class*="univer-scr"]').length, 0);
  p.q('.mark-select').dispatchEvent(new p.w.Event('change', { bubbles: true }));
  await tick();
  p.close();
});

test('study plan categories without credits fold behind one button', () => {
  const stats = ['5', '0', '0', '0', '12', '0'].map((v, i) => `<div class="up-stat${v === '0' ? ' up-zero' : ''}"><b>${v}</b> Категория ${i}</div>`).join('');
  const fields = ['РУП', 'Дисциплины', 'Цикл', 'Крд.', 'лек.', 'сем.', 'лаб.', 'Спец.', 'Тип контроля', 'Язык. гр.'];
  const values = [' - ', 'Социология', ' О', '2', '1,50', '0,50', '0,00', '-', '[РК1 + РК2 + Экз] (100)', ''];
  const course = '<table class="inner univer-app-cards"><tr class="link univer-app-card-row" id="course">' +
    values.map((v, i) => `<td data-univer-field="${fields[i]}"${i === 1 ? ' class="univer-app-long-field"' : ''}>${v}</td>`).join('') + '</tr></table>';
  const p = page('/student/educplan/', `<div class="up-facts up-stats" id="facts">${stats}</div>` + course);
  p.apply();
  // A course card hides the fields the site leaves as "-" or empty and keeps the rest.
  assert.ok(p.q('#course').classList.contains('univer-scr-course'));
  assert.deepEqual(Array.from(p.q('#course').cells).map(c => c.classList.contains('univer-scr-blank')),
    [true, false, false, false, false, false, false, true, false, true]);
  const button = p.q('.univer-scr-zeros-toggle');
  assert.equal(button.textContent, 'Показать категории без кредитов (4)');
  assert.ok(p.q('#facts').classList.contains('univer-scr-zeros-hidden'));
  button.click();
  assert.ok(!p.q('#facts').classList.contains('univer-scr-zeros-hidden'));
  assert.equal(button.textContent, 'Скрыть категории без кредитов');
  p.release();
  assert.equal(p.all('[class*="univer-scr"]').length, 0);
  p.close();
});

test('a form\'s main action is filled, Back stays quiet, and nothing happens without layers or off the listed pages', () => {
  const form = '<form id="send_message" class="mf"><input id="back" type="button" value="Назад"><input id="send" type="submit" value="Отправить сообщение"></form>';
  const p = page('/my/messages/index/', form);
  p.apply();
  assert.ok(p.q('#send').classList.contains('univer-scr-primary'));
  assert.ok(!p.q('#back').classList.contains('univer-scr-primary'));
  const css = p.q('#univer-screens-style').textContent;
  assert.match(css, /@layer univer-screens/);
  // Every declaration is important, or the layout's unlayered !important rules would win.
  const bodies = css.match(/\{[^{}]*\}/g);
  assert.ok(bodies.length > 50);
  for (const body of bodies) for (const declaration of body.slice(1, -1).split(';').filter(d => d.trim()))
    assert.match(declaration, /!important\s*$/, declaration);
  // A section's file count keeps its digits on one line next to a long title (Документы: «110» broke into «11» and «0»).
  const count = css.match(/summary>\.up-count \{([^}]*)\}/);
  assert.ok(count, 'the count rule is there');
  assert.match(count[1], /flex:none!important/);
  assert.match(count[1], /white-space:nowrap!important/);
  p.close();

  // A long FAQ question gets the lighter weight; a short one keeps the site's.
  const long = 'Вопрос о прокторинге и требованиях к компьютеру, камере и сети, '.repeat(4);
  const faqPage = page('/my/faq/2', `<details class="univer-app-faq" id="a"><summary>${long}</summary>Ответ</details>` +
    '<details class="univer-app-faq" id="b"><summary>Короткий вопрос</summary>Ответ</details>');
  faqPage.apply();
  assert.ok(faqPage.q('#a>summary').classList.contains('univer-scr-long-q'));
  assert.ok(!faqPage.q('#b>summary').hasAttribute('class'));
  faqPage.release();
  assert.ok(!faqPage.q('#a>summary').hasAttribute('class'));
  faqPage.close();

  const old = page('/my/messages/index/', form, { capable: false });
  old.apply();
  assert.equal(old.q('#univer-screens-style'), null);
  assert.equal(old.d.documentElement.className, 'univer-mobile');
  old.close();

  const other = page('/student/attendance/', form);
  other.apply();
  assert.equal(other.q('#univer-screens-style'), null);
  assert.ok(!other.q('#send').classList.contains('univer-scr-primary'));
  other.close();
});
