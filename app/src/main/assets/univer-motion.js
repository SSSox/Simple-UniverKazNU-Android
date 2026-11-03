/* Feedback motion of the presentation layer (shared with Windows desktop.js): reveal, resize, reflow and exit
   animations that respect reduced motion and the app's animation setting. Loaded right before univer-style.js. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz') return;
    if (!window.__univerMotionUI) {
        var motions = new Map(), layouts = new Map(), ghosts = new Map();
        var ease = 'cubic-bezier(.23,1,.32,1)';
        var reduce = function () { return !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches; };
        var cancel = function (node) { var animation = motions.get(node); if (animation) { motions.delete(node); animation.cancel(); } };
        var allowed = function () { var root = document.documentElement; return window.__univerMotion !== false && root.classList.contains('univer-mobile') && !root.classList.contains('univer-app-keyboard'); };
        var retire = function (map, node) { var record = map.get(node); if (record) { map.delete(node); record.animation.cancel(); record.cleanup?.(); } };
        var stop = function (parent) {
            [motions, layouts, ghosts].forEach(function (map) { Array.from(map.keys()).forEach(function (node) {
                if (!parent || parent === node || parent.contains(node)) { if (map === motions) cancel(node); else retire(map, node); }
            }); });
        };
        var track = function (map, node, animation, cleanup) {
            var record = {animation:animation, cleanup:cleanup}; map.set(node, record);
            animation.onfinish = animation.oncancel = function () { if (map.get(node) === record) { map.delete(node); cleanup?.(); } };
        };
        var reveal = function (node, kind, delay) {
            if (!node || !node.isConnected) return;
            var root = document.documentElement;
            if (window.__univerMotion === false || !root.classList.contains('univer-mobile') || root.classList.contains('univer-app-keyboard') || !node.animate) { cancel(node); return; }
            var current = motions.has(node) ? getComputedStyle(node) : null;
            retire(ghosts, node);
            var gentle = reduce(), content = kind === 'content', horizontal = kind === 'forward' || kind === 'back';
            var from = current ? {opacity:current.opacity, transform:current.transform} :
                {opacity:gentle ? .4 : content ? .35 : .2, transform:gentle ? 'none' : horizontal ? 'translateX(' + (kind === 'back' ? '-16px' : '16px') + ') scale(.985)' : content ? 'translateY(8px) scale(.985)' : kind === 'tile' ? 'translateY(10px) scale(.97)' : 'translateY(-8px) scale(.97)'};
            cancel(node);
            var animation = node.animate([from, {opacity:1, transform:'none'}],
                {duration:gentle ? 180 : content ? 220 : kind === 'tile' ? 200 : 240, delay:gentle ? 0 : (delay || 0), easing:ease, fill:'backwards'});
            motions.set(node, animation);
            var finish = function () { if (motions.get(node) === animation) motions.delete(node); };
            animation.onfinish = animation.oncancel = finish;
        };
        var resize = function (node, before, duration) {
            if (!node?.isConnected || !allowed() || reduce() || !node.animate) return;
            var after = node.getBoundingClientRect().height;
            if (before <= 0 || after <= 0 || Math.abs(before - after) < 1) return;
            // A long journal or menu would relayout on every frame of a height animation on a phone.
            if (Math.max(before, after) > innerHeight * 1.5) return;
            retire(layouts, node);
            track(layouts, node, node.animate([{height:before + 'px', maxHeight:'none', overflow:'hidden'}, {height:after + 'px', maxHeight:'none', overflow:'hidden'}],
                {duration:duration || 220, easing:ease, fill:'backwards'}));
        };
        var change = function (node, mutate, kind) {
            var before = node?.getBoundingClientRect().height || 0; stop(node); mutate();
            resize(node, before); reveal(node, kind || 'content');
        };
        var reflow = function (parent, mutate) {
            var focus = parent.contains(document.activeElement) ? document.activeElement : null;
            var before = new Map(); Array.from(parent.children).forEach(function (node) { before.set(node, node.getBoundingClientRect()); });
            stop(parent); mutate();
            if (focus?.isConnected && document.activeElement !== focus) focus.focus({preventScroll:true});
            if (!allowed() || reduce()) return;
            before.forEach(function (rect, node) {
                if (!node.isConnected || !node.animate || !rect.width || !rect.height) return;
                var after = node.getBoundingClientRect(), x = rect.left - after.left, y = rect.top - after.top;
                if (!after.width || !after.height || Math.abs(x) + Math.abs(y) < 1) return;
                var a = node.animate([{transform:'translate(' + x + 'px,' + y + 'px)'}, {transform:'none'}],
                    {duration:180, easing:'cubic-bezier(.77,0,.175,1)', fill:'backwards'});
                motions.set(node, a); a.onfinish = a.oncancel = function () { if (motions.get(node) === a) motions.delete(node); };
            });
        };
        var exit = function (node) {
            if (!node?.isConnected || !allowed() || !node.animate) { stop(node); return; }
            var visual = getComputedStyle(node), opacity = visual.opacity, transform = visual.transform;
            cancel(node); var rect = node.getBoundingClientRect();
            if (!rect.width || !rect.height) { stop(node); return; }
            // A passive visual copy lets the real popup close and release focus immediately.
            // Computed styles avoid duplicate adapter classes/IDs and preserve the already painted shape.
            var clone = node.cloneNode(true), sources = [node].concat(Array.from(node.querySelectorAll('*'))), copies = [clone].concat(Array.from(clone.querySelectorAll('*')));
            var properties = 'display position top right bottom left box-sizing width height min-width min-height max-width max-height margin padding border border-radius background color opacity font font-family font-size font-weight line-height letter-spacing text-align text-decoration white-space overflow gap align-items justify-content flex flex-direction flex-wrap grid-template-columns grid-column transform transform-origin box-shadow list-style vertical-align object-fit -webkit-mask mask'.split(' ');
            copies.forEach(function (copy, index) {
                var css = getComputedStyle(sources[index]);
                Array.from(copy.attributes).forEach(function (attr) { if (/^(?:id|class|name|autofocus|on)/i.test(attr.name)) copy.removeAttribute(attr.name); });
                properties.forEach(function (property) { copy.style.setProperty(property, css.getPropertyValue(property)); });
                copy.style.pointerEvents = 'none'; copy.tabIndex = -1;
                ['::before','::after'].forEach(function (which) {
                    if (!window.CSS?.supports('content', '""')) return;
                    var pseudo = getComputedStyle(sources[index], which);
                    if (!pseudo.content || pseudo.content === 'none' || pseudo.content === 'normal') return;
                    var span = document.createElement('span');
                    properties.forEach(function (property) { span.style.setProperty(property, pseudo.getPropertyValue(property)); });
                    if (/^["'].*["']$/.test(pseudo.content)) span.textContent = pseudo.content.slice(1, -1);
                    if (which === '::before') copy.prepend(span); else copy.append(span);
                });
            });
            clone.querySelectorAll('script,style,iframe,frame,object,embed,link,meta').forEach(function (copy) { copy.remove(); });
            clone.setAttribute('data-univer-motion-ghost', ''); clone.setAttribute('aria-hidden', 'true'); clone.inert = true;
            clone.style.cssText += ';position:fixed!important;left:' + rect.left + 'px!important;top:' + rect.top + 'px!important;width:' + rect.width + 'px!important;height:' + rect.height + 'px!important;margin:0!important;z-index:10000!important;pointer-events:none!important;';
            stop(node); document.body.appendChild(clone);
            track(ghosts, node, clone.animate([{opacity:opacity, transform:transform}, {opacity:0, transform:reduce() ? 'none' : 'translateY(-8px) scale(.97)'}],
                {duration:140, easing:ease, fill:'forwards'}), function () { clone.remove(); });
        };
        var zoom = function (node, ratio) {
            if (!node?.isConnected) return; stop(node);
            if (!allowed() || reduce() || !node.animate || !Number.isFinite(ratio) || ratio <= 0) return;
            var animation = node.animate([{transform:'scale(' + ratio + ')',transformOrigin:'top left'}, {transform:'none',transformOrigin:'top left'}],
                {duration:180, easing:ease, fill:'backwards'});
            motions.set(node, animation); animation.onfinish = animation.oncancel = function () { if (motions.get(node) === animation) motions.delete(node); };
        };
        var detailsSelector = 'details.univer-app-menu,details.univer-app-account,details.univer-app-faq,details.univer-app-catalog,details.univer-app-info,details.univer-app-category,details.univer-app-university,details.univer-app-useful';
        document.addEventListener('click', function (event) {
            var summary = event.target.closest?.('summary'), details = summary?.parentElement;
            if (!details?.matches(detailsSelector) || !allowed() || event.detail === 0) return;
            event.preventDefault(); var open = !details.open;
            if (details.matches('.univer-app-menu,.univer-app-account') && document.documentElement.classList.contains('univer-app-unified')) { if (!open) exit(details); else stop(details); details.open = open; }
            else {
                var before = details.getBoundingClientRect().height; stop(details); details.open = open; resize(details, before, open ? 220 : 180);
                if (open) Array.from(details.children).filter(function (n) { return n.tagName !== 'SUMMARY'; }).forEach(function (n) { reveal(n, 'content'); });
            }
            details.__univerMotionToggle = open;
        }, true);
        window.__univerMotionUI = {reveal:reveal, stop:stop, reduced:reduce, change:change, reflow:reflow, exit:exit, resize:resize, zoom:zoom};
        document.addEventListener('visibilitychange', function () { if (document.hidden) stop(); });
        document.addEventListener('toggle', function (event) {
            var details = event.target;
            if (!details.matches?.(detailsSelector)) return;
            var handled = details.__univerMotionToggle === details.open; delete details.__univerMotionToggle;
            if (!details.open) { if (!handled && !ghosts.has(details)) stop(details); return; }
            if (details.classList.contains('univer-app-menu') && document.documentElement.classList.contains('univer-app-unified')) reveal(details, 'popup');
            else if (!handled) Array.from(details.children).filter(function (node) { return node.tagName !== 'SUMMARY'; }).forEach(function (node) { reveal(node, details.classList.contains('univer-app-account') ? 'popup' : 'content'); });
        }, true);
        var preference = window.matchMedia?.('(prefers-reduced-motion: reduce)');
        if (preference?.addEventListener) preference.addEventListener('change', function () { stop(); });
    }
})();
