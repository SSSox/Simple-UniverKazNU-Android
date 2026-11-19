/* Home shortcuts shared with Windows: fixed study tiles with "СДО Moodle", student-pinned sections and useful links,
   and the useful links list inside "All sections". Only a real click pins or unpins; the app keeps the list. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerShortcutsV1) return;
    var dom = window.__univerDomV1, cleanText = dom.clean, el = dom.el;
    // A button of the module's own panels, named for screen readers when its face is only an icon.
    function control(className, label) {
        var button = el('button', className); button.type = 'button';
        if (label) button.setAttribute('aria-label', label);
        return button;
    }
    // A mark drawn by CSS (a plus, an icon) that a screen reader skips.
    function decor(className) { var mark = el('span', className); mark.setAttribute('aria-hidden', 'true'); return mark; }
    var pins = [], pinsKey = '[]', tileOrder = [], orderKey = '[]', draggedTile = null, language = 'ru', active = false, owned = [], pinButtons = new Set(), menuRows = new Map(), style = null;
    // Built-in tiles the student removed (canonical addresses); a pin button in "All sections" brings them back.
    var hiddenTiles = [], hiddenKey = '[]', trash = null, adding = null;
    var moodlePath = '/user/loginMoodle';
    var tilePaths = /^\/(student\/(myschedule|attendance\/full|transcript|umkd)|user\/loginMoodle|ssc)$/i;
    // Study tiles in the order students look for them; the site menu supplies their real links and names.
    // The news page menu has no study sections, so the observed routes are used directly there.
    var fixed = [['schedule', '/student/myschedule/'], ['grades', '/student/attendance/full/'], ['transcript', '/student/transcript/'], ['umkd', '/student/umkd/']];
        // Tiles open Главная and Бакалавр; Е-услуги is the services desk reached from its own tile.
    // The site lands on /news/student/1/ after sign-in: page 1 of the news is the home page as well.
    var homePages = /^\/news\/student(?:\/1)?\/?$|^\/student\/bachelor\/?$/i;
    // The home tiles' stylesheet lives in univer-shortcuts-css.js, loaded just before this file.
    var css = window.__univerShortcutsCssV1 || '';
    // Stroke icons as CSS masks: no network requests, and each family keeps one tint so links are told apart at a glance.
    function svg(body, fill) { return 'url("data:image/svg+xml,' + encodeURIComponent("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='" + (fill ? 'black' : 'none') + "' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>" + body + '</svg>') + '")'; }
    var glyphs = {
        calendar:svg("<rect x='4' y='5' width='16' height='16' rx='2'/><path d='M4 10h16M8 3v4M16 3v4'/>"),
        grades:svg("<path d='M4 20V10M10 20V4M16 20v-7M22 20H2'/>"),
        transcript:svg("<path d='M14 3H6v18h12V7z'/><path d='M14 3v4h4M9 12h6M9 16h6'/>"),
        book:svg("<path d='M4 19V5a2 2 0 0 1 2-2h14v16H6a2 2 0 0 0-2 2z'/><path d='M8 7h8'/>"),
        open:svg("<path d='M2 5h7a3 3 0 0 1 3 3v13a2 2 0 0 0-2-2H2zM22 5h-7a3 3 0 0 0-3 3v13a2 2 0 0 1 2-2h8z'/>"),
        cap:svg("<path d='M2 9l10-5 10 5-10 5z'/><path d='M6 11v5c2 2 10 2 12 0v-5M22 9v6'/>"),
        mail:svg("<rect x='3' y='5' width='18' height='14' rx='2'/><path d='M3 7l9 6 9-6'/>"),
        services:svg("<rect x='4' y='4' width='6' height='6' rx='1'/><rect x='14' y='4' width='6' height='6' rx='1'/><rect x='4' y='14' width='6' height='6' rx='1'/><rect x='14' y='14' width='6' height='6' rx='1'/>"),
        news:svg("<path d='M4 5h13v14H6a2 2 0 0 1-2-2z'/><path d='M17 9h3v8a2 2 0 0 1-2 2M8 9h5M8 13h5'/>"),
        help:svg("<circle cx='12' cy='12' r='9'/><path d='M9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6M12 17h.01'/>"),
        folder:svg("<path d='M3 19V5h6l2 3h10v11z'/>"),
        calc:svg("<rect x='5' y='3' width='14' height='18' rx='2'/><path d='M8 7h8M8 12h.01M12 12h.01M16 12h.01M8 16h.01M12 16h.01M16 16h.01'/>"),
        list:svg("<path d='M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01'/>"),
        play:svg("<circle cx='12' cy='12' r='9'/><path d='M10 8.5l5 3.5-5 3.5z'/>"),
        youtube:svg("<rect x='2' y='5' width='20' height='14' rx='4'/><path d='M10 9l5 3-5 3z'/>"),
        facebook:svg("<path d='M15 3h-2.5A3.5 3.5 0 0 0 9 6.5V10H6v4h3v7h4v-7h3l1-4h-4V7a1 1 0 0 1 1-1h2z'/>"),
        twitter:svg("<path d='M4 4l16 16M20 4L4 20'/>"),
        phone:svg("<rect x='7' y='2' width='10' height='20' rx='2'/><path d='M11 18h2'/>"),
        journal:svg("<path d='M6 3h12v18H6z'/><path d='M9 7h6M9 11h6M9 15h3'/>"),
        idea:svg("<path d='M9 18h6M10 21h4'/><path d='M12 3a6 6 0 0 0-4 10.5c.8.7 1 1.5 1 2.5h6c0-1 .2-1.8 1-2.5A6 6 0 0 0 12 3z'/>"),
        wifi:svg("<path d='M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01'/>"),
        globe:svg("<circle cx='12' cy='12' r='9'/><path d='M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18'/>"),
        person:svg("<circle cx='12' cy='8' r='4'/><path d='M4 21c1.6-4 4.6-6 8-6s6.4 2 8 6'/>"),
        key:svg("<circle cx='8' cy='15' r='4'/><path d='M11 12l9-9M17 6l3 3'/>"),
        home:svg("<path d='M3 11l9-8 9 8'/><path d='M5 10v11h5v-6h4v6h5V10'/>"),
        link:svg("<path d='M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1'/><path d='M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1'/>")
    };
    // App-style icons: a solid colour square with a white mark; well-known services keep their own colours and marks.
    function fill(body) { return 'url("data:image/svg+xml,' + encodeURIComponent("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='black'>" + body + '</svg>') + '")'; }
    function art(body) { return 'url("data:image/svg+xml,' + encodeURIComponent("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>" + body + '</svg>') + '")'; }
    glyphs.facebook = fill("<path d='M13.4 21v-7.6h2.6l.4-3h-3V8.5c0-.9.3-1.5 1.6-1.5H16.6V4.3a21 21 0 0 0-2.4-.1c-2.4 0-4 1.4-4 4.1v2.1H7.6v3h2.6V21z'/>");
    glyphs.youtube = fill("<path d='M9.5 7.8v8.4l7-4.2z'/>");
    glyphs.twitter = fill("<path d='M17.6 3.5h2.9l-6.3 7.2 7.4 9.8h-5.8l-4.5-5.9-5.2 5.9H3.2l6.7-7.7L2.8 3.5h5.9l4.1 5.4zm-1 15.3h1.6L7.6 5.1H5.9z'/>");
    glyphs.appstore = svg("<path d='M10.5 6.5l5.5 10M13.5 6.5L8 16.5M6 13.5h12M15.8 16.2l1.2 2.3'/>");
    glyphs.moodle = svg("<path d='M5.5 18v-5a2.7 2.7 0 0 1 5.4 0v5M10.9 13a2.7 2.7 0 0 1 5.4 0v5M8 7.5l6-2.5 6 2.5-6 2.5zM20 7.5v3.5'/>");
    var arts = {
        googleplay: art("<path fill='#00a0ff' d='M4.6 2.9 13.4 12l-8.8 9.1a1.3 1.3 0 0 1-.6-1.1V4a1.3 1.3 0 0 1 .6-1.1z'/><path fill='#00e676' d='M4.6 2.9 16.4 9.2 13.4 12z'/><path fill='#ff3d44' d='M13.4 12l3 2.8-11.8 6.3z'/><path fill='#ffc400' d='M16.4 9.2l3.3 1.8a1.1 1.1 0 0 1 0 2l-3.3 1.8-3-2.8z'/>")
    };
    var tints = {brand:'#1b5fa6', teal:'#0f766e', amber:'#b45309', violet:'#6d28d9', navy:'#14325c', green:'#15803d', ink:'#334155',
        facebook:'#1877f2', youtube:'#e62117', twitter:'#0f1419', apple:'#0d84ff', moodle:'#f98012', white:'#ffffff'};
    function glyphFor(href, title) {
        var url; try { url = new URL(href, location.href); } catch (_) { return ['link', 'brand']; }
        var host = url.hostname.toLowerCase(), p = url.pathname.toLowerCase(), t = (title || '').toLowerCase();
        if (url.origin === location.origin) {
            if (/^\/student\/myschedule/.test(p)) return ['calendar', 'brand'];
            if (/^\/student\/attendance/.test(p)) return ['grades', 'teal'];
            if (/^\/student\/transcript/.test(p)) return ['transcript', 'violet'];
            if (/^\/student\/umkd/.test(p)) return ['book', 'amber'];
            if (/moodle/.test(p)) return ['moodle', 'moodle'];
            if (/messages/.test(p)) return ['mail', 'brand'];
            if (/^\/ssc\/statementlist/.test(p)) return ['transcript', 'teal'];
            if (/^\/ssc/.test(p)) return ['services', 'brand'];
            if (/news|новост|жаңалық/.test(p + t)) return ['news', 'brand'];
            if (/faq|вопрос|сұрақ|question/.test(p + t)) return ['help', 'brand'];
            if (/calc|gpa/.test(p + t)) return ['calc', 'violet'];
            if (/catalog|каталог/.test(p + t)) return ['list', 'amber'];
            if (/doc|документ|құжат/.test(p + t)) return ['folder', 'amber'];
            if (/guide|руководств|нұсқаулы|manual/.test(p + t)) return ['book', 'brand'];
            return ['link', 'brand'];
        }
        if (/facebook\.com$/.test(host)) return ['facebook', 'facebook'];
        if (/youtube\.com$|youtu\.be$/.test(host)) return ['youtube', 'youtube'];
        if (/twitter\.com$|(^|\.)x\.com$/.test(host)) return ['twitter', 'twitter'];
        if (/apps\.apple\.com$/.test(host)) return ['appstore', 'apple'];
        if (/play\.google\.com$/.test(host)) return ['googleplay', 'white'];
        if (/moodle/.test(host + t)) return ['moodle', 'moodle'];
        if (/wifi|wi-fi/.test(host + p + t)) return ['wifi', 'green'];
        if (/kaizen|кайдзен/.test(host + t)) return ['idea', 'amber'];
        if (/oxford|journal|журнал/.test(host + t)) return ['journal', 'navy'];
        if (/lib|эбс|ebs|библиот|кітапхана|read\.kz|oqylyq/.test(host + t)) return ['open', 'amber'];
        if (/open\.kaznu|omc\.|master|видео|video/.test(host + t)) return ['play', 'brand'];
        return ['globe', 'brand'];
    }
    function linkIcon(href, title) {
        var pick = glyphFor(href, title), i = document.createElement('span');
        i.className = 'univer-app-link-icon'; i.setAttribute('aria-hidden', 'true');
        if (path(href) !== null) i.classList.add('univer-app-link-internal');
        i.style.setProperty('--tint', tints[pick[1]] || tints.brand);
        if (arts[pick[0]]) { i.classList.add('univer-app-link-art'); i.style.setProperty('--art', arts[pick[0]]); }
        else i.style.setProperty('--glyph', glyphs[pick[0]] || glyphs.link);
        return i;
    }
    function pinIcon(fill) {
        return 'url("data:image/svg+xml,' + encodeURIComponent("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='" + (fill ? 'black' : 'none') +
            "' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M9 4h6l-1 6 4 4H6l4-4z'/><path d='M12 14v7'/></svg>") + '")';
    }
    function text() {
        return language === 'en' ? {add:'New section', findSection:'Find a section', close:'Close', sections:'Sections', removed:'Removed from the home screen', noMatch:'No sections found', allPinned:'Every section is already on the home screen', useful:'Useful links', schedule:'Schedule', grades:'Grades', transcript:'Transcript', umkd:'Course materials', moodle:'Moodle LMS', eservices:'E-services', pin:'Pin to home screen', unpin:'Unpin from home screen', remove:'Unpin', trash:'Remove from home'} :
            language === 'kz' ? {add:'Жаңа бөлім', findSection:'Бөлімді табу', close:'Жабу', sections:'Бөлімдер', removed:'Басты беттен алынғандар', noMatch:'Бөлімдер табылмады', allPinned:'Барлық бөлім басты бетте', useful:'Пайдалы сілтемелер', schedule:'Сабақ кестесі', grades:'Үлгерім', transcript:'Транскрипт', umkd:'ОӘК', moodle:'Moodle ҚОЖ', eservices:'Е-қызметтер', pin:'Басты бетке бекіту', unpin:'Басты беттен алып тастау', remove:'Бекітуден алу', trash:'Басты беттен алып тастау'} :
            {add:'Новый раздел', findSection:'Найти раздел', close:'Закрыть', sections:'Разделы', removed:'Убрано с главной', noMatch:'Разделы не найдены', allPinned:'Все разделы уже на главной', useful:'Полезные ссылки', schedule:'Расписание', grades:'Успеваемость', transcript:'Транскрипт', umkd:'УМКД', moodle:'СДО Moodle', eservices:'Е-услуги', pin:'Закрепить на главной', unpin:'Открепить с главной', remove:'Открепить', trash:'Убрать с главной'};
    }
    function own(node) { owned.push(node); return node; }
    // A change of the module's own UI between adapter passes (a tile following the finger, the useful links filter)
    // must not make the mobile adapter run a whole pass for it.
    function quiet(action) { var mobile = window.__univerMobileV1; if (mobile?.quiet) mobile.quiet(action); else action(); }
    function path(href) { try { var url = new URL(href, location.href); return url.origin === location.origin ? url.pathname.replace(/\/+$/, '') || '/' : null; } catch (_) { return null; } }
    function pinnable(a) {
        try { var url = new URL(a.href); } catch (_) { return false; }
        if (url.origin === location.origin) return !/^\/user\/(logout|login)\/?$/i.test(url.pathname);
        return (url.protocol === 'https:' || url.protocol === 'http:') && !url.username && !url.password;
    }
    function fixedHomeLink(href) {
        try { var url = new URL(href, location.href); } catch (_) { return false; }
        return url.origin === location.origin && !url.search && !url.hash && /^\/(student\/(myschedule|attendance\/full|transcript|umkd)|user\/loginMoodle|ssc(\/UserServiceList|\/StatementList)?)$/i.test(url.pathname.replace(/\/+$/, ''));
    }
    // The canonical address of a built-in home tile (same spelling the app stores), or null for any other link.
    function tileKey(href) {
        try { var url = new URL(href, location.href); } catch (_) { return null; }
        if (url.origin !== location.origin || url.search || url.hash) return null;
        var p = url.pathname.replace(/\/+$/, '');
        return tilePaths.test(p) ? location.origin + p.toLowerCase() : null;
    }
    function pinned(href) {
        var key = tileKey(href);
        return key ? hiddenTiles.indexOf(key) < 0 : pins.some(function (p) { return p.url === href; });
    }
    function notify(value) {
        // Only the pin request crosses to native code, which validates and stores it; no callable host objects.
        if (typeof __univerNative === 'function') __univerNative(value);
        else if (window.CefSharp) CefSharp.PostMessage(value);
    }
    function request(event, pin, title, href) {
        event.preventDefault(); event.stopPropagation();
        // Scripted clicks and frames cannot change the student's home screen.
        var key = tileKey(href);
        if (!event.isTrusted || window !== window.top || (!key && fixedHomeLink(href))) return;
        if (key) {
            // A built-in tile is removed by hiding it, and returns when it is pinned again.
            hiddenTiles = pin ? hiddenTiles.filter(function (k) { return k !== key; }) : hiddenTiles.indexOf(key) < 0 ? hiddenTiles.concat([key]) : hiddenTiles;
            hiddenKey = JSON.stringify(hiddenTiles);
        } else {
            if (pin && !pinned(href)) pins = pins.concat([{title:title, url:href}]);
            if (!pin) pins = pins.filter(function (p) { return p.url !== href; });
        }
        notify('univer-pin:' + JSON.stringify({pin:pin, title:title, url:href}));
        // The tap shows at once, before the app's stored list comes back through configure().
        menuPinsDrawn = null;
        render();
    }
    function usefulSource() {
        return Array.from(document.querySelectorAll('.links')).find(function (links) {
            return links.querySelectorAll('a').length >= 8 && links.querySelector('a[href*="apps.apple.com"]') && links.querySelector('a[href*="play.google.com"]');
        });
    }
    function pinButton(li, a, title) {
        // Built-in tiles are pinned and unpinned like any other link; the other fixed links never are.
        if (fixedHomeLink(a.href) && !tileKey(a.href)) return;
        var button = li.querySelector(':scope>.univer-app-pin');
        if (!button) {
            button = document.createElement('button'); button.type = 'button'; button.className = 'univer-app-pin';
            button.addEventListener('click', function (event) { request(event, button.getAttribute('aria-pressed') !== 'true', button.__univerTitle, button.__univerHref); });
            a.after(button); pinButtons.add(button);
        }
        var on = pinned(a.href), label = (on ? text().unpin : text().pin) + ': ' + title;
        button.__univerTitle = title; button.__univerHref = a.href;
        if (button.getAttribute('aria-pressed') !== String(on)) button.setAttribute('aria-pressed', String(on));
        if (button.getAttribute('aria-label') !== label) { button.setAttribute('aria-label', label); button.title = label; }
    }
    // Every pass (each AJAX change, the keyboard) comes here; the menu's links seldom change, so the pin buttons are
    // redrawn only when the links, the pins or the language do.
    var menuPinsDrawn = null;
    function menuPins(menu) {
        var anchors = Array.from(menu.querySelectorAll('#tools li a[href]')), signature = [language, pinsKey, hiddenKey].join('|');
        var drawn = menuPinsDrawn;
        if (drawn && drawn.signature === signature && drawn.anchors.length === anchors.length &&
            anchors.every(function (a, i) { return a === drawn.anchors[i] && a.href === drawn.hrefs[i] && a.parentNode === drawn.rows[i]; })) return;
        menuPinsDrawn = null;
        anchors.forEach(function (a) {
            if (!pinnable(a) || !cleanText(a)) return;
            var entry = menuRows.get(a);
            if (!entry) {
                var row = document.createElement('span'), label = document.createElement('span');
                row.className = 'univer-app-pin-row'; label.className = 'univer-app-menu-label';
                a.before(row); row.appendChild(a);
                while (a.firstChild) label.appendChild(a.firstChild);
                a.appendChild(label); entry = {row:row, label:label}; menuRows.set(a, entry);
            }
            pinButton(entry.row, a, cleanText(a));
        });
        menuPinsDrawn = {signature:signature, anchors:anchors, hrefs:anchors.map(function (a) { return a.href; }), rows:anchors.map(function (a) { return a.parentNode; })};
    }
    function usefulBox(menu) {
        var source = usefulSource(), box = menu.querySelector('.univer-app-useful');
        if (!source) { box?.remove(); return; }
        var anchors = Array.from(source.querySelectorAll('a[href]')).filter(function (a) { return cleanText(a) && pinnable(a); });
        var key = language + '|' + anchors.map(function (a) { return a.href + ' ' + cleanText(a); }).join('|');
        if (!box || box.__univerKey !== key) {
            box?.remove();
            box = own(document.createElement('details')); box.className = 'univer-app-category univer-app-useful'; box.open = false; box.__univerKey = key;
            var summary = document.createElement('summary'); summary.textContent = text().useful;
            var list = document.createElement('ul'); box.append(summary, list);
            anchors.forEach(function (original) {
                var li = document.createElement('li'), a = document.createElement('a');
                a.href = original.href; a.append(linkIcon(original.href, cleanText(original)), document.createTextNode(cleanText(original)));
                if (path(original.href) === null) { a.target = '_blank'; a.rel = 'noopener'; }
                // The site's own anchor keeps any handler and target it has.
                a.addEventListener('click', function (event) { event.preventDefault(); original.click(); });
                li.appendChild(a); list.appendChild(li);
            });
            // Kept outside #tools: the site menu's current-page marks and the window title read only real sections.
            menu.insertBefore(box, menu.querySelector(':scope>.univer-app-empty'));
            var search = menu.querySelector('input[type=search]');
            if (search && !search.__univerUseful) { search.__univerUseful = true; search.addEventListener('input', function () { quiet(function () { filterUseful(menu); }); }); }
        }
        box.querySelectorAll('li').forEach(function (li) { var a = li.querySelector('a'); pinButton(li, a, cleanText(a)); });
        filterUseful(menu);
    }
    function filterUseful(menu) {
        var box = menu.querySelector('.univer-app-useful'); if (!box) return;
        var query = (menu.querySelector('input[type=search]')?.value || '').trim().toLocaleLowerCase().replace(/ё/g, 'е'), any = false;
        box.querySelectorAll('li').forEach(function (li) {
            var match = !query || li.textContent.toLocaleLowerCase().replace(/ё/g, 'е').includes(query);
            if (li.hidden === match) li.hidden = !match; any = any || match;
        });
        if (box.hidden !== !any) box.hidden = !any;
        if (query && any && !box.open) box.open = true;
        var empty = menu.querySelector(':scope>.univer-app-empty');
        if (empty && any && query && !empty.hidden) empty.hidden = true;
    }
    // The site's own menu link, looked up at click time: the menu can still be filling in when tiles are drawn.
    function siteLink(href) { return Array.from(document.querySelectorAll('.univer-app-menu #tools a[href]')).find(function (a) { return a.href === href || (path(href) !== null && path(a.href) === path(href) && new URL(a.href).search === new URL(href, location.href).search); }); }
    // The drop zone shown while a tile is dragged: dropping on it removes the tile from the home screen.
    function trashZone() {
        if (trash?.isConnected) return trash;
        trash = own(document.createElement('div')); trash.className = 'univer-app-trash'; trash.setAttribute('aria-hidden', 'true');
        var icon = document.createElement('span'), label = document.createElement('span');
        icon.className = 'univer-app-trash-icon'; label.className = 'univer-app-trash-label';
        trash.append(icon, label);
        // dragover repeats while the pointer rests; only a real change writes the class the adapter observes.
        trash.addEventListener('dragover', function (event) { if (!draggedTile) return; event.preventDefault(); event.dataTransfer.dropEffect = 'move'; if (!trash.classList.contains('univer-app-trash-hot')) trash.classList.add('univer-app-trash-hot'); });
        trash.addEventListener('dragleave', function (event) { if (!trash.contains(event.relatedTarget)) trash.classList.remove('univer-app-trash-hot'); });
        trash.addEventListener('drop', function (event) {
            if (!event.isTrusted || !draggedTile) return;
            event.preventDefault(); event.stopPropagation();
            var node = draggedTile; showTrash(false); draggedTile = null; discardTile(node, true);
        });
        document.body.appendChild(trash);
        return trash;
    }
    function showTrash(on) {
        var zone = trashZone();
        zone.querySelector('.univer-app-trash-label').textContent = text().trash;
        if (!on) zone.classList.remove('univer-app-trash-hot');
        document.documentElement.classList.toggle('univer-app-tile-drag', on);
    }
    function overTrash(x, y) {
        if (!trash?.isConnected) return false;
        var r = trash.getBoundingClientRect();
        return x >= r.left - 12 && x <= r.right + 12 && y >= r.top - 12 && y <= r.bottom + 12;
    }
    // Removes a tile: pinned links are unpinned, built-in tiles are hidden until pinned again in "All sections".
    function discardTile(span, animate) {
        var a = span.querySelector('a'), title = span.querySelector('.univer-app-tile-label')?.textContent || '';
        if (!a || span.classList.contains('univer-app-leaving')) return;
        // The tile shrinks away first, then it is removed and the others close the gap.
        var sent = false, href = a.href;
        var done = function () {
            if (sent) return; sent = true;
            request({preventDefault:function () {}, stopPropagation:function () {}, isTrusted:true}, false, title, href);
        };
        if (!animate || window.__univerMotion === false || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches || !span.animate) { done(); return; }
        span.classList.add('univer-app-leaving');
        var motion = span.animate([{opacity:1, transform:'none'}, {opacity:0, transform:'scale(.97)'}], {duration:140, easing:'cubic-bezier(.23,1,.32,1)', fill:'forwards'});
        motion.onfinish = motion.oncancel = done;
        // A view that is not being drawn runs no animation frames; the removal must not wait for one.
        setTimeout(done, 400);
    }
    function tile(title, href, removable) {
        var span = document.createElement('span'); span.className = 'univer-app-tile';
        var a = document.createElement('a'), label = document.createElement('span'); a.href = href; label.className = 'univer-app-tile-label'; label.textContent = title;
        a.append(linkIcon(href, title), label); span.appendChild(a);
        if (path(href) === null) { a.target = '_blank'; a.rel = 'noopener'; span.classList.add('univer-app-external'); }
        a.addEventListener('click', function (event) { var original = siteLink(href); if (original) { event.preventDefault(); original.click(); } });
        var handle = document.createElement('button'); handle.type = 'button'; handle.className = 'univer-app-reorder'; handle.draggable = true;
        handle.title = handle.getAttribute('aria-label') || (language === 'en' ? 'Reorder: drag, tap or use the arrow keys' : language === 'kz' ? 'Ретін өзгерту: сүйреңіз, түртіңіз немесе бағыт пернелерін басыңыз' : 'Переставить: перетащите, коснитесь или используйте стрелки') + ': ' + title;
        handle.setAttribute('aria-label', handle.title);
        handle.setAttribute('aria-expanded', 'false');
        handle.addEventListener('click', function (event) {
            event.preventDefault(); event.stopPropagation();
            if (span.__univerDragged) { span.__univerDragged = false; return; }
            moveMode(span, handle, !span.classList.contains('univer-app-moving'));
        });
        handle.addEventListener('dragstart', function (event) {
            if (!event.isTrusted || window !== window.top) { event.preventDefault(); return; }
            draggedTile = span; event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', 'univer-tile'); span.classList.add('univer-app-dragging');
            showTrash(true);
        });
        handle.addEventListener('dragend', function () {
            draggedTile = null; showTrash(false);
            document.querySelectorAll('.univer-app-dragging,.univer-app-drop-target').forEach(function (node) { node.classList.remove('univer-app-dragging','univer-app-drop-target'); });
        });
        span.addEventListener('dragover', function (event) { if (!draggedTile || draggedTile === span) return; event.preventDefault(); event.dataTransfer.dropEffect = 'move'; if (!span.classList.contains('univer-app-drop-target')) span.classList.add('univer-app-drop-target'); });
        span.addEventListener('dragleave', function (event) { if (!span.contains(event.relatedTarget)) span.classList.remove('univer-app-drop-target'); });
        span.addEventListener('drop', function (event) {
            if (!event.isTrusted || !draggedTile || draggedTile === span) return;
            event.preventDefault(); event.stopPropagation(); span.classList.remove('univer-app-drop-target');
            moveTile(draggedTile, Array.from(span.parentElement.querySelectorAll(':scope>.univer-app-tile')).indexOf(span));
        });
        handle.addEventListener('pointerdown', function (event) { touchReorder(event, span, handle); });
        handle.addEventListener('keydown', function (event) {
            if (!event.isTrusted || !['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End'].includes(event.key)) return;
            event.preventDefault(); event.stopPropagation();
            var nodes = Array.from(span.parentElement.querySelectorAll(':scope>.univer-app-tile')), index = nodes.indexOf(span);
            moveTile(span, event.key === 'Home' ? 0 : event.key === 'End' ? nodes.length - 1 : index + (/Left|Up/.test(event.key) ? -1 : 1));
        });
        span.appendChild(handle);
        longPressReorder(span, a, handle);
        if (removable) {
            span.classList.add('univer-app-pinned');
            var remove = document.createElement('button'); remove.type = 'button'; remove.className = 'univer-app-unpin';
            var label = text().remove + ': ' + title; remove.setAttribute('aria-label', label); remove.title = label;
            remove.addEventListener('click', function (event) {
                event.preventDefault(); event.stopPropagation();
                if (!event.isTrusted) return;
                discardTile(span, event.detail > 0);
            });
            span.appendChild(remove);
        }
        return span;
    }
    // A finger on the handle, or a long press on the tile, lifts it; it follows the finger and settles where dropped.
    // Mouse input keeps HTML drag and drop.
    function liftTile(span, x, y) {
        var quick = span.parentElement, target = null, binned = false;
        span.__univerDragged = false;
        window.__univerMotionUI?.stop(span);
        span.classList.add('univer-app-dragging', 'univer-app-lifted');
        showTrash(true);
        return {
            // Runs for every finger movement: its style and class writes start no adapter pass (quiet).
            move: function (cx, cy) { quiet(function () {
                if (Math.abs(cx - x) + Math.abs(cy - y) > 6) span.__univerDragged = true;
                span.style.setProperty('transform', 'translate(' + (cx - x) + 'px,' + (cy - y) + 'px) scale(1.02)');
                // Over the bin the tile no longer marks a neighbour: dropping there removes it.
                binned = overTrash(cx, cy);
                trash.classList.toggle('univer-app-trash-hot', binned);
                if (binned) { if (target) { target.classList.remove('univer-app-drop-target'); target = null; } return; }
                // The lifted tile itself sits under the finger, so look through it for the tile below.
                var under = (document.elementsFromPoint ? document.elementsFromPoint(cx, cy) : [document.elementFromPoint(cx, cy)])
                    .map(function (node) { return node?.closest?.('.univer-app-tile'); })
                    .find(function (tile) { return tile && tile !== span && tile.parentElement === quick; }) || null;
                if (target !== under) { target?.classList.remove('univer-app-drop-target'); target = under; target?.classList.add('univer-app-drop-target'); }
            }); },
            end: function (drop) {
                var dropped = span.getBoundingClientRect();
                span.classList.remove('univer-app-dragging', 'univer-app-lifted'); span.style.removeProperty('transform');
                if (span.getAttribute('style') === '') span.removeAttribute('style');
                target?.classList.remove('univer-app-drop-target');
                showTrash(false);
                if (drop && binned) { target = null; discardTile(span, true); return; }
                if (drop && target?.isConnected) moveTile(span, Array.from(quick.querySelectorAll(':scope>.univer-app-tile')).indexOf(target));
                target = null;
                var after = span.getBoundingClientRect(), dx = dropped.left - after.left, dy = dropped.top - after.top;
                if (!span.animate || window.__univerMotion === false || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches || Math.abs(dx) + Math.abs(dy) < 1) return;
                window.__univerMotionUI?.stop(span);
                span.animate([{transform:'translate(' + dx + 'px,' + dy + 'px)'}, {transform:'none'}], {duration:200, easing:'cubic-bezier(.23,1,.32,1)'});
            }
        };
    }
    function touchReorder(event, span, handle) {
        if (!event.isTrusted || event.pointerType === 'mouse' || window !== window.top || !event.isPrimary) return;
        event.preventDefault();
        var id = event.pointerId, drag = liftTile(span, event.clientX, event.clientY);
        // A touch must not also start the browser's own drag of the draggable handle.
        handle.draggable = false;
        try { handle.setPointerCapture(id); } catch (_) { }
        var move = function (e) { if (e.pointerId === id) drag.move(e.clientX, e.clientY); };
        var end = function (e) {
            if (e.pointerId !== id) return;
            ['pointermove', 'pointerup', 'pointercancel'].forEach(function (name) { handle.removeEventListener(name, name === 'pointermove' ? move : end); });
            handle.draggable = true;
            drag.end(e.type === 'pointerup');
        };
        handle.addEventListener('pointermove', move); handle.addEventListener('pointerup', end); handle.addEventListener('pointercancel', end);
    }
    function longPressReorder(span, link, handle) {
        // A link is natively draggable: Android Chrome would start its own drag on a long press and cancel the touch.
        link.draggable = false;
        link.addEventListener('dragstart', function (event) { event.preventDefault(); });
        link.addEventListener('touchstart', function (event) {
            if (!event.isTrusted || window !== window.top || event.touches.length !== 1 || span.classList.contains('univer-app-moving')) return;
            var touch = event.touches[0], x = touch.clientX, y = touch.clientY, drag = null;
            var timer = setTimeout(function () { drag = liftTile(span, x, y); span.__univerLongPress = true; }, 380);
            var done = function () {
                clearTimeout(timer);
                link.removeEventListener('touchmove', move); link.removeEventListener('touchend', end); link.removeEventListener('touchcancel', end);
            };
            var move = function (e) {
                var point = e.touches[0];
                // Moving before the press settles is a scroll, never a drag.
                if (!drag) { if (Math.abs(point.clientX - x) + Math.abs(point.clientY - y) > 8) done(); return; }
                if (e.cancelable) e.preventDefault();
                drag.move(point.clientX, point.clientY);
            };
            var end = function (e) {
                done();
                if (!drag) return;
                if (e.cancelable) e.preventDefault();
                var moved = span.__univerDragged;
                drag.end(e.type === 'touchend');
                // A cancelled touchend sends no click; clear the guard so the next tap works normally.
                setTimeout(function () { span.__univerLongPress = false; }, 400);
                // Holding without moving opens the edit mode: earlier/later arrows and unpin, like a home screen.
                if (!moved && e.type === 'touchend') moveMode(span, handle, true);
            };
            link.addEventListener('touchmove', move, {passive:false}); link.addEventListener('touchend', end); link.addEventListener('touchcancel', end);
        }, {passive:true});
        // The press that lifted the tile must not also open it or show the link menu.
        link.addEventListener('click', function (event) {
            // In edit mode a tap on the tile closes the mode instead of opening the page.
            if (!span.__univerLongPress && !span.classList.contains('univer-app-moving')) return;
            event.preventDefault(); event.stopImmediatePropagation();
            if (span.__univerLongPress) span.__univerLongPress = false;
            else moveMode(span, handle, false);
        }, true);
        link.addEventListener('contextmenu', function (event) { if (span.__univerLongPress || span.classList.contains('univer-app-lifted')) event.preventDefault(); });
    }
    function moveMode(span, handle, on) {
        var bar = span.querySelector(':scope>.univer-app-move');
        if (on && !bar) {
            bar = document.createElement('span'); bar.className = 'univer-app-move';
            [-1, 1].forEach(function (step) {
                var button = document.createElement('button'); button.type = 'button';
                button.className = step < 0 ? 'univer-app-move-earlier' : 'univer-app-move-later';
                button.addEventListener('click', function (event) {
                    event.preventDefault(); event.stopPropagation();
                    if (!event.isTrusted || button.getAttribute('aria-disabled') === 'true') return;
                    var nodes = Array.from(span.parentElement.querySelectorAll(':scope>.univer-app-tile'));
                    moveTile(span, nodes.indexOf(span) + step);
                    paintMove(span);
                    button.focus({preventScroll:true});
                });
                bar.appendChild(button);
            });
            // Removing needs no drag: keyboard and screen-reader users reach it here.
            var discard = document.createElement('button'); discard.type = 'button'; discard.className = 'univer-app-move-remove';
            var discardLabel = text().trash + ': ' + (span.querySelector('.univer-app-tile-label')?.textContent || '');
            discard.setAttribute('aria-label', discardLabel); discard.title = discardLabel;
            discard.addEventListener('click', function (event) {
                event.preventDefault(); event.stopPropagation();
                if (!event.isTrusted) return;
                discardTile(span, event.detail > 0);
            });
            bar.appendChild(discard);
            span.appendChild(bar);
        }
        span.classList.toggle('univer-app-moving', on);
        handle.setAttribute('aria-expanded', String(on));
        if (on) paintMove(span);
    }
    function paintMove(span) {
        var nodes = Array.from(span.parentElement?.querySelectorAll(':scope>.univer-app-tile') || []), index = nodes.indexOf(span);
        var title = span.querySelector('.univer-app-tile-label')?.textContent || '';
        var words = language === 'en' ? ['Move earlier', 'Move later'] : language === 'kz' ? ['Ертерек жылжыту', 'Кейінірек жылжыту'] : ['Переместить раньше', 'Переместить позже'];
        span.querySelectorAll(':scope>.univer-app-move>button:not(.univer-app-move-remove)').forEach(function (button, i) {
            var label = words[i] + ': ' + title, edge = i === 0 ? index <= 0 : index >= nodes.length - 1;
            if (button.getAttribute('aria-label') !== label) { button.setAttribute('aria-label', label); button.title = label; }
            button.setAttribute('aria-disabled', String(edge));
        });
    }
    function moveTile(node, index) {
        var nodes = Array.from(node.parentElement.querySelectorAll(':scope>.univer-app-tile')), from = nodes.indexOf(node);
        index = Math.max(0, Math.min(nodes.length - 1, index)); if (from < 0 || from === index) return;
        nodes.splice(from, 1); nodes.splice(index, 0, node);
        tileOrder = nodes.map(function (tile) { return tile.querySelector('a').href; }); orderKey = JSON.stringify(tileOrder);
        notify('univer-pin:' + JSON.stringify({order:tileOrder}));
        render();
    }
    // The built-in tiles (through the menu's own links where it has them) and the student's pins they do not repeat.
    function homeKey(href) { return path(href) ?? href; }
    // Everything "All sections" offers that is not on the home screen yet, in the site's own groups.
    function offered() {
        var taken = new Set(Array.from(document.querySelectorAll('.univer-app-quick .univer-app-tile>a')).map(function (a) { return homeKey(a.href); }));
        var groups = [], seen = new Set(), menu = document.querySelector('.univer-app-menu');
        var add = function (name, link, title) {
            var key = homeKey(link);
            if (taken.has(key) || seen.has(key) || (!tileKey(link) && fixedHomeLink(link))) return;
            seen.add(key);
            var group = groups.find(function (g) { return g.name === name; });
            if (!group) { group = {name:name, items:[]}; groups.push(group); }
            group.items.push({title:title, href:link});
        };
        if (menu) builtInTiles(menu).forEach(function (t) { if (hiddenTiles.indexOf(tileKey(t.href)) >= 0) add(text().removed, t.href, t.title); });
        document.querySelectorAll('.univer-app-menu details.univer-app-category:not(.univer-app-useful)').forEach(function (box) {
            var name = cleanText(box.querySelector('summary'));
            box.querySelectorAll('li a[href]').forEach(function (a) { if (pinnable(a) && cleanText(a)) add(name, a.href, cleanText(a)); });
        });
        document.querySelectorAll('.univer-app-menu #tools li a[href]').forEach(function (a) { if (pinnable(a) && cleanText(a)) add(text().sections, a.href, cleanText(a)); });
        document.querySelectorAll('.univer-app-menu .univer-app-useful li>a[href]').forEach(function (a) { if (pinnable(a) && cleanText(a)) add(text().useful, a.href, cleanText(a)); });
        return groups;
    }
    function closeAdd() {
        if (!adding) return false;
        var state = adding; adding = null;
        var done = function () { state.layer.remove(); owned = owned.filter(function (node) { return node !== state.layer; }); };
        if (window.__univerMotion !== false && !window.__univerMotionUI?.reduced?.() && state.layer.animate) {
            var out = state.layer.animate([{opacity:1}, {opacity:0}], {duration:140, easing:'ease-out', fill:'forwards'});
            out.onfinish = out.oncancel = done;
        } else done();
        if (state.from.isConnected) state.from.focus({preventScroll:true});
        return true;
    }
    function addRows(state) {
        var query = dom.fold(state.search.value.trim()), box = state.list, shown = 0;
        // Not replaceChildren(): Chromium 80-85 lacks it.
        box.textContent = '';
        offered().forEach(function (group) {
            var items = group.items.filter(function (item) { return !query || dom.fold(item.title).includes(query); });
            if (!items.length) return;
            box.appendChild(el('p', 'univer-app-add-group', group.name));
            items.forEach(function (item) {
                var row = control('univer-app-add-row', text().pin + ': ' + item.title);
                row.append(linkIcon(item.href, item.title), el('span', 'univer-app-add-name', item.title), decor('univer-app-add-plus'));
                row.addEventListener('click', function (event) {
                    if (!event.isTrusted) return;
                    var at = Array.prototype.indexOf.call(box.querySelectorAll('.univer-app-add-row'), row);
                    request(event, true, item.title, item.href);
                    addRows(state);
                    // The rebuilt list removed the focused row: focus stays in the dialog, on the row now in its place
                    // (or the panel itself, without raising the keyboard), so pinning several sections keeps the place.
                    if (adding !== state) return;
                    var rows = box.querySelectorAll('.univer-app-add-row');
                    (rows[Math.min(at, rows.length - 1)] || state.panel).focus({preventScroll:true});
                });
                box.appendChild(row); shown++;
            });
        });
        if (!shown) box.appendChild(el('p', 'univer-app-add-empty', query ? text().noMatch : text().allPinned));
    }
    // The sheet follows a finger pulled down from its top or from a list already scrolled to the top, and closes when
    // pulled a third of the way or flicked; otherwise it springs back. Touch events, not pointer events: a vertical
    // drag over the scrolling list would end in pointercancel as soon as the WebView took it for a scroll.
    function swipeDown(state) {
        var panel = state.panel, layer = state.layer, drag = null, swallow = 0;
        var still = function () { return window.__univerMotion === false || !!window.__univerMotionUI?.reduced?.(); };
        var settle = function (y, then) {
            var ease = 'transform .22s cubic-bezier(.32,.72,0,1)';
            if (still()) { panel.style.transition = layer.style.transition = 'none'; }
            else { panel.style.transition = ease; layer.style.transition = 'background-color .22s ease-out'; }
            panel.style.transform = y ? 'translateY(' + y + 'px)' : '';
            layer.style.backgroundColor = y ? 'rgba(16,28,44,0)' : '';
            if (!then) return;
            // A sheet closed and reopened meanwhile is a new sheet: the old timer leaves it alone.
            if (still()) then(); else setTimeout(function () { if (adding === state) then(); }, 220);
        };
        panel.addEventListener('touchstart', function (event) {
            // A new touch ends the swallow window: a deliberate tap always starts with one.
            swallow = 0;
            if (adding !== state || event.touches.length !== 1) {
                // A second finger on a moving sheet puts it back instead of leaving it halfway down.
                if (drag && drag.moved && adding === state) settle(0);
                drag = null; return;
            }
            var inList = state.list.contains(event.target);
            // A finger on the list scrolls it unless the list is at its top; the search field keeps its own gestures.
            if (event.target === state.search || (inList && state.list.scrollTop > 0)) { drag = null; return; }
            var touch = event.touches[0];
            drag = {y:touch.clientY, x:touch.clientX, moved:false, samples:[{y:touch.clientY, t:event.timeStamp}]};
        }, {passive:true});
        panel.addEventListener('touchmove', function (event) {
            if (!drag || adding !== state) return;
            var touch = event.touches[0], dy = touch.clientY - drag.y;
            if (!drag.moved) {
                if (Math.abs(touch.clientX - drag.x) > Math.abs(dy) || dy < 0) { if (Math.abs(dy) > 8 || Math.abs(touch.clientX - drag.x) > 8) drag = null; return; }
                if (dy < 8) return;
                drag.moved = true; drag.y = touch.clientY; dy = 0;
                panel.getAnimations?.().forEach(function (animation) { animation.cancel(); });
                panel.style.transition = layer.style.transition = 'none';
            }
            event.preventDefault();
            // 1:1 down; upward past the start the sheet resists instead of stopping dead.
            var y = dy > 0 ? dy : dy / 5, height = panel.getBoundingClientRect().height || 1;
            panel.style.transform = 'translateY(' + y + 'px)';
            layer.style.backgroundColor = 'rgba(16,28,44,' + (0.32 * Math.max(0, 1 - Math.max(0, y) / height)).toFixed(3) + ')';
            drag.samples.push({y:touch.clientY, t:event.timeStamp});
            if (drag.samples.length > 6) drag.samples.shift();
        }, {passive:false});
        var end = function (event) {
            var was = drag; drag = null;
            if (!was || !was.moved || adding !== state) return;
            swallow = event.timeStamp;
            var height = panel.getBoundingClientRect().height || 1;
            var match = /translateY\((-?[\d.]+)px\)/.exec(panel.style.transform), y = match ? parseFloat(match[1]) : 0;
            var first = was.samples[0], last = was.samples[was.samples.length - 1], speed = last.t > first.t ? (last.y - first.y) / (last.t - first.t) : 0;
            if (event.type !== 'touchcancel' && (y > height / 3 || (speed > 0.5 && y > 24))) settle(height, closeAdd);
            else settle(0);
        };
        panel.addEventListener('touchend', end); panel.addEventListener('touchcancel', end);
        // A pulled row must not pin itself when the finger lifts over it.
        panel.addEventListener('click', function (event) {
            if (swallow && event.timeStamp - swallow < 400) { event.preventDefault(); event.stopPropagation(); }
            swallow = 0;
        }, true);
    }
    // A sheet from the bottom of the screen. The keyboard stays down until the student taps the search field: the list
    // is what they came for, and Back closes the sheet (MobilePageAdapter.dismissNavigation).
    function openAdd(from) {
        if (adding) return;
        var layer = own(el('div', 'univer-app-add-layer')), panel = el('section', 'univer-app-add-panel');
        panel.setAttribute('role', 'dialog'); panel.setAttribute('aria-modal', 'true'); panel.setAttribute('aria-label', text().add); panel.tabIndex = -1;
        var head = el('div', 'univer-app-add-head'), close = control('univer-app-add-close', text().close);
        head.append(el('h2', '', text().add), close);
        var search = el('input', 'univer-app-add-search'); search.type = 'search'; search.placeholder = text().findSection;
        search.setAttribute('aria-label', text().findSection); search.autocomplete = 'off';
        var list = el('div', 'univer-app-add-list');
        panel.append(decor('univer-app-add-grip'), head, search, list); layer.appendChild(panel); document.body.appendChild(layer);
        var state = {layer:layer, panel:panel, search:search, list:list, from:from};
        adding = state; addRows(state); swipeDown(state);
        search.addEventListener('input', function () { addRows(state); });
        close.addEventListener('click', closeAdd);
        layer.addEventListener('pointerdown', function (event) { if (event.target === layer) closeAdd(); });
        // A modal sheet keeps the keyboard inside it: Escape closes it like Back, and Tab wraps around its controls
        // instead of moving to the page hidden behind it.
        layer.addEventListener('keydown', function (event) {
            if (adding !== state || event.isComposing) return;
            if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); closeAdd(); return; }
            if (event.key !== 'Tab') return;
            var stops = Array.from(panel.querySelectorAll('button,input,a[href],[tabindex]:not([tabindex="-1"])'))
                .filter(function (node) { return !node.disabled && node.getClientRects().length; });
            if (!stops.length) return;
            var at = document.activeElement, first = stops[0], last = stops[stops.length - 1];
            if (event.shiftKey ? (at === first || at === panel) : at === last) {
                event.preventDefault(); (event.shiftKey ? last : first).focus({preventScroll:true});
            }
        });
        window.__univerMotionUI?.reveal(panel, 'popup');
        panel.focus({preventScroll:true});
    }
    function addButton() {
        var button = control('univer-app-add-tile');
        button.append(decor('univer-app-add-icon'), el('span', 'univer-app-add-label'));
        button.addEventListener('click', function (event) { if (event.isTrusted) openAdd(button); });
        return button;
    }
    function homeTiles(menu) {
        var tiles = builtInTiles(menu);
        var taken = tiles.map(function (t) { return path(t.href) ?? t.href; }).concat(['/ssc/UserServiceList', '/ssc/StatementList']);
        // Built-in tiles the student removed stay out of the bar until they are pinned again.
        return {tiles:tiles.filter(function (t) { return hiddenTiles.indexOf(tileKey(t.href)) < 0; }),
            extra:pins.filter(function (p) { return taken.indexOf(path(p.url) ?? p.url) < 0; })};
    }
    function builtInTiles(menu) {
        var links = Array.from(menu.querySelectorAll('#tools a[href]')).filter(function (a) { return !a.closest('.univer-app-useful'); });
        var tiles = fixed.map(function (item) {
            var target = item[1].replace(/\/$/, ''), a = links.find(function (link) { return path(link.href) === target; });
            return {title:text()[item[0]], href:a ? a.href : location.origin + item[1]};
        });
        var moodle = Array.from(usefulSource()?.querySelectorAll('a[href]') || []).find(function (a) { return path(a.href) === moodlePath; });
        tiles.push({title:moodle ? cleanText(moodle) : text().moodle, href:location.origin + moodlePath});
        // One Е-услуги tile opens the services desk, where the catalogue and the student's requests live together.
        tiles.push({title:text().eservices, href:location.origin + '/ssc/'});
        return tiles;
    }
    // Mounts the wanted tiles in the saved order. Unchanged tiles (and keyboard focus) stay mounted; new pins animate in.
    function syncTiles(quick, tiles, extra, first) {
        var previous = new Map(), oldUrls = new Set(), added = [], wanted = [];
        Array.from(quick.querySelectorAll(':scope>.univer-app-tile')).forEach(function (node) {
            previous.set(node.__univerTileKey, node); oldUrls.add(node.querySelector('a').href);
        });
        var append = function (title, href, removable) {
            var key = JSON.stringify([title, href, removable]), node = previous.get(key);
            if (!node) { node = tile(title, href, removable); node.__univerTileKey = key; }
            wanted.push(node);
            if (!first && removable && !oldUrls.has(node.querySelector('a').href)) added.push(node);
        };
        tiles.forEach(function (t) { append(t.title, t.href, false); });
        extra.forEach(function (p) { append(p.title, p.url, true); });
        var rank = function (node) { var at = tileOrder.indexOf(node.querySelector('a').href); return at < 0 ? tileOrder.length : at; };
        wanted.sort(function (a, b) { return rank(a) - rank(b); });
        quick.__univerAdd = quick.__univerAdd || addButton();
        quick.__univerAdd.querySelector('.univer-app-add-label').textContent = text().add;
        wanted.push(quick.__univerAdd);
        var reconcile = function () {
            wanted.forEach(function (node, index) { if (quick.children[index] !== node) quick.insertBefore(node, quick.children[index] || null); });
            Array.from(quick.children).slice(wanted.length).forEach(function (node) { window.__univerMotionUI?.stop(node); node.remove(); });
        };
        if (window.__univerMotionUI) window.__univerMotionUI.reflow(quick, reconcile); else reconcile();
        if (first) Array.from(quick.querySelectorAll('.univer-app-tile')).forEach(function (node, index) {
            window.__univerMotionUI?.reveal(node, 'tile', Math.min(index, 3) * 24);
        });
        added.forEach(function (node) { window.__univerMotionUI?.reveal(node, 'tile'); });
    }
    function quickBar(menu) {
        var quick = document.querySelector('.univer-app-quick');
        if (!homePages.test(location.pathname)) { quick?.remove(); return; }
        var home = homeTiles(menu), tiles = home.tiles, extra = home.extra;
        var key = language + '|' + JSON.stringify(tiles.map(function (t) { return [t.title, t.href]; })) + JSON.stringify(extra) + orderKey + hiddenKey;
        if (quick && quick.__univerKey === key && quick.isConnected) return;
        var frame = document.querySelector('#l4>td.center') || document.querySelector('#l4>td');
        var body = frame?.querySelector(':scope>table.inner>tbody>tr>td.center') || frame;
        if (!body) return;
        if (body !== frame) mark(body, 'univer-app-home-body');
        if (!quick) { quick = document.createElement('nav'); quick.className = 'univer-app-quick'; owned.push(quick); }
        if (body.firstElementChild !== quick) body.prepend(quick);
        var first = !quick.__univerKey;
        quick.__univerKey = key; quick.setAttribute('aria-label', cleanText(menu.querySelector('summary')) || 'Univer');
        // The bin waits, hidden, next to the tiles; it only shows while one is dragged.
        trashZone().querySelector('.univer-app-trash-label').textContent = text().trash;
        syncTiles(quick, tiles, extra, first);
        watchTiles(quick);
    }
    // A word is never split: when a name cannot stand beside its icon, every name goes under its icon, so the grid stays even.
    function stackTiles(quick) {
        quick.classList.remove('univer-app-quick-stacked');
        var tight = Array.from(quick.querySelectorAll('.univer-app-tile>a')).some(function (link) { return link.scrollWidth > link.clientWidth; });
        quick.classList.toggle('univer-app-quick-stacked', tight);
    }
    // A bar already watched gets new names at its old size, so it is measured now. A new bar is measured by the observer's
    // first report, which comes before the bar is painted: measuring it here too laid the whole page out once more.
    function watchTiles(quick) {
        if (quick.__univerFit || !window.ResizeObserver) { stackTiles(quick); return; }
        // The stacking class is the app's own: it starts no adapter pass.
        quick.__univerFit = new ResizeObserver(function () { quiet(function () { stackTiles(quick); }); });
        quick.__univerFit.observe(quick);
    }

    // Tab pages: Главная has the tiles, Бакалавр leads with the GPA, Е-услуги switches between the service catalogue
    // and "my requests".
    var marked = new Set(), credentialSources = new Map(), hiddenPresentation = new Map();
    function hidePresentation(node) {
        if (!hiddenPresentation.has(node)) hiddenPresentation.set(node, {value:node.style.getPropertyValue('display'), priority:node.style.getPropertyPriority('display')});
        node.style.setProperty('display', 'none', 'important');
    }
    function wrapCredentialSource(host) {
        if (credentialSources.has(host)) return;
        var wrapper = document.createElement('div'); wrapper.className = 'univer-app-credential-original'; wrapper.style.setProperty('display','none','important');
        var children = Array.from(host.childNodes); host.appendChild(wrapper); children.forEach(function (node) { wrapper.appendChild(node); });
        credentialSources.set(host, wrapper);
    }
    function restoreCredentialSources() {
        credentialSources.forEach(function (wrapper, host) {
            if (wrapper.parentNode === host) { while (wrapper.firstChild) host.insertBefore(wrapper.firstChild, wrapper); wrapper.remove(); }
        });
        credentialSources.clear();
        hiddenPresentation.forEach(function (old, node) {
            if (old.value) node.style.setProperty('display', old.value, old.priority); else node.style.removeProperty('display');
            if (node.getAttribute('style') === '') node.removeAttribute('style');
        });
        hiddenPresentation.clear();
    }
    function hubText() {
        return language === 'en' ? {services:'Services', requests:'My requests', servicesHint:'Certificates, statements and other requests to the university', requestsHint:'Requests you have submitted and their status', find:'Find a service', none:'No services match', show:'Show', hide:'Hide', loading:'Loading…', copy:'Copy', copied:'Copied', login:'Login', password:'Password', nav:'E-services', gpa:'GPA'} :
            language === 'kz' ? {services:'Қызметтер', requests:'Менің өтінімдерім', servicesHint:'Анықтамалар, өтініштер және университетке басқа сұраулар', requestsHint:'Жіберілген өтінімдер және олардың күйі', find:'Қызметті табу', none:'Сәйкес қызмет жоқ', show:'Көрсету', hide:'Жасыру', loading:'Жүктелуде…', copy:'Көшіру', copied:'Көшірілді', login:'Логин', password:'Құпия сөз', nav:'Е-қызметтер', gpa:'GPA'} :
            {services:'Услуги', requests:'Мои заявки', servicesHint:'Справки, заявления и другие обращения в университет', requestsHint:'Поданные заявки и их статус', find:'Найти услугу', none:'Таких услуг нет', show:'Показать', hide:'Скрыть', loading:'Загрузка…', copy:'Копировать', copied:'Скопировано', login:'Логин', password:'Пароль', nav:'Е-услуги', gpa:'GPA'};
    }
    function mark(node, name, value) {
        if (value === undefined) { if (!node.classList.contains(name)) node.classList.add(name); }
        else if (node.getAttribute(name) !== value) node.setAttribute(name, value);
        marked.add(node);
    }
    // A bold line of its own is a heading; a bold name inside a sentence stays in that sentence.
    function inlineBold(cell) {
        Array.from(cell.children).forEach(function (bold) {
            if (bold.tagName === 'B' && bold.previousSibling?.nodeType === 3 && bold.previousSibling.textContent.trim()) mark(bold, 'univer-app-inline-bold');
        });
    }
    function content(node) { return node && !node.closest('#tools,.univer-app-menu,.univer-app-header,.univer-app-quick,#l5,.univer-app-overview,.univer-app-overview-grid,.univer-app-credentials,.univer-app-ssc-nav'); }
    function tabIcons() {
        document.querySelectorAll('.univer-app-tabs>a[href],#l3 td.center>a[href]').forEach(function (a) {
            var p = path(a.href) || '', header = !!a.closest('.univer-app-tabs');
            // Desktop: Е-услуги moved to a home tile and the header keeps the two big study tabs.
            // Android keeps all three tabs in the page, as the student chose.
            if (header && /^\/ssc/i.test(p)) { mark(a, 'univer-app-tab-hidden'); return; }
            var glyph = /^\/student\/bachelor/i.test(p) ? glyphs.cap : /^\/news|^\/$/.test(p) ? glyphs.home : /^\/ssc/i.test(p) ? glyphs.services : null;
            if (!glyph) return;
            mark(a, 'univer-app-tab-icon'); if (a.style.getPropertyValue('--glyph') !== glyph) a.style.setProperty('--glyph', glyph);
        });
    }
    function glyphIcon(glyph, tint) {
        var i = document.createElement('span'); i.className = 'univer-app-link-icon'; i.setAttribute('aria-hidden', 'true');
        i.style.setProperty('--glyph', glyph); i.style.setProperty('--tint', tint); return i;
    }
    // Site sections on Бакалавр and Е-услуги are "heading row + content row" tables; they become titled panels.
    function panels() {
        if (!/^\/student\/bachelor\/?$|^\/ssc(\/|$)/i.test(location.pathname)) return;
        document.querySelectorAll('#l4 table.mt').forEach(function (table) {
            if (!content(table) || table.querySelector('table.mt')) return;
            var cells = Array.from(table.querySelectorAll('td.ct,td.tt')).filter(function (c) { return c.closest('table') === table; });
            if (cells.length < 2) return;
            // One site table can hold several sections (e.g. "Личные данные" then "Обучение"): every short title
            // followed by content becomes a section header inside the same panel.
            var heads = cells.filter(function (cell, i) {
                var title = cleanText(cell);
                return i < cells.length - 1 && title && (title.length <= 60 || /print\.kaznu|wi-?fi/i.test(title)) && !cell.querySelector('table,form,input,select,ul,ol,img,a,br,h1,h2,h3,h4,h5,h6,p,div');
            });
            if (!heads.length || heads[0] !== cells[0]) return;
            mark(table, 'univer-app-panel');
            cells.forEach(function (cell) {
                if (heads.indexOf(cell) < 0) { mark(cell, 'univer-app-panel-body'); inlineBold(cell); return; }
                var t = cleanText(cell).toLocaleLowerCase();
                var pick = /личн|жеке|personal/.test(t) ? ['person', 'violet'] : /бюллет|bulletin|хабарланд/.test(t) ? ['news', 'amber'] : /обуч|оқу|study|learning/.test(t) ? ['cap', 'brand'] :
                    /услуг|қызмет|service/.test(t) ? ['services', 'teal'] : ['book', 'brand'];
                mark(cell, 'univer-app-panel-head');
                if (!cell.querySelector(':scope>.univer-app-link-icon')) cell.prepend(own(glyphIcon(glyphs[pick[0]], tints[pick[1]])));
            });
        });
    }
    // Login and password from the site's sign-in line; line breaks count as spaces so a login never runs into "Пароль".
    function readCredentials(node) {
        var parts = [], walk = (node.ownerDocument || document).createTreeWalker(node, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
        for (var n = walk.nextNode(); n; n = walk.nextNode()) parts.push(n.nodeType === 3 ? n.nodeValue : /^(BR|TD|TH|TR|P|DIV)$/.test(n.nodeName) ? ' ' : '');
        var text = parts.join('').replace(/\s+/g, ' ').trim();
        if (!/парол|password|құпия/i.test(text) || !/логин|login|print\.kaznu|wi-?fi/i.test(text)) return null;
        var login = text.match(/(?:логин|login)\s*:\s*(\S+)/i), secret = text.match(/(?:пароль|password|құпия\s*сөз)\s*:\s*(\S+)/i);
        if (!login || !secret) return null;
        return {heading:text.slice(0, text.search(/(?:логин|login)\s*:/i)).replace(/[\s.:]+$/, ''), login:login[1], secret:secret[1]};
    }
    // The panel keeps the values only in its own closures: nothing is logged or stored.
    function credentialPanel(found, withTitle) {
        var panel = own(document.createElement('section')); panel.className = 'univer-app-credentials'; panel.setAttribute('aria-label', found.heading);
        if (withTitle) { var head = document.createElement('h3'); head.append(glyphIcon(glyphs.wifi, tints.green), document.createTextNode(found.heading)); panel.appendChild(head); }
        [[hubText().login, found.login, false], [hubText().password, found.secret, true]].forEach(function (item) {
            var row = document.createElement('div'); row.className = 'univer-app-credential';
            var label = document.createElement('span'); label.className = 'univer-app-credential-label'; label.textContent = item[0];
            var value = document.createElement('span'); value.className = 'univer-app-credential-value';
            var actions = document.createElement('span'); actions.className = 'univer-app-credential-actions';
            var shown = !item[2], show = null;
            var paint = function () { value.textContent = shown ? item[1] : '••••••••'; value.classList.toggle('univer-app-credential-hidden', !shown); };
            var copy = document.createElement('button'); copy.type = 'button'; copy.className = 'univer-app-credential-button univer-app-credential-copy';
            var labels = function () {
                if (show) { show.textContent = shown ? hubText().hide : hubText().show; show.setAttribute('aria-pressed', String(shown)); show.setAttribute('aria-label', (shown ? hubText().hide : hubText().show) + ': ' + item[0]); }
                if (!copy.__univerDone) { copy.textContent = hubText().copy; copy.setAttribute('aria-label', hubText().copy + ': ' + item[0]); }
            };
            if (item[2]) {
                show = document.createElement('button'); show.type = 'button'; show.className = 'univer-app-credential-button univer-app-credential-show';
                show.addEventListener('click', function () { shown = !shown; paint(); labels(); });
                actions.appendChild(show);
            }
            copy.addEventListener('click', function () {
                var done = function () { copy.__univerDone = true; copy.textContent = hubText().copied; copy.classList.add('univer-app-copied'); clearTimeout(copy.__univerTimer);
                    copy.__univerTimer = setTimeout(function () { copy.__univerDone = false; copy.classList.remove('univer-app-copied'); labels(); }, 1600); };
                var fallback = function () { var area = document.createElement('textarea'); area.value = item[1]; area.setAttribute('readonly', ''); area.style.cssText = 'position:fixed;opacity:0;left:-9999px';
                    document.body.appendChild(area); area.select(); try { document.execCommand('copy'); done(); } catch (_) { } area.remove(); copy.focus(); };
                if (navigator.clipboard?.writeText) navigator.clipboard.writeText(item[1]).then(done, fallback); else fallback();
            });
            actions.appendChild(copy); paint(); labels();
            row.append(label, value, actions); panel.appendChild(row);
        });
        return panel;
    }
    function wifiTitle(title) {
        var icon = title?.querySelector(':scope>.univer-app-link-icon');
        if (icon) { icon.style.setProperty('--glyph', glyphs.wifi); icon.style.setProperty('--tint', tints.green); }
    }
    function credentials() {
        // The sign-in line is a list item on Е-услуги, or a whole cell under its own section title.
        // A plain selector list: Chromium before 88 cannot parse :is().
        document.querySelectorAll('li, #l4 td.ct:not(.univer-app-panel-head), #l4 td.tt:not(.univer-app-panel-head)').forEach(function (li) {
            if (li.closest('#tools,.univer-app-menu,.univer-app-quick,.univer-app-overview,.univer-app-credentials,.univer-app-credential-original,.univer-app-useful,.univer-app-ssc-nav') ||
                li.classList.contains('univer-app-credentials-source') || li.classList.contains('univer-app-credentials-host')) return;
            var cell = li.tagName === 'TD';
            if (cell && li.querySelector('form')) return;
            var found = readCredentials(li); if (!found) return;
            // The cell opens with the site's greeting; the list item inside it names the credentials by itself.
            var line = cell && Array.from(li.querySelectorAll('li')).map(readCredentials).find(Boolean);
            if (line) found.heading = line.heading;
            var cells = cell ? Array.from(li.closest('table').querySelectorAll('td.ct,td.tt')).filter(function (c) { return c.closest('table') === li.closest('table'); }) : [], title = cells[cells.indexOf(li) - 1];
            if (!/print\.kaznu|wi-?fi/i.test(found.heading) && !(cell && title && /print\.kaznu|wi-?fi/i.test(cleanText(title)))) return;
            // Е-услуги title their panel just "Услуги", so there the card has to say what the login opens.
            var named = cell && title && /print\.kaznu|wi-?fi/i.test(cleanText(title));
            if (named && title.classList.contains('univer-app-panel-head')) { wifiTitle(title); if (!found.heading) found.heading = cleanText(title).replace(/[\s.:]+$/, ''); }
            // The original line keeps its place and comes back in Original mode.
            mark(li, cell ? 'univer-app-credentials-host' : 'univer-app-credentials-source');
            var panel = credentialPanel(found, !named);
            if (cell) { wrapCredentialSource(li); li.appendChild(panel); }
            else { hidePresentation(li); (li.closest('ul,ol') || li).before(panel); }
        });
        // Бакалавр shows only the section title; the values live on the Е-услуги page and are read from there.
        if (!/^\/student\/bachelor\/?$/i.test(location.pathname)) return;
        // Keep everything after the Wi-Fi section in the source DOM for Original mode.
        document.querySelectorAll('#l4 td.ct,#l4 td.tt').forEach(function (title) {
            if (!content(title) || title.closest('.univer-app-credential-original') || !/print\.kaznu|wi-?fi/i.test(cleanText(title)) || /(?:логин|login|пароль|password)\s*:/i.test(cleanText(title))) return;
            var table = title.closest('table'), cells = Array.from(table.querySelectorAll('td.ct,td.tt')).filter(function (c) { return c.closest('table') === table; });
            var body = cells[cells.indexOf(title) + 1];
            if (!body) return;
            for (var next = body.closest('tr')?.nextElementSibling; next; next = next.nextElementSibling) { mark(next, 'univer-app-after-credentials'); hidePresentation(next); }
            var root = document.getElementById('l4');
            for (var cursor = table; cursor && cursor !== root && root.contains(cursor); cursor = cursor.parentElement) {
                for (var below = cursor.nextElementSibling; below; below = below.nextElementSibling) { mark(below, 'univer-app-after-credentials'); hidePresentation(below); }
            }
            if (!body || cleanText(body) || body.classList.contains('univer-app-credentials-host') || body.querySelector('table,form,input')) return;
            wifiTitle(title); mark(body, 'univer-app-credentials-host'); wrapCredentialSource(body);
            var pending = own(document.createElement('p')); pending.className = 'univer-app-pending univer-app-credentials'; pending.textContent = hubText().loading; body.appendChild(pending);
            var reader = window.__univerOverviewV1?.page; if (!reader) { pending.remove(); return; }
            reader('/ssc/').then(function (doc) {
                pending.remove(); if (!body.classList.contains('univer-app-credentials-host')) return;
                var source = Array.from(doc.querySelectorAll('li')).map(readCredentials).find(Boolean);
                if (source) { if (!source.heading) source.heading = cleanText(title); body.appendChild(credentialPanel(source, false)); }
            }, function () { pending.remove(); });
        });
    }
    function gpaCards() {
        var box = document.querySelector('.univer-app-gpa');
        if (!/^\/student\/bachelor\/?$/i.test(location.pathname)) { box?.remove(); return; }
        var cell = Array.from(document.querySelectorAll('#l4 .ct.message')).find(function (c) {
            var items = Array.from(c.querySelectorAll('li')); return content(c) && items.length === 2 && items.every(function (li) { return /G[PР][AА]\s*[=:]/i.test(cleanText(li)); });
        });
        if (!cell) { box?.remove(); return; }
        var values = Array.from(cell.querySelectorAll('li')).map(function (li) { var m = cleanText(li).match(/^(.*?)\s*[=:]\s*(\S+)$/); return m ? [m[1], m[2]] : null; });
        if (values.some(function (v) { return !v; })) { box?.remove(); return; }
        var key = JSON.stringify(values);
        if (!box || box.__univerKey !== key) {
            box?.remove(); box = own(document.createElement('div')); box.className = 'univer-app-gpa'; box.__univerKey = key; box.setAttribute('role', 'group'); box.setAttribute('aria-label', hubText().gpa);
            values.forEach(function (v) { var card = document.createElement('div'), b = document.createElement('b'), label = document.createElement('span'); b.textContent = v[1]; label.textContent = v[0]; card.append(b, label); box.appendChild(card); });
            (cell.closest('table.mt') || cell).before(box);
        }
        mark(cell, 'univer-app-gpa-source');
        var sourceTable = cell.closest('table.mt');
        if (sourceTable && !sourceTable.querySelector('form,input,select,textarea,button,a') &&
            Array.from(sourceTable.querySelectorAll('td')).every(function (td) { return td === cell || !cleanText(td); })) mark(sourceTable, 'univer-app-gpa-source-table');
    }
    function serviceDesk() {
        var nav = document.querySelector('.univer-app-ssc-nav');
        if (!/^\/ssc(\/|$)/i.test(location.pathname)) { nav?.remove(); return; }
        var t = hubText(), list = location.origin + '/ssc/UserServiceList', mine = location.origin + '/ssc/StatementList';
        var key = language + location.pathname;
        if (!nav || nav.__univerKey !== key) {
            nav?.remove(); nav = own(document.createElement('nav')); nav.className = 'univer-app-ssc-nav'; nav.__univerKey = key; nav.setAttribute('aria-label', t.nav);
            [[list, t.services, glyphs.services], [mine, t.requests, glyphs.transcript]].forEach(function (item) {
                var a = document.createElement('a'); a.href = item[0]; a.textContent = item[1]; a.style.setProperty('--glyph', item[2]);
                if (path(item[0]).toLowerCase() === location.pathname.replace(/\/+$/, '').toLowerCase()) a.setAttribute('aria-current', 'page');
                // The site's own link keeps its handler when there is one on the page.
                a.addEventListener('click', function (event) { var original = Array.from(document.querySelectorAll('#l4 a[href]')).find(function (o) { return o !== a && !o.closest('.univer-app-ssc-nav') && path(o.href) === path(item[0]); }); if (original) { event.preventDefault(); original.click(); } });
                nav.appendChild(a);
            });
            var anchor = document.querySelector('#l4>td'); if (anchor) anchor.prepend(nav);
        }
        // On the desk itself the site's two buttons become cards that say what is behind them.
        document.querySelectorAll('#l4 a[href]').forEach(function (a) {
            if (!content(a) || a.closest('.univer-app-ssc-nav') || !/^\/ssc\/?$/i.test(location.pathname)) return;
            var p = path(a.href), hint = /^\/ssc\/UserServiceList$/i.test(p) ? t.servicesHint : /^\/ssc\/StatementList$/i.test(p) ? t.requestsHint : null;
            if (!hint) return;
            mark(a, 'univer-app-service-card'); mark(a, 'data-univer-hint', hint);
            if (!a.querySelector(':scope>.univer-app-link-icon')) a.prepend(own(linkIcon(a.href, cleanText(a))));
        });
        // The live desk has no buttons of its own; it gets two cards that say what each half of Е-услуги holds.
        var desk = /^\/ssc\/?$/i.test(location.pathname), cards = document.querySelector('.univer-app-desk-cards');
        if (desk && !document.querySelector('#l4 .univer-app-service-card') && (!cards || cards.__univerKey !== language)) {
            cards?.remove(); cards = own(document.createElement('div')); cards.className = 'univer-app-desk-cards'; cards.__univerKey = language;
            [[list, t.services, t.servicesHint], [mine, t.requests, t.requestsHint]].forEach(function (item) {
                var a = document.createElement('a'); a.href = item[0]; a.className = 'univer-app-service-card univer-app-desk-card'; a.setAttribute('data-univer-hint', item[2]);
                a.append(linkIcon(item[0], item[1]), document.createTextNode(item[1])); cards.appendChild(a);
            });
            nav.after(cards);
        }
        if (!desk) cards?.remove();
        if (nav.hidden !== desk) nav.hidden = desk;
        serviceSearch(); requestStatuses(); credentials();
    }
    function mainTable() {
        return Array.from(document.querySelectorAll('#l4 table')).filter(function (table) { return content(table) && table.rows.length > 2 && !table.querySelector('table'); })
            .sort(function (a, b) { return b.rows.length - a.rows.length; })[0];
    }
    function serviceSearch() {
        var input = document.querySelector('.univer-app-service-search');
        var table = /^\/ssc\/UserServiceList\/?$/i.test(location.pathname) ? mainTable() : null;
        if (!table) { input?.remove(); document.querySelector('.univer-app-service-empty')?.remove(); return; }
        mark(table, 'univer-app-service-table');
        if (!input) {
            input = own(document.createElement('input')); input.type = 'search'; input.className = 'univer-app-service-search univer-app-catalog-search';
            var empty = own(document.createElement('p')); empty.className = 'univer-app-service-empty'; empty.hidden = true; empty.setAttribute('role', 'status');
            input.addEventListener('input', function () { filterServices(); });
            table.before(input, empty);
        }
        if (input.placeholder !== hubText().find + '…') { input.placeholder = hubText().find + '…'; input.setAttribute('aria-label', hubText().find); }
        filterServices();
    }
    function filterServices() {
        var input = document.querySelector('.univer-app-service-search'), table = mainTable(); if (!input || !table) return;
        var query = input.value.trim().toLocaleLowerCase().replace(/ё/g, 'е'), shown = 0;
        Array.from(table.rows).forEach(function (row) {
            if (row.querySelector('th')) return;
            var hide = !!query && !cleanText(row).toLocaleLowerCase().replace(/ё/g, 'е').includes(query);
            if (row.classList.contains('univer-app-service-hidden') !== hide) row.classList.toggle('univer-app-service-hidden', hide);
            marked.add(row); if (!hide) shown++;
        });
        var empty = document.querySelector('.univer-app-service-empty');
        if (empty) { empty.textContent = hubText().none; if (empty.hidden !== (!query || shown > 0)) empty.hidden = !query || shown > 0; }
    }
    function requestStatuses() {
        var table = /^\/ssc\/StatementList\/?$/i.test(location.pathname) ? mainTable() : null; if (!table) return;
        // A click on a request row opens it, like the site's double click; the checkbox and links keep their own clicks.
        if (!table.__univerOpen) {
            table.__univerOpen = function (event) {
                if (!event.isTrusted || event.target.closest('input,a,button,label,select')) return;
                var row = event.target.closest('tr[id]'); if (!row || !/^\d{1,12}$/.test(row.id)) return;
                location.href = '/ssc/StatementPage?statementId=' + row.id;
            };
            table.addEventListener('click', table.__univerOpen); marked.add(table);
        }
        mark(table, 'univer-app-request-table');
        Array.from(table.rows).forEach(function (row) {
            var cell = Array.from(row.cells).find(function (c) { return /статус|күй|status/i.test(c.getAttribute('data-univer-field') || ''); }) || row.cells[row.cells.length - 1], value = cleanText(cell).toLocaleLowerCase();
            if (!cell || cell.tagName !== 'TD' || !value || value.length > 40) return;
            var tone = /заверш|выполн|готов|выдан|аяқта|орында|дайын|complete|done|ready|issued/.test(value) ? 'done' :
                /отклон|отказ|аннул|бас тарт|reject|declin|cancel/.test(value) ? 'fail' : /обработ|рассмотр|нов|ожида|қаралу|өңде|process|review|pending|new/.test(value) ? 'wait' : null;
            if (!tone) return;
            mark(cell, 'univer-app-status'); mark(cell, 'data-univer-tone', tone);
        });
    }
    function hubs() {
        tabIcons(); panels(); gpaCards(); serviceDesk();
        if (/^\/student\/bachelor\/?$/i.test(location.pathname)) credentials();
        window.__univerOverviewV1?.apply(language);
    }
    function releaseHubs() {
        marked.forEach(function (node) {
            ['univer-app-tab-icon','univer-app-tab-hidden','univer-app-panel','univer-app-panel-head','univer-app-panel-body','univer-app-inline-bold','univer-app-gpa-source','univer-app-gpa-source-table','univer-app-home-body','univer-app-service-card','univer-app-service-hidden','univer-app-status','univer-app-credentials-source','univer-app-credentials-host','univer-app-after-credentials'].forEach(function (name) { node.classList.remove(name); });
            ['data-univer-hint','data-univer-tone'].forEach(function (name) { node.removeAttribute(name); });
            node.classList.remove('univer-app-request-table', 'univer-app-service-table');
            if (node.__univerOpen) { node.removeEventListener('click', node.__univerOpen); node.__univerOpen = null; }
            ['--glyph'].forEach(function (name) { node.style.removeProperty(name); });
            if (node.getAttribute('style') === '') node.removeAttribute('style');
        });
        marked.clear();
    }
    function render() {
        if (!active) return;
        if (!style || !style.isConnected) {
            style = document.createElement('style'); style.id = 'univer-shortcuts-style';
            var source = ':root{--univer-pin-icon:' + pinIcon(false) + ';--univer-pinned-icon:' + pinIcon(true) + ';--univer-close-icon:' + svg("<path d='M6 6l12 12M18 6L6 18'/>") +
                ';--univer-plus-icon:' + svg("<path d='M12 5v14M5 12h14'/>") +
                ';--univer-reorder-icon:' + svg("<circle cx='8' cy='6' r='1'/><circle cx='16' cy='6' r='1'/><circle cx='8' cy='12' r='1'/><circle cx='16' cy='12' r='1'/><circle cx='8' cy='18' r='1'/><circle cx='16' cy='18' r='1'/>") +
                ';--univer-trash-icon:' + svg("<path d='M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14M10 11v6M14 11v6'/>") +
                ';--univer-doc-icon:' + svg("<path d='M14 3H6v18h12V7z'/><path d='M14 3v4h4M9 13h6M9 17h6'/>") + ';--univer-eye-icon:' + svg("<path d='M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z'/><circle cx='12' cy='12' r='3'/>") + ';}' + css;
            if (window.__univerPaletteV1) window.__univerPaletteV1.fill(style, source); else style.textContent = source;
            (document.head || document.documentElement).appendChild(style);
        }
        document.documentElement.classList.toggle('univer-app-home', homePages.test(location.pathname));
        hubs();
        var menu = document.querySelector('.univer-app-menu');
        if (!menu) { document.querySelector('.univer-app-quick')?.remove(); return; }
        menuPins(menu); usefulBox(menu); quickBar(menu);
    }
    window.__univerShortcutsV1 = {
        apply: function (lang) { language = lang || 'ru'; active = true; render(); },
        configure: function (list, order, hidden) {
            var next = Array.isArray(list) ? list.filter(function (p) { return p && typeof p.title === 'string' && typeof p.url === 'string' && !fixedHomeLink(p.url); }) : [];
            var key = JSON.stringify(next);
            var nextOrder = Array.isArray(order) ? order.filter(function (url) { return typeof url === 'string'; }) : tileOrder;
            var nextOrderKey = JSON.stringify(nextOrder);
            var nextHidden = Array.isArray(hidden) ? hidden.map(function (url) { return typeof url === 'string' ? tileKey(url) : null; })
                .filter(function (k, i, all) { return k && all.indexOf(k) === i; }) : hiddenTiles;
            var nextHiddenKey = JSON.stringify(nextHidden);
            if (key === pinsKey && nextOrderKey === orderKey && nextHiddenKey === hiddenKey) return;
            pins = next; pinsKey = key; tileOrder = nextOrder; orderKey = nextOrderKey; hiddenTiles = nextHidden; hiddenKey = nextHiddenKey; render();
        },
        close: closeAdd,
        release: function () {
            active = false;
            draggedTile = null;
            adding = null;
            document.documentElement.classList.remove('univer-app-tile-drag');
            restoreCredentialSources();
            pinButtons.forEach(function (button) { button.remove(); }); pinButtons.clear();
            menuRows.forEach(function (entry, a) {
                while (entry.label.firstChild) a.insertBefore(entry.label.firstChild, entry.label);
                entry.label.remove(); entry.row.before(a); entry.row.remove();
            }); menuRows.clear(); menuPinsDrawn = null;
            document.documentElement.classList.remove('univer-app-home');
            owned.forEach(function (node) { window.__univerMotionUI?.stop(node); node.remove(); }); owned = [];
            document.querySelectorAll('.univer-app-desk-cards,.univer-app-quick,.univer-app-useful,.univer-app-gpa,.univer-app-ssc-nav,.univer-app-service-search,.univer-app-service-empty,.univer-app-credentials').forEach(function (node) { node.remove(); });
            releaseHubs(); window.__univerOverviewV1?.release();
            style?.remove(); style = null;
        }
    };
})();
