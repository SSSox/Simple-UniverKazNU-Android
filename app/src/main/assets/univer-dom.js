/* DOM helpers shared by the page modules (univer-mobile.js keeps its own: it also runs inside frames from its own source).
   Loaded first; every module below reads them from window.__univerDomV1. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerDomV1) return;
    // Stop chunked responses before the renderer allocates a whole oversized page.
    async function boundedText(response, limit) {
        if (+response.headers?.get('content-length') > limit) throw new Error('Oversized response');
        if (!response.body?.getReader) {
            var text = await response.text();
            if (new Blob([text]).size > limit) throw new Error('Oversized response');
            return text;
        }
        var reader = response.body.getReader(), decoder = new TextDecoder(), size = 0, parts = [], ended = false;
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
            if (!ended) reader.cancel().catch(function () {});
            reader.releaseLock();
        }
    }
    // The level the student (or a parent) aims at, chosen on the grades page: 'pass' (Удовлетворительно, 50 %), 'good'
    // (70 %) or 'top' (90 %); null is none. Kept in the site's localStorage, so it goes with the account on logout.
    var GOAL_KEY = 'univer.app.grade-goal.v1', GOALS = {pass:50, good:70, top:90}, aim = null, aimRead = false, aimHeard = [];
    function goal() {
        if (!aimRead) { aimRead = true; try { var kept = localStorage.getItem(GOAL_KEY); if (GOALS[kept]) aim = kept; } catch (_) {} }
        return aim;
    }
    window.__univerDomV1 = {
        boundedText: boundedText,
        goal: goal,
        /** Chooses the goal ('pass', 'good', 'top' or null) and tells every listener. */
        setGoal: function (name) {
            var next = GOALS[name] ? name : null;
            if (next === goal()) return;
            aim = next;
            try { if (aim) localStorage.setItem(GOAL_KEY, aim); else localStorage.removeItem(GOAL_KEY); } catch (_) {}
            aimHeard.slice().forEach(function (heard) { try { heard(aim); } catch (_) {} });
        },
        onGoal: function (heard) { if (typeof heard === 'function' && aimHeard.indexOf(heard) < 0) aimHeard.push(heard); },
        /** A node's text (or a string) with runs of whitespace collapsed. */
        clean: function (node) { return (typeof node === 'string' ? node : node?.textContent || '').replace(/\s+/g, ' ').trim(); },
        /** Text for matching a search: composed, lower case, "ё" read as "е". */
        fold: function (value) { return String(value).normalize('NFC').toLocaleLowerCase().replace(/ё/g, 'е'); },
        /** The table's own rows, not those of tables nested in its cells. */
        ownRows: function (table) { return Array.from(table.rows).filter(function (row) { return row.closest('table') === table; }); },
        /** A new element with an optional class and text. */
        el: function (tag, className, content) {
            var node = document.createElement(tag); if (className) node.className = className;
            if (content !== undefined) node.textContent = content;
            return node;
        },
        /** Writes only real changes, so DOM observers are not woken for nothing. */
        setText: function (node, value) { if (node.textContent !== value) node.textContent = value; },
        setHidden: function (node, value) { if (node.hidden !== value) node.hidden = value; },
        attr: function (node, name, value) { if (node.getAttribute(name) !== value) node.setAttribute(name, value); },
        /**
         * «В календарь»: the event as Android reads it (web/CalendarMessage.kt). Wall-clock times without a zone, as the
         * site shows them; [until] (a Date) makes a class repeat weekly (every second week with [interval] 2) through that day.
         */
        calendarMessage: function (item) {
            function two(n) { return String(n).padStart(2, '0'); }
            function day(d) { return d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate()); }
            function text(value, max) { return String(value || '').replace(/\s+/g, ' ').trim().slice(0, max); }
            var message = {v:1, title:text(item.title, 200), start:day(item.start) + 'T' + two(item.start.getHours()) + ':' + two(item.start.getMinutes()),
                end:day(item.end) + 'T' + two(item.end.getHours()) + ':' + two(item.end.getMinutes()), place:text(item.place, 200), notes:text(item.notes, 500),
                until:item.until ? day(item.until) : null};
            // A class of only one of the site's two weeks repeats every second week; [partial]: the other week could not be read.
            if (item.interval === 2) message.interval = 2;
            if (item.partial === true) message.partial = true;
            return 'univer-calendar:' + JSON.stringify(message);
        },
        /**
         * Where a score ("63" of "100", "12,5" of "25") stands against the marks students go by, as a share of its
         * maximum: below 50 % is short ('low'), 50–69 % admits ('pass'), 70–89 % is good, 90 % and up excellent ('top').
         * `next` is the next mark in this score's own points and `left` how many points it still needs (never 0 while
         * short of it). With a [goal] ('pass', 'good', 'top'), `next` is the goal's mark instead, and a score at or above
         * it has nothing left (`reached`). Null unless both are numbers and the maximum is positive.
         * domain/ScoreBand.kt is the same rule.
         */
        scoreBand: function (value, max, goal) {
            var number = function (raw) { var text = String(raw ?? '').trim().replace(',', '.'); return /^-?\d+(?:\.\d+)?$/.test(text) ? Number(text) : NaN; };
            var points = number(value), top = number(max);
            if (!isFinite(points) || !(top > 0)) return null;
            var percent = Math.max(0, Math.min(100, points / top * 100)), aimed = GOALS[goal] || null;
            var mark = aimed ? (percent < aimed ? aimed : undefined) : [50, 70, 90].find(function (at) { return percent < at; });
            return {
                band: percent >= 90 ? 'top' : percent >= 70 ? 'good' : percent >= 50 ? 'pass' : 'low',
                percent: Math.round(percent * 10) / 10,
                marks: [50, 70, 90].map(function (at) { return Math.round(at * top) / 100; }),
                next: mark === undefined ? null : Math.round(mark * top) / 100,
                left: mark === undefined ? 0 : Math.max(0.1, Math.ceil((mark * top / 100 - points) * 10 - 1e-9) / 10),
                reached: !!aimed && mark === undefined,
            };
        },
    };
})();
