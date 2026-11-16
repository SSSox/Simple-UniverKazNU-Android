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
    window.__univerDomV1 = {
        boundedText: boundedText,
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
    };
})();
