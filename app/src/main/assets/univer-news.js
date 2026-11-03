/* News filters belong to the whole result list; source rows and site handlers stay intact. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerNewsV1) return;
    var dom = window.__univerDomV1, setText = dom.setText, setHidden = dom.setHidden, el = dom.el;
    var key = 'univer.desktop.news-filter.v1', countKey = 'univer.desktop.news-page-count.v1', state = null, language = 'ru', timer = null;
    var folds = new Map(), foldSources = new WeakMap(), expanded = new WeakMap(), foldObserver = null, foldTimer = null, foldSequence = 0;
    // Bounds of the read-only page fetches: one response, one search batch, the highest page ever probed.
    var MAX_HTML = 4 * 1024 * 1024, BATCH = 30, MAX_PAGE = 4096;
    var WORDS = {
        en: {search:'Search news…', searchShort:'Search…', filters:'Filters', from:'From', to:'To', important:'Important only', shown:'Shown', of:'of', all:'Search all pages', reset:'Reset', searching:'Searching…', page:'Page', go:'Go', none:'No news match these filters', failed:'Search is incomplete. Retry to check the remaining pages.', retry:'Continue search', invalid:'The start date must not be after the end date.', expand:'Show full news', collapse:'Collapse'},
        kz: {search:'Жаңалықтарды іздеу…', searchShort:'Іздеу…', filters:'Сүзгілер', from:'Бастап', to:'Дейін', important:'Тек маңызды', shown:'Көрсетілді', of:'/', all:'Барлық беттерден іздеу', reset:'Тазалау', searching:'Іздеу…', page:'Бет', go:'Өту', none:'Бұл сүзгілерге сәйкес жаңалықтар жоқ', failed:'Іздеу аяқталмады. Қалған беттерді тексеру үшін қайталаңыз.', retry:'Іздеуді жалғастыру', invalid:'Басталу күні аяқталу күнінен кейін болмауы керек.', expand:'Толық көрсету', collapse:'Жасыру'},
        ru: {search:'Поиск по новостям…', searchShort:'Поиск…', filters:'Фильтры', from:'С', to:'По', important:'Только важные', shown:'Показано', of:'из', all:'Искать на всех страницах', reset:'Сбросить', searching:'Поиск…', page:'Страница', go:'Перейти', none:'По этим условиям новостей нет', failed:'Поиск не завершён. Повторите, чтобы проверить остальные страницы.', retry:'Продолжить поиск', invalid:'Дата «С» должна быть не позже даты «По».', expand:'Показать полностью', collapse:'Свернуть'}
    };
    var filter = restoreFilter();
    function restoreFilter() {
        try {
            var saved = JSON.parse(sessionStorage.getItem(key));
            if (saved && typeof saved.query === 'string' && saved.query.length <= 300 &&
                [saved.from, saved.to].every(v => typeof v === 'string' && (!v || /^\d{4}-\d{2}-\d{2}$/.test(v))))
                return {query:saved.query, from:saved.from, to:saved.to, important:saved.important === true, page:Math.max(1, Math.min(1000, +saved.page || 1))};
        } catch (_) { /* Storage may be unavailable; filtering still works in this document. */ }
        return {query:'', from:'', to:'', important:false, page:1};
    }
    // A page count found earlier in this tab stays usable for ten minutes.
    function savedCount(known) {
        try {
            var cached = JSON.parse(sessionStorage.getItem(countKey)), age = cached ? Date.now() - cached.time : -1;
            if (cached && Number.isInteger(cached.count) && cached.count >= known && cached.count <= MAX_PAGE && age >= 0 && age < 600000) return cached.count;
        } catch (_) {}
        return 0;
    }
    function text() { return WORDS[language] || WORDS.ru; }
    function clean(node) {
        // Search and signatures use the complete article, excluding our disclosure control.
        if (node?.matches?.('tr.news')) return Array.from(node.cells).map(cell => clean(cell.querySelector(':scope > .univer-app-news-body') || cell)).join(' ');
        return (node?.textContent || '').replace(/\s+/g, ' ').trim();
    }
    /** Writes only real changes, so DOM observers are not woken for nothing. */
    function attr(node, name, value) { if (node.getAttribute(name) !== value) node.setAttribute(name, value); }
    function updateFold(record) {
        if (!record.body.getBoundingClientRect().width) return; // Hidden search/source pages have no useful geometry.
        var long = record.body.scrollHeight > 176, open = expanded.get(record.source) === true, t = text();
        attr(record.body, 'data-collapsed', String(long && !open));
        setHidden(record.button, !long);
        attr(record.button, 'aria-expanded', String(!long || open));
        setText(record.button, long ? (open ? t.collapse : t.expand) : '');
    }
    function unfold(record) {
        window.__univerMotionUI?.stop(record.body);
        foldObserver?.unobserve(record.body); record.button.remove();
        if (record.body.parentNode) record.body.replaceWith(...record.body.childNodes);
    }
    // Moves the article of [row] into a collapsible body followed by its disclosure button.
    function createFold(row, cell) {
        var body = el('div', 'univer-app-news-body'), button = el('button', 'univer-app-news-toggle');
        body.id = 'univer-news-body-' + (++foldSequence);
        body.append(...cell.childNodes);
        button.type = 'button'; button.hidden = true; button.setAttribute('aria-controls', body.id);
        cell.append(body, button);
        var record = {body, button, row, source:foldSources.get(row) || row};
        button.addEventListener('click', event => {
            event.stopPropagation();
            var wasOpen = expanded.get(record.source) === true, before = body.getBoundingClientRect().height, motion = window.__univerMotionUI;
            motion?.stop(body);
            expanded.set(record.source, !wasOpen); updateFold(record);
            if (event.detail > 0) motion?.resize(body, before, wasOpen ? 180 : 220);
            if (!wasOpen && event.detail > 0) motion?.reveal(body, 'content');
            if (wasOpen && row.getBoundingClientRect().top < 80) row.scrollIntoView?.({block:'start', behavior:event.detail > 0 && window.__univerMotion !== false && !motion?.reduced() ? 'smooth' : 'auto'});
        });
        // A keyboard user reaching a link below the preview gets the full article before continuing.
        body.addEventListener('focusin', () => { window.__univerMotionUI?.stop(body); expanded.set(record.source, true); updateFold(record); });
        body.addEventListener('load', scheduleFolds, true);
        foldObserver?.observe(body);
        return record;
    }
    function refreshFolds() {
        if (!state) return;
        folds.forEach((record, row) => {
            if (!record.body.isConnected || (!state.table.contains(row) && !state.results.contains(row))) { unfold(record); folds.delete(row); }
        });
        [state.table, state.results].forEach(root => root.querySelectorAll('tr.news').forEach(row => {
            var record = folds.get(row), cell = row.cells[1]; if (!cell) return;
            if (!record) { record = createFold(row, cell); folds.set(row, record); }
            updateFold(record);
        }));
    }
    function scheduleFolds() {
        if (!state || foldTimer !== null) return;
        foldTimer = setTimeout(() => { foldTimer = null; refreshFolds(); }, 0);
    }
    function fold(value) { return value.toLocaleLowerCase().replace(/ё/g, 'е'); }
    function pageNumber() { return +(location.pathname.match(/^\/news\/student\/(\d+)/)?.[1] || 1); }
    function href(page) { return page > 1 ? '/news/student/' + page : '/news/student'; }
    function active() { return !!(filter.query.trim() || filter.from || filter.to || filter.important); }
    function valid() { return !(filter.from && filter.to && filter.from > filter.to); }
    function date(row) {
        var box = row.cells[0]?.querySelector('.date'), day = +clean(box?.querySelector('.day'));
        var parts = clean(box).match(/(\d{1,2})\.(\d{4})\s*$/);
        return day && parts ? parts[2] + '-' + parts[1].padStart(2, '0') + '-' + String(day).padStart(2, '0') : '';
    }
    function matches(row) {
        var stamp = date(row);
        return (!filter.from || (stamp && stamp >= filter.from)) && (!filter.to || (stamp && stamp <= filter.to)) &&
            (!filter.important || /важное|маңызды|important/i.test(clean(row.cells[0]))) &&
            fold(filter.query.trim()).split(/\s+/).every(word => fold(clean(row)).includes(word));
    }
    // News are newest first: a whole dated page before the lower bound means no older page can match.
    function precedes(rows) {
        return !!filter.from && rows.length > 0 && rows.every(row => { var stamp = date(row); return !!stamp && stamp < filter.from; });
    }
    function save() { try { if (active()) sessionStorage.setItem(key, JSON.stringify(filter)); else sessionStorage.removeItem(key); } catch (_) {} }
    function hasNext(doc, page) {
        return Array.from(doc.querySelectorAll('td.tb a[href]')).some(a => {
            var match = new URL(a.href, location.href).pathname.match(/^\/(?:my\/news|news\/student)\/(\d+)\/?$/);
            return match && +match[1] > page;
        });
    }
    // The page number the server rendered: it answers a page past the end with the last one.
    function displayedPage(doc) {
        var label = Array.from(doc.querySelectorAll('td.tb')).map(cell => {
            var copy = cell.cloneNode(true); copy.querySelectorAll('a').forEach(a => a.remove()); return clean(copy);
        }).join(' ').match(/(?:Страница|Page|Бет)\s*(\d+)/i);
        return label ? +label[1] : 0;
    }
    // Fetched HTML is inert. Only passive, sanitized copies are inserted into the result list.
    function safeRow(row) {
        var clone = row.cloneNode(true);
        clone.querySelectorAll('.univer-app-news-toggle').forEach(n => n.remove());
        clone.querySelectorAll('.univer-app-news-body').forEach(n => n.replaceWith(...n.childNodes));
        clone.querySelectorAll('script,style,iframe,frame,object,embed,form,input,button,svg,math,link,meta').forEach(n => n.remove());
        [clone, ...clone.querySelectorAll('*')].forEach(node => {
            if (!/^(TR|TD|TH|DIV|P|SPAN|A|IMG|BR|B|STRONG|I|EM|U|S|SUB|SUP|UL|OL|LI|TABLE|TBODY|THEAD|TFOOT|H[1-6]|BLOCKQUOTE|HR|PRE|CODE)$/.test(node.tagName)) { node.replaceWith(...node.childNodes); return; }
            Array.from(node.attributes).forEach(attribute => {
                if (!/^(class|colspan|rowspan|alt|title|href|src)$/.test(attribute.name)) node.removeAttribute(attribute.name);
            });
            ['href', 'src'].forEach(name => {
                if (!node.hasAttribute(name)) return;
                try {
                    var url = new URL(node.getAttribute(name), location.origin);
                    if (url.protocol !== 'https:' || url.username || url.password || (name === 'src' && url.origin !== location.origin)) node.removeAttribute(name);
                    else node.setAttribute(name, url.href);
                } catch (_) { node.removeAttribute(name); }
            });
        });
        clone.hidden = false;
        return clone;
    }
    function showPage(page) { filter.page = page; save(); render(); }
    function pager(current, count, filtered, exact) {
        var t = text(), nav = el('nav', 'univer-app-news-pager'); nav.setAttribute('aria-label', t.page);
        if (count <= 1) nav.hidden = true;
        var add = function (p) {
            var a = el('a', p === current ? 'univer-app-news-current' : '', String(p)); a.href = filtered ? '#news-results-' + p : href(p);
            if (p === current) a.setAttribute('aria-current', 'page');
            if (filtered) a.addEventListener('click', e => { e.preventDefault(); showPage(p); state.bar.scrollIntoView?.({block:'start'}); });
            nav.appendChild(a);
        };
        var pages = new Set([1, count]);
        for (var first = 1; first <= Math.min(5, count); first++) pages.add(first);
        for (var p = Math.max(1, current - 2); p <= Math.min(count, current + 2); p++) pages.add(p);
        var previous = 0;
        Array.from(pages).sort((a, b) => a - b).forEach(p => {
            if (previous && p > previous + 1) nav.appendChild(el('span', 'univer-app-news-gap', '…'));
            add(p); previous = p;
        });
        var form = el('form', 'univer-app-news-jump'), number = el('input'), go = el('button', '', t.go);
        number.type = 'number'; number.min = '1'; if (exact) number.max = String(count); number.value = String(current); number.setAttribute('aria-label', t.page);
        go.type = 'submit';
        form.append(el('span', 'univer-app-news-jump-label', t.page), number, go); nav.appendChild(form);
        form.addEventListener('submit', e => {
            e.preventDefault(); var target = Math.min(exact ? count : MAX_PAGE, Math.max(1, parseInt(number.value, 10) || 1));
            if (filtered) showPage(target); else location.assign(href(target));
        });
        return nav;
    }
    function replacePagers(current, count, filtered, exact = true) {
        var signature = [current, count, filtered, exact, language].join(':');
        if (state.pagerSignature === signature) return;
        document.querySelectorAll('.univer-app-news-pages,.univer-app-news-pager').forEach(n => n.remove());
        state.pagerSignature = signature;
        var top = pager(current, count, filtered, exact), bottom = pager(current, count, filtered, exact);
        top.classList.add('univer-app-news-pager-top');
        var pages = el('details', 'univer-app-news-pages');
        // A result that fits on one page needs no page navigation at all.
        pages.hidden = top.hidden;
        pages.append(el('summary', '', text().page + ' ' + current + (exact ? ' / ' + count : '')), top);
        state.bar.after(pages); state.anchor.after(bottom);
    }
    // The site's own hidden state of a news row, recorded before any filter hid it.
    function sourceHidden(row) { return state.hidden.has(row) ? state.hidden.get(row) : row.hidden; }
    function render() {
        if (!state) return;
        var t = text(), rows = Array.from(state.table.querySelectorAll('tr.news')), shown = 0;
        rows.forEach(row => {
            if (!state.hidden.has(row)) state.hidden.set(row, row.hidden);
            var visible = !sourceHidden(row) && (!active() || (valid() && matches(row)));
            setHidden(row, !visible);
            if (visible) shown++;
        });
        var filters = [filter.from, filter.to, filter.important].filter(Boolean).length;
        state.reset.hidden = !active();
        setText(state.filterCaption, t.filters + (filters ? ' (' + filters + ')' : ''));
        state.all.hidden = !active() || state.running || !state.searched || state.complete || !valid();
        setText(state.all, state.next > 1 ? t.retry : t.all);
        if (!active()) {
            state.table.removeAttribute('data-univer-news-source'); state.results.hidden = true;
            setText(state.counter, t.shown + ' ' + shown);
            replacePagers(pageNumber(), state.total || state.known, false, !!state.total);
            refreshFolds();
            return;
        }
        var found = [];
        state.pages.forEach((items, p) => items.forEach((row, i) => { if (!sourceHidden(row) && valid() && matches(row)) found.push({row:row, page:p, index:i}); }));
        found.sort((a, b) => a.page - b.page || a.index - b.index);
        var count = Math.max(1, Math.ceil(found.length / state.pageSize));
        // A restored page is retained while earlier pages are still being fetched.
        if (!state.running && state.searched) filter.page = Math.min(filter.page, count);
        var current = Math.min(filter.page, count);
        state.table.setAttribute('data-univer-news-source', 'true'); state.results.hidden = false;
        state.results.replaceChildren();
        var status = el('p', 'univer-app-news-status', !valid() ? t.invalid : state.running ? t.searching : !state.complete ? t.failed : found.length ? '' : t.none);
        status.setAttribute('role', 'status'); status.hidden = !status.textContent; state.results.appendChild(status);
        var table = el('table', 'univer-app-news'), body = el('tbody'); table.dataset.univerResults = 'true'; table.appendChild(body);
        var slice = found.slice((current - 1) * state.pageSize, current * state.pageSize);
        slice.forEach(hit => { var row = safeRow(hit.row); foldSources.set(row, hit.row); body.appendChild(row); }); state.results.appendChild(table);
        setText(state.counter, t.shown + ' ' + slice.length + ' ' + t.of + ' ' + found.length + (state.running || !state.complete ? '+' : ''));
        replacePagers(current, count, true); save(); refreshFolds();
    }
    function cancel() { clearTimeout(timer); if (state) { state.generation++; state.controller?.abort(); state.running = false; } }
    /** Whether the search or count of [record] started in [generation] is still the one on screen. */
    function current(record, generation) { return state === record && record.generation === generation; }
    function changed() {
        cancel(); filter.page = 1;
        filter.query = state.query.value.slice(0, 300); filter.from = state.from.value; filter.to = state.to.value; filter.important = state.important.getAttribute('aria-pressed') === 'true';
        state.next = 1; state.complete = pageNumber() === 1 && !hasNext(document, 1); state.searched = false;
        save(); render();
        if (active() && valid()) timer = setTimeout(search, 200);
        else if (!active() && !state.total) timer = setTimeout(discoverPages, 400);
    }
    async function readPage(p, record, generation) {
        var controller = new AbortController(); record.controller = controller;
        var timeout = setTimeout(() => controller.abort(), 10000);
        try {
            var response = await fetch(href(p), {credentials:'same-origin', cache:'no-store', redirect:'error', signal:controller.signal});
            var url = new URL(response.url, location.origin);
            if (response.status !== 200 || url.origin !== location.origin || url.pathname.replace(/\/$/, '') !== href(p) || +response.headers?.get('content-length') > MAX_HTML) throw new Error('Unexpected news response');
            var html = await response.text(); if (html.length > MAX_HTML) throw new Error('Oversized news response');
            if (!current(record, generation)) throw new Error('Stale news response');
            var doc = new DOMParser().parseFromString(html, 'text/html'), rows = Array.from(doc.querySelectorAll('tr.news'));
            if (doc.querySelector('#login_frm') || (!rows.length && !doc.querySelector('td.tb'))) throw new Error('News unavailable');
            var displayed = displayedPage(doc);
            if (displayed && displayed !== p) throw new Error('News page was clamped');
            return {rows, more:hasNext(doc, p)};
        } finally { clearTimeout(timeout); }
    }
    async function discoverPages() {
        if (!state || active() || state.total || typeof fetch !== 'function') return;
        var record = state, generation = record.generation, low = pageNumber(), high = 0, probe = Math.max(5, low * 2);
        // The legacy pager exposes only its next arrow. Find the end with bounded exponential/binary GET probes.
        // Only the count is retained; discovery does not populate the filtered-news cache.
        try {
            for (var attempts = 0; attempts < 24; attempts++) {
                var result = await readPage(probe, record, generation);
                if (result.rows.length) {
                    low = probe; record.known = Math.max(record.known, probe);
                    if (!result.more) { high = low; break; }
                } else high = probe - 1;
                if (high && high <= low) break;
                if (!high && probe >= MAX_PAGE) return;
                probe = high ? Math.ceil((low + high) / 2) : Math.min(MAX_PAGE, probe * 2);
                render();
            }
            if (high === low && current(record, generation)) {
                record.total = low;
                try { sessionStorage.setItem(countKey, JSON.stringify({count:low, time:Date.now()})); } catch (_) {}
                render();
            }
        } catch (_) { /* Keep confirmed links and an unrestricted direct jump when counting is unavailable. */ }
    }
    async function search() {
        cancel(); if (!state || !active() || !valid()) return;
        var record = state, generation = record.generation; record.running = true; render();
        // At most one batch of read-only GETs; a failed/limited search is explicitly incomplete.
        for (var fetched = 0; fetched < BATCH && !record.complete; fetched++) {
            var p = record.next;
            if (p === pageNumber()) { record.next++; if (!hasNext(document, p)) record.complete = true; continue; }
            if (record.pages.has(p)) {
                record.next++;
                if (!record.more.get(p) || precedes(record.pages.get(p))) record.complete = true;
                continue;
            }
            try {
                var result = await readPage(p, record, generation), rows = result.rows;
                record.pages.set(p, rows); record.more.set(p, result.more); record.pageSize = Math.max(record.pageSize, rows.length); record.next++;
                if (!rows.length || !result.more || precedes(rows)) record.complete = true;
                render();
            } catch (_) { break; }
        }
        if (!current(record, generation)) return;
        record.running = false; record.searched = true; render();
    }
    // The search field with its filter panel above the list, and the section that lists results from all pages.
    function build(table) {
        var bar = el('div', 'univer-app-news-tools'); bar.setAttribute('role', 'search');
        var input = function (type, cls, value) { var n = el('input', 'univer-app-news-' + cls); n.type = type; n.value = value; return n; };
        var button = function (cls) { var n = el('button', 'univer-app-news-' + cls); n.type = 'button'; return n; };
        var dateLabel = function (control) { var label = el('label', 'univer-app-news-date'); label.append(el('span'), control); return label; };
        var query = input('search', 'query', filter.query), from = input('date', 'from', filter.from), to = input('date', 'to', filter.to); query.maxLength = 300;
        var important = button('important'), reset = button('reset'), all = button('all'), filterCaption = button('filter-toggle');
        important.setAttribute('aria-pressed', String(filter.important));
        var counter = el('span', 'univer-app-news-count'); counter.setAttribute('role', 'status');
        // The filter button stays beside the search field; its panel opens on its own row underneath.
        var advanced = el('div', 'univer-app-news-advanced'), controls = el('div'); advanced.id = 'univer-app-news-filters';
        filterCaption.setAttribute('aria-controls', advanced.id);
        controls.append(dateLabel(from), dateLabel(to), important, reset, all, counter); advanced.append(controls);
        var filtersOpen = !!(filter.from || filter.to || filter.important);
        advanced.hidden = !filtersOpen; filterCaption.setAttribute('aria-expanded', String(filtersOpen));
        filterCaption.addEventListener('click', function () {
            var open = advanced.hidden, toggle = function () { advanced.hidden = !open; filterCaption.setAttribute('aria-expanded', String(open)); };
            if (window.__univerMobileV1?.mutate) window.__univerMobileV1.mutate(toggle); else toggle();
        });
        bar.append(query, filterCaption, advanced);
        var results = el('section', 'univer-app-news-results'); results.hidden = true;
        var anchor = table.closest('.univer-app-table-scroll') || table; anchor.before(bar, results);
        var page = pageNumber(), more = hasNext(document, page), rows = Array.from(table.querySelectorAll('tr.news'));
        state = {table, anchor, bar, query, from, to, important, reset, all, counter, filterCaption, results,
            hidden:new Map(), pages:new Map([[page, rows]]), more:new Map(), next:1, complete:page === 1 && !more,
            running:false, searched:false, generation:0, pageSize:Math.max(1, rows.length),
            known:page + (more ? 1 : 0), total:more ? 0 : page};
        if (!state.total) state.total = savedCount(state.known);
        if (typeof ResizeObserver === 'function') foldObserver = new ResizeObserver(scheduleFolds);
        window.addEventListener('resize', scheduleFolds);
        [query, from, to].forEach(n => n.addEventListener('input', changed));
        important.addEventListener('click', () => { important.setAttribute('aria-pressed', String(important.getAttribute('aria-pressed') !== 'true')); changed(); });
        reset.addEventListener('click', () => { query.value = from.value = to.value = ''; important.setAttribute('aria-pressed', 'false'); changed(); });
        all.addEventListener('click', search);
        if (active()) timer = setTimeout(search, 200);
        else if (!state.total) timer = setTimeout(discoverPages, 400);
    }
    function localize() {
        var t = text(), captions = state.bar.querySelectorAll('.univer-app-news-date>span');
        attr(state.query, 'placeholder', t.searchShort); attr(state.query, 'aria-label', t.search);
        setText(captions[0], t.from); setText(captions[1], t.to);
        setText(state.important, t.important); setText(state.reset, t.reset);
    }
    function apply(lang) {
        language = lang;
        // The site streams its bottom pager after the news rows. A partial document is not a one-page feed.
        if (document.readyState === 'loading') return;
        if (!/^\/news\/student(?:\/\d+)?\/?$/.test(location.pathname)) return;
        var table = document.querySelector('table.univer-app-news:not([data-univer-results])'); if (!table) return;
        if (state && state.table !== table) release();
        if (!state) build(table);
        localize();
        document.querySelectorAll('#l4 td.tb').forEach(cell => {
            if (!cell.classList.contains('univer-app-news-pager-source') && (cell.querySelector('a[href*="/my/news/"]') || /^(Страница|Page|Бет)\s*\d+/i.test(clean(cell))))
                cell.classList.add('univer-app-news-pager-source');
        });
        // Native polling calls apply repeatedly; only rerender on actual language/content changes.
        var rows = Array.from(table.querySelectorAll('tr.news')), signature = language + '|' + rows.map(clean).join('|');
        if (state.signature !== signature) { state.signature = signature; state.pages.set(pageNumber(), rows); render(); }
        scheduleFolds();
    }
    function release() {
        cancel(); if (!state) return;
        clearTimeout(foldTimer); foldTimer = null; window.removeEventListener('resize', scheduleFolds);
        foldObserver?.disconnect(); foldObserver = null;
        folds.forEach(unfold); folds.clear(); foldSources = new WeakMap(); expanded = new WeakMap();
        state.hidden.forEach((hidden, row) => { row.hidden = hidden; }); state.table.removeAttribute('data-univer-news-source');
        state.bar.remove(); state.results.remove(); document.querySelectorAll('.univer-app-news-pages,.univer-app-news-pager').forEach(n => n.remove());
        document.querySelectorAll('.univer-app-news-pager-source').forEach(n => n.classList.remove('univer-app-news-pager-source')); state = null;
    }
    window.__univerNewsV1 = {apply, release};
})();
