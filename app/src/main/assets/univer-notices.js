/* Reads the site's notification marks (red menu counts, messages asking for a questionnaire or test) and corrects the
   questionnaire badge in the mobile layout; never clicks or submits. [fetchSections] also reads the section tabs by
   GET (never the root: it opens the read notice). */
(function (fetchSections, generation, send, refresh) {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz') return null;
    if (!window.__univerNoticesV1) {
        var COUNT = /[(\[]\s*(\d{1,4})\s*[)\]]\s*$/;
        var MARK_CLASS = /(^|[\s_-])(red|danger|attention|warning|alert|badge|counter|count|notify|notification|unread)($|[\s_-])/i;
        var REQUIRED = /(необходимо|нужно|требуется|следует|обязательно|должн|пройдите|заполните|ответьте|оцените|қажет|міндетті|тиіс|өтіңіз|толтырыңыз|бағалаңыз|must|need to|required|please (complete|fill|take|answer))/i;
        var TASK = /(анкет|опрос|тест|оцен|сауалнам|сауалнама|тестіл|бағала|survey|questionnaire|test|evaluat|feedback)/i;
        var DONE = /^[+✓✔☑√]$/;
        var DATE = /\b\d{2}\.\d{2}\.\d{4}\b/g;
        var MESSAGE = '.message,.error,.warning,.alert,.attention,[style*="color"],font[color],p,li,div,span,td,b,strong';
        var SKIP = '#tools,#login_frm,#modal_auth_frm,#modal_agreement,#attentionSt,.news,.univer-app-news,.univer-app-menu,script,style,noscript,select,option,textarea';
        var MAX_LINKS = 120, MAX_SECTIONS = 4, MAX_LISTS = 3;
        var SURVEYS = '/student/rating/', MAX_SURVEYS = 10, READ_TIMEOUT = 15000, SURVEY_FRESH = 3 * 60 * 1000, SURVEY_SHOWN = 60 * 60 * 1000;
        var ACCOUNT = 'univer.app.account.v1', SURVEY_COUNT = 'univer.app.survey-count.v1';
        var badges = new Map(), presenting = false, surveyResult = null, lastCheck = null;
        var fetching = false, queuedRefreshes = [];

        function clean(value) { return String(value || '').replace(/\s+/g, ' ').trim(); }
        function quiet(action) {
            if (window.__univerMobileV1 && typeof window.__univerMobileV1.quiet === 'function') window.__univerMobileV1.quiet(action);
            else action();
        }
        function releaseMobileBadges() {
            if (window.__univerMobileV1 && typeof window.__univerMobileV1.releaseSurveyBadges === 'function') {
                quiet(function () { window.__univerMobileV1.releaseSurveyBadges(); });
            }
        }
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
        // The count is a red <b> after the link: <li><a href="/student/rating/">…</a><b style="color:Red">&nbsp;(2)</b></li>.
        // A red link without a number is a permanent highlight; an unmarked number ("Группа (2)") is part of the name.
        function item(a) {
            var li = a.closest('li');
            if (li && li.querySelectorAll('a[href]').length === 1) return {text:clean(li.textContent), node:li};
            var text = clean(a.textContent), next = a.nextElementSibling;
            return next && /^(B|SPAN|FONT|SUP|STRONG)$/.test(next.tagName) ? {text:clean(text + ' ' + next.textContent), node:next, link:a} : {text:text, node:a};
        }
        function surveyBadge(a) {
            var node = (a.closest('.univer-app-pin-row') || a).nextElementSibling;
            if (!node || node.tagName !== 'B' || !marked(node)) return null;
            var previous = badges.get(node);
            if (previous) {
                if (node.firstChild === previous.text && previous.text.data === previous.shown) return previous;
                restore(previous);
            }
            var text = node.firstChild;
            if (!text || text.nodeType !== 3 || text !== node.lastChild) return null;
            var count = clean(text.data).match(COUNT);
            return count ? {node:node, text:text, source:text.data, count:+count[1], hidden:node.getAttribute('hidden')} : null;
        }
        function menuBadges() {
            return Array.from(document.querySelectorAll('#tools a[href*="/student/rating"]')).filter(function (a) {
                return sitePath(a.getAttribute('href'), location.href) === SURVEYS;
            }).map(surveyBadge).filter(Boolean);
        }
        function owner() { try { return localStorage.getItem(ACCOUNT) || ''; } catch (_) { return ''; } }
        function cached(mark) {
            if (!mark) return null;
            try {
                var raw = localStorage.getItem(SURVEY_COUNT), value = raw && raw.length <= 10000 ? JSON.parse(raw) : null;
                if (value && value.owner === mark && Number.isInteger(value.count) && value.count > 0 && value.count <= 9999 &&
                    Number.isInteger(value.open) && value.open >= 0 && value.open <= MAX_SURVEYS) return value;
            } catch (_) { }
            return null;
        }
        function surveyFor(mark, count) {
            var result = surveyResult && surveyResult.owner === mark && surveyResult.count === count ? surveyResult : cached(mark);
            return result && result.count === count ? result : null;
        }
        // A check is reused for SURVEY_FRESH; the menu keeps showing it longer (up to an hour), so the answered number
        // does not come back between two checks.
        function freshSurvey(mark, count, limit) {
            var result = surveyFor(mark, count);
            if (!result || !Number.isFinite(result.at)) return null;
            var age = Date.now() - result.at;
            return age >= 0 && age < (limit || SURVEY_FRESH) && Array.isArray(result.details) && result.details.length === result.open &&
                result.details.every(function (detail) {
                    return detail && typeof detail.t === 'string' && detail.t.length > 0 && detail.t.length <= 100 &&
                        typeof detail.e === 'string' && (detail.e === '' || /^\d{2}\.\d{2}\.\d{4}$/.test(detail.e));
                }) ? result : null;
        }
        function restore(badge) {
            if (badge.text.data === badge.shown) badge.text.data = badge.source;
            if (badge.hidden === null) badge.node.removeAttribute('hidden');
            else badge.node.setAttribute('hidden', badge.hidden);
            badges.delete(badge.node);
        }
        function present(enabled) {
            presenting = !!enabled;
            releaseMobileBadges();
            quiet(function () {
                if (!presenting) { badges.forEach(restore); return; }
                var current = menuBadges();
                if (!current.length && !badges.size) return;
                var mark = owner();
                var live = new Set(current.map(function (badge) { return badge.node; }));
                badges.forEach(function (badge, node) { if (!live.has(node)) restore(badge); });
                current.forEach(function (badge) {
                    var result = freshSurvey(mark, badge.count, SURVEY_SHOWN), open = result ? result.open : null;
                    if (open === null || open === badge.count || badge.count === 0) {
                        if (badges.has(badge.node)) restore(badge);
                        return;
                    }
                    badges.set(badge.node, badge);
                    badge.shown = open === 0 ? badge.source : badge.source.replace(/\d{1,4}(?=\s*[)\]]\s*$)/, String(open));
                    if (badge.text.data !== badge.shown) badge.text.data = badge.shown;
                    if (badge.node.hidden !== (open === 0 || badge.hidden !== null)) badge.node.hidden = open === 0 || badge.hidden !== null;
                });
            });
        }
        function remember(mark, count, result) {
            var checked = result ? {owner:mark, count:count, open:result.o, details:result.d, at:Date.now()} : null;
            if (owner() !== mark) return checked;
            surveyResult = checked;
            try {
                if (mark && checked) localStorage.setItem(SURVEY_COUNT, JSON.stringify(checked));
                else if (cached(mark)?.count === count) localStorage.removeItem(SURVEY_COUNT);
            } catch (_) { }
            if (presenting) present(true);
            return checked;
        }
        function forgetChangedCount(mark, count) {
            if (surveyResult && surveyResult.owner === mark && surveyResult.count !== count) surveyResult = null;
            var saved = cached(mark);
            if (saved && saved.count !== count) try { localStorage.removeItem(SURVEY_COUNT); } catch (_) { }
        }
        function links(doc, base) {
            var out = [];
            doc.querySelectorAll('#tools a[href],#l3 a[href],#l2 a[href],.univer-app-header a[href],.univer-app-tabs a[href]').forEach(function (a) {
                if (out.length >= MAX_LINKS) return;
                var path = sitePath(a.getAttribute('href'), base), badge = path === SURVEYS ? surveyBadge(a) : null;
                var entry = badge ? {text:clean(a.textContent + ' ' + badge.source), node:badge.node} : item(a), text = entry.text;
                if (!path || !text || text.length > 200) return;
                var count = text.match(COUNT), title = clean(text.replace(COUNT, '')).slice(0, 100);
                if (count && !marked(entry.node) && !(entry.link && marked(entry.link))) count = null;
                var link = {u:path, t:count ? title || text.slice(0, 100) : text.slice(0, 100), n:count ? Math.min(+count[1], 9999) : 0};
                if (path === SURVEYS && link.n > 0) link.c = 1;
                if (a.closest('#tools')) link.m = 1;
                out.push(link);
            });
            return out;
        }
        // Each section has its own main menu (Главная, Бакалавр and E-услуги list different items): its active tab names it.
        function section(doc, base) {
            var tab = doc.querySelector('#l3 a.active[href],.univer-app-header a.active[href],.univer-app-tabs a.active[href]');
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
            var all = clean(root.textContent);
            if (!REQUIRED.test(all) || !TASK.test(all)) return out;
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
        // What a counted page lists: its record rows (tr.link, else rows with an id), header rows left out. o is how many
        // are not answered yet, d their names and last dates.
        function details(doc) {
            var root = doc.getElementById('l4') || doc.body, list = {d:[], o:0, r:0};
            if (!root) return list;
            var rows = root.querySelectorAll('tr.link');
            if (!rows.length) rows = root.querySelectorAll('tr[id]');
            Array.from(rows).forEach(function (row) {
                if (list.r >= 200 || row.closest('#tools') || row.cells.length < 2 || row.querySelector('th')) return;
                var cells = Array.from(row.cells).map(function (cell) { return clean(cell.textContent); });
                var name = cells.find(function (value) { return value.length >= 3 && !/^[\d.:\s/-]+$/.test(value); });
                if (!name) return;
                list.r++;
                if (cells.some(function (value) { return DONE.test(value); })) return;
                list.o++;
                if (list.d.length < 3) {
                    var dates = cells.join(' ').match(DATE);
                    list.d.push({t:name.slice(0, 100), e:dates ? dates[dates.length - 1] : ''});
                }
            });
            return list;
        }
        function describe(link, doc) {
            var list = details(doc);
            link.d = list.d;
            // A page without rows says nothing about the count.
            if (list.r > 0) link.o = list.o;
        }
        function surveyRows(doc) {
            var root = doc.getElementById('l4') || doc.body;
            if (!root) return [];
            return Array.from(root.querySelectorAll('tr.link')).filter(function (row) { return !row.closest('#tools'); });
        }
        // Checks every listed questionnaire (up to MAX_SURVEYS) against its own page within one shared deadline.
        async function look(doc, page) {
            var deadline = performance.now() + READ_TIMEOUT;
            function withinDeadline(path) {
                var remaining = deadline - performance.now();
                return remaining > 0 ? page(path, remaining) : null;
            }
            if (!doc) {
                var list = await withinDeadline(SURVEYS);
                if (!list) return null;
                doc = list.doc;
            }
            var rows = surveyRows(doc);
            if (!rows.length || rows.length > MAX_SURVEYS || rows.some(function (row) {
                return !/^\d{1,9}$/.test(row.id) || row.cells.length < 2 || !clean(row.cells[0].textContent);
            })) return null;
            var open = [], ids = new Set();
            for (var i = 0; i < rows.length; i++) {
                var row = rows[i];
                if (ids.has(row.id)) return null;
                ids.add(row.id);
                var detail = await withinDeadline(SURVEYS + row.id);
                if (!detail) return null;
                var lines = surveyRows(detail.doc);
                if (!lines.length || lines.some(function (line) { return line.cells.length < 3; })) return null;
                if (lines.every(function (line) {
                    var cell = line.cells[2];
                    return line.classList.contains('done') || !!cell.querySelector('img[alt*="[+]"]') || DONE.test(clean(cell.textContent));
                })) continue;
                var dates = Array.from(row.cells).map(function (cell) { return clean(cell.textContent); }).join(' ').match(DATE);
                open.push({t:clean(row.cells[0].textContent).slice(0, 100), e:dates ? dates[dates.length - 1] : ''});
            }
            return {o:open.length, d:open};
        }
        function read(doc, base, page, kind, gen) {
            var result = {v:1, g:gen, kind:kind, page:page, s:section(doc, base) || page, links:links(doc, base), blocks:blocks(doc, base, page)};
            result.links.forEach(function (link) { if (link.n > 0 && !link.c && link.u === page) describe(link, doc); });
            // The whole main menu was read (not cut by the cap): a menu item it no longer lists is gone.
            result.menu = !!doc.getElementById('tools') && result.links.length < MAX_LINKS && result.links.some(function (link) { return link.m; });
            return result;
        }
        async function readText(response, limit, charset) {
            if (+response.headers?.get('content-length') > limit) throw new Error('Oversized response');
            var decoder; try { decoder = new TextDecoder(charset); } catch (_) { decoder = new TextDecoder('utf-8'); }
            if (response.body && response.body.getReader) {
                var reader = response.body.getReader(), size = 0, parts = [], ended = false;
                try {
                    for (;;) {
                        var next = await reader.read();
                        if (next.done) { ended = true; break; }
                        size += next.value.byteLength;
                        if (size > limit) throw new Error('Oversized response');
                        parts.push(decoder.decode(next.value, {stream:true}));
                    }
                    parts.push(decoder.decode());
                    return parts.join('');
                } finally {
                    if (!ended) reader.cancel().catch(function () { });
                    reader.releaseLock();
                }
            }
            var bytes = await response.arrayBuffer();
            if (bytes.byteLength > limit) throw new Error('Oversized response');
            return decoder.decode(bytes);
        }
        async function load(path, timeout) {
            var abort = new AbortController(), timer = setTimeout(function () { abort.abort(); }, timeout || READ_TIMEOUT);
            try {
                var response = await fetch(path, {credentials:'same-origin', redirect:'follow', cache:'no-store', signal:abort.signal});
                var type = response.headers.get('content-type') || '';
                if (!response.ok || !/text\/html/i.test(type)) return null;
                var final = new URL(response.url);
                if (final.origin !== location.origin || final.pathname !== new URL(path, location.href).pathname) return null;
                var charset = (type.match(/charset=([\w-]+)/i) || [0, 'utf-8'])[1];
                var html = await readText(response, 3000000, charset);
                var doc = new DOMParser().parseFromString(html, 'text/html');
                // A sign-in page or a page without the menu (the read notice) says nothing about the section's marks.
                return doc.getElementById('login_frm') || !doc.getElementById('tools') ? false : {doc:doc, url:response.url};
            } finally { clearTimeout(timer); abort.abort(); }
        }
        function finishRead() {
            fetching = false;
            while (!fetching && queuedRefreshes.length) queuedRefreshes.shift()();
        }
        function check() {
            if (!presenting || fetching) return;
            var mark = owner(), counts = menuBadges().map(function (badge) { return badge.count; });
            var count = counts.length ? Math.max.apply(null, counts) : 0, now = performance.now();
            if (lastCheck && (lastCheck.owner !== mark || lastCheck.count !== count)) lastCheck = null;
            forgetChangedCount(mark, count);
            if (!count || freshSurvey(mark, count) || (lastCheck && now - lastCheck.at < SURVEY_FRESH)) return;
            lastCheck = {owner:mark, count:count, at:now};
            fetching = true;
            look(location.pathname === SURVEYS ? document : null, load).catch(function () { return null; })
                .then(function (result) { remember(mark, count, result); }).finally(finishRead);
        }
        function applySurvey(list, survey) {
            list.forEach(function (link) {
                delete link.c;
                if (survey) link.o = survey.open;
                link.d = survey ? survey.details : [];
            });
        }
        // Each section, then the lists the counts point to that no read has described yet (at most three in all).
        async function background(current, sections, counted, gen, channel, isRefresh, survey) {
            var lists = 0, seen = new Set(), done = 0, failed = 0, mark = owner();
            function post(value) { channel(JSON.stringify(value)); }
            async function page(path, timeout) {
                var loaded = await load(path, timeout).catch(function () { return null; });
                if (loaded) done++; else failed++;
                return loaded;
            }
            async function verify(list, doc) {
                if (!list.length) return;
                if (survey === undefined) {
                    var count = Math.max.apply(null, list.map(function (link) { return link.n; }));
                    survey = isRefresh ? null : freshSurvey(mark, count);
                    if (!survey) {
                        var failuresBefore = failed, result = await look(doc, page);
                        if (!result && failed === failuresBefore) failed++;
                        survey = remember(mark, count, result);
                    }
                }
                applySurvey(list, survey);
            }
            try {
                var surveys = current.links.filter(function (link) { return link.c; }).map(function (link) { return Object.assign({}, link); });
                if (surveys.length) {
                    await verify(surveys, location.pathname === SURVEYS ? document : null);
                    if (owner() !== mark) return;
                    // The app drops a page's messages missing from its report, so this one carries them too.
                    post({v:1, g:gen, kind:'section', page:current.page, s:current.s, links:surveys, blocks:current.blocks});
                }
                for (var i = 0; i < sections.length; i++) {
                    // A section that fails to load (a network error, a timeout) does not end the others; a sign-in page does.
                    var sectionPage = await page(sections[i]);
                    if (sectionPage === false) return;
                    if (!sectionPage) continue;
                    var result = read(sectionPage.doc, sectionPage.url, sections[i], 'section', gen);
                    await verify(result.links.filter(function (link) { return link.c; }), sections[i] === SURVEYS ? sectionPage.doc : null);
                    if (owner() !== mark) return;
                    for (var j = 0; j < result.links.length; j++) {
                        var link = result.links[j];
                        if (link.n <= 0 || link.d || lists >= MAX_LISTS || seen.has(link.u)) continue;
                        lists++; seen.add(link.u);
                        var target = await page(link.u);
                        if (target === false) return;
                        if (target) describe(link, target.doc);
                    }
                    post(result);
                }
                // A count on the current page whose list no section showed: its own read, sent as the list page itself.
                for (var k = 0; k < counted.length && lists < MAX_LISTS; k++) {
                    if (seen.has(counted[k])) continue;
                    lists++; seen.add(counted[k]);
                    var list = await page(counted[k]);
                    if (list === false) return;
                    if (list) {
                        var listing = read(list.doc, list.url, counted[k], 'section', gen);
                        await verify(listing.links.filter(function (link) { return link.c; }), null);
                        if (owner() !== mark) return;
                        post(listing);
                    }
                }
            } catch (_) { failed++; }
            finally {
                var summary = {v:1, g:gen, kind:'done', page:location.pathname + location.search, read:done, failed:failed};
                if (isRefresh) summary.refresh = isRefresh;
                post(summary);
            }
        }
        function scan(doFetch, gen, senders, isRefresh) {
            if (document.readyState === 'loading' || !document.body) return null;
            releaseMobileBadges();
            var page = location.pathname + location.search;
            var current = read(document, location.href, page, 'page', gen);
            var mark = owner(), menu = current.links.filter(function (link) { return link.m && link.u === SURVEYS; });
            if (menu.length) forgetChangedCount(mark, Math.max.apply(null, menu.map(function (link) { return link.n; })));
            var surveys = current.links.filter(function (link) { return link.c; }), verified;
            if (!isRefresh && surveys.length) {
                verified = freshSurvey(mark, Math.max.apply(null, surveys.map(function (link) { return link.n; }))) || undefined;
                if (verified) applySurvey(surveys, verified);
            }
            if (document.documentElement.classList.contains('univer-mobile')) present(true);
            // The app's own sender (ChannelGuard): it carries the key the app checks, so the site cannot forge this list.
            var channel = senders && typeof senders.notice === 'function' ? senders.notice : null;
            current.fetching = false;
            if (channel && fetching && isRefresh) {
                queuedRefreshes.push(function () {
                    if (owner() === mark && scan(doFetch, gen, senders, isRefresh)) return;
                    channel(JSON.stringify({v:1, g:current.g, kind:'done', page:page, read:0, failed:1, refresh:isRefresh}));
                });
                current.fetching = true;
            } else if (!fetching && channel) {
                var sections = [];
                if (doFetch) document.querySelectorAll('#l3 a[href],.univer-app-header a[href],.univer-app-tabs a[href]').forEach(function (a) {
                    var path = sitePath(a.getAttribute('href'), location.href);
                    if (path && path !== '/' && path !== page && sections.indexOf(path) < 0 && sections.length < MAX_SECTIONS && /^\/[a-z]+\/[a-z]*\/?$|^\/[a-z]+\/?$/i.test(path)) sections.push(path);
                });
                var counted = doFetch ? current.links.filter(function (link) { return link.n > 0 && !link.c && link.o === undefined && link.u !== page; })
                    .map(function (link) { return link.u; }).filter(function (path, index, all) { return all.indexOf(path) === index; })
                    : [];
                current.fetching = !!isRefresh || sections.length > 0 || counted.length > 0 || (surveys.length > 0 && !verified);
                if (current.fetching) {
                    fetching = true;
                    var start = function () {
                        background(current, sections, counted, gen, channel, isRefresh, verified).catch(function () { }).finally(finishRead);
                    };
                    // Section and survey reads wait until the page is idle, except when the student tapped Refresh.
                    if (isRefresh) start();
                    else if (typeof requestIdleCallback === 'function') requestIdleCallback(start, {timeout:4000});
                    else setTimeout(start, 1500);
                }
            }
            return JSON.stringify(current);
        }
        window.__univerNoticesV1 = {scan:scan, present:present, check:check};
    }
    return window.__univerNoticesV1.scan(fetchSections, generation, send, refresh);
})
