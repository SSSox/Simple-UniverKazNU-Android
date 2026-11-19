/* News filters belong to the whole result list; source rows and site handlers stay intact. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerNewsV1) return;
    var dom = window.__univerDomV1, setText = dom.setText, setHidden = dom.setHidden, el = dom.el;
    var key = 'univer.desktop.news-filter.v1', countKey = 'univer.desktop.news-page-count.v1', state = null, language = 'ru', timer = null;
    var folds = new Map(), foldSources = new WeakMap(), expanded = new WeakMap(), foldObserver = null, foldTimer = null, foldSequence = 0;
    // Bounds of the read-only page fetches: one response and its wait, one search batch, the highest page ever probed.
    var MAX_HTML = 4 * 1024 * 1024, READ_TIMEOUT = 10000, BATCH = 30, MAX_PAGE = 4096;
    // A search starts this long after the filter changes or the list is built. The count of pages is kept for a day.
    var SEARCH_DELAY = 200, COUNT_AGE = 86400000;
    // An article taller than LONG pixels folds to a preview of whole lines about FOLD high.
    var FOLD = 132, LONG = 176;
    // Content with no text of its own: a paragraph holding one of these is not blank (an audio player, a drawn image).
    var MEDIA = 'img,picture,table,iframe,video,audio,object,embed,svg,canvas,math,input,select,textarea,button,hr';
    var WORDS = {
        en: {period:'Period', anyDate:'Any date', week:'Past week', month:'Past month', half:'Past 6 months', search:'Search news', searchShort:'Search', from:'From', to:'To', important:'Important only', shown:'Shown', of:'of', all:'Search all pages', reset:'Reset', searching:'Searching…', page:'Page', go:'Go', none:'No news match these filters', failed:'Search is incomplete. Retry to check the remaining pages.', retry:'Continue search', invalid:'The start date must not be after the end date.', expand:'Show the full article', collapse:'Collapse'},
        kz: {period:'Кезең', anyDate:'Кез келген күн', week:'Соңғы апта', month:'Соңғы ай', half:'Соңғы 6 ай', search:'Жаңалықтарды іздеу', searchShort:'Іздеу', from:'Бастап', to:'Дейін', important:'Тек маңызды', shown:'Көрсетілді', of:'/', all:'Барлық беттерден іздеу', reset:'Тазалау', searching:'Іздеу…', page:'Бет', go:'Өту', none:'Бұл сүзгілерге сәйкес жаңалықтар жоқ', failed:'Іздеу аяқталмады. Қалған беттерді тексеру үшін қайталаңыз.', retry:'Іздеуді жалғастыру', invalid:'Басталу күні аяқталу күнінен кейін болмауы керек.', expand:'Толық көрсету', collapse:'Жасыру'},
        ru: {period:'Период', anyDate:'Любая дата', week:'За неделю', month:'За месяц', half:'За полгода', search:'Поиск по новостям', searchShort:'Поиск', from:'С', to:'По', important:'Только важные', shown:'Показано', of:'из', all:'Искать на всех страницах', reset:'Сбросить', searching:'Поиск…', page:'Страница', go:'Перейти', none:'По этим условиям новостей нет', failed:'Поиск не завершён. Повторите, чтобы проверить остальные страницы.', retry:'Продолжить поиск', invalid:'Дата «С» должна быть не позже даты «По».', expand:'Показать полностью', collapse:'Свернуть'}
    };
    var filter = restoreFilter();

    /* The filter and the page count kept in this tab. */
    function restoreFilter() {
        try {
            var saved = JSON.parse(sessionStorage.getItem(key));
            if (saved && typeof saved.query === 'string' && saved.query.length <= 300 &&
                [saved.from, saved.to].every(v => typeof v === 'string' && (!v || /^\d{4}-\d{2}-\d{2}$/.test(v))))
                return {query:saved.query, from:saved.from, to:saved.to, important:saved.important === true, page:Math.max(1, Math.min(1000, +saved.page || 1))};
        } catch (_) { /* Storage may be unavailable; filtering still works in this document. */ }
        return {query:'', from:'', to:'', important:false, page:1};
    }
    // A page count found earlier stays usable for a day, across app starts: counting reads up to 24 whole news pages.
    function savedCount(known) {
        try {
            var cached = JSON.parse(localStorage.getItem(countKey)), age = cached ? Date.now() - cached.time : -1;
            if (cached && Number.isInteger(cached.count) && cached.count >= known && cached.count <= MAX_PAGE && age >= 0 && age < COUNT_AGE) return cached.count;
        } catch (_) {}
        return 0;
    }
    function save() { try { if (active()) sessionStorage.setItem(key, JSON.stringify(filter)); else sessionStorage.removeItem(key); } catch (_) {} }

    /* Text. */
    function text() { return WORDS[language] || WORDS.ru; }
    function clean(node) {
        // Search and signatures use the complete article, excluding our disclosure control.
        if (node?.matches?.('tr.news')) return Array.from(node.cells).map(cell => clean(cell.querySelector(':scope > .univer-app-news-body') || cell)).join(' ');
        return (node?.textContent || '').replace(/\s+/g, ' ').trim();
    }
    /** Writes only real changes, so DOM observers are not woken for nothing. */
    function attr(node, name, value) { if (node.getAttribute(name) !== value) node.setAttribute(name, value); }
    /** Text for matching a search: lower case, "ё" read as "е". */
    function foldCase(value) { return value.toLocaleLowerCase().replace(/ё/g, 'е'); }

    /* Long articles fold to a preview with a disclosure button. */
    // The preview ends on a whole line: a fixed height would cut the last one through its letters.
    // Only boxes drawn inside the article count: editors leave hidden paste buffers (TinyMCE's #_mcePaste, placed at
    // left:-10000px, 1px wide) whose one-letter "lines" would otherwise end the preview between two real lines.
    function foldHeight(body) {
        var box = body.getBoundingClientRect(), top = box.top, range = document.createRange(), end = 0;
        Array.from(body.childNodes).some(node => {
            range.selectNodeContents(node);
            var lines = Array.from(range.getClientRects()).filter(line => line.width > 1 && line.height > 1 &&
                line.right > box.left && line.left < box.right);
            lines.forEach(line => {
                var bottom = line.bottom - top;
                if (bottom <= FOLD + 1 && bottom > end) end = bottom;
            });
            return lines.some(line => line.top - top > FOLD);
        });
        return Math.ceil(end) || FOLD;
    }
    // An article's fold is measured, then written. A page of news measures every article before writing any: a read
    // after a write lays the whole page out again, once per article (over 100 ms on a phone).
    function measureFold(record) {
        if (!record.body.getBoundingClientRect().width) return null; // Hidden search/source pages have no useful geometry.
        var long = record.body.scrollHeight > LONG, open = expanded.get(record.source) === true;
        return {long, open, height:long && !open ? foldHeight(record.body) : 0};
    }
    function writeFold(record, fold) {
        if (!fold) return;
        var t = text();
        attr(record.body, 'data-collapsed', String(fold.long && !fold.open));
        if (fold.long && !fold.open) attr(record.body, 'style', '--univer-news-fold:' + fold.height + 'px');
        setHidden(record.button, !fold.long);
        attr(record.button, 'aria-expanded', String(!fold.long || fold.open));
        setText(record.button, fold.long ? (fold.open ? t.collapse : t.expand) : '');
    }
    function updateFold(record) { writeFold(record, measureFold(record)); }
    function unfold(record) {
        window.__univerMotionUI?.stop(record.body);
        foldObserver?.unobserve(record.body); record.button.remove();
        record.body.querySelectorAll(':scope > [data-univer-blank]').forEach(node => node.removeAttribute('data-univer-blank'));
        if (record.body.parentNode) record.body.replaceWith(...record.body.childNodes);
    }
    // Blank paragraphs between an article's lines would fill its short preview with nothing.
    function markBlank(body) {
        Array.from(body.children).forEach(node => {
            if (/^(?:P|DIV)$/.test(node.tagName) && !clean(node) && !node.querySelector(MEDIA)) node.setAttribute('data-univer-blank', '');
        });
    }
    // The disclosure button: opens or closes the article, animated only for a pointer click.
    function toggleFold(record, event) {
        var body = record.body, row = record.row;
        event.stopPropagation();
        var wasOpen = expanded.get(record.source) === true, before = body.getBoundingClientRect().height, motion = window.__univerMotionUI;
        motion?.stop(body);
        expanded.set(record.source, !wasOpen); updateFold(record);
        if (event.detail > 0) motion?.resize(body, before, wasOpen ? 180 : 220);
        if (!wasOpen && event.detail > 0) motion?.reveal(body, 'content');
        if (wasOpen && row.getBoundingClientRect().top < 80) row.scrollIntoView?.({block:'start', behavior:event.detail > 0 && window.__univerMotion !== false && !motion?.reduced() ? 'smooth' : 'auto'});
    }
    // Moves the article of [row] into a collapsible body followed by its disclosure button.
    function createFold(row, cell) {
        var body = el('div', 'univer-app-news-body'), button = el('button', 'univer-app-news-toggle');
        body.id = 'univer-news-body-' + (++foldSequence);
        body.append(...cell.childNodes);
        markBlank(body);
        button.type = 'button'; button.hidden = true; button.setAttribute('aria-controls', body.id);
        cell.append(body, button);
        var record = {body, button, row, source:foldSources.get(row) || row};
        button.addEventListener('click', event => toggleFold(record, event));
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
        var records = [];
        [state.table, state.results].forEach(root => root.querySelectorAll('tr.news').forEach(row => {
            var record = folds.get(row), cell = row.cells[1]; if (!cell) return;
            if (!record) { record = createFold(row, cell); folds.set(row, record); }
            records.push(record);
        }));
        records.map(measureFold).forEach((fold, index) => writeFold(records[index], fold));
    }
    function scheduleFolds() {
        if (!state || foldTimer !== null) return;
        foldTimer = setTimeout(() => { foldTimer = null; refreshFolds(); }, 0);
    }

    /* The filter. */
    function pageNumber() { return +(location.pathname.match(/^\/news\/student\/(\d+)/)?.[1] || 1); }
    function href(page) { return page > 1 ? '/news/student/' + page : '/news/student'; }
    function active() { return !!(filter.query.trim() || filter.from || filter.to || filter.important); }
    function valid() { return !(filter.from && filter.to && filter.from > filter.to); }
    function date(row) {
        var box = row.cells[0]?.querySelector('.date'), day = +clean(box?.querySelector('.day'));
        var parts = clean(box).match(/(\d{1,2})\.(\d{4})\s*$/);
        return day && parts ? parts[2] + '-' + parts[1].padStart(2, '0') + '-' + String(day).padStart(2, '0') : '';
    }
    function isoDay(day) { return day.getFullYear() + '-' + String(day.getMonth() + 1).padStart(2, '0') + '-' + String(day.getDate()).padStart(2, '0'); }
    function matches(row) {
        var stamp = date(row);
        return (!filter.from || (stamp && stamp >= filter.from)) && (!filter.to || (stamp && stamp <= filter.to)) &&
            (!filter.important || /важное|маңызды|important/i.test(clean(row.cells[0]))) &&
            foldCase(filter.query.trim()).split(/\s+/).every(word => foldCase(clean(row)).includes(word));
    }
    // News are newest first: a whole dated page before the lower bound means no older page can match.
    function precedes(rows) {
        return !!filter.from && rows.length > 0 && rows.every(row => { var stamp = date(row); return !!stamp && stamp < filter.from; });
    }

    /* Other pages of the list, read with same-origin GETs. */
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
    // Unwraps an element outside the passive set, and keeps only inert attributes: https links, images from the site.
    function sanitize(node) {
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
    }
    // Fetched HTML is inert. Only passive, sanitized copies are inserted into the result list.
    function safeRow(row) {
        var clone = row.cloneNode(true);
        clone.querySelectorAll('.univer-app-news-toggle').forEach(n => n.remove());
        clone.querySelectorAll('.univer-app-news-body').forEach(n => n.replaceWith(...n.childNodes));
        clone.querySelectorAll('script,style,iframe,frame,object,embed,form,input,button,svg,math,link,meta').forEach(n => n.remove());
        [clone, ...clone.querySelectorAll('*')].forEach(node => sanitize(node));
        clone.hidden = false;
        return clone;
    }
    async function readPage(p, record, generation) {
        var controller = new AbortController(); record.controller = controller;
        var timeout = setTimeout(() => controller.abort(), READ_TIMEOUT);
        try {
            var response = await fetch(href(p), {credentials:'same-origin', cache:'no-store', redirect:'error', signal:controller.signal});
            var url = new URL(response.url, location.origin);
            if (response.status !== 200 || url.origin !== location.origin || url.pathname.replace(/\/$/, '') !== href(p) || +response.headers?.get('content-length') > MAX_HTML) throw new Error('Unexpected news response');
            var html = await response.text(); if (html.length > MAX_HTML) throw new Error('Oversized news response');
            if (!isCurrent(record, generation)) throw new Error('Stale news response');
            var doc = new DOMParser().parseFromString(html, 'text/html'), rows = Array.from(doc.querySelectorAll('tr.news'));
            if (doc.querySelector('#login_frm') || (!rows.length && !doc.querySelector('td.tb'))) throw new Error('News unavailable');
            var displayed = displayedPage(doc);
            if (displayed && displayed !== p) throw new Error('News page was clamped');
            return {rows, more:hasNext(doc, p)};
        } finally { clearTimeout(timeout); }
    }

    /* Page navigation above and below the list. */
    function showPage(page) { filter.page = page; save(); render(); }
    // The first five pages, the last one and two on either side of [current], in order.
    function visiblePages(current, count) {
        var pages = new Set([1, count]);
        for (var first = 1; first <= Math.min(5, count); first++) pages.add(first);
        for (var p = Math.max(1, current - 2); p <= Math.min(count, current + 2); p++) pages.add(p);
        return Array.from(pages).sort((a, b) => a - b);
    }
    function pageLink(p, current, filtered) {
        var a = el('a', p === current ? 'univer-app-news-current' : '', String(p)); a.href = filtered ? '#news-results-' + p : href(p);
        if (p === current) a.setAttribute('aria-current', 'page');
        if (filtered) a.addEventListener('click', e => { e.preventDefault(); showPage(p); state.bar.scrollIntoView?.({block:'start'}); });
        return a;
    }
    // A direct jump: within the result pages, or to any site page up to the known end (the probing limit while unknown).
    function jumpForm(current, count, filtered, exact) {
        var t = text(), form = el('form', 'univer-app-news-jump'), number = el('input'), go = el('button', '', t.go);
        number.type = 'number'; number.min = '1'; if (exact) number.max = String(count); number.value = String(current); number.setAttribute('aria-label', t.page);
        go.type = 'submit';
        form.append(el('span', 'univer-app-news-jump-label', t.page), number, go);
        form.addEventListener('submit', e => {
            e.preventDefault(); var target = Math.min(exact ? count : MAX_PAGE, Math.max(1, parseInt(number.value, 10) || 1));
            if (filtered) showPage(target); else location.assign(href(target));
        });
        return form;
    }
    function pager(current, count, filtered, exact) {
        var t = text(), nav = el('nav', 'univer-app-news-pager'); nav.setAttribute('aria-label', t.page);
        if (count <= 1) nav.hidden = true;
        var previous = 0;
        visiblePages(current, count).forEach(p => {
            if (previous && p > previous + 1) nav.appendChild(el('span', 'univer-app-news-gap', '…'));
            nav.appendChild(pageLink(p, current, filtered)); previous = p;
        });
        nav.appendChild(jumpForm(current, count, filtered, exact));
        return nav;
    }
    function removePagers() { document.querySelectorAll('.univer-app-news-pages,.univer-app-news-pager').forEach(n => n.remove()); }
    function replacePagers(current, count, filtered, exact = true) {
        var signature = [current, count, filtered, exact, language].join(':');
        if (state.pagerSignature === signature) return;
        // A count that arrives while the student is in the page navigation keeps it open, and the number being typed.
        var wasOpen = !!document.querySelector('.univer-app-news-pages')?.open, focused = document.activeElement;
        var typing = focused && focused.matches?.('.univer-app-news-jump input') ? {bottom:!focused.closest('.univer-app-news-pages'), value:focused.value} : null;
        removePagers();
        state.pagerSignature = signature;
        var top = pager(current, count, filtered, exact), bottom = pager(current, count, filtered, exact);
        top.classList.add('univer-app-news-pager-top');
        var pages = el('details', 'univer-app-news-pages');
        // A result that fits on one page needs no page navigation at all.
        pages.hidden = top.hidden;
        pages.append(el('summary', '', text().page + ' ' + current + (exact ? ' / ' + count : '')), top);
        pages.open = wasOpen && !pages.hidden;
        state.bar.after(pages); state.anchor.after(bottom);
        if (typing) { var input = (typing.bottom ? bottom : top).querySelector('.univer-app-news-jump input'); input.value = typing.value; input.focus(); }
    }

    /* Rendering. */
    // The site's own hidden state of a news row, recorded before any filter hid it.
    function sourceHidden(row) { return state.hidden.has(row) ? state.hidden.get(row) : row.hidden; }
    // Hides the source rows the filter excludes and counts those left.
    function filterSource() {
        var rows = Array.from(state.table.querySelectorAll('tr.news')), shown = 0;
        rows.forEach(row => {
            if (!state.hidden.has(row)) state.hidden.set(row, row.hidden);
            var visible = !sourceHidden(row) && (!active() || (valid() && matches(row)));
            setHidden(row, !visible);
            if (visible) shown++;
        });
        return shown;
    }
    // The bar's reset line, period name and "search all pages" button.
    function renderBar(t) {
        state.reset.hidden = !active(); state.line.hidden = !active();
        var from = state.from.value, to = state.to.value, short = v => v.slice(8, 10) + '.' + v.slice(5, 7);
        setText(state.periodName, from || to ? (from ? short(from) : '…') + ' – ' + (to ? short(to) : '…') : t.period);
        state.period.classList.toggle('univer-app-news-period-on', !!(from || to));
        state.all.hidden = !active() || state.running || !state.searched || state.complete || !valid();
        setText(state.all, state.next > 1 ? t.retry : t.all);
    }
    // Without a filter: the site's own list with page navigation as far as the count is known.
    function renderSource(t, shown) {
        state.table.removeAttribute('data-univer-news-source'); state.results.hidden = true;
        setText(state.counter, t.shown + ' ' + shown);
        replacePagers(pageNumber(), state.total || state.known, false, !!state.total);
        refreshFolds();
    }
    // The matches on every page read so far, in page order.
    function allMatches() {
        var found = [];
        state.pages.forEach((items, p) => items.forEach((row, i) => { if (!sourceHidden(row) && valid() && matches(row)) found.push({row:row, page:p, index:i}); }));
        found.sort((a, b) => a.page - b.page || a.index - b.index);
        return found;
    }
    // With a filter: one page of matches from all pages in place of the source list.
    function renderResults(t) {
        var found = allMatches();
        var count = Math.max(1, Math.ceil(found.length / state.pageSize));
        // A restored page is retained while earlier pages are still being fetched.
        if (!state.running && state.searched) filter.page = Math.min(filter.page, count);
        var current = Math.min(filter.page, count);
        state.table.setAttribute('data-univer-news-source', 'true'); state.results.hidden = false;
        state.results.textContent = '';
        var status = el('p', 'univer-app-news-status', !valid() ? t.invalid : state.running ? t.searching : !state.complete ? t.failed : found.length ? '' : t.none);
        status.setAttribute('role', 'status'); status.hidden = !status.textContent; state.results.appendChild(status);
        var table = el('table', 'univer-app-news'), body = el('tbody'); table.dataset.univerResults = 'true'; table.appendChild(body);
        var slice = found.slice((current - 1) * state.pageSize, current * state.pageSize);
        slice.forEach(hit => { var row = safeRow(hit.row); foldSources.set(row, hit.row); body.appendChild(row); }); state.results.appendChild(table);
        setText(state.counter, t.shown + ' ' + slice.length + ' ' + t.of + ' ' + found.length + (state.running || !state.complete ? '+' : ''));
        replacePagers(current, count, true); save(); refreshFolds();
    }
    function render() {
        if (!state) return;
        var t = text(), shown = filterSource();
        renderBar(t);
        if (!active()) renderSource(t, shown);
        else renderResults(t);
    }

    /* Searching and counting pages. */
    function cancel() { clearTimeout(timer); if (state) { state.generation++; state.controller?.abort(); state.running = false; } }
    /** Whether the search or count of [record] started in [generation] is still the one on screen. */
    function isCurrent(record, generation) { return state === record && record.generation === generation; }
    function changed() {
        cancel(); filter.page = 1;
        filter.query = state.query.value.slice(0, 300); filter.from = state.from.value; filter.to = state.to.value; filter.important = state.important.getAttribute('aria-pressed') === 'true';
        state.next = 1; state.complete = pageNumber() === 1 && !hasNext(document, 1); state.searched = false;
        save(); render();
        if (active() && valid()) timer = setTimeout(search, SEARCH_DELAY);
    }
    // The site's pager shows only a next arrow, so the last page is found by reading pages: only once the student
    // turns to the page navigation (opens it, touches or focuses it), not on every visit to the news.
    function wantCount(event) {
        if (!state || state.total || state.counting || active() || !event.target.closest?.('.univer-app-news-pages,.univer-app-news-pager')) return;
        state.counting = true;
        discoverPages().finally(() => { if (state) state.counting = false; });
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
            }
            // Drawn once, with the final count: each redraw replaces the page navigation the student is using.
            if (high === low && isCurrent(record, generation)) {
                record.total = low;
                try { localStorage.setItem(countKey, JSON.stringify({count:low, time:Date.now()})); } catch (_) {}
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
        if (!isCurrent(record, generation)) return;
        record.running = false; record.searched = true; render();
    }

    /* The filter bar. */
    function toolInput(type, cls, value) { var n = el('input', 'univer-app-news-' + cls); n.type = type; n.value = value; return n; }
    function toolButton(cls) { var n = el('button', 'univer-app-news-' + cls); n.type = 'button'; return n; }
    function dateLabel(control) { var label = el('label', 'univer-app-news-date'); label.append(el('span'), control); return label; }
    // The search field with its filter panel above the list, and the section that lists results from all pages.
    function createControls() {
        var bar = el('div', 'univer-app-news-tools'); bar.setAttribute('role', 'search');
        var query = toolInput('search', 'query', filter.query), from = toolInput('date', 'from', filter.from), to = toolInput('date', 'to', filter.to); query.maxLength = 300;
        var important = toolButton('important'), reset = toolButton('reset'), all = toolButton('all');
        important.setAttribute('aria-pressed', String(filter.important));
        var counter = el('span', 'univer-app-news-count'); counter.setAttribute('role', 'status');
        // Dates are the rarest filter, so they live behind one button with the usual ranges first.
        var period = el('details', 'univer-app-news-period'), periodName = el('summary'), periodBox = el('div', 'univer-app-news-period-box'), presets = el('div', 'univer-app-news-presets');
        periodBox.append(presets, dateLabel(from), dateLabel(to)); period.append(periodName, periodBox);
        var line = el('div', 'univer-app-news-state'); line.hidden = true; line.append(counter, all, reset);
        bar.append(query, period, important, line);
        var results = el('section', 'univer-app-news-results'); results.hidden = true;
        return {bar, query, from, to, important, reset, all, counter, results, period, periodName, periodBox, presets, line};
    }
    // The usual date ranges, each up to today.
    function addPresets(c) {
        [['anyDate', 0], ['week', 7], ['month', 31], ['half', 183]].forEach(item => {
            var preset = toolButton('preset'); preset.dataset.key = item[0];
            preset.addEventListener('click', () => {
                var day = new Date(Date.now() - item[1] * 86400000), iso = isoDay(day);
                c.from.value = item[1] ? iso : ''; c.to.value = ''; c.period.open = false; changed();
            });
            c.presets.appendChild(preset);
        });
    }
    function build(table) {
        var c = createControls(), anchor = table.closest('.univer-app-table-scroll') || table; anchor.before(c.bar, c.results);
        var page = pageNumber(), more = hasNext(document, page), rows = Array.from(table.querySelectorAll('tr.news'));
        state = Object.assign({table, anchor}, c, {
            hidden:new Map(), pages:new Map([[page, rows]]), more:new Map(), next:1, complete:page === 1 && !more,
            running:false, searched:false, generation:0, pageSize:Math.max(1, rows.length),
            known:page + (more ? 1 : 0), total:more ? 0 : page});
        if (!state.total) state.total = savedCount(state.known);
        if (typeof ResizeObserver === 'function') foldObserver = new ResizeObserver(scheduleFolds);
        window.addEventListener('resize', scheduleFolds);
        [c.query, c.from, c.to].forEach(n => n.addEventListener('input', changed));
        c.important.addEventListener('click', () => { c.important.setAttribute('aria-pressed', String(c.important.getAttribute('aria-pressed') !== 'true')); changed(); });
        c.reset.addEventListener('click', () => { c.query.value = c.from.value = c.to.value = ''; c.important.setAttribute('aria-pressed', 'false'); changed(); });
        c.all.addEventListener('click', search);
        addPresets(c);
        c.period.addEventListener('toggle', () => { if (c.period.open) window.__univerMotionUI?.reveal(c.periodBox, 'popup'); });
        state.closePeriod = event => { if (c.period.open && !c.period.contains(event.target)) c.period.open = false; };
        document.addEventListener('pointerdown', state.closePeriod, true);
        ['pointerdown', 'focusin', 'toggle'].forEach(type => document.addEventListener(type, wantCount, true));
        if (active()) timer = setTimeout(search, SEARCH_DELAY);
    }
    function localize() {
        var t = text(), captions = state.bar.querySelectorAll('.univer-app-news-date>span');
        attr(state.query, 'placeholder', t.searchShort); attr(state.query, 'aria-label', t.search);
        setText(captions[0], t.from); setText(captions[1], t.to);
        setText(state.important, t.important); setText(state.reset, t.reset);
        Array.from(state.presets.children).forEach(preset => setText(preset, t[preset.dataset.key]));
    }
    // The site's own pager cells, set aside by the mobile style while the app's page navigation stands in for them.
    function markPagerSources() {
        document.querySelectorAll('#l4 td.tb').forEach(cell => {
            if (!cell.classList.contains('univer-app-news-pager-source') && (cell.querySelector('a[href*="/my/news/"]') || /^(Страница|Page|Бет)\s*\d+/i.test(clean(cell))))
                cell.classList.add('univer-app-news-pager-source');
        });
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
        markPagerSources();
        // Native polling calls apply repeatedly; only rerender on actual language/content changes.
        var rows = Array.from(table.querySelectorAll('tr.news')), signature = language + '|' + rows.map(clean).join('|');
        if (state.signature !== signature) { state.signature = signature; state.pages.set(pageNumber(), rows); render(); }
        scheduleFolds();
    }
    function release() {
        cancel(); if (!state) return;
        clearTimeout(foldTimer); foldTimer = null; window.removeEventListener('resize', scheduleFolds);
        document.removeEventListener('pointerdown', state.closePeriod, true);
        ['pointerdown', 'focusin', 'toggle'].forEach(type => document.removeEventListener(type, wantCount, true));
        foldObserver?.disconnect(); foldObserver = null;
        folds.forEach(unfold); folds.clear(); foldSources = new WeakMap(); expanded = new WeakMap();
        state.hidden.forEach((hidden, row) => { row.hidden = hidden; }); state.table.removeAttribute('data-univer-news-source');
        state.bar.remove(); state.results.remove(); removePagers();
        document.querySelectorAll('.univer-app-news-pager-source').forEach(n => n.classList.remove('univer-app-news-pager-source')); state = null;
    }
    window.__univerNewsV1 = {apply, release};
})();
