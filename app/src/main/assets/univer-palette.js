/* Dark palette of the adapted layout: each adapter stylesheet's light colour is replaced by its dark counterpart for
   its role (surface, text, line). Original pages are never touched. Loaded first so later modules can use it. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerPaletteV1) return;
    function map(groups) {
        var table = {};
        Object.keys(groups).forEach(function (dark) { groups[dark].forEach(function (light) { table[light] = dark; }); });
        return table;
    }
    // Surfaces keep their order (page darker than cards), tints keep their hue; filled controls keep white text.
    var FILL = map({
        '#0f151c': ['#f1f4f7'],
        '#17202a': ['#ffffff'],
        '#18212b': ['#fafcfe'],
        '#1d2733': ['#f7f9fb'],
        '#1c2631': ['#f4f7fa', '#f4f7fb', '#f3f6f9'],
        '#222d39': ['#eef2f6', '#edf1f5', '#e9eef4'],
        '#2b3746': ['#dfe5ec'],
        '#1a2b40': ['#eef4fb', '#eef3f8', '#e9f1fb', '#e4effb'],
        '#22395a': ['#dbe8f7', '#cfe0f4'],
        '#13302c': ['#e3f3ef'],
        '#15301f': ['#e3f3ea', '#e5f3ea', '#ebf8f1'],
        '#33291a': ['#fff8eb', '#fbeedb', '#fbf1d9', '#fdebd8', '#fdf0dc', '#fdf3e4'],
        '#3a1d1b': ['#fdeceb']
    });
    var TEXT = map({
        '#e6ecf3': ['#16283b', '#172b40', '#1d2b39', '#20374d'],
        '#cfd8e2': ['#2a3d51'],
        '#9fadbc': ['#4b5b6c', '#5b6b7c', '#46596b', '#405468', '#526b82', '#7a8a9a', '#64748b', '#475467'],
        '#8ebbf0': ['#1b5fa6', '#164e80', '#184f86', '#123f69', '#154d88', '#154c86', '#1d5fbf', '#246298', '#2b79bd'],
        '#5fd0c2': ['#0f766e', '#0d5c58', '#0e8f84'],
        '#7fd6a2': ['#166534', '#1d6b3b', '#15803d'],
        '#ff8c82': ['#b42318', '#d92d20'],
        '#f3bb6a': ['#7a4300', '#7a4b00', '#8a3c00', '#a15c00', '#b45309', '#d97706', '#d9560b'],
        '#f490c4': ['#c11574'],
        '#bba3f3': ['#7a4fd6']
    });
    var LINE = map({
        '#2b3746': ['#dfe5ec', '#e2eaf3', '#edf1f5', '#eef2f6'],
        '#3b4a5b': ['#c8d3df', '#c4cfdb', '#a9bbce', '#9fb2c6'],
        '#34507a': ['#d7e3ef', '#c7d9ed', '#bdcfe3', '#bfd3ea'],
        '#6fa3e0': ['#1b5fa6', '#164e80', '#2b79bd', '#246298', '#154d88'],
        '#2f6b49': ['#86c7a0'],
        '#6b3531': ['#f0c9c5'],
        '#e0675c': ['#b42318'],
        '#00000080': ['#193a5b55']
    });
    // Token colours used as fills (buttons, badges) keep a mid tone behind white text; the same tokens as text turn light.
    var FILL_TOKENS = /var\((--u-(?:brand|brand-strong|muted|teal|danger))\)/g;
    function role(property) {
        if (/^(background|background-color|scrollbar-color)$/.test(property)) return FILL;
        if (/^(border|outline|box-shadow|column-rule|text-decoration)/.test(property)) return LINE;
        if (/^(color|fill|stroke|caret-color|accent-color|-webkit-text-fill-color)$/.test(property)) return TEXT;
        return null;
    }
    function hex(value) {
        var color = value.toLowerCase();
        return color.length === 4 ? '#' + color[1] + color[1] + color[2] + color[2] + color[3] + color[3] : color;
    }
    // [ink]: the fill is drawn through a mask, so it is a glyph's colour rather than a surface, and white stays white.
    function shade(value, table, ink) {
        var keepWhite = ink && table === FILL;
        value = value.replace(/#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})\b/g, function (color) {
            var key = hex(color);
            return keepWhite && key === '#ffffff' ? color : table[key] || color;
        })
            .replace(/rgba\(16,\s*40,\s*70,\s*([\d.]+)\)/g, function (_, alpha) { return 'rgba(0,0,0,' + Math.min(0.6, alpha * 3).toFixed(2) + ')'; })
            .replace(/rgba\(27,\s*95,\s*166,\s*([\d.]+)\)/g, function (_, alpha) { return 'rgba(142,187,240,' + Math.min(0.5, alpha * 1.8).toFixed(2) + ')'; });
        if (table !== FILL) return value;
        // The phone stylesheet also names white surfaces by keyword; white text on fills stays white.
        return (keepWhite ? value : value.replace(/\bwhite\b/gi, FILL['#ffffff'])).replace(FILL_TOKENS, 'var($1-fill)');
    }
    // A rule that draws its background through a mask (a white glyph on a coloured plaque); "mask:none" draws none.
    var MASKED = /[{;]\s*(?:-webkit-)?mask(?:-image)?\s*:(?!\s*none\s*(?:!important\s*)?[;}])/i;
    function darken(css) {
        // Declarations follow "{" or ";" inside a rule; selectors (a:hover, td:first-child) carry no colour role and pass unchanged.
        return css.replace(/\{[^{}]*\}/g, function (rule) {
            var ink = MASKED.test(rule);
            return rule.replace(/([{;]\s*)([a-z-]+)(\s*:\s*)([^;{}]+)/g, function (all, lead, property, colon, value) {
                var table = role(property.toLowerCase());
                return table ? lead + property + colon + shade(value, table, ink) : all;
            });
        });
    }
    function dark() { return window.__univerDark === true; }
    // The adapter's stylesheets are the same on every page, so their dark text (several milliseconds of regular
    // expressions per page) is kept for the app session in sessionStorage, by a hash of the light source: an updated
    // stylesheet misses. At most DARK_LIMIT characters are kept, the oldest entry goes first.
    var DARK_PREFIX = 'univer.app.dark.v1:', DARK_INDEX = 'univer.app.dark-index.v1', DARK_LIMIT = 1000000, darkMemo = new Map();
    function hash(text) {
        var a = 0x811c9dc5, b = 0x01000193 ^ text.length;
        for (var i = 0; i < text.length; i++) { var c = text.charCodeAt(i); a = Math.imul(a ^ c, 16777619); b = Math.imul(b + c, 2654435761); }
        return text.length.toString(36) + '.' + (a >>> 0).toString(36) + '.' + (b >>> 0).toString(36);
    }
    function darkened(css) {
        var known = darkMemo.get(css);
        if (known !== undefined) return known;
        var key = DARK_PREFIX + hash(css), out = null;
        try { out = sessionStorage.getItem(key); } catch (_) { }
        if (out === null) {
            out = darken(css);
            try {
                var index = JSON.parse(sessionStorage.getItem(DARK_INDEX) || '[]');
                if (!Array.isArray(index)) index = [];
                index = index.filter(function (entry) { return Array.isArray(entry) && entry[0] !== key; });
                index.push([key, out.length]);
                var total = index.reduce(function (sum, entry) { return sum + (+entry[1] || 0); }, 0);
                while (index.length > 1 && total > DARK_LIMIT) { var gone = index.shift(); total -= +gone[1] || 0; sessionStorage.removeItem(gone[0]); }
                if (out.length <= DARK_LIMIT) { sessionStorage.setItem(key, out); sessionStorage.setItem(DARK_INDEX, JSON.stringify(index)); }
            } catch (_) { /* Storage full or unavailable: the text is only not kept. */ }
        }
        darkMemo.set(css, out);
        return out;
    }

    // The site's own stylesheets (mtable.css paints "table.mt table.inner td" white) are read once per document and
    // answered under :root.univer-app-dark by dark counterparts of the same hue.
    function rgb(value) {
        if (value === 'white') return [255, 255, 255, 1];
        if (value === 'black') return [0, 0, 0, 1];
        var match = /^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)$/.exec(value);
        return match ? [+match[1], +match[2], +match[3], match[4] === undefined ? 1 : +match[4]] : null;
    }
    function hsl(color) {
        var r = color[0] / 255, g = color[1] / 255, b = color[2] / 255, max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2, h = 0, s = 0;
        if (max !== min) {
            var d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
            h *= 60;
        }
        return [h, s, l];
    }
    function css(h, s, l) { return 'hsl(' + Math.round(h) + ',' + Math.round(s * 100) + '%,' + Math.round(l * 100) + '%)'; }
    function siteColor(value, kind) {
        var color = rgb(value);
        if (!color || color[3] < 0.5) return null;
        var c = hsl(color), h = c[0], s = c[1], l = c[2];
        if (kind === 'fill') return l < 0.75 ? null : s < 0.08 && l > 0.96 ? '#17202a' : css(h, s * 0.55, 0.13 + (1 - l) * 0.55);
        if (kind === 'text') return l > 0.4 ? null : s < 0.25 ? '#cfd8e2' : css(h, Math.min(s, 0.7), 0.85 - l * 0.5);
        return l < 0.7 ? null : s < 0.15 ? '#2b3746' : css(h, s * 0.5, 0.2 + (1 - l) * 0.4);
    }
    var SITE_PROPERTIES = [['background-color', 'fill'], ['color', 'text'], ['border-top-color', 'line'], ['border-right-color', 'line'],
        ['border-bottom-color', 'line'], ['border-left-color', 'line']];
    function scoped(selector) {
        var parts = [], depth = 0, start = 0;
        for (var i = 0; i < selector.length; i++) {
            var ch = selector[i];
            if (ch === '(') depth++; else if (ch === ')') depth--;
            else if (ch === ',' && depth === 0) { parts.push(selector.slice(start, i)); start = i + 1; }
        }
        parts.push(selector.slice(start));
        return parts.map(function (part) {
            part = part.trim();
            return /^(html|:root)\b/i.test(part) ? part.replace(/^(html|:root)/i, ':root.univer-app-dark') : ':root.univer-app-dark ' + part;
        }).join(',');
    }
    function siteRules(doc) {
        var out = [];
        function walk(rules, depth) {
            Array.prototype.forEach.call(rules, function (rule) {
                if (out.length > 4000 || depth > 4) return;
                if (rule.type === 4) {
                    if (!/print/i.test(rule.conditionText || '')) { out.push('@media ' + rule.conditionText + '{'); walk(rule.cssRules, depth + 1); out.push('}'); }
                    return;
                }
                if (rule.type !== 1 || !rule.style) return;
                var declarations = SITE_PROPERTIES.map(function (pair) {
                    var shade = siteColor(rule.style.getPropertyValue(pair[0]).trim(), pair[1]);
                    return shade ? pair[0] + ':' + shade + (rule.style.getPropertyPriority(pair[0]) ? '!important' : '') : '';
                }).filter(Boolean);
                // The frame of the site's tables (table.mt: corners, tool bars, header and hover strips, observed in
                // mtable.css) is drawn with light gradient pictures that no colour rule reaches; dark, those parts are flat.
                if (/\/images\/mt\//i.test(rule.style.getPropertyValue('background-image'))) declarations.push('background-image:none');
                if (declarations.length) out.push(scoped(rule.selectorText) + '{' + declarations.join(';') + '}');
            });
        }
        Array.prototype.forEach.call(doc.styleSheets, function (sheet) {
            var owner = sheet.ownerNode;
            // The adapter's own stylesheets are already drawn in the dark palette.
            if (owner && (owner.id || '').indexOf('univer') === 0) return;
            var rules;
            try { rules = sheet.cssRules; } catch (_) { return; }
            walk(rules, 0);
        });
        // Colours written into the site's markup: white cells and black text from its editors.
        out.push(':root.univer-app-dark :is([bgcolor="#ffffff" i],[bgcolor="#fff" i],[bgcolor="white" i]){background-color:transparent!important}',
            ':root.univer-app-dark body :is([style*="background-color: rgb(255, 255, 255)"],[style*="background-color:#fff" i],[style*="background-color: #fff" i],' +
                '[style*="background:#fff" i],[style*="background: #fff" i],[style*="background-color: white" i],[style*="background: white" i]){background-color:transparent!important}',
            ':root.univer-app-dark body :is([style*="color: rgb(0, 0, 0)"],[style*="color:#000" i],[style*="color: #000" i],[style*="color: black" i],[color="#000000" i],[color="black" i]){color:inherit!important}',
            // Notices written as style="color:Green" and the like keep their meaning in a lighter shade.
            ':root.univer-app-dark body :is([style*="color:green" i],[style*="color: green" i],[color="green" i]){color:#7fd6a2!important}',
            ':root.univer-app-dark body :is([style*="color:red" i],[style*="color: red" i],[color="red" i]){color:#ff8c82!important}',
            ':root.univer-app-dark body :is([style*="color:blue" i],[style*="color: blue" i],[style*="color:navy" i],[style*="color: navy" i],[color="blue" i],[color="navy" i]){color:#8ebbf0!important}');
        return out.join('\n');
    }
    function siteStyle(doc, on) {
        var style = doc.getElementById('univer-app-site-dark');
        if (!on) { if (style) style.remove(); return; }
        // Rebuilt only when the site's stylesheets change; the adapter's own ones (added during a pass) are not read.
        var count = doc.styleSheets.length - doc.querySelectorAll('style[id^="univer"]').length;
        if (style && style.__univerSheets === count) return;
        var text = siteRules(doc);
        if (!style) { style = doc.createElement('style'); style.id = 'univer-app-site-dark'; }
        style.textContent = text;
        style.__univerSheets = count;
        (doc.head || doc.documentElement).appendChild(style);
    }

    window.__univerPaletteV1 = {
        /** Fills an adapter stylesheet, keeping its light source so a later theme can redraw it. */
        fill: function (style, css) {
            style.__univerPaletteSource = css;
            style.__univerPaletteDark = dark();
            style.textContent = style.__univerPaletteDark ? darkened(css) : css;
        },
        /** Redraws the adapter stylesheets of [doc] for the current theme and marks the document while the layout is adapted. */
        sync: function (doc, enabled) {
            var on = enabled && dark();
            if (doc.documentElement.classList.contains('univer-app-dark') !== on) doc.documentElement.classList.toggle('univer-app-dark', on);
            Array.prototype.forEach.call(doc.querySelectorAll('style'), function (style) {
                if (typeof style.__univerPaletteSource === 'string' && style.__univerPaletteDark !== dark())
                    window.__univerPaletteV1.fill(style, style.__univerPaletteSource);
            });
            siteStyle(doc, on);
        },
        darken: darken
    };
})();
