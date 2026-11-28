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
    function canAct() {
        if (typeof __univerActions === 'boolean') return __univerActions;
        return typeof __univerNative === 'function';
    }
    function deliver(prefix, value) {
        if (typeof __univerPost === 'function') { __univerPost(prefix, value); return; }
        var body;
        try { body = (typeof __univerJson === 'function' ? __univerJson : JSON.stringify)(value); } catch (e) { return; }
        if (typeof body === 'string' && typeof __univerNative === 'function') __univerNative(prefix + body);
    }
    function onTap(node, handler) {
        var run = function (event) { if (event && event.isTrusted) handler(event); };
        if (typeof __univerListen === 'function') __univerListen(node, 'click', run);
        else node.addEventListener('click', run);
    }
    var reader = window.__univerGradeReadV1, scores = reader.scores;
    var selected = 'all', language = 'ru', state = null, selectedCourse = 'all';
    var hidden = new Map(), absenceMarks = new Map(), labels = new Map(), owned = [], identities = new WeakMap(), sequence = 0;
    // The user's own choice per course; without one, details open only when a single course is shown.
    var expanded = new Map(), moves = [], logAnimations = new Map();
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
    var ABSENCE_WORDS = {
        en: {term:'Absences this term', unexcused:'Unexcused', excused:'Excused', other:'Other classes', log:'Class log',
            limit:'{n} unexcused hours this term. The limit is {limit}.'},
        kz: {term:'Семестрдегі қатыспаған сабақтар', unexcused:'Себепсіз', excused:'Себепті', other:'Басқа сабақтар', log:'Сабақ журналы',
            limit:'Семестрде себепсіз қалған сағат: {n}. Шегі: {limit} сағат.'},
        ru: {term:'Пропуски за семестр', unexcused:'Без уважительной причины', excused:'По уважительной причине', other:'Другие занятия', log:'Журнал занятий',
            limit:'Пропущено {n} ч без уважительной причины за семестр. Лимит: {limit} ч.'}
    };
    function absenceText() { return ABSENCE_WORDS[language] || ABSENCE_WORDS.ru; }
    // Only «н» was seen in the live journal. Treating «нб», «н/б» and «ну» as excused marks is an assumption.
    function absenceKind(mark) {
        if (/^н$/i.test(mark)) return 'unexcused';
        if (/^(нб|н\/б|ну)$/i.test(mark)) return 'excused';
        return '';
    }
    function absenceTotals(counts) {
        return counts.reduce((sum, item) => {
            sum.unexcused += item.unexcused; sum.excused += item.excused; return sum;
        }, {unexcused:0, excused:0});
    }
    function classAbsences(lessons, facts) {
        return absenceTotals(lessons.flatMap(lesson => lesson.tables.map(table => facts.get(table).counts)));
    }
    function absenceLine(box, title, counts) {
        box.appendChild(el('span', 'univer-app-attendance-absence-title', title));
        ['unexcused', 'excused'].forEach(kind => {
            if (!counts[kind]) return;
            var pair = el('span', 'univer-app-attendance-absence-count');
            pair.dataset.kind = kind;
            pair.append(el('span', '', absenceText()[kind]), ' ', el('b', '', String(counts[kind])));
            box.append(' ', pair);
        });
    }
    // More «н» hours than this in a term (one class = one hour) shows a warning over the courses (the student's own limit).
    var MISSED_LIMIT = 25;
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
        return groups;
    }
    function matrixFacts(table) {
        var rows = ownRows(table), entries = [], grid = [];
        // Logical columns account for the observed two-row RK label and any merged date headings.
        rows.forEach((row, r) => {
            if (!grid[r]) grid[r] = [];
            var column = 0;
            Array.from(row.cells).forEach(cell => {
                while (grid[r][column]) column++;
                var end = cell.rowSpan ? r + cell.rowSpan : rows.length;
                for (var y = r; y < end && rows[y]?.parentNode === row.parentNode; y++) {
                    if (!grid[y]) grid[y] = [];
                    for (var x = column; x < column + cell.colSpan; x++) grid[y][x] = cell;
                }
                column += cell.colSpan;
            });
        });
        var sums = new Set(), sections = [], marks = new Map(), counts = {unexcused:0, excused:0};
        rows.forEach((row, r) => {
            var cells = Array.from(row.cells), header = grid[r][grid[r].length - 1];
            var hasHead = cells.some(cell => cell.tagName === 'TH');
            if (!r || (hasHead && Array.from(rows[r - 1].cells).some(cell => cell.tagName === 'TD'))) sections.push([]);
            sections[sections.length - 1].push(grid[r]);
            if (header?.tagName !== 'TH' || !/^(сумма|sum|total|жиыны|барлығы)$/i.test(clean(header))) return;
            sums.add(header);
            var period = clean(grid[r][0]), last = grid[r].length - 1;
            for (var n = r + 1; n < rows.length; n++) {
                var value = grid[n]?.[last];
                if (!value || value.tagName !== 'TD') break;
                // A spanned value is a single server total, never count it twice.
                if (!sums.has(value)) entries.push({period, value:clean(value)});
                sums.add(value);
            }
        });
        rows.forEach(row => Array.from(row.cells).forEach(cell => {
            if (cell.tagName !== 'TD' || sums.has(cell)) return;
            var kind = absenceKind(clean(cell));
            if (kind) { marks.set(cell, kind); counts[kind]++; }
        }));
        // Keep date-only and mark-only columns. Empty sites of a merged header may disappear only together.
        var empty = new Set(), kept = new Set();
        sections.forEach(section => {
            var columns = Array.from({length:Math.max(...section.map(row => row.length))}, (_, c) =>
                new Set(section.map(row => row[c]).filter(Boolean)));
            var keptColumns = columns.map(cells => Array.from(cells).some(cell =>
                sums.has(cell) || clean(cell) || cell.querySelector('a,button,input,select,textarea,img')));
            columns.forEach((cells, c) => {
                if (keptColumns[c] || Array.from(cells).some(cell => cell.colSpan > 1 &&
                    columns.some((other, x) => keptColumns[x] && other.has(cell)))) {
                    cells.forEach(cell => kept.add(cell));
                } else cells.forEach(cell => empty.add(cell));
            });
        });
        kept.forEach(cell => empty.delete(cell));
        return {entries, counts, empty, marks};
    }
    function journalFacts(groups) {
        var facts = new Map();
        groups.forEach(group => group.lessons.forEach(lesson => lesson.tables.forEach(table => {
            if (!facts.has(table)) facts.set(table, matrixFacts(table));
        })));
        return facts;
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
    function markAbsences(marks) {
        marks.forEach((kind, cell) => {
            absenceMarks.set(cell, ['data-univer-absence', 'aria-label'].map(name => [name, cell.getAttribute(name)]));
            cell.dataset.univerAbsence = kind;
            cell.setAttribute('aria-label', text().absences + '. ' + absenceText()[kind] + ' (' + clean(cell) + ')');
        });
    }
    function restoreAbsenceMarks() {
        absenceMarks.forEach((attrs, cell) => attrs.forEach(([name, value]) => {
            if (value === null) cell.removeAttribute(name); else cell.setAttribute(name, value);
        }));
        absenceMarks.clear();
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
    function afterLayout(read, write) {
        var adapter = window.__univerMobileV1;
        if (adapter?.afterLayout) adapter.afterLayout(read, write); else write(read());
    }
    function quiet(write) {
        var adapter = window.__univerMobileV1;
        if (adapter?.quiet) adapter.quiet(write); else write();
    }
    function stopLog(log) {
        var animation = logAnimations.get(log);
        if (animation) { logAnimations.delete(log); animation.cancel(); }
    }
    function hideLog(log, hidden) {
        log.hidden = hidden;
        var host = log.closest('tr.univer-app-attendance-log-host');
        if (host) host.hidden = hidden;
    }
    function setLogOpen(log, open, animate) {
        log.setAttribute('aria-hidden', String(!open));
        log.toggleAttribute('inert', !open);
        var root = document.documentElement;
        if (!animate || !log.animate || window.__univerMotion === false ||
            !root.matches('.univer-mobile:not(.univer-app-still):not(.univer-app-keyboard)') ||
            window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
            stopLog(log); hideLog(log, !open); return;
        }
        if (open) hideLog(log, false);
        afterLayout(() => {
            var height = log.firstElementChild.getBoundingClientRect().height;
            return [logAnimations.has(log) ? log.getBoundingClientRect().height : open ? 0 : height, open ? height : 0];
        }, ([from, to]) => {
            if (!log.isConnected) return;
            stopLog(log);
            if (Math.abs(from - to) < 1) { hideLog(log, !open); return; }
            var animation = log.animate([from, to].map(height => ({height:height + 'px', overflow:'hidden'})),
                {duration:open ? 220 : 180, easing:'cubic-bezier(.23,1,.32,1)', fill:'both'});
            logAnimations.set(log, animation);
            animation.onfinish = animation.oncancel = () => {
                if (logAnimations.get(log) !== animation) return;
                quiet(() => { hideLog(log, !open); stopLog(log); });
            };
        });
    }
    // Collect the lesson's whole siblings, including any empty-state row after its caption.
    function lessonNodes(lesson) {
        var start = lesson.start, end = lesson.end, parent = start.parentNode, last = start;
        if (end && start.contains(end)) return null;
        for (var table of lesson.tables) {
            var node = table;
            while (node.parentNode && node.parentNode !== parent && node !== parent) node = node.parentNode;
            if (node.parentNode !== parent) return null;
            if (before(last, node)) last = node;
        }
        var nodes = [start];
        for (var node = start.nextElementSibling; node && (!end || (before(node, end) && !node.contains(end))); node = node.nextElementSibling) {
            if (node.matches('tr.bot,a[href*="/student/attendance/full"]') || node.querySelector('a[href*="/student/attendance/full"]')) break;
            // The app's own summary row follows the first lesson on a page without a course heading.
            if (node.matches('script,style,.univer-app-attendance-owned')) continue;
            nodes.push(node);
        }
        return nodes.includes(last) ? nodes : null;
    }
    function classLog(group, summaryHost, open) {
        var parent = summaryHost.parentElement, rows = summaryHost.tagName === 'TR';
        var lessons = group.lessons.map(lesson => ({lesson, nodes:lessonNodes(lesson)}));
        // A legacy row with separate, nonempty cells may share its boundary with other site content.
        if (!lessons.length || lessons.some(({nodes}) => !nodes || nodes.some(node => node.parentElement !== parent ||
            (rows ? node.tagName !== 'TR' : node.matches('td,th,tr'))))) return null;
        var log = el('div', 'univer-app-attendance-log'), body = el('div', 'univer-app-attendance-log-body'), blocks = new Map();
        log.id = 'univer-class-log-' + id(group.heading || group.lessons[0].cell);
        log.setAttribute('role', 'region');
        log.setAttribute('aria-label', [absenceText().log, group.name].filter(Boolean).join(' · '));
        log.appendChild(body);
        lessons.forEach(({lesson, nodes}) => {
            var label = lesson.type ? text()[lesson.type] : clean(lesson.link).match(/^(.+?)\s+[-–—]\s+/)?.[1] || absenceText().other;
            var key = lesson.type || label.toLocaleLowerCase(), block = blocks.get(key);
            if (!block) {
                var section = el('div', 'univer-app-attendance-log-type');
                section.hidden = selected !== 'all' && lesson.type !== selected;
                section.appendChild(el('h3', 'univer-app-attendance-log-title', label));
                block = section;
                if (rows) {
                    var frame = withRole(el('table', 'mt univer-app-attendance-log-rows'), 'presentation');
                    block = el('tbody'); frame.appendChild(block); section.appendChild(frame);
                }
                body.appendChild(section); blocks.set(key, block);
            }
            nodes.forEach(node => {
                var home = document.createComment(''), source = node;
                if (node.matches('.univer-app-table-scroll') && node.firstElementChild?.matches('table.inner')) source = node.firstElementChild;
                node.before(home); block.appendChild(node); moves.push({node:source, home, block});
            });
        });
        var host = log;
        if (rows) {
            host = el('tr', 'univer-app-attendance-owned univer-app-attendance-log-host');
            var holder = el('td');
            holder.colSpan = summaryHost.cells[0].colSpan;
            holder.appendChild(log); host.appendChild(holder);
        }
        summaryHost.after(host); owned.push(host);
        setLogOpen(log, open && lessons.some(({lesson}) => selected === 'all' || lesson.type === selected), false);
        return {log, host};
    }
    function restoreLogs() {
        logAnimations.forEach((_, log) => stopLog(log));
        moves.forEach(({node, home, block}) => {
            // The table adapter can add a scroll box around a bare matrix after this module moves it.
            var scroll = node.parentElement?.closest('.univer-app-table-scroll');
            var carrier = scroll?.parentNode === block ? scroll : node;
            if (!home.parentNode || carrier.parentNode !== block) { home.remove(); return; }
            var hint = carrier.previousElementSibling;
            if (carrier === scroll && hint?.matches('.univer-app-scroll-hint')) home.before(hint);
            home.replaceWith(carrier);
        });
        moves = [];
    }
    // The sums of one class type by control period, keyed by the period's name.
    function typeTotals(lessons, facts) {
        var values = new Map(), totals = new Map();
        lessons.forEach(lesson => lesson.tables.forEach(table => facts.get(table).entries.forEach(entry => {
            if (!values.has(entry.period)) values.set(entry.period, []);
            values.get(entry.period).push(entry.value);
        })));
        values.forEach((items, period) => totals.set(periodKey(period), {period, value:periodTotal(items) || ''}));
        return totals;
    }
    function detailsToggle(group, open, log, source) {
        var toggle = el('button', 'univer-app-attendance-details-toggle', open ? text().hideDetails : text().showDetails);
        toggle.type = 'button'; toggle.setAttribute('aria-expanded', String(open));
        if (log) toggle.setAttribute('aria-controls', log.id);
        toggle.addEventListener('click', event => {
            open = !open;
            expanded.set(group.token, open);
            var update = () => {
                toggle.setAttribute('aria-expanded', String(open));
                setText(toggle, open ? text().hideDetails : text().showDetails);
                if (log) setLogOpen(log, open, event.detail > 0);
                else source.forEach(node => setHidden(node, !open));
            };
            // The shared table adapter needs one pass to fit and label matrices that have just become visible.
            var adapter = window.__univerMobileV1;
            if (open && adapter?.mutate) adapter.mutate(update); else quiet(update);
        });
        return toggle;
    }
    function summary(group, facts) {
        var box = el('section', 'univer-app-attendance-summary'), periods = [], rows = [], any = false, t = text();
        // The server's total is always hidden: with all classes its passive copy is shown, while a class-type
        // filter is about the class log, where the course total would only repeat what the filter hides.
        if (group.overall) {
            setHidden(boundary(group.overall), true);
            if (selected === 'all') periods = scores(clean(group.overall));
        }
        (selected === 'all' ? TYPES : [selected]).forEach(type => {
            var lessons = group.lessons.filter(l => l.type === type); if (!lessons.length) return;
            any = true; rows.push({type, values:typeTotals(lessons, facts)});
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
        var lessons = group.lessons.filter(lesson => selected === 'all' || lesson.type === selected);
        var counts = classAbsences(lessons, facts);
        if (counts.unexcused || counts.excused) {
            var lost = el('div', 'univer-app-attendance-absences');
            absenceLine(lost, t.absences, counts);
            if (selected === 'all' && counts.unexcused) {
                var parts = TYPES.concat(['']).map(type => [type, classAbsences(lessons.filter(l => l.type === type), facts).unexcused])
                    .filter(([, n]) => n > 0);
                if (parts.length > 1) lost.append(' ', el('span', 'univer-app-attendance-absence-breakdown',
                    parts.map(([type, n]) => t.absenceKinds[type || 'other'] + ' ' + n).join(', ')));
            }
            box.appendChild(lost);
        }
        // Anything the server wrote that is not a recognised period stays readable as its own line.
        if (group.overall && selected === 'all' && !periods.length) box.appendChild(el('p', 'univer-app-attendance-overall-value', clean(group.overall)));
        if (!lessons.length) box.appendChild(el('p', 'univer-app-attendance-nothing', selected === 'all' ? t.noClasses : t.empty));
        // Nothing to total: one quiet line instead of an empty card.
        if (!any && !group.overall) box.classList.add('univer-app-attendance-summary-empty');
        return box;
    }
    // Adapter-owned updates pause the shared DOM observer, so they never re-trigger adaptation.
    function redraw() {
        var action = () => { if (state) render(); };
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
        var host = state.host;
        afterLayout(() => host.getBoundingClientRect().top, top => { if (top < 0) host.scrollIntoView?.({block:'start'}); });
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
            var centre = () => afterLayout(() => {
                if (!chosen.isConnected || !chosen.offsetWidth || list.scrollWidth <= list.clientWidth) return null;
                return Math.max(0, chosen.offsetLeft - (list.clientWidth - chosen.offsetWidth) / 2);
            }, left => {
                if (left === null || state !== owner || selectedCourse !== wanted || owner.courseShown === wanted) return;
                owner.courseShown = wanted; list.scrollLeft = left;
            });
            centre();
            if (typeof requestAnimationFrame === 'function') requestAnimationFrame(centre);
            setTimeout(centre, 120); // A hidden WebView throttles animation frames.
        }
    }
    // Hides the outermost [elements] wholly inside [start, end) (and before [last], when given).
    function rangeNodes(elements, start, end, last) {
        var contained = node => (node === start || before(start, node)) && (!end || (before(node, end) && !node.contains(end))) &&
            (!last || node === last || last.contains(node) || (before(node, last) && !node.contains(last)));
        return elements.filter(node => contained(node) && !contained(node.parentElement));
    }
    function render() {
        restoreLogs(); resetVisibility(); restoreAbsenceMarks(); removeOwned();
        var groups = model(), facts = journalFacts(groups);
        courseControls(groups);
        groups.forEach(group => group.lessons.forEach(lesson => { if (lesson.type) makeLabel(lesson.link); }));
        var root = document.querySelector('#l4'), elements = Array.from(root.querySelectorAll('*')), t = text();
        facts.forEach(item => {
            item.empty.forEach(cell => setHidden(cell, true));
            markAbsences(item.marks);
        });
        // With all courses listed, each course starts as its summary; one chosen course shows its class log.
        var single = selectedCourse !== 'all' || groups.filter(group => group.heading).length < 2;
        groups.forEach(group => {
            var excludeCourse = selectedCourse !== 'all' && selectedCourse !== group.token;
            // Choosing lectures, seminars or independent work opens every course's log of that type.
            var open = !group.heading || selected !== 'all' || (expanded.has(group.token) ? expanded.get(group.token) : single);
            if (excludeCourse && group.heading) rangeNodes(elements, group.start, group.end,
                !group.end && group.overall ? boundary(group.overall) : null).forEach(node => setHidden(node, true));
            var box = summary(group, facts), host = insertAfterCell(group.heading || group.lessons[0].cell, box);
            var log = classLog(group, host, open), source = [];
            if (!log) group.lessons.forEach(lesson => {
                var nodes = rangeNodes(elements, lesson.start, lesson.end);
                var show = !excludeCourse && open && (selected === 'all' || selected === lesson.type);
                nodes.forEach(node => setHidden(node, !show)); source.push(...nodes);
            });
            if (excludeCourse) { setHidden(host, true); if (log) setHidden(log.host, true); }
            if (group.heading && group.lessons.length && selected === 'all') box.appendChild(detailsToggle(group, open, log?.log, source));
        });
        overview(groups);
        // Every course of the term counts, whichever course or class type is shown.
        var counts = classAbsences(groups.flatMap(group => group.lessons), facts), hours = counts.unexcused;
        state.term.hidden = !hours && !counts.excused;
        state.term.textContent = '';
        if (!state.term.hidden) absenceLine(state.term, absenceText().term, counts);
        state.limit.hidden = hours <= MISSED_LIMIT;
        setText(state.limit, state.limit.hidden ? '' : words(absenceText().limit, {n:hours, limit:MISSED_LIMIT}));
        state.bar.querySelectorAll('button').forEach(button => { button.textContent = t[button.dataset.kind]; button.setAttribute('aria-pressed', String(selected === button.dataset.kind)); });
        state.bar.setAttribute('aria-label', t.filter);
        // The note explains a type filter, so it appears only while one is active.
        state.caption.textContent = t.filter; state.note.textContent = t.note; state.note.hidden = selected === 'all';
        setText(state.share, t.report);
        var graded = groups.some(group => group.heading && group.overall && scores(clean(group.overall)).length);
        var reportable = graded || groups.some(group => group.heading && group.lessons.some(lesson =>
            lesson.tables.some(table => facts.get(table).counts.unexcused || facts.get(table).counts.excused)));
        dom.setHidden(state.share, !canAct() || !/^\/student\/attendance\/full(\/|$)/.test(location.pathname) || !reportable);
        dom.setHidden(state.goal, !graded || selected !== 'all' || selectedCourse !== 'all');
        setText(state.goalCaption, t.goal); state.goal.setAttribute('aria-label', t.goal);
        state.goal.querySelectorAll('button[data-goal]').forEach(button => {
            var name = button.dataset.goal;
            setText(button, name === 'none' ? t.noGoal : t[name]);
            button.setAttribute('aria-pressed', String((dom.goal() || 'none') === name));
        });
        state.signature = signatureOf(model());
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
        var limit = el('p', 'univer-app-attendance-limit'); limit.dataset.band = 'low'; limit.hidden = true;
        var term = el('div', 'univer-app-attendance-term');
        var note = el('p', 'univer-app-attendance-filter-note'), summaryList = el('section', 'univer-app-attendance-overview');
        // The report for printing or sending (a parent, a curator) is drawn by the app from every course's totals.
        var share = el('button', 'univer-app-attendance-report'); share.type = 'button'; share.hidden = true;
        onTap(share, function () { deliver('univer-report:', reportData()); });
        // The level to aim at (a parent's question: how far is «Отлично»?). The hints and the report count to it; it is
        // chosen with all courses in view and kept for each course.
        var goal = el('div', 'univer-app-goal'), goalCaption = el('p', 'univer-app-goal-caption'), goalList = el('div', 'univer-app-goal-list');
        goal.setAttribute('role', 'group'); goal.hidden = true; goalCaption.setAttribute('aria-hidden', 'true');
        ['none', 'pass', 'good', 'top'].forEach(name => {
            var button = el('button'); button.type = 'button'; button.dataset.goal = name;
            button.addEventListener('click', () => dom.setGoal(name === 'none' ? null : name)); goalList.appendChild(button);
        });
        goal.append(goalCaption, goalList);
        // The goal sits under the course overview, next to the score colours: the summary stays on the first screen.
        var tools = el('div', 'univer-app-attendance-tools'); tools.append(term, limit, courses, caption, bar, note, share, summaryList, goal);
        insertAfterCell(groups[0].heading || groups[0].lessons[0].cell, tools, true);
        // The filter bar has its own lifetime; summary rebuilds must not remove it.
        var host = owned.pop();
        state = {bar, host, term, limit, caption, note, share, goal, goalCaption, courses, courseCaption, courseList, allCourse, courseButtons:new Map(), overview:summaryList, signature:null};
    }
    function termOf() { return reader.term(document, location.pathname); }
    // What the app's report needs and nothing more: the term, the name shown in the site header and each course's
    // control-period totals, whatever course or class type is chosen. Native code checks every field again.
    function reportData() {
        var name = document.querySelector('#l2 a[href="/user/pass/"]'), groups = model(), facts = journalFacts(groups);
        return {
            // The header reads "Surname Name (login: id)": the report names the student only.
            v:1, term:termOf(), student:name ? clean(name).replace(/\s*\([^()]*\)$/, '').slice(0, 120) : '',
            courses:groups.filter(group => group.heading).slice(0, 60).map(group => {
                var counts = classAbsences(group.lessons, facts);
                return {
                    name:group.name.slice(0, 200),
                    scores:(group.overall ? scores(clean(group.overall)) : []).slice(0, 8).map(item => ({label:item.label, max:item.max, value:item.value})),
                    absences:Math.min(999, counts.unexcused), excused:Math.min(999, counts.excused),
                };
            }),
            goal:dom.goal(),
        };
    }
    // Language and every course, lesson and grade table with its text: polling rerenders only when one changes.
    function signatureOf(groups) {
        return language + '|' + (dom.goal() || '') + '|' + groups.map(g => id(g.heading || g.lessons[0].cell) + ':' + (g.token || '') +
            g.lessons.map(l => id(l.link) + clean(l.link) + l.tables.map(t => id(t) + ownRows(t).map(row =>
                Array.from(row.cells, cell => [id(cell), cell.colSpan, cell.rowSpan, clean(cell)].join(':')).join(',')).join('/')).join('|')).join('/') +
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
        if (state.signature !== signature) { freshness(groups); render(); }
    }
    function release() {
        restoreLogs(); resetVisibility(); restoreAbsenceMarks(); removeOwned(); restoreLabels();
        state?.host.remove(); state = null;
    }
    window.__univerAttendanceV1 = {apply, release};
})();
