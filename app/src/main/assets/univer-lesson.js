/* One class card for the schedule page and the Бакалавр "Today" summary, read from the site's schedule cell
   without changing it. Every fact keeps a spoken name ("Аудитория: 309"), not a bare number. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerLesson) return;
    var dom = window.__univerDomV1, clean = dom.clean, el = dom.el;
    // Only a recognised class type is split off the subject: "Казахский (русский) язык" keeps its brackets.
    var TYPE = /^(лек|сем|прак|лаб|срс|оқыт|дәр|зерт|сөж|lect|semin|pract|lab|tutor|office)/i;
    // "Ауд.: 309", "ауд. 309" and "Room 309" all keep only the room itself.
    var ROOM = /^(аудитория|ауд|aud|room)\.?\s*:?\s*/i;
    var KINDS = [['lecture', /^(лек|дәр|lect)/i], ['seminar', /^(сем|semin)/i], ['practice', /^(прак|pract)/i], ['lab', /^(лаб|зерт|lab)/i]];
    function words(language) {
        return language === 'en' ? {teacher:'Teacher', room:'Room', roomShort:'Room', faculty:'Faculty', dates:'Course dates'} :
            language === 'kz' ? {teacher:'Оқытушы', room:'Аудитория', roomShort:'Ауд.', faculty:'Факультет', dates:'Курс мерзімі'} :
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
    // The card body: subject with its type chip, then teacher, place and (in the detailed view) course dates.
    function fill(card, info, language) {
        var w = words(language), head = card.querySelector(':scope>.univer-app-lesson-head');
        if (info.type) {
            var type = el('span', 'univer-app-lesson-type', info.type.charAt(0).toUpperCase() + info.type.slice(1));
            type.dataset.kind = info.kind; (head || card).appendChild(type);
        }
        card.appendChild(el('p', 'univer-app-subject', info.subject));
        var facts = el('ul', 'univer-app-lesson-facts');
        if (info.teacher) facts.appendChild(fact('teacher', w.teacher, info.teacher));
        if (info.room || info.faculty) {
            // Room and faculty share one line: "Ауд. 309 · ФИТ", spoken as "Аудитория: 309, Факультет: ФИТ".
            var place = el('li', 'univer-app-fact univer-app-fact-place');
            if (info.room) place.appendChild(labelled('span', 'univer-app-fact-room', w.room, w.roomShort + ' ' + info.room));
            if (info.faculty) place.appendChild(labelled('span', 'univer-app-fact-faculty', w.faculty, w.faculty.toLowerCase() + ' ' + info.faculty));
            facts.appendChild(place);
        }
        info.extra.forEach(function (value) { facts.appendChild(el('li', 'univer-app-fact univer-app-fact-note', value)); });
        if (info.dates.length === 2) facts.appendChild(fact('dates univer-app-lesson-detail univer-app-lesson-period', w.dates, info.dates.join(' – ')));
        if (facts.children.length) card.appendChild(facts);
        return card;
    }
    function card(info, timeText, language) {
        var article = el('article', 'univer-app-lesson'), head = el('div', 'univer-app-lesson-head');
        if (info.kind) article.dataset.kind = info.kind;
        head.appendChild(el('time', '', timeText)); article.appendChild(head);
        return fill(article, info, language);
    }
    window.__univerLesson = {read:read, card:card, fill:fill, summary:summary, words:words};
})();
