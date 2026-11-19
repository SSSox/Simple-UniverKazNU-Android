/* Read-only summaries on the tab pages: Бакалавр shows today's classes and the grade summary, the Е-услуги desk
   shows the latest requests. Each is read from the site's own GET page once per visit and never changes it. */
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
    Object.assign(WORDS.en, {upNow:'Class in progress', upNext:'Next class', upThen:'Then', upLast:'Last class today', upRoom:'Room'});
    Object.assign(WORDS.kz, {upNow:'Сабақ жүріп жатыр', upNext:'Келесі сабақ', upThen:'Содан кейін', upLast:'Бүгінгі соңғы сабақ', upRoom:'Дәрісхана'});
    Object.assign(WORDS.ru, {upNow:'Идёт пара', upNext:'Следующая пара', upThen:'Затем', upLast:'Это последняя пара сегодня', upRoom:'Ауд.'});
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
    async function read(url, keep) {
        var abort = new AbortController(), timeout = setTimeout(function () { abort.abort(); }, READ_TIMEOUT);
        try {
            var response = await fetch(url, {credentials:'same-origin', cache:'no-store', signal:abort.signal});
            var final = new URL(response.url, location.origin);
            if (response.status !== 200 || final.origin !== location.origin || /^\/user\/login/i.test(final.pathname)) throw new Error('Unavailable');
            var html = await response.text(); if (html.length > MAX_HTML) throw new Error('Oversized');
            var doc = new DOMParser().parseFromString(html, 'text/html');
            if (doc.querySelector('#login_frm')) throw new Error('Session expired');
            if (keep) keep(html);
            return doc;
        } finally { clearTimeout(timeout); }
    }
    function once(key, url) { if (!loads.has(key)) loads.set(key, read(url)); return loads.get(key); }
    // The Бакалавр panels' pages (the week's schedule, the grades) are kept for KEPT_AGE in this app session, for the
    // account that signed in last and the page's language: coming back to the tab within minutes reads nothing again.
    // Sign-out erases the site's storage; another account never gets them.
    var KEPT = 'univer.app.overview.v1:', KEPT_AGE = 10 * 60000, KEPT_SIZE = 1500000;
    function account() { try { return localStorage.getItem('univer.app.account.v1') || ''; } catch (_) { return ''; } }
    function kept(key, url) {
        if (loads.has(key)) return loads.get(key);
        var name = KEPT + language + ':' + url, owner = account(), saved = null;
        try { saved = JSON.parse(sessionStorage.getItem(name)); } catch (_) { }
        var age = saved ? Date.now() - saved.at : -1;
        if (saved && saved.owner === owner && typeof saved.html === 'string' && age >= 0 && age < KEPT_AGE) {
            var doc = new DOMParser().parseFromString(saved.html, 'text/html');
            if (!doc.querySelector('#login_frm')) { loads.set(key, Promise.resolve(doc)); return loads.get(key); }
        }
        loads.set(key, read(url, function (html) {
            try { if (html.length <= KEPT_SIZE) sessionStorage.setItem(name, JSON.stringify({owner:owner, at:Date.now(), html:html})); else sessionStorage.removeItem(name); } catch (_) { }
        }));
        return loads.get(key);
    }

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
    // Only a week that holds [now]: its Monday, every class with its weekday (Monday = 0), clock times, names and course
    // dates, and the account the page belongs to. Another week, an undated page or a page without the table gives null.
    function timetable(doc, now) {
        var select = doc.getElementById('weekSelect'); if (!select || !doc.querySelector('table.schedule')) return null;
        var options = Array.from(select.options), current = options.find(function (o) { return o.selected || o.hasAttribute('selected'); }) || options[0];
        var range = current && weekRange(current.value + ' ' + current.textContent); if (!range) return null;
        var monday = new Date(range[0].getFullYear(), range[0].getMonth(), range[0].getDate() - (range[0].getDay() + 6) % 7);
        var today = new Date(now.getFullYear(), now.getMonth(), now.getDate()), next = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 7);
        if (today < monday || today >= next) return null;
        var lessons = [];
        for (var column = 1; column < Math.min(columns(doc), 8); column++) (lessonsOn(doc, column) || []).forEach(function (lesson) {
            var info = lesson.info || {}, dates = courseDates(info.dates || [], monday);
            if (lesson.end <= lesson.start) return;
            lessons.push({day:column - 1, start:clock(lesson.start), end:clock(lesson.end), subject:(info.subject || lesson.subject).slice(0, 200),
                type:(info.type || '').slice(0, 60), room:(info.room || '').slice(0, 100), faculty:(info.faculty || '').slice(0, 100), teacher:(info.teacher || '').slice(0, 200),
                from:dates.from, until:dates.until});
        });
        return {v:1, week:isoDay(monday), owner:account().slice(0, 128), lessons:lessons.slice(0, 80)};
    }
    // Hands the week to the app once per page; the same week read again (another pass, the kept copy) is not sent twice.
    var sentWeek = '';
    function saveWeek(doc) {
        if (typeof __univerNative !== 'function') return;
        var week = timetable(doc, new Date()); if (!week) return;
        var message = 'univer-schedule:' + JSON.stringify(week);
        if (message === sentWeek) return;
        sentWeek = message; __univerNative(message);
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
    function panel(cls, title, href, linkText) {
        var box = el('section', 'univer-app-overview ' + cls); box.setAttribute('aria-label', title);
        var head = el('div', 'univer-app-overview-head'), h = el('h2', '', title), link = el('a', 'univer-app-overview-link', linkText);
        link.href = href; head.append(h, link); box.appendChild(head);
        var body = el('div', 'univer-app-overview-body'); body.appendChild(el('p', 'univer-app-pending', text().loading)); box.appendChild(body);
        return {box:box, body:body};
    }
    function settled(body, render) {
        if (window.__univerMotionUI) window.__univerMotionUI.change(body, render, 'content'); else render();
    }
    function fail(body) { settled(body, function () { body.textContent = ''; body.append(el('p', 'univer-app-overview-empty', text().failed)); }); }
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
    function dayName(date) {
        return text().days[date.getDay()] + ', ' + (language === 'kz' ? date.getDate() + ' ' + KZ_MONTHS[date.getMonth()] :
            date.toLocaleDateString(language === 'en' ? 'en-GB' : 'ru-RU', {day:'numeric', month:'long'}));
    }
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
    function repaintToday(body, lessons, upcoming) {
        var expanded = body.querySelector('button[aria-expanded=true]'), focused = body.contains(document.activeElement);
        settled(body, function () { renderToday(body, lessons, upcoming); });
        var more = body.querySelector('button');
        if (expanded && more) more.click();
        if (focused && more) more.focus();
    }
    // The panel's clock tick: a new day reads the schedule again, and the panel is drawn only when what it shows changes.
    function todayUpdater(body, doc, epoch) {
        var signature = '', updating = false, loadedDay = new Date().toDateString(), scheduleDoc = doc;
        return async function update() {
            if (updating || epoch !== generation || (signature && document.hidden)) return;
            updating = true;
            try {
                var now = new Date();
                if (loadedDay !== now.toDateString()) {
                    try { scheduleDoc = await once('schedule-day:' + now.toDateString(), '/student/myschedule/'); loadedDay = now.toDateString(); saveWeek(scheduleDoc); }
                    catch (_) { if (epoch === generation && signature !== 'unavailable') { signature = 'unavailable'; fail(body); } return; }
                }
                var lessons = lessonsToday(scheduleDoc, now), upcoming = null, at = minuteOf(now);
                if (noneLeft(lessons, at)) { try { upcoming = await nextClasses(scheduleDoc, now); } catch (_) { upcoming = null; } }
                var key = now.toDateString() + JSON.stringify([lessons, upcoming, (lessons || []).map(function (l) { return at < l.start ? 'next' : at < l.end ? 'now' : 'done'; })]);
                if (epoch === generation && key !== signature) { signature = key; repaintToday(body, lessons, upcoming); }
                else if (epoch === generation) tickCounts(body);
            } finally { updating = false; }
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
    function renderRequests(body, items) {
        body.textContent = '';
        if (!items.length) { body.appendChild(el('p', 'univer-app-overview-empty', text().noRequests)); return; }
        var list = el('ul', 'univer-app-overview-requests');
        items.forEach(function (item) { list.appendChild(requestItem(item)); });
        appendList(body, list, 4);
    }

    /* The tab pages. */
    // Draws the page read once under [key] into [body], or the failure note; a release in between leaves [body] alone.
    function fill(body, key, url, epoch, render, load) {
        (load || once)(key, url).then(function (doc) { if (epoch === generation) settled(body, function () { render(doc); }); }, function () { if (epoch === generation) fail(body); });
    }
    function bachelor() {
        if (document.querySelector('.univer-app-overview-grid')) return;
        var grid = el('div', 'univer-app-overview-grid'); owned.push(grid);
        var today = panel('univer-app-overview-today', text().schedule, '/student/myschedule/', text().allSchedule);
        var grades = panel('univer-app-overview-grades', text().grades, '/student/attendance/full/', text().allGrades);
        grid.append(today.box, grades.box);
        var anchor = document.querySelector('.univer-app-gpa') || document.querySelector('#l4 table.mt');
        if (!anchor) return; if (anchor.classList.contains('univer-app-gpa')) anchor.after(grid); else anchor.before(grid);
        var epoch = generation;
        kept('schedule', '/student/myschedule/').then(async function (doc) {
            saveWeek(doc);
            var update = todayUpdater(today.body, doc, epoch);
            await update();
            // A grid built again in the same generation (the site replaced the old one) keeps a single clock.
            if (epoch === generation) { clearInterval(scheduleTimer); scheduleTimer = setInterval(update, REFRESH); }
        }, function () { if (epoch === generation) fail(today.body); });
        fill(grades.body, 'grades', '/student/attendance/full/', epoch, function (doc) { renderGrades(grades.body, courseScores(doc)); saveGrades(doc); }, kept);
    }
    function desk() {
        if (document.querySelector('.univer-app-overview-requests-panel')) return;
        var box = panel('univer-app-overview-requests-panel', text().requests, '/ssc/StatementList', text().allRequests); owned.push(box.box);
        var anchor = document.querySelector('.univer-app-desk-cards') || document.querySelector('#l4 .univer-app-service-card')?.closest('.ts') || document.querySelector('.univer-app-credentials') || document.querySelector('.univer-app-ssc-nav');
        if (!anchor) return; anchor.after(box.box);
        fill(box.body, 'requests', '/ssc/StatementList', generation, function (doc) { renderRequests(box.body, requests(doc)); });
    }
    function release() { active = false; generation++; clearInterval(scheduleTimer); owned.forEach(function (node) { window.__univerMotionUI?.stop(node); node.remove(); }); owned = []; }
    window.__univerOverviewV1 = {
        apply: function (lang) {
            if (lang !== language) { language = lang || 'ru'; if (active) this.release(); }
            active = true;
            if (/^\/student\/bachelor\/?$/i.test(location.pathname)) bachelor();
            else if (/^\/ssc\/?$/i.test(location.pathname)) desk();
            else if (/^\/student\/myschedule\//i.test(location.pathname)) saveWeek(document);
        },
        release: release,
        // Test hooks: the same readers on a supplied document.
        // A site page read once per visit (GET, same origin), shared with the tab-page helpers.
        page: function (url) { return once(url, url); },
        lessonsToday: lessonsToday, timetable: timetable, courseScores: courseScores, requests: requests, nextClasses: nextClasses, dayCaption: dayCaption
    };
})();
