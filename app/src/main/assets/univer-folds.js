/* УМКД: every material section folds under its heading, whether it is a heading row of the files table or a heading over
   a block of links (Силлабус). The heading itself is the button, with the count of what it holds; every course opens
   with all its sections open, and a fold lasts only while the page is shown. A files section with two or more files gets «Скачать все» in its heading row: the app checks
   the addresses and saves the files it does not have yet. Original restores the headings and rows as they were.
   Shared behaviour with Windows. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerFoldsV1) return;
    var dom = window.__univerDomV1, clean = dom.clean, ownRows = dom.ownRows;
    var sections = new Map(), syllabi = new Map(), blanks = new Map(), language = 'ru', syllabusStyle;
    // Earlier versions kept folded sections per course here; the record goes once the module runs.
    try { localStorage.removeItem('univer.mobile.umkd-folds.v1'); } catch (_) { }
    function text() {
        return language === 'en' ? {all:'Download all', allLabel:'Download every file in this section', fresh:'New', freshHint:'Added since your last visit'} :
            language === 'kz' ? {all:'Барлығын жүктеу', allLabel:'Бөлімнің барлық файлын жүктеу', fresh:'Жаңа', freshHint:'Соңғы кіруіңізден кейін қосылды'} :
            {all:'Скачать все', allLabel:'Скачать все файлы раздела', fresh:'Новый', freshHint:'Появился после вашего прошлого захода'};
    }
    function coursePage() { return /^\/student\/umkd\/\d+\/\d+\/\d+\/?$/.test(location.pathname); }
    function nameOf(head) { return clean(head).slice(0, 200); }
    // A fold slides its rows shut and open rather than cutting them. One registered number, --univer-slide, runs between
    // 1 and 0, and a rule scales each row's height, vertical padding, margins and borders by it: the card rules set those
    // with !important, which keyframes cannot move. The folded attribute is the state from the first frame; a row sliding
    // shut stays on screen until its run ends. The run's own keyframes carry the measures, so no inline style is written.
    var slides = new Map(), slideRegistered = null;
    function slideReady() {
        var root = document.documentElement;
        if (window.__univerMotion === false || !root.classList.contains('univer-mobile') || root.classList.contains('univer-app-still') ||
            root.classList.contains('univer-app-keyboard') || window.__univerMotionUI?.reduced?.() || !root.animate) return false;
        if (slideRegistered === null) {
            try { CSS.registerProperty({name:'--univer-slide', syntax:'<number>', inherits:false, initialValue:'1'}); slideRegistered = true; }
            catch (error) { slideRegistered = !!error && error.name === 'InvalidModificationError'; }
        }
        return slideRegistered;
    }
    function slideMeasures(item) {
        var style = getComputedStyle(item);
        // An inline item, inline blocks too, sits in a line that keeps its height until the item is hidden: it is cut at once.
        // (A child of a flex or grid row is blockified, so it never reports inline-*.)
        if (/^(none|contents|inline|table|ruby)/.test(style.display)) return null;
        var height = item.getBoundingClientRect().height;
        if (height < 1) return null;
        return {'--univer-slide-h':height + 'px', '--univer-slide-pt':style.paddingTop, '--univer-slide-pb':style.paddingBottom, '--univer-slide-mt':style.marginTop,
            '--univer-slide-mb':style.marginBottom, '--univer-slide-bt':style.borderTopWidth, '--univer-slide-bb':style.borderBottomWidth};
    }
    function stopSlide(item) {
        var run = slides.get(item);
        if (!run) return;
        slides.delete(item); item.removeAttribute('data-univer-sliding'); run.animation.cancel();
    }
    // Sets the row's state and slides it there when motion is on; a reversal starts from where the row is now.
    function slideRow(item, closing) {
        var run = slides.get(item), from = closing ? 1 : 0, measures = run ? run.measures : null;
        if (run) { var now = parseFloat(getComputedStyle(item).getPropertyValue('--univer-slide')); if (now >= 0 && now <= 1) from = now; stopSlide(item); }
        var ready = slideReady();
        if (!closing) item.removeAttribute('data-univer-folded-row');
        if (ready && !measures) measures = slideMeasures(item);
        if (closing) item.setAttribute('data-univer-folded-row', '');
        if (!ready || !measures || !item.animate) return;
        var to = closing ? 0 : 1, frame = function (value) { var result = {'--univer-slide':String(value)}; Object.keys(measures).forEach(function (k) { result[k] = measures[k]; }); return result; };
        item.setAttribute('data-univer-sliding', '');
        var animation = item.animate([frame(from), frame(to)], {duration:Math.max(90, Math.round((closing ? 260 : 320) * Math.abs(to - from))),
            easing:closing ? 'cubic-bezier(.4,0,.2,1)' : 'cubic-bezier(.22,1,.36,1)', fill:'forwards'});
        var mine = {animation:animation, measures:measures};
        slides.set(item, mine);
        animation.onfinish = function () { if (slides.get(item) === mine) stopSlide(item); };
    }
    function paint(record, animate) {
        var closed = record.closed, state = closed ? 'closed' : 'open';
        if (record.head.getAttribute('aria-expanded') !== String(!closed)) record.head.setAttribute('aria-expanded', String(!closed));
        if (record.head.dataset.univerFold !== state) record.head.dataset.univerFold = state;
        record.items.forEach(function (item) {
            if (item.hasAttribute('data-univer-folded-row') === closed) return;
            if (animate) { slideRow(item, closed); return; }
            stopSlide(item);
            if (closed) item.setAttribute('data-univer-folded-row', ''); else item.removeAttribute('data-univer-folded-row');
        });
    }
    // A material without a file type and with a zero size is a teacher's link: the site answers it with a redirect to that
    // page, so it is no file to save (the same rule marks such rows on Windows).
    function linkRow(row, link) {
        if (/\.[a-z0-9]{2,5}\s*$/i.test(clean(link))) return false;
        return Array.from(row.cells).some(function (cell, i) { return i > 1 && /^0(?:[.,]0+)?\s*(?:б|b|байт|bytes?)$/i.test(clean(cell)); });
    }
    function sectionFiles(items) {
        var found = [];
        items.forEach(function (row) {
            var link = row.querySelector('a.downLoad[href]'), url = null;
            if (!link || linkRow(row, link)) return;
            try { url = new URL(link.href); } catch (_) { url = null; }
            if (url && url.origin === location.origin && /^\/student\/umkd\/get\/\d+\/\d+\/\d+\/\d+\/?$/.test(url.pathname) && found.indexOf(url.href) < 0) found.push(url.href);
        });
        return found;
    }
    // The button is a child of the heading's row, not of the heading: the heading's text names the fold and the folder.
    // The app takes at most 200 files in one message, so a larger section has no button rather than one that does nothing.
    // The button shows only where the app takes the message: the keyed channel with its serializer and its listener
    // registration (MobilePageAdapter's __univerJson and __univerListen, null on a WebView without document-start scripts
    // and in a document window). Both were taken before any site script ran, so a swapped JSON.stringify cannot change
    // the message and a swapped addEventListener never gets the handler to call with a lookalike of a tap.
    function serializer() { return typeof __univerNative === 'function' && typeof __univerJson === 'function' ? __univerJson : null; }
    // The prefix is fixed here. The keyed sender serializes the object, so the text of the message is not chosen by the page.
    function deliver(prefix, value) {
        if (typeof __univerPost === 'function') { __univerPost(prefix, value); return; }
        var body = typeof __univerJson === 'function' ? __univerJson(value) : null;
        if (typeof body === 'string' && typeof __univerNative === 'function') __univerNative(prefix + body);
    }
    function listener() { return typeof __univerListen === 'function' ? __univerListen : null; }
    // The channel's own note of the tapped heading row (MobilePageAdapter's __univerMark): its number among the rows of
    // #l4 with one TH cell, how many there are, and a number that names this very row to the app's lookup. The lookup
    // counts the same way with the same built-ins, and the app refuses the batch unless the count and the row agree.
    function marker() { return typeof __univerMark === 'function' ? __univerMark : null; }
    function allButton(record) {
        var button = record.all, count = record.head.tagName === 'TH' && serializer() && listener() && marker() ? sectionFiles(record.items).length : 0;
        if (count < 2 || count > 200) { if (button) { button.remove(); record.all = null; } return; }
        if (!button) {
            button = record.all = document.createElement('button'); button.type = 'button'; button.className = 'univer-app-fold-all';
            // Only the student's own tap downloads: the channel calls this for a click the browser marks trusted, nothing else.
            listener()(button, 'click', function (event) {
                event.preventDefault(); event.stopPropagation();
                if (!event.isTrusted) return;
                // A double tap sends the section once.
                var urls = sectionFiles(record.items), now = performance.now(), json = serializer();
                if (record.sent != null && now - record.sent < 1500) return;
                record.sent = now;
                // The serializer taken at document start: a JSON.stringify or toJSON the site swapped in cannot change it.
                // The app checks again that every address belongs to this section of the open page.
                var mark = marker(), tap = urls.length && json && mark ? mark(record.head) : null;
                if (tap) deliver('univer-download-all:', {section:nameOf(record.head), head:tap.head, count:tap.count, mark:tap.mark, urls:urls});
            });
        }
        var row = record.head.parentNode, t = text();
        if (row && button.parentNode !== row) row.appendChild(button);
        if (button.dataset.label !== t.all) button.dataset.label = t.all;
        if (button.getAttribute('aria-label') !== t.allLabel) button.setAttribute('aria-label', t.allLabel);
    }
    function section(head, items) {
        var record = sections.get(head);
        if (!record) {
            var attrs = ['role','tabindex','aria-expanded','data-univer-fold','data-univer-count'];
            record = {head:head, items:items, closed:false, attrs:attrs.map(function (name) { return [name, head.getAttribute(name)]; })};
            record.toggle = function (event) {
                if (event.type === 'keydown' && event.key !== 'Enter' && event.key !== ' ') return;
                if (event.target !== head && event.target.closest('a,button,input,select,textarea')) return;
                event.preventDefault();
                record.closed = !record.closed; paint(record, event.type === 'keydown' || event.detail > 0);
                var own = syllabi.get(head);
                if (own?.status) own.status.hidden = record.closed;
                if (record.closed) own?.order.flush();
            };
            head.addEventListener('click', record.toggle); head.addEventListener('keydown', record.toggle);
            head.setAttribute('role', 'button'); head.tabIndex = 0;
            sections.set(head, record);
        } else record.items = items;
        var count = String(items.length);
        if (head.dataset.univerCount !== count) head.dataset.univerCount = count;
        paint(record, false);
        allButton(record);
    }
    var SYLLABUS = /^(?:(?:интерактивный|интерактивті|interactive)\s+)?(?:силлабус|syllabus)\s*:?$/i;
    var NOT_FILLED = /^(не\s+заполнен[оаы]?|толтырылма(ған|ды|й\s+қалған)|not\s+(filled|completed)(\s+in)?)$/i;
    function unfilled(head) {
        var box = head.parentElement;
        if (!box?.matches('div,td.tt') || box.querySelectorAll('h4').length !== 1 ||
            box.querySelector('a[href],img,iframe,video,button,input,select,table')) return false;
        var words = '', node = head.nextSibling;
        while (node) { words += ' ' + (node.textContent || ''); node = node.nextSibling; }
        node = head.previousSibling;
        while (node) { if (clean(node)) return false; node = node.previousSibling; }
        return NOT_FILLED.test(words.replace(/[\s()[\].,:;!–—-]+/g, ' ').trim());
    }
    function restoreAttr(node, name, value) {
        if (value === null) node.removeAttribute(name); else node.setAttribute(name, value);
    }
    function mountSyllabusStyle() {
        if (syllabusStyle?.isConnected) return;
        syllabusStyle = document.createElement('style');
        syllabusStyle.id = 'univer-umkd-syllabus-style';
        var css = [
            'html:root:root.univer-mobile #l4 [data-univer-empty-syllabus] { display:none!important; }',
            'html:root:root.univer-mobile #l4 td.tt a[data-univer-syllabus-link] { display:flex!important; flex-wrap:wrap; align-items:center; gap:4px 8px; box-sizing:border-box; width:100%; min-height:44px; padding:8px 12px!important; line-height:1.35; text-align:start; white-space:normal; overflow-wrap:break-word; overflow-wrap:anywhere; }',
            'html:root:root.univer-mobile #l4 td.tt a[data-univer-folded-row]:not([data-univer-sliding]) { display:none!important; }',
            'html:root:root.univer-mobile #l4 td.tt a[data-univer-syllabus-link]:focus { outline:2px solid var(--u-brand); outline-offset:-2px; }',
            'html:root:root.univer-mobile #l4 td.tt a[data-univer-syllabus-link]:active { background:var(--u-brand-soft); }',
            'html:root:root.univer-mobile #l4 td.tt a[data-univer-syllabus-link="own"] { border-inline-start:3px solid var(--u-brand); background:var(--u-brand-soft); }',
            'html:root:root.univer-mobile #l4 td.tt .univer-app-own-tag { background:var(--u-surface); color:var(--u-brand)!important; border-radius:4px; font-size:12px; font-weight:600; line-height:1.4; padding:2px 6px; }',
            'html:root:root.univer-mobile #l4 td.tt .univer-app-own-status { margin:6px 0!important; padding:6px 12px; color:var(--u-muted)!important; font-size:13px; line-height:1.4; }',
            'html:root:root.univer-mobile #l4 td.tt .univer-app-own-status[hidden] { display:none!important; }'
        ].join('\n');
        if (window.__univerPaletteV1) window.__univerPaletteV1.fill(syllabusStyle, css); else syllabusStyle.textContent = css;
        (document.head || document.documentElement).appendChild(syllabusStyle);
    }
    function unmarkSyllabus(record) {
        record.order.release();
        record.status?.remove(); record.tag?.remove(); record.space?.remove();
        record.links.forEach(function (item) { restoreAttr(item.link, 'data-univer-syllabus-link', item.attr); });
        var home = record.home;
        if (home && home.node.parentNode === home.parent) home.parent.insertBefore(home.node, home.next?.parentNode === home.parent ? home.next : null);
    }
    function ownSection(head, items) {
        var api = window.__univerSyllabusV1;
        if (!api?.peekOwn || !SYLLABUS.test(clean(head))) return;
        var links = [];
        items.forEach(function (item) {
            var anchors = item.matches('a[href]') ? [item] : Array.from(item.querySelectorAll('a[href]'));
            anchors.forEach(function (link) {
                var path = api.printPath(link.getAttribute('href'));
                if (path) links.push({item:item, link:link, path:path});
            });
        });
        var record = syllabi.get(head), who = api.account(), labels = api.ownText(language);
        if (record?.who === who && record.links.length === links.length && links.every(function (item) {
            return record.links.some(function (old) { return old.link === item.link && old.path === item.path; });
        })) {
            if (record.tag) dom.setText(record.tag, labels.tag);
            if (record.status) dom.setText(record.status, labels.pending);
            return;
        }
        if (record) { unmarkSyllabus(record); syllabi.delete(head); }
        if (!links.length) return;
        mountSyllabusStyle();
        links.forEach(function (item) {
            item.attr = item.link.getAttribute('data-univer-syllabus-link');
            item.link.setAttribute('data-univer-syllabus-link', '');
        });
        var box = head.parentElement, fold = sections.get(head);
        record = {links:links, who:who, order:api.stableOrder(box, function () { return fold.closed; })};
        syllabi.set(head, record);
        var finish = function (path) {
            record.status?.remove(); record.status = null;
            var match = links.find(function (item) { return item.path === path; });
            if (syllabi.get(head) !== record || who !== api.account() || !match?.link.isConnected) return;
            var tag = document.createElement('span');
            tag.className = 'univer-app-own-tag'; tag.textContent = api.ownText(language).tag;
            record.space = document.createTextNode(' '); record.tag = tag; match.link.append(record.space, tag);
            match.link.setAttribute('data-univer-syllabus-link', 'own');
            record.order.move(function () {
                var first = links[0], node = match.item, before = head.nextSibling;
                if (match === first || node.parentNode !== box) return;
                if (match.item === first.item) {
                    if (match.link.parentNode !== first.link.parentNode) return;
                    node = match.link; before = first.link;
                }
                record.home = {node:node, parent:node.parentNode, next:node.nextSibling};
                node.parentNode.insertBefore(node, before);
            });
        };
        var hrefs = links.map(function (item) { return item.link.href; }), course = location.pathname;
        var cached = api.peekOwn(hrefs, course);
        if (cached !== undefined) { finish(cached); return; }
        var status = record.status = document.createElement('p');
        status.className = 'univer-app-own-status'; status.setAttribute('role', 'status');
        status.hidden = fold.closed; status.textContent = labels.pending; head.after(status);
        api.own(hrefs, course).then(finish, function () { finish(null); });
    }
    /* «Новое»: files that appeared since the previous visit to this course page. A visit is an app session (a
       sessionStorage mark) or 12 hours, like the grades. Per course page the record holds the file ids of the previous
       visit (base) and of this one (cur); nothing is new on the first visit, for another account or without the account
       mark. Only numeric file ids are kept, at most 400 per page and 40 pages. */
    var SEEN_KEY = 'univer.app.umkd-seen.v1', VISIT_KEY = 'univer.app.umkd-visit.v1', VISIT_AGE = 12 * 3600000;
    var SEEN_PAGES = 40, SEEN_FILES = 400, SEEN_SIZE = 100000;
    var baseline, baselinePath = '', stored = '', freshMarks = [];
    function fileId(link) {
        try {
            var url = new URL(link.getAttribute('href'), location.href);
            var found = url.origin === location.origin && url.pathname.match(/^\/student\/umkd\/get\/(\d{1,12})\/\d{1,12}\/\d{1,6}\/\d{1,6}\/?$/);
            return found ? found[1] : '';
        } catch (_) { return ''; }
    }
    function fresh(rows) {
        var who = ''; try { who = localStorage.getItem('univer.app.account.v1') || ''; } catch (_) { }
        // A page without files (a notice, an error) is no first look.
        if (!who || !rows.length) return new Set();
        var ids = [];
        rows.forEach(function (row) { var link = row.querySelector('a.downLoad[href]'), id = link ? fileId(link) : ''; if (id && ids.indexOf(id) < 0) ids.push(id); });
        var path = location.pathname.replace(/\/$/, ''), store = null, now = Date.now(), visited = false;
        try { store = JSON.parse(localStorage.getItem(SEEN_KEY) || 'null'); } catch (_) { store = null; }
        try { visited = sessionStorage.getItem(VISIT_KEY) === '1'; } catch (_) { }
        if (!store || typeof store !== 'object' || store.owner !== who || !store.pages || typeof store.pages !== 'object' || Array.isArray(store.pages)) store = {owner:who, at:0, pages:{}};
        // Read once per document: later passes (and Original, then reading mode) mark from memory. The record is written
        // then and when the list changes, not on every adapter pass.
        var changed = false;
        if (baseline === undefined || baselinePath !== path) {
            changed = true;
            if (!visited || !(now - store.at >= 0 && now - store.at < VISIT_AGE)) {
                Object.keys(store.pages).forEach(function (name) { var page = store.pages[name]; if (page && Array.isArray(page.cur)) page.base = page.cur; });
                store.at = now;
                try { sessionStorage.setItem(VISIT_KEY, '1'); } catch (_) { }
            }
            var known = store.pages[path];
            baselinePath = path;
            baseline = known && Array.isArray(known.base) ? new Set(known.base.filter(function (id) { return typeof id === 'string'; })) : null;
        }
        var page = store.pages[path] && typeof store.pages[path] === 'object' ? store.pages[path] : (store.pages[path] = {});
        var cur = ids.slice(-SEEN_FILES);
        if (!Array.isArray(page.cur) || page.cur.join(',') !== cur.join(',')) { page.cur = cur; changed = true; }
        if (changed) page.seen = now;
        if (!changed) return marks(ids);
        Object.keys(store.pages).sort(function (a, b) { return (store.pages[b].seen || 0) - (store.pages[a].seen || 0); }).slice(SEEN_PAGES).forEach(function (old) { delete store.pages[old]; });
        try { var raw = JSON.stringify(store); if (raw.length <= SEEN_SIZE && raw !== stored) { localStorage.setItem(SEEN_KEY, raw); stored = raw; } } catch (_) { }
        return marks(ids);
    }
    // A page with more files than the record keeps is not marked: its earliest files would read as new on every visit.
    function marks(ids) {
        return baseline && ids.length <= SEEN_FILES ? new Set(ids.filter(function (id) { return !baseline.has(id); })) : new Set();
    }
    function markFresh(rows) {
        var news = fresh(rows), t = text();
        rows.forEach(function (row) {
            var link = row.querySelector('a.downLoad[href]'), id = link ? fileId(link) : '';
            if (!link || !id || !news.has(id)) return;
            var mark = link.nextElementSibling;
            if (!mark || !mark.classList.contains('univer-app-file-new')) {
                mark = document.createElement('span'); mark.className = 'univer-app-file-new'; link.after(mark); freshMarks.push(mark);
            }
            if (mark.textContent !== t.fresh) mark.textContent = t.fresh;
            if (mark.title !== t.freshHint) mark.title = t.freshHint;
        });
    }
    function apply(lang) {
        language = lang || 'ru';
        if (!coursePage()) return;
        var fileRows = [];
        document.querySelectorAll('#l4 table.univer-app-materials').forEach(function (table) {
            var current = null, groups = [];
            ownRows(table).forEach(function (row) {
                if (row.cells.length === 1 && row.cells[0].tagName === 'TH') { current = {head:row.cells[0], items:[]}; groups.push(current); }
                else if (current && row.matches('tr.file')) { current.items.push(row); fileRows.push(row); }
            });
            groups.forEach(function (group) { if (group.items.length && clean(group.head)) section(group.head, group.items); });
        });
        markFresh(fileRows);
        var empty = new Set();
        document.querySelectorAll('#l4 td.tt h4').forEach(function (h4) {
            if (h4.closest('#tools')) return;
            if (SYLLABUS.test(clean(h4)) && unfilled(h4)) {
                var box = h4.parentElement;
                mountSyllabusStyle(); empty.add(box);
                if (!blanks.has(box)) {
                    blanks.set(box, box.getAttribute('data-univer-empty-syllabus'));
                    box.setAttribute('data-univer-empty-syllabus', '');
                }
                return;
            }
            var items = [], node = h4.nextElementSibling;
            while (node && node.tagName !== 'H4') {
                if (node !== syllabi.get(h4)?.status) items.push(node);
                node = node.nextElementSibling;
            }
            if (items.length && clean(h4)) { section(h4, items); ownSection(h4, items); }
        });
        blanks.forEach(function (value, box) {
            if (!empty.has(box)) { restoreAttr(box, 'data-univer-empty-syllabus', value); blanks.delete(box); }
        });
    }
    function release() {
        freshMarks.forEach(function (mark) { mark.remove(); }); freshMarks = [];
        Array.from(slides.keys()).forEach(stopSlide);
        syllabi.forEach(unmarkSyllabus); syllabi.clear();
        blanks.forEach(function (value, box) { restoreAttr(box, 'data-univer-empty-syllabus', value); }); blanks.clear();
        syllabusStyle?.remove(); syllabusStyle = null;
        sections.forEach(function (record, head) {
            head.removeEventListener('click', record.toggle); head.removeEventListener('keydown', record.toggle);
            record.attrs.forEach(function (attr) { restoreAttr(head, attr[0], attr[1]); });
            record.items.forEach(function (item) { item.removeAttribute('data-univer-folded-row'); });
            if (record.all) record.all.remove();
        });
        sections.clear();
    }
    window.__univerFoldsV1 = {apply:apply, release:release};
})();
