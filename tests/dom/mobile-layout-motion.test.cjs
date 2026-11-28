const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM, VirtualConsole } = require('jsdom');

const assets = 'app/src/main/assets/';
const adapter = fs.readFileSync('app/src/main/java/com/univerflow/kaznu/web/MobilePageAdapter.kt', 'utf8');
const scripts = JSON.parse('[' + adapter.match(/val SCRIPTS = listOf\(([^)]*)\)/)[1] + ']');
const run = (w, ...names) => names.forEach(name => w.eval(fs.readFileSync(assets + name, 'utf8')));
function page(path, body, classes = 'univer-mobile') {
  const dom = new JSDOM('<!doctype html><html class="' + classes + '"><body>' + body + '</body></html>', {
    url: 'https://univer.kaznu.kz' + path, runScripts: 'outside-only', pretendToBeVisual: true, virtualConsole: new VirtualConsole()
  });
  const w = dom.window;
  Object.defineProperty(w.document, 'readyState', { value: 'complete' });
  w.matchMedia = () => ({ matches: false, addEventListener() {} });
  return w;
}

const people = rows => '<div class="input-row"><div class="input-field"><b>Ф.И.О.</b></div><div class="input-field"><b>Номер мобильного телефона</b></div><div class="input-field"><b>Email</b></div></div>' +
  rows.map(row => '<div class="input-row">' + row.map(value => '<div class="input-field"><span>' + value + '</span></div>').join('') + '</div>').join('');
const syllabus = '<div id="content"><table class="students"><tr><td>Название дисциплины:</td><td>Казахский (русский) язык</td></tr>' +
  '<tr><td>Лекторы:</td><td id="lecturers">' + people([['Тестова Айгерим Сериковна', '+7 (701) 234-56-78', 'a.testova@example.kz'], ['Тестов Арман', '8 701 23', 'не указан']]) + '</td></tr>' +
  '<tr><td>Ассистенты:</td><td id="assistants">' + people([['', '', ''], ['Сейітова Дана', '', 'dana@@example']]) + '</td></tr>' +
  '<tr><td>Ассистенты:</td><td id="empty">' + people([['', '', '']]) + '</td></tr></table></div>';
const buttons = d => Array.from(d.querySelectorAll('button.univer-scr-syllabus-copy'));
const nextTask = () => new Promise(resolve => setTimeout(resolve, 0));

test('readable syllabus puts original contact values in tap links and copies each provided value', async t => {
  const w = page('/student/interactive/syllabus/print/12/3', syllabus, '');
  t.after(() => w.close());
  const d = w.document, sheet = d.querySelector('#content'), original = sheet.innerHTML;
  const source = d.querySelector('#lecturers .input-row:nth-child(2) .input-field:nth-child(2) span').firstChild;
  let copied, clicks = 0;
  source.parentElement.addEventListener('click', event => { clicks++; event.preventDefault(); });
  Object.defineProperty(w.navigator, 'clipboard', { value: { writeText: value => { copied = value; return Promise.resolve(); } } });
  run(w, ...scripts);
  const mobile = w.__univerMobileV1, screens = w.__univerScreensV1;
  mobile.setEnabled(true, 'ru'); screens.apply('ru'); screens.apply('ru');
  const links = Array.from(sheet.querySelectorAll('a'), link => link.getAttribute('href'));
  assert.deepEqual(links, ['tel:+77012345678', 'mailto:a.testova@example.kz']);
  assert.ok(sheet.querySelector('a[href^="tel:"]').contains(source));
  source.parentElement.click(); assert.equal(clicks, 1, 'the original span still handles clicks');
  assert.equal(buttons(d).length, 7);
  assert.equal(d.querySelector('#empty .univer-scr-syllabus-none').textContent, 'Не указаны');
  assert.equal(d.querySelector('details'), null, 'contacts are visible without opening a disclosure');
  const email = buttons(d)[2];
  assert.equal(email.getAttribute('aria-label'), 'Копировать: a.testova@example.kz');
  email.click(); await nextTask();
  assert.equal(copied, 'a.testova@example.kz');
  assert.equal(email.textContent, 'Скопировано');
  assert.equal(d.querySelector('[role="status"].univer-scr-syllabus-status').textContent, 'Скопировано');
  screens.apply('kz');
  assert.equal(email.textContent, 'Көшірілді');
  assert.equal(d.querySelector('#empty .univer-scr-syllabus-none').textContent, 'Көрсетілмеген');
  screens.apply('en');
  assert.equal(email.textContent, 'Copied');
  mobile.setEnabled(false, 'ru');
  assert.equal(sheet.innerHTML, original, 'Original restores all cells including empty fields');
  assert.equal(d.querySelector('.univer-scr-syllabus-status'), null);
  assert.equal(source.parentElement.tagName, 'SPAN');
});

test('a rejected clipboard write falls back to selection and returns keyboard focus', async t => {
  const w = page('/student/interactive/syllabus/print/12/3/', syllabus);
  t.after(() => { w.__univerScreensV1.release(); w.close(); });
  const d = w.document;
  Object.defineProperty(w.navigator, 'clipboard', { value: { writeText: () => Promise.reject(new Error('denied')) } });
  let selection;
  d.execCommand = command => {
    assert.equal(command, 'copy');
    const area = d.querySelector('textarea');
    selection = area.value.slice(area.selectionStart, area.selectionEnd);
    return true;
  };
  run(w, 'univer-dom.js', 'univer-screens.js');
  w.__univerScreensV1.apply('en');
  const phone = buttons(d)[1];
  phone.focus(); phone.click(); await nextTask();
  assert.equal(selection, '+7 (701) 234-56-78');
  assert.equal(phone.textContent, 'Copied');
  assert.equal(d.activeElement, phone);
  assert.equal(d.querySelector('textarea'), null);
});

test('other print routes do not turn the same fields into syllabus controls', t => {
  const w = page('/student/transcript/12/', syllabus);
  t.after(() => w.close());
  run(w, 'univer-dom.js', 'univer-screens.js');
  w.__univerScreensV1.apply('ru');
  assert.equal(buttons(w.document).length, 0);
});

const tap = (w, n) => n.dispatchEvent(new w.MouseEvent('click', { detail: 1, bubbles: true, cancelable: true }));
const rect = (height, top = 0) => ({ height, width: 300, top, bottom: top + height, left: 0, right: 300 });
function fakeAnimations(w) {
  const animations = [];
  w.Element.prototype.animate = function (frames, options) {
    const animation = { node: this, frames, options, playState: 'running',
      cancel() { this.playState = 'idle'; this.oncancel?.(); },
      finish() { this.playState = 'finished'; this.onfinish?.(); }
    };
    animations.push(animation);
    return animation;
  };
  return animations;
}

test('a tall details fold keeps content through closing and reverses from the current height', t => {
  const w = page('/fixture/', '<details class="univer-app-faq"><summary>Question</summary><a href="#answer">Answer</a></details>');
  t.after(() => w.close());
  const animations = fakeAnimations(w), details = w.document.querySelector('details'), summary = details.firstChild;
  let height;
  details.getBoundingClientRect = () => rect(details.hasAttribute('data-univer-resizing') && height ? height : details.open ? 1500 : 44);
  summary.getBoundingClientRect = () => rect(44);
  run(w, 'univer-motion.js');
  tap(w, summary);
  const opening = animations.at(-1);
  assert.equal(opening.options.duration, 420, 'a fold taller than the viewport still animates at the capped duration');
  opening.finish();
  details.querySelector('a').focus();
  tap(w, summary);
  const closing = animations.at(-1);
  assert.equal(details.open, true, 'content remains visible to the moving edge');
  assert.equal(w.document.activeElement, summary);
  assert.equal(closing.options.easing, opening.options.easing);
  assert.equal(closing.options.duration, opening.options.duration);
  height = 768;
  tap(w, summary);
  const reversal = animations.at(-1);
  assert.equal(closing.playState, 'idle');
  assert.equal(reversal.frames[0].height, '768px');
  assert.equal(details.open, true);
  height = undefined;
  reversal.finish();
  tap(w, summary); animations.at(-1).finish();
  assert.equal(details.open, false);
  assert.equal(details.hasAttribute('data-univer-resizing'), false);
});

test('keyboard, still mode and reduced motion toggle folds without animation', t => {
  const w = page('/fixture/', '<details class="univer-app-faq"><summary>Question</summary><p>Answer</p></details>');
  t.after(() => w.close());
  const animations = fakeAnimations(w), details = w.document.querySelector('details');
  let reduced = false;
  w.matchMedia = () => ({ matches: reduced, addEventListener() {} });
  run(w, 'univer-motion.js');
  details.firstChild.click(); assert.equal(details.open, true);
  w.document.documentElement.classList.add('univer-app-still');
  tap(w, details.firstChild); assert.equal(details.open, false);
  w.document.documentElement.classList.remove('univer-app-still');
  reduced = true;
  tap(w, details.firstChild); assert.equal(details.open, true);
  assert.equal(animations.length, 0);
});

test('popup leaves at the same pace, and reopening retires the passive closing copy', t => {
  const w = page('/fixture/', '<details class="univer-app-account"><summary>Account</summary><div><button>Settings</button></div></details>', 'univer-mobile univer-app-unified');
  t.after(() => w.close());
  const animations = fakeAnimations(w), d = w.document, details = d.querySelector('details');
  details.lastChild.getBoundingClientRect = () => rect(240);
  run(w, 'univer-motion.js');
  tap(w, details.firstChild);
  const opening = animations.at(-1);
  assert.equal(opening.node, details.lastChild);
  tap(w, details.firstChild);
  const closing = animations.at(-1), ghost = d.querySelector('[data-univer-motion-ghost]');
  assert.equal(details.open, false);
  assert.equal(ghost.textContent, 'Settings');
  assert.equal(ghost.getAttribute('aria-hidden'), 'true');
  assert.deepEqual([closing.options.duration, closing.options.easing], [opening.options.duration, opening.options.easing]);
  ghost.style.opacity = '.6';
  tap(w, details.firstChild);
  assert.equal(details.open, true);
  assert.equal(ghost.isConnected, false);
  assert.equal(animations.at(-1).frames[0].opacity, '0.6');
});

test('a news preview animates both ways while keeping space between articles', t => {
  const w = page('/news/student', '<table class="univer-app-news"><tr class="news"><td>10.11.2026</td>' +
    '<td><div style="margin: 4.8px 180px 0px 142px; text-align:center">Казахский (русский) язык</div></td></tr></table>');
  t.after(() => w.close());
  const d = w.document, row = d.querySelector('.news'), article = row.cells[1].firstChild, original = row.outerHTML;
  const animations = fakeAnimations(w);
  Object.defineProperty(w.HTMLElement.prototype, 'scrollHeight', { get() { return 620; } });
  w.HTMLElement.prototype.getBoundingClientRect = function () { return rect(this.dataset.collapsed === 'true' ? 132 : 620, 100); };
  w.Range.prototype.getClientRects = () => [];
  run(w, 'univer-dom.js', 'univer-motion.js', 'univer-news.js', 'univer-mobile-css.js');
  w.__univerNewsV1.apply('ru');
  const button = row.querySelector('button'), body = row.querySelector('.univer-app-news-body');
  const style = d.createElement('style'); style.textContent = w.__univerMobileCssV1; d.head.appendChild(style);
  const css = Array.from(style.sheet.cssRules);
  const narrow = css.find(rule => rule.conditionText === '(max-width:600px)' && rule.cssText.includes('.univer-app-news-body'));
  assert.match(narrow.cssText, /margin-left:\s*0\s*!important/);
  assert.match(narrow.cssText, /margin-right:\s*0\s*!important/);
  assert.equal(article.style.margin, '4.8px 180px 0px 142px', 'the inline paste formatting is left in the DOM');
  assert.equal(button.getAttribute('aria-expanded'), 'false');
  tap(w, button);
  const opening = animations.at(-1);
  assert.equal(button.getAttribute('aria-expanded'), 'true');
  assert.equal(css.find(rule => rule.selectorText?.endsWith('.univer-app-news tr.news')).style.getPropertyValue('margin-bottom'), '24px');
  opening.finish();
  tap(w, button);
  const closing = animations.at(-1);
  assert.equal(closing.frames[0].height, '620px');
  assert.equal(closing.frames[1].height, '132px');
  assert.equal(closing.options.duration, opening.options.duration);
  assert.ok(body.contains(article) && body.hasAttribute('data-univer-resizing'), 'the cap steps aside during the close');
  assert.equal(button.getAttribute('aria-expanded'), 'false');
  closing.finish();
  assert.equal(body.hasAttribute('data-univer-resizing'), false);
  const actions = animations.length;
  button.click();
  assert.equal(button.getAttribute('aria-expanded'), 'true');
  assert.equal(animations.length, actions, 'keyboard activation shows the article immediately');
  w.__univerNewsV1.release();
  assert.equal(row.outerHTML, original);
  assert.equal(row.cells[1].firstChild, article);
});

test('mobile cards override the site row height through CSS and retain the inline value for Original', t => {
  const w = page('/fixture/', '<table><tr class="univer-app-card-row" style="height:30px"><td style="height:30px">Казахский (русский) язык</td><td>Преподаватель</td><td class="univer-app-blank-field"></td></tr></table>');
  t.after(() => w.close());
  run(w, 'univer-mobile-css.js');
  const d = w.document, style = d.createElement('style');
  style.textContent = w.__univerMobileCssV1; d.head.appendChild(style);
  const rules = Array.from(style.sheet.cssRules);
  const rowRule = rules.find(rule => rule.selectorText === 'html.univer-mobile .univer-app-card-row');
  assert.equal(rowRule.style.getPropertyValue('height'), 'auto');
  assert.equal(rowRule.style.getPropertyPriority('height'), 'important');
  const cellRule = rules.find(rule => rule.selectorText === 'html.univer-mobile .univer-app-card-row>:is(td,th)');
  assert.equal(cellRule.style.getPropertyValue('height'), 'auto');
  assert.equal(cellRule.style.getPropertyPriority('height'), 'important');
  assert.equal(cellRule.style.getPropertyValue('float'), 'none');
  const blankRule = rules.find(rule => rule.selectorText === 'html.univer-mobile .univer-app-card-row>td.univer-app-blank-field');
  assert.equal(blankRule.style.getPropertyValue('display'), 'none');
  assert.equal(d.querySelector('tr').style.height, '30px');
  assert.equal(d.querySelector('td').style.height, '30px');
});

test('expanded news articles mark separation, defer preview clipping until shut ends, and reverse smoothly mid-close', t => {
  const w = page('/news/student', '<table class="univer-app-news"><tr class="news"><td>10.11.2026</td>' +
    '<td><div style="margin: 4.8px 180px 0px 142px; text-align:center">Казахский (русский) язык</div></td></tr></table>');
  t.after(() => w.close());
  const d = w.document, row = d.querySelector('.news'), animations = fakeAnimations(w);
  let midHeight = 0;
  Object.defineProperty(w.HTMLElement.prototype, 'scrollHeight', { get() { return 620; } });
  w.HTMLElement.prototype.getBoundingClientRect = function () {
    if (midHeight && this.hasAttribute('data-univer-resizing') && this.classList.contains('univer-app-news-body')) return rect(midHeight, 100);
    return rect(this.dataset.collapsed === 'true' ? 132 : 620, 100);
  };
  w.Range.prototype.getClientRects = () => [];
  run(w, 'univer-dom.js', 'univer-motion.js', 'univer-news.js', 'univer-mobile-css.js');
  w.__univerNewsV1.apply('ru');
  const button = row.querySelector('button'), body = row.querySelector('.univer-app-news-body');
  const style = d.createElement('style'); style.textContent = w.__univerMobileCssV1; d.head.appendChild(style);
  const rules = Array.from(style.sheet.cssRules);
  assert.equal(rules.find(r => r.selectorText?.endsWith('tr.news[data-univer-expanded=true]')).style.getPropertyValue('margin-bottom'), '28px');
  assert.ok(rules.find(r => r.selectorText?.endsWith('button.univer-app-news-toggle[aria-expanded=true]')).style.getPropertyValue('border-top'));
  tap(w, button);
  assert.equal(row.getAttribute('data-univer-expanded'), 'true');
  animations.at(-1).finish();
  tap(w, button);
  const closing = animations.at(-1);
  assert.equal(row.hasAttribute('data-univer-expanded'), false);
  assert.equal(body.getAttribute('data-collapsed'), 'false', 'the preview cap waits until the closing animation finishes');
  assert.equal(button.getAttribute('aria-expanded'), 'false');
  // Quick repeated tap mid-close reverses smoothly from the current height.
  midHeight = 380;
  tap(w, button);
  const reopened = animations.at(-1);
  assert.equal(closing.playState, 'idle');
  assert.equal(reopened.frames[0].height, '380px');
  assert.equal(row.getAttribute('data-univer-expanded'), 'true');
  midHeight = 0;
  reopened.finish();
  tap(w, button);
  animations.at(-1).finish();
  assert.equal(body.getAttribute('data-collapsed'), 'true');
  w.__univerNewsV1.release();
  assert.equal(row.hasAttribute('data-univer-expanded'), false);
});

test('screen folds (instructions panel, GPA scale, memo, study plan) keep content while closing and reverse cleanly', t => {
  const w = page('/student/myexam/schedule/',
    '<table class="wrappertable"><tr id="l4"><td>' +
    '<table class="mt" id="inst"><tr class="top"><td class="ct">Инструкция</td></tr>' +
    '<tr class="mid"><td class="ct"><a href="/a.pdf">A</a><a href="/b.pdf">B</a></td></tr></table>' +
    '</td></tr></table>');
  t.after(() => w.close());
  const d = w.document, animations = fakeAnimations(w);
  w.CSS = { supports: () => true }; w.CSSLayerBlockRule = function () {};
  run(w, 'univer-dom.js', 'univer-motion.js', 'univer-screens.js');
  w.__univerScreensV1.apply('ru');
  const panel = d.getElementById('inst'), fold = panel.querySelector('button.univer-scr-fold');
  let height = 0;
  panel.getBoundingClientRect = () => rect(panel.hasAttribute('data-univer-resizing') && height ? height : panel.classList.contains('univer-scr-folded') ? 48 : 340);
  panel.rows[0].getBoundingClientRect = () => rect(48);
  tap(w, fold);
  const opening = animations.at(-1);
  assert.equal(opening.options.easing, w.__univerMotionUI.easing);
  assert.equal(panel.classList.contains('univer-scr-folded'), false);
  opening.finish();
  tap(w, fold);
  const closing = animations.at(-1);
  assert.equal(panel.classList.contains('univer-scr-folded'), false, 'panel rows stay visible while closing');
  assert.equal(fold.getAttribute('aria-expanded'), 'false', 'button state and chevron flip immediately');
  assert.deepEqual([closing.options.duration, closing.options.easing], [opening.options.duration, opening.options.easing]);
  height = 190;
  tap(w, fold);
  const reversal = animations.at(-1);
  assert.equal(closing.playState, 'idle');
  assert.equal(reversal.frames[0].height, '190px');
  height = 0;
  reversal.finish();
  tap(w, fold);
  animations.at(-1).finish();
  assert.equal(panel.classList.contains('univer-scr-folded'), true);
});

test('collapse and details shutting state clean up once, and large popups use a lightweight shell ghost', t => {
  const many = Array.from({ length: 90 }, (_, i) => '<span>item ' + i + '</span>').join('');
  const w = page('/fixture/',
    '<details class="univer-app-faq" id="faq"><summary>Q</summary><p>A</p></details>' +
    '<div id="box"><p>Content</p></div>' +
    '<details class="univer-app-account" id="acc" open><summary>Account</summary><div id="pop">' + many + '</div></details>',
    'univer-mobile univer-app-unified');
  t.after(() => w.close());
  const d = w.document, animations = fakeAnimations(w);
  const faq = d.getElementById('faq'), box = d.getElementById('box'), pop = d.getElementById('pop');
  faq.getBoundingClientRect = () => rect(faq.open ? 400 : 44);
  faq.firstChild.getBoundingClientRect = () => rect(44);
  box.getBoundingClientRect = () => rect(220);
  pop.getBoundingClientRect = () => rect(300);
  run(w, 'univer-motion.js');
  // Details sets data-univer-shutting while closing and clears it on finish.
  faq.open = true;
  tap(w, faq.firstChild);
  assert.equal(faq.hasAttribute('data-univer-shutting'), true);
  assert.equal(faq.open, true);
  animations.at(-1).finish();
  assert.equal(faq.hasAttribute('data-univer-shutting'), false);
  assert.equal(faq.open, false);
  // collapse runs done exactly once, even when stopped early.
  let calls = 0;
  w.__univerMotionUI.collapse(box, () => { calls++; box.hidden = true; });
  assert.equal(box.hidden, false);
  assert.equal(animations.at(-1).frames[1].height, '0px');
  w.__univerMotionUI.stop(box);
  assert.equal(calls, 1);
  assert.equal(box.hidden, true);
  // Large popup (>80 descendants) fades a lightweight shell without cloning all children.
  w.__univerMotionUI.exit(pop);
  const ghost = d.querySelector('[data-univer-motion-ghost]');
  assert.ok(ghost);
  assert.equal(ghost.children.length, 0);
  animations.at(-1).finish();
  assert.equal(ghost.isConnected, false);
});

test('printed syllabus supports Kazakh headings and two-column contact blocks, and darkens its CSS cleanly', t => {
  const kzBlock = '<div id="content"><table class="students"><tr><td>Лектор:</td><td id="lec">' +
    '<div class="input-row"><div class="input-field"><b>Т.А.Ә.</b></div><div class="input-field"><b>Электрондық пошта</b></div></div>' +
    '<div class="input-row"><div class="input-field"><span>Ахметова Гүлнар</span></div><div class="input-field"><span>g.akhmetova@kaznu.edu.kz</span></div></div>' +
    '</td></tr></table></div>';
  const w = page('/student/interactive/syllabus/print/42/7', kzBlock);
  t.after(() => w.close());
  w.__univerDark = true;
  run(w, 'univer-palette.js', 'univer-dom.js', 'univer-mobile-css.js', 'univer-screens.js');
  w.__univerScreensV1.apply('kz');
  assert.equal(w.document.querySelector('a[href="mailto:g.akhmetova@kaznu.edu.kz"]').textContent, 'g.akhmetova@kaznu.edu.kz');
  assert.equal(buttons(w.document).length, 2);
  const darkened = w.__univerPaletteV1.darken(w.__univerMobileCssV1);
  assert.match(darkened, /button\.univer-scr-syllabus-copy[^}]*font:600 14px\/1\.2[^}]*!important/);
  assert.match(darkened, /button\.univer-scr-syllabus-copy[^}]*background:var\(--u-surface,#17202a\)/);
});
