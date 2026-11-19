/* The journal's course totals from any document: the page itself, or a copy the app reads in the background on
   about:blank. No origin check and no page state, so both read by the same rules the journal below uses. */
(function () {
    'use strict';
    if (window.__univerGradeReadV1) return;
    function clean(node) { return (typeof node === 'string' ? node : node?.textContent || '').replace(/\s+/g, ' ').trim(); }
    // Server totals read as "РК1 (100): 51 РК2 (100): 0"; each control period becomes a score tile.
    function scores(value) {
        var found = [], pattern = /((?:РК|RK|МБ|АБ)\s*[12]|(?:Экз|Exam|Емтихан|Итог|Total)\p{L}*)\s*(?:\(\s*(\d+(?:[.,]\d+)?)\s*\))?\s*:\s*(-?\d+(?:[.,]\d+)?)?/giu, match;
        while ((match = pattern.exec(value))) found.push({label:match[1].replace(/\s+/g, ''), max:match[2] || '', value:match[3] || ''});
        return found;
    }
    // Observed course headings contain the teaching date range; summary cells contain bold RK totals.
    function isCourse(cell) { return /\d{2}\.\d{2}\.\d{4}\s*[-–]\s*\d{2}\.\d{2}\.\d{4}/.test(clean(cell)); }
    function isOverall(cell) { return !!cell.querySelector('b,strong') && /(?:РК|RK|МБ|АБ)\s*[12]/i.test(clean(cell)); }
    // The heading's text before its group list or dates.
    function courseName(source) { return source.match(/^(.*?)\s*(?:\(\[|\d{2}\.\d{2}\.\d{4})/)?.[1]?.trim() || source; }
    // The academic year and term a journal shows: from its own address, or between the previous and next terms it
    // links to (/full/2025/2 and /full/2026/2 surround 2026/1). Null when neither says.
    function term(doc, path) {
        var own = String(path || '').match(/\/full\/(\d{4})\/([123])\/?$/);
        if (own) return {year:Number(own[1]), term:Number(own[2])};
        var steps = Array.from(doc.querySelectorAll('#l4 a[href*="/student/attendance/full/"]'), link => {
            var found = (link.getAttribute('href') || '').match(/\/full\/(\d{4})\/([12])\/?$/);
            return found ? Number(found[1]) * 2 + Number(found[2]) - 1 : NaN;
        }).filter(isFinite);
        var low = Math.min.apply(null, steps), high = Math.max.apply(null, steps);
        if (!steps.length || high - low !== 2) return null;
        var middle = low + 1;
        return {year:Math.floor(middle / 2), term:middle % 2 + 1};
    }
    // Each course's control-period totals, in the site's language; null without the journal's grade area.
    function read(doc, path) {
        var root = doc.querySelector('#l4'); if (!root) return null;
        var courses = [], course = null;
        root.querySelectorAll('.ct').forEach(cell => {
            if (cell.closest('#tools,.univer-app-attendance-summary')) return;
            if (isCourse(cell)) courses.push(course = {name:courseName(clean(cell.querySelector('.univer-app-course-source') || cell)).slice(0, 200), scores:[]});
            else if (course && isOverall(cell)) course.scores = scores(clean(cell)).slice(0, 8).map(item => ({label:item.label, max:item.max, value:item.value}));
        });
        return {v:1, term:term(doc, path), lang:(doc.documentElement?.getAttribute('lang') || '').slice(0, 5), courses:courses.slice(0, 60)};
    }
    window.__univerGradeReadV1 = {read, scores, isCourse, isOverall, courseName, term};
})();

/* Attendance filters hide complete lesson sections; the site's matrices and overall grades stay intact. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerAttendanceV1) return;
    var dom = window.__univerDomV1, clean = dom.clean, ownRows = dom.ownRows, el = dom.el, setText = dom.setText;
    var reader = window.__univerGradeReadV1, scores = reader.scores;
    var selected = 'all', language = 'ru', state = null, selectedCourse = 'all';
    var hidden = new Map(), labels = new Map(), owned = [], identities = new WeakMap(), sequence = 0;
    // The user's own choice per course; without one, details open only when a single course is shown.
    var expanded = new Map(), focusToggle = null;
    var TYPES = ['lecture', 'seminar', 'sro'];
    var WORDS = {
        en: {all:'All classes', lecture:'Lectures', seminar:'Seminars', sro:'Independent work', filter:'Class type', kind:'Class type', total:'Total', outOf:'of', totals:'Totals by class type', overall:'Overall course total', empty:'No classes of this type', ungraded:'No grades yet', none:'none', note:'The overall course total includes every class type.', lectureLabel:'Lecture', seminarLabel:'Seminar', sroLabel:'Independent work', overview:'Course summary', showDetails:'Show class log', hideDetails:'Hide class log', courses:'Courses', allCourses:'All', noClasses:'No classes yet', low:'Below 50', pass:'Satisfactory', good:'Good', top:'Excellent', short:'{left} short of {at}', reached:'Every mark reached', goal:'Goal', noGoal:'None', goalReached:'Goal reached', absences:'Absences', absenceKinds:{lecture:'lectures', seminar:'seminars', sro:'independent work', other:'other'}, fresh:'new', freshSaid:'new since your last visit', legend:'Score colours', legendLow:'below 50: short', legendPass:'50–69: satisfactory, scholarship at risk', legendGood:'70–89: good', legendTop:'90 and above: excellent', report:'Share grades: PDF or image'},
        kz: {all:'Барлық сабақтар', lecture:'Дәрістер', seminar:'Семинарлар', sro:'СӨЖ', filter:'Сабақ түрі', kind:'Сабақ түрі', total:'Барлығы', outOf:'/', totals:'Сабақ түрлері бойынша қорытынды', overall:'Пән бойынша жалпы қорытынды', empty:'Бұл түрдегі сабақтар жоқ', ungraded:'Бағалар әлі қойылмаған', none:'жоқ', note:'Пән бойынша жалпы қорытынды сабақтардың барлық түрін қамтиды.', lectureLabel:'Дәріс', seminarLabel:'Семинар', sroLabel:'СӨЖ', overview:'Пәндер бойынша қорытынды', showDetails:'Сабақ журналын көрсету', hideDetails:'Сабақ журналын жасыру', courses:'Пәндер', allCourses:'Барлығы', noClasses:'Сабақтар әлі жоқ', low:'Балл жетпейді', pass:'Қанағаттанарлық', good:'Жақсы', top:'Өте жақсы', short:'{at} шегіне {left} жетпейді', reached:'Барлық шектен өтті', goal:'Мақсат', noGoal:'Жоқ', goalReached:'Мақсатқа жетті', absences:'Қатыспаған сабақтар', absenceKinds:{lecture:'дәріс', seminar:'семинар', sro:'СӨЖ', other:'басқа'}, fresh:'жаңа', freshSaid:'соңғы кіруден кейін жаңа', legend:'Балл түстері', legendLow:'50-ден төмен: балл жетпейді', legendPass:'50–69: қанағаттанарлық, стипендия қауіпте', legendGood:'70–89: жақсы', legendTop:'90 және жоғары: өте жақсы', report:'Үлгерімді бөлісу: PDF не сурет'},
        ru: {all:'Все занятия', lecture:'Лекции', seminar:'Семинары', sro:'СРО', filter:'Тип занятий', kind:'Вид занятий', total:'Итого', outOf:'из', totals:'Итоги по видам занятий', overall:'Общий итог предмета', empty:'Нет занятий этого вида', ungraded:'Оценки пока не выставлены', none:'нет', note:'Общий итог предмета учитывает все виды занятий.', lectureLabel:'Лекция', seminarLabel:'Семинар', sroLabel:'СРО / СРОП', overview:'Сводка по предметам', showDetails:'Показать журнал занятий', hideDetails:'Скрыть журнал занятий', courses:'Предметы', allCourses:'Все', noClasses:'Занятия пока не добавлены', low:'Недобор', pass:'Удовлетворительно', good:'Хорошо', top:'Отлично', short:'ещё {left} до {at}', reached:'Все пороги пройдены', goal:'Цель', noGoal:'Нет', goalReached:'Цель достигнута', absences:'Пропуски', absenceKinds:{lecture:'лекции', seminar:'семинары', sro:'СРО', other:'другие'}, fresh:'новое', freshSaid:'новое с прошлого захода', legend:'Цвета баллов', legendLow:'ниже 50: недобор', legendPass:'50–69: удовлетворительно, стипендия под риском', legendGood:'70–89: хорошо', legendTop:'90 и выше: отлично', report:'Поделиться успеваемостью: PDF или картинка'}
    };
    // The journal opens with every course and class type each time; the choice is not kept. A tap on a course in the
    // home overview or in Текущая аттестация hands that course over once: it is read and removed here, so a refresh or
    // a later visit shows everything again. Earlier versions also kept the class type and the last course chosen.
    try {
        var path = location.pathname.replace(/\/$/, ''), course = null;
        [path + '/', path].forEach(function (variant) {
            var name = 'univer.mobile.attendance-course.v1:' + variant, value = sessionStorage.getItem(name);
            if (course === null && value) course = value;
            sessionStorage.removeItem(name);
        });
        sessionStorage.removeItem('univer.mobile.attendance-kind.v1');
        if (course && course.length <= 1000) selectedCourse = course;
    } catch (_) {}
    function text() { return WORDS[language] || WORDS.ru; }
    // Absences are the journal's own «н» marks in a class's date cells; anything else in a cell is not counted.
    function absences(table) {
        var count = 0;
        ownRows(table).forEach(row => Array.from(row.cells).forEach(cell => { if (cell.tagName === 'TD' && /^н$/i.test(clean(cell))) count++; }));
        return count;
    }
    // Every class counts (labs and practicals with no known type too); a type counts only its own.
    function missed(group, type) {
        return group.lessons.filter(lesson => !type || lesson.type === type)
            .reduce((sum, lesson) => sum + lesson.tables.reduce((n, table) => n + absences(table), 0), 0);
    }
    // «Новое»: the course totals as they stood at the previous visit (base) and now (current), per term and account.
    // A visit is an app session (a sessionStorage mark) or 12 hours: a reload within one keeps its marks, the next visit
    // starts from what this one showed. Nothing is new on the first visit or for another account.
    var SEEN_KEY = 'univer.app.grades-seen.v1', VISIT_KEY = 'univer.app.grades-visit.v1', VISIT_AGE = 12 * 3600000, fresh = new Set();
    function seenKey(name, label) { return name.normalize('NFC').toLocaleLowerCase() + '|' + label; }
    function isFresh(name, label) { return fresh.has(seenKey(name, label)); }
    function freshness(groups) {
        if (!/^\/student\/attendance\/full(\/|$)/.test(location.pathname)) return;
        var values = {}, count = 0;
        groups.forEach(group => { if (group.heading && group.overall) scores(clean(group.overall)).forEach(item => { values[seenKey(group.name, item.label)] = item.value; count++; }); });
        if (!count) return;
        var owner = '', store = null, visited = false, now = Date.now(), term = termOf(), key = term ? term.year + '/' + term.term : 'current';
        try { owner = localStorage.getItem('univer.app.account.v1') || ''; store = JSON.parse(localStorage.getItem(SEEN_KEY) || 'null'); } catch (_) { store = null; }
        try { visited = sessionStorage.getItem(VISIT_KEY) === '1'; } catch (_) {}
        if (!store || typeof store !== 'object' || store.owner !== owner || !store.terms || typeof store.terms !== 'object') store = {owner, at:0, terms:{}};
        if (!visited || !(now - store.at >= 0 && now - store.at < VISIT_AGE)) {
            Object.keys(store.terms).forEach(name => { var entry = store.terms[name]; if (entry && entry.current) entry.base = entry.current; });
            store.at = now;
            try { sessionStorage.setItem(VISIT_KEY, '1'); } catch (_) {}
        }
        var entry = store.terms[key] && typeof store.terms[key] === 'object' ? store.terms[key] : (store.terms[key] = {});
        var base = entry.base && typeof entry.base === 'object' ? entry.base : null;
        fresh = new Set(base ? Object.keys(values).filter(name => values[name] && base[name] !== values[name]) : []);
        entry.current = values;
        var terms = Object.keys(store.terms); if (terms.length > 6) terms.filter(name => name !== key).slice(0, terms.length - 6).forEach(name => delete store.terms[name]);
        try { localStorage.setItem(SEEN_KEY, JSON.stringify(store)); } catch (_) {}
    }
    // The chip the home screen shows, so a grade looks alike wherever it is read.
    function gradeChips(list, name) {
        var box = el('span', 'univer-app-overview-scores');
        list.forEach(item => {
            var chip = el('span', 'univer-app-overview-score'), isNew = isFresh(name, item.label);
            chip.setAttribute('role', 'group');
            if (isNew) chip.dataset.univerFresh = 'true';
            // The eye reads a dash for a missing grade; a screen reader says the word ("нет", "none", "жоқ").
            chip.setAttribute('aria-label', item.label + ': ' + (item.value || text().none) + (item.max ? ' / ' + item.max : '') + (isNew ? ', ' + text().freshSaid : ''));
            chip.append(el('span', 'univer-app-overview-score-label', item.label), el('span', 'univer-app-overview-score-value', item.value || '–'));
            var bar = scoreBar('univer-app-overview-score-bar', item.value, item.max); if (bar) chip.appendChild(bar);
            band(chip, item.value, item.max);
            // The chip's label is what a screen reader announces; its parts are for the eye only.
            Array.from(chip.children).forEach(child => child.setAttribute('aria-hidden', 'true'));
            box.appendChild(chip);
        });
        return box;
    }
    // A grade's share of its maximum as a filled bar ("12,5" of "25" fills half), coloured by its band; null when
    // either is not a number.
    function scoreBar(className, value, max) {
        var found = dom.scoreBand(value, max);
        if (!found) return null;
        var bar = el('span', className); bar.setAttribute('aria-hidden', 'true');
        bar.style.setProperty('--univer-score', found.percent + '%'); bar.dataset.band = found.band;
        return bar;
    }
    function band(node, value, max) { var found = dom.scoreBand(value, max); if (found) node.dataset.band = found.band; return found; }
    function number(value) { return language === 'en' ? String(value).replace(',', '.') : String(value).replace('.', ','); }
    function words(template, values) { return template.replace(/\{(\w+)\}/g, (_, key) => values[key]); }
    // One control period's total against the marks 50, 70 and 90: its band in words, a bar with the marks drawn on it,
    // and how far the next mark is. Colour is never the only signal; a missing grade is one quiet line.
    function progress(item, name) {
        var t = text(), found = dom.scoreBand(item.value, item.max, dom.goal()), box = el('div', 'univer-app-progress');
        box.setAttribute('role', 'group');
        var head = el('div', 'univer-app-progress-head'), value = el('span', 'univer-app-progress-value'), isNew = isFresh(name, item.label);
        value.append(el('b', '', item.value ? number(item.value) : '–'));
        if (item.max) value.append(' ' + t.outOf + ' ' + number(item.max));
        head.append(el('span', 'univer-app-progress-label', item.label));
        if (isNew) head.appendChild(el('span', 'univer-app-fresh', t.fresh));
        head.appendChild(value);
        box.appendChild(head);
        if (!found) {
            box.setAttribute('aria-label', item.label + ': ' + (item.value || t.none) + (isNew ? '. ' + t.freshSaid : ''));
            head.setAttribute('aria-hidden', 'true');
            return box;
        }
        box.dataset.band = found.band;
        var state = el('span', 'univer-app-progress-band', t[found.band]);
        head.insertBefore(state, head.children[1]);
        var track = el('div', 'univer-app-progress-track'), scale = el('div', 'univer-app-progress-scale');
        track.style.setProperty('--univer-score', found.percent + '%');
        [50, 70, 90].forEach((at, index) => {
            var tick = el('i'); tick.style.setProperty('--at', at + '%');
            if (found.percent >= at) tick.className = 'univer-app-progress-passed';
            var mark = el('span', '', number(found.marks[index])); mark.style.setProperty('--at', at + '%');
            track.appendChild(tick); scale.appendChild(mark);
        });
        track.appendChild(el('span', 'univer-app-progress-fill'));
        var next = el('p', 'univer-app-progress-next', found.next === null ? (found.reached ? t.goalReached : t.reached)
            : words(t.short, {at:number(found.next), left:number(found.left)}));
        // What is left is the part a parent looks for: it stands out; a reached goal or the top band is plain.
        if (found.next !== null) next.dataset.univerLeft = 'true';
        box.append(track, scale, next);
        box.setAttribute('aria-label', [item.label + ': ' + number(item.value) + (item.max ? ' ' + t.outOf + ' ' + number(item.max) : ''), t[found.band], next.textContent]
            .concat(isNew ? [t.freshSaid] : []).join('. '));
        Array.from(box.children).forEach(child => child.setAttribute('aria-hidden', 'true'));
        return box;
    }
    function legend() {
        var t = text(), box = el('p', 'univer-app-band-legend');
        box.appendChild(el('span', 'univer-app-band-legend-title', t.legend));
        [['low', t.legendLow], ['pass', t.legendPass], ['good', t.legendGood], ['top', t.legendTop]].forEach(([key, label]) => {
            var item = el('span', 'univer-app-band-legend-item', label); item.dataset.band = key; box.appendChild(item);
        });
        return box;
    }
    function withRole(node, role) { node.setAttribute('role', role); return node; }
    function cell(className, content) { return withRole(el('span', className, content), 'cell'); }
    // A period's name without its "(100)".
    function periodKey(value) { return value.replace(/\s*\(.*$/, '').replace(/\s+/g, ''); }
    // Rows are class types, columns the control periods: one number per place. The course total follows as progress.
    function scoreTable(columns, rows) {
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
            if (reader.isCourse(cell)) events.push({tag:'course', cell, start:boundary(cell)});
            else if (reader.isOverall(cell)) events.push({tag:'overall', cell, start:boundary(cell)});
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
                var name = reader.courseName(source);
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
        if (columns.length && rows.length) box.appendChild(scoreTable(columns, rows));
        else if (!columns.length) rows.forEach(item => {
            var row = el('div', 'univer-app-type-row'); row.dataset.kind = item.type;
            row.append(el('span', 'univer-app-type-label', t[item.type]), el('span', 'univer-app-type-empty', t.ungraded));
            box.appendChild(row);
        });
        if (periods.length) {
            var totals = el('div', 'univer-app-progress-list');
            totals.appendChild(el('p', 'univer-app-progress-title', t.total));
            periods.forEach(item => totals.appendChild(progress(item, group.name)));
            box.appendChild(totals);
        }
        // The journal's «н» marks of the classes shown: every class with all types, the chosen type alone.
        var gone = missed(group, selected === 'all' ? '' : selected);
        if (gone > 0) {
            var lost = el('p', 'univer-app-attendance-absences', t.absences + ': '); lost.appendChild(el('b', '', String(gone)));
            if (selected === 'all') {
                var parts = TYPES.concat(['']).map(type => [type, group.lessons.filter(l => type ? l.type === type : !TYPES.includes(l.type))
                    .reduce((n, lesson) => n + lesson.tables.reduce((m, table) => m + absences(table), 0), 0)]).filter(([, n]) => n > 0);
                if (parts.length > 1 || (parts.length === 1 && parts[0][0])) lost.appendChild(el('span', '', ' · ' + parts.map(([type, n]) => t.absenceKinds[type || 'other'] + ' ' + n).join(', ')));
            }
            box.appendChild(lost);
        }
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
    function redraw() {
        var action = () => { var groups = model(), signature = signatureOf(groups); render(groups); if (state) state.signature = signature; };
        if (window.__univerMobileV1) window.__univerMobileV1.mutate(action); else action();
    }
    // Another goal: every hint counts what is left to it (the report reads it when it is made).
    dom.onGoal(() => { if (state) redraw(); });
    function overview(groups) {
        var list = state.overview, courses = groups.filter(group => group.heading), t = text();
        list.textContent = ''; list.hidden = selectedCourse !== 'all' || courses.length < 2 || selected !== 'all';
        if (list.hidden) return;
        var rows = el('ul', 'univer-app-attendance-overview-list');
        courses.forEach(group => {
            var row = el('li'), name = el('button', 'univer-app-attendance-overview-name', group.name);
            name.type = 'button'; name.addEventListener('click', event => chooseCourse(group.token, event)); row.appendChild(name);
            var periods = group.overall ? scores(clean(group.overall)) : [];
            row.appendChild(periods.length ? gradeChips(periods, group.name)
                : el('span', 'univer-app-attendance-overview-empty', group.overall ? clean(group.overall) : t.ungraded));
            rows.appendChild(row);
        });
        list.append(el('p', 'univer-app-attendance-overview-title', t.overview), rows, legend());
    }
    function chooseCourse(token, event) {
        // A name in the course summary is removed by the redraw (the summary hides once one course is chosen).
        var fromOverview = !!state && state.overview.contains(document.activeElement);
        selectedCourse = token; redraw();
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
        selected = type;
        redraw(); if (event.detail > 0) revealOwned('content');
    }
    function courseControls(groups) {
        var courses = groups.filter(group => group.heading), t = text();
        // Текущая аттестация asks for a course by name; it becomes this page's own course choice (or all courses).
        if (selectedCourse.startsWith('name:')) {
            var wanted = selectedCourse.slice(5), match = courses.find(group => group.name.normalize('NFC').toLocaleLowerCase() === wanted);
            selectedCourse = match ? match.token : 'all';
        }
        if (selectedCourse !== 'all' && !courses.some(group => group.token === selectedCourse)) { selectedCourse = 'all'; }
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
        setText(state.share, t.report);
        var graded = groups.some(group => group.heading && group.overall && scores(clean(group.overall)).length);
        dom.setHidden(state.share, typeof __univerNative !== 'function' || !/^\/student\/attendance\/full(\/|$)/.test(location.pathname) || !graded);
        dom.setHidden(state.goal, !graded || selected !== 'all');
        setText(state.goalCaption, t.goal); state.goal.setAttribute('aria-label', t.goal);
        state.goal.querySelectorAll('button[data-goal]').forEach(button => {
            var name = button.dataset.goal;
            setText(button, name === 'none' ? t.noGoal : t[name]);
            button.setAttribute('aria-pressed', String((dom.goal() || 'none') === name));
        });
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
        // The report for printing or sending (a parent, a curator) is drawn by the app from every course's totals.
        var share = el('button', 'univer-app-attendance-report'); share.type = 'button'; share.hidden = true;
        share.addEventListener('click', event => { if (event.isTrusted && typeof __univerNative === 'function') __univerNative('univer-report:' + JSON.stringify(reportData())); });
        // The level to aim at (a parent's question: how far is «Отлично»?). The hints and the report count to it.
        var goal = el('div', 'univer-app-goal'), goalCaption = el('p', 'univer-app-goal-caption'), goalList = el('div', 'univer-app-goal-list');
        goal.setAttribute('role', 'group'); goal.hidden = true; goalCaption.setAttribute('aria-hidden', 'true');
        ['none', 'pass', 'good', 'top'].forEach(name => {
            var button = el('button'); button.type = 'button'; button.dataset.goal = name;
            button.addEventListener('click', () => dom.setGoal(name === 'none' ? null : name)); goalList.appendChild(button);
        });
        goal.append(goalCaption, goalList);
        var tools = el('div', 'univer-app-attendance-tools'); tools.append(courses, caption, bar, note, goal, share, summaryList);
        insertAfterCell(groups[0].heading || groups[0].lessons[0].cell, tools, true);
        // The filter bar has its own lifetime; summary rebuilds must not remove it.
        var host = owned.pop();
        state = {bar, host, caption, note, share, goal, goalCaption, courses, courseCaption, courseList, allCourse, courseButtons:new Map(), overview:summaryList, signature:null};
    }
    function termOf() { return reader.term(document, location.pathname); }
    // What the app's report needs and nothing more: the term, the name shown in the site header and each course's
    // control-period totals, whatever course or class type is chosen. Native code checks every field again.
    function reportData() {
        var name = document.querySelector('#l2 a[href="/user/pass/"]');
        return {
            // The header reads "Surname Name (login: id)": the report names the student only.
            v:1, term:termOf(), student:name ? clean(name).replace(/\s*\([^()]*\)$/, '').slice(0, 120) : '',
            courses:model().filter(group => group.heading).slice(0, 60).map(group => ({
                name:group.name.slice(0, 200),
                scores:(group.overall ? scores(clean(group.overall)) : []).slice(0, 8).map(item => ({label:item.label, max:item.max, value:item.value})),
                absences:Math.min(999, missed(group)),
            })),
            goal:dom.goal(),
        };
    }
    // Language and every course, lesson and grade table with its text: polling rerenders only when one changes.
    function signatureOf(groups) {
        return language + '|' + (dom.goal() || '') + '|' + groups.map(g => id(g.heading || g.lessons[0].cell) + ':' + (g.token || '') +
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
        if (state.signature !== signature) freshness(groups);
        // The signature is kept only after a complete render, so a failed one is retried on the next pass.
        if (state.signature !== signature) { render(groups); state.signature = signature; }
    }
    function release() {
        resetVisibility(); removeOwned(); restoreLabels();
        state?.host.remove(); state = null;
    }
    window.__univerAttendanceV1 = {apply, release};
})();
