/* Wide tables on a phone: a slightly wider one is drawn smaller; a wider one gets "Expand", which moves the same node
   (links, sorting, handlers kept) into a zoomable full-screen view and back. DOM changes go through mutate(). */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerTablesV1) return;
    var MIN_FIT = 0.8, MIN_ZOOM = 0.25, MAX_ZOOM = 4;
    var clean = window.__univerDomV1.clean, language = 'ru', viewer = null, fitted = new Map();
    var SKIP = '.univer-app-cards,.univer-app-courses,.univer-app-materials,.univer-app-document-layout,.univer-app-pairs,.univer-app-nav-table';
    function labels() {
        return language === 'en' ? {expand:'Expand', close:'Close', bigger:'Zoom in', smaller:'Zoom out', fit:'Fit width', table:'Table', zoom:'Zoom'} :
            language === 'kz' ? {expand:'Ашу', close:'Жабу', bigger:'Үлкейту', smaller:'Кішірейту', fit:'Ені бойынша', table:'Кесте', zoom:'Масштаб'} :
            {expand:'Развернуть', close:'Закрыть', bigger:'Крупнее', smaller:'Мельче', fit:'По ширине', table:'Таблица', zoom:'Масштаб'};
    }
    function mutate(action) {
        if (window.__univerMobileV1) window.__univerMobileV1.mutate(action); else action();
    }
    function boxTable(box) {
        var table = box.firstElementChild;
        return table && table.tagName === 'TABLE' && !table.matches(SKIP) ? table : null;
    }
    // Called inside the adapter's pass (observer paused): each box is measured at the table's own size, then fitted.
    // All fits are undone first, then every box is measured, then the new fits are written: one layout for the page
    // instead of one per table. The adapter passes its [afterLayout], so the measuring joins the pass's single read.
    function fit(afterLayout) {
        var boxes = [];
        document.querySelectorAll('.univer-app-table-scroll').forEach(function (box) {
            if (box.closest('.univer-app-table-viewer')) return;
            var table = boxTable(box);
            if (!table) return;
            if (fitted.has(table)) { table.style.zoom = fitted.get(table); fitted.delete(table); table.classList.remove('univer-app-table-fitted'); }
            if (!table.querySelector('input:not([type=hidden]),select,textarea,button')) boxes.push({box:box, table:table});
        });
        fitted.forEach(function (zoom, table) {
            if (!table.isConnected) fitted.delete(table);
        });
        (afterLayout || function (read, write) { write(read()); })(function () {
            return boxes.map(function (item) { return item.box.getClientRects().length ? item.box.clientWidth / item.box.scrollWidth : 1; });
        }, function (ratios) {
            boxes.forEach(function (item, i) {
                if (!(ratios[i] < 1 && ratios[i] >= MIN_FIT) || !item.table.isConnected) return;
                fitted.set(item.table, item.table.style.zoom);
                item.table.style.zoom = String(Math.floor(ratios[i] * 1000) / 1000);
                item.table.classList.add('univer-app-table-fitted');
            });
        });
    }
    // The nearest short text before the table (a section row of a document, a heading), else "Table".
    function title(table) {
        if (table.caption && clean(table.caption)) return clean(table.caption).slice(0, 80);
        var node = table.parentElement;
        for (var depth = 0; node && depth < 5; depth++, node = node.parentElement) {
            for (var previous = node.previousElementSibling, step = 0; previous && step < 3; previous = previous.previousElementSibling, step++) {
                if (previous.matches('.univer-app-scroll-hint,script,style') || !previous.getClientRects().length) continue;
                var text = clean(previous);
                if (text.length >= 3 && text.length <= 80) return text;
                if (text) break;
            }
        }
        return labels().table;
    }
    function button(name, label, icon) {
        var item = document.createElement('button');
        item.type = 'button'; item.className = 'univer-app-table-viewer-' + name;
        item.setAttribute('aria-label', label); item.title = label; item.textContent = icon;
        return item;
    }
    function natural() {
        var zoom = viewer.stage.style.zoom;
        viewer.stage.style.zoom = '1';
        var width = viewer.stage.offsetWidth;
        viewer.stage.style.zoom = zoom;
        return width;
    }
    function fitZoom() {
        var available = viewer.scroller.clientWidth - 16;
        return Math.max(MIN_ZOOM, Math.min(1, available / Math.max(1, natural())));
    }
    // Zooms around a point of the scroller (its centre by default), so what is under the fingers stays there.
    function setZoom(zoom, fittedToWidth, fx, fy) {
        var v = viewer, old = v.zoom;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        if (fx === undefined) { fx = v.scroller.clientWidth / 2; fy = v.scroller.clientHeight / 2; }
        var cx = (v.scroller.scrollLeft + fx) / old, cy = (v.scroller.scrollTop + fy) / old;
        v.zoom = zoom; v.fitted = !!fittedToWidth;
        v.stage.style.zoom = String(zoom);
        v.scroller.scrollLeft = cx * zoom - fx; v.scroller.scrollTop = cy * zoom - fy;
        var text = Math.round(zoom * 100) + '%';
        if (v.level.textContent !== text) v.level.textContent = text;
        v.smaller.disabled = zoom <= MIN_ZOOM + 0.001; v.bigger.disabled = zoom >= MAX_ZOOM - 0.001;
        v.fit.setAttribute('aria-pressed', String(v.fitted));
    }
    function point(event, touches) {
        var rect = viewer.scroller.getBoundingClientRect();
        var x = 0, y = 0;
        for (var i = 0; i < touches.length; i++) { x += touches[i].clientX; y += touches[i].clientY; }
        return {x:x / touches.length - rect.left, y:y / touches.length - rect.top};
    }
    function distance(touches) { return Math.hypot(touches[0].clientX - touches[1].clientX, touches[0].clientY - touches[1].clientY); }
    function touchStart(event) {
        var v = viewer;
        if (!v) return;
        if (event.touches.length === 2) {
            var at = point(event, event.touches);
            v.pinch = {distance:Math.max(1, distance(event.touches)), zoom:v.zoom, x:at.x, y:at.y};
            event.preventDefault();
        }
    }
    function touchMove(event) {
        var v = viewer;
        if (!v || !v.pinch || event.touches.length !== 2) return;
        event.preventDefault();
        var at = point(event, event.touches), zoom = v.pinch.zoom * distance(event.touches) / v.pinch.distance;
        if (v.frame) cancelAnimationFrame(v.frame);
        v.frame = requestAnimationFrame(function () { v.frame = 0; if (viewer === v) setZoom(zoom, false, at.x, at.y); });
    }
    // A double tap switches between the whole table and its real size at that point.
    function touchEnd(event) {
        var v = viewer;
        if (!v) return;
        if (v.pinch) { if (event.touches.length < 2) v.pinch = null; v.lastTap = 0; return; }
        if (event.touches.length || event.changedTouches.length !== 1) return;
        var touch = event.changedTouches[0], now = Date.now(), at = point(event, [touch]);
        if (v.lastTap && now - v.lastTap < 320 && Math.hypot(at.x - v.tapX, at.y - v.tapY) < 30) {
            v.lastTap = 0;
            event.preventDefault();
            if (v.fitted && v.zoom < 0.99) setZoom(1, false, at.x, at.y); else setZoom(fitZoom(), true, at.x, at.y);
            return;
        }
        v.lastTap = now; v.tapX = at.x; v.tapY = at.y;
    }
    function keydown(event) {
        if (event.key === 'Escape' && viewer) { event.preventDefault(); close(); }
    }
    function resize() {
        if (viewer && viewer.fitted) setZoom(fitZoom(), true);
    }
    function open(table) {
        if (viewer || !table || !table.isConnected || !document.documentElement.classList.contains('univer-mobile')) return false;
        var text = labels(), name = title(table);
        var overlay = document.createElement('div');
        overlay.className = 'univer-app-table-viewer'; overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true'); overlay.setAttribute('aria-label', name);
        var bar = document.createElement('div'); bar.className = 'univer-app-table-viewer-bar';
        var heading = document.createElement('div'); heading.className = 'univer-app-table-viewer-title'; heading.textContent = name;
        var closeButton = button('close', text.close, '✕');
        var smaller = button('smaller', text.smaller, '−'), bigger = button('bigger', text.bigger, '+');
        var level = document.createElement('span'); level.className = 'univer-app-table-viewer-level';
        level.setAttribute('aria-live', 'polite'); level.setAttribute('aria-label', text.zoom);
        var fitButton = button('fit', text.fit, text.fit);
        var tools = document.createElement('div'); tools.className = 'univer-app-table-viewer-tools';
        tools.append(smaller, level, bigger, fitButton);
        bar.append(heading, closeButton, tools);
        var scroller = document.createElement('div'); scroller.className = 'univer-app-table-viewer-scroll'; scroller.tabIndex = 0;
        // The stage is a table box too, so the table keeps the look it has on the page.
        var stage = document.createElement('div'); stage.className = 'univer-app-table-scroll univer-app-table-viewer-stage';
        var home = document.createComment('univer-table-home');
        var zoom = fitted.has(table) ? fitted.get(table) : table.style.zoom;
        mutate(function () {
            if (fitted.has(table)) { fitted.delete(table); table.classList.remove('univer-app-table-fitted'); }
            table.style.zoom = zoom;
            // The table keeps the width (and so the line breaks) it has on the page.
            stage.style.setProperty('width', Math.ceil(table.getBoundingClientRect().width) + 'px', 'important');
            table.before(home); stage.appendChild(table); scroller.appendChild(stage); overlay.append(bar, scroller);
            document.body.appendChild(overlay);
            document.documentElement.classList.add('univer-app-table-viewing');
        });
        viewer = {table:table, home:home, overlay:overlay, scroller:scroller, stage:stage, level:level, smaller:smaller,
            bigger:bigger, fit:fitButton, zoom:1, fitted:true, pinch:null, lastTap:0, frame:0, focus:document.activeElement};
        closeButton.addEventListener('click', function () { close(); });
        smaller.addEventListener('click', function () { if (viewer) setZoom(viewer.zoom / 1.25, false); });
        bigger.addEventListener('click', function () { if (viewer) setZoom(viewer.zoom * 1.25, false); });
        fitButton.addEventListener('click', function () { if (viewer) setZoom(fitZoom(), true); });
        scroller.addEventListener('touchstart', touchStart, {passive:false});
        scroller.addEventListener('touchmove', touchMove, {passive:false});
        scroller.addEventListener('touchend', touchEnd, {passive:false});
        scroller.addEventListener('touchcancel', touchEnd, {passive:false});
        document.addEventListener('keydown', keydown, true);
        window.addEventListener('resize', resize);
        setZoom(fitZoom(), true, 0, 0);
        scroller.scrollLeft = 0; scroller.scrollTop = 0;
        closeButton.focus({preventScroll:true});
        return true;
    }
    function close() {
        var v = viewer;
        if (!v) return false;
        viewer = null;
        if (v.frame) cancelAnimationFrame(v.frame);
        document.removeEventListener('keydown', keydown, true);
        window.removeEventListener('resize', resize);
        var hint = null;
        mutate(function () {
            var box = v.home.parentNode;
            if (box) {
                box.replaceChild(v.table, v.home);
                // The pass that ran while the box was empty hid its "Expand" hint, and a hidden button takes no focus.
                // The table is back, so the hint shows again now; the adapter's next pass measures it as usual.
                hint = box.__univerHint || null;
                if (hint) hint.hidden = false;
            }
            v.overlay.remove();
            document.documentElement.classList.remove('univer-app-table-viewing');
        });
        // Focus returns to what opened the view, else to this table's own "Expand" (never another table's).
        var opener = hint && hint.isConnected ? hint.querySelector('.univer-app-table-open') : null;
        try { (v.focus && v.focus.isConnected && v.focus !== document.body ? v.focus : opener)?.focus({preventScroll:true}); } catch (_) { }
        return true;
    }
    // Original layout: the table goes home and every fitted table gets its own zoom back.
    function release() {
        close();
        fitted.forEach(function (zoom, table) { table.style.zoom = zoom; table.classList.remove('univer-app-table-fitted'); });
        fitted.clear();
    }
    window.__univerTablesV1 = {
        fit:fit, open:open, close:close, release:release,
        setLanguage:function (value) { language = value === 'en' || value === 'kz' ? value : 'ru'; },
        label:function () { return labels().expand; },
        isOpen:function () { return !!viewer; },
        /** Whether the table in this scroll box is zoomed to the box's width. */
        fitted:function (box) { var table = box && boxTable(box); return !!table && fitted.has(table); }
    };
})();
