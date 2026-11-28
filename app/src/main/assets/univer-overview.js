/* Read-only summaries on the tab pages: Бакалавр shows today's classes and the grade summary, the Е-услуги desk
   shows the latest requests. Each is read from the site's own GET page once per visit and never changes it. */
// The class cards' «В календарь» (univer-mobile.js) reads the week pair through this. The modules go in together inside
// MobilePageAdapter's wrapper, so it is a variable of that call, which no site script reaches.
var __univerWeekPair = null;
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerOverviewV1) return;
    var dom = window.__univerDomV1, clean = dom.clean, ownRows = dom.ownRows, el = dom.el;
    var language = 'ru', active = false, owned = [], loads = new Map(), generation = 0, scheduleTimer;
    // Bounds of one page read (its size and its wait) and the clock that keeps the today panel current.
    var MAX_HTML = 4 * 1024 * 1024, READ_TIMEOUT = 12000, REFRESH = 30000;
    var WORDS = {
        en: {schedule:'Schedule', none:'none', today:'Today', tomorrow:'Tomorrow', grades:'Grades', requests:'My requests', loading:'Loading…', failed:'Could not load. Open the page itself.', noClasses:'No classes today',
            finished:'Today’s classes have finished',
            allSchedule:'Full schedule', allGrades:'All grades', allRequests:'All requests', noRequests:'No requests yet', now:'Now', next:'Next', done:'Finished', noGrades:'No grades yet', showAll:'Show all', collapse:'Show less', nextClasses:'Next classes', open:'Open request',
            days:['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday']},
        kz: {schedule:'Сабақ кестесі', none:'жоқ', today:'Бүгін', tomorrow:'Ертең', grades:'Үлгерім', requests:'Менің өтінімдерім', loading:'Жүктелуде…', failed:'Жүктеу мүмкін болмады. Бетті ашыңыз.', noClasses:'Бүгін сабақ жоқ',
            finished:'Бүгінгі сабақтар аяқталды',
            allSchedule:'Толық кесте', allGrades:'Барлық бағалар', allRequests:'Барлық өтінімдер', noRequests:'Өтінімдер әлі жоқ', now:'Қазір', next:'Келесі', done:'Аяқталды', noGrades:'Бағалар әлі жоқ', showAll:'Барлығын көрсету', collapse:'Жасыру', nextClasses:'Келесі сабақтар', open:'Өтінімді ашу',
            days:['Жексенбі','Дүйсенбі','Сейсенбі','Сәрсенбі','Бейсенбі','Жұма','Сенбі']},
        ru: {schedule:'Расписание', none:'нет', today:'Сегодня', tomorrow:'Завтра', grades:'Успеваемость', requests:'Мои заявки', loading:'Загрузка…', failed:'Не удалось загрузить. Откройте страницу целиком.', noClasses:'Сегодня пар нет',
            finished:'Сегодняшние пары закончились',
            allSchedule:'Всё расписание', allGrades:'Вся успеваемость', allRequests:'Все заявки', noRequests:'Заявок пока нет', now:'Идёт', next:'Следующая', done:'Прошла', noGrades:'Оценок пока нет', showAll:'Показать все', collapse:'Свернуть', nextClasses:'Ближайшие пары', open:'Открыть заявку',
            days:['Воскресенье','Понедельник','Вторник','Среда','Четверг','Пятница','Суббота']}
    };
    // The line before today's list: the class under way and what follows it, or when the next one starts.
    Object.assign(WORDS.en, {stale:'Saved {0}. Could not refresh.', work:'Independent work this week', workRead:'Read from the schedule on {0}', points:'max score: '});
    Object.assign(WORDS.kz, {stale:'{0} сақталған. Жаңарту мүмкін болмады.', work:'Осы аптадағы БӨЖ', workRead:'Кестеден оқылды: {0}', points:'ең жоғары балл: '});
    Object.assign(WORDS.ru, {stale:'Сохранено {0}. Обновить не удалось.', work:'СРО на этой неделе', workRead:'Прочитано в расписании {0}', points:'макс. балл: '});
    Object.assign(WORDS.en, {upNow:'Class in progress', upNext:'Next class', upThen:'Then', upLast:'Last class today', upRoom:'Room'});
    Object.assign(WORDS.kz, {upNow:'Сабақ жүріп жатыр', upNext:'Келесі сабақ', upThen:'Содан кейін', upLast:'Бүгінгі соңғы сабақ', upRoom:'Дәрісхана'});
    Object.assign(WORDS.ru, {upNow:'Идёт пара', upNext:'Следующая пара', upThen:'Затем', upLast:'Это последняя пара сегодня', upRoom:'Ауд.'});
    Object.assign(WORDS.en, {exportLabel:'Export schedule', exportIcs:'To calendar', exportPng:'As picture',
        exportIcsTitle:'Both weeks to the calendar (.ics file)', exportPngTitle:'Both weeks as a picture (PNG)'});
    Object.assign(WORDS.kz, {exportLabel:'Кестені экспорттау', exportIcs:'Күнтізбеге', exportPng:'Сурет ретінде',
        exportIcsTitle:'Екі аптаны да күнтізбеге (.ics файлы)', exportPngTitle:'Екі аптаны да сурет ретінде (PNG)'});
    Object.assign(WORDS.ru, {exportLabel:'Экспорт расписания', exportIcs:'В календарь', exportPng:'Картинкой',
        exportIcsTitle:'Обе недели в календарь (файл .ics)', exportPngTitle:'Обе недели картинкой (PNG)'});
    function text() { return language === 'en' ? WORDS.en : language === 'kz' ? WORDS.kz : WORDS.ru; }
    // The schedule page's countdown words: "через 2 ч 15 мин", "ещё 25 мин".
    function duration(m) {
        var u = language === 'en' ? ['d','h','min'] : language === 'kz' ? ['күн','сағ','мин'] : ['д','ч','мин'];
        if (m < 60) return m + ' ' + u[2];
        if (m < 1440) return Math.floor(m / 60) + ' ' + u[1] + (m % 60 ? ' ' + m % 60 + ' ' + u[2] : '');
        var hours = Math.floor(m % 1440 / 60);
        return Math.floor(m / 1440) + ' ' + u[0] + (hours ? ' ' + hours + ' ' + u[1] : '');
    }
    function until(m) { return language === 'en' ? 'in ' + duration(m) : language === 'kz' ? duration(m) + ' кейін' : 'через ' + duration(m); }
    function left(m) { return language === 'en' ? duration(m) + ' left' : language === 'kz' ? duration(m) + ' қалды' : 'ещё ' + duration(m); }
    // A label that counts down to a moment; the clock tick rewrites only its words, so the panel does not redraw every minute.
    function counter(node, prefix, kind, moment) { node.dataset.univerCountAt = String(moment); node.dataset.univerCountKind = kind; node.dataset.univerCountPrefix = prefix; tick(node, +new Date()); return node; }
    function tick(node, now) {
        var m = Math.max(1, Math.ceil((+node.dataset.univerCountAt - now) / 60000)), value = node.dataset.univerCountPrefix + (node.dataset.univerCountKind === 'left' ? left(m) : until(m));
        if (node.textContent !== value) node.textContent = value;
    }
    function tickCounts(root) { var now = +new Date(); root.querySelectorAll('[data-univer-count-at]').forEach(function (node) { tick(node, now); }); }

    /* Site pages, each read once per visit. */
    async function read(url) {
        var abort = new AbortController(), timeout = setTimeout(function () { abort.abort(); }, READ_TIMEOUT);
        try {
            var response = await fetch(url, {credentials:'same-origin', cache:'no-store', signal:abort.signal});
            var final = new URL(response.url, location.origin);
            if (response.status !== 200 || final.origin !== location.origin || /^\/user\/login/i.test(final.pathname)) throw new Error('Unavailable');
            var html = await response.text(); if (html.length > MAX_HTML) throw new Error('Oversized');
            var doc = new DOMParser().parseFromString(html, 'text/html');
            if (doc.querySelector('#login_frm')) throw new Error('Session expired');
            return doc;
        } finally { clearTimeout(timeout); }
    }
    function once(key, url) { if (!loads.has(key)) loads.set(key, read(url)); return loads.get(key); }
    // The last visit's panels. They belong to the account that signed in here (the mark the sign-in form leaves) and to one
    // language; without the mark nothing is read or written, and another account's or a damaged record is removed. Only
    // parsed text is kept, never the site's markup. Sign-out erases the site's storage (SiteData.erase), and the site's own
    // logout link forgets the record at once.
    var KEY = 'univer.app.overview.v2', LIMIT = 200000, WEEK = 7 * 86400000, HOUR = 3600000, AGE = 14 * 86400000;
    function account() { try { return localStorage.getItem('univer.app.account.v1') || ''; } catch (_) { return ''; } }
    // The other per-account records the page scripts keep go with it.
    var PERSONAL = [KEY, 'univer.app.syllabus-own.v1', 'univer.app.syllabus-weeks.v1', 'univer.app.discussion-tasks.v1', 'univer.mobile.discussion-tasks.v1', 'univer.app.survey-count.v1'];
    function forget() { PERSONAL.forEach(function (key) { try { localStorage.removeItem(key); } catch (_) { } }); }
    function kept() {
        var who = account(); if (!who) return null;
        try {
            var raw = localStorage.getItem(KEY); if (!raw) return null;
            var all = raw.length <= LIMIT ? JSON.parse(raw) : null;
            if (!all || typeof all !== 'object' || all.owner !== who) { forget(); return null; }
            return all;
        } catch (_) { forget(); return null; }
    }
    function saved(name, valid) {
        var all = kept(), part = all && all.language === language ? all[name] : null;
        try { return part && Number.isFinite(part.at) && part.at <= Date.now() + 60000 && valid(part) ? part : null; } catch (_) { return null; }
    }
    // lang: the page's language when another page writes here (the schedule's independent work), since only apply() on
    // the home pages sets this module's own.
    function keep(name, part, valid, lang) {
        var who = account(), as = lang || language; if (!who) return;
        try {
            part.at = Date.now(); if (!valid(part)) return;
            var all = kept(); if (!all || all.language !== as) all = {owner:who, language:as};
            all[name] = part;
            var raw = JSON.stringify(all); if (raw.length <= LIMIT) localStorage.setItem(KEY, raw);
        } catch (_) { }
    }
    function words(value, max) { return typeof value === 'string' && value.length <= (max || 400); }
    function isInfo(info) {
        return info === undefined || !!info && typeof info === 'object' && ['subject', 'type', 'kind', 'teacher', 'room', 'faculty'].every(function (k) { return words(info[k], 400); }) &&
            Array.isArray(info.dates) && info.dates.length <= 2 && info.dates.every(function (v) { return words(v, 40); }) &&
            Array.isArray(info.extra) && info.extra.length <= 12 && info.extra.every(function (v) { return words(v, 400); });
    }
    function isLessons(list) {
        return Array.isArray(list) && list.length <= 40 && list.every(function (l) {
            return l && Number.isInteger(l.start) && Number.isInteger(l.end) && l.start >= 0 && l.end <= 1500 && words(l.subject) && words(l.details, 2000) && isInfo(l.info);
        });
    }
    function isWeek(part) {
        return Number.isFinite(part.start) && Number.isFinite(part.end) && part.end > part.start && part.end - part.start <= WEEK + HOUR && Array.isArray(part.days) && part.days.length <= 8 && part.days.every(isLessons) &&
            (part.following == null || Number.isFinite(part.following.date) && isLessons(part.following.lessons));
    }
    function isCourses(part) {
        return Date.now() - part.at < AGE && Array.isArray(part.list) && part.list.length <= 80 && part.list.every(function (c) {
            return c && words(c.name) && words(c.token, 2000) && Array.isArray(c.scores) && c.scores.length <= 8 &&
                c.scores.every(function (s) { return s && words(s.label, 40) && words(s.max, 40) && words(s.value, 40); });
        });
    }
    // The week's independent work as the schedule page listed it (univer-syllabus.js): only the week it showed, only while
    // that week was the current one, consultations left out. The site's own scripts can write this storage too, so every
    // field is bounded and the course address must be a UMKD course page.
    var COURSE = /^\/student\/umkd\/\d{1,12}\/\d{4}\/[12]$/;
    function isWork(part) {
        return Number.isFinite(part.start) && Number.isFinite(part.end) && part.end > part.start && part.end - part.start <= WEEK + HOUR &&
            Array.isArray(part.list) && part.list.length <= 40 && part.list.every(function (w) {
                return w && words(w.course) && typeof w.href === 'string' && COURSE.test(w.href) && words(w.name, 80) && words(w.topic, 1200) &&
                    Number.isFinite(w.score) && w.score >= 0 && w.score <= 1000;
            });
    }
    function isRequests(part) {
        return Date.now() - part.at < AGE && Array.isArray(part.list) && part.list.length <= 60 && part.list.every(function (r) {
            return r && words(r.service) && words(r.date, 40) && words(r.sort, 80) && words(r.status) && /^\/ssc\/(?:StatementPage\?statementId=\d{1,12}|StatementList)$/.test(r.href);
        });
    }
    // The site's own sign-out link: the next account must not find these panels.
    document.addEventListener('click', function (event) {
        var link = event.target && event.target.closest ? event.target.closest('a[href]') : null;
        try { if (link && /^\/user\/logout\/?$/i.test(new URL(link.getAttribute('href'), location.href).pathname)) forget(); } catch (_) { }
    }, true);

    /* The weekly schedule table, read the same way as the schedule page itself. */
    function minutes(value) { var m = value.split(':'); return +m[0] * 60 + +m[1]; }
    function clock(value) { return String(Math.floor(value / 60)).padStart(2, '0') + ':' + String(value % 60).padStart(2, '0'); }
    function columns(doc) { var table = doc.querySelector('table.schedule'); return table && table.rows[0] ? table.rows[0].cells.length : 0; }
    // Every row's cells by column with rowspans and colspans resolved: the cell with its first and last row.
    function scheduleGrid(rows) {
        var grid = [];
        rows.forEach(function (row, r) {
            grid[r] = grid[r] || []; var c = 0;
            Array.from(row.cells).forEach(function (cell) {
                while (grid[r][c]) c++;
                for (var y = 0; y < cell.rowSpan; y++) { grid[r + y] = grid[r + y] || []; for (var x = 0; x < cell.colSpan; x++) grid[r + y][c + x] = {cell:cell, start:r, end:r + cell.rowSpan - 1}; }
                c += cell.colSpan;
            });
        });
        return grid;
    }
    // The minutes a slot spans, from the times in the first column of its first and last rows; null without them.
    function slotTime(grid, slot) {
        var from = clean(grid[slot.start]?.[0]?.cell).match(/\d{1,2}:\d{2}/g) || [], to = clean(grid[Math.min(slot.end, grid.length - 1)]?.[0]?.cell).match(/\d{1,2}:\d{2}/g) || [];
        return from.length && to.length ? {start:minutes(from[0]), end:minutes(to[to.length - 1])} : null;
    }
    // A schedule cell's lessons: each subgroup (or the whole cell) with its subject and the rest as details.
    function cellLessons(cell, time) {
        var lessons = [], groups = Array.from(cell.querySelectorAll('.groups>div')); if (!groups.length) groups = [cell];
        groups.forEach(function (group) {
            var parts = Array.from(group.querySelectorAll('p')); if (!parts.length) parts = [group];
            var texts = parts.map(function (p) { return p.classList.contains('params') ? Array.from(p.childNodes).map(clean).filter(Boolean).join(' / ') : clean(p); }).filter(Boolean);
            if (texts.length) lessons.push({start:time.start, end:time.end, subject:texts[0], details:texts.slice(1).join(' · '), info:window.__univerLesson?.read(group)});
        });
        return lessons;
    }
    function lessonsOn(doc, column) {
        var table = doc.querySelector('table.schedule'); if (!table) return null;
        var rows = ownRows(table), header = rows[0];
        if (!header || column < 1 || column >= header.cells.length) return [];
        var grid = scheduleGrid(rows), lessons = [];
        grid.slice(1).forEach(function (line, i) {
            var slot = line[column]; if (!slot || slot.start !== i + 1 || !clean(slot.cell)) return;
            var time = slotTime(grid, slot); if (!time) return;
            lessons.push(...cellLessons(slot.cell, time));
        });
        return lessons.sort(function (a, b) { return a.start - b.start; });
    }
    // Today's column of the weekly table, rowspans resolved, the same reading as the schedule page itself.
    function lessonsToday(doc, now) { return lessonsOn(doc, now.getDay() === 0 ? -1 : now.getDay()); }
    function weekRange(value) {
        var dates = (value || '').match(/\d{1,2}\.\d{1,2}\.\d{4}/g) || [];
        return dates.length >= 2 ? dates.map(function (d) { var p = d.split('.'); return new Date(+p[2], +p[1] - 1, +p[0]); }) : null;
    }
    // The first day with classes in [doc]'s week from [column] on, dated from [base], the day of column [baseColumn].
    function firstClassDay(doc, column, base, baseColumn) {
        for (; column < columns(doc); column++) {
            var lessons = lessonsOn(doc, column);
            if (lessons && lessons.length) return {date:new Date(base.getFullYear(), base.getMonth(), base.getDate() + column - baseColumn), lessons:lessons};
        }
        return null;
    }
    // The week after the one selected in [doc]'s week selector: its schedule option and dates, or null.
    function followingWeek(doc) {
        var select = doc.getElementById('weekSelect'); if (!select) return null;
        var options = Array.from(select.options), current = options.find(function (o) { return o.selected || o.hasAttribute('selected'); }) || options[0];
        var range = current && weekRange(current.value + ' ' + current.textContent); if (!range) return null;
        var next = options.map(function (o) { return {option:o, range:weekRange(o.value + ' ' + o.textContent)}; })
            .filter(function (o) { return o.range && o.range[0] > range[range.length - 1]; }).sort(function (a, b) { return a.range[0] - b.range[0]; })[0];
        return next && /^\/student\/myschedule\//.test(next.option.value) ? next : null;
    }
    // On a day without classes: the next day with classes later this week, or the first one of the following week.
    // The following week is the site's own week link (the week selector's option address), read like the current one.
    async function nextClasses(doc, now) {
        var later = firstClassDay(doc, now.getDay() === 0 ? 7 : now.getDay() + 1, now, now.getDay());
        if (later) return later;
        var next = followingWeek(doc); if (!next) return null;
        var following = await once('schedule:' + next.option.value, next.option.value);
        return firstClassDay(following, 1, next.range[0], 1);
    }

    /* The week's timetable for the phone: the widget, class reminders and the offline schedule read it. */
    function isoDay(d) { return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }
    // A course date as the site writes it: "15.12.2026", "15.12.26", "2026-12-15", or "01.09" without a year. The site
    // shows a class only within its course dates, so a yearless first day is the last such day by the week's end and a
    // yearless last day the first such day from its Monday ("01.09"–"20.01" seen in January spans the new year).
    function courseDay(value, monday, last) {
        var iso = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value || ''), dmy = /^(\d{1,2})\.(\d{1,2})(?:\.(\d{2}|\d{4}))?$/.exec(value || ''), day;
        if (iso) day = new Date(+iso[1], +iso[2] - 1, +iso[3]);
        else if (dmy) {
            day = new Date(dmy[3] ? (dmy[3].length === 2 ? 2000 + +dmy[3] : +dmy[3]) : monday.getFullYear(), +dmy[2] - 1, +dmy[1]);
            var sunday = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 6);
            if (!dmy[3] && last && day < monday) day.setFullYear(day.getFullYear() + 1);
            if (!dmy[3] && !last && day > sunday) day.setFullYear(day.getFullYear() - 1);
        }
        return day && !isNaN(day) ? day : null;
    }
    // Both course dates, or neither: a pair the phone cannot read as a span is not guessed at.
    function courseDates(dates, monday) {
        var from = dates.length === 2 ? courseDay(dates[0], monday, false) : null, until = dates.length === 2 ? courseDay(dates[1], monday, true) : null;
        return from && until && from <= until ? {from:isoDay(from), until:isoDay(until)} : {from:null, until:null};
    }
    // The classes of [doc]'s week: weekday (Monday = 0), clock times, names and course dates ([monday] dates the yearless ones).
    function weekLessons(doc, monday) {
        var lessons = [];
        for (var column = 1; column < Math.min(columns(doc), 8); column++) (lessonsOn(doc, column) || []).forEach(function (lesson) {
            var info = lesson.info || {}, dates = courseDates(info.dates || [], monday);
            if (lesson.end <= lesson.start) return;
            lessons.push({day:column - 1, start:clock(lesson.start), end:clock(lesson.end), subject:(info.subject || lesson.subject).slice(0, 200),
                type:(info.type || '').slice(0, 60), room:(info.room || '').slice(0, 100), faculty:(info.faculty || '').slice(0, 100), teacher:(info.teacher || '').slice(0, 200),
                from:dates.from, until:dates.until});
        });
        return lessons.slice(0, 80);
    }
    function mondayOf(date) { return new Date(date.getFullYear(), date.getMonth(), date.getDate() - (date.getDay() + 6) % 7); }
    // Only a week that holds [now]: its Monday, every class with its weekday (Monday = 0), clock times, names and course
    // dates, and the account the page belongs to. Another week, an undated page or a page without the table gives null.
    function timetable(doc, now) {
        var select = doc.getElementById('weekSelect'); if (!select || !doc.querySelector('table.schedule')) return null;
        var options = Array.from(select.options), current = options.find(function (o) { return o.selected || o.hasAttribute('selected'); }) || options[0];
        var range = current && weekRange(current.value + ' ' + current.textContent); if (!range) return null;
        var monday = mondayOf(range[0]);
        var today = new Date(now.getFullYear(), now.getMonth(), now.getDate()), next = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 7);
        if (today < monday || today >= next) return null;
        return {v:1, week:isoDay(monday), owner:account().slice(0, 128), lessons:weekLessons(doc, monday)};
    }

    /* The shown week and its neighbour. The site alternates odd and even weeks, so a class that is in one of the two and not
       in the other must not repeat every week. The neighbour is the week right after the shown one, else the one right before
       it (a week after a gap may be of the same parity); it is read by GET at the site's own week address, nothing may
       redirect, and the page that answers must name the week that was asked for. A neighbour that cannot be read is named in
       `missing`: no other week stands in for it. */
    var EXPORT_VALUE = /^\/student\/myschedule\/\d{4}\/[12]\/\d{1,2}\.\d{1,2}\.\d{4}\/\d{1,2}\.\d{1,2}\.\d{4}\/?$/;
    var SCHEDULE_PAGE = /^\/student\/myschedule(?:\/\d{4}\/[12](?:\/\d{1,2}\.\d{1,2}\.\d{4}\/\d{1,2}\.\d{1,2}\.\d{4})?)?\/?$/;
    // The channel's week reader, Promise and clock, taken at document start (univer-channel.js); without them this module's own.
    var held = typeof __univerClock === 'object' && __univerClock && typeof __univerClock.page === 'function' && typeof __univerClock.promise === 'function' ? __univerClock : null;
    function timeOf(date) { return held ? held.time(date) : date.getTime(); }
    function dayAfter(date) {
        if (!held) return new Date(date.getFullYear(), date.getMonth(), date.getDate() + 1).getTime();
        var p = held.parts(held.time(date)); return held.at(p.year, p.month, p.date + 1, 0, 0);
    }
    // The site's number of the week from its option ("7 неделя …"), else its place in the list.
    function weekNumber(option, index) {
        var label = clean(option).replace(/\d{1,2}\.\d{1,2}\.\d{4}/g, ' '), found = parseInt((label.match(/\d{1,2}/) || [''])[0], 10);
        return found >= 1 && found <= 40 ? {number:found, labelled:true} : {number:index + 1, labelled:false};
    }
    function weekFrame(doc) {
        var select = doc.getElementById('weekSelect'); if (!select || !doc.querySelector('table.schedule')) return null;
        var options = Array.from(select.options), at = Math.max(select.selectedIndex, 0), shown = options[at];
        var ranges = options.map(function (o) { return weekRange(o.value + ' ' + o.textContent); });
        var term = shown && /^\/student\/myschedule\/(\d{4})\/([12])\//.exec(shown.value);
        return ranges[at] && term ? {options:options, ranges:ranges, at:at, term:term[1] + '/' + term[2]} : null;
    }
    function weekEntry(frame, index, doc) {
        var range = frame.ranges[index], number = weekNumber(frame.options[index], index), monday = mondayOf(range[0]);
        return {number:number.number, labelled:number.labelled, monday:isoDay(monday), first:isoDay(range[0]), last:isoDay(range[range.length - 1]), lessons:weekLessons(doc, monday)};
    }
    // [doc] when it shows the week of [range] (its first and last day), else an error.
    function sameWeek(doc, range) {
        if (doc.querySelector('#login_frm') || !doc.querySelector('table.schedule')) throw new Error('Unread');
        var got = weekFrame(doc), shown = got && got.ranges[got.at];
        if (!shown || timeOf(shown[0]) !== timeOf(range[0]) || timeOf(shown[shown.length - 1]) !== timeOf(range[range.length - 1])) throw new Error('Other week');
        return doc;
    }
    async function readWeek(option, range) {
        var value = option.value, url = new URL(value, location.origin);
        if (!EXPORT_VALUE.test(value) || url.origin !== location.origin) throw new Error('Address');
        var abort = new AbortController(), timeout = setTimeout(function () { abort.abort(); }, READ_TIMEOUT);
        try {
            var response = await fetch(url.href, {credentials:'same-origin', cache:'no-store', redirect:'error', signal:abort.signal});
            var final = new URL(response.url, location.origin);
            if (response.status !== 200 || final.origin !== location.origin || final.pathname !== url.pathname) throw new Error('Unavailable');
            var html = await response.text(); if (html.length > MAX_HTML) throw new Error('Oversized');
            return sameWeek(new DOMParser().parseFromString(html, 'text/html'), range);
        } finally { clearTimeout(timeout); }
    }
    // {v, term, until (the last day the site lists), weeks (the shown one first, then its neighbour), missing (numbers)}.
    // With the channel the neighbour is fetched and parsed by its reader (clock.page), the promise is its Promise, and the
    // pair's own `then` (undefined, never sent) keeps a then the page put on Object.prototype from answering for the pair:
    // a page that swaps fetch, DOMParser, Response, URL or Promise methods at load can neither block the neighbour nor
    // replace the pair. The arrays are literals, so no push or index setter of the page's takes part.
    function readPair(doc) {
        if (!held) return readPairHere(doc);
        return held.promise(function (resolve) {
            var base = pairBase(doc || document), pair = base.pair, other = base.other;
            if (other < 0) { resolve(pair); return; }
            var missing = function () { pair.missing = [weekNumber(base.frame.options[other], other).number]; resolve(pair); };
            var reading = null, value = base.frame.options[other].value;
            try { reading = typeof value === 'string' && EXPORT_VALUE.test(value) ? held.page(value, MAX_HTML, READ_TIMEOUT) : null; } catch (_) { reading = null; }
            // A WebView that had no fetch or Response at document start: this module's own reader.
            var own = !reading;
            if (own) reading = readWeek(base.frame.options[other], base.frame.ranges[other]);
            held.then(reading, function (got) {
                try { pair.weeks = [pair.weeks[0], weekEntry(base.frame, other, own ? got : sameWeek(got.doc, base.frame.ranges[other]))]; resolve(pair); }
                catch (_) { missing(); }
            }, missing);
        });
    }
    function pairBase(doc) {
        var frame = weekFrame(doc); if (!frame) throw new Error('No week');
        var shown = frame.ranges[frame.at], start = timeOf(shown[0]), end = dayAfter(shown[shown.length - 1]), after = -1, before = -1, latest = shown[shown.length - 1];
        for (var i = 0; i < frame.ranges.length; i++) {
            var r = frame.ranges[i];
            if (!r) continue;
            if (timeOf(r[r.length - 1]) > timeOf(latest)) latest = r[r.length - 1];
            if (i === frame.at) continue;
            if (timeOf(r[0]) === end && after < 0) after = i;
            if (dayAfter(r[r.length - 1]) === start && before < 0) before = i;
        }
        return {frame:frame, other:after >= 0 ? after : before,
            pair:{v:1, term:frame.term, until:isoDay(latest), weeks:[weekEntry(frame, frame.at, doc)], missing:[], then:undefined}};
    }
    // Without the channel: this module's own fetch, DOMParser and Promise.
    async function readPairHere(doc) {
        var base = pairBase(doc || document), pair = base.pair, other = base.other;
        if (other >= 0) {
            try { pair.weeks = [pair.weeks[0], weekEntry(base.frame, other, await readWeek(base.frame.options[other], base.frame.ranges[other]))]; }
            catch (_) { pair.missing = [weekNumber(base.frame.options[other], other).number]; }
        }
        return pair;
    }
    // One reading shared by the class cards' buttons for a minute; a failed one is not kept.
    // Its age is read with the channel's Date.now taken at document start, so a page's Date.now cannot force a new reading
    // between the card and the tap. It is handed only to univer-mobile.js (__univerWeekPair), not put on window.
    var pairCache = null, EXPORT_LIMIT = 98304; // the keyed channel's limit for univer-export: (MobilePageAdapter NATIVE)
    var pairNow = typeof __univerClock === 'object' && __univerClock ? __univerClock.now : function () { return Date.now(); };
    function weekPair() {
        var select = document.getElementById('weekSelect'), key = location.pathname + '|' + (select ? select.selectedIndex : -1), now = pairNow();
        if (!pairCache || pairCache.key !== key || now - pairCache.at > 60000) {
            var made = {key:key, at:now, promise:readPair()};
            made.promise.catch(function () { if (pairCache === made) pairCache = null; });
            pairCache = made;
        }
        return pairCache.promise;
    }
    __univerWeekPair = weekPair;

    /* The schedule page's «В календарь» and «Картинкой». Only the student's own press counts; the app builds the file. */
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
    // A press on [node] itself, its target read with the channel's getter taken at document start (univer-channel.js clock).
    var targetOf = typeof __univerClock === 'object' && __univerClock ? __univerClock.target : function (event) { return event.target; };
    function onTap(node, handler) {
        var run = function (event) { if (event && event.isTrusted && targetOf(event) === node) handler(event); };
        if (typeof __univerListen === 'function') __univerListen(node, 'click', run);
        else node.addEventListener('click', run);
    }
    var exporting = false, exportToken = 0;
    function exportButtons(on) {
        Array.prototype.forEach.call(document.querySelectorAll('.univer-app-schedule-export button[data-univer-export]'), function (b) {
            // Busy, not disabled: a disabled button would drop the keyboard focus of the student who pressed it.
            if (on) { b.setAttribute('aria-busy', 'true'); b.setAttribute('aria-disabled', 'true'); } else { b.removeAttribute('aria-busy'); b.removeAttribute('aria-disabled'); }
        });
    }
    function exportDone(token) { if (token !== exportToken) return; exporting = false; exportToken++; exportButtons(false); }
    function exportBox() {
        var select = document.getElementById('weekSelect');
        if (!select || select.tagName !== 'SELECT' || !canAct() || document.querySelector('.univer-app-schedule-export')) return;
        var box = el('div', 'univer-app-schedule-export'); box.setAttribute('role', 'group'); box.setAttribute('aria-label', text().exportLabel);
        [['ics', text().exportIcs, text().exportIcsTitle], ['png', text().exportPng, text().exportPngTitle]].forEach(function (kind) {
            var button = el('button', '', kind[1]); button.type = 'button'; button.title = kind[2]; button.setAttribute('data-univer-export', kind[0]); box.appendChild(button);
            // On the button itself, its kind kept here: a click elsewhere, or on content the page moved into it, is not its press.
            onTap(button, function () { startExport(kind[0]); });
        });
        (select.closest('.ts') || select.parentElement).appendChild(box); owned.push(box);
    }
    function startExport(kind) {
        if (exporting || !canAct() || !SCHEDULE_PAGE.test(location.pathname) || (kind !== 'ics' && kind !== 'png')) return;
        exporting = true; var token = ++exportToken; exportButtons(true);
        var timer = setTimeout(function () { exportDone(token); }, 40000);
        // A page that cannot be read at all is sent as {v:0}: the app says so.
        var send = function (pair) {
            try {
                // Past the channel's limit the message would be dropped without a word: the app is told it failed instead.
                var encode = typeof __univerJson === 'function' ? __univerJson : JSON.stringify, body = '';
                try { body = encode(pair); } catch (e) { body = ''; }
                if (token === exportToken) deliver('univer-export:', body && ('univer-export:' + body).length <= EXPORT_LIMIT ? pair : {v:0, kind:kind});
            } finally { clearTimeout(timer); exportDone(token); }
        };
        // Through the channel's then (taken at document start): a then the page swaps cannot answer for the reading.
        var reading;
        try { reading = readPair(); } catch (_) { send({v:0, kind:kind}); return; }
        var settle = held ? held.then : function (promise, done, failed) { promise.then(done, failed); };
        settle(reading, function (pair) { pair.kind = kind; send(pair); }, function () { send({v:0, kind:kind}); });
    }
    // Hands the week to the app once per page; the same week read again (another pass, the kept copy) is not sent twice.
    // The site alternates odd and even weeks, so the neighbouring week (one GET, read-only) goes along as `second` and the
    // widget and reminders follow the parity; with it unread (or none listed) the week goes alone and repeats as before.
    // The week goes at once; the neighbour is read once an app session for each week (the app keeps it when a later copy
    // comes without it), so an ordinary visit costs no second request and the widget never waits for one.
    var sentWeek = '', weekTicket = 0, PAIRED = 'univer.app.week-paired.v1', WEEK_LIMIT = 131072; // univer-schedule: in MobilePageAdapter NATIVE
    function saveWeek(doc) {
        if (typeof __univerNative !== 'function') return;
        var week = timetable(doc, new Date()); if (!week) return;
        // The schedule page and Бакалавр read today's week: other pages need not read it again (freshWeek).
        try { sessionStorage.setItem(FRESH, account() + '|' + new Date().toDateString()); } catch (_) { }
        var key = JSON.stringify(week);
        if (key === sentWeek) return;
        sentWeek = key; var ticket = ++weekTicket;
        __univerNative('univer-schedule:' + key);
        var paired = account() + '|' + week.week;
        try { if (sessionStorage.getItem(PAIRED) === paired) return; } catch (_) { return; }
        readPair(doc).then(function (pair) {
            if (ticket !== weekTicket) return;
            var message;
            if (pair.weeks[1]) message = 'univer-schedule:' + JSON.stringify(Object.assign({}, week, {second:{week:pair.weeks[1].monday, lessons:pair.weeks[1].lessons}}));
            // The site lists no neighbour (a one-week schedule): the app drops the one it kept. An unread neighbour says nothing.
            else if (!pair.missing.length) message = 'univer-schedule:' + JSON.stringify(Object.assign({}, week, {single:true}));
            else return;
            // Past the channel's limit both weeks would be dropped unseen: the week already sent stays alone, and is not read again.
            if (message.length <= WEEK_LIMIT) __univerNative(message);
            try { sessionStorage.setItem(PAIRED, paired); } catch (_) { }
        }, function () { });
    }

    // The widget keeps the week of the student's last visit, but the start page (news) never shows the schedule: a few
    // seconds after any other page shows, the schedule is read once a day an app session (one GET, read-only) and saved.
    // The mark is set when the read starts, so a page left early does not use up the day.
    var FRESH = 'univer.app.week-fresh.v1', FRESH_DELAY = 4000;
    function freshWeek() {
        if (typeof __univerNative !== 'function' || !account() || document.getElementById('login_frm')) return;
        var at = generation, mark = function () { return account() + '|' + new Date().toDateString(); };
        try { if (sessionStorage.getItem(FRESH) === mark()) return; } catch (_) { return; }
        setTimeout(function () {
            if (at !== generation || !active) return;
            try { if (sessionStorage.getItem(FRESH) === mark()) return; sessionStorage.setItem(FRESH, mark()); } catch (_) { return; }
            once('schedule-day:' + new Date().toDateString(), '/student/myschedule/').then(function (doc) { if (at === generation) saveWeek(doc); }, function () { });
        }, FRESH_DELAY);
    }

    // The grades panel shows the journal's totals, so the app keeps them as seen: a background check does not announce
    // them again. The journal page itself is read by the app; this is the one copy the app cannot read.
    var sentGrades = '';
    function saveGrades(doc) {
        var reader = window.__univerGradeReadV1;
        if (typeof __univerNative !== 'function' || !reader) return;
        var totals = reader.read(doc, '/student/attendance/full/');
        if (!totals || !totals.courses.some(function (course) { return course.scores.length; })) return;
        var message = 'univer-grades:' + JSON.stringify(totals);
        if (message === sentGrades) return;
        sentGrades = message; __univerNative(message);
    }

    /* Grades and requests. */
    function scores(value) {
        var found = [], pattern = /((?:РК|RK|МБ|АБ)\s*[12]|(?:Экз|Exam|Емтихан|Итог|Total)\p{L}*)\s*(?:\(\s*(\d+(?:[.,]\d+)?)\s*\))?\s*:\s*(-?\d+(?:[.,]\d+)?)?/giu, match;
        while ((match = pattern.exec(value))) found.push({label:match[1].replace(/\s+/g, ''), max:match[2] || '', value:match[3] || ''});
        return found;
    }
    // Course headings carry the teaching dates; the bold "РК1 (100): 51 …" cell after each is its total.
    function courseScores(doc) {
        var courses = [], current = null;
        doc.querySelectorAll('#l4 .ct').forEach(function (cell) {
            var value = clean(cell);
            if (/\d{2}\.\d{2}\.\d{4}\s*[-–]\s*\d{2}\.\d{2}\.\d{4}/.test(value)) {
                current = {name:(value.match(/^(.*?)\s*(?:\(\[|\d{2}\.\d{2}\.\d{4})/)?.[1] || value).trim(), token:value.normalize('NFC').toLocaleLowerCase(), scores:[]}; courses.push(current);
            } else if (current && !current.scores.length && cell.querySelector('b,strong') && /(?:РК|RK|МБ|АБ)\s*[12]/i.test(value)) current.scores = scores(value);
        });
        return courses.filter(function (c) { return c.name; });
    }
    function requests(doc) {
        var table = Array.from(doc.querySelectorAll('#l4 table')).filter(function (t) { return !t.closest('#tools') && !t.querySelector('table') && t.rows.length; })
            .sort(function (a, b) { return b.rows.length - a.rows.length; })[0];
        if (!table) return [];
        // Columns by their headings (Услуга, Дата, Статус); the row id is the request number the site opens.
        var head = Array.from(table.rows).find(function (r) { return r.querySelector('th'); }), names = head ? Array.from(head.cells).map(clean) : [];
        var column = function (pattern) { return names.findIndex(function (n) { return pattern.test(n); }); };
        var serviceAt = column(/услуг|қызмет|service/i), dateAt = column(/дата|күні|date/i), statusAt = column(/статус|күй|status/i);
        return Array.from(table.rows).filter(function (r) { return !r.querySelector('th'); }).map(function (row) {
            var cells = Array.from(row.cells).map(clean), at = dateAt >= 0 ? dateAt : cells.findIndex(function (c) { return /\d{2}\.\d{2}\.\d{4}/.test(c); });
            var date = (cells[at] || '').match(/(\d{2})\.(\d{2})\.(\d{4})/);
            if (!date) return null;
            var id = /^\d{1,12}$/.test(row.id) ? row.id : row.querySelector('input[type=checkbox][value]')?.value;
            return {service:cells[serviceAt >= 0 ? serviceAt : at - 1], date:cells[at].replace(/:\d{2}$/, ''), sort:date[3] + date[2] + date[1] + cells[at],
                status:cells[statusAt >= 0 ? statusAt : cells.length - 1], href:/^\d{1,12}$/.test(id || '') ? '/ssc/StatementPage?statementId=' + id : '/ssc/StatementList'};
        }).filter(Boolean).sort(function (a, b) { return a.sort < b.sort ? 1 : -1; });
    }
    function tone(status) {
        var v = status.toLocaleLowerCase();
        return /заверш|выполн|готов|выдан|аяқта|орында|дайын|complete|done|ready|issued/.test(v) ? 'done' : /отклон|отказ|аннул|бас тарт|reject|declin|cancel/.test(v) ? 'fail' :
            /обработ|рассмотр|нов|ожида|қаралу|өңде|process|review|pending|new/.test(v) ? 'wait' : '';
    }

    /* Panels. */
    function pending() { return el('p', 'univer-app-pending', text().loading); }
    function panel(cls, title, href, linkText) {
        var box = el('section', 'univer-app-overview ' + cls); box.setAttribute('aria-label', title);
        var head = el('div', 'univer-app-overview-head'), h = el('h2', '', title), link = el('a', 'univer-app-overview-link', linkText);
        link.href = href; head.append(h, link); box.appendChild(head);
        var body = el('div', 'univer-app-overview-body'); body.appendChild(pending()); box.appendChild(body);
        return {box:box, body:body};
    }
    function settled(body, render) {
        if (window.__univerMotionUI) window.__univerMotionUI.change(body, render, 'content'); else render();
    }
    function fail(body) { settled(body, function () { body.textContent = ''; body.append(el('p', 'univer-app-overview-empty', text().failed)); }); }
    // A list drawn from the last visit that the site did not confirm says when it was read.
    function stale(body, at) {
        if (body.querySelector('.univer-app-overview-stale')) return;
        var date = new Date(at);
        body.appendChild(el('p', 'univer-app-overview-stale', text().stale.replace('{0}', dayMonth(date) + ', ' + clock(date.getHours() * 60 + date.getMinutes()))));
    }
    // A panel with one list: drawn at once from the last visit, then read from the site and redrawn only if it differs.
    function listed(box, name, url, valid, parse, render) {
        var epoch = generation, old = saved(name, valid), shown = '';
        if (old) {
            try { render(box.body, old.list); shown = JSON.stringify(old.list); }
            catch (_) { shown = ''; box.body.textContent = ''; box.body.appendChild(pending()); }
        }
        once(name, url).then(function (doc) {
            var list = parse(doc);
            keep(name, {list:list}, valid);
            if (epoch === generation && JSON.stringify(list) !== shown) settled(box.body, function () { render(box.body, list); });
        }, function () { if (epoch !== generation) return; if (shown) stale(box.body, old.at); else fail(box.body); });
    }
    // Long lists show their first items; the rest open in place with the same motion as other disclosures.
    function limit(list, count) {
        // A button that only reveals one more item is clutter: collapse only when at least two would hide.
        var items = Array.from(list.children); if (items.length <= count + 1) return null;
        items.slice(count).forEach(function (li) { li.hidden = true; });
        var button = el('button', 'univer-app-overview-more'); button.type = 'button'; button.setAttribute('aria-expanded', 'false');
        var paint = function (open) { button.textContent = open ? text().collapse : text().showAll + ' (' + items.length + ')'; button.setAttribute('aria-expanded', String(open)); };
        button.addEventListener('click', function (event) {
            var open = button.getAttribute('aria-expanded') !== 'true';
            var before = list.getBoundingClientRect().height; window.__univerMotionUI?.stop(list);
            items.slice(count).forEach(function (li, index) {
                window.__univerMotionUI?.stop(li); li.hidden = !open;
                if (open && event.detail > 0) window.__univerMotionUI?.reveal(li, 'content', Math.min(index, 2) * 24);
            });
            if (event.detail > 0) window.__univerMotionUI?.resize(list, before, open ? 220 : 180);
            paint(open);
        });
        paint(false); return button;
    }
    // Adds [list] to [body], with a button that shows the items past the first [count] when it has many.
    function appendList(body, list, count) { body.appendChild(list); var more = limit(list, count); if (more) body.appendChild(more); }

    /* Today's classes. */
    function minuteOf(date) { return date.getHours() * 60 + date.getMinutes(); }
    // No classes that day, or every one of them over by minute [at].
    function noneLeft(lessons, at) { return !lessons || !lessons.length || lessons.every(function (l) { return l.end <= at; }); }
    function span(lesson) { return clock(lesson.start) + '–' + clock(lesson.end); }
    // Kazakh month names from our own list: whether Chromium 109 and old Android WebViews format 'kk' dates was never checked.
    var KZ_MONTHS = ['қаңтар','ақпан','наурыз','сәуір','мамыр','маусым','шілде','тамыз','қыркүйек','қазан','қараша','желтоқсан'];
    function dayMonth(date) {
        return language === 'kz' ? date.getDate() + ' ' + KZ_MONTHS[date.getMonth()] : date.toLocaleDateString(language === 'en' ? 'en-GB' : 'ru-RU', {day:'numeric', month:'long'});
    }
    function dayName(date) { return text().days[date.getDay()] + ', ' + dayMonth(date); }
    // "Сегодня, понедельник, 2 ноября", "Завтра, вторник, 3 ноября" or the date alone for a later day.
    function dayCaption(date, now) {
        var shift = Math.round((new Date(date.getFullYear(), date.getMonth(), date.getDate()) - new Date(now.getFullYear(), now.getMonth(), now.getDate())) / 86400000);
        var name = dayName(date), relative = shift === 0 ? text().today : shift === 1 ? text().tomorrow : '';
        if (!relative) return name;
        return relative + ', ' + (language === 'en' ? name : name.charAt(0).toLocaleLowerCase() + name.slice(1));
    }
    function lessonItem(lesson, state, moment) {
        var li = el('li', 'univer-app-overview-lesson' + (state ? ' univer-app-overview-' + state : ''));
        if (lesson.info && window.__univerLesson) {
            // The same labelled card as the schedule page: type, teacher, room and faculty, without course dates.
            var info = Object.assign({}, lesson.info, {dates:[]});
            var card = window.__univerLesson.card(info, span(lesson), language);
            if (state === 'done') card.querySelector('.univer-app-subject').appendChild(stateBadge(lesson, state, moment));
            li.appendChild(card);
        } else {
            var time = el('time', '', span(lesson)), main = el('div', 'univer-app-overview-lesson-main');
            main.appendChild(el('b', '', lesson.subject)); if (lesson.details) main.appendChild(el('span', '', lesson.details));
            li.append(time, main);
            if (state === 'done') li.appendChild(stateBadge(lesson, state, moment));
        }
        return li;
    }
    // The line above the list already counts down to the class under way or the next one; its row is only highlighted,
    // so the time is never said twice. A finished class keeps its quiet "Прошла".
    function stateBadge(lesson, state) { return el('span', 'univer-app-overview-state', text()[state]); }
    function renderToday(body, lessons, upcoming) {
        body.textContent = '';
        var now = new Date(), at = minuteOf(now), day = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
        body.appendChild(el('p', 'univer-app-overview-caption', dayCaption(now, now)));
        if (noneLeft(lessons, at)) {
            var finished = lessons && lessons.length;
            body.appendChild(el('p', 'univer-app-overview-empty', finished ? text().finished : text().noClasses));
            if (!upcoming) return;
            // After today's classes (or on a day off) the first classes of the next day follow, with their day.
            body.appendChild(el('p', 'univer-app-overview-next-day', text().nextClasses + ' · ' + dayCaption(upcoming.date, now)));
            lessons = upcoming.lessons; at = -1; day = new Date(upcoming.date.getFullYear(), upcoming.date.getMonth(), upcoming.date.getDate()).getTime();
        }
        var next = lessons.find(function (l) { return l.start > at; }), live = lessons.find(function (l) { return l.start <= at && at < l.end; });
        var moment = function (minutes) { return day + minutes * 60000; }, t = text();
        // "What now" in one line before the list: the class under way and what follows it, or when the next one starts.
        if (live || next) {
            var up = el('div', 'univer-app-overview-upnext'); up.dataset.state = live ? 'now' : 'next'; up.setAttribute('role', 'status');
            up.appendChild(live ? counter(el('b'), t.upNow + ' · ', 'left', moment(live.end)) : counter(el('b'), t.upNext + ' ', 'until', moment(next.start)));
            var then = live ? lessons.find(function (l) { return l.start >= live.end; }) : null;
            var room = next && next.info && next.info.room ? t.upRoom + ' ' + next.info.room : '';
            up.appendChild(el('span', '', live ? (then ? t.upThen + ': ' + then.subject + ', ' + clock(then.start) : t.upLast) : [next.subject, span(next), room].filter(Boolean).join(' · ')));
            body.appendChild(up);
        }
        var list = el('ol', 'univer-app-overview-lessons');
        lessons.forEach(function (lesson) {
            var state = lesson.start <= at && at < lesson.end ? 'now' : lesson === next ? 'next' : lesson.end <= at ? 'done' : '';
            list.appendChild(lessonItem(lesson, state, moment));
        });
        appendList(body, list, 4);
    }
    // Draws the panel again, keeping its list expanded and its focus when it had them.
    function repaintToday(body, lessons, upcoming, instant) {
        var expanded = body.querySelector('button[aria-expanded=true]'), focused = body.contains(document.activeElement);
        if (instant) renderToday(body, lessons, upcoming); else settled(body, function () { renderToday(body, lessons, upcoming); });
        var more = body.querySelector('button');
        if (expanded && more) more.click();
        if (focused && more) more.focus();
    }
    // The week the site showed, by column, with the first classes of the following week once they were read.
    function weekOf(doc, now, upcoming) {
        var select = doc.getElementById('weekSelect'), table = doc.querySelector('table.schedule'); if (!select || !table) return null;
        var options = Array.from(select.options), current = options.find(function (o) { return o.selected || o.hasAttribute('selected'); }) || options[0];
        var range = current && weekRange(current.value + ' ' + current.textContent), start = range ? range[0].getTime() : NaN;
        // The week ends with the last day the site names: one that starts midweek is not next Monday's.
        var end = range ? range[range.length - 1].getTime() + 86400000 : NaN;
        if (!(now.getTime() >= start && now.getTime() < end)) return null;
        var following = upcoming && upcoming.date.getTime() >= end ? {date:upcoming.date.getTime(), lessons:upcoming.lessons} : null;
        var days = []; for (var column = 1; column < columns(doc); column++) days.push(lessonsOn(doc, column) || []);
        return {start:start, end:end, days:days, following:following};
    }
    // The panel's clock: [drawKept] shows this week from the last visit until the site's page arrives; [update] is the
    // clock tick, where a new day reads the schedule again and the panel is drawn only when what it shows changes.
    function todayPanel(body, epoch, tick) {
        var signature = '', updating = false, loadedDay = new Date().toDateString(), scheduleDoc = null, keptDoc = null, keptMark = '', live = false;
        function show(lessons, upcoming, now, instant) {
            var at = minuteOf(now);
            var key = now.toDateString() + JSON.stringify([lessons, upcoming, (lessons || []).map(function (l) { return at < l.start ? 'next' : at < l.end ? 'now' : 'done'; })]);
            if (key === signature) { tickCounts(body); return; }
            signature = key; repaintToday(body, lessons, upcoming, instant);
        }
        function keepWeek(doc, now, upcoming) {
            var week = weekOf(doc, now, upcoming); if (!week) return;
            var mark = week.start + '|' + !!week.following;
            if (doc === keptDoc && mark === keptMark) return;
            if (!week.following) { var old = saved('schedule', isWeek); if (old && old.start === week.start) week.following = old.following || null; }
            keptDoc = doc; keptMark = mark; keep('schedule', week, isWeek);
        }
        return {
            drawKept: function () {
                var week = saved('schedule', isWeek), first = new Date();
                if (!week || !(first.getTime() >= week.start && first.getTime() < week.end)) return;
                try {
                    var column = first.getDay() === 0 ? 7 : first.getDay(), lessons = first.getDay() === 0 ? [] : week.days[column - 1] || [], upcoming = null;
                    if (noneLeft(lessons, minuteOf(first))) {
                        for (var c = column + 1; !upcoming && c <= week.days.length; c++)
                            if (week.days[c - 1].length) upcoming = {date:new Date(first.getFullYear(), first.getMonth(), first.getDate() + c - first.getDay()), lessons:week.days[c - 1]};
                        if (!upcoming && week.following && week.following.date > first.getTime()) upcoming = {date:new Date(week.following.date), lessons:week.following.lessons};
                    }
                    show(lessons, upcoming, first, true);
                } catch (_) { signature = ''; body.textContent = ''; body.appendChild(pending()); }
            },
            load: function (doc) { scheduleDoc = doc; },
            update: async function () {
                // A hidden page waits, except for the first reading after a panel drawn from the last visit.
                if (updating || epoch !== generation || (live && signature && document.hidden)) return;
                updating = true;
                try {
                    var now = new Date();
                    if (tick) tick(now);
                    if (loadedDay !== now.toDateString()) {
                        try { scheduleDoc = await once('schedule-day:' + now.toDateString(), '/student/myschedule/'); loadedDay = now.toDateString(); saveWeek(scheduleDoc); }
                        catch (_) { if (epoch === generation && signature !== 'unavailable') { signature = 'unavailable'; fail(body); } return; }
                    }
                    var lessons = lessonsToday(scheduleDoc, now), upcoming = null;
                    if (noneLeft(lessons, minuteOf(now))) { try { upcoming = await nextClasses(scheduleDoc, now); } catch (_) { upcoming = null; } }
                    keepWeek(scheduleDoc, now, upcoming);
                    if (epoch === generation) { show(lessons, upcoming, now, false); live = true; }
                } finally { updating = false; }
            }
        };
    }

    /* Grades and requests lists. */
    function scoreChip(score) {
        var chip = el('span', 'univer-app-overview-score'), band = dom.scoreBand(score.value, score.max);
        chip.setAttribute('role', 'img'); chip.setAttribute('aria-label', score.label + ': ' + (score.value || text().none) + (score.max ? ' / ' + score.max : ''));
        chip.append(el('span', 'univer-app-overview-score-label', score.label), el('span', 'univer-app-overview-score-value', score.value || text().none));
        // The bar's colour says where the grade stands against 50, 70 and 90 (the journal explains the colours).
        if (band) { var bar = el('span', 'univer-app-overview-score-bar'); bar.style.setProperty('--univer-score', band.percent + '%'); chip.dataset.band = bar.dataset.band = band.band; chip.appendChild(bar); }
        Array.from(chip.children).forEach(function (c) { c.setAttribute('aria-hidden', 'true'); });
        return chip;
    }
    function courseItem(course) {
        var li = el('li', ''), name = el('a', 'univer-app-overview-course-link', course.name), chips = el('span', 'univer-app-overview-scores');
        name.href = '/student/attendance/full/';
        name.addEventListener('click', function () {
            try {
                sessionStorage.setItem('univer.mobile.attendance-course.v1:/student/attendance/full/', course.token);
                sessionStorage.setItem('univer.mobile.attendance-course.v1:/student/attendance/full', course.token);
            } catch (_) { }
        });
        course.scores.forEach(function (score) { chips.appendChild(scoreChip(score)); });
        li.append(name, chips);
        return li;
    }
    function renderGrades(body, courses) {
        body.textContent = '';
        var graded = courses.filter(function (c) { return c.scores.length; });
        if (!graded.length) { body.appendChild(el('p', 'univer-app-overview-empty', text().noGrades)); return; }
        var list = el('ul', 'univer-app-overview-courses');
        graded.forEach(function (course) { list.appendChild(courseItem(course)); });
        appendList(body, list, 5);
    }
    // Each request opens the site's own request page.
    function requestItem(item) {
        var li = el('li', ''), link = el('a', 'univer-app-overview-request'), main = el('span', 'univer-app-overview-lesson-main'), status = el('span', 'univer-app-status', item.status);
        link.href = item.href; link.setAttribute('aria-label', text().open + ': ' + item.service + ', ' + item.date + ', ' + item.status);
        main.append(el('b', '', item.service), el('span', '', item.date));
        var t = tone(item.status); if (t) status.setAttribute('data-univer-tone', t);
        link.append(main, status); li.appendChild(link);
        return li;
    }
    // The week's independent work, drawn only from what the schedule page kept: the home page reads no syllabus itself.
    function renderWork(body, part) {
        body.textContent = '';
        var t = text(), read = new Date(part.at), first = new Date(part.start), last = new Date(part.end - 86400000);
        // "16–22 ноября" within one month, "30 ноября – 6 декабря" across two.
        body.appendChild(el('p', 'univer-app-overview-caption', first.getMonth() === last.getMonth() ? first.getDate() + '–' + dayMonth(last) : dayMonth(first) + ' – ' + dayMonth(last)));
        var list = el('ul', 'univer-app-overview-work-list');
        part.list.forEach(function (item) {
            var li = el('li', ''), main = el('div', 'univer-app-overview-lesson-main'), course = el('a', 'univer-app-overview-course-link', item.course);
            course.href = item.href; main.appendChild(course);
            var score = item.score > 0 ? t.points + String(item.score).replace('.', language === 'en' ? '.' : ',') : '';
            var meta = [item.name, score].filter(Boolean).join(' · ');
            if (meta) main.appendChild(el('span', 'univer-app-overview-work-meta', meta));
            if (item.topic) { var topic = el('p', 'univer-app-overview-work-topic', item.topic); topic.title = item.topic; main.appendChild(topic); }
            li.appendChild(main); list.appendChild(li);
        });
        appendList(body, list, 4);
        body.appendChild(el('p', 'univer-app-overview-stale', t.workRead.replace('{0}', dayMonth(read) + ', ' + clock(read.getHours() * 60 + read.getMinutes()))));
    }
    // Shown only while the kept week lasts and when it has work in it; it goes when the week ends with the page open.
    function work(after) {
        var part = saved('work', isWork), now = Date.now();
        if (!part || now < part.start || now >= part.end || !part.list.length) return null;
        var box = panel('univer-app-overview-work', text().work, '/student/myschedule/', text().allSchedule); owned.push(box.box);
        try { renderWork(box.body, part); } catch (_) { box.box.remove(); return null; }
        after.after(box.box);
        return {box:box.box, end:part.end};
    }
    function renderRequests(body, items) {
        body.textContent = '';
        if (!items.length) { body.appendChild(el('p', 'univer-app-overview-empty', text().noRequests)); return; }
        var list = el('ul', 'univer-app-overview-requests');
        items.forEach(function (item) { list.appendChild(requestItem(item)); });
        appendList(body, list, 4);
    }

    /* The tab pages. */
    function bachelor() {
        if (document.querySelector('.univer-app-overview-grid')) return;
        var grid = el('div', 'univer-app-overview-grid'); owned.push(grid);
        var today = panel('univer-app-overview-today', text().schedule, '/student/myschedule/', text().allSchedule);
        var grades = panel('univer-app-overview-grades', text().grades, '/student/attendance/full/', text().allGrades);
        grid.append(today.box, grades.box);
        var anchor = document.querySelector('.univer-app-gpa') || document.querySelector('#l4 table.mt');
        if (!anchor) return; if (anchor.classList.contains('univer-app-gpa')) anchor.after(grid); else anchor.before(grid);
        var weekWork = work(grid), epoch = generation, ticker = todayPanel(today.body, epoch, function (now) {
            if (weekWork && now.getTime() >= weekWork.end) { weekWork.box.remove(); weekWork = null; }
        });
        ticker.drawKept();
        once('schedule', '/student/myschedule/').then(async function (doc) {
            saveWeek(doc); ticker.load(doc);
            await ticker.update();
            // A grid built again in the same generation (the site replaced the old one) keeps a single ticker.
            if (epoch === generation) { clearInterval(scheduleTimer); scheduleTimer = setInterval(ticker.update, REFRESH); }
        }, function () { if (epoch === generation) fail(today.body); });
        listed(grades, 'grades', '/student/attendance/full/', isCourses, function (doc) { saveGrades(doc); return courseScores(doc); }, renderGrades);
    }
    function desk() {
        if (document.querySelector('.univer-app-overview-requests-panel')) return;
        var box = panel('univer-app-overview-requests-panel', text().requests, '/ssc/StatementList', text().allRequests); owned.push(box.box);
        var anchor = document.querySelector('.univer-app-desk-cards') || document.querySelector('#l4 .univer-app-service-card')?.closest('.ts') || document.querySelector('.univer-app-credentials') || document.querySelector('.univer-app-ssc-nav');
        if (!anchor) return; anchor.after(box.box);
        listed(box, 'requests', '/ssc/StatementList', isRequests, requests, renderRequests);
    }
    function release() { active = false; generation++; clearInterval(scheduleTimer); owned.forEach(function (node) { window.__univerMotionUI?.stop(node); node.remove(); }); owned = []; }
    window.__univerOverviewV1 = {
        apply: function (lang) {
            if (lang !== language) { language = lang || 'ru'; if (active) this.release(); }
            active = true;
            if (/^\/student\/bachelor\/?$/i.test(location.pathname)) bachelor();
            else if (/^\/ssc\/?$/i.test(location.pathname)) desk();
            else if (/^\/student\/myschedule\//i.test(location.pathname)) { saveWeek(document); exportBox(); }
            else freshWeek();
        },
        release: release,
        // The schedule page's list of this week's independent work, kept for the home page (univer-syllabus.js).
        keepWork: function (lang, part) {
            if (['ru', 'kz', 'en'].indexOf(lang) < 0 || !part || !Array.isArray(part.list)) return;
            var list = part.list.map(function (w) { return w && {course:w.course, href:w.href, name:w.name, topic:w.topic, score:w.score}; });
            keep('work', {start:part.start, end:part.end, list:list}, isWork, lang);
        },
        // Test hooks: the same readers on a supplied document.
        // A site page read once per visit (GET, same origin), shared with the tab-page helpers.
        page: function (url) { return once(url, url); },
        readPair: readPair,
        lessonsToday: lessonsToday, timetable: timetable, courseScores: courseScores, requests: requests, nextClasses: nextClasses, dayCaption: dayCaption
    };
})();
