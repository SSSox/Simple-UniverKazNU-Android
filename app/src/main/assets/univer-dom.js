/* DOM helpers shared by the page modules (univer-mobile.js keeps its own: it also runs inside frames from its own source).
   Loaded first; every module below reads them from window.__univerDomV1. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerDomV1) return;
    window.__univerDomV1 = {
        /** A node's text (or a string) with runs of whitespace collapsed. */
        clean: function (node) { return (typeof node === 'string' ? node : node?.textContent || '').replace(/\s+/g, ' ').trim(); },
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
