/* Reused Android DOM adapter; desktop CSS below changes presentation, not site handlers. */
(function initializeMobile() {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz') return;
    if (window.__univerMobileV1) return;
    var enabled = false, observer = null, queued = false, viewport = null, originalViewport = null;
    var madeViewport = false, menuHome = null, menuTitle = 'Все разделы';
    var infoTitles = {contacts:'Контакты и информация КазНУ', links:'Полезные ссылки'};
    var categories = [], searchLabel = 'Найти раздел…', emptyLabel = 'Разделы не найдены';
    var menuHidden = new WeakMap();
    var closeLabel = 'Закрыть', frameListeners = new WeakSet(), childAdapters = new Set();
    var selectedLanguage = 'ru';
    var nativeChrome = false;
    var headerMoves = [], schedules = new Map(), weekViews = new Map(), upcomingTimer = 0, finishedWeek = null;
    var punctuation = new Map(), usabilityMoves = [], catalogHidden = new WeakMap(), pageMarkers = new Map();
    function labels() {
        return selectedLanguage === 'en' ? {account:'Account', university:'About KazNU', open:'Open', all:'All days', empty:'No classes', files:'Download', sections:'All sections', close:'Close', today:'Today', now:'In progress', next:'Next', done:'Done', byDay:'By day', weekGrid:'Week grid', scheduleView:'Schedule view', weekDay:'Day of week', details:'Class details', days:['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday']} :
            selectedLanguage === 'kz' ? {account:'Аккаунт', university:'ҚазҰУ туралы', open:'Ашу', all:'Барлық күндер', empty:'Сабақ жоқ', files:'Жүктеу', sections:'Барлық бөлімдер', close:'Жабу', today:'Бүгін', now:'Жүріп жатыр', next:'Келесі', done:'Өтті', byDay:'Күн бойынша', weekGrid:'Апта кестесі', scheduleView:'Кесте көрінісі', weekDay:'Апта күні', details:'Сабақ мәліметтері', days:['Дүйсенбі','Сейсенбі','Сәрсенбі','Бейсенбі','Жұма','Сенбі','Жексенбі']} :
            {account:'Аккаунт', university:'О КазНУ', open:'Открыть', all:'Все дни', empty:'Нет занятий', files:'Скачать', sections:'Все разделы', close:'Закрыть', today:'Сегодня', now:'Идёт', next:'Следующая', done:'Прошла', byDay:'По дням', weekGrid:'Сетка недели', scheduleView:'Вид расписания', weekDay:'День недели', details:'Подробно о занятиях', days:['Понедельник','Вторник','Среда','Четверг','Пятница','Суббота','Воскресенье']};
    }
    function shortDays() {
        return selectedLanguage === 'en' ? ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'] : selectedLanguage === 'kz' ? ['Дс','Сс','Ср','Бс','Жм','Сн','Жс'] : ['Пн','Вт','Ср','Чт','Пт','Сб','Вс'];
    }
    function weekDates(option) {
        return (cleanText(option).match(/\d{1,2}\.\d{1,2}\.\d{4}/g) || []).map(function (value) {
            var p = value.split('.'); return new Date(+p[2], +p[1] - 1, +p[0]);
        });
    }
    function dayStart(date, shift) { return new Date(date.getFullYear(), date.getMonth(), date.getDate() + (shift || 0)); }
    // The displayed week from "Неделя N. дд.мм.гггг - дд.мм.гггг"; columns start on its Monday. Undated weeks are taken as the current one.
    function shownWeek(now) {
        var week = document.getElementById('weekSelect'), today = dayStart(now);
        var dates = week?.tagName === 'SELECT' && week.selectedIndex >= 0 ? weekDates(week.options[week.selectedIndex]) : [];
        var first = dates.length >= 2 ? dates[0] : today, last = dates.length >= 2 ? dates[dates.length - 1] : dayStart(today, 6 - (today.getDay() + 6) % 7);
        return {select:dates.length >= 2 ? week : null, first:first, last:last, monday:dayStart(first, -((first.getDay() + 6) % 7)), today:today};
    }
    // Index of today in the displayed week (Monday = 0), or -1 when another week is shown.
    function todayInWeek(now) {
        var week = shownWeek(now);
        if (week.today < week.first || week.today > week.last) return -1;
        return (week.today.getDay() + 6) % 7;
    }
    function agendaLabels() {
        function duration(minutes, units) {
            if (minutes < 60) return minutes + ' ' + units[2];
            if (minutes < 1440) return Math.floor(minutes / 60) + ' ' + units[1] + (minutes % 60 ? ' ' + minutes % 60 + ' ' + units[2] : '');
            var hours = Math.floor(minutes % 1440 / 60);
            return Math.floor(minutes / 1440) + ' ' + units[0] + (hours ? ' ' + hours + ' ' + units[1] : '');
        }
        if (selectedLanguage === 'en') return {
            now:'Class in progress', pause:'Break', gap:'Free window', first:'Until the first class', over:'Classes are over for today',
            dayOff:'Day off today', free:'No classes today', shown:'First class of this week', weekDone:'No more classes this week',
            last:'Last class today', then:'Then', showDay:'Show day', nextWeek:'Next week', region:'Schedule summary',
            left:function (m) { return duration(m, ['d','h','min']) + ' left'; }, until:function (m) { return 'in ' + duration(m, ['d','h','min']); },
            count:function (n) { return n + (n === 1 ? ' class' : ' classes'); },
            today:function (n, range) { return 'Today: ' + this.count(n) + ', ' + range; }, remain:function (n, end) { return this.count(n) + ' left, until ' + end; },
            when:function (shift, day, time) { return (shift === 0 ? 'today' : shift === 1 ? 'tomorrow' : labels().days[day]) + ' at ' + time; }};
        if (selectedLanguage === 'kz') return {
            now:'Сабақ жүріп жатыр', pause:'Үзіліс', gap:'Бос уақыт', first:'Бірінші сабаққа дейін', over:'Бүгінгі сабақтар аяқталды',
            dayOff:'Бүгін демалыс күні', free:'Бүгін сабақ жоқ', shown:'Осы аптадағы бірінші сабақ', weekDone:'Бұл аптада сабақ қалмады',
            last:'Бүгінгі соңғы сабақ', then:'Содан кейін', showDay:'Күнді көрсету', nextWeek:'Келесі апта', region:'Кесте қорытындысы',
            left:function (m) { return duration(m, ['күн','сағ','мин']) + ' қалды'; }, until:function (m) { return duration(m, ['күн','сағ','мин']) + ' кейін'; },
            count:function (n) { return n + ' сабақ'; },
            today:function (n, range) { return 'Бүгін ' + this.count(n) + ', ' + range; }, remain:function (n, end) { return this.count(n) + ' қалды, ' + end + ' дейін'; },
            when:function (shift, day, time) { return (shift === 0 ? 'бүгін ' : shift === 1 ? 'ертең ' : labels().days[day] + ', ') + time; }};
        return {
            now:'Идёт пара', pause:'Перемена', gap:'Окно', first:'До первой пары', over:'Пары на сегодня закончились',
            dayOff:'Сегодня выходной', free:'Сегодня пар нет', shown:'Первая пара этой недели', weekDone:'На этой неделе пар больше нет',
            last:'Это последняя пара сегодня', then:'Затем', showDay:'Показать день', nextWeek:'Следующая неделя', region:'Сводка расписания',
            left:function (m) { return 'ещё ' + duration(m, ['д','ч','мин']); }, until:function (m) { return 'через ' + duration(m, ['д','ч','мин']); },
            count:function (n) { var t = n % 10, h = n % 100; return n + (t === 1 && h !== 11 ? ' пара' : t >= 2 && t <= 4 && (h < 12 || h > 14) ? ' пары' : ' пар'); },
            today:function (n, range) { return 'Сегодня ' + this.count(n) + ', ' + range; }, remain:function (n, end) { return 'Осталось ' + this.count(n) + ', до ' + end; },
            when:function (shift, day, time) { return (shift === 0 ? 'сегодня' : shift === 1 ? 'завтра' : ['в понедельник','во вторник','в среду','в четверг','в пятницу','в субботу','в воскресенье'][day]) + ' в ' + time; }};
    }
    // The phone layout's stylesheet lives in univer-mobile-css.js, loaded just before this file.
    var css = window.__univerMobileCssV1 || '';
    function moveHeader(node, destination) {
        var home = document.createComment('univer-original-header');
        node.parentNode.insertBefore(home, node); destination.appendChild(node);
        headerMoves.push({node:node, home:home});
    }
    function adaptHeader() {
        var text = labels(), header = document.querySelector('#l1 .header');
        if (header && !header.querySelector('.univer-app-header')) {
            // E-services nests all three flags in one span with line breaks.
            // Move the actual links/images, retaining their original homes.
            var flagItems=Array.from(header.querySelectorAll('img')).filter(function(img){return /\/(kz|ru|en)\.png$/i.test(img.getAttribute('src')||'');}).map(function(img){return img.closest('a')||img;});
            var nodes = Array.from(header.childNodes);
            var bar = document.createElement('div'); bar.className = 'univer-app-header';
            var info = document.createElement('details'); info.className = 'univer-app-university';
            var title = document.createElement('summary'); title.textContent = text.university;
            var content = document.createElement('div'); info.append(title, content);
            var flags = document.createElement('div'); flags.className = 'univer-app-flags';
            flags.setAttribute('role', 'group');
            bar.append(info, flags); header.appendChild(bar);
            nodes.forEach(function (node) {
                moveHeader(node, flagItems.includes(node) ? flags : content);
            });
            flagItems.forEach(function(flag){if(flag.parentElement!==flags)moveHeader(flag,flags);});
        }
        // Some server pages append the remaining flags after the first pass.
        var flagBar=header?.querySelector('.univer-app-flags');
        if(flagBar)Array.from(header.querySelectorAll('img')).filter(function(img){return /\/(kz|ru|en)\.png$/i.test(img.getAttribute('src')||'');}).forEach(function(img){
            var item=img.closest('a')||img;if(item.parentElement!==flagBar)moveHeader(item,flagBar);
        });
        header?.querySelector('.univer-app-flags')?.setAttribute('aria-label', selectedLanguage === 'en' ? 'Language' : selectedLanguage === 'kz' ? 'Тіл' : 'Язык');
        var account = document.querySelector('#l2 .links');
        if (account?.querySelector('a[href^="/user/pass"]') && !account.querySelector('.univer-app-account')) {
            var children = Array.from(account.childNodes);
            var details = document.createElement('details'); details.className = 'univer-app-account';
            var summary = document.createElement('summary'); summary.textContent = text.account;
            var body = document.createElement('div'); details.append(summary, body); account.appendChild(details);
            children.forEach(function (node) { moveHeader(node, body); });
        }
        [['.univer-app-university>summary',text.university],['.univer-app-account>summary',text.account]].forEach(function (pair) {
            var node = document.querySelector(pair[0]); if (node && node.textContent !== pair[1]) node.textContent = pair[1];
        });
        unifyHeader(header);
    }
    // Desktop: one compact bar instead of three stacked rows. Flags stay inside .header, where
    // language detection and the trusted flag-click listener look for them; every move is restorable.
    function unifyHeader(header) {
        var bar = header?.querySelector('.univer-app-header');
        // Android keeps its native app bar; the site header stays hidden there and the tab row remains in the page.
        if (!bar || nativeChrome) return;
        // Late server nodes (line breaks, separators) would add an empty line under the bar.
        var info = document.querySelector('.univer-app-university>div');
        if (info) Array.from(header.childNodes).forEach(function (node) {
            if (node !== bar && node.nodeType !== 8 && !(node.nodeType === 3 && !node.data.trim())) moveHeader(node, info);
        });
        var text = labels(), brand = bar.querySelector('.univer-app-brand'), tabs = bar.querySelector('.univer-app-tabs');
        var actions = bar.querySelector('.univer-app-actions');
        if (!brand) {
            brand = document.createElement('div'); brand.className = 'univer-app-brand';
            tabs = document.createElement('nav'); tabs.className = 'univer-app-tabs';
            actions = document.createElement('div'); actions.className = 'univer-app-actions';
            var sections = document.createElement('button'); sections.type = 'button'; sections.className = 'univer-app-sections';
            sections.setAttribute('aria-expanded', 'false');
            sections.addEventListener('click', function (event) { event.stopPropagation(); toggleMenu(); });
            bar.prepend(brand, tabs); bar.append(actions); actions.appendChild(sections);
        }
        var flags = bar.querySelector('.univer-app-flags');
        if (flags && flags.parentElement !== actions) actions.appendChild(flags);
        var logo = document.querySelector('#l2 #logo');
        if (logo) { moveHeader(logo, brand); logo.closest('#l2')?.classList.add('univer-app-header-row'); }
        var links = document.querySelector('#l2 .links');
        if (links && (links.querySelector('.univer-app-account') || links.querySelector('a[href]'))) {
            var row = links.closest('#l2'); moveHeader(links, actions); row?.classList.add('univer-app-header-row');
        }
        var tabRow = document.querySelector('#l3');
        if (tabRow) {
            var moved = false;
            tabRow.querySelectorAll('td.center>a[href]').forEach(function (tab) { moveHeader(tab, tabs); moved = true; });
            // The login page keeps an empty decorative tab strip.
            if (moved || (!cleanText(tabRow) && !tabRow.querySelector('a[href],input,select,textarea,button,img,iframe'))) tabRow.classList.add('univer-app-header-row');
        }
        tabs.setAttribute('aria-label', selectedLanguage === 'en' ? 'Main sections' : selectedLanguage === 'kz' ? 'Негізгі бөлімдер' : 'Основные разделы');
        var university = bar.querySelector('.univer-app-university'), footer = document.querySelector('#l5 td.center');
        if (university && footer) moveHeader(university, footer);
        var button = bar.querySelector('.univer-app-sections');
        if (button && button.textContent !== text.sections) button.textContent = text.sections;
        if (button) button.hidden = !document.getElementById('tools');
        document.documentElement.classList.add('univer-app-unified');
    }
    function toggleMenu(force) {
        var menu = document.querySelector('.univer-app-menu');
        if (!menu) return;
        var open = typeof force === 'boolean' ? force : !menu.open;
        if (!open) { closePopup(menu); return; }
        menu.open = true;
        menu.querySelector('input[type=search]')?.focus({preventScroll:true});
    }
    // Frequent menus close immediately, so Escape never leaves invisible focusable content behind.
    function closePopup(popup) {
        if (!popup?.open) return;
        var hadFocus = popup.contains(document.activeElement);
        // Only a floating popup leaves as a fading copy; an in-page disclosure simply closes.
        if (document.documentElement.classList.contains('univer-app-unified')) window.__univerMotionUI?.exit(popup);
        popup.open = false;
        if (hadFocus) {
            var menu = popup.classList.contains('univer-app-menu');
            // Under the native app bar there is no header "All sections" button: focus goes to the menu's own title, as with Back.
            var sections = menu && !nativeChrome ? document.querySelector('.univer-app-sections') : null;
            var trigger = !menu ? popup.querySelector('summary') : sections && sections.getClientRects().length ? sections : popup.querySelector(':scope>summary');
            trigger?.focus({preventScroll:true});
        }
    }
    function syncMenuButton() {
        // "toggle" is queued; it can arrive after Original mode removed the adapter's nodes.
        if (!enabled) return;
        var menu = document.querySelector('.univer-app-menu'), button = document.querySelector('.univer-app-sections');
        if (button && button.getAttribute('aria-expanded') !== String(!!menu?.open)) button.setAttribute('aria-expanded', String(!!menu?.open));
    }
    function dismissMenu(event) {
        // Header popups close like ordinary menus: Escape or a click elsewhere.
        [document.querySelector('.univer-app-menu'), document.querySelector('.univer-app-actions .univer-app-account')].forEach(function (popup) {
            if (!popup?.open) return;
            // In the phone layout the menu sits in the page: a tap elsewhere must not collapse it and shift
            // the content under the finger. Escape and Android Back still close it.
            if (event.type !== 'keydown' && !document.documentElement.classList.contains('univer-app-unified')) return;
            var outside = event.type === 'keydown' ? event.key === 'Escape' :
                !popup.contains(event.target) && !event.target.closest?.('.univer-app-sections');
            if (outside) closePopup(popup);
        });
    }
    function ownRows(table) { return Array.from(table.rows).filter(function (r) { return r.closest('table') === table; }); }
    function cleanText(node) { return (node?.textContent || '').replace(/\s+/g, ' ').trim(); }
    // Schedule and УМКД panels become cards; their links to other study pages read as page links.
    function markStudyLinks() {
        document.querySelectorAll('#l4 table.mt').forEach(function (table) {
            if (table.closest('#tools,.univer-app-info,form')) return;
            table.classList.add('univer-app-study-panel');
            ownRows(table).forEach(function (row) { if (!cleanText(row) && !row.querySelector('input,select,img')) row.classList.add('univer-app-study-empty'); });
        });
        document.querySelectorAll('#l4 a[href]').forEach(function (a) {
            if (!a.closest('#tools') && /^\/student\/(myschedule|umkd|interactive\/syllabus)\//.test(new URL(a.href, location.href).pathname) && !a.classList.contains('downLoad') && !a.classList.contains('univer-app-lesson-name') && !a.closest('.univer-app-syl-panel,.univer-app-syl-task'))
                a.classList.add('univer-app-page-link');
        });
    }
    // The site's week list has no label; the adapter names it and removes only its own name in Original.
    function labelWeekSelect() {
        var week = document.getElementById('weekSelect');
        if (week?.tagName !== 'SELECT') return;
        (week.closest('.ts') || week.parentElement).classList.add('univer-app-schedule-controls');
        var weekName = selectedLanguage === 'en' ? 'Week' : selectedLanguage === 'kz' ? 'Апта' : 'Неделя';
        if (!week.hasAttribute('aria-label') || week.hasAttribute('data-univer-label')) {
            if (week.getAttribute('aria-label') !== weekName) week.setAttribute('aria-label', weekName);
            week.setAttribute('data-univer-label', '');
        }
        addWeekSteps(week);
    }
    // The option of the week before (-1) or after (1) the selected one, by its dates and, for undated options, by list order; -1 at either end.
    function neighbourWeek(select, direction) {
        var options = Array.from(select.options), at = select.selectedIndex;
        if (at < 0) return -1;
        var from = weekDates(options[at])[0], found = -1, best = null;
        if (!from) return at + direction >= 0 && at + direction < options.length ? at + direction : -1;
        options.forEach(function (option, i) {
            var date = weekDates(option)[0]; if (!date) return;
            if (direction > 0 ? date > from && (!best || date < best) : date < from && (!best || date > best)) { best = date; found = i; }
        });
        return found;
    }
    // Previous and next week buttons around the site's week list; each runs the list's own change handler.
    function addWeekSteps(week) {
        weekViews.forEach(function (view, select) { if (!select.isConnected) { select.removeEventListener('change', view.update); view.nav.remove(); weekViews.delete(select); } });
        var names = selectedLanguage === 'en' ? ['Previous week', 'Next week'] : selectedLanguage === 'kz' ? ['Алдыңғы апта', 'Келесі апта'] : ['Предыдущая неделя', 'Следующая неделя'];
        var view = weekViews.get(week);
        if (!view) {
            var nav = document.createElement('div'); nav.className = 'univer-app-week-nav';
            var step = function (direction, glyph) {
                var button = document.createElement('button'); button.type = 'button'; button.className = 'univer-app-week-step'; button.textContent = glyph;
                button.addEventListener('click', function () {
                    var at = neighbourWeek(week, direction); if (at < 0) return;
                    week.selectedIndex = at; week.dispatchEvent(new Event('change', {bubbles:true}));
                });
                return button;
            };
            var back = step(-1, '‹'), forward = step(1, '›');
            var update = function () {
                var left = neighbourWeek(week, -1) < 0, right = neighbourWeek(week, 1) < 0, current = view.names;
                if (back.disabled !== left) back.disabled = left;
                if (forward.disabled !== right) forward.disabled = right;
                [[back, current[0]], [forward, current[1]]].forEach(function (pair) {
                    if (pair[0].getAttribute('aria-label') !== pair[1]) { pair[0].setAttribute('aria-label', pair[1]); pair[0].title = pair[1]; }
                });
            };
            week.before(nav); nav.append(back, week, forward);
            week.addEventListener('change', update);
            view = {nav:nav, update:update, names:names}; weekViews.set(week, view);
        }
        view.names = names; view.update();
    }
    // A УМКД file table: each file row's values are labelled with their column, and the row gets its material actions.
    function markMaterials(table, rows, header) {
        table.classList.add('univer-app-materials');
        wrapTable(table);
        rows.filter(function (r) { return r.matches('tr.file'); }).forEach(function (row) {
            Array.from(row.cells).forEach(function (cell, i) {
                if (i > 1 && cleanText(header.cells[i]) && !cell.hasAttribute('data-univer-field'))
                    cell.setAttribute('data-univer-field', cleanText(header.cells[i]));
            });
            window.__univerMaterialsV1?.apply(row, selectedLanguage);
        });
    }
    // A УМКД course list: an "Open" button per course runs the site's own dblclick navigation.
    function markCourses(table, rows) {
        table.classList.add('univer-app-courses');
        wrapTable(table);
        var open = labels().open;
        rows.filter(function (r) { return r.matches('tr.link[id]'); }).forEach(function (row) {
            var button = row.querySelector('.univer-app-course-open');
            if (!button) {
                button = document.createElement('button'); button.type = 'button'; button.className = 'univer-app-course-open';
                button.addEventListener('click', function (event) {
                    event.preventDefault(); event.stopPropagation();
                    // The observed site attaches its navigation to jQuery's dblclick handler.
                    row.dispatchEvent(new MouseEvent('dblclick', {bubbles:true, cancelable:true, view:window}));
                });
                row.cells[row.cells.length - 1].appendChild(button);
            }
            if (button.textContent !== open) button.textContent = open;
            // The compact card shows a chevron; the spoken name keeps the course it opens.
            var openName = open + ': ' + cleanText(row.cells[1] || row);
            if (button.getAttribute('aria-label') !== openName) button.setAttribute('aria-label', openName);
            // The type column becomes an icon beside the course name; its words stay for screen readers.
            var kindText = cleanText(row.cells[2]), kind = /необяз|элект|elective|optional|таңдау/i.test(kindText) ? 'elective' : /обяз|міндетті|required|compulsory/i.test(kindText) ? 'required' : '';
            if (kind && row.dataset.univerCourseKind !== kind) row.dataset.univerCourseKind = kind;
        });
    }
    function adaptUmkd() {
        document.querySelectorAll('#l4 .ct').forEach(function (cell) {
            if (cell.querySelectorAll(':scope>a.univer-app-page-link').length < 2) return;
            cell.classList.add('univer-app-breadcrumb');
            Array.from(cell.childNodes).forEach(function (node) {
                if (node.nodeType === 3 && /^[\s\\/]+$/.test(node.data) && /[\\/]/.test(node.data)) rewriteText(node, '');
            });
        });
        document.querySelectorAll('#l4 table.inner').forEach(function (table) {
            if (table.closest('#tools')) return;
            var rows = ownRows(table), header = rows.find(function (r) { return r.cells.length >= 3 && r.cells[0].tagName === 'TH'; });
            if (!header) return;
            if (table.querySelector('tr.file a.downLoad[href^="/student/umkd/get/"]')) markMaterials(table, rows, header);
            else if (table.querySelector('tr.link[id]') && header.cells.length === 3) markCourses(table, rows);
        });
    }
    function adaptStudyPages() {
        if (/^\/student\/(myschedule|umkd)(\/|$)/.test(location.pathname)) markStudyLinks();
        labelWeekSelect();
        if (/^\/student\/umkd(\/|$)/.test(location.pathname)) adaptUmkd();
    }
    function lessonPeriodDates(value) {
        return (value || '').match(/\b(?:\d{1,2}\.\d{1,2}\.\d{4}|\d{4}-\d{2}-\d{2})\b/g) || [];
    }
    function setText(node, text) { if (node.textContent !== text) node.textContent = text; }
    function setHidden(node, hidden) { if (node.hidden !== hidden) node.hidden = hidden; }
    function slots(lessons) { return lessons.filter(function (l, i) { return !lessons.slice(0, i).some(function (o) { return o.from === l.from; }); }).length; }
    function minutesUntil(from, to) { return Math.max(1, Math.ceil((to - from) / 60000)); }
    function clock(minutes) { return String(Math.floor(minutes / 60)).padStart(2, '0') + ':' + String(minutes % 60).padStart(2, '0'); }
    // The option of the week right after the displayed one, found by its dates rather than by list order.
    function followingWeek(week) {
        if (!week.select) return -1;
        var found = -1, best = null;
        Array.from(week.select.options).forEach(function (option, i) {
            var dates = weekDates(option);
            if (dates.length >= 2 && dates[0] > week.last && (!best || dates[0] < best)) { best = dates[0]; found = i; }
        });
        return found;
    }
    // Each class card says whether it is in progress, the next one or (on [doneDay], today in the current week) over,
    // with the time left or still to wait.
    function markLessons(lessons, live, next, doneDay, at) {
        var words = labels(), L = agendaLabels();
        lessons.forEach(function (lesson) {
            var state = live.indexOf(lesson) >= 0 ? 'now' : next.indexOf(lesson) >= 0 ? 'next' : lesson.day === doneDay && lesson.to <= at ? 'done' : '';
            var text = state === 'now' ? words.now + ' · ' + L.left(minutesUntil(at, lesson.to)) : state === 'next' ? words.next + ' · ' + L.until(minutesUntil(at, lesson.from)) : state ? words.done : '';
            if (lesson.state !== state) {
                lesson.state = state;
                ['now','next','done'].forEach(function (name) { lesson.card.classList.toggle('univer-app-lesson-' + name, state === name); });
            }
            var badge = lesson.card.querySelector('.univer-app-lesson-badge');
            if (!state) { if (badge) badge.remove(); return; }
            if (!badge) { badge = document.createElement('span'); badge.className = 'univer-app-lesson-badge'; lesson.card.querySelector('.univer-app-subject').appendChild(badge); }
            setText(badge, text);
        });
    }
    // Statuses follow the clock (in progress, next, finished); the summary card says what is on now.
    // Only adapter-made nodes change, and only when their text or state differs, so a steady page makes no mutations.
    function markUpcoming(record, now) {
        var week = shownWeek(now), at = now.getTime(), today = todayInWeek(now);
        var current = week.today >= week.first && week.today <= week.last;
        // A later week only says what comes first in it: the current week may still hold classes.
        var ahead = week.first > week.today && week.monday - week.today < 7 * 86400000;
        record.lessons.forEach(function (lesson) {
            var date = dayStart(week.monday, lesson.day).getTime();
            lesson.from = date + lesson.start * 60000; lesson.to = date + lesson.end * 60000;
        });
        var live = record.lessons.filter(function (l) { return l.from <= at && at < l.to; });
        var upcoming = record.lessons.filter(function (l) { return l.from > at; }).sort(function (a, b) { return a.from - b.from; });
        var next = upcoming.length && (current || ahead) ? upcoming.filter(function (l) { return l.from === upcoming[0].from; }) : [];
        if (!current) live = [];
        markLessons(record.lessons, live, next, current && today, at);
        var card = record.card;
        if (!current && !ahead) { setHidden(card.root, true); return; }
        setHidden(card.root, false);
        var todays = record.lessons.filter(function (l) { return l.day === today; }).sort(function (a, b) { return a.from - b.from; });
        var shown = live.length ? live : next, status = agendaStatus(week, at, today, current, live, next, todays);
        showAgenda(card, status, agendaAction(record, week, today, current, shown, status));
    }
    // What the summary card says about now: the class in progress, a break or window before today's next class,
    // the first class of a later week, or that today's classes are over.
    function agendaStatus(week, at, today, current, live, next, todays) {
        var L = agendaLabels(), lead = (live.length ? live : next)[0], status = {state:'', count:'', note:'', progress:-1};
        var lastEnd = todays.length ? Math.max.apply(null, todays.map(function (l) { return l.end; })) : 0;
        if (live.length) {
            status.state = 'now'; status.count = L.left(minutesUntil(at, lead.to));
            status.progress = Math.round(Math.min(1, (at - lead.from) / Math.max(1, lead.to - lead.from)) * 100);
            var then = next.filter(function (l) { return l.day === today; });
            status.note = then.length ? L.then + ': ' + then[0].subject + ', ' + clock(then[0].start) : L.last;
        } else if (!current) {
            // After the finished current week was seen today, the first class here is the real next one.
            var known = finishedWeek && finishedWeek.today === week.today.getTime() && finishedWeek.last < week.first.getTime() && week.first - finishedWeek.last <= 7 * 86400000;
            status.state = known ? finishedWeek.state : 'ahead'; status.count = next.length ? L.until(minutesUntil(at, lead.from)) : '';
        } else if (next.length && lead.day === today) {
            var ended = todays.filter(function (l) { return l.to <= at; });
            var gap = ended.length ? (lead.from - Math.max.apply(null, ended.map(function (l) { return l.to; }))) / 60000 : 0;
            status.state = !ended.length ? 'first' : gap > 30 ? 'gap' : 'pause'; status.count = L.until(minutesUntil(at, lead.from));
            status.note = ended.length ? L.remain(slots(todays.filter(function (l) { return l.from > at; })), clock(lastEnd)) : L.today(slots(todays), clock(todays[0].start) + '–' + clock(lastEnd));
        } else {
            status.state = todays.length ? 'over' : today >= 5 ? 'dayOff' : 'free';
            if (next.length) status.count = L.until(minutesUntil(at, lead.from));
            else finishedWeek = {today:week.today.getTime(), last:week.last.getTime(), state:status.state};
        }
        return status;
    }
    // The card's subject and time line, and what its button does: show the class's day, or load the next week.
    function agendaAction(record, week, today, current, shown, status) {
        var L = agendaLabels(), card = record.card, lead = shown[0];
        if (lead) {
            setText(card.subject, shown.map(function (l) { return l.subject; }).filter(function (v, i, a) { return a.indexOf(v) === i; }).join(' / '));
            var shift = Math.round((dayStart(new Date(lead.from)) - week.today) / 86400000);
            setText(card.meta, (status.state === 'now' || shift === 0 ? clock(lead.start) + '–' + clock(lead.end) : L.when(shift, lead.day, clock(lead.start))) + (lead.details ? ' · ' + lead.details : ''));
            if (status.state !== 'now' && lead.day !== today && record.selected !== -1 && record.selected !== lead.day)
                return {text:L.showDay, run:function () { record.select(lead.day); }};
            return null;
        }
        setText(card.subject, L.weekDone); setText(card.meta, '');
        var following = current ? followingWeek(week) : -1;
        if (following < 0) return null;
        // The site's own week selector loads the week through its change handler.
        return {text:L.nextWeek, run:function () { week.select.selectedIndex = following; week.select.dispatchEvent(new Event('change', {bubbles:true})); }};
    }
    function showAgenda(card, status, action) {
        var L = agendaLabels(), state = status.state, progress = status.progress;
        var title = state === 'now' ? L.now : state === 'ahead' ? L.shown : state === 'first' ? L.first : L[state];
        if (card.root.dataset.state !== state) card.root.dataset.state = state;
        setText(card.title, title); setText(card.count, status.count); setText(card.note, status.note); setText(card.action, action ? action.text : '');
        setHidden(card.meta, !card.meta.textContent); setHidden(card.count, !status.count); setHidden(card.note, !status.note); setHidden(card.action, !action);
        setHidden(card.progress, progress < 0);
        if (progress >= 0 && card.bar.style.width !== progress + '%') card.bar.style.width = progress + '%';
        if (progress >= 0 && card.progress.getAttribute('aria-valuenow') !== String(progress)) card.progress.setAttribute('aria-valuenow', String(progress));
        card.run = action ? action.run : null;
    }
    function tickUpcoming() {
        upcomingTimer = setTimeout(function () { upcomingTimer = 0; if (enabled && schedules.size) { schedule(); tickUpcoming(); } }, 60500 - Date.now() % 60000);
    }
    function agendaCard() {
        var root = document.createElement('section'); root.className = 'univer-app-now'; root.setAttribute('aria-label', agendaLabels().region);
        var head = document.createElement('div'); head.className = 'univer-app-now-head';
        var title = document.createElement('span'); title.className = 'univer-app-now-title';
        var count = document.createElement('span'); count.className = 'univer-app-now-count';
        head.append(title, count);
        var subject = document.createElement('p'); subject.className = 'univer-app-now-subject';
        var meta = document.createElement('p'); meta.className = 'univer-app-now-meta';
        var progress = document.createElement('div'); progress.className = 'univer-app-now-progress';
        progress.setAttribute('role', 'progressbar'); progress.setAttribute('aria-valuemin', '0'); progress.setAttribute('aria-valuemax', '100'); progress.setAttribute('aria-label', agendaLabels().now);
        var bar = document.createElement('span'); progress.appendChild(bar);
        var foot = document.createElement('div'); foot.className = 'univer-app-now-foot';
        var note = document.createElement('span'); note.className = 'univer-app-now-note';
        var action = document.createElement('button'); action.type = 'button'; action.className = 'univer-app-now-action';
        foot.append(note, action);
        root.append(head, subject, meta, progress, foot);
        var card = {root:root, title:title, count:count, subject:subject, meta:meta, progress:progress, bar:bar, note:note, action:action, run:null};
        action.addEventListener('click', function () { if (card.run) card.run(); });
        return card;
    }
    var DETAILS_KEY = 'univer.mobile.schedule-details.v1';
    // A rowspan/colspan-aware logical grid keeps each class attached to its actual day and time rows.
    function slotGrid(rows) {
        var grid = [];
        rows.forEach(function (row, r) {
            grid[r] = grid[r] || []; var column = 0;
            Array.from(row.cells).forEach(function (cell) {
                while (grid[r][column]) column++;
                for (var y = 0; y < cell.rowSpan; y++) {
                    grid[r + y] = grid[r + y] || [];
                    for (var x = 0; x < cell.colSpan; x++) grid[r + y][column + x] = {cell:cell, start:r, end:r + cell.rowSpan - 1};
                }
                column += cell.colSpan;
            });
        });
        return grid;
    }
    // The site draws times as rotated SVG text that disappears in a narrow column. A readable copy is set
    // before the signature is taken, so the agenda is not rebuilt for it.
    function copyScheduleTimes(table) {
        table.querySelectorAll('td.time').forEach(function (cell) {
            var marks = cleanText(cell).match(/\d{1,2}:\d{2}/g) || [], time = marks.length ? marks[0] + (marks.length > 1 ? '–' + marks[marks.length - 1] : '') : '';
            if (time && cell.dataset.univerTime !== time) cell.dataset.univerTime = time;
        });
    }
    // Subject, type, teacher, room and faculty of one class, read as in the Бакалавр summary. Desktop hover
    // text is not accessible on a phone: a full date range there becomes the course dates.
    function lessonInfo(group) {
        var info = window.__univerLesson.read(group);
        if (info.dates.length !== 2) [group].concat(Array.from(group.querySelectorAll('[title]'))).some(function (node) {
            var found = lessonPeriodDates(node.getAttribute('title'));
            if (found.length === 2) info.dates = found;
            return found.length === 2;
        });
        return info;
    }
    // The class's own links repeated in its card; a tap runs the original link with the site's handlers.
    function copyLessonLinks(group, card) {
        group.querySelectorAll('a[href]').forEach(function (original) {
            var link = document.createElement('a'); link.textContent = cleanText(original); link.href = original.href;
            link.className = 'univer-app-page-link'; link.addEventListener('click', function (e) { e.preventDefault(); original.click(); });
            card.querySelector('.univer-app-lesson-main').appendChild(link);
        });
    }
    // The class cards of one weekday; classes with clock times also feed the "Now / Next" summary.
    function dayLessons(grid, dayIndex, section, record) {
        var count = 0;
        grid.slice(1).forEach(function (line, i) {
            var slot = line[dayIndex + 1]; if (!slot || slot.start !== i + 1 || !cleanText(slot.cell)) return;
            var start = cleanText(grid[slot.start]?.[0]?.cell).match(/\d{1,2}:\d{2}/g) || [];
            var end = cleanText(grid[Math.min(slot.end, grid.length - 1)]?.[0]?.cell).match(/\d{1,2}:\d{2}/g) || [];
            var timeText = start.length && end.length ? start[0] + '–' + end[end.length - 1] : cleanText(line[0]?.cell);
            var from = (start[0] || '').split(':'), to = (end[end.length - 1] || '').split(':');
            var groups = Array.from(slot.cell.querySelectorAll('.groups>div'));
            (groups.length ? groups : [slot.cell]).forEach(function (group) {
                if (!cleanText(group) || group.hidden || getComputedStyle(group).display === 'none') return;
                var info = lessonInfo(group), card = window.__univerLesson.card(info, timeText, selectedLanguage);
                copyLessonLinks(group, card);
                section.appendChild(card); count++;
                record.cards.push({card:card, main:card.querySelector('.univer-app-lesson-main'), subject:info.subject, type:info.type,
                    teacher:info.teacher, day:dayIndex, order:record.cards.length, time:start[0] || timeText});
                if (from.length === 2 && to.length === 2)
                    record.lessons.push({card:card, day:dayIndex, start:+from[0] * 60 + +from[1], end:+to[0] * 60 + +to[1], state:'', subject:info.subject || '', details:window.__univerLesson.summary(info, selectedLanguage)});
            });
        });
        return count;
    }
    // One weekday: its heading with the span of its classes, then the class cards or "No classes".
    function daySection(grid, dayIndex, name, record) {
        var section = document.createElement('section'), title = document.createElement('h3');
        section.className = 'univer-app-day'; title.textContent = name; section.appendChild(title);
        var count = dayLessons(grid, dayIndex, section, record), times = record.lessons.filter(function (l) { return l.day === dayIndex; });
        if (times.length) {
            var range = document.createElement('span'); range.className = 'univer-app-day-range';
            range.textContent = clock(Math.min.apply(null, times.map(function (l) { return l.start; }))) + '–' + clock(Math.max.apply(null, times.map(function (l) { return l.end; })));
            title.appendChild(range);
        }
        if (!count) { var empty = document.createElement('p'); empty.className = 'univer-app-no-classes'; empty.textContent = labels().empty; section.appendChild(empty); }
        return {section:section, count:count};
    }
    // Arrow keys, Home and End move between the day buttons and choose that day, as in a tab list.
    function roveDays(tabs, buttons) {
        tabs.addEventListener('keydown', function (e) {
            var at = buttons.indexOf(document.activeElement), next;
            if (at < 0) return;
            if (e.key === 'ArrowRight') next = (at + 1) % buttons.length;
            else if (e.key === 'ArrowLeft') next = (at + buttons.length - 1) % buttons.length;
            else if (e.key === 'Home') next = 0; else if (e.key === 'End') next = buttons.length - 1; else return;
            e.preventDefault(); buttons[next].focus(); buttons[next].click();
        });
    }
    // "Class details" adds course dates and notes to every card; the choice outlives the page.
    function detailSwitch(view, record) {
        var label = document.createElement('label'), checkbox = document.createElement('input'), caption = document.createElement('span');
        label.className = 'univer-app-schedule-mode'; checkbox.type = 'checkbox'; checkbox.checked = record.detailed; caption.textContent = labels().details;
        label.append(checkbox, caption);
        view.classList.toggle('univer-app-schedule-detailed', record.detailed);
        checkbox.addEventListener('change', function () {
            record.detailed = checkbox.checked; view.classList.toggle('univer-app-schedule-detailed', checkbox.checked);
            try { sessionStorage.setItem(DETAILS_KEY, String(checkbox.checked)); } catch (_) {}
        });
        return label;
    }
    // Keeps the chosen day fully inside the scrolling row, also after the presentation layer resizes it;
    // only the row scrolls, never the page.
    function revealDay(tabs, chosen) {
        var reveal = function () {
            if (!chosen.isConnected) return;
            var bounds = tabs.getBoundingClientRect(), target = chosen.getBoundingClientRect(), inset = 16;
            if (target.right > bounds.right - inset) tabs.scrollLeft += target.right - bounds.right + inset;
            else if (target.left < bounds.left + inset) tabs.scrollLeft += target.left - bounds.left - inset;
        };
        reveal(); if (typeof requestAnimationFrame === 'function') requestAnimationFrame(reveal);
    }
    // The agenda of one site week grid: a summary card, day buttons, a section of class cards per day and the
    // "By day / Week grid" switch. It replaces the previous agenda of the same table and keeps the user's choices.
    function buildSchedule(table, header, rows, previous, signature, today) {
        var words = labels(), grid = slotGrid(rows), sections = [], buttons = [];
        var view = document.createElement('section'); view.className = 'univer-app-schedule';
        var heading = cleanText(document.querySelector('#l4 tr.top>td.ct'));
        if (/(семестр|semester)/i.test(heading)) {
            var context = document.createElement('p'); context.className = 'univer-app-schedule-context';
            context.textContent = heading.replace(/^(Расписание|Schedule|Сабақ кестесі)\s*[:—–-]?\s*/i, ''); view.appendChild(context);
        }
        var modes = document.createElement('div'), dayMode = document.createElement('button'), gridMode = document.createElement('button');
        modes.className = 'univer-app-schedule-modes'; modes.setAttribute('role', 'group'); modes.setAttribute('aria-label', words.scheduleView);
        dayMode.type = gridMode.type = 'button'; dayMode.textContent = words.byDay; gridMode.textContent = words.weekGrid;
        modes.append(dayMode, gridMode); view.appendChild(modes);
        var tabs = document.createElement('div'); tabs.className = 'univer-app-days'; tabs.setAttribute('role', 'group'); tabs.setAttribute('aria-label', words.weekDay);
        view.appendChild(tabs);
        var detailed = previous?.detailed || false;
        if (!previous) { try { detailed = sessionStorage.getItem(DETAILS_KEY) === 'true'; } catch (_) {} }
        var record = {view:view, signature:signature, selected:previous ? previous.selected : -1, detailed:detailed, mode:previous?.mode || 'days', lessons:[], cards:[], card:agendaCard(), select:select};
        tabs.before(record.card.root);
        view.appendChild(detailSwitch(view, record));
        function select(index, event) {
            var previousIndex = record.selected;
            if (previousIndex !== index) sections.forEach(function (section) { window.__univerMotionUI?.stop(section); });
            record.selected = index;
            view.dataset.allDays = String(index === -1);
            sections.forEach(function (section, i) { section.hidden = index !== -1 && index !== i; });
            buttons.forEach(function (button, i) { button.setAttribute('aria-pressed', String(index === (i === sections.length ? -1 : i))); });
            var chosen = buttons[index === -1 ? sections.length : index];
            if (chosen) revealDay(tabs, chosen);
            if (event?.detail > 0 && previousIndex !== index) sections.forEach(function (section, i) {
                if (!section.hidden) window.__univerMotionUI?.reveal(section, index >= previousIndex ? 'forward' : 'back', Math.min(i, 2) * 24);
            });
        }
        Array.from(header.cells).slice(1).forEach(function (day, dayIndex) {
            var name = words.days[dayIndex] || cleanText(day), shortDay = nativeChrome ? shortDays()[dayIndex] : null;
            // A phone shows short day names in one scrolling row; the full name stays the accessible label,
            // which starts with the visible short name so voice control can use either.
            var button = document.createElement('button'); button.type = 'button'; button.textContent = shortDay || name;
            button.setAttribute('aria-label', (shortDay ? shortDay + ', ' : '') + name);
            button.addEventListener('click', function (event) { select(dayIndex, event); }); tabs.appendChild(button); buttons.push(button);
            var day = daySection(grid, dayIndex, name, record);
            sections.push(day.section); view.appendChild(day.section);
            var total = document.createElement('span'); total.className = 'univer-app-day-count'; total.textContent = String(day.count); total.setAttribute('aria-hidden', 'true'); button.appendChild(total);
        });
        var all = document.createElement('button'); all.type = 'button'; all.textContent = words.all;
        all.addEventListener('click', function (event) { select(-1, event); }); tabs.appendChild(all); buttons.push(all);
        // Today opens first; "All days" keeps it marked. A rebuilt week keeps the user's own choice.
        if (today >= 0 && sections[today]) {
            sections[today].classList.add('univer-app-today'); buttons[today].classList.add('univer-app-today');
            var badge = document.createElement('span'); badge.className = 'univer-app-today-badge'; badge.textContent = words.today;
            sections[today].querySelector('h3').appendChild(badge);
            buttons[today].setAttribute('aria-label', buttons[today].getAttribute('aria-label') + ', ' + words.today);
            if (!previous) record.selected = today;
        }
        wrapTable(table, true);
        var scroll = table.parentElement; scroll.classList.add('univer-app-schedule-scroll');
        function mode(value, event) {
            var changed = record.mode !== value;
            if (changed) { window.__univerMotionUI?.stop(view); window.__univerMotionUI?.stop(scroll); }
            record.mode = value; view.dataset.mode = value;
            table.classList.toggle('univer-app-schedule-grid-visible', value === 'grid');
            scroll.classList.toggle('univer-app-schedule-grid-visible', value === 'grid');
            dayMode.setAttribute('aria-pressed', String(value === 'days')); gridMode.setAttribute('aria-pressed', String(value === 'grid'));
            if (event?.detail > 0 && changed) {
                if (value === 'grid') window.__univerMotionUI?.reveal(scroll, 'forward');
                else sections.filter(function (s) { return !s.hidden; }).forEach(function (s) { window.__univerMotionUI?.reveal(s, 'back'); });
            }
        }
        dayMode.addEventListener('click', function (event) { mode('days', event); }); gridMode.addEventListener('click', function (event) { mode('grid', event); });
        roveDays(tabs, buttons);
        if (previous) { window.__univerMotionUI?.stop(previous.view); previous.view.replaceWith(view); } else scroll.before(view);
        schedules.set(table, record); table.classList.add('univer-app-schedule-source'); select(record.selected); mode(record.mode); markUpcoming(record, new Date());
    }
    function adaptSchedules() {
        schedules.forEach(function (record, table) { if (!table.isConnected) { record.view.remove(); schedules.delete(table); } });
        if (!schedules.size && upcomingTimer) { clearTimeout(upcomingTimer); upcomingTimer = 0; }
        if (!/^\/student\/myschedule(\/|$)/.test(location.pathname) || /^\/student\/myschedule\/print\//.test(location.pathname)) return;
        document.documentElement.classList.add('univer-app-schedule-page');
        document.querySelectorAll('table.schedule').forEach(function (table) {
            table.closest('.scrool,.scrool200,div.inner')?.classList.add('univer-app-schedule-content');
            var rows = ownRows(table), header = rows[0];
            if (!header || header.cells.length < 3 || header.cells[1].tagName !== 'TH') return;
            copyScheduleTimes(table);
            var today = todayInWeek(new Date());
            Array.from(header.cells).forEach(function (cell, i) { cell.classList.toggle('univer-app-today-col', today >= 0 && i === today + 1); });
            // Taken after the time copies and today's column; the agenda's own class goes on the table itself,
            // outside its innerHTML, so the signature stays stable between passes.
            var signature = table.innerHTML + selectedLanguage, previous = schedules.get(table);
            if (previous?.signature === signature) { markUpcoming(previous, new Date()); return; }
            buildSchedule(table, header, rows, previous, signature, today);
            // A tick at each minute boundary re-runs the adapter, so statuses and countdowns follow the clock.
            if (!upcomingTimer) tickUpcoming();
        });
    }
    function dialogViewport() {
        if (!enabled) return;
        var view = window.visualViewport, height = view ? view.height : window.innerHeight;
        var root = document.documentElement;
        var values = {'--univer-dialog-top': ((view ? view.offsetTop : 0) + 12) + 'px',
            '--univer-dialog-height': Math.max(120, height - 24) + 'px',
            '--univer-frame-height': Math.max(100, height - 100) + 'px'};
        Object.keys(values).forEach(function (key) { if (root.style.getPropertyValue(key) !== values[key]) root.style.setProperty(key, values[key]); });
    }
    // Replaces the text of a site text node; Original restores the recorded value.
    function rewriteText(node, value) {
        if (!punctuation.has(node)) punctuation.set(node, node.data);
        if (node.data !== value) node.data = value;
    }
    // Stray "/", "|" and "( )" separators between links read as noise on a phone.
    function cleanSeparators() {
        document.querySelectorAll('#l4 .ct,#l4 .ts,table.xp td').forEach(function (cell) {
            Array.from(cell.childNodes).forEach(function (node) {
                if (node.nodeType !== 3) return;
                if (/^[\s\\/|]+$/.test(node.data) && /[\\/|]/.test(node.data)) rewriteText(node, '');
                else if (node.previousSibling?.nodeType === 1 && node.previousSibling.tagName === 'A' && /^\s*[\\/]\s*\S/.test(node.data))
                    rewriteText(node, node.data.replace(/^\s*[\\/]\s*/, ' / '));
            });
        });
        document.querySelectorAll('#tools li').forEach(function (li) {
            if (Array.from(li.children).filter(function (n) { return n.tagName === 'A'; }).length < 2) return;
            Array.from(li.childNodes).forEach(function (node) {
                if (node.nodeType === 3 && /^[\s()]+$/.test(node.data) && /[()]/.test(node.data)) rewriteText(node, '');
            });
        });
    }
    // Panels drawn with the site's corner cells (.lt/.rt) become one card; their empty spacer rows collapse.
    function markStudyPanels() {
        document.querySelectorAll('table.mt').forEach(function (table) {
            if (table.closest('#tools,#login_frm,#modal_auth_frm,#modal_agreement,.univer-app-info')) return;
            if (Array.from(table.rows).some(function (r) { return Array.from(r.cells).some(function (c) { return c.matches('.lt,.rt'); }); })) {
                table.classList.add('univer-app-study-panel');
                ownRows(table).forEach(function (row) { row.classList.toggle('univer-app-study-empty', !cleanText(row) && !row.querySelector('input,select,textarea,button,a,img,iframe')); });
            }
        });
    }
    // The academic calendar's semester anchors become a navigation row above its table.
    function adaptCalendarNav() {
        document.querySelectorAll('#l4 table.xp').forEach(function (table) {
            var links = Array.from(table.querySelectorAll('a[href^="#"]'));
            if (!links.length) return;
            var nav = document.createElement('nav'); nav.className = 'univer-app-semester-nav';
            nav.setAttribute('aria-label', selectedLanguage === 'en' ? 'Semesters' : selectedLanguage === 'kz' ? 'Семестрлер' : 'Семестры'); table.before(nav);
            links.forEach(function (link) { moveUsability(link, nav); });
            table.classList.add('univer-app-calendar-meta');
            ownRows(table).forEach(function (row) { Array.from(row.cells).forEach(function (cell) { if (!cleanText(cell) && !cell.querySelector('input,select,button,img')) cell.classList.add('univer-app-empty-nav-cell'); }); });
        });
    }
    function headingRow(rows) {
        return rows.find(function (r) { return r.cells.length > 1 && Array.from(r.cells).every(function (c) { return c.tagName === 'TH'; }); });
    }
    // Label/value rows ("ФИО:", "Факультет"), two or four cells each, that read as a profile block. The student's
    // questionnaire marks its labels with a list item, with or without a colon.
    function labelValueRows(own) {
        return own.some(function (r) { return r.cells[0]?.querySelector('li') || /:|фио|ф\.и\.о|факульт|кафедр|телефон|e.?mail/i.test(r.cells[0]?.textContent || ''); }) && own.every(function (r) {
            var cells = Array.from(r.cells);
            return (cells.length === 2 || cells.length === 4) && cells.every(function (c) { return c.colSpan === 1 && c.rowSpan === 1; }) &&
                cells.filter(function (_, i) { return i % 2 === 0; }).every(function (c) {
                    return !c.textContent.trim() || c.querySelector('li') || /:\s*$/.test(c.textContent) || /^(фио|ф\.и\.о\.?|факультет|кафедра|(?:рабочий )?телефон|e.?mail|курс|специальность|адрес)$/i.test(c.textContent.trim());
                });
        });
    }
    // "Форма обучения: очная" in a .xp table: every other cell is a label. The other .xp tables hold navigation links.
    function factRows(own) {
        return own.some(function (r) { return /:\s*$/.test(r.cells[0]?.textContent || ''); }) &&
            own.every(function (r) { return Array.from(r.cells).every(function (c, i) { return i % 2 || !c.textContent.trim() || /:\s*$/.test(c.textContent); }); });
    }
    // The site evens out its two-column rows with label/value cells that hold nothing.
    function markBlankPairs(own) {
        own.forEach(function (r) {
            Array.from(r.cells).forEach(function (c, i) {
                var other = r.cells[i % 2 ? i - 1 : i + 1];
                c.classList.toggle('univer-app-blank-field', !cleanText(c) && !c.children.length && !cleanText(other) && !other?.children.length);
            });
        });
    }
    // One card per record, each value labelled with its column heading.
    function makeCards(table, own, header) {
        table.classList.add('univer-app-cards'); header.classList.add('univer-app-card-header');
        // Keep the original clickable sort headings available above the statement cards.
        if (table.matches('.table_sort')) {
            header.classList.add('univer-app-sort-head');
            Array.from(header.cells).forEach(function (c) { c.classList.toggle('univer-app-blank-field', !cleanText(c) && !c.children.length); });
        }
        own.forEach(function (r) {
            if (r === header) return;
            var cells = Array.from(r.cells);
            if (cells.length === 1 && cells[0].colSpan > 1) { r.classList.add('univer-app-card-section'); return; }
            r.classList.add('univer-app-card-row');
            // A short single link in the last column is the row's action ("Подать заявку", "Открыть").
            var last = cells[cells.length - 1], action = last?.children.length === 1 && last.firstElementChild.tagName === 'A' ? last.firstElementChild : null;
            if (action && cleanText(last) === cleanText(action) && cleanText(action).length <= 28) action.classList.add('univer-app-action-link');
            cells.forEach(function (c, i) {
                if (c.tagName === 'TH') return;
                var label = header.cells[i]?.textContent.trim();
                if (label && c.dataset.univerField !== label) c.dataset.univerField = label;
                c.classList.toggle('univer-app-blank-field', !c.children.length && !c.textContent.trim());
                if (/дисциплин|название|описание|услуга|содержание|преподаватель/i.test(label || '') || c.textContent.length > 100)
                    c.classList.add('univer-app-long-field');
            });
        });
    }
    // Classifies one site table by its observed structure: profile pairs, form, print layout, matrix or cards.
    function classifyTable(table, path, personal, print) {
        var own = ownRows(table);
        if (!own.length || own.some(function (r) { return Array.from(r.cells).some(function (c) { return c.matches('.left,.right,.center'); }); })) return;
        var header = headingRow(own);
        if ((personal || print) && !header && labelValueRows(own)) { table.classList.add('univer-app-pairs'); markBlankPairs(own); return; }
        if (personal && (table.querySelector('input[type=file]') || (!header && table.querySelector('input,select,textarea')))) {
            table.classList.add('univer-app-form-table'); markBlankPairs(own); return;
        }
        if (print) {
            if (/^(ф\.и\.о\.?|фио|аты.?жөні|full name|student name|name)$/i.test(own[0].cells[0]?.textContent.trim() || '')) {
                table.classList.add('univer-app-pairs'); return;
            }
            // The outer print sheet and syllabus text blocks are layout tables. Only real grids scroll.
            if (own.every(function (r) { return r.cells.length <= 2; })) { table.classList.add('univer-app-document-layout'); return; }
            wrapTable(table, true); return;
        }
        if (!table.matches('.inner,.inner2,#scheduleList')) return;
        // A one-cell result such as "no data" reads as a quiet empty state, not as a record.
        if (own.length === 1 && own[0].cells.length === 1 && !table.querySelector('input,select,textarea,button,a,img,iframe,table') &&
            cleanText(own[0]).length > 0 && cleanText(own[0]).length < 120) { table.classList.add('univer-app-empty-note'); return; }
        if (/^\/student\/contract\/list(\/|$)/.test(path) && table.querySelector('th>.full-width>div')) {
            table.classList.add('univer-app-div-records', 'univer-app-data'); wrapTable(table); return;
        }
        if (/\/attendance\//i.test(path) || table.querySelector('td[rowspan],th[rowspan]')) {
            table.classList.add('univer-app-matrix'); wrapTable(table); return;
        }
        // Exam and Midterm use a separate table for column headings and the AJAX result rows.
        if (!header && /\/my(exam|midterm)\/schedule\/|^\/my\/messages\//i.test(path)) {
            var previous = Array.from(document.querySelectorAll('table.inner')).filter(function (t) {
                return !!(t.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING);
            }).pop();
            header = previous && headingRow(Array.from(previous.rows));
            if (header) header.classList.add('univer-app-card-header');
        }
        if (!header || Array.from(header.cells).some(function (c) { return c.colSpan !== 1 || c.rowSpan !== 1; })) return;
        if (own.length === 1 && own[0].cells.length === 1 && !table.querySelector('input,select,textarea,button,a') && /^(данные не найдены|обращени[яе] не найден[ыо]|нет данных|не заполнено расписание midterm|файлы не загружены)$/i.test(cleanText(own[0])))
            table.closest('.scrool,.scrool200')?.classList.add('univer-app-empty-result');
        if (own.some(function (r) { return r !== header && r.cells.length > 1 && Array.from(r.cells).some(function (c) { return c.colSpan > 1 && c.colSpan < header.cells.length; }); })) {
            table.classList.add('univer-app-matrix'); wrapTable(table); return;
        }
        makeCards(table, own, header);
    }
    // These classes describe observed table structures; original controls and handlers stay in place.
    function adaptRecords() {
        cleanSeparators();
        markStudyPanels();
        var path = location.pathname;
        if (!/^\/(student|ssc|my)(\/|$)/i.test(path)) return;
        var print = /^\/student\/(iup|transcript|recbook)(\/|$)/i.test(path) || /^\/student\/(interactive\/syllabus|myschedule)\/print\//i.test(path);
        var personal = /^\/student\/(bachelor|profile|advicer|mydata)(\/|$)/i.test(path) || path === '/student' || path === '/student/';
        document.querySelectorAll('#l4 ul,#l4 ol').forEach(function (list) {
            if (!list.closest('#tools,#login_frm,#modal_agreement')) list.classList.add('univer-app-list');
        });
        if (/^\/student\/academcalendar\/?$/.test(path)) adaptCalendarNav();
        document.querySelectorAll('table.xp').forEach(function (table) {
            var own = ownRows(table);
            if (!factRows(own) || table.querySelector('input,select,textarea,button')) { table.classList.add('univer-app-nav-table'); return; }
            table.classList.add('univer-app-pairs'); markBlankPairs(own);
        });
        if (print) document.documentElement.classList.add('univer-app-document');
        Array.from(document.querySelectorAll('table')).forEach(function (table) {
            if (table.closest('#tools,#login_frm,#modal_auth_frm,#modal_agreement,.univer-app-info') ||
                table.matches('.mt,.tool,.cols,.wrappertable,.univer-app-shell,.univer-app-news,.univer-app-courses,.univer-app-materials,.schedule')) return;
            classifyTable(table, path, personal, print);
        });
    }
    // A box that scrolls a table wider than the phone sideways, unless the table is part of another table's
    // structure (wrapping it would corrupt that DOM) or, unless [nested], already in one. scrollHints() makes it a
    // named, focusable region only while the table is really wider than the box.
    function wrapTable(table, nested) {
        if (table.parentElement.matches('tr,tbody,thead,tfoot,table,.univer-app-table-scroll') ||
            (!nested && table.closest('.univer-app-table-scroll'))) return;
        var box = document.createElement('div'); box.className = 'univer-app-table-scroll';
        table.parentNode.insertBefore(box, table); box.appendChild(table);
    }
    // A wide table's hint row: the swipe caption and, with univer-tables.js, "Expand" for the full-screen view.
    function scrollHints() {
        var caption = selectedLanguage === 'en' ? '↔ Swipe the table sideways to see all columns' :
            selectedLanguage === 'kz' ? '↔ Барлық бағанды көру үшін кестені көлденең жылжытыңыз' :
            '↔ Сдвиньте таблицу вбок, чтобы увидеть все столбцы';
        // The spoken name is the caption without its arrow.
        var name = caption.replace(/^↔\s*/, '');
        var tables = window.__univerTablesV1;
        tables?.setLanguage(selectedLanguage);
        document.querySelectorAll('.univer-app-table-scroll').forEach(function (box) {
            if (box.closest('.univer-app-table-viewer')) return;
            var hint = box.__univerHint;
            // A table with nothing in it has nothing to swipe to or to open.
            var empty = box.querySelector('table.univer-app-empty-note') || box.querySelector('tr.univer-app-empty-row') && !box.querySelector('tr:not(.univer-app-empty-row)>td');
            var wide = !empty && box.getClientRects().length && box.scrollWidth > box.clientWidth + 2;
            if (!hint && wide) {
                hint = document.createElement('div'); hint.className = 'univer-app-scroll-hint';
                var text = document.createElement('span'); text.className = 'univer-app-scroll-caption'; hint.appendChild(text);
                if (tables && box.firstElementChild?.tagName === 'TABLE') {
                    var open = document.createElement('button'); open.type = 'button'; open.className = 'univer-app-table-open';
                    open.addEventListener('click', function () { tables.open(box.firstElementChild); });
                    // With the button the swipe caption is for screen readers only.
                    hint.appendChild(open); hint.classList.add('univer-app-scroll-hint-expand');
                }
                box.before(hint); box.__univerHint = hint;
            }
            if (hint) {
                var label = hint.querySelector('.univer-app-scroll-caption'), expand = hint.querySelector('.univer-app-table-open');
                if (label && label.textContent !== caption) label.textContent = caption;
                if (expand && tables && expand.textContent !== tables.label()) expand.textContent = tables.label();
                hint.hidden = !wide;
            }
            // Only a table wider than its box is a keyboard and screen-reader stop of its own; card lists, fitted and
            // narrow tables are read in place.
            if (wide) {
                if (box.tabIndex !== 0) box.tabIndex = 0;
                if (box.getAttribute('role') !== 'region') box.setAttribute('role', 'region');
                if (box.getAttribute('aria-label') !== name) box.setAttribute('aria-label', name);
            } else ['tabindex', 'role', 'aria-label'].forEach(function (attribute) { if (box.hasAttribute(attribute)) box.removeAttribute(attribute); });
        });
        document.querySelectorAll('.univer-app-scroll-hint').forEach(function (hint) {
            if (!hint.nextElementSibling?.classList.contains('univer-app-table-scroll')) hint.remove();
        });
    }
    function adaptFrames() {
        document.querySelectorAll('iframe,frame').forEach(function (frame) {
            if (!frameListeners.has(frame)) { frameListeners.add(frame); frame.addEventListener('load', schedule); }
            try {
                var win = frame.contentWindow;
                if (win && win.location.origin === location.origin && win.document.readyState !== 'loading') {
                    // The adapter runs again inside the frame from its own source; its stylesheet (univer-mobile-css.js) goes first.
                    if (!win.__univerMobileCssV1) win.__univerMobileCssV1 = css;
                    if (!win.__univerMobileV1) win.eval('(' + initializeMobile.toString() + ')();');
                    win.__univerMobileV1.setEnabled(enabled, selectedLanguage);
                    win.__univerPresentationV1?.release(); childAdapters.add(win);
                }
            } catch (_) { /* Never alter external frames. */ }
        });
    }
    function moveUsability(node, destination) {
        var home = document.createComment('univer-original-content');
        node.before(home); destination.appendChild(node); usabilityMoves.push({node:node, home:home});
    }
    function filterCatalog() {
        var input = document.querySelector('.univer-app-catalog-search');
        var query = (input?.value || '').trim().toLocaleLowerCase().replace(/ё/g,'е');
        document.querySelectorAll('.univer-app-catalog').forEach(function (group) {
            var count = 0;
            group.querySelectorAll('tr.file').forEach(function (row) {
                if (!catalogHidden.has(row)) catalogHidden.set(row, row.hidden);
                var title = Array.from(row.cells[2]?.childNodes || []).filter(function(n){return !n.classList?.contains('univer-app-catalog-download');}).map(cleanText).join(' ');
                var matches = !catalogHidden.get(row) && (!query || title.toLocaleLowerCase().replace(/ё/g,'е').includes(query));
                if (row.hidden === matches) row.hidden = !matches;
                if (matches) count++;
            });
            if (group.hidden !== (!!query && !count)) group.hidden = !!query && !count;
            if (query && count && !group.open) group.open = true;
        });
        var empty = document.querySelector('.univer-app-catalog-empty');
        if (empty) { empty.textContent = selectedLanguage==='en'?'No documents found':selectedLanguage==='kz'?'Құжаттар табылмады':'Документы не найдены'; empty.hidden = !query || !!document.querySelector('.univer-app-catalog:not([hidden])'); }
    }
    // The two GPA counters the site repeats above every student section: a list of exactly two GPA items.
    function gpaItems(cell) {
        var items = Array.from((cell.querySelector(':scope>ul') || cell).children);
        return items.length === 2 && items.every(function (li) { return li.tagName === 'LI' && /G[PР][AА]/i.test(cleanText(li)); }) ? items : null;
    }
    function adaptGpaCounters() {
        if (/^\/student\/bachelor\/?$/.test(location.pathname)) return;
        // In the browser layout their original nodes move under Account instead of consuming
        // the first screen of the timetable and course material pages.
        var accountBody = document.querySelector('.univer-app-account>div');
        if (accountBody && !nativeChrome) document.querySelectorAll('#l4 .ct.message').forEach(function (cell) {
            var list = cell.querySelector(':scope>ul'), items = gpaItems(cell);
            if (!items || cell.querySelector('input,select,button,form')) return;
            var home = cell.closest('table.mt');
            if (list) moveUsability(list, accountBody);
            else {
                var destination = document.createElement('ul'); destination.className = 'univer-app-academic-meta'; accountBody.appendChild(destination);
                items.forEach(function (item) { moveUsability(item, destination); });
            }
            if (home && ownRows(home).every(function (r) { return !cleanText(r) && !r.querySelector('input,select,textarea,button,a,img,iframe'); })) home.classList.add('univer-app-empty-panel');
        });
        // Android hides the site's account menu under its own app bar. The two GPA figures stay where they lead,
        // as cards on Бакалавр; elsewhere their table is hidden, not moved.
        if (nativeChrome) document.querySelectorAll('#l4 .ct.message').forEach(function (cell) {
            var home = cell.closest('table.mt');
            if (!home || !gpaItems(cell) || home.querySelector('input,select,button,form,a')) return;
            if (ownRows(home).every(function (r) { return r.contains(cell) || !cleanText(r); })) home.classList.add('univer-app-empty-panel');
        });
    }
    // The week title links to the shown week; headings that only repeat it and the print link are marked.
    function adaptScheduleHeading() {
        var headingLink = Array.from(document.querySelectorAll('#l4 .ct>a[href]')).find(function (a) { return /^\/student\/myschedule\/\d+\/\d+\/?$/.test(new URL(a.href).pathname); });
        if (headingLink) {
            var heading = headingLink.closest('.ct'), title = cleanText(headingLink);
            heading.classList.add('univer-app-schedule-title');
            document.querySelectorAll('#l4 .ct').forEach(function (cell) {
                if (cell !== heading && !cell.querySelector('a,input,select,textarea,button,form') && cleanText(cell).startsWith(title)) cell.classList.add('univer-app-secondary-heading');
            });
        }
        document.querySelectorAll('#l4 a[href]').forEach(function (a) { if (/^\/student\/myschedule\/print\/\d+\/\d+\/?$/.test(new URL(a.href).pathname)) a.classList.add('univer-app-print-action'); });
    }
    // The section menu marks the links of the current page; Original restores their own aria-current.
    function markCurrentLinks() {
        var menu = document.querySelector('.univer-app-menu'), here = location.pathname.replace(/\/+$/, '');
        if (menu) menu.querySelectorAll('#tools a[href]').forEach(function (a) {
            var url = new URL(a.href);
            if (url.origin !== location.origin || url.pathname.replace(/\/+$/, '') !== here) return;
            if (!pageMarkers.has(a)) pageMarkers.set(a, a.getAttribute('aria-current'));
            a.setAttribute('aria-current', 'page');
        });
    }
    function markForms(path) {
        document.querySelectorAll('#l4 form table.cols').forEach(function (table) { table.classList.add('univer-app-form-table'); });
        // Single-cell "no data" rows inside real tables become a quiet empty state.
        document.querySelectorAll('#l4 table tr').forEach(function (row) {
            if (row.cells.length !== 1 || row.classList.contains('univer-app-empty-row') || row.querySelector('input,select,textarea,button,a,img')) return;
            if (/^(нет данных|данные не найдены|файлы не загружены|в журнале нет данных|обращени[яе] не найден[ыо]|нет расписания|no data( found)?|data not found|деректер (жоқ|табылмады))\.?$/i.test(cleanText(row)))
                row.classList.add('univer-app-empty-row');
        });
        if (/^\/student\/mydata\/edit\/?$/.test(path)) document.querySelectorAll('#l4 .univer-app-form-table').forEach(function (table) {
            ownRows(table).forEach(function (row) {
                var cells = Array.from(row.cells);
                if (!cleanText(row) && !row.querySelector('input,select,textarea,button,a,img,iframe')) row.classList.add('univer-app-form-spacer');
                if (cells.length === 3 && cells[0].tagName === 'TH' && cells[2].tagName === 'TH' && !cleanText(cells[1]) && !row.querySelector('input,select,textarea,button,a') &&
                    /^(данные|data|деректер)$/i.test(cleanText(cells[0])) && /^(фото|photo|сурет)$/i.test(cleanText(cells[2]))) row.classList.add('univer-app-form-spacer');
            });
        });
        document.querySelectorAll('#l4 table.cols').forEach(function (table) {
            if (!table.closest('#login_frm,#modal_auth_frm,#modal_agreement') && !table.querySelector('form') && !table.closest('form'))
                table.classList.add('univer-app-content-columns');
        });
    }
    // Exam schedule and the distance-course index place blocks side by side with floats; on a phone they stack.
    // A marked block no longer floats (the stylesheet), so it needs no new style lookup.
    function markMediaColumns() {
        document.querySelectorAll('#l4 div').forEach(function (block) {
            if (block.closest('th,.univer-app-data') || block.classList.contains('univer-app-media-column') || getComputedStyle(block).cssFloat === 'none') return;
            block.classList.add('univer-app-media-column');
            if (!cleanText(block) && !block.querySelector('input,select,button,a,img,iframe')) block.classList.add('univer-app-study-empty');
        });
    }
    // An embedded YouTube instruction also gets a plain link that opens the video outside the page.
    function adaptVideos() {
        var caption = selectedLanguage === 'en' ? 'Watch video instructions' : selectedLanguage === 'kz' ? 'Бейненұсқаулықты көру' : 'Смотреть видеоинструкцию';
        document.querySelectorAll('#l4 iframe[src]').forEach(function (frame) {
            var source; try { source = new URL(frame.src); } catch (_) { return; }
            if (!/^(www\.)?youtube(-nocookie)?\.com$/.test(source.hostname)) return;
            frame.classList.add('univer-app-video');
            if (!frame.__univerVideoLink?.isConnected) {
                var link = document.createElement('a'), video = source.pathname.match(/^\/embed\/([A-Za-z0-9_-]+)\/?$/);
                link.className = 'univer-app-video-link'; link.target = '_blank'; link.rel = 'noopener noreferrer';
                link.href = video ? 'https://www.youtube.com/watch?v=' + encodeURIComponent(video[1]) : source.href;
                frame.after(link); frame.__univerVideoLink = link;
            }
            if (frame.__univerVideoLink.textContent !== caption) frame.__univerVideoLink.textContent = caption;
            var parent = frame.parentElement;
            if (parent.tagName === 'DIV' && parent.style.cssFloat) Array.from(parent.parentElement.children).forEach(function (node) {
                if (node.tagName === 'DIV' && node.style.cssFloat) node.classList.add('univer-app-media-column');
            });
        });
    }
    // The server IP and the separator left after "Сайт КазНУ |" are not content; Original restores both.
    function hideCopyrightNoise() {
        var copyright = document.getElementById('copyright'); if (!copyright) return;
        var walker = document.createTreeWalker(copyright, NodeFilter.SHOW_TEXT), piece;
        while ((piece = walker.nextNode())) if (/^\s*IP:\s*[\d.:]+\s*$/.test(piece.data) || /^\s*\|\s*$/.test(piece.data)) rewriteText(piece, '');
    }
    // Real general document catalog: separate TD headings, tr.file.link[id], /my/docsget/{id}/ via dblclick.
    function adaptDocCatalog() {
        document.querySelectorAll('#l4 table.mt').forEach(function (table) {
            if (table.closest('.univer-app-catalog') || !table.querySelector('.scrool200 tr.file.link[id]')) return;
            var rows = ownRows(table); if (rows.length !== 3 || rows[0].cells.length !== 1) return;
            var details = document.createElement('details'); details.className = 'univer-app-catalog';
            var summary = document.createElement('summary'); summary.textContent = cleanText(rows[0]); details.appendChild(summary);
            table.before(details); moveUsability(table, details); rows[0].classList.add('univer-app-catalog-title');
            table.querySelectorAll('table.inner').forEach(function (inner) {
                if (inner.closest('.scrool200')) return;
                if (ownRows(inner).length === 1) inner.classList.add('univer-app-catalog-head');
            });
        });
        var fields = selectedLanguage === 'en' ? ['Date','Size','Downloads'] : selectedLanguage === 'kz' ? ['Күні','Көлемі','Жүктеулер'] : ['Дата','Размер','Скачали'], download = labels().files;
        document.querySelectorAll('.univer-app-catalog tr.file.link[id]').forEach(function (row) {
            if (row.cells.length !== 6) return;
            [3, 4, 5].forEach(function (i) { if (row.cells[i].dataset.univerField !== fields[i - 3]) row.cells[i].dataset.univerField = fields[i - 3]; });
            var button = row.querySelector('.univer-app-catalog-download');
            if (!button) {
                button = document.createElement('button'); button.type = 'button'; button.className = 'univer-app-catalog-download';
                button.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); row.dispatchEvent(new MouseEvent('dblclick', {bubbles:true, cancelable:true})); });
                row.cells[2].appendChild(button);
            }
            if (button.textContent !== download) button.textContent = download;
        });
        var group = document.querySelector('.univer-app-catalog');
        if (group && !document.querySelector('.univer-app-catalog-search')) {
            var input = document.createElement('input'); input.type = 'search'; input.className = 'univer-app-catalog-search';
            input.addEventListener('input', filterCatalog); group.before(input);
            var empty = document.createElement('p'); empty.className = 'univer-app-catalog-empty'; empty.setAttribute('role', 'status'); empty.hidden = true; input.after(empty);
        }
        var search = document.querySelector('.univer-app-catalog-search');
        if (search) {
            var title = selectedLanguage === 'en' ? 'Find a document…' : selectedLanguage === 'kz' ? 'Құжатты іздеу…' : 'Найти документ…';
            if (search.placeholder !== title) { search.placeholder = title; search.setAttribute('aria-label', title); }
        }
        filterCatalog();
    }
    function adaptFaq() {
        document.querySelectorAll('#l4 td.tt>table.inner').forEach(function (table) {
            if (table.closest('.univer-app-faq') || ownRows(table).length !== 2) return;
            var answer = table.closest('tr'), question = answer?.previousElementSibling?.querySelector('.ct');
            if (!question || !cleanText(question)) return;
            var box = document.createElement('details'); box.className = 'univer-app-faq';
            var summary = document.createElement('summary'); summary.textContent = cleanText(question); box.appendChild(summary);
            table.before(box); moveUsability(table, box); question.closest('tr').classList.add('univer-app-catalog-title');
            // The real FAQ keeps answer rows display:none until the old question handler runs.
            // Our summary lives in that row, so expose it only while mobile mode is enabled.
            answer.classList.add('univer-app-faq-row');
            var panel = question.closest('table.mt'); if (panel) panel.classList.add('univer-app-study-panel');
        });
        // Filtered here rather than with :has(), which Chromium before 105 cannot parse.
        Array.from(document.querySelectorAll('#l4 table.mt')).filter(function (panel) { return panel.querySelector('.univer-app-faq'); }).forEach(function (panel) {
            ownRows(panel).forEach(function (row) {
                if (!cleanText(row) && !row.querySelector('input,select,textarea,button,a,img,iframe')) row.classList.add('univer-app-study-empty');
            });
        });
    }
    function adaptGpaCalculator() {
        var gpa = document.querySelector('#gpa-table'), rows = gpa ? ownRows(gpa) : [], head = rows[0];
        // The calculator fills its own table; one without rows yet waits for the next pass instead of failing it.
        if (!head) return;
        gpa.classList.add('univer-app-cards'); head.classList.add('univer-app-card-header');
        rows.slice(1).forEach(function (r) {
            if (r.cells.length !== 3) return; r.classList.add('univer-app-card-row'); r.cells[0].classList.add('univer-app-long-field');
            Array.from(r.cells).forEach(function (c, i) { var label = cleanText(head.cells[i]); if (c.dataset.univerField !== label) c.dataset.univerField = label; });
        });
        var outer = gpa.parentElement.closest('table'); if (outer) outer.classList.add('univer-app-gpa-layout');
    }
    // Statement cards keep the site's sortable headings behind one button; sorting itself stays the site's.
    function addSortToggles() {
        var caption = selectedLanguage === 'en' ? 'Sort by column' : selectedLanguage === 'kz' ? 'Баған бойынша сұрыптау' : 'Сортировка по столбцу';
        document.querySelectorAll('table.table_sort.univer-app-cards').forEach(function (table) {
            if (table.__univerSortButton?.isConnected) { if (table.__univerSortButton.textContent !== caption) table.__univerSortButton.textContent = caption; return; }
            var button = document.createElement('button'); button.type = 'button'; button.className = 'univer-app-sort-toggle'; button.setAttribute('aria-expanded', 'false');
            button.textContent = caption;
            button.addEventListener('click', function (event) {
                var height = table.getBoundingClientRect().height; window.__univerMotionUI?.stop(table);
                var closed = table.classList.toggle('univer-app-sort-collapsed'); button.setAttribute('aria-expanded', String(!closed));
                if (event.detail > 0) window.__univerMotionUI?.resize(table, height, closed ? 180 : 220);
            });
            table.classList.add('univer-app-sort-collapsed'); table.before(button); table.__univerSortButton = button;
        });
    }
    // Not every observed .inner grid has TH headings (testing and split AJAX results).
    function wrapInnerGrids() {
        document.querySelectorAll('#l4 table.inner').forEach(function (table) {
            if (table.closest('#tools,.univer-app-info,.univer-app-catalog,.univer-app-table-scroll') ||
                table.matches('.univer-app-cards,.univer-app-pairs,.univer-app-form-table,.univer-app-news,.univer-app-courses,.univer-app-materials')) return;
            var rows = ownRows(table);
            if (rows.some(function (r) { return Array.from(r.cells).some(function (c) { return c.matches('.left,.right,.center'); }); })) return;
            if (rows.some(function (r) { return r.cells.length > 1 || r.cells[0]?.colSpan > 1; })) wrapTable(table);
        });
    }
    function adaptUsability() {
        var path = location.pathname;
        adaptGpaCounters();
        if (/^\/student\/myschedule(\/|$)/.test(path)) adaptScheduleHeading();
        var footer = document.querySelector('#l5 td.center');
        if (footer) document.querySelectorAll('.univer-app-info').forEach(function (box) {
            if (!box.closest('#l5')) moveUsability(box, footer);
        });
        markCurrentLinks();
        // Home tiles, pins and the useful links inside "All sections" (shortcuts.js) follow the menu's current links.
        guarded(function () { window.__univerShortcutsV1?.apply(selectedLanguage); });
        markForms(path);
        if (/^\/student\/(myexam\/schedule|discussion\/index)(\/|$)/.test(path)) markMediaColumns();
        adaptVideos();
        hideCopyrightNoise();
        if (/^\/my\/docs\/?$/.test(path)) adaptDocCatalog();
        if (/^\/my\/faq(\/\d+)?\/?$/.test(path)) adaptFaq();
        if (/^\/my\/calculatorgpa\/?$/.test(path)) adaptGpaCalculator();
        addSortToggles();
        wrapInnerGrids();
    }
    // Website changes the adapter follows; its own changes happen while the observer is disconnected.
    var OBSERVED = {childList:true, subtree:true, characterData:true, attributes:true, attributeFilter:['style','class','hidden','title']};
    // Under the app's own title bar, a page heading that only repeats the current menu item is redundant.
    function markNativeHeading() {
        var pageHeading = document.querySelector('#l4 tr.top>td.ct');
        if (!nativeChrome || !pageHeading) return;
        var here = location.pathname.replace(/\/$/, '');
        var currentLink = Array.from(document.querySelectorAll('#tools a[href]')).find(function (a) { return new URL(a.href, location.href).pathname.replace(/\/$/, '') === here; });
        if ((!pageHeading.querySelector('a,input,select,button') && cleanText(pageHeading) === cleanText(currentLink)) ||
            /^\/(news\/student|student\/myschedule)(\/|$)/.test(location.pathname)) pageHeading.classList.add('univer-app-native-heading');
    }
    // The outer layout table of a site page.
    function markShell() {
        var shell = document.querySelector('body>table.wrappertable') ||
            Array.from(document.querySelectorAll('body>table')).find(function (t) { return !!t.querySelector('#l1,#l4'); });
        if (shell && !shell.classList.contains('univer-app-shell')) shell.classList.add('univer-app-shell');
        return shell;
    }
    // The news list: a "Дата | Новость" heading over tr.news rows.
    function markNewsTables() {
        document.querySelectorAll('table.inner').forEach(function (table) {
            if (!table.classList.contains('univer-app-news') && table.querySelector('tr.news') &&
                Array.from(table.rows).some(function (row) { return row.cells.length === 2 && row.cells[0].tagName === 'TH' &&
                    /^(дата|date|күні)$/i.test(row.cells[0].textContent.trim()) && /новость|news|жаңалық/i.test(row.cells[1].textContent); }))
                table.classList.add('univer-app-news');
        });
    }
    // Univer also uses .inner for real data tables, not only its decorative layout.
    function markDataTables() {
        document.querySelectorAll('table.inner:not(.univer-app-news)').forEach(function (table) {
            if (table.classList.contains('univer-app-data') || table.closest('#tools,#login_frm,#modal_auth_frm')) return;
            var header = Array.from(table.rows).find(function (row) { return row.cells.length >= 2 &&
                Array.from(row.cells).every(function (cell) { return cell.tagName === 'TH'; }); });
            if (!header) return;
            table.classList.add('univer-app-data');
            Array.from(table.rows).forEach(function (row) { Array.from(row.cells).forEach(function (cell, i) {
                if (header.cells[i]?.textContent.trim()) cell.classList.add('univer-app-data-field');
            }); });
        });
    }
    // Site dialogs (simplemodal) get a toolbar that holds their own close control, named and keyboard-operable.
    function adaptDialogs() {
        document.querySelectorAll('.univer-app-modal-toolbar').forEach(function (bar) {
            if (!bar.__univerContainer?.isConnected) { bar.remove(); return; }
            var hidden = !bar.__univerContainer.getClientRects().length;
            if (bar.hidden !== hidden) bar.hidden = hidden;
        });
        document.querySelectorAll('#simplemodal-container .simplemodal-close,.univer-app-modal-toolbar .simplemodal-close').forEach(function (close) {
            if (!close.dataset.univerClose) {
                close.dataset.univerClose = '1'; close.dataset.univerOldTitle = close.title;
                close.dataset.univerOldLabel = close.getAttribute('aria-label') || '';
                close.dataset.univerOldRole = close.getAttribute('role') || '';
                close.dataset.univerOldTab = close.getAttribute('tabindex') || '';
                close.setAttribute('role', 'button'); close.tabIndex = 0;
                close.__univerKeydown = function (event) { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); close.click(); } };
                close.addEventListener('keydown', close.__univerKeydown);
            }
            if (!close.closest('.univer-app-modal-toolbar')) {
                var container = close.closest('#simplemodal-container');
                var home = document.createComment('univer-original-close'); close.parentNode.insertBefore(home, close);
                var toolbar = document.createElement('div'); toolbar.className = 'univer-app-modal-toolbar'; toolbar.__univerCloseHome = home;
                toolbar.__univerContainer = container;
                toolbar.style.zIndex = String((parseInt(getComputedStyle(container).zIndex, 10) || 1002) + 1);
                toolbar.hidden = !container.getClientRects().length;
                document.body.appendChild(toolbar); toolbar.appendChild(close);
            }
            if (close.title !== closeLabel) close.title = closeLabel;
            if (close.getAttribute('aria-label') !== closeLabel) close.setAttribute('aria-label', closeLabel);
        });
    }
    // Only the observed static help box and app/resource links are folded, never account notices/forms.
    function foldHelp() {
        Array.from(document.querySelectorAll('table.mt')).forEach(function (table) {
            var text = table.textContent;
            if (!table.closest('form,#simplemodal-container,#attentionSt,#modal_agreement,#modal_auth_frm') && !table.querySelector('form,input') && text.includes('it@kaznu.kz') &&
                text.includes('wifi-support@kaznu.kz') && text.includes('help@kaznu.kz')) foldInfo(table, 'contacts');
        });
        Array.from(document.querySelectorAll('.links')).forEach(function (links) {
            if (links.querySelectorAll('a').length >= 8 && links.querySelector('a[href*="apps.apple.com"]') &&
                links.querySelector('a[href*="play.google.com"]')) foldInfo(links, 'links');
        });
        document.querySelectorAll('.univer-app-info').forEach(function (box) {
            var summary = box.querySelector('summary'), title = infoTitles[box.dataset.univerInfo];
            if (summary && summary.textContent !== title) summary.textContent = title;
        });
    }
    // The site's section menu (#tools) moves into an "All sections" disclosure with search, close and collapse.
    function buildMenu(tools) {
        var details = document.createElement('details'); details.className = 'univer-app-menu';
        var summary = document.createElement('summary'); summary.textContent = menuTitle;
        details.appendChild(summary);
        menuHome = document.createComment('univer-original-menu');
        var parent = tools.parentNode;
        parent.insertBefore(menuHome, tools);
        // The observed support block can be in a separate Tr1 row above the main content.
        var help = document.querySelector('.univer-app-info[data-univer-info="contacts"]');
        if (help && !help.closest('form,#simplemodal-container')) help.parentNode.insertBefore(details, help);
        else parent.insertBefore(details, parent.firstChild);
        details.appendChild(tools);
        var search = document.createElement('input'); search.type = 'search';
        search.className = 'univer-app-menu-search'; search.autocomplete = 'off';
        // Each keystroke only shows and hides menu items; it needs no adapter pass.
        search.addEventListener('input', function () { quiet(filterMenu); }); details.insertBefore(search, tools);
        var close = document.createElement('button'); close.type = 'button'; close.className = 'univer-app-menu-close';
        close.addEventListener('click', function () { toggleMenu(false); }); details.insertBefore(close, search);
        details.addEventListener('toggle', syncMenuButton);
        var empty = document.createElement('p'); empty.className = 'univer-app-empty'; empty.hidden = true;
        empty.setAttribute('role', 'status'); details.appendChild(empty);
        // A long open menu can also be closed from its end, returning to its title.
        var collapse = document.createElement('button'); collapse.type = 'button'; collapse.className = 'univer-app-menu-collapse';
        collapse.addEventListener('click', function () {
            toggleMenu(false);
            var title = details.querySelector(':scope>summary');
            if (title && title.getBoundingClientRect().top < 0) title.scrollIntoView({block:'start'});
            title?.focus({preventScroll:true});
        });
        details.appendChild(collapse);
    }
    function localizeMenu() {
        var summary = document.querySelector('.univer-app-menu>summary');
        if (summary && summary.textContent !== menuTitle) summary.textContent = menuTitle;
        var search = document.querySelector('.univer-app-menu-search');
        if (search && search.placeholder !== searchLabel) { search.placeholder = searchLabel; search.setAttribute('aria-label', searchLabel); }
        var collapseLabel = selectedLanguage === 'en' ? 'Collapse all sections' : selectedLanguage === 'kz' ? 'Барлық бөлімдерді жию' : 'Свернуть все разделы';
        var menuCollapse = document.querySelector('.univer-app-menu-collapse');
        if (menuCollapse && menuCollapse.textContent !== collapseLabel) menuCollapse.textContent = collapseLabel;
        var menuClose = document.querySelector('.univer-app-menu-close');
        if (menuClose && menuClose.getAttribute('aria-label') !== closeLabel) { menuClose.setAttribute('aria-label', closeLabel); menuClose.title = closeLabel; }
        syncMenuButton();
    }
    // Each titled menu group becomes its own disclosure; only the essentials start open.
    function groupMenu(tools) {
        var here = location.pathname.replace(/\/$/, '');
        tools.querySelectorAll('table.tool').forEach(function (table) {
            if (table.closest('.univer-app-category')) return;
            var footer = table.querySelector('.bottom-center');
            if (!footer || !footer.textContent.trim()) return;
            var box = document.createElement('details'); box.className = 'univer-app-category';
            // The first group, the study process and the group of the current page start open.
            var current = Array.from(table.querySelectorAll('a[href]')).some(function (a) { try { return new URL(a.href, location.href).pathname.replace(/\/$/, '') === here; } catch (_) { return false; } });
            box.open = !categories.length || current || /учебный процесс|оқу процесі|educational process|study process/i.test(footer.textContent);
            var title = document.createElement('summary'); title.textContent = footer.textContent.trim(); box.appendChild(title);
            var home = document.createComment('univer-original-category');
            table.parentNode.insertBefore(home, table); table.parentNode.insertBefore(box, table); box.appendChild(table);
            footer.closest('tr').classList.add('univer-app-menu-footer');
            categories.push({table:table, box:box, home:home});
        });
        filterMenu();
    }
    function adaptMenu() {
        var tools = document.getElementById('tools');
        if (tools && !tools.closest('.univer-app-menu')) buildMenu(tools);
        localizeMenu();
        if (tools) groupMenu(tools);
    }
    // Wide semantic tables scroll sideways; the shell and decorative layout, menu and login tables already fit.
    function wrapLayoutTables(shell) {
        Array.from(document.querySelectorAll('table')).forEach(function (table) {
            if (table === shell || (table.matches('.inner') && !table.classList.contains('univer-app-data')) || table.matches('.mt,.tool,.cols,#tools,#copyright') ||
                table.closest('#login_frm,#modal_auth_frm,#tools,.jexcel_container,.jss_container')) return;
            wrapTable(table);
        });
    }
    function adapt() {
        queued = false;
        if (!enabled || !document.body) return;
        // Observe website updates, not the adapter's own class/DOM changes.
        observer?.disconnect();
        try {
            document.documentElement.classList.toggle('univer-native-chrome', nativeChrome);
            markNativeHeading();
            var shell = markShell();
            adaptHeader();
            adaptStudyPages();
            guarded(function () { window.__univerStudyV1?.apply(selectedLanguage); });
            guarded(function () { window.__univerAttendanceV1?.apply(selectedLanguage); });
            adaptSchedules();
            guarded(function () { window.__univerSyllabusV1?.apply(selectedLanguage, Array.from(schedules.values())); });
            adaptRecords();
            markNewsTables();
            markDataTables();
            dialogViewport();
            adaptDialogs();
            foldHelp();
            adaptMenu();
            wrapLayoutTables(shell);
            adaptUsability();
            // Page modules run after tables are classified and wrapped, so they can hide whole source blocks.
            guarded(function () { window.__univerTermsV1?.apply(selectedLanguage); });
            guarded(function () { window.__univerAttestationV1?.apply(selectedLanguage); });
            guarded(function () { window.__univerDiscussionV1?.apply(selectedLanguage); });
            guarded(function () { window.__univerFoldsV1?.apply(selectedLanguage); });
            guarded(function () { window.__univerNewsV1?.apply(selectedLanguage); });
            // Finish for the plain pages (notes, warnings, label and value lists, pagers), shared with Windows.
            guarded(function () { window.__univerPolishV1?.apply(selectedLanguage); });
            guarded(function () { window.__univerScreensV1?.apply(selectedLanguage); });
            adaptFrames();
            guarded(function () { window.__univerTablesV1?.fit(); });
            scrollHints();
        } finally {
            window.__univerPresentationV1?.release();
            if (enabled) observer?.observe(document.documentElement, OBSERVED);
        }
    }
    // One page module failing (an older engine missing a selector or DOM method it uses) must not cost the rest of
    // the pass; the error is still reported, once the pass is done.
    function guarded(action) {
        try { action(); } catch (error) { setTimeout(function () { throw error; }); }
    }
    // An adapter-owned change made outside a pass (a tile following the finger, a menu search) starts no pass of its
    // own: its mutation records are dropped. Records the site queued before it still schedule one.
    function quiet(action) {
        var pending = observer ? observer.takeRecords() : [];
        try { action(); } finally {
            observer?.takeRecords();
            if (pending.length) schedule();
        }
    }
    function filterMenu() {
        var menu = document.querySelector('.univer-app-menu');
        if (!menu) return;
        var query = (menu.querySelector('input[type=search]')?.value || '').trim().toLocaleLowerCase().replace(/ё/g, 'е');
        var count = 0;
        menu.querySelectorAll('#tools li').forEach(function (item) {
            if (!menuHidden.has(item)) menuHidden.set(item, item.hidden);
            var matches = !menuHidden.get(item) && (!query || item.textContent.toLocaleLowerCase().replace(/ё/g, 'е').includes(query));
            if (item.hidden === matches) item.hidden = !matches;
            if (matches) count++;
        });
        categories.forEach(function (category) {
            var matches = Array.from(category.table.querySelectorAll('li')).some(function (li) { return !li.hidden; });
            if (category.box.hidden !== (!!query && !matches)) category.box.hidden = !!query && !matches;
            // A search opens the groups it found; clearing it returns each group to how the user left it.
            if (query && !('searchOpen' in category)) category.searchOpen = category.box.open;
            if (query && matches && !category.box.open) category.box.open = true;
            if (!query && 'searchOpen' in category) { if (category.box.open !== category.searchOpen) category.box.open = category.searchOpen; delete category.searchOpen; }
        });
        var empty = menu.querySelector('.univer-app-empty');
        if (empty.textContent !== emptyLabel) empty.textContent = emptyLabel;
        if (empty.hidden !== (!query || count > 0)) empty.hidden = !query || count > 0;
    }
    function foldInfo(node, kind) {
        if (node.closest('.univer-app-info')) return;
        var details = document.createElement('details'); details.className = 'univer-app-info';
        details.dataset.univerInfo = kind;
        var summary = document.createElement('summary'); summary.textContent = infoTitles[kind];
        details.appendChild(summary); node.parentNode.insertBefore(details, node); details.appendChild(node);
    }
    function schedule() {
        if (queued || !enabled) return;
        queued = true;
        // Adapt before the next frame: late AJAX content must not paint in the original layout first.
        // The timer is a fallback for hidden views, where animation frames are throttled.
        var run = function () { if (queued) adapt(); };
        if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run);
        setTimeout(run, 100);
    }
    // Classes the adapter puts on site nodes; Original removes every one of them.
    var RECORD_CLASSES = ['univer-app-pairs','univer-app-form-table','univer-app-cards','univer-app-card-header',
        'univer-app-card-row','univer-app-card-section','univer-app-long-field','univer-app-blank-field','univer-app-matrix',
        'univer-app-nav-table','univer-app-list','univer-app-document-layout','univer-app-sort-head','univer-app-breadcrumb',
        'univer-app-gpa-layout','univer-app-catalog-title','univer-app-catalog-head','univer-app-sort-collapsed','univer-app-faq-row',
        'univer-app-video','univer-app-media-column','univer-app-content-columns','univer-app-empty-result','univer-app-calendar-meta','univer-app-empty-nav-cell','univer-app-div-records','univer-app-schedule-controls','univer-app-schedule-grid-visible',
        'univer-app-schedule-content','univer-app-empty-panel','univer-app-secondary-heading','univer-app-schedule-title','univer-app-print-action','univer-app-form-spacer',
        'univer-app-header-row','univer-app-semester-link','univer-app-empty-note','univer-app-action-link','univer-app-empty-row',
        'univer-app-today-col','univer-app-news-pager-source'];
    var MODULE_CLASSES = ['univer-app-schedule-source','univer-app-courses','univer-app-materials','univer-app-page-link','univer-app-study-panel','univer-app-study-empty'];
    var SHELL_CLASSES = ['univer-app-shell','univer-app-news','univer-app-data','univer-app-data-field'];
    var MARKER_ATTRIBUTES = ['data-univer-field','data-univer-action','data-univer-time','data-univer-course-kind'];
    function removeClasses(classes) {
        document.querySelectorAll(classes.map(function (c) { return '.' + c; }).join(',')).forEach(function (node) { node.classList.remove(...classes); });
    }
    // Moved site nodes go back to their recorded places, the latest move first.
    function undoMoves(moves) {
        moves.slice().reverse().forEach(function (move) { if (move.home.parentNode) move.home.parentNode.replaceChild(move.node, move.home); });
    }
    function localizeChrome(language) {
        selectedLanguage = language;
        menuTitle = language === 'en' ? 'All sections' : language === 'kz' ? 'Барлық бөлімдер' : 'Все разделы';
        infoTitles = language === 'en' ? {contacts:'KazNU contacts and information', links:'Useful links'} :
            language === 'kz' ? {contacts:'ҚазҰУ байланыстары мен ақпараты', links:'Пайдалы сілтемелер'} :
            {contacts:'Контакты и информация КазНУ', links:'Полезные ссылки'};
        searchLabel = language === 'en' ? 'Find a section…' : language === 'kz' ? 'Бөлімді іздеу…' : 'Найти раздел…';
        emptyLabel = language === 'en' ? 'No sections found' : language === 'kz' ? 'Бөлімдер табылмады' : 'Разделы не найдены';
        closeLabel = language === 'en' ? 'Close' : language === 'kz' ? 'Жабу' : 'Закрыть';
    }
    function enable() {
        enabled = true;
        document.documentElement.classList.add('univer-mobile');
        viewport = document.querySelector('meta[name=viewport]');
        madeViewport = !viewport;
        if (!viewport) { viewport = document.createElement('meta'); viewport.name = 'viewport'; (document.head || document.documentElement).appendChild(viewport); }
        originalViewport = viewport.getAttribute('content');
        viewport.content = 'width=device-width, initial-scale=1';
        var style = document.createElement('style'); style.id = 'univer-app-mobile-style';
        // The dark palette lives in the top window (univer-palette.js); frames of this origin share it.
        var palette = null;
        try { palette = window.top.__univerPaletteV1; } catch (_) { }
        if (palette) palette.fill(style, css); else style.textContent = css;
        (document.head || document.documentElement).appendChild(style);
        observer = new MutationObserver(schedule);
        observer.observe(document.documentElement, OBSERVED);
        window.addEventListener('resize', schedule);
        window.addEventListener('univer-frame-ready', schedule);
        window.visualViewport?.addEventListener('resize', dialogViewport);
        window.visualViewport?.addEventListener('scroll', dialogViewport);
        document.addEventListener('click', dismissMenu); document.addEventListener('keydown', dismissMenu);
    }
    function restoreViewport() {
        if (madeViewport) viewport.remove();
        else if (originalViewport === null) viewport.removeAttribute('content');
        else viewport.setAttribute('content', originalViewport);
    }
    function restoreMenu() {
        document.querySelectorAll('#tools li').forEach(function (li) { if (menuHidden.has(li)) li.hidden = menuHidden.get(li); });
        menuHidden = new WeakMap();
        categories.forEach(function (category) {
            if (category.home.parentNode) category.home.parentNode.replaceChild(category.table, category.home);
            category.box.remove();
        }); categories = [];
        document.querySelectorAll('.univer-app-menu-footer').forEach(function (row) { row.classList.remove('univer-app-menu-footer'); });
        document.querySelectorAll('.univer-app-menu').forEach(function (box) {
            var table = box.querySelector('#tools');
            if (table && menuHome?.parentNode) { menuHome.parentNode.replaceChild(table, menuHome); box.remove(); }
            else if (table) box.replaceWith(table);
        });
        menuHome = null;
        document.querySelectorAll('.univer-app-info').forEach(function (box) {
            box.querySelector('summary')?.remove(); box.replaceWith(...box.childNodes);
        });
    }
    function restoreDialogs() {
        document.querySelectorAll('[data-univer-close]').forEach(function (close) {
            close.title = close.dataset.univerOldTitle;
            if (close.dataset.univerOldLabel) close.setAttribute('aria-label', close.dataset.univerOldLabel); else close.removeAttribute('aria-label');
            if (close.dataset.univerOldRole) close.setAttribute('role', close.dataset.univerOldRole); else close.removeAttribute('role');
            if (close.dataset.univerOldTab) close.setAttribute('tabindex', close.dataset.univerOldTab); else close.removeAttribute('tabindex');
            close.removeEventListener('keydown', close.__univerKeydown); delete close.__univerKeydown;
            delete close.dataset.univerClose; delete close.dataset.univerOldTitle; delete close.dataset.univerOldLabel;
            delete close.dataset.univerOldRole; delete close.dataset.univerOldTab;
        });
        document.querySelectorAll('.univer-app-modal-toolbar').forEach(function (bar) {
            var close = bar.querySelector('.simplemodal-close'), home = bar.__univerCloseHome;
            if (close && home?.parentNode) home.parentNode.replaceChild(close, home);
            bar.remove();
        });
    }
    // Original: every class, attribute, node and listener the adapter added goes, and moved site nodes return.
    function disable(language) {
        enabled = false;
        if (observer) observer.disconnect();
        document.removeEventListener('click', dismissMenu); document.removeEventListener('keydown', dismissMenu);
        window.removeEventListener('resize', schedule);
        window.removeEventListener('univer-frame-ready', schedule);
        window.visualViewport?.removeEventListener('resize', dialogViewport);
        window.visualViewport?.removeEventListener('scroll', dialogViewport);
        ['--univer-dialog-top','--univer-dialog-height','--univer-frame-height'].forEach(function (key) { document.documentElement.style.removeProperty(key); });
        document.documentElement.classList.remove('univer-mobile','univer-app-document','univer-native-chrome','univer-app-schedule-page','univer-app-unified');
        window.__univerMaterialsV1?.release();
        window.__univerSyllabusV1?.release();
        document.querySelectorAll('.univer-app-native-heading').forEach(function (node) { node.classList.remove('univer-app-native-heading'); });
        punctuation.forEach(function (value, node) { node.data = value; }); punctuation.clear();
        removeClasses(RECORD_CLASSES);
        document.getElementById('univer-app-mobile-style')?.remove();
        undoMoves(headerMoves); headerMoves = [];
        document.querySelectorAll('.univer-app-header,.univer-app-account,.univer-app-course-open').forEach(function (node) { node.remove(); });
        schedules.forEach(function (record) { record.view.remove(); }); schedules.clear();
        weekViews.forEach(function (view, select) { select.removeEventListener('change', view.update); if (view.nav.parentNode) view.nav.replaceWith(select); }); weekViews.clear();
        if (upcomingTimer) { clearTimeout(upcomingTimer); upcomingTimer = 0; }
        finishedWeek = null;
        MARKER_ATTRIBUTES.forEach(function (name) { document.querySelectorAll('[' + name + ']').forEach(function (node) { node.removeAttribute(name); }); });
        document.querySelectorAll('[data-univer-label]').forEach(function (node) { node.removeAttribute('aria-label'); node.removeAttribute('data-univer-label'); });
        removeClasses(MODULE_CLASSES);
        restoreViewport();
        window.__univerTablesV1?.release();
        document.querySelectorAll('.univer-app-table-scroll').forEach(function (box) { box.replaceWith(...box.childNodes); });
        document.querySelectorAll('.univer-app-scroll-hint').forEach(function (hint) { hint.remove(); });
        document.querySelectorAll('.univer-app-catalog tr.file').forEach(function (row) { if (catalogHidden.has(row)) row.hidden = catalogHidden.get(row); }); catalogHidden = new WeakMap();
        undoMoves(usabilityMoves); usabilityMoves = [];
        pageMarkers.forEach(function (value, node) { if (value === null) node.removeAttribute('aria-current'); else node.setAttribute('aria-current', value); }); pageMarkers.clear();
        [window.__univerNewsV1, window.__univerShortcutsV1, window.__univerStudyV1, window.__univerAttendanceV1,
            window.__univerTermsV1, window.__univerAttestationV1, window.__univerDiscussionV1, window.__univerFoldsV1, window.__univerPolishV1, window.__univerScreensV1].forEach(function (module) { module?.release(); });
        document.querySelectorAll('.univer-app-menu-close,.univer-app-menu-collapse,.univer-app-quick,.univer-app-menu-return,.univer-app-catalog,.univer-app-catalog-search,.univer-app-catalog-empty,.univer-app-catalog-download,.univer-app-faq,.univer-app-sort-toggle,.univer-app-semester-nav,.univer-app-academic-meta,.univer-app-video-link').forEach(function (node) { node.remove(); });
        restoreMenu();
        restoreDialogs();
        childAdapters.forEach(function (win) { try { win.__univerMobileV1?.setEnabled(false, language); } catch (_) {} });
        childAdapters.clear();
        document.querySelectorAll('iframe,frame').forEach(function (frame) {
            try {
                if (frame.contentWindow.location.origin === location.origin)
                    frame.contentWindow.__univerPresentationV1?.release();
            } catch (_) { /* External documents are untouched. */ }
        });
        removeClasses(SHELL_CLASSES);
    }
    function setEnabled(value, language) {
        // AJAX is handled by the observer. Repeated native renders need no full DOM/layout traversal.
        if (enabled === !!value && selectedLanguage === language) {
            if (enabled) dialogViewport();
            return;
        }
        localizeChrome(language);
        if (value && !enabled) enable();
        else if (!value && enabled) disable(language);
        adapt();
        // «Наверх» after a long scroll, sized for the thumb and kept in its corner; Original has none.
        window.__univerTopV1?.configure(enabled, language, {edge:16, height:48, anchored:true});
    }
    window.__univerMobileV1 = {setEnabled:setEnabled, mutate:function (action) {
        observer?.disconnect();
        try { action(); } finally { schedule(); }
    }, quiet:quiet, setNativeChrome:function (value) {
        nativeChrome = !!value;
        document.documentElement.classList.toggle('univer-native-chrome', enabled && nativeChrome);
    }};
})();
