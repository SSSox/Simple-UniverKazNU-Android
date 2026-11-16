// The moving parts shared with the Windows app's kit.js: a segmented control's thumb that slides to the chosen option, and text
// that rolls only the part that changed. Both add presentation only and leave the site's own nodes and handlers alone;
// Original removes every thumb.
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerKitV1) return;
    var groups = '.univer-app-schedule-modes,.univer-app-attendance-filters', enabled = false, sizes = null;
    var allowed = function () { return enabled && window.__univerMotion !== false && !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches; };
    // A text-size change (WebView textZoom) resizes the buttons without a window resize or a DOM change, so the thumb
    // follows the sizes of a group and its buttons. The thumb is absolutely positioned: moving it resizes nothing here.
    var watch = function (group) {
        if (typeof ResizeObserver !== 'function') return;
        if (!sizes) sizes = new ResizeObserver(function (entries, observer) {
            var changed = [];
            entries.forEach(function (entry) {
                var target = entry.target;
                // A removed group or button is let go, so replaced controls are not kept alive; a group placed again is watched again.
                if (!target.isConnected) {
                    observer.unobserve(target);
                    if (target.__univerKitSizes === observer) target.__univerKitSizes = null;
                    return;
                }
                var owner = target.closest(groups);
                if (owner && changed.indexOf(owner) < 0) changed.push(owner);
            });
            if (enabled && observer === sizes) changed.forEach(place);
        });
        if (group.__univerKitSizes === sizes) return;
        group.__univerKitSizes = sizes;
        sizes.observe(group);
        group.querySelectorAll('button').forEach(function (button) { sizes.observe(button); });
    };
    var place = function (group) {
        watch(group);
        var chosen = group.querySelector('button[aria-pressed=true]'), thumb = group.querySelector(':scope>.univer-kit-thumb');
        if (!chosen || !chosen.offsetWidth) { thumb?.remove(); group.classList.remove('univer-kit-slid'); return; }
        var fresh = !thumb;
        if (fresh) { thumb = document.createElement('span'); thumb.className = 'univer-kit-thumb'; thumb.setAttribute('aria-hidden', 'true'); group.prepend(thumb); group.classList.add('univer-kit-slid'); }
        var x = chosen.offsetLeft, y = chosen.offsetTop, width = chosen.offsetWidth, height = chosen.offsetHeight;
        if (thumb.__x === x && thumb.__y === y && thumb.__width === width && thumb.__height === height) return;
        thumb.__x = x; thumb.__y = y; thumb.__width = width; thumb.__height = height;
        if (fresh || !allowed()) thumb.style.transition = 'none';
        thumb.style.width = width + 'px'; thumb.style.height = height + 'px'; thumb.style.transform = 'translate(' + x + 'px,' + y + 'px)';
        if (fresh || !allowed()) { thumb.getBoundingClientRect(); thumb.style.transition = ''; }
    };
    var refresh = function () { if (enabled) document.querySelectorAll(groups).forEach(place); };
    var clear = function () {
        if (sizes) { sizes.disconnect(); sizes = null; }
        document.querySelectorAll('.univer-kit-thumb').forEach(function (thumb) { thumb.parentNode.classList.remove('univer-kit-slid'); thumb.remove(); });
    };
    document.addEventListener('click', function (event) {
        if (enabled && event.target.closest?.(groups)) setTimeout(refresh, 0);
    }, true);
    window.addEventListener('resize', refresh);
    // Only the changed span rolls, the words around it stay; the plain text returns when the motion ends.
    var roll = function (node, text) {
        var old = node.textContent;
        if (old === text) return;
        if (!old || !allowed() || !node.animate) { node.textContent = text; return; }
        var head = 0; while (head < old.length && head < text.length && old[head] === text[head]) head++;
        var tail = 0; while (tail < old.length - head && tail < text.length - head && old[old.length - 1 - tail] === text[text.length - 1 - tail]) tail++;
        var slot = document.createElement('span'); slot.style.display = 'inline-block'; slot.textContent = text.slice(head, text.length - tail);
        node.textContent = ''; node.append(text.slice(0, head), slot, tail ? text.slice(text.length - tail) : '');
        var motion = slot.animate([{opacity:0, transform:'translateY(60%)'}, {opacity:1, transform:'none'}], {duration:300, easing:'cubic-bezier(.22,1,.36,1)'});
        motion.onfinish = motion.oncancel = function () { if (slot.parentNode === node) node.textContent = text; };
    };
    window.__univerKitV1 = {
        configure: function (on) { enabled = !!on; if (enabled) refresh(); else clear(); },
        refresh: refresh, roll: roll
    };
})();
