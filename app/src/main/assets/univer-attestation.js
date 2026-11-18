/* Текущая аттестация (/student/attestation/…): one card per course with its control-period scores, total and grade.
 * The server table, heading and footnote stay in place, hidden in the phone view; Original shows them unchanged. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerAttestationV1) return;
    var dom = window.__univerDomV1, clean = dom.clean, ownRows = dom.ownRows, el = dom.el;
    var language = 'ru', state = null;
    var journalPath = '/student/attendance/full/', journalKey = 'univer.mobile.attendance-course.v1:' + journalPath;
    function text() {
        return language === 'en' ? {region:'Current attestation', none:'none', courses:'Courses', total:'Total', gpa:'GPA', planned:'Credits this semester', year:'this academic year', journal:'Class log', credits:function (n) { return n + (n === '1' ? ' credit' : ' credits'); }} :
            language === 'kz' ? {region:'Ағымдағы аттестация', none:'жоқ', courses:'Пәндер', total:'Жиыны', gpa:'GPA', planned:'Семестрдегі кредиттер', year:'(оқу жылы)', journal:'Сабақ журналы', credits:function (n) { return n + ' кредит'; }} :
            {region:'Текущая аттестация', none:'нет', courses:'Дисциплины', total:'Итог', gpa:'GPA', planned:'Кредитов в семестре', year:'за учебный год', journal:'Журнал занятий', credits:function (n) {
                var v = Math.abs(Number(n)) % 100, d = v % 10;
                return n + (!/^\d+$/.test(n) ? ' кред.' : v > 10 && v < 20 ? ' кредитов' : d === 1 ? ' кредит' : d > 1 && d < 5 ? ' кредита' : ' кредитов');
            }};
    }
    // The heading cell also hosts the adapter's summary; only the site's own text nodes describe the semester.
    function siteText(cell) { return Array.from(cell?.childNodes || []).filter(function (node) { return node.nodeType === 3; }).map(function (node) { return node.data; }).join(' ').replace(/\s+/g, ' ').trim(); }
    function number(value) { return /^-?\d+(?:[.,]\d+)?$/.test(value) ? Number(value.replace(',', '.')) : NaN; }
    function grade(letter) {
        var value = (letter || '').toUpperCase().replace(/\s+/g, '');
        return /^A/.test(value) ? 'a' : /^B/.test(value) ? 'b' : /^C/.test(value) ? 'c' : /^D/.test(value) ? 'd' : /^F/.test(value) ? 'f' : '';
    }
    // The observed header: Дисциплина, Кред., РК1, РК2, ПА*, Сумма, GPA, Букв., Значение (optional periods vary by semester).
    function model() {
        if (!/^\/student\/attestation(\/|$)/.test(location.pathname)) return null;
        var table = Array.from(document.querySelectorAll('#l4 table.inner')).find(function (candidate) {
            var head = ownRows(candidate)[0];
            return head && head.cells.length >= 5 && Array.from(head.cells).every(function (cell) { return cell.tagName === 'TH' && cell.colSpan === 1; }) &&
                Array.from(head.cells).some(function (cell) { return /^(?:РК|RK|АБ|МБ)\s*\d/i.test(clean(cell)); });
        });
        if (!table) return null;
        var rows = ownRows(table), head = rows[0], labels = Array.from(head.cells).map(clean);
        var find = function (pattern) { return labels.findIndex(function (label, i) { return i > 0 && pattern.test(label); }); };
        var columns = {credits:find(/^(кред|cred)/i), total:find(/^(сумма|жиыны|барлығы|total|sum)/i), gpa:find(/^gpa$|\(gpa\)/i),
            letter:find(/^(букв|әріп|letter)/i), value:find(/^(значен|мәні|мағына|value|trad)/i)};
        // Unrecognised wording (another site language): the observed order, when the letter column holds letter grades.
        var n = labels.length, courses = rows.filter(function (row) { return row !== head && row.cells.length === n && Array.from(row.cells).every(function (cell) { return cell.tagName === 'TD'; }); });
        if (!courses.length) return null;
        if (columns.letter < 0 && n >= 7 && courses.every(function (row) { return !clean(row.cells[n - 2]) || /^[A-F][+-]?X?$/i.test(clean(row.cells[n - 2])); }))
            columns = {credits:columns.credits < 0 ? 1 : columns.credits, total:n - 4, gpa:n - 3, letter:n - 2, value:n - 1};
        if (columns.credits < 0) columns.credits = 1;
        var trailing = [columns.total, columns.gpa, columns.letter, columns.value].filter(function (i) { return i > 0; });
        var last = trailing.length ? Math.min.apply(null, trailing) : n, periods = [];
        for (var i = columns.credits + 1; i < last; i++) periods.push(i);
        // Footer rows read "GPA(2026-2027 уч. год)- 0" and "Освоено кредитов (2026-2027 уч. год) - 0".
        var facts = [];
        rows.forEach(function (row) {
            if (row === head || !Array.from(row.cells).every(function (cell) { return cell.tagName === 'TH'; })) return;
            Array.from(row.cells).forEach(function (cell) {
                var match = clean(cell).match(/^(.*?\S)\s*[-–:]\s*(-?\d+(?:[.,]\d+)?)$/);
                // "(2026-2027 уч. год)" repeats the heading's year; the label keeps only "for the academic year".
                var year = /\(\s*\d{4}\s*[-–]\s*\d{4}[^)]*\)/;
                // English: "Grade Point Average (GPA):(2026-2027 уч. год)- 0".
                if (match) facts.push({label:match[1].replace(year, '').replace(/\s*\(/, ' (').trim().replace(/:$/, ''), yearly:year.test(match[1]), value:match[2]});
            });
        });
        var panel = table.closest('table.mt'), heading = panel && ownRows(panel).find(function (row) { return row.classList.contains('top'); })?.querySelector('td.ct');
        var note = panel && Array.from(panel.querySelectorAll('td.ct')).find(function (cell) { return cell.closest('table') === panel && /^(примечание|[еe]скерт(у|пе)|note|additional information)/i.test(clean(cell)); });
        return {table:table, head:head, labels:labels, columns:columns, periods:periods, courses:courses, facts:facts, heading:heading, note:note};
    }
    function tile(label, value, emphasised) {
        var box = el('div', 'univer-app-score' + (emphasised ? ' univer-app-attestation-total' : '')); box.setAttribute('role', 'group');
        box.setAttribute('aria-label', label + ': ' + (value || text().none));
        var name = el('span', 'univer-app-score-label', label), amount = el('span', 'univer-app-score-value', value || text().none);
        box.append(name, amount);
        // Univer grades control periods and the weighted total on a 100-point scale.
        var score = number(value), band = isFinite(score) && score >= 0 && score <= 100 ? dom.scoreBand(String(score), '100') : null;
        if (band) {
            var bar = el('span', 'univer-app-score-bar'); bar.style.setProperty('--univer-score', band.percent + '%');
            box.dataset.band = bar.dataset.band = band.band; box.appendChild(bar);
        }
        Array.from(box.children).forEach(function (child) { child.setAttribute('aria-hidden', 'true'); });
        return box;
    }
    function course(data, row) {
        var cells = Array.from(row.cells), at = function (i) { return i >= 0 && cells[i] ? clean(cells[i]) : ''; };
        var card = el('article', 'univer-app-attestation-course'); card.setAttribute('role', 'listitem');
        var head = el('div', 'univer-app-attestation-head'), name = el('h3', 'univer-app-attestation-name', at(0));
        head.appendChild(name);
        var letter = at(data.columns.letter);
        if (letter) {
            var badge = el('span', 'univer-app-attestation-letter', letter); badge.dataset.grade = grade(letter);
            // A named generic span is not announced; as an image it reads "Букв.: F".
            badge.setAttribute('role', 'img'); badge.setAttribute('aria-label', (data.labels[data.columns.letter] || '') + ': ' + letter); head.appendChild(badge);
        }
        card.appendChild(head);
        var meta = [at(data.columns.credits) ? text().credits(at(data.columns.credits)) : '', at(data.columns.value)].filter(Boolean);
        if (at(data.columns.gpa)) meta.push(text().gpa + ' ' + at(data.columns.gpa));
        var scores =el('div', 'univer-app-attendance-scores univer-app-attestation-scores');
        data.periods.forEach(function (i) { scores.appendChild(tile(data.labels[i], at(i))); });
        if (data.columns.total > 0) scores.appendChild(tile(text().total, at(data.columns.total), true));
        card.appendChild(scores);
        // The class log can open on this course: attendance.js resolves the name to its own course choice.
        var journal = el('a', 'univer-app-attestation-journal', text().journal); journal.href = journalPath;
        journal.setAttribute('aria-label', text().journal + ': ' + at(0));
        journal.addEventListener('click', function () {
            try { sessionStorage.setItem(journalKey, 'name:' + at(0).normalize('NFC').toLocaleLowerCase()); } catch (_) {}
        });
        // Credits, the traditional mark and GPA points share the last line with the journal link.
        var foot = el('div', 'univer-app-attestation-foot');
        foot.append(el('p', 'univer-app-attestation-meta', meta.join(' · ')), journal);
        card.appendChild(foot);
        return card;
    }
    function summary(data) {
        var box = el('section', 'univer-app-attestation-summary'); box.setAttribute('aria-label', text().region);
        var source = siteText(data.heading), parts =source.match(/^[^.]+\.\s*(.+?)\s*(?:\(([^()]+)\))?\s*$/);
        box.appendChild(el('h2', 'univer-app-attestation-term', parts ? parts[1] : source));
        if (parts && parts[2]) box.appendChild(el('p', 'univer-app-attestation-level', parts[2]));
        var stats = el('div', 'univer-app-attestation-stats');
        var stat = function (value, label) {
            var item = el('div', 'univer-app-attestation-stat'); item.setAttribute('role', 'group'); item.setAttribute('aria-label', label + ': ' + value);
            var amount = el('span', 'univer-app-attestation-stat-value', value), caption = el('span', 'univer-app-attestation-stat-label', label);
            amount.setAttribute('aria-hidden', 'true'); caption.setAttribute('aria-hidden', 'true');
            item.append(amount, caption); stats.appendChild(item);
        };
        data.facts.forEach(function (fact) { stat(fact.value, fact.label + (fact.yearly ? ' ' + text().year : '')); });
        var planned = data.courses.reduce(function (sum, row) { var value = number(clean(row.cells[data.columns.credits])); return isFinite(sum) && isFinite(value) ? sum + value : NaN; }, 0);
        if (isFinite(planned) && planned > 0) stat(String(Math.round(planned * 10) / 10), text().planned);
        if (stats.children.length) box.appendChild(stats);
        return box;
    }
    function mark(node, className) { if (node && !node.classList.contains(className)) { node.classList.add(className); state.marked.push([node, className]); } }
    function apply(lang) {
        language = lang;
        if (document.readyState === 'loading') return;
        var data = model();
        if (!data) { release(); return; }
        var signature = language + '|' + siteText(data.heading) + '|' + data.labels.join('|') + '|' + data.courses.map(clean).join('|') + '|' + data.facts.map(function (f) { return f.label + f.value; }).join('|');
        if (state && state.signature === signature && state.view.isConnected && state.table === data.table) return;
        release();
        state = {signature:signature, table:data.table, marked:[], view:el('div', 'univer-app-attestation'), summary:null};
        // The summary replaces the long "Аттестация студента. …" line in its own row, above the semester switch.
        if (data.heading && siteText(data.heading)) { state.summary = summary(data); data.heading.appendChild(state.summary); }
        var list = el('div', 'univer-app-attestation-list'); list.setAttribute('role', 'list'); list.setAttribute('aria-label', text().courses);
        data.courses.forEach(function (row) { list.appendChild(course(data, row)); });
        state.view.appendChild(list);
        // The view leads the table's cell; the table (with any scroll wrapper) and the repeated heading are hidden.
        var cell = data.table.closest('td') || data.table.parentElement;
        cell.insertBefore(state.view, cell.firstChild);
        var source = data.table;
        while (source.parentElement && source.parentElement !== cell && source.parentElement.children.length === 1) source = source.parentElement;
        mark(source, 'univer-app-attestation-source');
        mark(data.table, 'univer-app-attestation-source');
        if (state.summary) mark(data.heading, 'univer-app-attestation-heading');
        mark(data.note, 'univer-app-attestation-note');
        mark(document.documentElement, 'univer-app-attestation-page');
    }
    function release() {
        if (!state) return;
        state.view.remove(); state.summary?.remove();
        state.marked.forEach(function (entry) { entry[0].classList.remove(entry[1]); if (!entry[0].className) entry[0].removeAttribute('class'); });
        state = null;
    }
    window.__univerAttestationV1 = {apply:apply, release:release};
})();
