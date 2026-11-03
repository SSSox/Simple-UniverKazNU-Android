/* Attendance filters hide complete lesson sections; the site's matrices and overall grades stay intact. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerAttendanceV1) return;
    var dom = window.__univerDomV1, clean = dom.clean, ownRows = dom.ownRows, el = dom.el, setText = dom.setText;
    var key = 'univer.mobile.attendance-kind.v1', selected = 'all', language = 'ru', state = null;
    var courseKey = 'univer.mobile.attendance-course.v1:' + location.pathname, selectedCourse = 'all';
    var hidden = new Map(), labels = new Map(), owned = [], identities = new WeakMap(), sequence = 0;
    // The user's own choice per course; without one, details open only when a single course is shown.
    var expanded = new Map(), focusToggle = null;
    var TYPES = ['lecture', 'seminar', 'sro'];
    var WORDS = {
        en: {all:'All classes', lecture:'Lectures', seminar:'Seminars', sro:'Independent work', filter:'Show classes', totals:'Totals by class type', overall:'Overall course total', empty:'No classes of this type', ungraded:'No grades yet', none:'none', note:'The overall course total includes every class type.', lectureLabel:'Lecture', seminarLabel:'Seminar', sroLabel:'Independent work', overview:'Course summary', showDetails:'Show class log', hideDetails:'Hide class log', courses:'Courses', allCourses:'All', noClasses:'No classes yet'},
        kz: {all:'Барлық сабақтар', lecture:'Дәрістер', seminar:'Семинарлар', sro:'СӨЖ', filter:'Сабақтарды көрсету', totals:'Сабақ түрлері бойынша қорытынды', overall:'Пән бойынша жалпы қорытынды', empty:'Бұл түрдегі сабақтар жоқ', ungraded:'Бағалар әлі қойылмаған', none:'жоқ', note:'Пән бойынша жалпы қорытынды сабақтардың барлық түрін қамтиды.', lectureLabel:'Дәріс', seminarLabel:'Семинар', sroLabel:'СӨЖ', overview:'Пәндер бойынша қорытынды', showDetails:'Сабақ журналын көрсету', hideDetails:'Сабақ журналын жасыру', courses:'Пәндер', allCourses:'Барлығы', noClasses:'Сабақтар әлі жоқ'},
        ru: {all:'Все занятия', lecture:'Лекции', seminar:'Семинары', sro:'СРО', filter:'Показывать занятия', totals:'Итоги по видам занятий', overall:'Общий итог предмета', empty:'Нет занятий этого вида', ungraded:'Оценки пока не выставлены', none:'нет', note:'Общий итог предмета учитывает все виды занятий.', lectureLabel:'Лекция', seminarLabel:'Семинар', sroLabel:'СРО / СРОП', overview:'Сводка по предметам', showDetails:'Показать журнал занятий', hideDetails:'Скрыть журнал занятий', courses:'Предметы', allCourses:'Все', noClasses:'Занятия пока не добавлены'}
    };
    try { var saved = sessionStorage.getItem(key); if (/^(all|lecture|seminar|sro)$/.test(saved)) selected = saved; } catch (_) {}
    try { var course = sessionStorage.getItem(courseKey); if (course && course.length <= 1000) selectedCourse = course; } catch (_) {}
    function text() { return WORDS[language] || WORDS.ru; }
    // Server totals read as "РК1 (100): 51 РК2 (100): 0"; each control period becomes a score tile.
    function scores(value) {
        var found = [], pattern = /((?:РК|RK|МБ|АБ)\s*[12]|(?:Экз|Exam|Емтихан|Итог|Total)\p{L}*)\s*(?:\(\s*(\d+(?:[.,]\d+)?)\s*\))?\s*:\s*(-?\d+(?:[.,]\d+)?)?/giu, match;
        while ((match = pattern.exec(value))) found.push({label:match[1].replace(/\s+/g, ''), max:match[2] || '', value:match[3] || ''});
        return found;
    }
    function scoreTiles(list, compact) {
        var box = el('div', 'univer-app-attendance-scores' + (compact ? ' univer-app-attendance-scores-compact' : ''));
        list.forEach(item => {
            var tile = el('div', 'univer-app-score');
            tile.setAttribute('role', 'group');
            tile.setAttribute('aria-label', item.label + (item.max ? ' (' + item.max + ')' : '') + ': ' + (item.value || text().none));
            tile.append(el('span', 'univer-app-score-label', item.label), el('span', 'univer-app-score-value', item.value || text().none));
            if (item.max) {
                tile.appendChild(el('span', 'univer-app-score-max', '/ ' + item.max));
                var ratio = Number(item.value.replace(',', '.')) / Number(item.max.replace(',', '.'));
                if (!compact && item.value && isFinite(ratio)) {
                    var bar = el('span', 'univer-app-score-bar');
                    bar.style.setProperty('--univer-score', (Math.round(Math.max(0, Math.min(1, ratio)) * 1000) / 10) + '%'); tile.appendChild(bar);
                }
            }
            // The tile's label is what a screen reader announces; its parts are for the eye only.
            Array.from(tile.children).forEach(child => child.setAttribute('aria-hidden', 'true'));
            box.appendChild(tile);
        });
        return box;
    }
    function id(node) { if (!identities.has(node)) identities.set(node, ++sequence); return identities.get(node); }
    function kind(value) {
        if (/^(лекц|дәріс|lecture)/i.test(value)) return 'lecture';
        if (/^(семинар|seminar)/i.test(value)) return 'seminar';
        if (/^(ср[ос]|сро[п]?|с[өо]ж|б[өо]ж|independent|self[ -]?study)/i.test(value)) return 'sro';
        return '';
    }
    function before(a, b) { return !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING); }
    function boundary(cell) {
        var row = cell.closest('tr');
        return row && Array.from(row.cells).every(c => c === cell || !clean(c)) ? row : cell;
    }
    // Courses in document order: heading, overall total and lessons, each lesson with the grade tables up to the next one.
    function model() {
        var root = document.querySelector('#l4'); if (!root) return [];
        var events = [];
        root.querySelectorAll('.ct').forEach(cell => {
            if (cell.closest('#tools,.univer-app-attendance-summary')) return;
            // Observed course headings contain the teaching date range; summary cells contain bold RK totals.
            if (/\d{2}\.\d{2}\.\d{4}\s*[-–]\s*\d{2}\.\d{2}\.\d{4}/.test(clean(cell))) events.push({tag:'course', cell, start:boundary(cell)});
            else if (cell.querySelector('b,strong') && /(?:РК|RK|МБ|АБ)\s*[12]/i.test(clean(cell))) events.push({tag:'overall', cell, start:boundary(cell)});
        });
        root.querySelectorAll('a.lt[href*="/student/attendance/show/"],a[data-univer-lesson-label]').forEach(link => {
            var cell = link.closest('.ts') || link.parentElement;
            events.push({tag:'lesson', cell, link, type:kind(clean(link)), start:boundary(cell)});
        });
        events.sort((a, b) => a.start === b.start ? 0 : before(a.start, b.start) ? -1 : 1);
        var groups = [], group = null, tables = Array.from(root.querySelectorAll('table.inner'));
        events.forEach((event, index) => {
            if (event.tag === 'course') {
                if (group) group.end = event.start;
                var source = clean(event.cell.querySelector('.univer-app-course-source') || event.cell);
                var name = source.match(/^(.*?)\s*(?:\(\[|\d{2}\.\d{2}\.\d{4})/)?.[1]?.trim() || source;
                group = {heading:event.cell, start:event.start, end:null, token:source.normalize('NFC').toLocaleLowerCase(), name, lessons:[], overall:null};
                groups.push(group);
            } else if (event.tag === 'overall') {
                if (group) group.overall = event.cell;
            } else {
                // A single-group attendance/show page may omit the full-course heading.
                if (!group) { group = {heading:null, lessons:[], overall:null}; groups.push(group); }
                event.end = events[index + 1]?.start || null; group.lessons.push(event);
                event.tables = tables.filter(table => before(event.start, table) && (!event.end || before(table, event.end)) && !table.contains(event.start));
            }
        });
        return groups.filter(group => group.heading || group.lessons.some(lesson => lesson.type));
    }
    function sums(table) {
        var rows = ownRows(table), entries = [], grid = [];
        // Logical columns account for the observed two-row RK label and any merged date headings.
        rows.forEach((row, r) => {
            if (!grid[r]) grid[r] = [];
            var column = 0;
            Array.from(row.cells).forEach(cell => {
                while (grid[r][column]) column++;
                for (var y = r; y < r + cell.rowSpan; y++) {
                    if (!grid[y]) grid[y] = [];
                    for (var x = column; x < column + cell.colSpan; x++) grid[y][x] = cell;
                }
                column += cell.colSpan;
            });
        });
        rows.forEach((row, r) => {
            var last = grid[r]?.length - 1, header = grid[r]?.[last];
            if (!header || header.tagName !== 'TH' || !/^(сумма|sum|total|жиыны|барлығы)$/i.test(clean(header))) return;
            var period = clean(grid[r][0]);
            for (var n = r + 1; n < rows.length; n++) {
                var value = grid[n]?.[last];
                if (!value || value.tagName !== 'TD') break;
                // A spanned value is a single server total, never count it twice.
                if (!entries.some(e => e.cell === value)) entries.push({period, value:clean(value), cell:value});
            }
        });
        return entries;
    }
    // Adds the per-table sums of one control period exactly, keeping their decimals; other values are listed.
    function periodTotal(items) {
        var precision = Math.max(...items.map(value => (value.match(/[.,](\d+)$/)?.[1] || '').length)), scale = Math.pow(10, precision);
        var numeric = items.every(value => /^-?\d+(?:[.,]\d+)?$/.test(value)) && precision <= 8 &&
            items.every(value => Math.abs(Number(value.replace(',', '.'))) * scale < Number.MAX_SAFE_INTEGER / items.length);
        return numeric ? String(items.reduce((sum, v) => sum + Math.round(Number(v.replace(',', '.')) * scale), 0) / scale) : items.filter(Boolean).join(' + ');
    }
    // Hides a site node; resetVisibility() restores its hidden attribute and class exactly.
    function setHidden(node, value) {
        if (!hidden.has(node)) hidden.set(node, {value:node.hidden, attribute:node.getAttribute('hidden'), classAbsent:!node.hasAttribute('class')});
        var next = hidden.get(node).value || value;
        if (node.hidden !== next) node.hidden = next;
        node.classList.add('univer-app-attendance-part');
    }
    function resetVisibility() {
        hidden.forEach((record, node) => {
            if (record.attribute === null) node.removeAttribute('hidden'); else node.setAttribute('hidden', record.attribute);
            node.classList.remove('univer-app-attendance-part');
            if (record.classAbsent && !node.className) node.removeAttribute('class');
        });
        hidden.clear();
    }
    // The lesson link stays (hidden) for Original mode; a passive caption shows "Лекция" over the teacher's name.
    function makeLabel(link) {
        if (!labels.has(link)) {
            var caption = el('span', 'univer-app-lesson-label');
            var attrs = ['hidden', 'data-univer-lesson-label', 'aria-hidden', 'tabindex'].map(name => [name, link.getAttribute(name)]);
            link.after(caption); link.hidden = true; link.setAttribute('data-univer-lesson-label', 'true'); link.setAttribute('aria-hidden', 'true'); link.tabIndex = -1;
            labels.set(link, {caption, attrs, signature:null});
        }
        var record = labels.get(link), source = clean(link), signature = language + '|' + source;
        if (record.signature === signature) return;
        record.signature = signature;
        var parts = source.match(/^(.+?)\s+[-–—]\s+(.+)$/);
        if (!parts) { record.caption.textContent = source; return; }
        record.caption.replaceChildren(el('strong', '', text()[kind(source) + 'Label']), el('span', '', parts[2]));
    }
    function restoreLabels() {
        labels.forEach((record, link) => {
            record.caption.remove();
            record.attrs.forEach(([name, value]) => { if (value === null) link.removeAttribute(name); else link.setAttribute(name, value); });
        });
        labels.clear();
    }
    // Keep source links and their listeners for Original mode, but prevent programmatic activation in Reading mode too.
    document.addEventListener('click', event => {
        if (state && event.target.closest?.('a[data-univer-lesson-label]')) { event.preventDefault(); event.stopImmediatePropagation(); }
    }, true);
    // Inserts [node] after (or [above]) the cell; inside a table it gets a full-width row of its own.
    function insertAfterCell(cell, node, above) {
        var inserted = node, anchor = cell;
        if (cell.matches('td,th') && cell.parentElement?.tagName === 'TR') {
            var holder = el('td'); inserted = el('tr', 'univer-app-attendance-owned'); anchor = cell.parentElement;
            holder.colSpan = Array.from(anchor.cells).reduce((sum, c) => sum + c.colSpan, 0);
            holder.appendChild(node); inserted.appendChild(holder);
        }
        if (above) anchor.before(inserted); else anchor.after(inserted);
        owned.push(inserted); return inserted;
    }
    function removeOwned() { owned.forEach(node => { window.__univerMotionUI?.stop(node); node.remove(); }); owned = []; }
    function revealOwned(direction) { owned.filter(node => !node.closest('[hidden]')).forEach(node => window.__univerMotionUI?.reveal(node, direction)); }
    // A passive copy of the server's course total; Original restores the unchanged server nodes in their place.
    function overallBlock(group) {
        var source = clean(group.overall), block = el('div', 'univer-app-attendance-overall-block');
        var value = el('p', 'univer-app-attendance-overall-value', source);
        block.append(el('p', 'univer-app-attendance-overall', text().overall), value);
        // Recognized periods are shown as tiles; the server wording stays the fallback for anything else.
        var periods = scores(source);
        if (periods.length) { block.insertBefore(scoreTiles(periods, false), value); value.hidden = true; }
        return block;
    }
    // One class type of a course: a coloured type label, then a compact score chip per control period.
    function typeRow(lessons, type) {
        var values = new Map();
        lessons.forEach(lesson => lesson.tables.forEach(table => sums(table).forEach(entry => {
            if (!values.has(entry.period)) values.set(entry.period, []);
            values.get(entry.period).push(entry.value);
        })));
        var row = el('div', 'univer-app-type-row'), content = [];
        row.dataset.kind = type; row.appendChild(el('span', 'univer-app-type-label', text()[type]));
        values.forEach((items, period) => content.push({label:period, max:'', value:periodTotal(items) || ''}));
        row.appendChild(content.length ? scoreTiles(content, true) : el('span', 'univer-app-type-empty', text().ungraded));
        return row;
    }
    function detailsToggle(group, open) {
        var toggle = el('button', 'univer-app-attendance-details-toggle', open ? text().hideDetails : text().showDetails);
        toggle.type = 'button'; toggle.setAttribute('aria-expanded', String(open));
        toggle.addEventListener('click', event => {
            var table = group.heading.closest('table'), height = table?.getBoundingClientRect().height || 0;
            window.__univerMotionUI?.stop(table);
            expanded.set(group.token, !open); focusToggle = group.token; redraw();
            if (event.detail > 0) { window.__univerMotionUI?.resize(table, height, open ? 180 : 220); revealOwned('content'); }
        });
        return toggle;
    }
    function summary(group, open) {
        var box = el('section', 'univer-app-attendance-summary');
        // The server's total is always hidden: with all classes its passive copy is shown, while a class-type
        // filter is about the class log, where the course total would only repeat what the filter hides.
        if (group.overall) {
            setHidden(boundary(group.overall), true);
            if (selected === 'all') box.appendChild(overallBlock(group));
        }
        var typesBox = el('div', 'univer-app-attendance-type-totals'), title = el('p', 'univer-app-attendance-summary-title', text().totals), any = false;
        box.appendChild(typesBox); typesBox.appendChild(title);
        (selected === 'all' ? TYPES : [selected]).forEach(type => {
            var lessons = group.lessons.filter(l => l.type === type); if (!lessons.length) return;
            any = true; typesBox.appendChild(typeRow(lessons, type));
        });
        if (!any) typesBox.appendChild(el('p', '', selected === 'all' ? text().noClasses : text().empty));
        // Nothing to total: one quiet line instead of an empty two-column card.
        if (!any && !group.overall) { box.classList.add('univer-app-attendance-summary-empty'); title.remove(); }
        if (group.heading && group.lessons.length && selected === 'all') {
            box.appendChild(detailsToggle(group, open));
            if (focusToggle === group.token) { focusToggle = null; box.dataset.univerFocus = 'true'; }
        }
        return insertAfterCell(group.heading || group.lessons[0].cell, box);
    }
    // Adapter-owned updates pause the shared DOM observer, so they never re-trigger adaptation.
    function redraw() { var action = () => render(model()); if (window.__univerMobileV1) window.__univerMobileV1.mutate(action); else action(); }
    function overview(groups) {
        var list = state.overview, courses = groups.filter(group => group.heading), t = text();
        list.replaceChildren(); list.hidden = selectedCourse !== 'all' || courses.length < 2 || selected !== 'all';
        if (list.hidden) return;
        var rows = el('ul', 'univer-app-attendance-overview-list');
        courses.forEach(group => {
            var row = el('li'), name = el('button', 'univer-app-attendance-overview-name', group.name);
            name.type = 'button'; name.addEventListener('click', event => chooseCourse(group.token, event)); row.appendChild(name);
            var periods = group.overall ? scores(clean(group.overall)) : [];
            row.appendChild(periods.length ? scoreTiles(periods, true)
                : el('span', 'univer-app-attendance-overview-empty', group.overall ? clean(group.overall) : t.ungraded));
            rows.appendChild(row);
        });
        list.append(el('p', 'univer-app-attendance-overview-title', t.overview), rows);
    }
    function saveCourse() { try { if (selectedCourse === 'all') sessionStorage.removeItem(courseKey); else sessionStorage.setItem(courseKey, selectedCourse); } catch (_) {} }
    function chooseCourse(token, event) {
        selectedCourse = token; saveCourse(); redraw();
        if (event?.detail > 0) revealOwned('forward');
        if (state.host.getBoundingClientRect().top < 0) state.host.scrollIntoView?.({block:'start'});
    }
    function chooseType(type, event) {
        selected = type; try { sessionStorage.setItem(key, type); } catch (_) {}
        redraw(); if (event.detail > 0) revealOwned('content');
    }
    function courseControls(groups) {
        var courses = groups.filter(group => group.heading), t = text();
        // Текущая аттестация asks for a course by name; it becomes this page's own course choice (or all courses).
        if (selectedCourse.startsWith('name:')) {
            var wanted = selectedCourse.slice(5), match = courses.find(group => group.name.normalize('NFC').toLocaleLowerCase() === wanted);
            selectedCourse = match ? match.token : 'all'; saveCourse();
        }
        if (selectedCourse !== 'all' && !courses.some(group => group.token === selectedCourse)) { selectedCourse = 'all'; saveCourse(); }
        state.courses.hidden = !courses.length; state.courses.setAttribute('aria-label', t.courses);
        setText(state.courseCaption, t.courses); setText(state.allCourse, t.allCourses);
        var keep = new Set();
        courses.forEach((group, index) => {
            keep.add(group.token);
            var option = state.courseOptions.get(group.token);
            if (!option) { option = el('option'); option.value = group.token; state.courseOptions.set(group.token, option); }
            setText(option, group.name);
            var position = state.courseSelect.children[index + 1];
            if (position !== option) state.courseSelect.insertBefore(option, position || null);
        });
        state.courseOptions.forEach((option, token) => { if (!keep.has(token)) { option.remove(); state.courseOptions.delete(token); } });
        state.courseSelect.value = selectedCourse;
        state.courseSelect.setAttribute('aria-label', t.courses);
    }
    // Hides the outermost [elements] wholly inside [start, end) (and before [last], when given).
    function hideRange(elements, start, end, exclude, last) {
        var contained = node => (node === start || before(start, node)) && (!end || (before(node, end) && !node.contains(end))) &&
            (!last || node === last || last.contains(node) || (before(node, last) && !node.contains(last)));
        elements.filter(node => contained(node) && !contained(node.parentElement)).forEach(node => setHidden(node, exclude));
    }
    function render(groups) {
        resetVisibility(); removeOwned();
        courseControls(groups);
        groups.forEach(group => group.lessons.forEach(lesson => { if (lesson.type) makeLabel(lesson.link); }));
        var root = document.querySelector('#l4'), elements = Array.from(root.querySelectorAll('*')), t = text();
        // With all courses listed, each course starts as its summary; one chosen course shows its class log.
        var single = selectedCourse !== 'all' || groups.filter(group => group.heading).length < 2;
        groups.forEach(group => {
            var excludeCourse = selectedCourse !== 'all' && selectedCourse !== group.token;
            // Choosing lectures, seminars or independent work opens every course's log of that type.
            var open = !group.heading || selected !== 'all' || (expanded.has(group.token) ? expanded.get(group.token) : single);
            group.lessons.forEach(lesson => {
                var exclude = excludeCourse || !open || (selected !== 'all' && selected !== lesson.type);
                // Hide only maximal elements wholly inside this lesson's document-order range.
                // This works both with sibling blocks and with legacy tables nesting multiple subjects.
                hideRange(elements, lesson.start, lesson.end, exclude);
            });
            if (excludeCourse && group.heading) hideRange(elements, group.start, group.end, true, !group.end && group.overall ? boundary(group.overall) : null);
            var host = summary(group, open); if (excludeCourse) setHidden(host, true);
        });
        overview(groups);
        state.bar.querySelectorAll('button').forEach(button => { button.textContent = t[button.dataset.kind]; button.setAttribute('aria-pressed', String(selected === button.dataset.kind)); });
        state.bar.setAttribute('aria-label', t.filter);
        // The note explains a type filter, so it appears only while one is active.
        state.caption.textContent = t.filter; state.note.hidden = true;
        state.optionsCaption.textContent = selected === 'all' ? t.filter : t[selected];
        var focused = root.querySelector('.univer-app-attendance-summary[data-univer-focus] .univer-app-attendance-details-toggle');
        if (focused) { focused.closest('.univer-app-attendance-summary').removeAttribute('data-univer-focus'); focused.focus?.({preventScroll:true}); }
    }
    // Course choice, the class-type filter in a disclosure and the course overview, above the first course.
    function buildTools(groups) {
        var bar = el('div', 'univer-app-attendance-filters'), caption = el('span', 'univer-app-attendance-filter-caption');
        bar.setAttribute('role', 'group'); bar.appendChild(caption);
        ['all'].concat(TYPES).forEach(type => {
            var button = el('button'); button.type = 'button'; button.dataset.kind = type;
            button.addEventListener('click', event => chooseType(type, event)); bar.appendChild(button);
        });
        var courses = el('nav', 'univer-app-attendance-courses'), courseCaption = el('p', 'univer-app-attendance-course-caption');
        var courseList = el('div', 'univer-app-attendance-course-list'), courseSelect = el('select'), allCourse = el('option');
        courseSelect.addEventListener('change', () => chooseCourse(courseSelect.value));
        allCourse.value = 'all'; courseSelect.appendChild(allCourse); courseList.appendChild(courseSelect);
        courses.append(courseCaption, courseList);
        var options = el('details', 'univer-app-attendance-options'), optionsCaption = el('summary'), optionsBody = el('div');
        var note = el('p', 'univer-app-attendance-filter-note'), summaryList = el('section', 'univer-app-attendance-overview');
        optionsBody.append(bar, note); options.append(optionsCaption, optionsBody);
        var tools = el('div', 'univer-app-attendance-tools'); tools.append(courses, options, summaryList);
        insertAfterCell(groups[0].heading || groups[0].lessons[0].cell, tools, true);
        // The filter bar has its own lifetime; summary rebuilds must not remove it.
        var host = owned.pop();
        state = {bar, host, caption, note, courses, courseCaption, courseList, courseSelect, optionsCaption, allCourse, courseOptions:new Map(), overview:summaryList, signature:null};
    }
    // Language and every course, lesson and grade table with its text: polling rerenders only when one changes.
    function signatureOf(groups) {
        return language + '|' + groups.map(g => id(g.heading || g.lessons[0].cell) + ':' + (g.token || '') +
            g.lessons.map(l => id(l.link) + clean(l.link) + l.tables.map(t => id(t) + clean(t)).join('|')).join('/') +
            (g.overall ? clean(g.overall) : '')).join(';');
    }
    function apply(lang) {
        language = lang;
        if (document.readyState === 'loading') return;
        if (!/^\/student\/attendance\/(full|show)(\/|$)/.test(location.pathname)) return;
        var groups = model();
        if (state && (!groups.length || !state.host.isConnected)) { release(); groups = model(); }
        if (!groups.length) return;
        var signature = signatureOf(groups);
        if (!state) buildTools(groups);
        if (state.signature !== signature) { state.signature = signature; render(groups); }
    }
    function release() {
        resetVisibility(); removeOwned(); restoreLabels();
        state?.host.remove(); state = null;
    }
    window.__univerAttendanceV1 = {apply, release};
})();
