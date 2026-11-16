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
        en: {all:'All classes', lecture:'Lectures', seminar:'Seminars', sro:'Independent work', filter:'Class type', kind:'Class type', total:'Total', outOf:'of', totals:'Totals by class type', overall:'Overall course total', empty:'No classes of this type', ungraded:'No grades yet', none:'none', note:'The overall course total includes every class type.', lectureLabel:'Lecture', seminarLabel:'Seminar', sroLabel:'Independent work', overview:'Course summary', showDetails:'Show class log', hideDetails:'Hide class log', courses:'Courses', allCourses:'All', noClasses:'No classes yet'},
        kz: {all:'Барлық сабақтар', lecture:'Дәрістер', seminar:'Семинарлар', sro:'СӨЖ', filter:'Сабақ түрі', kind:'Сабақ түрі', total:'Барлығы', outOf:'/', totals:'Сабақ түрлері бойынша қорытынды', overall:'Пән бойынша жалпы қорытынды', empty:'Бұл түрдегі сабақтар жоқ', ungraded:'Бағалар әлі қойылмаған', none:'жоқ', note:'Пән бойынша жалпы қорытынды сабақтардың барлық түрін қамтиды.', lectureLabel:'Дәріс', seminarLabel:'Семинар', sroLabel:'СӨЖ', overview:'Пәндер бойынша қорытынды', showDetails:'Сабақ журналын көрсету', hideDetails:'Сабақ журналын жасыру', courses:'Пәндер', allCourses:'Барлығы', noClasses:'Сабақтар әлі жоқ'},
        ru: {all:'Все занятия', lecture:'Лекции', seminar:'Семинары', sro:'СРО', filter:'Тип занятий', kind:'Вид занятий', total:'Итого', outOf:'из', totals:'Итоги по видам занятий', overall:'Общий итог предмета', empty:'Нет занятий этого вида', ungraded:'Оценки пока не выставлены', none:'нет', note:'Общий итог предмета учитывает все виды занятий.', lectureLabel:'Лекция', seminarLabel:'Семинар', sroLabel:'СРО / СРОП', overview:'Сводка по предметам', showDetails:'Показать журнал занятий', hideDetails:'Скрыть журнал занятий', courses:'Предметы', allCourses:'Все', noClasses:'Занятия пока не добавлены'}
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
    // The chip the home screen shows, so a grade looks alike wherever it is read.
    function gradeChips(list) {
        var box = el('span', 'univer-app-overview-scores');
        list.forEach(item => {
            var chip = el('span', 'univer-app-overview-score');
            chip.setAttribute('role', 'group');
            // The eye reads a dash for a missing grade; a screen reader says the word ("нет", "none", "жоқ").
            chip.setAttribute('aria-label', item.label + ': ' + (item.value || text().none) + (item.max ? ' / ' + item.max : ''));
            chip.append(el('span', 'univer-app-overview-score-label', item.label), el('span', 'univer-app-overview-score-value', item.value || '–'));
            var bar = scoreBar('univer-app-overview-score-bar', item.value, item.max); if (bar) chip.appendChild(bar);
            // The chip's label is what a screen reader announces; its parts are for the eye only.
            Array.from(chip.children).forEach(child => child.setAttribute('aria-hidden', 'true'));
            box.appendChild(chip);
        });
        return box;
    }
    // A grade's share of its maximum as a filled bar ("12,5" of "25" fills half); null when either is not a number.
    function scoreBar(className, value, max) {
        var ratio = max && value ? Number(value.replace(',', '.')) / Number(max.replace(',', '.')) : NaN;
        if (!isFinite(ratio)) return null;
        var bar = el('span', className); bar.setAttribute('aria-hidden', 'true');
        bar.style.setProperty('--univer-score', Math.round(Math.max(0, Math.min(1, ratio)) * 1000) / 10 + '%');
        return bar;
    }
    function withRole(node, role) { node.setAttribute('role', role); return node; }
    function cell(className, content) { return withRole(el('span', className, content), 'cell'); }
    // A period's name without its "(100)".
    function periodKey(value) { return value.replace(/\s*\(.*$/, '').replace(/\s+/g, ''); }
    // Rows are class types, columns the control periods, the last row the course total: one number per place.
    function scoreTable(columns, rows, periods) {
        var t = text(), table = withRole(el('div', 'univer-app-score-table'), 'table'), head = withRole(el('div', 'univer-app-score-head'), 'row');
        table.setAttribute('aria-label', t.totals);
        table.style.setProperty('--univer-columns', String(columns.length));
        head.appendChild(withRole(el('span', '', t.kind), 'columnheader'));
        columns.forEach(column => {
            var name = withRole(el('span', '', column.label), 'columnheader');
            if (column.max) name.appendChild(el('small', '', t.outOf + ' ' + column.max));
            head.appendChild(name);
        });
        table.appendChild(head);
        rows.forEach(item => {
            var row = withRole(el('div', 'univer-app-type-row'), 'row');
            row.dataset.kind = item.type; row.appendChild(withRole(el('span', 'univer-app-type-label', t[item.type]), 'rowheader'));
            columns.forEach(column => {
                var value = item.values.get(column.key)?.value || '', score = cell('univer-app-score', value || '–');
                score.setAttribute('aria-label', column.label + (column.max ? ' (' + column.max + ')' : '') + ': ' + (value || t.none));
                row.appendChild(score);
            });
            table.appendChild(row);
        });
        if (periods.length) {
            var total = withRole(el('div', 'univer-app-score-total'), 'row');
            total.appendChild(withRole(el('span', '', t.total), 'rowheader'));
            columns.forEach(column => {
                var found = periods.find(p => periodKey(p.label) === column.key), value = found?.value || '', score = cell('univer-app-score');
                score.setAttribute('aria-label', t.total + ', ' + column.label + ': ' + (value || t.none) + (found?.max ? ' / ' + found.max : ''));
                score.appendChild(el('b', 'univer-app-score-value', value || '–'));
                var bar = scoreBar('univer-app-score-bar', value, found?.max); if (bar) score.appendChild(bar);
                total.appendChild(score);
            });
            table.appendChild(total);
        }
        return table;
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
        var parts = source.match(/^(.+?)\s+[-–—]\s+(.+)$/);
        // Emptied and refilled rather than replaceChildren(), which Chromium 80-85 lacks.
        record.caption.textContent = parts ? '' : source;
        if (parts) record.caption.append(el('strong', '', text()[kind(source) + 'Label']), el('span', '', parts[2]));
        // Only a caption that was really written counts as current.
        record.signature = signature;
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
    // The sums of one class type by control period, keyed by the period's name.
    function typeTotals(lessons) {
        var values = new Map(), totals = new Map();
        lessons.forEach(lesson => lesson.tables.forEach(table => sums(table).forEach(entry => {
            if (!values.has(entry.period)) values.set(entry.period, []);
            values.get(entry.period).push(entry.value);
        })));
        values.forEach((items, period) => totals.set(periodKey(period), {period, value:periodTotal(items) || ''}));
        return totals;
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
        var box = el('section', 'univer-app-attendance-summary'), periods = [], rows = [], any = false, t = text();
        // The server's total is always hidden: with all classes its passive copy is shown, while a class-type
        // filter is about the class log, where the course total would only repeat what the filter hides.
        if (group.overall) {
            setHidden(boundary(group.overall), true);
            if (selected === 'all') periods = scores(clean(group.overall));
        }
        (selected === 'all' ? TYPES : [selected]).forEach(type => {
            var lessons = group.lessons.filter(l => l.type === type); if (!lessons.length) return;
            any = true; rows.push({type, values:typeTotals(lessons)});
        });
        var columns = periods.map(p => ({key:periodKey(p.label), label:p.label, max:p.max}));
        rows.forEach(row => row.values.forEach((entry, name) => {
            if (!columns.some(c => c.key === name)) columns.push({key:name, label:name, max:entry.period.match(/\(\s*(\d+(?:[.,]\d+)?)\s*\)/)?.[1] || ''});
        }));
        if (columns.length) box.appendChild(scoreTable(columns, rows, periods));
        else rows.forEach(item => {
            var row = el('div', 'univer-app-type-row'); row.dataset.kind = item.type;
            row.append(el('span', 'univer-app-type-label', t[item.type]), el('span', 'univer-app-type-empty', t.ungraded));
            box.appendChild(row);
        });
        // Anything the server wrote that is not a recognised period stays readable as its own line.
        if (group.overall && selected === 'all' && !periods.length) box.appendChild(el('p', 'univer-app-attendance-overall-value', clean(group.overall)));
        if (!any) box.appendChild(el('p', 'univer-app-attendance-nothing', selected === 'all' ? t.noClasses : t.empty));
        // Nothing to total: one quiet line instead of an empty card.
        if (!any && !group.overall) box.classList.add('univer-app-attendance-summary-empty');
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
        list.textContent = ''; list.hidden = selectedCourse !== 'all' || courses.length < 2 || selected !== 'all';
        if (list.hidden) return;
        var rows = el('ul', 'univer-app-attendance-overview-list');
        courses.forEach(group => {
            var row = el('li'), name = el('button', 'univer-app-attendance-overview-name', group.name);
            name.type = 'button'; name.addEventListener('click', event => chooseCourse(group.token, event)); row.appendChild(name);
            var periods = group.overall ? scores(clean(group.overall)) : [];
            row.appendChild(periods.length ? gradeChips(periods)
                : el('span', 'univer-app-attendance-overview-empty', group.overall ? clean(group.overall) : t.ungraded));
            rows.appendChild(row);
        });
        list.append(el('p', 'univer-app-attendance-overview-title', t.overview), rows);
    }
    function saveCourse() { try { if (selectedCourse === 'all') sessionStorage.removeItem(courseKey); else sessionStorage.setItem(courseKey, selectedCourse); } catch (_) {} }
    function chooseCourse(token, event) {
        // A name in the course summary is removed by the redraw (the summary hides once one course is chosen).
        var fromOverview = !!state && state.overview.contains(document.activeElement);
        selectedCourse = token; saveCourse(); redraw();
        if (event?.detail > 0) revealOwned('forward');
        // Keyboard and TalkBack focus moves to the chosen course's chip instead of falling back to the page.
        var at = document.activeElement;
        if (fromOverview && state && (!at || at === document.body || !at.isConnected)) {
            var chip = token === 'all' ? state.allCourse : state.courseButtons.get(token);
            if (chip?.isConnected) chip.focus({preventScroll:true});
        }
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
        state.allCourse.setAttribute('aria-pressed', String(selectedCourse === 'all'));
        var keep = new Set();
        courses.forEach((group, index) => {
            keep.add(group.token);
            var button = state.courseButtons.get(group.token);
            if (!button) {
                button = el('button'); button.type = 'button'; button.dataset.course = group.token;
                button.addEventListener('click', event => chooseCourse(group.token, event)); state.courseButtons.set(group.token, button);
            }
            setText(button, group.name); button.setAttribute('aria-pressed', String(selectedCourse === group.token));
            var position = state.courseList.children[index + 1];
            if (position !== button) state.courseList.insertBefore(button, position || null);
        });
        state.courseButtons.forEach((button, token) => { if (!keep.has(token)) { button.remove(); state.courseButtons.delete(token); } });
        // The row scrolls sideways on a phone; a new choice is brought into sight. Only a new one: a late AJAX redraw
        // must not snap back a row the student has just swiped to look at the other courses.
        var chosen = state.courseList.querySelector('button[aria-pressed=true]');
        if (chosen && state.courseShown !== selectedCourse) {
            // The first pass runs before the app style turns the chips into one scrolling row: a course chosen on another
            // page (the home overview, Текущая аттестация) is centred once the row can scroll, not lost on the wrapped one.
            var list = state.courseList, wanted = selectedCourse, owner = state;
            var centre = function () {
                if (state !== owner || selectedCourse !== wanted || owner.courseShown === wanted || !chosen.isConnected) return;
                if (!chosen.offsetWidth || list.scrollWidth <= list.clientWidth) return; // Hidden or wrapped: nothing to scroll yet.
                owner.courseShown = wanted;
                list.scrollLeft = Math.max(0, chosen.offsetLeft - (list.clientWidth - chosen.offsetWidth) / 2);
            };
            centre();
            if (typeof requestAnimationFrame === 'function') requestAnimationFrame(centre);
            setTimeout(centre, 120); // A hidden WebView throttles animation frames.
        }
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
        state.caption.textContent = t.filter; state.note.textContent = t.note; state.note.hidden = selected === 'all';
        var focused = root.querySelector('.univer-app-attendance-summary[data-univer-focus] .univer-app-attendance-details-toggle');
        if (focused) { focused.closest('.univer-app-attendance-summary').removeAttribute('data-univer-focus'); focused.focus?.({preventScroll:true}); }
    }
    // The course chips, the class-type segment and the course overview, above the first course.
    function buildTools(groups) {
        var bar = el('div', 'univer-app-attendance-filters'), caption = el('p', 'univer-app-attendance-filter-caption');
        bar.setAttribute('role', 'group');
        ['all'].concat(TYPES).forEach(type => {
            var button = el('button'); button.type = 'button'; button.dataset.kind = type;
            button.addEventListener('click', event => chooseType(type, event)); bar.appendChild(button);
        });
        var courses = el('nav', 'univer-app-attendance-courses'), courseCaption = el('p', 'univer-app-attendance-course-caption');
        var courseList = el('div', 'univer-app-attendance-course-list'), allCourse = el('button');
        allCourse.type = 'button'; allCourse.dataset.course = 'all'; allCourse.addEventListener('click', event => chooseCourse('all', event));
        courseList.appendChild(allCourse); courses.append(courseCaption, courseList);
        var note = el('p', 'univer-app-attendance-filter-note'), summaryList = el('section', 'univer-app-attendance-overview');
        var tools = el('div', 'univer-app-attendance-tools'); tools.append(courses, caption, bar, note, summaryList);
        insertAfterCell(groups[0].heading || groups[0].lessons[0].cell, tools, true);
        // The filter bar has its own lifetime; summary rebuilds must not remove it.
        var host = owned.pop();
        state = {bar, host, caption, note, courses, courseCaption, courseList, allCourse, courseButtons:new Map(), overview:summaryList, signature:null};
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
        // The signature is kept only after a complete render, so a failed one is retried on the next pass.
        if (state.signature !== signature) { render(groups); state.signature = signature; }
    }
    function release() {
        resetVisibility(); removeOwned(); restoreLabels();
        state?.host.remove(); state = null;
    }
    window.__univerAttendanceV1 = {apply, release};
})();
