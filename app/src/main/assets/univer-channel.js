/* Document-start key of the app's message channels (ChannelGuard.kt replaces the secret). It runs before any site
   script in the main frame: Reflect.apply and each channel's postMessage are taken here, so a site script that later
   replaces them neither sees nor stops the app's messages. __univerClaim cannot be redefined and hands the senders,
   which put the secret in front of each message, only to a caller that knows the secret: the app's modules get it
   inside their injected source, the site never does.
   json is a serializer taken here as well: a JSON.stringify, Object.keys or toJSON the site sets up later cannot change
   what a tap sends. It copies plain data (strings, numbers, booleans, null, arrays and objects with their own enumerable
   keys) into objects without a prototype, so no inherited toJSON or getter takes part, and drops anything else.
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
    function sender(channel) {
        var post = channel && channel.postMessage;
        if (typeof post !== 'function') return null;
        return function (value) { if (typeof value === 'string') apply(post, channel, [secret + value]); };
    }
    // keyed: these senders carry the secret, so native code may act on what they send (downloads, home tiles).
    var senders = freeze({app: sender(window.__univerAppEvents), notice: sender(window.__univerNoticeEvents), json: json, listen: listen, mark: mark, places: places, keyed: true});
    try {
        Object.defineProperty(window, '__univerClaim', {value: freeze(function (key) { return key === secret ? senders : null; })});
    } catch (_) { /* Nothing defined it before: this runs ahead of every site script of the document. */ }
})();
