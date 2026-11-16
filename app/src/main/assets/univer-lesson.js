/* One class card for the schedule page and the Бакалавр "Today" summary, read from the site's schedule cell
   without changing it. As in the Windows app the time leads, then what the class is, where and who teaches it, and the
   name opens the course's materials. Every fact keeps a spoken name ("Аудитория: 309"), not a bare number. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerLesson) return;
    var dom = window.__univerDomV1, clean = dom.clean, el = dom.el;
    // Only a recognised class type is split off the subject: "Казахский (русский) язык" keeps its brackets.
    var TYPE = /^(лек|сем|прак|лаб|срс|оқыт|дәр|зерт|сөж|lect|semin|pract|lab|tutor|office)/i;
    // "Ауд.: 309", "ауд. 309", "Дәрісхана: 309" and "Room 309" all keep only the room itself.
    var ROOM = /^(аудитория|ауд|дәрісхана|aud|room)\.?\s*:?\s*/i;
    var KINDS = [['lecture', /^(лек|дәр|lect)/i], ['seminar', /^(сем|semin)/i], ['practice', /^(прак|pract)/i], ['lab', /^(лаб|зерт|lab)/i]];
    function words(language) {
        return language === 'en' ? {teacher:'Teacher', room:'Room', roomShort:'Room', faculty:'Faculty', dates:'Course dates'} :
            language === 'kz' ? {teacher:'Оқытушы', room:'Дәрісхана', roomShort:'Дәрісхана', faculty:'Факультет', dates:'Курс мерзімі'} :
            {teacher:'Преподаватель', room:'Аудитория', roomShort:'Ауд.', faculty:'Факультет', dates:'Даты курса'};
    }
    function dates(value) { return clean(value).match(/\d{1,2}\.\d{2}(?:\.\d{2,4})?/g) || []; }
    function read(group) {
        var info = {subject:'', type:'', kind:'', teacher:'', room:'', faculty:'', dates:[], extra:[]};
        var parts = Array.from(group.querySelectorAll('p')); if (!parts.length) parts = [group];
        parts.forEach(function (part) {
            if (part.classList.contains('params')) {
                var spans = Array.from(part.children).filter(function (n) { return clean(n); });
                (spans.length ? spans : [part]).forEach(function (span) {
                    var value = clean(span), found = dates(value);
                    if (span.classList?.contains('aud_faculty')) info.faculty = value;
                    else if (ROOM.test(value)) info.room = value.replace(ROOM, '') || value;
                    else if (!info.type && TYPE.test(value) && value.length <= 30) info.type = value;
                    else if (span.classList?.contains('dateStartLbl') || (found.length >= 2 && /период|кезең|period|\bс\b|\bпо\b/i.test(value))) info.dates = found.slice(0, 2);
                    else info.extra.push(value);
                });
                return;
            }
            var value = clean(part); if (!value) return;
            if (!info.subject) {
                var match = value.match(/^(.*\S)\s*\(([^()]{2,30})\)\s*$/);
                if (match && TYPE.test(match[2].trim())) { info.subject = match[1]; info.type = match[2].trim(); }
                else info.subject = value;
            } else if (!info.teacher && part.classList.contains('teacher')) info.teacher = value;
            else if (/период|кезең|period/i.test(value) && dates(value).length >= 2) info.dates = dates(value).slice(0, 2);
            else info.extra.push(value);
        });
        if (info.type) info.kind = (KINDS.find(function (k) { return k[1].test(info.type); }) || ['other'])[0];
        return info;
    }
    var courses = null;
    // The observed course list: rows are tr.link[id], and the site's dblclick handler opens /student/umkd/ + id + /year/term.
    // Read once per page; a fetched page's scripts are parsed as text and never run.
    function courseList(force) {
        if (courses && !force) return courses;
        courses = (async function () {
            var abort = new AbortController(), timer = setTimeout(function () { abort.abort(); }, 15000);
            try {
            var response = await fetch('/student/umkd/', {credentials:'same-origin', cache:'no-store', redirect:'error', signal:abort.signal}), final = new URL(response.url, location.origin);
            if (response.status !== 200 || final.origin !== location.origin || /^\/user\/login/i.test(final.pathname)) throw new Error('Unavailable');
            var html = await dom.boundedText(response, 4 * 1024 * 1024);
            var doc = new DOMParser().parseFromString(html, 'text/html');
            var term = Array.from(doc.scripts).map(function (s) { return s.textContent; }).join('\n').match(/["']\/student\/umkd\/["']\s*\+\s*this\.id\s*\+\s*["'](\/\d{4}\/[12])["']/);
            if (!term) throw new Error('Semester unavailable');
            return Array.from(doc.querySelectorAll('#l4 table.inner tr.link[id]')).filter(function (r) { return /^\d+$/.test(r.id) && r.cells.length === 3; })
                .map(function (r) { return {name:clean(r.cells[1]), href:'/student/umkd/' + r.id + term[1]}; });
            } finally { clearTimeout(timer); abort.abort(); }
        })();
        // One attempt per page: a failed read leaves the links on the course list instead of asking again for every class.
        courses.catch(function () { });
        return courses;
    }
    // Where a course's materials are; the list itself when the course is not in the current semester.
    function courseHref(name) {
        var key = dom.fold(name);
        return courseList().then(function (list) {
            var found = list.find(function (c) { return dom.fold(c.name) === key; }) || list.find(function (c) { return key.startsWith(dom.fold(c.name)) || dom.fold(c.name).startsWith(key); });
            return found ? found.href : '/student/umkd/';
        }, function () { return '/student/umkd/'; });
    }
    // Plain text of the facts, used where a single line is enough (the "Now / Next" card).
    function summary(info, language) {
        var w = words(language);
        return [info.room ? w.roomShort + ' ' + info.room : '', info.faculty, info.teacher].concat(info.extra).filter(Boolean).join(' · ');
    }
    // [shown] on screen with its spoken name before it, read only by a screen reader.
    function labelled(tag, className, spoken, shown) {
        var node = el(tag, className);
        node.append(el('span', 'univer-app-sr', spoken + ': '), document.createTextNode(shown));
        return node;
    }
    function fact(kind, spoken, shown) { return labelled('li', 'univer-app-fact univer-app-fact-' + kind, spoken, shown); }
    // The card body: the name, then type, room and faculty on one line, the teacher and (in the detailed view) course dates.
    function fill(main, info, language) {
        var w = words(language), subject = el('p', 'univer-app-subject'), name = el('a', 'univer-app-lesson-name', info.subject);
        name.href = '/student/umkd/'; subject.appendChild(name); main.appendChild(subject);
        courseHref(info.subject).then(function (href) { name.href = href; });
        var facts = el('ul', 'univer-app-lesson-facts');
        if (info.type || info.room || info.faculty) {
            // "Лекция  Ауд. 309  ФИТ", spoken as "Лекция, Аудитория: 309, Факультет: ФИТ".
            var place = el('li', 'univer-app-fact univer-app-fact-place');
            if (info.type) {
                var type = el('span', 'univer-app-lesson-type', info.type.charAt(0).toUpperCase() + info.type.slice(1));
                type.dataset.kind = info.kind; place.appendChild(type);
            }
            if (info.room) place.appendChild(labelled('span', 'univer-app-fact-room', w.room, w.roomShort + ' ' + info.room));
            if (info.faculty) place.appendChild(labelled('span', 'univer-app-fact-faculty', w.faculty, info.faculty));
            facts.appendChild(place);
        }
        if (info.teacher) facts.appendChild(fact('teacher', w.teacher, info.teacher));
        info.extra.forEach(function (value) { facts.appendChild(el('li', 'univer-app-fact univer-app-fact-note', value)); });
        if (info.dates.length === 2) facts.appendChild(fact('dates univer-app-lesson-detail univer-app-lesson-period', w.dates, info.dates.join(' – ')));
        if (facts.children.length) main.appendChild(facts);
        return name;
    }
    function card(info, timeText, language) {
        var article = el('article', 'univer-app-lesson'), time = el('time', ''), main = el('div', 'univer-app-lesson-main');
        var times = timeText.split('–');
        time.append(el('b', '', times[0]), el('span', '', times[1] || ''));
        time.setAttribute('aria-label', timeText);
        if (info.kind) article.dataset.kind = info.kind;
        article.append(time, main);
        var name = fill(main, info, language);
        // The whole card leads to the course's materials.
        article.addEventListener('click', function (event) { if (!event.target.closest('a,button')) name.click(); });
        return article;
    }
    window.__univerLesson = {read:read, card:card, summary:summary, words:words, courses:courseList};
})();
