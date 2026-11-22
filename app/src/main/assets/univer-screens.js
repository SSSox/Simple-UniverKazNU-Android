/* Phone screens for the student's everyday pages (Android only; the page polish shared with Windows stays as it is):
 * exam days with a countdown, survey progress and its answer scale, the advisor's contact card, the GPA grade scale,
 * IGPA controls, folded memos and instructions, titled empty states and one filled main action per form.
 * The site's nodes, values and handlers stay; the few nodes it hides keep working underneath. release() puts every
 * change back. Its stylesheet is a cascade layer placed first in <head>, so its !important rules outrank the layout's
 * and the polish layer's; every rule is scoped to html.univer-scr, which only these routes get. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerScreensV1) return;
    var dom = window.__univerDomV1, clean = dom.clean, el = dom.el, setText = dom.setText, ownRows = dom.ownRows;
    // Without layers and :has() the hidden sources would stand beside the views that replace them.
    var capable = !!(window.CSS && CSS.supports && CSS.supports('selector(:has(a))') && window.CSSLayerBlockRule);
    var language = 'ru', style = null, page = '', views = {}, entered = {}, timer = 0;
    var classes = new Map(), added = [], listeners = [], wraps = [];

    function words() {
        return language === 'en' ? {
            nextExam:'Next exam', nextConsult:'Next consultation', nextMidterm:'Next midterm', exam:'Exam', consult:'Consultation',
            schedule:'Exam days', start:'Start the exam', calendar:'Add to calendar', noExams:'The exam timetable is not published yet', noMidterm:'The midterm timetable is not published yet',
            scheduleNote:'Dates, times and rooms will appear here once it is out.', today:'Today', tomorrow:'Tomorrow', past:'Past', show:'Show', hide:'Hide', more:'Show in full', less:'Collapse',
            noFail:'No courses to retake', noFx:'No courses for an FX retake', noGpa:'No courses for raising GPA',
            orderNote:'Courses you can apply for will be listed here.', noRequests:'No applications yet',
            noAppeals:'No appeals yet', appealsNote:'Complaints and suggestions you send appear here with their status.',
            answers:'Answers', allDone:'Every questionnaire in this survey is answered', left:function (n) { return 'Left to answer: ' + n; },
            scale:'Answer scale', advisor:'Your advisor', write:'Write', call:'Call', noContacts:'No contacts given',
            grades:'Grade scale', otherMarks:'Other marks', zeroShow:function (n) { return 'Show categories without credits (' + n + ')'; },
            zeroHide:'Hide categories without credits', zeroAll:'Credits are not split into categories yet', files:function (n) { return n + (n === 1 ? ' file' : ' files'); },
            courses:function (n) { return n + (n === 1 ? ' course' : ' courses'); }, credits:function (n) { return n + (n === 1 ? ' credit' : ' credits'); },
            days:function (n) { return n === 1 ? 'day' : 'days'; }, hours:'h', minutes:'min', inDays:function (n) { return 'in ' + n + ' ' + (n === 1 ? 'day' : 'days'); },
            months:['January','February','March','April','May','June','July','August','September','October','November','December'],
            short:['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'],
            weekdays:['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday']
        } : language === 'kz' ? {
            nextExam:'Жақын емтихан', nextConsult:'Жақын кеңес', nextMidterm:'Жақын Midterm', exam:'Емтихан', consult:'Кеңес',
            schedule:'Емтихан күндері', start:'Емтиханды бастау', calendar:'Күнтізбеге қосу', noExams:'Емтихан кестесі әлі жарияланбаған', noMidterm:'Midterm кестесі әлі жарияланбаған',
            scheduleNote:'Жарияланған соң күні, уақыты мен аудиториясы осында шығады.', today:'Бүгін', tomorrow:'Ертең', past:'Өтті', show:'Көрсету', hide:'Жасыру', more:'Толығымен көрсету', less:'Жию',
            noFail:'Қайта оқитын пән жоқ', noFx:'FX қайта тапсыратын пән жоқ', noGpa:'GPA көтеретін пән жоқ',
            orderNote:'Өтінім беруге болатын пәндер осында шығады.', noRequests:'Әзірге өтінім жоқ',
            noAppeals:'Әзірге өтініш жоқ', appealsNote:'Жіберген шағымдарыңыз бен ұсыныстарыңыз мәртебесімен осында шығады.',
            answers:'Жауаптар', allDone:'Бұл сауалнаманың барлық анкетасы толтырылды', left:function (n) { return 'Жауап беру керек: ' + n; },
            scale:'Жауап шкаласы', advisor:'Сіздің эдвайзеріңіз', write:'Жазу', call:'Қоңырау шалу', noContacts:'Байланыс деректері көрсетілмеген',
            grades:'Бағалау шкаласы', otherMarks:'Басқа белгілер', zeroShow:function (n) { return 'Кредитсіз санаттарды көрсету (' + n + ')'; },
            zeroHide:'Кредитсіз санаттарды жасыру', zeroAll:'Кредиттер санаттарға әлі бөлінбеген', files:function (n) { return n + ' файл'; },
            courses:function (n) { return n + ' пән'; }, credits:function (n) { return n + ' кредит'; },
            days:function () { return 'күн'; }, hours:'сағ', minutes:'мин', inDays:function (n) { return n + ' күннен кейін'; },
            months:['қаңтар','ақпан','наурыз','сәуір','мамыр','маусым','шілде','тамыз','қыркүйек','қазан','қараша','желтоқсан'],
            short:['қаң','ақп','нау','сәу','мам','мау','шіл','там','қыр','қаз','қар','жел'],
            weekdays:['жексенбі','дүйсенбі','сейсенбі','сәрсенбі','бейсенбі','жұма','сенбі']
        } : {
            nextExam:'Ближайший экзамен', nextConsult:'Ближайшая консультация', nextMidterm:'Ближайший Midterm', exam:'Экзамен', consult:'Консультация',
            schedule:'Дни экзаменов', start:'Начать экзамен', calendar:'В календарь', noExams:'Расписание экзаменов пока не опубликовано', noMidterm:'Расписание Midterm пока не опубликовано',
            scheduleNote:'Когда оно появится, здесь будут даты, время и аудитории.', today:'Сегодня', tomorrow:'Завтра', past:'Прошло', show:'Показать', hide:'Скрыть', more:'Показать полностью', less:'Свернуть',
            noFail:'Дисциплин для повторного изучения нет', noFx:'Дисциплин для пересдачи FX нет', noGpa:'Дисциплин для повышения GPA нет',
            orderNote:'Здесь появятся дисциплины, по которым можно подать заявку.', noRequests:'Заявок пока нет',
            noAppeals:'Обращений пока нет', appealsNote:'Отправленные жалобы и предложения появятся здесь вместе со статусом.',
            answers:'Ответы', allDone:'Все анкеты этого опроса заполнены', left:function (n) { return 'Осталось ответить: ' + n; },
            scale:'Шкала ответов', advisor:'Ваш эдвайзер', write:'Написать', call:'Позвонить', noContacts:'Контакты не указаны',
            grades:'Шкала оценок', otherMarks:'Другие отметки', zeroShow:function (n) { return 'Показать категории без кредитов (' + n + ')'; },
            zeroHide:'Скрыть категории без кредитов', zeroAll:'Кредиты по категориям пока не распределены', files:function (n) { return n + ' ' + plural(n, 'файл', 'файла', 'файлов'); },
            courses:function (n) { return n + ' ' + plural(n, 'дисциплина', 'дисциплины', 'дисциплин'); },
            credits:function (n) { return n + ' ' + plural(n, 'кредит', 'кредита', 'кредитов'); },
            days:function (n) { return plural(n, 'день', 'дня', 'дней'); }, hours:'ч', minutes:'мин',
            inDays:function (n) { return 'через ' + n + ' ' + plural(n, 'день', 'дня', 'дней'); },
            months:['января','февраля','марта','апреля','мая','июня','июля','августа','сентября','октября','ноября','декабря'],
            short:['янв','фев','мар','апр','мая','июн','июл','авг','сен','окт','ноя','дек'],
            weekdays:['воскресенье','понедельник','вторник','среда','четверг','пятница','суббота']
        };
    }
    function plural(n, one, few, many) {
        var v = Math.abs(Math.round(n)) % 100, d = v % 10;
        return v > 10 && v < 20 ? many : d === 1 ? one : d > 1 && d < 5 ? few : many;
    }

    // Every change goes through these helpers, so release() undoes exactly what was done.
    function mark(node, name) {
        if (!node || node.classList.contains(name)) return;
        node.classList.add(name);
        var set = classes.get(node); if (!set) classes.set(node, set = new Set()); set.add(name);
    }
    function unmark(node, name) {
        var set = classes.get(node); if (!set || !set.delete(name)) return;
        node.classList.remove(name); if (!node.getAttribute('class')) node.removeAttribute('class');
        if (!set.size) classes.delete(node);
    }
    function create(tag, name, text) { var node = el(tag, name, text); added.push(node); return node; }
    // A dropped node takes its listeners with it, so rebuilt views do not pile up handlers until release().
    function drop(node) {
        if (!node) return; node.remove(); var i = added.indexOf(node); if (i >= 0) added.splice(i, 1);
        listeners = listeners.filter(function (l) { if (!node.contains(l[0])) return true; l[0].removeEventListener(l[1], l[2]); return false; });
    }
    // Writes made outside the layout's pass (the minute timer, the GPA count) do not start a new pass.
    function quiet(action) { var mobile = window.__univerMobileV1; if (mobile && mobile.quiet) mobile.quiet(action); else action(); }
    function listen(node, type, handler) { node.addEventListener(type, handler); listeners.push([node, type, handler]); }
    function ui() { return window.__univerMotionUI; }
    // Only a real pointer click animates; scripted and keyboard clicks change state at once.
    function moving(event) { return !!event && event.detail > 0; }
    function motionAllowed() {
        var root = document.documentElement;
        return window.__univerMotion !== false && !root.classList.contains('univer-app-keyboard') && !root.classList.contains('univer-app-still') &&
            !(window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches);
    }
    // A view's first appearance in this document gets a short staggered entrance; rebuilds after AJAX updates do not.
    function enter(name, nodes) {
        if (entered[name]) return; entered[name] = true;
        nodes.slice(0, 8).forEach(function (node, i) { ui()?.reveal(node, 'tile', i * 40); });
    }
    function forget(name) {
        var view = views[name]; if (!view) return;
        (view.nodes || [view.node]).forEach(drop);
        (view.marks || []).forEach(function (entry) { unmark(entry[0], entry[1]); });
        view.cleanup?.();
        delete views[name];
    }
    // The table and the scroll box the layout may have put it in, when the box holds nothing else.
    function holderOf(table) {
        var box = table.parentElement;
        return box && box.matches('.univer-app-table-scroll') && box.children.length === 1 ? box : table;
    }
    // The box that holds a replaced table: its scroll box, and the site's div.inner around it when that holds nothing else.
    function sourceOf(table) {
        var holder = holderOf(table), box = holder.parentElement;
        return box && box.matches('div.inner') && box.children.length === 1 ? box : holder;
    }
    function sameSources(view, sources) {
        return !!view.marks && view.marks.length === sources.length && sources.every(function (source, i) {
            return view.marks[i][0] === source && source.classList.contains('univer-scr-source');
        });
    }
    // Cell text with each <br> read as a space ("30-60<br>50-100").
    function lineText(node) { return clean(Array.from(node.childNodes).map(function (n) { return n.nodeName === 'BR' ? ' ' : n.textContent; }).join('')); }
    function textOf(node) { return clean(Array.from(node.childNodes).filter(function (n) { return n.nodeType === 3; }).map(function (n) { return n.nodeValue; }).join(' ')); }

    // Long memo text folds to a few lines behind "Show in full". The cell's own nodes move into one wrapper, which
    // release() unwraps again; the height change is animated.
    var fits = new WeakSet();
    function clampCell(cell, name) {
        if (!cell || fits.has(cell) || cell.querySelector(':scope>.univer-scr-clamp')) return;
        if (!cell.getClientRects().length) return;
        if (cell.querySelector('table,form,input,select,textarea,iframe,video')) { fits.add(cell); return; }
        var box = el('div', 'univer-scr-clamp');
        while (cell.firstChild) box.appendChild(cell.firstChild);
        cell.appendChild(box); wraps.push(box);
        if (box.scrollHeight <= box.clientHeight + 24) { unwrap(box); fits.add(cell); return; }
        var button = create('button', 'univer-scr-more'); button.type = 'button'; button.setAttribute('aria-expanded', 'false');
        button.textContent = words().more; button.dataset.univerScrName = name || '';
        listen(button, 'click', function (event) {
            var open = !box.classList.contains('univer-scr-open'), before = box.getBoundingClientRect().height;
            ui()?.stop(box);
            box.classList.toggle('univer-scr-open', open);
            button.setAttribute('aria-expanded', String(open)); button.textContent = open ? words().less : words().more;
            if (moving(event)) ui()?.resize(box, before, open ? 260 : 200);
            if (!open && box.getBoundingClientRect().top < 0) box.scrollIntoView({block:'start', behavior:moving(event) && motionAllowed() ? 'smooth' : 'auto'});
        });
        cell.appendChild(button);
    }
    function unwrap(box) {
        var i = wraps.indexOf(box); if (i >= 0) wraps.splice(i, 1);
        if (box.parentNode) { while (box.firstChild) box.parentNode.insertBefore(box.firstChild, box); box.remove(); }
    }
    // A long memo panel: a text cell over 600 characters in a panel of its own.
    function clampMemos() {
        document.querySelectorAll('#l4 table.mt>tbody>tr>td.ct').forEach(function (cell) {
            if (cell.closest('#tools,.univer-scr-view') || cell.parentElement.classList.contains('top') || clean(cell).length < 600) return;
            clampCell(cell, 'memo');
        });
    }

    // An empty state with a title, an icon and an optional line, standing in for a table that says "no data".
    function emptyState(tone, title, note) {
        var box = create('div', 'univer-scr-view univer-scr-empty'); box.dataset.tone = tone; box.setAttribute('role', 'status');
        var icon = el('span', 'univer-scr-empty-icon'); icon.setAttribute('aria-hidden', 'true');
        var text = el('div', 'univer-scr-empty-text'); text.appendChild(el('h3', 'univer-scr-empty-title', title));
        if (note) text.appendChild(el('p', 'univer-scr-empty-note', note));
        box.append(icon, text);
        return box;
    }
    function onlyEmpty(table) {
        var rows = ownRows(table), empty = rows.filter(function (r) { return r.classList.contains('empty'); });
        return empty.length > 0 && rows.every(function (r) { return r.classList.contains('empty') || Array.from(r.cells).every(function (c) { return c.tagName === 'TH'; }); });
    }

    // Exams and Midterm (#scheduleList): the result rows arrive by AJAX under date rows ("15.12.2026 13:00", with the
    // site's own countdown) and are read with the column headings of the table above them.
    function when(text) {
        var m = /(\d{2})\.(\d{2})\.(\d{4})(?:\s+(\d{1,2}):(\d{2}))?/.exec(text || '');
        return m ? new Date(+m[3], +m[2] - 1, +m[1], +(m[4] || 0), +(m[5] || 0)) : null;
    }
    function dayKey(date) { return date.getFullYear() + '-' + date.getMonth() + '-' + date.getDate(); }
    function hhmm(date) { return String(date.getHours()).padStart(2, '0') + ':' + String(date.getMinutes()).padStart(2, '0'); }
    function midnight(date) { var d = new Date(date); d.setHours(0, 0, 0, 0); return d; }
    function dayDistance(date) { return Math.round((midnight(date) - midnight(new Date())) / 86400000); }
    function relative(date) {
        var w = words(), n = dayDistance(date);
        return n < 0 ? w.past : n === 0 ? w.today : n === 1 ? w.tomorrow : w.inDays(n);
    }
    // The separate table above the results that holds only the column headings.
    function scheduleHead(cell, except) {
        return Array.from(cell?.querySelectorAll('table') || []).find(function (table) {
            var rows = ownRows(table);
            return table !== except && rows.length === 1 && rows[0].cells.length > 3 && Array.from(rows[0].cells).every(function (c) { return c.tagName === 'TH'; });
        }) || null;
    }
    function scheduleColumns(list) {
        var head = scheduleHead(list.closest('td'), list);
        var labels = head ? Array.from(ownRows(head)[0].cells).map(clean) : [];
        var find = function (pattern, fallback) { var i = labels.findIndex(function (l) { return pattern.test(l); }); return i >= 0 ? i : fallback; };
        return {name:find(/дисципл|пән|subject|discipline|course/i, 0), teacher:find(/преподав|оқытушы|teacher|lecturer/i, 1),
            kind:find(/^(тип|түрі|type)$/i, 2), place:find(/аудитор|room|auditor/i, 3), group:find(/групп|топ|group/i, 4), links:find(/ссылк|сілтеме|link/i, 5)};
    }
    function place(text) {
        var m = /корп[^:]*:\s*([^|]*?)\s*\|\s*ауд[^:]*:\s*(.*)$/i.exec(text);
        return m ? [m[1], m[2]].filter(Boolean).join(' · ') : text;
    }
    function scheduleItems(list) {
        var columns = scheduleColumns(list), current = null, items = [];
        ownRows(list).forEach(function (row) {
            var cells = Array.from(row.cells);
            if (cells.length === 1 && cells[0].tagName === 'TH') { current = when(clean(cells[0])) || current; return; }
            if (cells.length < 4 || cells.some(function (c) { return c.tagName === 'TH'; })) return;
            var at = when(row.id) || current; if (!at) return;
            var at2 = function (i) { return i >= 0 && cells[i] ? clean(cells[i]) : ''; };
            var kindCell = cells[columns.kind], notes = kindCell ? Array.from(kindCell.querySelectorAll('i,em')).map(clean).join(' ') : '';
            var kindText = clean(at2(columns.kind).replace(notes, '')), kind = /^(экзамен|емтихан|exam)/i.test(kindText) ? 'exam' : /^(консультац|кеңес|consult)/i.test(kindText) ? 'consult' : '';
            var links = columns.links >= 0 && cells[columns.links] ? Array.from(cells[columns.links].querySelectorAll('a[href]')).filter(function (a) {
                return /^https?:$/.test(a.protocol) && !a.classList.contains('take-exam') && a.getAttribute('href') !== '#';
            }) : [];
            // The site's own "take the exam" link: hidden until the exam opens, then it runs the site's handlers.
            var take = row.querySelector('a.take-exam'), open = take && !take.classList.contains('unactiveExam') ? take : null;
            // The key leaves out live text elsewhere in the row, so a ticking label does not rebuild the view every second.
            items.push({at:at, id:row.id || '', name:at2(columns.name), teacher:at2(columns.teacher), kind:kind, kindText:kindText, note:notes.replace(/^\(|\)$/g, ''),
                place:place(at2(columns.place)), group:at2(columns.group), links:links, take:open, column:columns.name,
                key:[row.id, at2(columns.name), at2(columns.teacher), at2(columns.kind), at2(columns.place), at2(columns.group),
                    links.map(function (a) { return a.href; }).join(' '), take ? take.className + (take.getAttribute('onclick') || '') + clean(take) : ''].join('|')});
        });
        // One group per day even if the site lists a day twice; the sort keeps the site's order within a time.
        return items.sort(function (a, b) { return a.at - b.at; });
    }
    function upcoming(items) {
        var now = Date.now(), future = items.filter(function (item) { return item.at.getTime() > now; });
        return future.find(function (item) { return item.kind === 'exam'; }) || future[0] || null;
    }
    function countdown(date) {
        var w = words(), ms = date.getTime() - Date.now(), days = dayDistance(date);
        if (ms >= 86400000 && days >= 1) return [String(days), w.days(days)];
        var hours = Math.floor(ms / 3600000);
        return hours >= 1 ? [String(hours), w.hours] : [String(Math.max(1, Math.ceil(ms / 60000))), w.minutes];
    }
    // «В календарь»: the student's own tap hands this exam to the phone's calendar app (two hours; one for a consultation).
    function calendarButton(item, w) {
        var button = el('button', 'univer-scr-calendar', w.calendar); button.type = 'button';
        button.addEventListener('click', function (event) {
            if (!event.isTrusted || typeof __univerNative !== 'function') return;
            var kind = item.kind === 'exam' ? w.exam : item.kind === 'consult' ? w.consult : item.kindText;
            __univerNative(dom.calendarMessage({title:(kind ? kind + ': ' : '') + item.name, start:item.at,
                end:new Date(item.at.getTime() + (item.kind === 'consult' ? 1 : 2) * 3600000), place:item.place,
                notes:[item.teacher, item.group, item.note].filter(Boolean).join(' · ')}));
        });
        return button;
    }
    // The coming exams (or midterms, by the page) go to the app for its reminders, as local wall-clock times. The list
    // replaces the app's copy of this kind, so it is sent whenever it changes, an empty one included; consultations are
    // not exams. Never sent without the app's keyed sender (Original layout, frames).
    var sentExams = '';
    function saveExams(items, midterm) {
        if (typeof __univerNative !== 'function') return;
        var now = Date.now(), kind = midterm ? 'midterm' : 'exam', two = function (n) { return String(n).padStart(2, '0'); };
        var coming = items.filter(function (item) { return item.kind !== 'consult' && item.at.getTime() > now; }).slice(0, 60).map(function (item) {
            return {kind:kind, subject:item.name.slice(0, 200), start:item.at.getFullYear() + '-' + two(item.at.getMonth() + 1) + '-' + two(item.at.getDate()) + 'T' + hhmm(item.at),
                place:item.place.slice(0, 200)};
        }).filter(function (item) { return item.subject; });
        var message = 'univer-exams:' + JSON.stringify({v:1, kind:kind, items:coming});
        if (message === sentExams) return;
        sentExams = message; __univerNative(message);
    }
    function exams() {
        instructions();
        var list = document.getElementById('scheduleList'), host = list && list.closest('td');
        var items = list && host ? scheduleItems(list) : [], head = list && host ? scheduleHead(host, list) : null;
        if (!items.length) { forget('exams'); scheduleEmpty(); return; }
        saveExams(items, /midterm/i.test(location.pathname));
        forget('scheduleEmpty');
        var next = upcoming(items), signature = language + '|' + items.map(function (i) { return i.key; }).join('#') + '|' + (next ? next.key : '');
        var view = views.exams;
        var sources = [head ? sourceOf(head) : null, sourceOf(list)].filter(Boolean);
        if (view && view.signature === signature && view.node.isConnected && view.list === list && sameSources(view, sources)) { refreshExams(view); return; }
        forget('exams');
        var w = words(), midterm = /midterm/i.test(location.pathname);
        var node = create('div', 'univer-scr-view univer-scr-exams'), parts = [];
        if (next) {
            var card = el('section', 'univer-scr-next'); card.setAttribute('aria-live', 'off');
            var eyebrow = midterm ? w.nextMidterm : next.kind === 'consult' ? w.nextConsult : w.nextExam;
            card.appendChild(el('p', 'univer-scr-eyebrow', eyebrow));
            card.appendChild(el('h3', 'univer-scr-next-name', next.name));
            card.appendChild(el('p', 'univer-scr-next-when', w.weekdays[next.at.getDay()] + ', ' + next.at.getDate() + ' ' + w.months[next.at.getMonth()] + ' · ' + hhmm(next.at)));
            var count = el('div', 'univer-scr-count'), value = el('span', 'univer-scr-count-value'), unit = el('span', 'univer-scr-count-unit');
            count.append(value, unit); card.appendChild(count);
            if (typeof __univerNative === 'function') card.appendChild(calendarButton(next, w));
            node.appendChild(card); parts.push(card);
        }
        var days = el('ol', 'univer-scr-days'); days.setAttribute('aria-label', w.schedule);
        var groups = [];
        items.forEach(function (item) {
            var key = dayKey(item.at), group = groups[groups.length - 1];
            if (!group || group.key !== key) groups.push(group = {key:key, at:item.at, items:[]});
            group.items.push(item);
        });
        groups.forEach(function (group) {
            var day = el('li', 'univer-scr-day'), head = el('div', 'univer-scr-day-head');
            var date = el('span', 'univer-scr-day-date'); date.setAttribute('aria-hidden', 'true');
            date.append(el('span', 'univer-scr-day-number', String(group.at.getDate())), el('span', 'univer-scr-day-month', w.short[group.at.getMonth()]));
            var title = el('h3', 'univer-scr-day-name', w.weekdays[group.at.getDay()]);
            title.setAttribute('aria-label', w.weekdays[group.at.getDay()] + ', ' + group.at.getDate() + ' ' + w.months[group.at.getMonth()]);
            head.append(date, title, el('span', 'univer-scr-day-rel'));
            day.appendChild(head);
            var slots = el('ul', 'univer-scr-slots');
            group.items.forEach(function (item) {
                var slot = el('li', 'univer-scr-slot'); if (item.kind) slot.dataset.kind = item.kind;
                slot.appendChild(el('span', 'univer-scr-slot-time', hhmm(item.at)));
                var body = el('div', 'univer-scr-slot-body'), tags = el('p', 'univer-scr-slot-tags');
                var kind = el('span', 'univer-scr-kind', item.kind === 'exam' ? w.exam : item.kind === 'consult' ? w.consult : item.kindText);
                if (item.kind) kind.dataset.kind = item.kind;
                if (kind.textContent) tags.appendChild(kind);
                if (item.note) tags.appendChild(el('span', 'univer-scr-slot-note', item.note));
                if (tags.childNodes.length) body.appendChild(tags);
                body.appendChild(el('h4', 'univer-scr-slot-name', item.name));
                var meta = el('p', 'univer-scr-slot-meta');
                if (item.teacher) meta.appendChild(el('span', 'univer-scr-who', item.teacher));
                if (item.place) meta.appendChild(el('span', 'univer-scr-where', item.place));
                if (item.group) meta.appendChild(el('span', 'univer-scr-group', item.group));
                if (meta.childNodes.length) body.appendChild(meta);
                var later = typeof __univerNative === 'function' && item.at.getTime() > Date.now();
                if (item.links.length || item.take || later) {
                    var links = el('p', 'univer-scr-slot-links');
                    if (item.take) {
                        var start = el('button', 'univer-scr-start', clean(item.take) || w.start); start.type = 'button';
                        start.addEventListener('click', takeExam.bind(null, item));
                        links.appendChild(start);
                    }
                    item.links.forEach(function (source) {
                        var a = el('a', 'univer-scr-link', clean(source) || source.hostname); a.href = source.href;
                        if (source.target) a.target = source.target; a.rel = 'noopener';
                        links.appendChild(a);
                    });
                    if (later) links.appendChild(calendarButton(item, w));
                    body.appendChild(links);
                }
                slot.appendChild(body); slots.appendChild(slot);
            });
            day.appendChild(slots); days.appendChild(day); parts.push(day);
        });
        node.appendChild(days);
        sources[0].before(node);
        sources.forEach(function (source) { mark(source, 'univer-scr-source'); });
        views.exams = {name:'exams', node:node, list:list, signature:signature, next:next, groups:groups,
            marks:sources.map(function (source) { return [source, 'univer-scr-source']; })};
        refreshExams(views.exams);
        // The countdown and "today" follow the clock between site updates; once the next exam has begun, the card
        // moves on to the one after it.
        clearInterval(timer); timer = setInterval(function () {
            quiet(function () {
                var current = views.exams; if (!current) return;
                if (current.next && current.next.at.getTime() <= Date.now()) exams(); else refreshExams(current);
            });
        }, 60000);
        views.exams.cleanup = function () { clearInterval(timer); timer = 0; };
        enter('exams', parts);
    }
    // The start button finds the site's link when it is tapped, so a list the site has re-rendered still starts the exam.
    function takeExam(item) {
        var list = document.getElementById('scheduleList');
        var row = list && ownRows(list).find(function (r) {
            return r.id === item.id && r.cells[item.column] && clean(r.cells[item.column]) === item.name && r.querySelector('a.take-exam');
        });
        var link = row && row.querySelector('a.take-exam');
        if (link && !link.classList.contains('unactiveExam')) link.click();
    }
    // A term without a timetable: the heading table and a table whose only row says "not filled in", side by side in
    // one cell of the timetable panel. Nothing else in that cell is hidden.
    function scheduleEmpty() {
        var found = null;
        Array.from(document.querySelectorAll('#l4 td.tt')).some(function (cell) {
            var head = scheduleHead(cell), empty = head && Array.from(cell.querySelectorAll('table')).find(function (t) { return t !== head && onlyEmpty(t); });
            if (empty) found = {head:head, empty:empty};
            return !!found;
        });
        if (!found) { forget('scheduleEmpty'); return; }
        var sources = [sourceOf(found.head), sourceOf(found.empty)], view = views.scheduleEmpty;
        if (view && view.node.isConnected && view.node.nextElementSibling === sources[0] && view.signature === language && sameSources(view, sources)) return;
        forget('scheduleEmpty');
        var w = words(), box = emptyState('quiet', /midterm/i.test(location.pathname) ? w.noMidterm : w.noExams, w.scheduleNote);
        sources[0].before(box);
        sources.forEach(function (source) { mark(source, 'univer-scr-source'); });
        views.scheduleEmpty = {name:'scheduleEmpty', node:box, signature:language, marks:sources.map(function (source) { return [source, 'univer-scr-source']; })};
        enter('scheduleEmpty', [box]);
    }
    function refreshExams(view) {
        var nodes = view.node.querySelectorAll('.univer-scr-day');
        view.groups.forEach(function (group, i) {
            var day = nodes[i]; if (!day) return;
            var n = dayDistance(group.at), state = n < 0 ? 'past' : n === 0 ? 'today' : 'future';
            if (day.dataset.state !== state) day.dataset.state = state;
            setText(day.querySelector('.univer-scr-day-rel'), relative(group.at));
        });
        if (view.next) {
            var parts = countdown(view.next.at);
            setText(view.node.querySelector('.univer-scr-count-value'), parts[0]);
            setText(view.node.querySelector('.univer-scr-count-unit'), parts[1]);
            var count = view.node.querySelector('.univer-scr-count');
            var label = parts[0] + ' ' + parts[1];
            if (count.getAttribute('aria-label') !== label) { count.setAttribute('aria-label', label); count.setAttribute('role', 'img'); }
        }
    }
    // The exam instructions (PDF files and proctoring notes) fold under their heading, so the schedule comes first.
    function instructions() {
        document.querySelectorAll('#l4 table.mt').forEach(function (panel) {
            var head = ownRows(panel)[0]?.querySelector('td.ct');
            var files = Array.from(panel.querySelectorAll('a[href]')).filter(function (a) { return /\.pdf$/i.test(a.getAttribute('href')); }).length;
            if (!head || !files || !/^(инструкц|нұсқаул|instruction)/i.test(textOf(head))) return;
            foldPanel(panel, head, files);
        });
    }
    function foldPanel(panel, head, count) {
        var button = head.querySelector(':scope>.univer-scr-fold');
        if (!button) {
            button = create('button', 'univer-scr-fold'); button.type = 'button'; button.setAttribute('aria-expanded', 'false');
            var label = el('span', 'univer-scr-fold-label'), chip = el('span', 'univer-scr-fold-count');
            button.append(chip, label); head.appendChild(button);
            mark(panel, 'univer-scr-foldable'); mark(panel, 'univer-scr-folded');
            listen(button, 'click', function (event) {
                var open = panel.classList.contains('univer-scr-folded'), before = panel.getBoundingClientRect().height;
                ui()?.stop(panel);
                if (open) unmark(panel, 'univer-scr-folded'); else mark(panel, 'univer-scr-folded');
                button.setAttribute('aria-expanded', String(open));
                setText(button.querySelector('.univer-scr-fold-label'), open ? words().hide : words().show);
                if (moving(event)) {
                    ui()?.resize(panel, before, open ? 260 : 200);
                    if (open) ownRows(panel).slice(1).forEach(function (row) { ui()?.reveal(row, 'content'); });
                }
            });
        }
        setText(button.querySelector('.univer-scr-fold-count'), count ? words().files(count) : '');
        setText(button.querySelector('.univer-scr-fold-label'), panel.classList.contains('univer-scr-folded') ? words().show : words().hide);
    }

    // Debts, FX retakes and raising GPA: the long memo folds, and an empty order list or request list gets a titled
    // empty state. A list with rows stays the site's own form, with its order radios and buttons.
    function orders() {
        clampMemos();
        var w = words(), path = location.pathname.toLowerCase();
        var orderTitle = /failfx/.test(path) ? w.noFx : /gpaincrease/.test(path) ? w.noGpa : w.noFail;
        var found = [];
        document.querySelectorAll('#l4 table.inner').forEach(function (table) {
            if (table.closest('#tools,.univer-scr-view') || !onlyEmpty(table)) return;
            var order = !!table.closest('form[action*="order/add"]');
            var titleRow = ownRows(table).find(function (r) { return r.cells.length === 1 && r.cells[0].tagName === 'TH' && r.cells[0].colSpan > 1; });
            found.push({table:table, order:order, title:order ? orderTitle : titleRow ? clean(titleRow) : w.noRequests,
                note:order ? w.orderNote : titleRow ? w.noRequests : ''});
        });
        var signature = language + '|' + found.map(function (f) { return f.title + f.order; }).join('|');
        var view = views.orders;
        if (view && view.signature === signature && view.nodes.every(function (n) { return n.isConnected; }) &&
            found.every(function (f, i) { return view.nodes[i].nextElementSibling === holderOf(f.table); })) return;
        forget('orders');
        if (!found.length) return;
        var nodes = [], marks = [];
        found.forEach(function (f) {
            var box = emptyState(f.order ? 'good' : 'quiet', f.title, f.note), holder = holderOf(f.table);
            holder.before(box); mark(holder, 'univer-scr-source'); nodes.push(box); marks.push([holder, 'univer-scr-source']);
        });
        views.orders = {name:'orders', nodes:nodes, marks:marks, signature:signature};
        enter('orders', nodes);
    }

    // Complaints and suggestions: the "New appeal" button is the page's main action, and an empty list says so.
    function appeals() {
        var button = document.getElementById('newAppealBtn');
        if (button) { mark(button, 'univer-scr-primary'); mark(button, 'univer-scr-add'); }
        var submit = document.getElementById('appealSubmitBtn'); if (submit) mark(submit, 'univer-scr-primary');
        var table = document.querySelector('#l4 table.appeals-table');
        var empty = table && onlyEmpty(table), view = views.appeals;
        if (view && empty && view.node.isConnected && view.node.nextElementSibling === holderOf(table) && view.signature === language) return;
        forget('appeals');
        if (!empty) return;
        var w = words(), box = emptyState('quiet', w.noAppeals, w.appealsNote), holder = holderOf(table);
        holder.before(box); mark(holder, 'univer-scr-source');
        views.appeals = {name:'appeals', node:box, signature:language, marks:[[holder, 'univer-scr-source']]};
        enter('appeals', [box]);
    }

    // Questionnaires: the answer scale stays in view above the folded introduction, and a survey's subject list opens
    // with how many of its questionnaires are answered.
    function rating() {
        var path = location.pathname;
        if (/^\/student\/rating\/?$/i.test(path)) ratingIntro();
        else forget('scale');
        if (/^\/student\/rating\/\d+(\/|$)/i.test(path)) ratingProgress();
        else forget('progress');
    }
    function ratingIntro() {
        var cell = Array.from(document.querySelectorAll('#l4 table.mt td.ct')).find(function (c) {
            return !c.closest('.univer-scr-view') && /(^|\s)1\s*[–—-]\s*\S/.test(clean(c)) && /(^|\s)5\s*[–—-]\s*\S/.test(clean(c));
        });
        if (!cell) { forget('scale'); return; }
        var marks = [], pattern = /(?:^|\s)([1-5])\s*[–—-]\s*([^;\d]{3,40}?)\s*;/g, found = {}, m, text = clean(cell);
        while ((m = pattern.exec(text))) if (!found[m[1]]) found[m[1]] = m[2].trim();
        var scale = ['1', '2', '3', '4', '5'].map(function (n) { return found[n]; });
        var view = views.scale, signature = language + '|' + scale.join('|');
        if (!(view && view.signature === signature && view.node.isConnected && view.node.parentNode === cell)) {
            forget('scale');
            if (scale.every(Boolean)) {
                var w = words(), box = create('div', 'univer-scr-view univer-scr-scale');
                box.setAttribute('role', 'group');
                box.setAttribute('aria-label', w.scale + ': ' + scale.map(function (label, i) { return (i + 1) + ' – ' + label; }).join(', '));
                box.appendChild(el('p', 'univer-scr-scale-title', w.scale));
                var row = el('div', 'univer-scr-scale-row'); row.setAttribute('aria-hidden', 'true');
                scale.forEach(function (label, i) { var step = el('span', 'univer-scr-scale-step', String(i + 1)); step.dataset.step = String(i + 1); row.appendChild(step); });
                var ends = el('div', 'univer-scr-scale-ends'); ends.setAttribute('aria-hidden', 'true');
                ends.append(el('span', '', scale[0]), el('span', '', scale[4]));
                box.append(row, ends);
                cell.insertBefore(box, cell.firstChild);
                views.scale = {name:'scale', node:box, signature:signature, marks:marks};
                enter('scale', [box]);
            }
        }
        // The introduction under the scale folds; the scale itself is outside the fold.
        var wrapper = cell.querySelector(':scope>.univer-scr-clamp');
        if (!wrapper && !fits.has(cell) && cell.getClientRects().length && clean(cell).length > 400) {
            var scaleBox = views.scale && views.scale.node.parentNode === cell ? views.scale.node : null;
            if (scaleBox) scaleBox.remove();
            clampCell(cell, 'intro');
            if (scaleBox) cell.insertBefore(scaleBox, cell.firstChild);
        }
    }
    function ratingProgress() {
        // The table that owns the questionnaire rows; the page may wrap everything in an outer table.inner.
        var first = document.querySelector('#l4 table.inner tr.link[id]'), table = first && first.closest('table');
        if (!table) { forget('progress'); return; }
        var rows = Array.from(table.querySelectorAll('tr.link[id]')), done = rows.filter(function (r) { return r.classList.contains('done'); }).length;
        // Right above the list, under the page's own heading; an outer div.inner can wrap the whole page.
        var total = rows.length, anchor = sourceOf(table);
        var signature = language + '|' + done + '/' + total, view = views.progress;
        if (view && view.signature === signature && view.node.isConnected && view.node.nextElementSibling === anchor) return;
        forget('progress');
        var w = words(), box = create('section', 'univer-scr-view univer-scr-progress');
        box.setAttribute('aria-label', w.answers + ': ' + done + ' / ' + total);
        var top = el('div', 'univer-scr-progress-top');
        var count = el('span', 'univer-scr-progress-value');
        count.append(el('b', '', String(done)), document.createTextNode(' / ' + total));
        top.append(el('span', 'univer-scr-progress-label', w.answers), count);
        var bar = el('div', 'univer-scr-progress-bar'), fill = el('span', 'univer-scr-progress-fill');
        var ratio = total ? done / total : 0; fill.style.transform = 'scaleX(' + ratio + ')'; bar.appendChild(fill);
        if (done === total) box.dataset.complete = 'true';
        box.append(top, bar, el('p', 'univer-scr-progress-note', done === total ? w.allDone : w.left(total - done)));
        anchor.before(box);
        views.progress = {name:'progress', node:box, signature:signature};
        if (!entered.progress && motionAllowed() && fill.animate && ratio > 0)
            fill.animate([{transform:'scaleX(0)'}, {transform:'scaleX(' + ratio + ')'}], {duration:700, delay:120, easing:'cubic-bezier(.23,1,.32,1)', fill:'backwards'});
        enter('progress', [box]);
    }

    // The advisor: one contact card with the initials, the name, every other line the site gives and buttons that write
    // or call when it gives an address or a number. Only the site's label/value table is replaced; a table with rows of
    // another shape stays as it is.
    function advisor() {
        var table = document.querySelector('#group_list table.inner');
        if (!table) { forget('advisor'); return; }
        var data = {extra:[]}, odd = false;
        // Each row holds label/value pairs, two of them side by side on the site.
        ownRows(table).forEach(function (row) {
            if (!clean(row)) return;
            if (row.cells.length % 2) { odd = true; return; }
            for (var i = 0; i < row.cells.length; i += 2) {
                var label = clean(row.cells[i]).replace(/:$/, ''), value = clean(row.cells[i + 1]);
                if (!label) { if (value) odd = true; continue; }
                // The first filled value of a kind gets its slot; a second address or number stays as a labelled line.
                var kind = /^(ф\.?\s*и\.?\s*о|аты|толық аты|full name|name)/i.test(label) ? 'name' :
                    /e-?mail|почта|пошта/i.test(label) ? 'email' : /телефон|phone/i.test(label) ? 'phone' : '';
                if (kind && !data[kind]) data[kind] = value;
                else if (value) data.extra.push([label, value]);
            }
        });
        if (!data.name || odd) { forget('advisor'); return; }
        // Page polish shows the same table as a facts grid just before it; the card replaces both.
        var mirror = holderOf(table).previousElementSibling, view = views.advisor;
        var sources = [mirror && mirror.matches('.up-facts') ? mirror : null, sourceOf(table)].filter(Boolean);
        var signature = [language, data.name, data.email || '', data.phone || ''].concat(data.extra.map(function (e) { return e.join(':'); })).join('|');
        if (view && view.signature === signature && view.node.isConnected && view.node.nextElementSibling === sources[0] && sameSources(view, sources)) return;
        forget('advisor');
        var w = words(), card = create('section', 'univer-scr-view univer-scr-contact');
        var initials = data.name.split(/\s+/).filter(Boolean).slice(0, 2).map(function (part) { return part.charAt(0).toUpperCase(); }).join('');
        var avatar = el('span', 'univer-scr-avatar', initials); avatar.setAttribute('aria-hidden', 'true');
        var who = el('div', 'univer-scr-contact-who');
        who.append(el('p', 'univer-scr-contact-role', w.advisor), el('h3', 'univer-scr-contact-name', data.name));
        var head = el('div', 'univer-scr-contact-head'); head.append(avatar, who); card.appendChild(head);
        var list = el('div', 'univer-scr-contact-lines');
        var email = ((data.email || '').match(/[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+/) || [''])[0];
        var phone = ((data.phone || '').match(/\+?\d[\d\s()\-]{3,}\d/) || [''])[0].replace(/[^\d+]/g, '');
        if (data.email) list.appendChild(el('p', 'univer-scr-contact-line univer-scr-contact-mail', data.email));
        if (data.phone) list.appendChild(el('p', 'univer-scr-contact-line univer-scr-contact-phone', data.phone));
        if (!data.email && !data.phone) list.appendChild(el('p', 'univer-scr-contact-line univer-scr-contact-none', w.noContacts));
        data.extra.forEach(function (entry) {
            var line = el('p', 'univer-scr-contact-line univer-scr-contact-extra');
            line.append(el('span', 'univer-scr-contact-label', entry[0]), document.createTextNode(entry[1]));
            list.appendChild(line);
        });
        card.appendChild(list);
        if (email || phone.length >= 5) {
            var actions = el('div', 'univer-scr-contact-actions');
            if (email) { var mail = el('a', 'univer-scr-action univer-scr-action-mail', w.write); mail.href = 'mailto:' + email; actions.appendChild(mail); }
            if (phone.length >= 5) { var call = el('a', 'univer-scr-action univer-scr-action-call', w.call); call.href = 'tel:' + phone; actions.appendChild(call); }
            card.appendChild(actions);
        }
        sources[0].before(card);
        sources.forEach(function (source) { mark(source, 'univer-scr-source'); });
        views.advisor = {name:'advisor', node:card, signature:signature, marks:sources.map(function (source) { return [source, 'univer-scr-source']; })};
        enter('advisor', [card]);
    }

    // GPA calculator: the rows read as short cards, the result keeps the count of courses and credits under it, and
    // the wide reference tables become one folded grade scale.
    function number(value) { var n = Number(String(value).replace(',', '.')); return isFinite(n) ? n : NaN; }
    function calculator() {
        var table = document.getElementById('gpa-table'), result = document.getElementById('resultCell');
        if (!table || !result) { forget('scaleTable'); forget('gpaMeta'); return; }
        var layout = table.parentElement.closest('table'), reference = null;
        if (layout) Array.from(layout.rows[0]?.cells || []).forEach(function (cell) {
            if (!cell.contains(table) && /буквенн|әріп|letter/i.test(clean(cell.querySelector('th') || ''))) reference = cell;
        });
        gpaMeta(table, result);
        if (!reference) { forget('scaleTable'); return; }
        var spacer = reference.previousElementSibling && !clean(reference.previousElementSibling) && !reference.previousElementSibling.contains(table) ? reference.previousElementSibling : null;
        var signature = language + '|' + clean(reference), view = views.scaleTable;
        if (view && view.signature === signature && view.node.isConnected) return;
        forget('scaleTable');
        var groups = [], others = [], tables = Array.from(reference.querySelectorAll('table'));
        tables.forEach(function (t, index) {
            var group = null;
            ownRows(t).forEach(function (row) {
                var cells = Array.from(row.cells).filter(function (c) { return c.tagName === 'TD'; });
                if (!cells.length) return;
                if (index === 0 && /^[A-F][+-]?$|^FX$/i.test(clean(cells[0]))) {
                    if (cells.length >= 4) groups.push(group = {name:clean(cells[3]), grades:[]});
                    if (!group) groups.push(group = {name:'', grades:[]});
                    group.grades.push({letter:clean(cells[0]), points:clean(cells[1]), percent:clean(cells[2])});
                } else if (cells.length >= 2 && clean(cells[0])) {
                    var range = cells.length >= 3 && clean(cells[2]) !== '-' ? lineText(cells[2]).replace(/\s+/g, ' / ') : '';
                    others.push({label:clean(cells[0]), text:clean(lineText(cells[cells.length - 1]).replace(/"+/g, '')) + (range ? ' · ' + range : '')});
                }
            });
        });
        if (!groups.length) return;
        var w = words(), section = create('section', 'univer-scr-view univer-scr-grades');
        var toggle = el('button', 'univer-scr-grades-toggle'); toggle.type = 'button'; toggle.setAttribute('aria-expanded', 'false');
        toggle.appendChild(el('span', 'univer-scr-grades-title', w.grades));
        var body = el('div', 'univer-scr-grades-body'); body.hidden = true;
        toggle.setAttribute('aria-controls', body.id = 'univer-scr-grades-body');
        groups.forEach(function (group) {
            var block = el('div', 'univer-scr-grade-group');
            if (group.name) block.appendChild(el('p', 'univer-scr-grade-name', group.name));
            var row = el('div', 'univer-scr-grade-row');
            group.grades.forEach(function (grade) {
                var chip = el('span', 'univer-scr-grade'); chip.dataset.letter = grade.letter.charAt(0).toUpperCase();
                chip.append(el('b', '', grade.letter), el('span', 'univer-scr-grade-points', grade.points), el('span', 'univer-scr-grade-percent', grade.percent));
                row.appendChild(chip);
            });
            block.appendChild(row); body.appendChild(block);
        });
        if (others.length) {
            body.appendChild(el('p', 'univer-scr-grade-name', w.otherMarks));
            var list = el('dl', 'univer-scr-marks');
            others.forEach(function (item) { list.append(el('dt', '', item.label), el('dd', '', item.text)); });
            body.appendChild(list);
        }
        section.append(toggle, body);
        listen(toggle, 'click', function (event) {
            var open = body.hidden, before = section.getBoundingClientRect().height;
            ui()?.stop(section); body.hidden = !open; toggle.setAttribute('aria-expanded', String(open));
            if (moving(event)) { ui()?.resize(section, before, open ? 260 : 200); if (open) ui()?.reveal(body, 'content'); }
        });
        var panelCell = layout.closest('td') || layout.parentElement, holder = layout.parentElement.matches('.univer-app-table-scroll') ? layout.parentElement : layout;
        holder.after(section);
        var marks = [[reference, 'univer-scr-source'], [layout, 'univer-scr-gpa-layout']];
        mark(reference, 'univer-scr-source'); mark(layout, 'univer-scr-gpa-layout');
        if (spacer) { mark(spacer, 'univer-scr-source'); marks.push([spacer, 'univer-scr-source']); }
        if (holder !== layout) { mark(holder, 'univer-scr-flat'); marks.push([holder, 'univer-scr-flat']); }
        views.scaleTable = {name:'scaleTable', node:section, signature:signature, marks:marks, cell:panelCell};
    }
    function gpaMeta(table, result) {
        var bar = result.parentElement, view = views.gpaMeta;
        if (!view || !view.node.isConnected || view.node.parentNode !== bar) {
            forget('gpaMeta');
            var meta = create('span', 'univer-scr-gpa-meta'); meta.setAttribute('aria-live', 'polite');
            bar.appendChild(meta); mark(bar, 'univer-scr-gpa-bar');
            var update = function () { setTimeout(function () { if (views.gpaMeta) quiet(function () { gpaCount(table, views.gpaMeta.node); }); }, 0); };
            table.addEventListener('input', update); table.addEventListener('change', update);
            views.gpaMeta = view = {name:'gpaMeta', node:meta, marks:[[bar, 'univer-scr-gpa-bar']], cleanup:function () {
                table.removeEventListener('input', update); table.removeEventListener('change', update);
            }};
        }
        gpaCount(table, view.node);
    }
    function gpaCount(table, meta) {
        var courses = 0, credits = 0;
        Array.from(table.rows).forEach(function (row) {
            var select = row.querySelector('select.mark-select'), input = row.querySelector('input.credit-input');
            if (!select || !input || select.selectedIndex <= 0) return;
            var value = number(input.value); if (!(value > 0)) return;
            courses++; credits += value;
        });
        var w = words();
        setText(meta, courses ? w.courses(courses) + ' · ' + w.credits(Math.round(credits * 10) / 10) : '');
    }

    // Study plan: a course card reads as its name, one row of figures (cycle, credits, lecture, seminar, lab) and the
    // control type; fields the site leaves as "-" are not shown. Columns are read by position, the same in every language.
    function courses() {
        document.querySelectorAll('#l4 table.univer-app-cards tr.univer-app-card-row').forEach(function (row) {
            if (row.cells.length !== 10 || !row.cells[1].classList.contains('univer-app-long-field')) return;
            mark(row, 'univer-scr-course');
            Array.from(row.cells).forEach(function (cell) {
                if (/^-?$/.test(clean(cell)) && !cell.querySelector('input,select,textarea,button,a,img,svg')) mark(cell, 'univer-scr-blank');
                else unmark(cell, 'univer-scr-blank');
            });
        });
    }
    // Study plan: categories with no credits fold behind one button; a plan with credits in every category is unchanged.
    function plan() {
        courses();
        var boxes = Array.from(document.querySelectorAll('#l4 .up-facts.up-stats')).filter(function (box) {
            return box.querySelectorAll('.up-stat').length >= 6 && box.querySelectorAll('.up-stat.up-zero').length >= 3;
        });
        var signature = language + '|' + boxes.map(function (box) { return box.querySelectorAll('.up-stat').length + ':' + box.querySelectorAll('.up-zero').length; }).join('|');
        var view = views.plan;
        if (view && view.signature === signature && view.nodes.every(function (n) { return n.isConnected; }) &&
            boxes.every(function (box, i) { return box.nextElementSibling === view.nodes[i]; })) return;
        forget('plan');
        if (!boxes.length) return;
        var w = words(), nodes = [], marks = [];
        boxes.forEach(function (box) {
            var zeros = box.querySelectorAll('.up-stat.up-zero').length, all = zeros === box.querySelectorAll('.up-stat').length;
            var wrap = create('div', 'univer-scr-view univer-scr-zeros');
            if (all) wrap.appendChild(el('p', 'univer-scr-zeros-note', w.zeroAll));
            var button = el('button', 'univer-scr-zeros-toggle', w.zeroShow(zeros)); button.type = 'button'; button.setAttribute('aria-expanded', 'false');
            wrap.appendChild(button);
            listen(button, 'click', function (event) {
                var open = box.classList.contains('univer-scr-zeros-hidden'), before = box.getBoundingClientRect().height;
                ui()?.stop(box);
                if (open) unmark(box, 'univer-scr-zeros-hidden'); else mark(box, 'univer-scr-zeros-hidden');
                button.setAttribute('aria-expanded', String(open)); button.textContent = open ? w.zeroHide : w.zeroShow(zeros);
                if (moving(event)) ui()?.resize(box, Math.max(before, 1), open ? 260 : 200);
            });
            box.after(wrap); mark(box, 'univer-scr-zeros-hidden'); if (all) mark(box, 'univer-scr-zeros-all');
            nodes.push(wrap); marks.push([box, 'univer-scr-zeros-hidden'], [box, 'univer-scr-zeros-all']);
        });
        views.plan = {name:'plan', nodes:nodes, marks:marks, signature:signature};
    }

    // IGPA: switching a section shows it with the same short entrance as the rest of the app.
    function igpa() {
        var nav = document.querySelector('#l4 nav.igpa-nav');
        if (!nav || views.igpa?.node === nav) return;
        forget('igpa');
        var handler = function (event) {
            var link = event.target.closest && event.target.closest('a[href^="#igpa-"]');
            if (!link || !moving(event)) return;
            setTimeout(function () {
                var panel = document.getElementById(link.getAttribute('href').slice(1));
                if (panel && panel.getClientRects().length) ui()?.reveal(panel, 'content');
            }, 0);
        };
        nav.addEventListener('click', handler);
        views.igpa = {name:'igpa', node:nav, nodes:[], cleanup:function () { nav.removeEventListener('click', handler); }};
    }

    // The main action of a form on these pages is a filled button; Back, Cancel and Clear keep the quiet look.
    var MAIN_ACTION = /^(отправить|сохранить|подать|загрузить|показать|жіберу|сақтау|беру|жүктеу|көрсету|send|save|submit|upload|show)/i;
    // FAQ: a question written as a long paragraph reads in a lighter weight than the short ones; its text stays whole.
    function faq() {
        document.querySelectorAll('#l4 details.univer-app-faq>summary').forEach(function (summary) {
            if (clean(summary).length > 160) mark(summary, 'univer-scr-long-q'); else unmark(summary, 'univer-scr-long-q');
        });
    }
    function mainActions() {
        document.querySelectorAll('#l4 input[type=submit],#l4 input[type=button],#l4 button').forEach(function (button) {
            if (button.closest('#tools,#login_frm,.univer-scr-view,.univer-app-menu,nav,.igpa-ui,#appealModal') || /(^|\s)(univer-app|up-secondary|igpa-button|univer-scr-(more|fold|zeros|grades|start))/.test(button.className)) return;
            var value = (button.tagName === 'INPUT' ? button.value : clean(button)).trim();
            if (MAIN_ACTION.test(value) && (button.type === 'submit' || button.closest('form,.appeal-buttons'))) mark(button, 'univer-scr-primary');
        });
    }

    var ROUTES = [
        [/^\/student\/my(exam|midterm)\/schedule(\/|$)/i, 'exams', exams],
        [/^\/student\/stud\/rating\/anket(\/|$)/i, 'igpa', igpa],
        [/^\/student\/(fail|failfx|gpaincrease)(\/|$)/i, 'orders', orders],
        [/^\/my\/appeals(\/|$)/i, 'appeals', appeals],
        [/^\/student\/rating(\/|$)/i, 'rating', rating],
        [/^\/student\/advicer(\/|$)/i, 'advisor', advisor],
        [/^\/my\/calculatorgpa(\/|$)/i, 'gpa', calculator],
        [/^\/student\/educplan(\/|$)/i, 'plan', plan],
        [/^\/my\/(messages|faq|docs)(\/|$)/i, 'site', faq],
        [/^\/student\/(monrknews|mydata|practik|contract|academcalendar|profile|testlist)(\/|$)/i, 'site', null]
    ];
    function icon(path) {
        return 'url("data:image/svg+xml,' + encodeURIComponent("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>" + path + '</svg>') + '")';
    }
    // Every declaration is !important: inside the first layer that beats the layout's unlayered rules and the polish
    // layer; outside :root.univer-scr nothing here applies.
    function important(css) {
        return css.replace(/\{([^{}]*)\}/g, function (all, body) {
            return '{' + body.split(';').map(function (d) { return d.trim() && !/!important/.test(d) ? d + '!important' : d; }).join(';') + '}';
        });
    }
    function css() {
        var P = ':root.univer-mobile.univer-scr', M = P + ':not(.univer-app-still):not(.univer-app-keyboard)';
        var person = icon("<circle cx='12' cy='8' r='4'/><path d='M4 21c1.6-4 4.6-6 8-6s6.4 2 8 6'/>"), pin = icon("<path d='M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z'/><circle cx='12' cy='9.5' r='2.5'/>");
        var people = icon("<circle cx='9' cy='8' r='3.5'/><path d='M2.5 20c.9-3.4 3.4-5.5 6.5-5.5s5.6 2.1 6.5 5.5'/><path d='M16 4.6a3.5 3.5 0 0 1 0 6.8M18.5 14.8c1.5.9 2.5 2.6 3 5.2'/>");
        var check = icon("<circle cx='12' cy='12' r='9'/><path d='m8.5 12.5 2.5 2.5 4.5-5'/>"), inbox = icon("<path d='M22 12h-6l-2 3h-4l-2-3H2'/><path d='M5.5 5.1 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.8 4H7.2a2 2 0 0 0-1.7 1.1z'/>");
        var chevron = icon("<path d='m6 9 6 6 6-6'/>"), mail = icon("<rect x='3' y='5' width='18' height='14' rx='2'/><path d='m3 7 9 6 9-6'/>");
        var phone = icon("<path d='M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1.9.4 1.8.7 2.7a2 2 0 0 1-.5 2.1L8 9.8a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.5c.9.3 1.8.6 2.7.7a2 2 0 0 1 1.7 2z'/>");
        var plus = icon("<path d='M12 5v14'/><path d='M5 12h14'/>"), link = icon("<path d='M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7'/><path d='M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7'/>"),
        calendar = icon("<rect x='3' y='5' width='18' height='16' rx='2'/><path d='M16 3v4M8 3v4M3 10h18M12 13v5M9.5 15.5h5'/>");
        var plusWhite = 'url("data:image/svg+xml,' + encodeURIComponent("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='white' stroke-width='2.2' stroke-linecap='round'><path d='M12 5v14'/><path d='M5 12h14'/></svg>") + '")';
        var mask = function (image, size) { return '-webkit-mask:' + image + ' center/' + (size || '18px') + ' no-repeat;mask:' + image + ' center/' + (size || '18px') + ' no-repeat'; };
        return important(`
@layer univer-screens {
${P} #l4 .univer-scr-source { display:none; }
${P} #l4 .univer-scr-view { box-sizing:border-box; font-family:var(--u-font); }
/* The resets weigh less than any rule below (:where adds nothing), so a card that is also a <li> keeps its own box. */
${P} #l4 :where(.univer-scr-view) * { box-sizing:border-box; }
${P} #l4 :where(.univer-scr-view) :where(h3,h4,p,ol,ul,li,dl,dt,dd) { margin:0; padding:0; border:0; background:none; text-align:start; text-transform:none; letter-spacing:normal; }
${P} #l4 :where(.univer-scr-view) :where(ol,ul) { list-style:none; }
${P} #l4 :where(.univer-scr-view) :where(li)::before, ${P} #l4 :where(.univer-scr-view) :where(li)::marker { content:none; }

/* Exams: the next exam carries the one bright moment, the days below stay quiet. */
${P} #l4 .univer-scr-exams { display:flex; flex-direction:column; gap:20px; margin:4px 0 8px; }
${P} #l4 .univer-scr-next { display:grid; grid-template-columns:minmax(0,1fr) auto; column-gap:16px; row-gap:4px; align-items:center; padding:16px; border:1px solid var(--u-brand-line); border-radius:16px; background:var(--u-brand-soft); }
${P} #l4 .univer-scr-eyebrow { grid-column:1; color:var(--u-brand-strong); font-size:var(--u-fs-meta); font-weight:500; line-height:1.3; }
${P} #l4 .univer-scr-next-name { grid-column:1; color:var(--u-ink); font-size:var(--u-fs-section); font-weight:600; line-height:1.3; overflow-wrap:anywhere; }
${P} #l4 .univer-scr-next-when { grid-column:1; color:var(--u-text); font-size:var(--u-fs-meta); line-height:1.4; }
${P} #l4 .univer-scr-count { grid-column:2; grid-row:1 / span 3; display:flex; flex-direction:column; align-items:center; justify-content:center; min-width:76px; min-height:76px; padding:8px; border-radius:12px; background:var(--u-surface); }
${P} #l4 .univer-scr-count-value { color:var(--u-brand-strong); font-size:32px; font-weight:600; line-height:1; font-variant-numeric:tabular-nums; }
${P} #l4 .univer-scr-count-unit { margin-top:4px; color:var(--u-muted); font-size:var(--u-fs-caption); line-height:1.2; }
${P} #l4 .univer-scr-days { display:flex; flex-direction:column; gap:20px; }
${P} #l4 .univer-scr-day-head { display:flex; align-items:center; gap:12px; margin-bottom:8px; }
${P} #l4 .univer-scr-day-date { display:flex; flex:none; flex-direction:column; align-items:center; justify-content:center; width:48px; height:48px; border:1px solid var(--u-line); border-radius:12px; background:var(--u-surface); }
${P} #l4 .univer-scr-day-number { color:var(--u-ink); font-size:18px; font-weight:600; line-height:1.1; font-variant-numeric:tabular-nums; }
${P} #l4 .univer-scr-day-month { color:var(--u-muted); font-size:var(--u-fs-caption); line-height:1.2; }
${P} #l4 h3.univer-scr-day-name { min-width:0; color:var(--u-ink); font-size:var(--u-fs-strong); font-weight:500; line-height:1.3; }
${P} #l4 h3.univer-scr-day-name::first-letter { text-transform:uppercase; }
${P} #l4 .univer-scr-day-rel { margin-left:auto; color:var(--u-muted); font-size:var(--u-fs-meta); line-height:1.3; white-space:nowrap; }
${P} #l4 .univer-scr-day[data-state=today] .univer-scr-day-date { border-color:transparent; background:var(--u-brand-fill,var(--u-brand)); }
${P} #l4 .univer-scr-day[data-state=today] :is(.univer-scr-day-number,.univer-scr-day-month) { color:#fff; }
${P} #l4 .univer-scr-day[data-state=today] .univer-scr-day-rel { color:var(--u-brand-strong); font-weight:500; }
${P} #l4 .univer-scr-day[data-state=past] { opacity:.62; }
${P} #l4 .univer-scr-slots { display:flex; flex-direction:column; gap:8px; }
${P} #l4 .univer-scr-slot { display:grid; grid-template-columns:max-content minmax(0,1fr); gap:12px; padding:14px 16px 14px 12px; border:1px solid var(--u-line); border-radius:14px; background:var(--u-surface); }
${P} #l4 .univer-scr-slot[data-kind=exam] { border-color:var(--u-brand-line); box-shadow:inset 3px 0 0 var(--u-brand); }
${P} #l4 .univer-scr-slot-time { padding-top:1px; color:var(--u-ink); font-size:var(--u-fs-strong); font-weight:600; line-height:1.35; font-variant-numeric:tabular-nums; text-align:center; white-space:nowrap; }
${P} #l4 .univer-scr-slot-body { display:flex; flex-direction:column; gap:4px; min-width:0; }
${P} #l4 .univer-scr-slot-tags { display:flex; flex-wrap:wrap; align-items:center; gap:4px 8px; }
${P} #l4 .univer-scr-kind { display:inline-flex; align-items:center; min-height:24px; padding:0 8px; border-radius:6px; background:var(--u-surface-2); color:var(--u-muted); font-size:var(--u-fs-caption); font-weight:500; line-height:1.2; }
${P} #l4 .univer-scr-kind[data-kind=exam] { background:var(--u-brand-soft); color:var(--u-brand-strong); }
${P} #l4 .univer-scr-kind[data-kind=consult] { background:var(--u-teal-soft); color:var(--u-teal); }
${P} #l4 .univer-scr-slot-note { color:var(--u-muted); font-size:var(--u-fs-caption); line-height:1.3; }
${P} #l4 h4.univer-scr-slot-name { color:var(--u-ink); font-size:var(--u-fs-strong); font-weight:600; line-height:1.35; overflow-wrap:anywhere; }
${P} #l4 .univer-scr-slot-meta { display:flex; flex-wrap:wrap; gap:4px 14px; color:var(--u-text); font-size:var(--u-fs-meta); line-height:1.4; }
${P} #l4 .univer-scr-slot-meta>span { display:inline-flex; align-items:center; gap:6px; min-width:0; overflow-wrap:anywhere; }
${P} #l4 .univer-scr-slot-meta>span::before { content:''; flex:none; width:16px; height:16px; background:var(--u-muted); }
${P} #l4 .univer-scr-who::before { ${mask(person, '16px')}; }
${P} #l4 .univer-scr-where::before { ${mask(pin, '16px')}; }
${P} #l4 .univer-scr-group::before { ${mask(people, '16px')}; }
${P} #l4 .univer-scr-slot-links { display:flex; flex-wrap:wrap; gap:8px; margin-top:6px; }
${P} #l4 a.univer-scr-link { display:inline-flex; align-items:center; gap:6px; min-height:40px; padding:0 14px; border:1px solid var(--u-brand-line); border-radius:10px; background:var(--u-surface); color:var(--u-brand); font-size:var(--u-fs-meta); font-weight:500; text-decoration:none; }
${P} #l4 button.univer-scr-start { min-height:44px; margin:0; padding:0 18px; border:0; border-radius:10px; background:var(--u-brand-fill,var(--u-brand)); color:#fff; font:500 var(--u-fs-meta)/1.2 var(--u-font); cursor:pointer; }
${P} #l4 a.univer-scr-link::before { content:''; width:16px; height:16px; background:currentColor; ${mask(link, '16px')}; }
${P} #l4 button.univer-scr-calendar { display:inline-flex; align-items:center; gap:6px; min-height:44px; margin:0; padding:0 14px; border:1px solid var(--u-brand-line); border-radius:10px; background:var(--u-surface); color:var(--u-brand); font:500 var(--u-fs-meta)/1.2 var(--u-font); cursor:pointer; }
${P} #l4 button.univer-scr-calendar::before { content:''; flex:none; width:16px; height:16px; background:currentColor; ${mask(calendar, '16px')}; }
${P} #l4 .univer-scr-next>button.univer-scr-calendar { justify-self:start; margin-top:12px; }
${P} #l4 .univer-scr-slot-links>button.univer-scr-calendar { padding:0 8px; border-color:transparent; background:transparent; }
${P} #l4 .univer-scr-slot-links>button.univer-scr-calendar:first-child { margin-left:-8px; }

/* A folded site panel: its heading row stays, with a quiet Show/Hide button on the right. */
${P} #l4 table.mt.univer-scr-folded>tbody>tr:not(:first-child) { display:none; }
${P} #l4 table.mt.univer-scr-foldable>tbody>tr:first-child>td.ct { display:flex; flex-wrap:wrap; align-items:center; gap:8px 12px; overflow-wrap:break-word; word-break:normal; }
${P} #l4 button.univer-scr-fold { display:inline-flex; align-items:center; gap:8px; min-height:40px; margin:0 0 0 auto; padding:0 12px; border:1px solid var(--u-line); border-radius:10px; background:var(--u-surface); color:var(--u-brand); font:500 var(--u-fs-meta)/1.2 var(--u-font); white-space:nowrap; cursor:pointer; }
${P} #l4 button.univer-scr-fold::after { content:''; width:16px; height:16px; background:currentColor; ${mask(chevron, '16px')}; transform:rotate(180deg); }
${P} #l4 table.univer-scr-folded button.univer-scr-fold::after { transform:none; }
${P} #l4 .univer-scr-fold-count { color:var(--u-muted); font-weight:400; }
${P} #l4 .univer-scr-fold-count:empty { display:none; }

/* Folded memo text: a few lines and a fade into the card. */
${P} #l4 .univer-scr-clamp:not(.univer-scr-open) { max-height:10.5em; overflow:hidden; -webkit-mask-image:linear-gradient(#000 60%,transparent); mask-image:linear-gradient(#000 60%,transparent); }
${P} #l4 button.univer-scr-more { display:inline-flex; align-items:center; gap:6px; min-height:44px; margin:8px 0 0; padding:0 14px; border:1px solid var(--u-brand-line); border-radius:10px; background:var(--u-surface); color:var(--u-brand); font:500 var(--u-fs-meta)/1.2 var(--u-font); cursor:pointer; }
${P} #l4 button.univer-scr-more::after { content:''; width:16px; height:16px; background:currentColor; ${mask(chevron, '16px')}; }
${P} #l4 button.univer-scr-more[aria-expanded=true]::after { transform:rotate(180deg); }

/* Empty states: what is empty, in words, with a calm icon. */
${P} #l4 .univer-scr-empty { display:flex; align-items:flex-start; gap:12px; margin:8px 0 16px; padding:16px; border:1px solid var(--u-line); border-radius:14px; background:var(--u-surface); }
${P} #l4 .univer-scr-empty-icon { flex:none; width:40px; height:40px; border-radius:12px; background:var(--u-surface-2); position:relative; }
${P} #l4 .univer-scr-empty-icon::after { content:''; position:absolute; inset:0; background:var(--u-muted); ${mask(inbox, '20px')}; }
${P} #l4 .univer-scr-empty[data-tone=good] .univer-scr-empty-icon { background:var(--u-teal-soft); }
${P} #l4 .univer-scr-empty[data-tone=good] .univer-scr-empty-icon::after { background:var(--u-teal); ${mask(check, '20px')}; }
${P} #l4 .univer-scr-empty-text { display:flex; flex-direction:column; gap:2px; min-width:0; padding-top:1px; }
${P} #l4 h3.univer-scr-empty-title { color:var(--u-ink); font-size:var(--u-fs-strong); font-weight:500; line-height:1.4; }
${P} #l4 .univer-scr-empty-note { color:var(--u-muted); font-size:var(--u-fs-meta); line-height:1.45; }

/* Filled main action; the quiet secondary look stays the polish's. */
${P} #l4 :is(input,button).univer-scr-primary { min-height:48px; padding:0 20px; border:0; border-radius:12px; background:var(--u-brand-fill,var(--u-brand)); background-image:none; color:#fff; font:500 var(--u-fs-strong)/1.2 var(--u-font); box-shadow:none; cursor:pointer; }
${P} #l4 :is(input,button).univer-scr-primary:disabled { opacity:.55; }
@media (hover:hover) { ${P} #l4 :is(input,button).univer-scr-primary:hover { background:var(--u-brand-strong-fill,var(--u-brand-strong)); } }
${P} #l4 input.univer-scr-add { width:auto; max-width:100%; padding-left:48px; padding-right:20px; background-image:${plusWhite}; background-repeat:no-repeat; background-position:16px center; background-size:20px; }
${P} #l4 .appeal-actions { display:block; margin:0 0 12px; padding:0; text-align:start; }
${P} #l4 .appeal-buttons { display:grid; grid-template-columns:1fr 2fr; gap:8px; align-items:stretch; margin-top:16px; }
${P} #l4 .appeal-buttons>input { width:100%; min-height:48px; margin:0; }
${P} #l4 .appeal-buttons>img#appealWait { grid-column:1 / -1; justify-self:center; }
${P} #l4 .appeal-form-row { margin:0 0 16px; }
${P} #l4 .appeal-form-row>label { display:block; margin:0 0 6px; color:var(--u-ink); font-size:var(--u-fs-body); font-weight:500; }
${P} #l4 .appeal-form-row textarea { min-height:160px; }
${P} #l4 #appealTextCount { color:var(--u-muted); font-size:var(--u-fs-caption); font-variant-numeric:tabular-nums; }
${P} #l4 .appeal-file-help { margin-top:6px; color:var(--u-muted); font-size:var(--u-fs-meta); line-height:1.45; }
${P} #appealModal { box-sizing:border-box; max-width:calc(100vw - 32px); padding:20px; border:0; border-radius:16px; background:var(--u-surface); color:var(--u-text); box-shadow:var(--u-shadow-pop); font-family:var(--u-font); }
${P} #appealModalHeader { color:var(--u-ink); font-size:var(--u-fs-section); font-weight:600; background:none; padding:0 0 8px; border:0; }
${P} #appealModalFooter { padding:16px 0 0; border:0; background:none; text-align:end; }
${P} #appealModalFooter input { min-height:44px; padding:0 20px; border:0; border-radius:10px; background:var(--u-brand-fill,var(--u-brand)); color:#fff; font:500 var(--u-fs-body)/1 var(--u-font); }

/* Files: the browser's own picker button reads as a quiet app button on every form here. */
${P} #l4 input[type=file] { max-width:100%; color:var(--u-muted); font:var(--u-fs-meta)/1.4 var(--u-font); }
${P} #l4 input[type=file]::file-selector-button { min-height:40px; margin:0 12px 0 0; padding:0 14px; border:1px solid var(--u-brand-line); border-radius:10px; background:var(--u-surface); color:var(--u-brand); font:500 var(--u-fs-meta)/1 var(--u-font); cursor:pointer; }

/* Survey: the answer scale, its fill and the progress of a survey. */
${P} #l4 .univer-scr-scale { display:flex; flex-direction:column; gap:8px; margin:0 0 16px; padding:14px 16px; border:1px solid var(--u-line); border-radius:14px; background:var(--u-surface); }
${P} #l4 .univer-scr-scale-title { color:var(--u-ink); font-size:var(--u-fs-meta); font-weight:500; }
${P} #l4 .univer-scr-scale-row { display:grid; grid-template-columns:repeat(5,1fr); gap:6px; }
${P} #l4 .univer-scr-scale-step { display:flex; align-items:center; justify-content:center; height:40px; border-radius:10px; background:var(--u-surface-2); color:var(--u-ink); font-size:var(--u-fs-strong); font-weight:600; font-variant-numeric:tabular-nums; }
${P} #l4 .univer-scr-scale-step[data-step="4"], ${P} #l4 .univer-scr-scale-step[data-step="5"] { background:var(--u-brand-soft); color:var(--u-brand-strong); }
${P} #l4 .univer-scr-scale-ends { display:flex; justify-content:space-between; gap:12px; color:var(--u-muted); font-size:var(--u-fs-caption); line-height:1.3; }
${P} #l4 .univer-scr-scale-ends>span:last-child { text-align:end; }
${P} #l4 .univer-scr-progress { display:flex; flex-direction:column; gap:8px; margin:0 0 16px; padding:16px; border:1px solid var(--u-line); border-radius:14px; background:var(--u-surface); }
${P} #l4 .univer-scr-progress-top { display:flex; align-items:baseline; justify-content:space-between; gap:12px; }
${P} #l4 .univer-scr-progress-label { color:var(--u-muted); font-size:var(--u-fs-meta); }
${P} #l4 .univer-scr-progress-value { color:var(--u-muted); font-size:var(--u-fs-body); font-variant-numeric:tabular-nums; }
${P} #l4 .univer-scr-progress-value>b { color:var(--u-ink); font-size:24px; font-weight:600; }
${P} #l4 .univer-scr-progress-bar { position:relative; height:8px; overflow:hidden; border-radius:4px; background:var(--u-surface-2); }
${P} #l4 .univer-scr-progress-fill { position:absolute; inset:0; border-radius:4px; background:var(--u-brand); transform-origin:left center; }
${P} #l4 .univer-scr-progress[data-complete=true] .univer-scr-progress-fill { background:var(--u-teal); }
${P} #l4 .univer-scr-progress-note { color:var(--u-text); font-size:var(--u-fs-meta); line-height:1.4; }
${P} #l4 .univer-scr-progress[data-complete=true] .univer-scr-progress-note { color:var(--u-teal); }
${P}.univer-scr-page-rating #l4 input[type=radio] { width:22px; height:22px; accent-color:var(--u-brand); vertical-align:middle; }

/* Advisor contact card. */
${P} #l4 .univer-scr-contact { display:flex; flex-direction:column; gap:14px; padding:16px; border:1px solid var(--u-line); border-radius:16px; background:var(--u-surface); }
${P} #l4 .univer-scr-contact-head { display:flex; align-items:center; gap:14px; }
${P} #l4 .univer-scr-avatar { display:flex; flex:none; align-items:center; justify-content:center; width:56px; height:56px; border-radius:50%; background:var(--u-brand-soft); color:var(--u-brand-strong); font-size:20px; font-weight:600; letter-spacing:.02em; }
${P} #l4 .univer-scr-contact-who { display:flex; flex-direction:column; gap:2px; min-width:0; }
${P} #l4 .univer-scr-contact-role { color:var(--u-muted); font-size:var(--u-fs-meta); }
${P} #l4 h3.univer-scr-contact-name { color:var(--u-ink); font-size:var(--u-fs-section); font-weight:600; line-height:1.3; overflow-wrap:anywhere; }
${P} #l4 .univer-scr-contact-lines { display:flex; flex-direction:column; gap:6px; }
${P} #l4 .univer-scr-contact-line { display:flex; align-items:center; gap:10px; color:var(--u-text); font-size:var(--u-fs-body); overflow-wrap:anywhere; }
${P} #l4 .univer-scr-contact-line::before { content:''; flex:none; width:18px; height:18px; background:var(--u-muted); }
${P} #l4 .univer-scr-contact-mail::before { ${mask(mail)}; }
${P} #l4 .univer-scr-contact-phone::before { ${mask(phone)}; }
${P} #l4 .univer-scr-contact-none { color:var(--u-muted); }
${P} #l4 .univer-scr-contact-none::before { ${mask(inbox)}; }
${P} #l4 .univer-scr-contact-extra { display:block; }
${P} #l4 .univer-scr-contact-extra::before { display:none; }
${P} #l4 .univer-scr-contact-label { display:block; color:var(--u-muted); font-size:var(--u-fs-meta); }
${P} #l4 details.univer-app-faq>summary.univer-scr-long-q { font-weight:500; }
/* A long section title squeezed polish's count until its digits broke onto two lines (td.tt wraps anywhere); its fixed
   22px height also let large text spill out of the pill. */
${P} #l4 summary>.up-count { flex:none; height:auto; min-height:22px; white-space:nowrap; overflow-wrap:normal; }
${P} #l4 .univer-scr-contact-actions { display:grid; grid-template-columns:repeat(auto-fit,minmax(120px,1fr)); gap:8px; }
${P} #l4 a.univer-scr-action { display:inline-flex; align-items:center; justify-content:center; gap:8px; min-height:48px; padding:0 16px; border-radius:12px; background:var(--u-brand-fill,var(--u-brand)); color:#fff; font-size:var(--u-fs-body); font-weight:500; text-decoration:none; }
${P} #l4 a.univer-scr-action-call { border:1px solid var(--u-brand-line); background:var(--u-surface); color:var(--u-brand); }
${P} #l4 a.univer-scr-action::before { content:''; width:18px; height:18px; background:currentColor; }
${P} #l4 a.univer-scr-action-mail::before { ${mask(mail)}; }
${P} #l4 a.univer-scr-action-call::before { ${mask(phone)}; }

/* GPA calculator: short course cards, the result in reach, one folded grade scale. */
${P} #l4 .univer-scr-flat { overflow:visible; padding:0; border:0; border-radius:0; background:none; box-shadow:none; }
${P} #l4 .univer-scr-flat+.univer-app-scroll-hint, ${P} #l4 .univer-app-scroll-hint:has(+.univer-scr-flat) { display:none; }
${P} #l4 table.univer-scr-gpa-layout, ${P} #l4 table.univer-scr-gpa-layout>tbody, ${P} #l4 table.univer-scr-gpa-layout>tbody>tr, ${P} #l4 table.univer-scr-gpa-layout>tbody>tr>td:not(.univer-scr-source) { display:block; width:auto; min-width:0; margin:0; padding:0; border:0; }
/* Credits and grade side by side where both fit; the narrowest phones keep the layout's single column. */
@media (min-width:361px) {
${P} #l4 #gpa-table tr.univer-app-card-row { grid-template-columns:minmax(0,1fr) minmax(0,1.3fr); align-items:end; }
${P} #l4 #gpa-table tr.univer-app-card-row>td:not(:first-child) { grid-column:auto; }
}
${P} #l4 .univer-scr-gpa-bar { position:sticky; bottom:12px; z-index:3; display:flex; flex-wrap:wrap; align-items:baseline; column-gap:8px; row-gap:2px; width:100%; box-shadow:var(--u-shadow-pop); }
${P} #l4 .univer-scr-gpa-meta { flex-basis:100%; color:var(--u-muted); font-size:var(--u-fs-meta); font-weight:400; line-height:1.3; font-variant-numeric:tabular-nums; }
${P} #l4 .univer-scr-gpa-meta:empty { display:none; }
${P} #l4 .univer-scr-grades { margin:16px 0 0; border:1px solid var(--u-line); border-radius:14px; background:var(--u-surface); overflow:hidden; }
${P} #l4 button.univer-scr-grades-toggle { display:flex; align-items:center; justify-content:space-between; gap:12px; width:100%; min-height:52px; margin:0; padding:0 16px; border:0; border-radius:0; background:none; color:var(--u-ink); font:500 var(--u-fs-strong)/1.3 var(--u-font); text-align:start; cursor:pointer; }
${P} #l4 button.univer-scr-grades-toggle::after { content:''; flex:none; width:18px; height:18px; background:var(--u-muted); ${mask(chevron)}; }
${P} #l4 button.univer-scr-grades-toggle[aria-expanded=true]::after { transform:rotate(180deg); }
${P} #l4 .univer-scr-grades-body { display:flex; flex-direction:column; gap:12px; padding:0 16px 16px; }
${P} #l4 .univer-scr-grades-body[hidden] { display:none; }
${P} #l4 .univer-scr-grade-name { color:var(--u-muted); font-size:var(--u-fs-meta); font-weight:500; margin:0 0 6px; }
${P} #l4 .univer-scr-grade-row { display:grid; grid-template-columns:repeat(auto-fill,minmax(88px,1fr)); gap:6px; }
${P} #l4 .univer-scr-grade { display:grid; grid-template-columns:auto 1fr; column-gap:8px; align-items:baseline; padding:8px 10px; border-radius:10px; background:var(--u-surface-2); }
${P} #l4 .univer-scr-grade>b { grid-row:1 / span 2; color:var(--u-ink); font-size:var(--u-fs-section); font-weight:600; }
${P} #l4 .univer-scr-grade[data-letter=A]>b { color:var(--u-teal); }
${P} #l4 .univer-scr-grade[data-letter=F]>b { color:var(--u-danger); }
${P} #l4 .univer-scr-grade-points { color:var(--u-text); font-size:var(--u-fs-meta); font-variant-numeric:tabular-nums; }
${P} #l4 .univer-scr-grade-percent { color:var(--u-muted); font-size:var(--u-fs-caption); font-variant-numeric:tabular-nums; }
${P} #l4 dl.univer-scr-marks { display:grid; grid-template-columns:auto 1fr; gap:6px 12px; }
${P} #l4 dl.univer-scr-marks>dt { color:var(--u-ink); font-size:var(--u-fs-meta); font-weight:600; }
${P} #l4 dl.univer-scr-marks>dd { color:var(--u-text); font-size:var(--u-fs-meta); line-height:1.4; }

/* Study plan: zero categories fold away. */
${P} #l4 .up-facts.univer-scr-zeros-hidden .up-stat.up-zero { display:none; }
${P} #l4 .up-facts.univer-scr-zeros-hidden.univer-scr-zeros-all { display:none; }
${P} #l4 .univer-scr-zeros { display:flex; flex-direction:column; align-items:flex-start; gap:8px; margin:8px 0 16px; }
${P} #l4 .univer-scr-zeros-note { color:var(--u-muted); font-size:var(--u-fs-meta); }
${P} #l4 button.univer-scr-zeros-toggle { min-height:44px; margin:0; padding:0 14px; border:1px solid var(--u-brand-line); border-radius:10px; background:var(--u-surface); color:var(--u-brand); font:500 var(--u-fs-meta)/1.2 var(--u-font); text-align:start; cursor:pointer; }

/* Study plan courses: the name on top, five short figures in one row, the rest under them. */
${P} #l4 tr.univer-scr-course { grid-template-columns:repeat(5,minmax(0,1fr)); gap:10px 8px; }
${P} #l4 tr.univer-scr-course>td { grid-column:auto; min-width:0; text-align:start; }
${P} #l4 tr.univer-scr-course>td.univer-scr-blank { display:none; }
${P} #l4 tr.univer-scr-course>td:nth-child(1), ${P} #l4 tr.univer-scr-course>td:nth-child(n+8) { grid-column:1 / -1; }
${P} #l4 tr.univer-scr-course>td:nth-child(2) { grid-column:1 / -1; order:-1; color:var(--u-ink); font-size:var(--u-fs-strong); font-weight:600; line-height:1.35; }
${P} #l4 tr.univer-scr-course>td:nth-child(2)::before { display:none; }
${P} #l4 tr.univer-scr-course>td:nth-child(n+3):nth-child(-n+7) { font-variant-numeric:tabular-nums; }

/* IGPA: buttons that read on their fill, one clear selected period, one row of sections (each on its own surface, so
   neighbouring labels do not run together), full-width dates. */
${P} #l4 a.igpa-button-primary, ${P} #l4 button.igpa-button-primary { display:inline-flex; align-items:center; justify-content:center; min-height:44px; padding:0 18px; border:0; border-radius:10px; background:var(--u-brand-fill,var(--u-brand)); color:#fff; font:500 var(--u-fs-meta)/1.2 var(--u-font); text-decoration:none; }
${P} #l4 .igpa-period { display:grid; grid-template-columns:1fr 1fr; gap:4px; padding:4px; border:0; border-radius:12px; background:var(--u-surface-2); }
${P} #l4 .igpa-period>a { display:flex; align-items:center; justify-content:center; min-height:40px; margin:0; padding:0 10px; border:0; border-radius:9px; background:none; color:var(--u-muted); font:500 var(--u-fs-meta)/1.2 var(--u-font); text-align:center; text-decoration:none; }
${P} #l4 .igpa-year>a { display:inline-flex; align-items:center; justify-content:center; min-width:44px; min-height:44px; border-radius:10px; }
${P} #l4 .igpa-period>a.igpa-selected { background:var(--u-surface); color:var(--u-brand-strong); box-shadow:0 1px 3px rgba(16,40,70,.14); }
${P} #l4 nav.igpa-nav { display:grid; grid-template-columns:auto auto auto; gap:4px; padding:4px; border:0; border-radius:12px; background:var(--u-surface-2); }
${P} #l4 nav.igpa-nav>a { display:flex; align-items:center; justify-content:center; gap:6px; min-width:0; min-height:44px; margin:0; padding:4px 8px; border:0; border-radius:9px; background:var(--u-surface); color:var(--u-muted); font:500 var(--u-fs-meta)/1.25 var(--u-font); text-align:center; text-decoration:none; }
${P} #l4 nav.igpa-nav>a.igpa-tab-active { background:var(--u-brand-fill,var(--u-brand)); color:#fff; }
${P} #l4 nav.igpa-nav>a .igpa-count { min-width:20px; padding:0 6px; border-radius:10px; background:var(--u-surface-2); color:var(--u-brand); font-size:var(--u-fs-caption); }
${P} #l4 nav.igpa-nav>a.igpa-tab-active .igpa-count { background:#fff; color:var(--u-brand-fill,var(--u-brand)); }
${P} #l4 .igpa-ui input[type=date], ${P} #l4 #igpaAchievementForm input[type=date] { display:block; width:100%; max-width:none; min-height:48px; background-image:none; }
${P} #l4 .igpa-filter button[type=submit] { min-height:44px; padding:0 18px; border:1px solid var(--u-brand-line); border-radius:10px; background:var(--u-surface); color:var(--u-brand); font:500 var(--u-fs-meta)/1.2 var(--u-font); }
${P} #l4 .igpa-request-meta { display:flex; flex-wrap:wrap; gap:8px; }
${P} #l4 .igpa-empty { display:flex; flex-direction:column; align-items:center; gap:12px; padding:24px 16px; text-align:center; }
${P} #l4 .igpa-section-heading { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:8px 12px; }

/* Motion only where the app allows it: no still mode, no open keyboard, no reduced-motion request. Layered
   !important outranks the app's own "still" switch, so every transition here sits behind these conditions. */
@media (prefers-reduced-motion:no-preference) {
${M} #l4 :is(button.univer-scr-fold,button.univer-scr-more,button.univer-scr-grades-toggle)::after { transition:transform 220ms var(--u-ease-out); }
${M} #l4 :is(a.univer-scr-action,a.univer-scr-link,button.univer-scr-start,button.univer-scr-calendar,button.univer-scr-fold,button.univer-scr-more,button.univer-scr-zeros-toggle,.univer-scr-primary,nav.igpa-nav>a,.igpa-period>a,a.igpa-button-primary) { transition:transform var(--u-release) var(--u-ease-out),background-color 160ms var(--u-ease-out),color 160ms var(--u-ease-out),box-shadow 160ms var(--u-ease-out); }
${M} #l4 :is(a.univer-scr-action,a.univer-scr-link,button.univer-scr-start,button.univer-scr-calendar,button.univer-scr-fold,button.univer-scr-more,button.univer-scr-zeros-toggle,.univer-scr-primary,nav.igpa-nav>a,.igpa-period>a,a.igpa-button-primary):active:not(:disabled) { transform:scale(.97); transition-duration:var(--u-press); }
}
}
`);
    }

    function apply(lang) {
        language = lang || 'ru';
        if (!capable || document.readyState === 'loading' || !document.body) return;
        var path = location.pathname, route = ROUTES.find(function (r) { return r[0].test(path); });
        if (!route) { release(); return; }
        var root = document.documentElement;
        if (!root.classList.contains('univer-mobile')) return;
        if (!style || !style.isConnected) {
            style = document.createElement('style'); style.id = 'univer-screens-style'; style.textContent = css();
            // First in <head>: the first declared layer wins among !important rules.
            var head = document.head || root; head.insertBefore(style, head.firstChild);
        }
        mark(root, 'univer-scr');
        if (page !== route[1]) { if (page) unmark(root, 'univer-scr-page-' + page); page = route[1]; }
        mark(root, 'univer-scr-page-' + page);
        // A node the site took out loses this module's classes, so putting it back later shows it as the site drew it.
        classes.forEach(function (set, node) {
            if (node.isConnected) return;
            set.forEach(function (name) { node.classList.remove(name); });
            if (!node.getAttribute('class')) node.removeAttribute('class');
            classes.delete(node);
        });
        if (route[2]) route[2]();
        if (page !== 'exams') { forget('exams'); forget('scheduleEmpty'); }
        mainActions();
        // Labels written once follow a language change.
        document.querySelectorAll('#l4 button.univer-scr-more').forEach(function (button) {
            setText(button, button.getAttribute('aria-expanded') === 'true' ? words().less : words().more);
        });
    }
    function release() {
        Object.keys(views).forEach(forget);
        clearInterval(timer); timer = 0;
        listeners.forEach(function (l) { l[0].removeEventListener(l[1], l[2]); }); listeners = [];
        added.forEach(function (node) { node.remove(); }); added = [];
        wraps.slice().forEach(unwrap); wraps = [];
        classes.forEach(function (set, node) { set.forEach(function (name) { node.classList.remove(name); }); if (!node.getAttribute('class')) node.removeAttribute('class'); });
        classes.clear(); fits = new WeakSet();
        style?.remove(); style = null; page = '';
    }
    window.__univerScreensV1 = {apply:apply, release:release};
})();
