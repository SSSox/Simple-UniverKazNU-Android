/* Reads the site's notification marks (red menu counts, messages asking for a questionnaire or test); never clicks or
   submits. [fetchSections] also reads the section tabs by GET (never the root: it opens the read notice). */
(function (fetchSections, generation, send) {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || document.readyState === 'loading' || !document.body) return null;
    var COUNT = /[(\[]\s*(\d{1,4})\s*[)\]]\s*$/;
    var MARK_CLASS = /(^|[\s_-])(red|danger|attention|warning|alert|badge|counter|count|notify|notification|unread)($|[\s_-])/i;
    var REQUIRED = /(необходимо|нужно|требуется|следует|обязательно|должн|пройдите|заполните|ответьте|оцените|қажет|міндетті|тиіс|өтіңіз|толтырыңыз|бағалаңыз|must|need to|required|please (complete|fill|take|answer))/i;
    var TASK = /(анкет|опрос|тест|оцен|сауалнам|сауалнама|тестіл|бағала|survey|questionnaire|test|evaluat|feedback)/i;
    var MESSAGE = '.message,.error,.warning,.alert,.attention,[style*="color"],font[color],p,li,div,span,td,b,strong';
    var SKIP = '#tools,#login_frm,#modal_auth_frm,#modal_agreement,#attentionSt,.news,.univer-app-news,.univer-app-menu,script,style,noscript,select,option,textarea';
    var SURVEYS = /^\/student\/rating\/$/;

    function clean(value) { return String(value || '').replace(/\s+/g, ' ').trim(); }
    function red(value) {
        var color = String(value || '').trim().toLowerCase(), m;
        if (/^(red|crimson|darkred|firebrick|orangered)$/.test(color)) return true;
        if ((m = color.match(/^#([0-9a-f])([0-9a-f])([0-9a-f])$/))) color = '#' + m[1] + m[1] + m[2] + m[2] + m[3] + m[3];
        if ((m = color.match(/^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})/))) m = [0, parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)];
        else m = color.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/);
        return !!m && +m[1] >= 170 && +m[2] <= 100 && +m[3] <= 100;
    }
    // Marked in the site's markup (computed colours are the app's in the phone layout): a red colour or a badge class.
    function marked(node) {
        return [node].concat(Array.from(node.querySelectorAll('*'))).some(function (element) {
            var style = element.getAttribute('style') || '', color = style.match(/(?:^|;)\s*color\s*:\s*([^;!]+)/i);
            return (color && red(color[1])) || (element.tagName === 'FONT' && red(element.getAttribute('color'))) ||
                (typeof element.className === 'string' && MARK_CLASS.test(element.className) && !/univer-app-/.test(element.className));
        });
    }
    // A same-origin page address (path and query), or null for files, account, language and script links.
    function sitePath(href, base) {
        try {
            var url = new URL(href, base);
            if (url.origin !== location.origin || !/^\/[^\s]*$/.test(url.pathname)) return null;
            if (/^\/(content|lang|user)\//i.test(url.pathname) || /\.(pdf|docx?|xlsx?|pptx?|zip|rar)$/i.test(url.pathname)) return null;
            return url.pathname + url.search;
        } catch (_) { return null; }
    }
    // The count is a red <b> after the link: <a …>Анкетирование студентов</a><b style="color:Red">&nbsp;(2)</b>.
    // A red link without a number is a permanent highlight; an unmarked number ("Группа (2)") is part of the name.
    function item(a) {
        var li = a.closest('li');
        if (li && li.querySelectorAll('a[href]').length === 1) return {text:clean(li.textContent), node:li};
        var text = clean(a.textContent), next = a.nextElementSibling;
        return next && /^(B|SPAN|FONT|SUP|STRONG)$/.test(next.tagName) ? {text:clean(text + ' ' + next.textContent), node:next, link:a} : {text:text, node:a};
    }
    function links(doc, base) {
        var out = [];
        doc.querySelectorAll('#tools a[href],#l3 a[href],#l2 a[href],.univer-app-header a[href]').forEach(function (a) {
            if (out.length >= 120) return;
            var path = sitePath(a.getAttribute('href'), base), entry = item(a), text = entry.text;
            if (!path || !text || text.length > 200) return;
            var count = text.match(COUNT), title = clean(text.replace(COUNT, '')).slice(0, 100);
            if (count && !marked(entry.node) && !(entry.link && marked(entry.link))) count = null;
            var link = {u:path, t:count ? title || text.slice(0, 100) : text.slice(0, 100), n:count ? Math.min(+count[1], 9999) : 0};
            if (a.closest('#tools')) link.m = 1;
            out.push(link);
        });
        return out;
    }
    // Each section has its own main menu (Главная, Бакалавр and E-услуги list different items): its active tab names it.
    function section(doc, base) {
        var tab = doc.querySelector('#l3 a.active[href]');
        return tab ? sitePath(tab.getAttribute('href'), base) : null;
    }
    function heading(doc) {
        var top = doc.querySelector('#l4 tr.top>td.ct') || doc.querySelector('h1,h2');
        return clean(top ? top.textContent : doc.title).slice(0, 100);
    }
    // The smallest marked element whose text both asks for something and names a questionnaire or a test.
    function blocks(doc, base, page) {
        var root = doc.getElementById('l4') || doc.body, out = [], texts = new Set();
        if (!root) return out;
        root.querySelectorAll(MESSAGE).forEach(function (node) {
            if (out.length >= 5 || node.closest(SKIP)) return;
            var text = clean(node.textContent);
            if (text.length < 15 || text.length > 400 || !REQUIRED.test(text) || !TASK.test(text)) return;
            if (!marked(node) && !node.matches('.message,.error,.warning,.alert,.attention') && !node.closest('.message,.error,.warning,.alert')) return;
            var inner = Array.from(node.children).some(function (child) {
                var value = clean(child.textContent);
                return value.length >= 15 && REQUIRED.test(value) && TASK.test(value);
            });
            if (inner || texts.has(text)) return;
            texts.add(text);
            var target = Array.from(node.querySelectorAll('a[href]')).map(function (a) { return sitePath(a.getAttribute('href'), base); }).find(Boolean);
            out.push({u:page, t:heading(doc), x:text.slice(0, 280), l:target || ''});
        });
        return out;
    }
    // What a counted page lists (its open questionnaires or tests): the record rows' names, last dates and ids.
    function records(doc) {
        var out = [];
        (doc.getElementById('l4') || doc.body)?.querySelectorAll('tr[id],tr.link').forEach(function (row) {
            if (out.length >= 10 || row.closest('#tools') || row.cells.length < 2) return;
            var name = clean(row.cells[0].textContent).slice(0, 100), dates = Array.from(row.cells).map(function (cell) { return clean(cell.textContent); }).join(' ').match(/\b\d{2}\.\d{2}\.\d{4}\b/g);
            if (name.length >= 3) out.push({t:name, e:dates ? dates[dates.length - 1] : '', id:row.id});
        });
        return out;
    }
    function details(rows) { return rows.slice(0, 3).map(function (row) { return {t:row.t, e:row.e}; }); }
    function scan(doc, base, page, kind) {
        var result = {v:1, g:generation, kind:kind, page:page, s:section(doc, base) || page, links:links(doc, base), blocks:blocks(doc, base, page)};
        result.links.forEach(function (link) { if (link.n > 0 && link.u === page) link.d = details(records(doc)); });
        // The whole main menu was read (not cut by the cap): a menu item it no longer lists is gone.
        result.menu = !!doc.getElementById('tools') && result.links.length < 120 && result.links.some(function (link) { return link.m; });
        return result;
    }
    async function read(path) {
        var abort = new AbortController(), timer = setTimeout(function () { abort.abort(); }, 15000);
        try {
            var response = await fetch(path, {credentials:'same-origin', redirect:'follow', cache:'no-store', signal:abort.signal});
            var type = response.headers.get('content-type') || '';
            if (!response.ok || new URL(response.url).pathname !== new URL(path, location.href).pathname || !/text\/html/i.test(type)) return null;
            var bytes = await response.arrayBuffer();
            if (bytes.byteLength > 3000000) return null;
            var charset = (type.match(/charset=([\w-]+)/i) || [0, 'utf-8'])[1];
            var html; try { html = new TextDecoder(charset).decode(bytes); } catch (_) { html = new TextDecoder('utf-8').decode(bytes); }
            var doc = new DOMParser().parseFromString(html, 'text/html');
            // A sign-in page or a page without the menu (the read notice) says nothing about the section's marks.
            return doc.getElementById('login_frm') || !doc.getElementById('tools') ? false : {doc:doc, url:response.url};
        } finally { clearTimeout(timer); }
    }
    // A questionnaire's own page (/student/rating/208) has a line per teacher or topic, marked once answered:
    // <tr class="link done"> with the [+] icon. True when every line is marked, null when the page says nothing.
    async function answered(id) {
        if (!/^\d{1,9}$/.test(id)) return null;
        var page = await read('/student/rating/' + id).catch(function () { return null; });
        if (!page) return null;
        var lines = Array.from(page.doc.querySelectorAll('#l4 tr.link')).filter(function (line) { return !line.closest('#tools'); });
        if (!lines.length) return null;
        return lines.every(function (line) { return line.classList.contains('done') || !!line.querySelector('img[alt*="[+]"]'); });
    }
    // A counted link's details from its page ([doc] when it is already open). For the questionnaires they are the
    // ones left to answer, and [o] how many those are, set only when every listed one could be checked.
    async function look(link, doc) {
        var rows = records(doc), left = [];
        link.d = details(rows);
        if (!SURVEYS.test(link.u) || !rows.length || rows.length > 5) return;
        for (var i = 0; i < rows.length; i++) {
            var done = await answered(rows[i].id);
            if (done === null) return;
            if (!done) left.push(rows[i]);
        }
        link.d = details(left);
        link.o = left.length;
    }

    var page = location.pathname + location.search;
    var current = scan(document, location.href, page, 'page');
    // The app's own sender (ChannelGuard): it carries the key the app checks, so the site cannot forge this list.
    var channel = send && typeof send.notice === 'function' ? send.notice : null;
    current.fetching = false;
    if (fetchSections && channel && !window.__univerNoticeFetch) {
        var sections = [];
        document.querySelectorAll('#l3 a[href],.univer-app-header a[href]').forEach(function (a) {
            var path = sitePath(a.getAttribute('href'), location.href);
            if (path && path !== '/' && path !== page && sections.indexOf(path) < 0 && sections.length < 4 && /^\/[a-z]+\/[a-z]*\/?$|^\/[a-z]+\/?$/i.test(path)) sections.push(path);
        });
        // A questionnaire count on this page is sent again once checked; the app does not list it before that.
        var surveys = current.links.filter(function (link) { return link.n > 0 && SURVEYS.test(link.u); });
        surveys.forEach(function (link) { link.c = 1; });
        current.fetching = sections.length > 0 || surveys.length > 0;
        if (current.fetching) window.__univerNoticeFetch = true;
        // This page's questionnaire count, each section, then the pages the counts point to (at most 3 in all), for
        // the names and dates they list.
        if (current.fetching) (async function () {
            var opened = 0, seen = {};
            async function count(link, doc) {
                var known = seen[link.u];
                if (known) { link.d = known.d; if (known.o !== undefined) link.o = known.o; return; }
                if (!doc) {
                    if (opened >= 3) return;
                    opened++;
                    var target = await read(link.u).catch(function () { return null; });
                    if (!target) return;
                    doc = target.doc;
                }
                await look(link, doc);
                seen[link.u] = {d:link.d, o:link.o};
            }
            if (surveys.length) {
                var again = scan(document, location.href, page, 'section');
                for (var k = 0; k < again.links.length; k++) {
                    if (again.links[k].n > 0 && SURVEYS.test(again.links[k].u)) await count(again.links[k], again.links[k].u === page ? document : null);
                }
                channel(JSON.stringify(again));
            }
            for (var i = 0; i < sections.length; i++) {
                // One section that fails to load (a network error, a timeout) does not end the others.
                var section = await read(sections[i]).catch(function () { return null; });
                if (section === false) break;
                if (!section) continue;
                var result = scan(section.doc, section.url, sections[i], 'section');
                for (var j = 0; j < result.links.length; j++) {
                    var link = result.links[j];
                    if (link.n <= 0 || (link.d && !SURVEYS.test(link.u))) continue;
                    await count(link, link.u === sections[i] ? section.doc : null);
                }
                channel(JSON.stringify(result));
            }
        })().catch(function () { }).finally(function () { window.__univerNoticeFetch = false; });
    }
    return JSON.stringify(current);
})
