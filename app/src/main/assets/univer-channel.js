/* Document-start key of the app's message channels (ChannelGuard.kt replaces the secret). It runs before any site
   script in the main frame: Reflect.apply and each channel's postMessage are taken here, so a site script that later
   replaces them neither sees nor stops the app's messages. __univerClaim cannot be redefined and hands the senders,
   which put the secret in front of each message, only to a caller that knows the secret: the app's modules get it
   inside their injected source, the site never does.
   json is a serializer taken here as well: a JSON.stringify, Object.keys or toJSON the site sets up later cannot change
   what a tap sends. It copies plain data (strings, numbers, booleans, null, arrays and objects with their own enumerable
   keys) into objects without a prototype, so no inherited toJSON or getter takes part, and drops anything else.
   post(prefix, value) is the only sender that may start a tap. The prefix is one of the list below, compared as a whole,
   and the body is json(value): a string the page built cannot choose another command's prefix, and a body of the wrong
   shape for that prefix is not sent. The raw sender keys only a report (the week, the totals, the exams). It will not
   put the secret on a pin, a download, a grade report, a calendar event or a schedule export, so a text the page
   composed cannot be passed off as one of those. The same list and lengths are MobilePageAdapter's NATIVE.
   listen registers a module's handler for an action (a pin, «Скачать все») through addEventListener as it is here, so a
   site script that swaps it later never gets the handler to call with an object of its own; the handler runs only for
   an event the browser itself marks trusted, read through the isTrusted getter taken here (a lookalike object makes
   that getter throw). It returns the function that removes the handler again.
   mark notes, when the student taps «Скачать все», which heading row was tapped: it keeps that row itself (not its
   text or place) under a new number and returns the number, the heading's number among the rows of #l4 with one TH
   cell and how many such rows there are, all counted here. The fold module sends these three in its message.
   places reads, for the app, where the open UMKD page lists given files: how many heading rows #l4 has now and, per
   address, the course, the heading over the file, that heading's number (-1 for none) and whether it is the very row the
   tap marked (a sibling with the same text that took the tapped heading's number after it was removed is not). It uses
   only built-ins taken here, so a script that swapped querySelectorAll, a getter or JSON.stringify cannot make up the
   answer, and the app refuses the whole batch unless the count and the marked row agree with the tap. The page is a
   course's UMKD page, /student/umkd/{course}/{year}/{term} with any term number and any query or fragment, the same
   shape as DownloadBatch.coursePage. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window !== window.top) return;
    var secret = '__UNIVER_CHANNEL_SECRET__', apply = Reflect.apply, freeze = Object.freeze;
    var stringify = JSON.stringify, keys = Object.keys, isArray = Array.isArray, create = Object.create,
        setPrototype = Object.setPrototypeOf, MAX_DEPTH = 8;
    function plain(value, depth) {
        var kind = typeof value;
        if (value === null || kind === 'string' || kind === 'boolean') return value;
        if (kind === 'number') return value === value && value !== Infinity && value !== -Infinity ? value : null;
        if (kind !== 'object' || depth > MAX_DEPTH) return undefined;
        var copy, i, item;
        if (isArray(value)) {
            copy = setPrototype([], null);
            for (i = 0; i < value.length; i++) { item = plain(value[i], depth + 1); copy[i] = item === undefined ? null : item; }
            return copy;
        }
        copy = create(null);
        var own = keys(value);
        for (i = 0; i < own.length; i++) { item = plain(value[own[i]], depth + 1); if (item !== undefined) copy[own[i]] = item; }
        return copy;
    }
    function json(value) { var copy = plain(value, 0); return copy === undefined ? undefined : apply(stringify, null, [copy]); }
    var addListener = EventTarget.prototype.addEventListener, removeListener = EventTarget.prototype.removeEventListener,
        trusted = Object.getOwnPropertyDescriptor(new Event('univer'), 'isTrusted').get;
    function listen(target, type, handler, options) {
        if (typeof handler !== 'function') return function () { };
        var wrapper = function (event) {
            var real = false;
            try { real = apply(trusted, event, []) === true; } catch (_) { real = false; }
            if (real) return handler(event);
        };
        apply(addListener, target, [type, wrapper, options]);
        return function () { apply(removeListener, target, [type, wrapper, options]); };
    }
    /* clock is what a tap's calendar event is computed with: Date, its readers, Promise.prototype.then and the event's
       target, taken here, so a Date.prototype.getHours, Date.now or then the page swaps after the tap cannot move or
       replace what the tap sends, nor (taken before any site script) what a card computes when it is drawn. Times are plain numbers (ms) and a moment's fields a frozen object without a prototype. */
    var DateOf = Date, construct = Reflect.construct, dateNow = Date.now, dates = Date.prototype, timeOf = dates.getTime,
        yearOf = dates.getFullYear, monthOf = dates.getMonth, dateOf = dates.getDate, dayOf = dates.getDay,
        hoursOf = dates.getHours, minutesOf = dates.getMinutes, then = Promise.prototype.then,
        targetOf = Object.getOwnPropertyDescriptor(Event.prototype, 'target').get, PromiseOf = Promise;
    // The week reader's built-ins (clock.page). A WebView without one of them leaves page() to the module's own reader.
    var HOME = 'https://univer.kaznu.kz', WEEK_PAGE = /^\/student\/myschedule\/\d{4}\/[12]\/\d{1,2}\.\d{1,2}\.\d{4}\/\d{1,2}\.\d{1,2}\.\d{4}\/?$/,
        fetchOf = typeof window.fetch === 'function' ? window.fetch : null, setTimer = window.setTimeout, clearTimer = window.clearTimeout,
        URLOf = URL, AbortOf = typeof AbortController === 'function' ? AbortController : null, ParserOf = DOMParser,
        parseHtml = DOMParser.prototype.parseFromString, textOfResponse = null, responseStatus = null, responseUrl = null,
        urlHref, urlOrigin, urlPath, signalOf, abortIt;
    try {
        var getter = function (proto, name) { return Object.getOwnPropertyDescriptor(proto, name).get; };
        urlHref = getter(URL.prototype, 'href'); urlOrigin = getter(URL.prototype, 'origin'); urlPath = getter(URL.prototype, 'pathname');
        signalOf = getter(AbortOf.prototype, 'signal'); abortIt = AbortOf.prototype.abort;
        if (typeof Response === 'function') { responseStatus = getter(Response.prototype, 'status'); responseUrl = getter(Response.prototype, 'url'); textOfResponse = Response.prototype.text; }
    } catch (_) { fetchOf = null; }
    function whole(n) { return typeof n === 'number' && n === n && n !== Infinity && n !== -Infinity ? n : 0; }
    var clock = freeze({
        now: function () { return apply(dateNow, DateOf, []); },
        // A Date's time through the getTime taken here (NaN for anything that is not a Date).
        time: function (date) { try { return apply(timeOf, date, []); } catch (_) { return NaN; } },
        at: function (year, month, date, hours, minutes) {
            return apply(timeOf, construct(DateOf, [whole(year), whole(month), whole(date), whole(hours), whole(minutes), 0, 0]), []);
        },
        parts: function (time) {
            var d = construct(DateOf, [whole(time)]), p = create(null);
            p.year = apply(yearOf, d, []); p.month = apply(monthOf, d, []); p.date = apply(dateOf, d, []);
            p.day = apply(dayOf, d, []); p.hours = apply(hoursOf, d, []); p.minutes = apply(minutesOf, d, []);
            return freeze(p);
        },
        then: function (promise, done, failed) { try { apply(then, promise, [done, failed]); } catch (_) { failed(); } },
        target: function (event) { try { return apply(targetOf, event, []); } catch (_) { return null; } },
        // A new Promise of the constructor taken here. Resolve it only with an object that has no prototype or an own
        // `then`, so no then the page put on a prototype takes part.
        promise: function (executor) { return construct(PromiseOf, [executor]); },
        // page(path, limit, wait): one GET of a week of the student's schedule, read and parsed with fetch, the
        // response's status, url and text, URL, AbortController, setTimeout and DOMParser as they were here, so a page
        // that swaps them at load neither changes nor blocks the neighbouring week «В календарь» and the export read.
        // It resolves with {doc} (no prototype) or rejects; null when the WebView had no fetch or Response at document start.
        page: function (path, limit, wait) { return typeof fetchOf === 'function' && typeof textOfResponse === 'function' ? readPage(path, limit, wait) : null; }
    });
    function readPage(path, limit, wait) {
        return construct(PromiseOf, [function (resolve, reject) {
            var timer = null, settled = false, done = function (value, failure) {
                if (settled) return;
                settled = true;
                if (timer !== null) { try { apply(clearTimer, window, [timer]); } catch (_) { } timer = null; }
                if (failure) { reject(failure); return; }
                var box = create(null); box.doc = value; resolve(box);
            };
            var failed = function () { done(null, new Error('Unavailable')); };
            if (typeof path !== 'string' || !apply(test, WEEK_PAGE, [path])) { done(null, new Error('Address')); return; }
            var url = construct(URLOf, [path, HOME]), href = apply(urlHref, url, []), wanted = apply(urlPath, url, []);
            if (apply(urlOrigin, url, []) !== HOME) { done(null, new Error('Address')); return; }
            var abort = construct(AbortOf, []), init = create(null);
            init.credentials = 'same-origin'; init.cache = 'no-store'; init.redirect = 'error'; init.signal = apply(signalOf, abort, []);
            timer = apply(setTimer, window, [function () { try { apply(abortIt, abort, []); } catch (_) { } failed(); }, wait]);
            apply(then, apply(fetchOf, window, [href, init]), [function (response) {
                try {
                    var final = construct(URLOf, [apply(responseUrl, response, []), HOME]);
                    if (apply(responseStatus, response, []) !== 200 || apply(urlOrigin, final, []) !== HOME || apply(urlPath, final, []) !== wanted) { failed(); return; }
                    apply(then, apply(textOfResponse, response, []), [function (html) {
                        try {
                            if (typeof html !== 'string' || html.length > limit) { failed(); return; }
                            done(apply(parseHtml, construct(ParserOf, []), [html, 'text/html']), null);
                        } catch (_) { failed(); }
                    }, failed]);
                } catch (_) { failed(); }
            }, failed]);
        }]);
    }
    var describe = Object.getOwnPropertyDescriptor, test = RegExp.prototype.test,
        selectAll = Document.prototype.querySelectorAll, nodeCount = describe(NodeList.prototype, 'length').get,
        nodeAt = NodeList.prototype.item, closest = Element.prototype.closest,
        previous = describe(Element.prototype, 'previousElementSibling').get, tagName = describe(Element.prototype, 'tagName').get,
        cellsOf = describe(HTMLTableRowElement.prototype, 'cells').get, cellCount = describe(HTMLCollection.prototype, 'length').get,
        cellAt = HTMLCollection.prototype.item, textOf = describe(Node.prototype, 'textContent').get,
        hrefOf = describe(HTMLAnchorElement.prototype, 'href').get, pageUrl = describe(Document.prototype, 'URL').get,
        parentOf = describe(Node.prototype, 'parentNode').get, isInteger = Number.isInteger,
        doc = document, COURSE_PAGE = /^https:\/\/univer\.kaznu\.kz\/student\/umkd\/\d+\/\d{4}\/\d+\/?(?:\?[^#]*)?(?:#.*)?$/;
    function all(selector) { var list = apply(selectAll, doc, [selector]), n = apply(nodeCount, list, []), out = create(null), i; for (i = 0; i < n; i++) out[i] = apply(nodeAt, list, [i]); out.length = n; return out; }
    function heading(row) { var cells = apply(cellsOf, row, []); return apply(cellCount, cells, []) === 1 && apply(tagName, apply(cellAt, cells, [0]), []) === 'TH'; }
    // As the page shows it: the app makes the folder names from these.
    function name(node) { var value = apply(textOf, node, []); return typeof value === 'string' ? value : ''; }
    // The heading rows of #l4 in page order, counted the one way both mark and places count them.
    function headings() {
        var rows = all('#l4 tr'), heads = create(null), count = 0, i;
        for (i = 0; i < rows.length; i++) if (heading(rows[i])) heads[count++] = rows[i];
        heads.length = count;
        return heads;
    }
    // The last few taps' rows: a lookup comes within seconds of its tap, so older marks are let go.
    var marks = create(null), lastMark = 0, KEPT_MARKS = 16;
    function mark(cell) {
        try {
            var row = apply(parentOf, cell, []);
            if (!row || apply(tagName, row, []) !== 'TR' || !heading(row) || apply(cellAt, apply(cellsOf, row, []), [0]) !== cell) return null;
            var heads = headings(), head = -1, i;
            for (i = 0; i < heads.length && head < 0; i++) if (heads[i] === row) head = i;
            if (head < 0) return null;
            lastMark++;
            marks[lastMark] = row;
            delete marks[lastMark - KEPT_MARKS];
            var tap = create(null); tap.mark = lastMark; tap.head = head; tap.count = heads.length;
            return freeze(tap);
        } catch (_) { return null; }
    }
    function places(page, wanted, marked) {
        if (typeof page !== 'string' || !isArray(wanted) || !isInteger(marked) || marked < 1 || apply(pageUrl, doc, []) !== page || !apply(test, COURSE_PAGE, [page])) return null;
        var tapped = marks[marked] || null, heads = headings(), count = heads.length, i, j, k;
        var crumbs = all('#l4 tr.top>td.ct a[href*="/student/umkd/"]'), course = crumbs.length > 1 ? name(crumbs[crumbs.length - 1]) : '';
        var links = all('#l4 a.downLoad[href]'), found = create(null);
        for (i = 0; i < links.length; i++) {
            var href = apply(hrefOf, links[i], []), asked = false;
            for (j = 0; j < wanted.length && !asked; j++) asked = wanted[j] === href;
            if (!asked || found[href]) continue;
            var row = apply(closest, links[i], ['tr']), head = -1, section = '', same = false;
            for (row = row ? apply(previous, row, []) : null; row; row = apply(previous, row, [])) {
                if (apply(tagName, row, []) !== 'TR' || !heading(row)) continue;
                for (k = 0; k < count && head < 0; k++) if (heads[k] === row) head = k;
                section = name(apply(cellAt, apply(cellsOf, row, []), [0]));
                same = tapped !== null && row === tapped;
                break;
            }
            var place = create(null); place.course = course; place.section = section; place.head = head; place.tapped = same;
            found[href] = place;
        }
        var answer = create(null); answer.count = count; answer.files = found;
        // Every object here has no prototype and holds only strings, numbers, booleans and such objects: no toJSON or
        // getter of the site's takes part in the stringify taken at document start.
        return apply(stringify, null, [answer]);
    }
    function open(channel) {
        var postMessage = channel && channel.postMessage;
        if (typeof postMessage !== 'function') return null;
        // postMessage is the one taken now. A site script that later replaces the channel object never sees the secret.
        return function (value) { if (typeof value === 'string') apply(postMessage, channel, [secret + value]); };
    }
    // The same prefixes, lengths and tap set as MobilePageAdapter's NATIVE (tests/dom/channel-prefixes.test.cjs reads both).
    var k=['univer-pin:',8192,'univer-download-all:',65536,'univer-report:',65536,'univer-calendar:',4096,'univer-schedule:',131072,'univer-export:',98304,'univer-grades:',65536,'univer-exams:',32768];
    var tap={'univer-pin:':1,'univer-download-all:':1,'univer-report:':1,'univer-calendar:':1,'univer-export:':1};
    var MOMENT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/, DAY = /^\d{4}-\d{2}-\d{2}$/;
    function listed(message, prefix) {
        var i;
        if (typeof message !== 'string' || message.length < prefix.length) return false;
        for (i = 0; i < prefix.length; i++) if (message[i] !== prefix[i]) return false;
        return true;
    }
    // A report the raw sender may key: the week, the totals or the exams, within that prefix's own length.
    function reportText(message) {
        var i, prefix;
        for (i = 0; i < k.length; i += 2) {
            prefix = k[i];
            if (tap[prefix] === 1) continue;
            if (listed(message, prefix) && message.length <= k[i + 1]) return true;
        }
        return false;
    }
    function shape(prefix, copy) {
        var i, list;
        if (prefix === 'univer-pin:') {
            list = copy.order;
            if (isArray(list)) {
                for (i = 0; i < list.length; i++) if (typeof list[i] !== 'string') return false;
                return true;
            }
            return typeof copy.pin === 'boolean' && typeof copy.url === 'string';
        }
        if (prefix === 'univer-download-all:') return typeof copy.section === 'string' && isArray(copy.urls) && typeof copy.head === 'number' && typeof copy.count === 'number' && typeof copy.mark === 'number';
        if (prefix === 'univer-report:') return copy.v === 1 && isArray(copy.courses);
        if (prefix === 'univer-calendar:') {
            if (copy.v !== 1 || typeof copy.title !== 'string' || copy.title.length < 1 || copy.title.length > 200) return false;
            if (typeof copy.start !== 'string' || !apply(test, MOMENT, [copy.start])) return false;
            if (typeof copy.end !== 'string' || !apply(test, MOMENT, [copy.end])) return false;
            if (typeof copy.place !== 'string' || copy.place.length > 200 || typeof copy.notes !== 'string' || copy.notes.length > 500) return false;
            if (!(copy.until === undefined || copy.until === null || (typeof copy.until === 'string' && apply(test, DAY, [copy.until])))) return false;
            if (!(copy.interval === undefined || copy.interval === null || copy.interval === 1 || copy.interval === 2)) return false;
            if (!(copy.partial === undefined || copy.partial === null || typeof copy.partial === 'boolean')) return false;
            return true;
        }
        if (prefix === 'univer-schedule:') return copy.v === 1 && typeof copy.week === 'string' && isArray(copy.lessons);
        if (prefix === 'univer-export:') return (copy.v === 0 || copy.v === 1) && (copy.kind === 'ics' || copy.kind === 'png');
        if (prefix === 'univer-grades:') return copy.v === 1 && isArray(copy.courses);
        if (prefix === 'univer-exams:') return copy.v === 1 && (copy.kind === 'exam' || copy.kind === 'midterm') && isArray(copy.items);
        return false;
    }
    // Taken once, at document start. post() keys through this, not through whatever the page later puts on window.
    var appPipe = open(window.__univerAppEvents), noticeSend = open(window.__univerNoticeEvents);
    // Reports only. A pin, a download, the grade report, a calendar event or an export is not keyed from a raw string.
    function app(value) { if (reportText(value) && typeof appPipe === 'function') appPipe(value); }
    // Prefix and body are separate. The body is plain data serialized here, never a message string the page composed.
    function post(prefix, value) {
        try {
            var lim = 0, i, copy, body, message;
            if (typeof prefix !== 'string' || window !== window.top) return;
            for (i = 0; i < k.length; i += 2) if (k[i] === prefix) lim = k[i + 1];
            if (!lim) return;
            copy = plain(value, 0);
            if (!copy || typeof copy !== 'object' || isArray(copy) || !shape(prefix, copy)) return;
            body = apply(stringify, null, [copy]);
            if (typeof body !== 'string' || body.length < 2 || body[0] !== '{') return;
            message = prefix + body;
            if (message.length > lim) return;
            if (typeof appPipe === 'function') appPipe(message);
        } catch (_) { }
    }
    // keyed: these senders carry the secret, so native code may act on what they send (downloads, home tiles).
    var senders = freeze({app: app, notice: noticeSend, json: json, listen: listen, mark: mark, places: places, post: post, clock: clock, keyed: true});
    try {
        Object.defineProperty(window, '__univerClaim', {value: freeze(function (key) { return key === secret ? senders : null; })});
    } catch (_) { /* Nothing defined it before: this runs ahead of every site script of the document. */ }
})();
