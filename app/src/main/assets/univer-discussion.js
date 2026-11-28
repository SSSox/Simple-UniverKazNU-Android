/* Дистанционные курсы: a row the site opens only on dblclick becomes a card that replays click + dblclick on a tap.
 * Source rows and handlers stay in place, hidden. Tasks live in one panel with open and closed tabs and a single
 * Refresh; GET reads (3 at a time) are cached per account for ten minutes, an ended week with closed tasks for a day. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerDiscussionV1) return;
    var dom = window.__univerDomV1, clean = dom.clean, ownRows = dom.ownRows, el = dom.el, setText = dom.setText, setHidden = dom.setHidden;
    var language = 'ru', lists = new Map(), marked = [], help = null, facts = null, waiting = false, forwarded = false;
    var weekTasks = null, task = null, owned = [];
    // Toolbar buttons that only open the selected row; every card already opens its row.
    var openers = '#subjectShow_btn,#specialityOpen_btn,#discipline_btn,#add_task_btn,#preview_btn';
    // Toolbar actions on the selected row; each card offers them for its own row.
    var rowActions = ['#lessons_btn', '#mark_btn'];
    var cacheKey = 'univer.app.discussion-tasks.v1', cacheCourses = 10 * 60000, cacheWeeks = 86400000;
    var cacheSize = 400000, readWait = 15000;
    var overview = null, taskCards = new Map(), saved = null;
    // Column headings observed on the site in Russian, Kazakh and English ("Бітіру уақыты", "Completion date", "Number of jobs").
    var columnStart = /^(дата начала|басталу|басталған|start)/i, columnEnd = /^(дата окончания|аяқталу|бітіру|end|completion)/i;
    var columnTasks = /задани|тапсырма|task|job/i;
    var columnPeriod = /^(период|кезең|оқу мерзімі|period)$/i, columnWeek = /^(неделя|апта|week)$/i;
    var columnTitle = /дисциплин|специальн|название|пән|мамандық|мамандығ|атау|discipline|specialty|speciality|title|name/i;
    function text() {
        return language === 'en' ? {instructions:'Instructions', current:'This week', description:'Description', items:'List', about:'About the course',
                due:'Open tasks', closedHeading:'Closed tasks', openTab:'Open', closedTab:'Closed', viewLabel:'Which assignments to show',
                refresh:'Refresh', noClosed:'No closed assignments', checking:'Checking tasks…',
                checkingCourse:function (d, t) { return 'Checking courses: ' + d + ' of ' + t; },
                noDue:'No open tasks', failed:'Some courses could not be checked', noTasks:'No tasks',
                deadline:function (d) { return 'due ' + d; }, today:'today', tomorrow:'tomorrow', tasks:function (n) { return n + (n === 1 ? ' task' : ' tasks'); },
                courseTasks:'Course tasks', noCourseTasks:'No tasks in this course yet', closed:'closed', partial:'Some tasks could not be loaded',
                allWeeks:function (n) { return 'Show all weeks (' + n + ')'; }, hideEmpty:'Hide empty weeks', attempts:'Attempts', maxScore:'Max score', format:'Answer',
                until:function (d) { return 'Due ' + d; }, opens:function (d) { return 'Opens ' + d; }, ended:'Deadline has passed',
                left:function (n, hours) { return hours ? n + ' h left' : n + (n === 1 ? ' day left' : ' days left'); }, more:'Show more', less:'Show less',
                hint:'Up to 3 files', noMessages:'No messages yet'} :
            language === 'kz' ? {instructions:'Нұсқаулықтар', current:'Ағымдағы апта', description:'Сипаттама', items:'Тізім', about:'Курс туралы',
                due:'Ашық тапсырмалар', closedHeading:'Жабық тапсырмалар', openTab:'Ашық', closedTab:'Жабық', viewLabel:'Қай тапсырмаларды көрсету',
                refresh:'Жаңарту', noClosed:'Жабық тапсырмалар жоқ', checking:'Тапсырмалар тексерілуде…',
                checkingCourse:function (d, t) { return 'Пәндер тексерілуде: ' + d + ' / ' + t; },
                noDue:'Ашық тапсырмалар жоқ', failed:'Кейбір пәндерді тексеру мүмкін болмады', noTasks:'Тапсырма жоқ',
                deadline:function (d) { return 'мерзімі: ' + d; }, today:'бүгін', tomorrow:'ертең', tasks:function (n) { return n + ' тапсырма'; },
                courseTasks:'Пән тапсырмалары', noCourseTasks:'Бұл пәнде әзірге тапсырма жоқ', closed:'мерзімі өтті', partial:'Тапсырмалардың бір бөлігі жүктелмеді',
                allWeeks:function (n) { return 'Барлық апталар (' + n + ')'; }, hideEmpty:'Бос апталарды жасыру', attempts:'Жауап саны', maxScore:'Ең жоғары балл', format:'Жауап',
                until:function (d) { return d + ' дейін'; }, opens:function (d) { return d + ' ашылады'; }, ended:'Мерзімі өтті',
                left:function (n, hours) { return n + (hours ? ' сағат' : ' күн') + ' қалды'; }, more:'Толығырақ', less:'Жасыру',
                hint:'3 файлға дейін', noMessages:'Хабарламалар әзірге жоқ'} :
            {instructions:'Инструкции', current:'Текущая неделя', description:'Описание', items:'Список', about:'О курсе',
                due:'Открытые задания', closedHeading:'Закрытые задания', openTab:'Открытые', closedTab:'Закрытые', viewLabel:'Какие задания показать',
                refresh:'Обновить', noClosed:'Закрытых заданий нет', checking:'Проверяем задания…',
                checkingCourse:function (d, t) { return 'Проверяем дисциплины: ' + d + ' из ' + t; },
                noDue:'Открытых заданий нет', failed:'Не удалось проверить часть дисциплин', noTasks:'Заданий нет',
                deadline:function (d) { return 'срок ' + d; }, today:'сегодня', tomorrow:'завтра', tasks:function (n) {
                    var v = n % 100, d = n % 10; return n + (v > 10 && v < 20 ? ' заданий' : d === 1 ? ' задание' : d > 1 && d < 5 ? ' задания' : ' заданий');
                },
                courseTasks:'Задания предмета', noCourseTasks:'В этом предмете пока нет заданий', closed:'срок прошёл', partial:'Не удалось загрузить часть заданий',
                allWeeks:function (n) { return 'Показать все недели (' + n + ')'; }, hideEmpty:'Скрыть пустые недели', attempts:'Попыток', maxScore:'Макс. балл', format:'Ответ',
                until:function (d) { return 'До ' + d; }, opens:function (d) { return 'Откроется ' + d; }, ended:'Срок сдачи прошёл',
                left:function (n, hours) {
                    var v = n % 100, d = n % 10, few = !(v > 10 && v < 20) && d > 1 && d < 5, one = !(v > 10 && v < 20) && d === 1;
                    return 'Осталось ' + n + (hours ? (one ? ' час' : few ? ' часа' : ' часов') : (one ? ' день' : few ? ' дня' : ' дней'));
                }, more:'Показать полностью', less:'Свернуть',
                hint:'До 3 файлов', noMessages:'Сообщений пока нет'};
    }
    function mark(node, className) { if (node && !node.classList.contains(className)) { node.classList.add(className); marked.push([node, className]); } }
    function unmark(predicate) {
        marked = marked.filter(function (entry) {
            if (predicate && !predicate(entry[0])) return true;
            entry[0].classList.remove(entry[1]); if (!entry[0].className) entry[0].removeAttribute('class');
            return false;
        });
    }
    // jQuery 1.4 keeps bound handlers in jQuery.data(element, 'events'); the site binds them on document ready.
    function opens(row) {
        try { var events = window.jQuery?.data?.(row, 'events'), list = events && events.dblclick; return !!(list && (list.length || Object.keys(list).length)); }
        catch (_) { return false; }
    }
    function dates(value) { return value.match(/\d{2}\.\d{2}\.\d{4}/g) || []; }
    function day(value) { var p = value.split('.'); return new Date(+p[2], +p[1] - 1, +p[0]).getTime(); }
    function today() { var now = new Date(); now.setHours(0, 0, 0, 0); return now.getTime(); }
    function period(from, to) {
        // "01.09.2026 00:00-06.09.2026 00:00" reads as "01.09 – 06.09.2026" within one year.
        if (!to) return from;
        return (from.slice(6) === to.slice(6) ? from.slice(0, 5) : from) + ' – ' + to;
    }
    function deadline(value) {
        var left = Math.round((day(value) - today()) / 86400000);
        return text().deadline(left === 0 ? text().today : left === 1 ? text().tomorrow : value.slice(0, 5));
    }
    function shortLabel(label) {
        return /ресурс|resource/i.test(label) ? (language === 'en' ? 'Resources' : language === 'kz' ? 'Ресурстар' : 'Ресурсы') :
            columnTasks.test(label) ? (language === 'en' ? 'Tasks' : language === 'kz' ? 'Тапсырмалар' : 'Задания') :
            /участник|қатысушы|participant/i.test(label) ? (language === 'en' ? 'Participants' : language === 'kz' ? 'Қатысушылар' : 'Участники') :
            label.replace(/\s*\((дд|dd|кк|күні|day)[^)]*\)\s*/i, '').replace(/:$/, '');
    }
    function linkRows(root) { return Array.from(root.querySelectorAll('table.mt table.inner tr.link[id],table.mt table.inner tr.file[id]')); }
    // A list keeps its header in the same table, or (weeks) in a separate one-row table just before it.
    function headerOf(table) {
        var own = ownRows(table).find(function (row) { return row.cells.length > 1 && Array.from(row.cells).every(function (cell) { return cell.tagName === 'TH'; }); });
        if (own) return {row:own, table:null};
        var cell = table.closest('td'), previous = cell && Array.from(cell.querySelectorAll('table.inner')).filter(function (candidate) {
            return candidate !== table && candidate.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING && !candidate.querySelector('tr.link');
        }).pop();
        var rows = previous ? ownRows(previous) : [];
        return rows.length === 1 && Array.from(rows[0].cells).every(function (c) { return c.tagName === 'TH'; }) ? {row:rows[0], table:previous} : {row:null, table:null};
    }
    function labelsOf(table) { var header = headerOf(table); return header.row ? Array.from(header.row.cells).map(clean) : []; }
    // Task rows read "Название (0). Автор: Фамилия Имя"; the card shows the name and the author separately.
    function taskTitle(value) { var match = value.match(/^(.*?\S)\s*\(\d+\)\.?\s*(?:Автор|Author)\s*:/i); return match ? match[1] : value; }
    function taskAuthor(value) { var match = value.match(/\(\d+\)\.?\s*(Автор|Author)\s*:\s*(.+)$/i); return match ? {label:match[1], value:match[2]} : null; }
    // The discipline list's academic year and term: from its address, or from the site's own row handler.
    function termKey() {
        var match = location.pathname.match(/^\/student\/discussion\/index\/(\d{4})\/(\d{1,2})\/?$/);
        if (match) return match[1] + '/' + match[2];
        if (!/^\/student\/discussion\/index\/?$/.test(location.pathname)) return null;
        for (var script of Array.from(document.scripts)) {
            var found = script.textContent.match(/acpos\/list\/"\s*\+\s*this\.id\s*\+\s*"\/(\d{4})\/(\d{1,2})\//);
            if (found) return found[1] + '/' + found[2];
        }
        return null;
    }
    function model(table) {
        var header = headerOf(table), labels = header.row ? Array.from(header.row.cells).map(clean) : [];
        var rows = ownRows(table).filter(function (row) { return row.matches('tr.link[id],tr.file[id]'); });
        var find = function (pattern) { return labels.findIndex(function (label) { return pattern.test(label); }); };
        var columns = {week:find(columnWeek), start:find(columnStart), end:find(columnEnd), title:find(columnTitle), period:find(columnPeriod)};
        var index = !!termKey();
        return {table:table, header:header, rows:rows.map(function (row) {
            var cells = Array.from(row.cells), icon = cells[0]?.querySelector('img'), skip = icon && !clean(cells[0]) ? 0 : -1;
            var titleIndex = columns.title >= 0 ? columns.title : cells.findIndex(function (cell, i) { return i !== skip && i !== columns.week && clean(cell); });
            var title = clean(cells[titleIndex]), count = title.match(/^(.*\S)\s*\((\d+)\)$/), facts = [], range = '';
            if (columns.start >= 0) range = period(clean(cells[columns.start]), columns.end >= 0 ? clean(cells[columns.end]) : '');
            var span = columns.period >= 0 ? dates(clean(cells[columns.period])) : [], tasks = 0, resources = 0;
            cells.forEach(function (cell, i) {
                if ([skip, titleIndex, columns.week, columns.start, columns.end, columns.period].indexOf(i) >= 0 || !clean(cell) || !labels[i]) return;
                if (columnTasks.test(labels[i])) tasks = parseInt(clean(cell), 10) || 0;
                if (/ресурс|resource/i.test(labels[i])) resources = parseInt(clean(cell), 10) || 0;
                facts.push({label:shortLabel(labels[i]), value:clean(cell)});
            });
            var author = taskAuthor(title); if (author) facts.unshift(author);
            var all = columns.start >= 0 ? dates(clean(cells[columns.start])).concat(dates(clean(cells[columns.end] || ''))) : span;
            var current = row.classList.contains('normal') || (all.length >= 2 && day(all[0]) <= today() && today() <= day(all[1]));
            var name = count ? count[1] : title;
            return {row:row, id:row.id, kind:row.id === 'other' ? 'people' : columns.week >= 0 ? 'week' : row.matches('.file') ? 'download' : /file/.test(icon?.getAttribute('src') || '') ? 'file' : 'folder',
                week:columns.week >= 0 ? clean(cells[columns.week]) : '', title:columns.week >= 0 && span.length ? period(span[0], span[1]) : (count && count[2] !== '0' ? title : taskTitle(name)),
                discipline:index && /^\d+$/.test(row.id) ? row.id : '', name:name,
                range:range, facts:facts, description:(row.getAttribute('title') || '').trim(), opens:opens(row), current:columns.week >= 0 && current,
                tasks:tasks, resources:resources, started:!all.length || day(all[0]) <= today(), until:all[1] ? day(all[1]) + 86399999 : 0};
        })};
    }
    function labelWeek(number) { return (language === 'en' ? 'Week ' : language === 'kz' ? 'Апта ' : 'Неделя ') + number; }
    // The site's own addresses (from its row handlers): a plan's weeks and a task's page.
    function weekPath(plan, discipline, term) { return '/student/discussion/acpos/week/list/' + plan + '/' + discipline + '/' + term + '/'; }
    function taskPath(task, plan, discipline, term, week) { return '/student/discussion/task/preview/' + task + '/' + plan + '/' + discipline + '/' + term + '/' + week + '/0/0/0/'; }
    function activate(row) {
        // A real double click: the first click selects the row (some pages open "the selected row"), then dblclick opens it.
        ['click', 'dblclick'].forEach(function (type) { row.dispatchEvent(new MouseEvent(type, {bubbles:true, cancelable:true, view:window, detail:type === 'click' ? 1 : 2})); });
    }
    function card(entry, actions) {
        var item = el('div', 'univer-app-dist-card'); item.setAttribute('role', 'listitem'); item.dataset.kind = entry.kind;
        if (entry.current) item.dataset.current = 'true';
        var main = el(entry.opens ? 'button' : 'div', 'univer-app-dist-main');
        if (entry.opens) {
            main.type = 'button';
            main.addEventListener('click', function () {
                // A discipline with a single study plan opens its weeks directly: the plan page would only repeat that choice.
                var plans = entry.discipline && overview?.results.get(entry.discipline)?.plans, term = termKey();
                if (plans && plans.length === 1 && term) { location.href = weekPath(plans[0], entry.discipline, term); return; }
                activate(entry.row);
            });
        }
        var lead = el('span', 'univer-app-dist-lead', entry.week || ''); lead.setAttribute('aria-hidden', 'true');
        var body = el('span', 'univer-app-dist-body'), title = el('span', 'univer-app-dist-title', entry.title);
        body.appendChild(title);
        var meta = [entry.range].concat(entry.facts.map(function (fact) { return fact.label + ': ' + fact.value; })).filter(Boolean);
        if (entry.current) body.insertBefore(el('span', 'univer-app-dist-badge', text().current), title);
        if (meta.length) body.appendChild(el('span', 'univer-app-dist-meta', meta.join(' · ')));
        main.append(lead, body);
        var label = [entry.current ? text().current : '', (entry.week ? labelWeek(entry.week) + ', ' : '') + entry.title].concat(meta).filter(Boolean).join('. ');
        // Only the button gets a name; a passive card is read from its visible text.
        if (entry.opens) main.setAttribute('aria-label', label);
        // A week with tasks shows their number where the eye scans: beside the chevron.
        if (entry.tasks > 0) { var weekTasks = el('span', 'univer-app-dist-count', String(entry.tasks)); weekTasks.dataset.state = 'open'; weekTasks.setAttribute('aria-hidden', 'true'); main.appendChild(weekTasks); }
        if (entry.discipline) {
            // Filled by the task overview once this discipline's weeks are read.
            var stats = el('span', 'univer-app-dist-meta univer-app-dist-tasks'); stats.hidden = true; body.appendChild(stats);
            var count = el('span', 'univer-app-dist-count'); count.hidden = true; count.setAttribute('aria-hidden', 'true'); main.appendChild(count);
            taskCards.set(entry.discipline, {main:main, stats:stats, count:count, label:label, name:entry.name});
        }
        item.appendChild(main);
        if (entry.description && entry.description !== entry.title) {
            var details = el('details', 'univer-app-dist-description'), summary = el('summary', '', text().description);
            details.append(summary, el('p', '', entry.description)); item.appendChild(details);
        }
        // "Проведенные уроки", "Сводный список": select this row with the site's own click, then press the site's button.
        // Weeks that have not started have no lessons held yet, so they skip the row actions.
        if (entry.opens && actions.length && entry.started) {
            var row = el('div', 'univer-app-dist-actions');
            actions.forEach(function (action) {
                var button = el('button', 'univer-app-dist-secondary', clean(action)); button.type = 'button';
                button.setAttribute('aria-label', clean(action) + ': ' + (entry.week ? labelWeek(entry.week) : entry.title));
                button.addEventListener('click', function () { entry.row.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window})); action.click(); });
                row.appendChild(button);
            });
            item.appendChild(row);
        }
        return item;
    }
    // The outermost wrapper (site div, adapter scroll box) that holds nothing but this table; scroll hints do not count.
    function sourceOf(node, stop) {
        var only = function (parent) { return Array.from(parent.children).filter(function (child) { return !child.classList.contains('univer-app-scroll-hint'); }).length === 1; };
        while (node.parentElement && node.parentElement !== stop && only(node.parentElement)) node = node.parentElement;
        return node;
    }
    function fold() {
        if (help?.box.isConnected) { setText(help.box.querySelector('summary'), text().instructions + ' (' + help.count + ')'); return; }
        var panel = Array.from(document.querySelectorAll('#l4 table.mt')).find(function (table) {
            var top = ownRows(table)[0];
            return top?.classList.contains('top') && /^(инструкция|нұсқаулық|instruction)/i.test(clean(top)) && table.querySelector('a[href$=".pdf"]');
        });
        if (!panel) return;
        var count = panel.querySelectorAll('a[href]').length, box = el('details', 'univer-app-dist-help'), home = document.createComment('univer-original-instructions');
        box.appendChild(el('summary', '', text().instructions + ' (' + count + ')'));
        panel.before(home); home.after(box); box.appendChild(panel);
        mark(ownRows(panel)[0], 'univer-app-dist-source');
        help = {box:box, home:home, panel:panel, count:count};
    }
    // "Предмет: … Текущая неделя: 9" (table.xp with label/value cells) folds into one line that opens to the full list.
    function courseFacts() {
        var table = Array.from(document.querySelectorAll('#l4 table.xp')).find(function (candidate) {
            return candidate.querySelectorAll('li').length >= 2 && Array.from(candidate.querySelectorAll('td')).length % 2 === 0;
        });
        if (facts && (facts.table !== table || !facts.box.isConnected)) { facts.box.remove(); facts = null; }
        if (!table) return;
        var pairs = [];
        Array.from(table.querySelectorAll('td')).forEach(function (cell, i, cells) {
            if (i % 2 === 0 && cell.querySelector('li') && clean(cells[i + 1])) pairs.push({label:clean(cell).replace(/:$/, ''), value:clean(cells[i + 1])});
        });
        var signature = language + '|' + pairs.map(function (p) { return p.label + p.value; }).join('|');
        if (!pairs.length || facts?.signature === signature) return;
        facts?.box.remove();
        var box = el('details', 'univer-app-dist-facts'), week = pairs.find(function (p) { return /текущая неделя|ағымдағы апта|current week/i.test(p.label); });
        box.appendChild(el('summary', '', pairs[0].value + (week ? ' · ' + week.label + ': ' + week.value : '')));
        var list = el('dl');
        pairs.forEach(function (pair) { list.append(el('dt', '', pair.label), el('dd', '', pair.value)); });
        // Outside the adapter's scroll box, which is hidden together with the table.
        box.appendChild(list); (table.parentElement.matches('.univer-app-table-scroll') ? table.parentElement : table).before(box);
        mark(table, 'univer-app-dist-source');
        facts = {table:table, box:box, signature:signature};
    }
    function apply(lang) {
        language = lang;
        if (!/^\/student\/discussion(\/|$)/.test(location.pathname)) { release(); return; }
        if (document.readyState === 'loading') return;
        // Handlers appear on jQuery's ready; adapt again then so the cards can open their rows.
        if (window.jQuery && !window.jQuery.isReady && !waiting) {
            waiting = true;
            window.jQuery(function () { waiting = false; window.__univerMobileV1?.mutate(function () { apply(language); }); });
        }
        mark(document.documentElement, 'univer-app-discussion-page');
        // A discipline's study-plan page lists a single plan in practice: open its weeks at once, replacing this page in history.
        var planPage = location.pathname.match(/^\/student\/discussion\/acpos\/list\/(\d+)\/(\d{4})\/(\d{1,2})\/?$/);
        if (planPage && !forwarded) {
            var plans = linkRows(document).filter(function (row) { return /^\d+$/.test(row.id); });
            if (plans.length === 1) { forwarded = true; location.replace(weekPath(plans[0].id, planPage[1], planPage[2] + '/' + planPage[3])); return; }
        }
        fold(); courseFacts(); taskPage();
        var actions = rowActions.map(function (selector) { return document.querySelector('#l4 ' + selector); }).filter(Boolean);
        lists.forEach(function (record, table) { if (!table.isConnected || !record.view.isConnected) drop(table); });
        document.querySelectorAll('#l4 table.inner').forEach(function (table) {
            if (!table.closest('#tools,.univer-app-dist-help') && table.querySelector('tr.link[id],tr.file[id]')) adaptList(table, actions);
        });
        if (lists.size) document.querySelectorAll(openers).forEach(function (button) { if (button.closest('#l4')) mark(button, 'univer-app-dist-source'); });
        if (lists.size) actions.forEach(function (action) { mark(action, 'univer-app-dist-source'); });
        markHeads();
        paint();
    }
    // One site list of disciplines, weeks or tasks becomes a list of cards above it; the table stays as the source.
    function adaptList(table, actions) {
        var data = model(table);
        if (!data.rows.length) { drop(table); return; }
        var signature = language + '|' + actions.map(clean).join(',') + '|' + data.rows.map(function (entry) {
            return [entry.id, entry.title, entry.range, entry.week, entry.opens, entry.current, entry.started, entry.description.length].concat(entry.facts.map(function (f) { return f.label + f.value; })).join('/');
        }).join('|');
        if (lists.get(table)?.signature === signature) return;
        drop(table);
        var view = el('div', 'univer-app-dist-list'); view.setAttribute('role', 'list'); view.setAttribute('aria-label', text().items);
        data.rows.forEach(function (entry) { view.appendChild(card(entry, actions)); });
        var cell = table.closest('td') || table.parentElement;
        cell.insertBefore(view, cell.firstChild);
        var sources = [sourceOf(table, cell), table];
        if (data.header.table) sources.push(sourceOf(data.header.table, cell));
        sources.forEach(function (node) { mark(node, 'univer-app-dist-source'); });
        var record = {view:view, signature:signature, sources:sources, extra:[]};
        lists.set(table, record);
        var disciplines = data.rows.filter(function (entry) { return entry.discipline; });
        if (disciplines.length) startOverview(disciplines, view);
        if (data.rows.some(function (entry) { return entry.kind === 'week'; })) { quietWeeks(record, data.rows); startWeekTasks(record, data.rows); }
    }
    // The page heading with its links stays; the semester caption row that only repeats it
    // ("Список дисциплин 2026-2027 год Осенний Семестр") is hidden.
    function markHeads() {
        var heads = [];
        document.querySelectorAll('#l4 table.mt tr.top>td.ct').forEach(function (cell) {
            if (cell.querySelector('a') && !cell.closest('.univer-app-dist-help')) { mark(cell, 'univer-app-dist-head'); heads.push(clean(cell)); }
        });
        document.querySelectorAll('#l4 tr.brk>td.ct').forEach(function (cell) {
            if (clean(cell) && heads.some(function (head) { return head.includes(clean(cell)); })) mark(cell.closest('tr'), 'univer-app-dist-source');
        });
    }
    // Task overview on the discipline list.
    function limiter(size) {
        var active = 0, queue = [];
        function next() {
            if (active >= size || !queue.length) return;
            var job = queue.shift(); active++;
            job.run().then(job.resolve, job.reject).finally(function () { active--; next(); });
        }
        return function (run) { return new Promise(function (resolve, reject) { queue.push({run:run, resolve:resolve, reject:reject}); next(); }); };
    }
    // A read the server leaves unanswered ends at its deadline, body included: its course is reported as not checked
    // instead of holding the "checking" state and a place in the gate for good.
    function fetchPage(path, signal) {
        var abort = new AbortController(), done = false;
        var cancel = function () { if (!done) abort.abort(); };
        var timer = setTimeout(cancel, readWait);
        var stop = function () { clearTimeout(timer); if (signal) signal.removeEventListener('abort', cancel); };
        if (signal) {
            if (signal.aborted) cancel(); else signal.addEventListener('abort', cancel);
        }
        return fetch(path, {credentials:'same-origin', cache:'no-store', signal:abort.signal}).then(function (response) {
            var url = new URL(response.url || path, location.href);
            // A login page, Univer's 213 "sign in" answer or any redirect is not data: the discipline is reported as not checked.
            if (response.status !== 200 || url.origin !== location.origin || !/^\/student\/discussion\//.test(url.pathname)) throw new Error('unexpected page');
            return response.text();
        }).then(function (html) {
            var doc = new DOMParser().parseFromString(html, 'text/html');
            if (doc.querySelector('#login_frm') || !doc.querySelector('table.mt')) throw new Error('not a course page');
            return doc;
        }).then(function (doc) { done = true; stop(); return doc; }, function (error) { done = true; stop(); throw error; });
    }
    // Cached reads, per account and language: a course list for ten minutes, a week that ended days ago with all its
    // tasks closed for a day. Only parsed titles, dates and links are kept — never the site's markup — at most a few
    // hundred records, and nothing without the account mark the sign-in form leaves.
    function account() { try { return localStorage.getItem('univer.app.account.v1') || ''; } catch (_) { return ''; } }
    function fresh(at, limit) { return typeof at === 'number' && at <= Date.now() + 60000 && Date.now() - at < limit; }
    function stored() {
        var who = account();
        if (!who) return null;
        if (saved && saved.owner === who) return saved;
        var value = null;
        try { value = JSON.parse(localStorage.getItem(cacheKey) || 'null'); } catch (_) { value = null; }
        return saved = value && typeof value === 'object' && value.owner === who && value.courses && typeof value.courses === 'object' &&
            value.weeks && typeof value.weeks === 'object' ? value : {owner:who, courses:{}, weeks:{}};
    }
    function store(field, key, value, limit) {
        var cache = stored(); if (!cache) return;
        var records = cache[field]; records[key] = value;
        var keys = Object.keys(records).sort(function (a, b) { return (records[a].at || 0) - (records[b].at || 0); });
        while (keys.length > limit) delete records[keys.shift()];
        try {
            var raw = JSON.stringify(cache);
            while (raw.length > cacheSize && keys.length) { delete records[keys.shift()]; raw = JSON.stringify(cache); }
            if (raw.length <= cacheSize) localStorage.setItem(cacheKey, raw);
        } catch (_) { }
    }
    function planPath(discipline, term) { return '/student/discussion/acpos/list/' + discipline + '/' + term + '/'; }
    function validItem(item) {
        return !!item && typeof item === 'object' && typeof item.title === 'string' && item.title.length > 0 && item.title.length <= 400 &&
            (item.end === '' || /^\d{2}\.\d{2}\.\d{4}$/.test(item.end)) && /^\/student\/discussion\/(task|acpos|resource)\//.test(item.href || '');
    }
    function cachedCourse(cache, name, discipline, term) {
        if (!cache) return null;
        var record = cache.courses[language + '|' + planPath(discipline, term)];
        if (!record || record.name !== name || !fresh(record.at, cacheCourses) || typeof record.tasks !== 'number' || !Array.isArray(record.items)) return null;
        return {tasks:record.tasks, items:record.items.filter(validItem).slice(0, 200),
            plans:Array.isArray(record.plans) ? record.plans.filter(function (id) { return /^\d+$/.test(id); }).slice(0, 6) : []};
    }
    // The week rows of a weeks page: their id, the site's own task count and where the week ends (its last minute).
    function weekRows(doc) {
        var rows = linkRows(doc); if (!rows.length) return [];
        var labels = labelsOf(rows[0].closest('table')), tasks = labels.findIndex(function (label) { return columnTasks.test(label); });
        if (tasks < 0) throw new Error('weeks');
        var spans = [];
        labels.forEach(function (label, i) { if (columnEnd.test(label) || columnPeriod.test(label)) spans.push(i); });
        return rows.map(function (row) {
            var cells = Array.from(row.cells), window = [];
            spans.forEach(function (i) { window = window.concat(dates(clean(cells[i] || ''))); });
            return {id:row.id, tasks:parseInt(clean(cells[tasks]), 10) || 0, end:window[1] ? day(window[1]) + 86399999 : 0};
        });
    }
    function taskRows(doc) {
        var rows = linkRows(doc).filter(function (row) { return row.matches('.link'); }); if (!rows.length) return [];
        var labels = labelsOf(rows[0].closest('table'));
        var title = labels.findIndex(function (l) { return /название|атау|title|name/i.test(l); }), end = labels.findIndex(function (l) { return columnEnd.test(l); });
        return rows.map(function (row) { return {id:row.id, title:taskTitle(clean(row.cells[title >= 0 ? title : 1])), end:end >= 0 ? dates(clean(row.cells[end]))[0] || '' : ''}; });
    }
    function tasksOf(discipline, term, signal, run, cache, force) {
        var tail = discipline + '/' + term + '/', partial = false;
        return run(function () { return fetchPage(planPath(discipline, term), signal); }).then(function (doc) {
            var plans = linkRows(doc).map(function (row) { return row.id; }).filter(function (id) { return /^\d+$/.test(id); }).slice(0, 6);
            return Promise.all(plans.map(function (plan) {
                return run(function () { return fetchPage('/student/discussion/acpos/week/list/' + plan + '/' + tail, signal); }).then(function (doc) {
                    return {plan:plan, weeks:weekRows(doc)};
                }, function (error) { if (signal.aborted) throw error; partial = true; return {plan:plan, weeks:[]}; });
            }));
        }).then(function (plans) {
            var busy = [], total = 0;
            plans.forEach(function (plan) { plan.weeks.forEach(function (week) {
                total += week.tasks; if (week.tasks > 0 && /^\d+$/.test(week.id)) busy.push({plan:plan.plan, week:week});
            }); });
            return Promise.all(busy.slice(0, 24).map(function (entry) {
                var href = '/student/discussion/task/list/' + entry.plan + '/' + tail + entry.week.id + '/';
                return readTasks(href, entry.week, signal, run, cache, force, function (id) {
                    // The task opens at the address the site's own dblclick uses; the week's list is the fallback.
                    return /^\d+$/.test(id) ? '/student/discussion/task/preview/' + id + '/' + entry.plan + '/' + tail + entry.week.id + '/0/0/0/' : href;
                }).then(null, function (error) { if (signal.aborted) throw error; partial = true; return []; });
            })).then(function (found) {
                return {tasks:total, items:[].concat.apply([], found), plans:plans.map(function (plan) { return plan.plan; }), partial:partial};
            });
        });
    }
    // One week's task list, served from the cache when the week ended days ago and every task in it is closed.
    function readTasks(url, week, signal, run, cache, force, link) {
        var count = Math.max(0, week.tasks || 0), key = language + '|' + url, kept = cache && cache.weeks[key];
        var ended = week.end > 0 && week.end <= Date.now() - cacheWeeks;
        if (!force && ended && kept && fresh(kept.at, cacheWeeks) && kept.count === count && Array.isArray(kept.items))
            return Promise.resolve(kept.items.filter(validItem).slice(0, 200));
        return run(function () { return fetchPage(url, signal); }).then(function (doc) {
            var items = taskRows(doc).map(function (task) { return {title:task.title, end:task.end, href:link(task.id)}; });
            if (ended && items.length && items.every(function (item) { return item.end && day(item.end) < today(); }))
                store('weeks', key, {at:Date.now(), count:count, items:items}, 512);
            return items;
        });
    }
    function open(item) { return !item.end || day(item.end) >= today(); }
    function safePath(value) { return typeof value === 'string' && /^\/student\/discussion\/(task|acpos|resource)\//.test(value) ? value : ''; }
    // One list of tasks, split by whether they can still be handed in; the nearest deadline first in each.
    function grouped(items) {
        var groups = {open:[], closed:[]};
        items.forEach(function (item) { groups[open(item) ? 'open' : 'closed'].push(item); });
        groups.open.sort(function (a, b) { return (a.end ? day(a.end) : Infinity) - (b.end ? day(b.end) : Infinity); });
        groups.closed.sort(function (a, b) { return (b.end ? day(b.end) : 0) - (a.end ? day(a.end) : 0); });
        return groups;
    }
    function taskRowLink(item) {
        var entry = el('div'), href = safePath(item.href), link = el(href ? 'a' : 'span', 'univer-app-dist-due-item');
        entry.setAttribute('role', 'listitem');
        if (href) link.href = href;
        var isOpen = open(item);
        if (!isOpen) link.dataset.closed = 'true'; else if (item.end && day(item.end) - today() <= 86400000) link.dataset.soon = 'true';
        link.append(el('span', 'univer-app-dist-due-name', item.title),
            el('span', 'univer-app-dist-due-meta', [item.course || item.context || '', item.end ? (isOpen ? deadline(item.end) : text().closed + ' ' + item.end.slice(0, 5)) : ''].filter(Boolean).join(' · ')));
        entry.appendChild(link); return entry;
    }
    // A tasks panel: one head with the single Refresh, one row of tabs, one list per tab (the hidden one is out of the
    // eye's way and out of a screen reader's alike).
    function tasksBox(className) {
        var section = el('section', className + ' univer-app-dist-tasks-panel'), head = el('div', 'univer-app-dist-panel-head');
        section.setAttribute('aria-live', 'polite');
        var title = el('h2', 'univer-app-dist-due-title'), refresh = el('button', 'univer-app-dist-refresh'); refresh.type = 'button';
        head.append(title, refresh);
        var status = el('p', 'univer-app-dist-due-status'); status.setAttribute('role', 'status');
        var failed = el('p', 'univer-app-dist-failed'); failed.setAttribute('aria-live', 'polite');
        var tabs = el('div', 'univer-app-dist-tabs'); tabs.setAttribute('role', 'group');
        var body = el('div', 'univer-app-dist-bodies'), panels = {open:el('div', 'univer-app-dist-tab-panel'), closed:el('div', 'univer-app-dist-tab-panel')};
        var lists = {open:el('div', 'univer-app-dist-due-list'), closed:el('div', 'univer-app-dist-due-list')};
        var empty = {open:el('p', 'univer-app-dist-empty'), closed:el('p', 'univer-app-dist-empty')};
        ['open', 'closed'].forEach(function (view) {
            panels[view].dataset.view = view; lists[view].setAttribute('role', 'list');
            empty[view].hidden = true; panels[view].append(lists[view], empty[view]);
        });
        body.append(panels.open, panels.closed);
        section.append(head, status, failed, tabs, body);
        return {section:section, title:title, refresh:refresh, status:status, failed:failed, tabs:tabs, panels:panels, lists:lists, empty:empty};
    }
    function renderTabs(box, view, counts, pick) {
        box.panels.open.hidden = view !== 'open'; box.panels.closed.hidden = view !== 'closed';
        var signature = language + '|' + view + '|' + counts.open + '|' + counts.closed;
        if (box.tabs.dataset.signature === signature) return;
        box.tabs.dataset.signature = signature; box.tabs.textContent = '';
        box.tabs.hidden = !(counts.open + counts.closed);
        if (box.tabs.hidden) return;
        box.tabs.setAttribute('aria-label', text().viewLabel);
        [['open', text().openTab, counts.open], ['closed', text().closedTab, counts.closed]].forEach(function (entry) {
            var button = el('button', ''); button.type = 'button'; button.dataset.view = entry[0];
            button.append(el('span', '', entry[1]), el('span', 'univer-app-dist-tab-count', String(entry[2])));
            button.setAttribute('aria-pressed', String(view === entry[0]));
            button.addEventListener('click', function () { if (view !== entry[0]) pick(entry[0]); });
            box.tabs.appendChild(button);
        });
    }
    function renderList(box, view, items, emptyText) {
        var list = box.lists[view];
        setHidden(box.empty[view], !emptyText || !!items.length);
        setText(box.empty[view], emptyText || '');
        var signature = language + '|' + view + '|' + items.map(function (item) {
            return [item.href, item.title, item.end, item.course, item.context].join('/');
        }).join('|');
        if (list.dataset.signature === signature) return;
        list.dataset.signature = signature; list.textContent = '';
        items.forEach(function (item) { list.appendChild(taskRowLink(item)); });
    }
    // The task count on each discipline card, from the same overview.
    function paintCards() {
        taskCards.forEach(function (nodes, id) {
            var result = overview?.results.get(id), ready = !!result && !result.error;
            setHidden(nodes.stats, !ready); setHidden(nodes.count, !ready || !result.tasks);
            if (!ready) return;
            var pending = result.items.filter(open).sort(function (a, b) { return (a.end ? day(a.end) : Infinity) - (b.end ? day(b.end) : Infinity); });
            var line = result.tasks ? text().tasks(result.tasks) + (pending[0]?.end ? ' · ' + deadline(pending[0].end) : '') : text().noTasks;
            setText(nodes.stats, line); setText(nodes.count, String(result.tasks));
            var state = pending.length ? 'open' : 'closed';
            if (nodes.count.dataset.state !== state) nodes.count.dataset.state = state;
            if (nodes.main.tagName === 'BUTTON' && nodes.main.getAttribute('aria-label') !== nodes.label + '. ' + line) nodes.main.setAttribute('aria-label', nodes.label + '. ' + line);
        });
    }
    // One panel for every discipline's tasks: cached results show at once, the rest follow; Refresh rereads them all in
    // the same panel, and a failed course leaves a named retry state instead of an endless "checking".
    function startOverview(disciplines, view, force) {
        var term = termKey();
        if (!term) return;
        var key = language + '|' + term + '|' + disciplines.map(function (entry) { return entry.discipline; }).join(',');
        var previous = overview;
        if (previous && previous.key === key && !force) {
            if (view && view.isConnected) { previous.anchor = view; view.before(previous.box.section); }
            paint(); return;
        }
        var box = previous && previous.key === key ? previous.box : tasksBox('univer-app-dist-due');
        if (previous) { previous.controller.abort(); if (previous.key !== key) previous.box.section.remove(); }
        var controller = new AbortController(), run = limiter(3), cache = stored();
        var current = overview = {key:key, controller:controller, results:new Map(), names:new Map(), box:box, anchor:view,
            view: previous && previous.key === key ? previous.view : 'open'};
        box.refresh.onclick = function () {
            if (overview === current && current.anchor.isConnected) startOverview(disciplines, current.anchor, true);
        };
        setText(box.refresh, text().refresh);
        view.before(box.section);
        disciplines.forEach(function (entry) {
            current.names.set(entry.discipline, entry.name);
            var known = force ? null : cachedCourse(cache, entry.name, entry.discipline, term);
            current.results.set(entry.discipline, known);
            if (known) return;
            tasksOf(entry.discipline, term, controller.signal, run, cache, force).then(function (result) {
                if (controller.signal.aborted || overview !== current) return null;
                if (!result.partial) store('courses', language + '|' + planPath(entry.discipline, term),
                    {at:Date.now(), items:result.items, tasks:result.tasks, plans:result.plans, name:entry.name}, 120);
                return result;
            }, function (error) { return controller.signal.aborted ? null : {error:true}; }).then(function (result) {
                if (controller.signal.aborted || overview !== current) return;
                current.results.set(entry.discipline, result);
                window.__univerMobileV1 ? window.__univerMobileV1.mutate(paint) : paint();
            });
        });
        paint();
    }
    function paint() {
        if (!overview) return;
        var box = overview.box, waiting = 0, failed = [], items = [];
        overview.results.forEach(function (result, id) {
            if (!result) { waiting++; return; }
            if (result.error) { failed.push(overview.names.get(id)); return; }
            if (result.partial) failed.push(overview.names.get(id));
            result.items.forEach(function (item) { items.push({title:item.title, end:item.end, href:item.href, course:overview.names.get(id)}); });
        });
        var groups = grouped(items), counts = {open:groups.open.length, closed:groups.closed.length};
        var loading = waiting > 0;
        box.section.dataset.state = loading ? 'loading' : failed.length ? 'failed' : 'ready';
        setText(box.title, (overview.view === 'closed' ? text().closedHeading : text().due) + (counts[overview.view] ? ' · ' + counts[overview.view] : ''));
        setText(box.status, loading ? text().checkingCourse(overview.results.size - waiting, overview.results.size) : failed.length ? text().failed : '');
        setHidden(box.status, !box.status.textContent);
        setText(box.failed, failed.join(', '));
        setHidden(box.failed, !failed.length);
        box.refresh.disabled = loading;
        renderTabs(box, overview.view, counts, function (next) { overview.view = next; paint(); });
        // An empty tab names its emptiness only when every course was really checked.
        var certain = !loading && !failed.length;
        renderList(box, 'open', groups.open, certain ? text().noDue : '');
        renderList(box, 'closed', groups.closed, certain ? text().noClosed : '');
        paintCards();
    }
    // A discipline's weeks: its tasks first, empty weeks folded.
    var weeksExpanded = false;
    // Weeks without tasks or resources (other than the current one) wait behind "Show all weeks".
    function quietWeeks(record, entries) {
        var quiet = entries.filter(function (entry) { return entry.kind === 'week' && !entry.tasks && !entry.resources && !entry.current; });
        if (quiet.length < 2) return;
        Array.from(record.view.children).forEach(function (item, i) { if (quiet.indexOf(entries[i]) >= 0) item.dataset.quiet = 'true'; });
        var toggle = el('button', 'univer-app-dist-more'); toggle.type = 'button';
        var sync = function () {
            record.view.classList.toggle('univer-app-dist-expanded', weeksExpanded);
            toggle.setAttribute('aria-expanded', String(weeksExpanded));
            setText(toggle, weeksExpanded ? text().hideEmpty : text().allWeeks(entries.length));
        };
        toggle.addEventListener('click', function () { weeksExpanded = !weeksExpanded; window.__univerMobileV1 ? window.__univerMobileV1.mutate(sync) : sync(); });
        record.view.after(toggle); record.extra.push(toggle); sync();
    }
    // All tasks of the discipline, read from the task lists of the weeks that have any; open and closed are two tabs of
    // one panel, and Refresh rereads them without adding a second panel.
    function startWeekTasks(record, entries, force) {
        var match = location.pathname.match(/^\/student\/discussion\/acpos\/week\/list\/(\d+)\/(\d+)\/(\d{4})\/(\d{1,2})\/?$/);
        if (!match) return;
        var plan = match[1], discipline = match[2], term = match[3] + '/' + match[4];
        var busy = entries.filter(function (entry) { return entry.kind === 'week' && entry.tasks > 0 && /^\d+$/.test(entry.id); });
        var key = language + '|' + location.pathname + '|' + busy.map(function (entry) { return entry.id + ':' + entry.tasks; }).join(',');
        var previous = weekTasks;
        // A rebuilt week list keeps the tasks already read for it.
        if (previous && previous.key === key && !force) {
            previous.box.refresh.onclick = function () {
                if (weekTasks === previous && record.view.isConnected) startWeekTasks(record, entries, true);
            };
            record.view.before(previous.box.section); paintWeeks(); return;
        }
        var box = previous && previous.key === key ? previous.box : tasksBox('univer-app-dist-due univer-app-dist-week-tasks');
        if (previous) { previous.controller.abort(); if (previous.key !== key) previous.box.section.remove(); }
        var state = weekTasks = {key:key, controller:new AbortController(), box:box,
            view: previous && previous.key === key ? previous.view : 'open', items:null, partial:false};
        box.refresh.onclick = function () {
            if (weekTasks === state && record.view.isConnected) startWeekTasks(record, entries, true);
        };
        setText(box.refresh, text().refresh);
        record.view.before(box.section);
        if (!busy.length) { state.items = []; paintWeeks(); return; }
        paintWeeks();
        var run = limiter(3), cache = stored();
        Promise.all(busy.map(function (entry) {
            var href = '/student/discussion/task/list/' + plan + '/' + discipline + '/' + term + '/' + entry.id + '/';
            return readTasks(href, {id:entry.id, tasks:entry.tasks, end:entry.until}, state.controller.signal, run, cache, force, function (id) {
                return /^\d+$/.test(id) ? taskPath(id, plan, discipline, term, entry.id) : href;
            }).then(function (items) {
                return items.map(function (item) { return {title:item.title, end:item.end, href:item.href, context:labelWeek(entry.week)}; });
            }, function (error) {
                if (state.controller.signal.aborted) throw error;
                state.partial = true; return null;
            });
        })).then(function (found) {
            if (state.controller.signal.aborted || weekTasks !== state) return;
            state.partial = state.partial || found.some(function (part) { return part === null; });
            state.items = [].concat.apply([], found.filter(Boolean));
            window.__univerMobileV1 ? window.__univerMobileV1.mutate(paintWeeks) : paintWeeks();
        }, function () {});
    }
    function paintWeeks() {
        var state = weekTasks; if (!state) return;
        var box = state.box, items = state.items || [], groups = grouped(items), counts = {open:groups.open.length, closed:groups.closed.length};
        var loading = state.items === null;
        box.section.dataset.state = loading ? 'loading' : state.partial ? 'failed' : 'ready';
        setText(box.title, text().courseTasks + (items.length ? ' · ' + items.length : ''));
        setText(box.status, loading ? text().checking : state.partial ? text().partial : '');
        setHidden(box.status, !box.status.textContent);
        setText(box.failed, ''); setHidden(box.failed, true);
        box.refresh.disabled = loading;
        renderTabs(box, state.view, counts, function (next) { state.view = next; paintWeeks(); });
        // An empty tab names its emptiness only when every week was really read.
        var certain = !loading && !state.partial;
        renderList(box, 'open', groups.open, certain ? text().noCourseTasks : '');
        renderList(box, 'closed', groups.closed, certain ? text().noClosed : '');
    }
    // Task page (task/preview): one readable card instead of the two-column information table.
    // The site writes the task as label spans followed by values ("Название задания:" text, "Автор:" text…).
    function labelled(root, selector) {
        var pairs = [];
        Array.from(root?.childNodes || []).forEach(function (node) {
            if (node.nodeType === 1 && node.matches(selector)) pairs.push({label:clean(node).replace(/:$/, ''), value:''});
            else if (pairs.length && !(node.nodeType === 1 && node.matches('.btn'))) pairs[pairs.length - 1].value += node.nodeType === 1 && node.tagName === 'BR' ? '\n' : node.textContent;
        });
        return pairs.map(function (pair) {
            return {label:pair.label, value:pair.value.split('\n').map(function (line) { return line.replace(/\s+/g, ' ').trim(); }).filter(Boolean).join('\n')};
        });
    }
    function moment(value) {
        var match = value.match(/(\d{2})\.(\d{2})\.(\d{4})(?:\s+(\d{1,2}):(\d{2}))?/);
        return match ? new Date(+match[3], +match[2] - 1, +match[1], +(match[4] || 23), +(match[5] || 59)) : null;
    }
    function shortMoment(date) { return ('0' + date.getDate()).slice(-2) + '.' + ('0' + (date.getMonth() + 1)).slice(-2) + ', ' + ('0' + date.getHours()).slice(-2) + ':' + ('0' + date.getMinutes()).slice(-2); }
    // "until 12.03, 23:59" and what is left, from the task's "start - end" period; nothing without an end.
    function taskDeadline(period) {
        var parts = period.split(/\s*-\s*(?=\d{2}\.\d{2}\.\d{4})/), start = moment(parts[0] || ''), end = moment(parts[1] || '');
        if (!end) return null;
        var now = Date.now(), left = end.getTime() - now, state = now < start?.getTime() ? 'upcoming' : left < 0 ? 'closed' : left < 86400000 ? 'soon' : 'open';
        var due = el('div', 'univer-app-task-deadline'); due.dataset.state = state;
        due.append(el('strong', '', state === 'upcoming' ? text().opens(shortMoment(start)) : text().until(shortMoment(end))),
            el('span', '', state === 'closed' ? text().ended : state === 'upcoming' ? '' : left < 86400000 ? text().left(Math.max(1, Math.ceil(left / 3600000)), true) : text().left(Math.ceil(left / 86400000), false)));
        return due;
    }
    // The task description; a long one starts folded to a few lines, with the whole text one tap away.
    function foldedText(value) {
        var details = el('p', 'univer-app-task-description', value);
        if (value.length <= 420) return [details];
        details.dataset.folded = 'true';
        var more = el('button', 'univer-app-task-more', text().more); more.type = 'button'; more.setAttribute('aria-expanded', 'false');
        more.addEventListener('click', function () {
            var folded = details.dataset.folded === 'true';
            var change = function () { details.dataset.folded = String(!folded); setText(more, folded ? text().less : text().more); more.setAttribute('aria-expanded', String(folded)); };
            window.__univerMobileV1 ? window.__univerMobileV1.mutate(change) : change();
        });
        return [details, more];
    }
    function dropTask() {
        var marks = task.marks;
        task.box.remove(); unmark(function (node) { return marks.indexOf(node) >= 0; }); task = null;
    }
    function taskPage() {
        if (!/^\/student\/discussion\/task\/preview\//.test(location.pathname)) return;
        var info = document.querySelector('#l4 tr.message .inf');
        if (task && (!info || !task.box.isConnected)) dropTask();
        if (!info) return;
        var row = info.closest('tr.message'), table = row.closest('table'), cells = Array.from(row.cells);
        var about = labelled(info, 'span.title,span.capt,span.duration'), body = labelled(cells[1], 'span.header');
        var signature = language + '|' + JSON.stringify(about) + JSON.stringify(body);
        if (task && task.signature === signature) return;
        if (task) dropTask();
        var box = el('section', 'univer-app-task'), marks = [];
        var course = facts && clean(facts.box.querySelector('dd'));
        if (course) box.appendChild(el('p', 'univer-app-task-course', course));
        box.appendChild(el('h2', 'univer-app-task-title', about[0]?.value || ''));
        var author = about.find(function (pair) { return /автор|author/i.test(pair.label); });
        if (author?.value) box.appendChild(el('p', 'univer-app-task-author', author.value));
        var span = about.find(function (pair) { return (pair.value.match(/\d{2}\.\d{2}\.\d{4}/g) || []).length >= 2; });
        var due = span && taskDeadline(span.value);
        if (due) box.appendChild(due);
        var chips = el('div', 'univer-app-task-chips');
        var chip = function (label, value) { if (value) chips.appendChild(el('span', 'univer-app-task-chip', (label ? label + ': ' : '') + value.replace(/\n/g, ' '))); };
        var description = body.find(function (pair) { return /описани|сипаттама|description/i.test(pair.label); }) || body.slice().sort(function (a, b) { return b.value.length - a.value.length; })[0];
        // "Формат ответа: Ответ в виде файла" already says what it is.
        body.forEach(function (pair) { if (pair !== description) chip(/формат|format|пішім/i.test(pair.label) ? '' : pair.label, pair.value); });
        about.slice(1).forEach(function (pair) {
            if (pair === author || pair === span) return;
            chip(/ответ|жауап|answer|attempt/i.test(pair.label) ? text().attempts : /балл|score|point/i.test(pair.label) ? text().maxScore : pair.label, pair.value);
        });
        if (chips.children.length) box.appendChild(chips);
        if (description?.value) {
            box.appendChild(el('h3', 'univer-app-task-label', description.label));
            box.append(...foldedText(description.value));
        }
        var cell = table.closest('td') || table.parentElement;
        cell.insertBefore(box, cell.firstChild);
        var tag = function (node, className) { if (node) { mark(node, className || 'univer-app-dist-source'); marks.push(node); } };
        tag(sourceOf(table, cell)); tag(table);
        // The card names the course; the folded course facts would only repeat it here.
        tag(facts?.box, 'univer-app-dist-source');
        // The site's help line and the empty message history (a header row only) add nothing to the task.
        tag(document.getElementById('help_btn')?.closest('tr'));
        var history = document.querySelector('#l4 tr#group_list table');
        if (history && ownRows(history).length <= 1) tag(document.querySelector('#l4 tr#group_list'));
        // The panel heading repeats the task name the card now shows; its breadcrumb links stay.
        var panel = table.closest('table.mt');
        tag(panel && ownRows(panel)[0]?.querySelector('td.ct'), 'univer-app-task-head');
        tag(document.getElementById('send_message'), 'univer-app-task-form');
        var files = document.getElementById('files_div');
        if (files && !files.nextElementSibling?.classList.contains('univer-app-task-hint')) {
            var hint = el('p', 'univer-app-task-hint', text().hint); files.after(hint); owned.push(hint);
        } else if (files) setText(files.nextElementSibling, text().hint);
        task = {box:box, marks:marks, signature:signature};
    }
    function drop(table) {
        var record = lists.get(table); if (!record) return;
        record.view.remove();
        record.extra.forEach(function (node) { node.remove(); });
        Array.from(taskCards.entries()).forEach(function (entry) { if (record.view.contains(entry[1].main)) taskCards.delete(entry[0]); });
        unmark(function (node) { return record.sources.indexOf(node) >= 0; });
        lists.delete(table);
    }
    function release() {
        Array.from(lists.keys()).forEach(drop);
        unmark();
        if (help) { if (help.home.parentNode) help.home.replaceWith(help.panel); help.box.remove(); help = null; }
        if (facts) { facts.box.remove(); facts = null; }
        if (overview) { overview.controller.abort(); overview.box.section.remove(); overview = null; }
        if (weekTasks) { weekTasks.controller.abort(); weekTasks.box.section.remove(); weekTasks = null; }
        if (task) { task.box.remove(); task = null; }
        owned.forEach(function (node) { node.remove(); }); owned = [];
        taskCards.clear();
    }
    window.__univerDiscussionV1 = {apply:apply, release:release};
})();
