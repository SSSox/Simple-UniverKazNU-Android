/* Feedback motion of the presentation layer (shared with Windows desktop.js): reveal, resize, reflow and exit
   animations that respect reduced motion and the app's animation setting. Loaded right before univer-style.js. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz') return;
    if (!window.__univerMotionUI) {
        var motions = new Map(), layouts = new Map(), ghosts = new Map(), closingDetails = new WeakMap();
        var ease = 'cubic-bezier(.23,1,.32,1)';
        var foldEase = 'cubic-bezier(.45,0,.25,1)';
        var span = function (distance) { return Math.round(Math.min(420, Math.max(220, 220 + Math.abs(distance || 0) * .2))); };
        var options = function (value) { return value && typeof value === 'object' ? value : null; };
        var reduce = function () { return !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches; };
        var cancel = function (node) { var animation = motions.get(node); if (animation) { motions.delete(node); animation.cancel(); } };
        var allowed = function () { var root = document.documentElement; return window.__univerMotion !== false && root.classList.contains('univer-mobile') && !root.classList.contains('univer-app-keyboard') && !root.classList.contains('univer-app-still'); };
        var retire = function (map, node) { var record = map.get(node); if (record) { map.delete(node); record.animation.cancel(); record.cleanup?.(); } };
        var stop = function (parent) {
            [motions, layouts, ghosts].forEach(function (map) { Array.from(map.keys()).forEach(function (node) {
                if (!parent || parent === node || parent.contains(node)) { if (map === motions) cancel(node); else retire(map, node); }
            }); });
        };
        var track = function (map, node, animation, cleanup) {
            var record = {animation:animation, cleanup:cleanup}; map.set(node, record);
            animation.onfinish = animation.oncancel = function () {
                if (map.get(node) !== record) return;
                map.delete(node); cleanup?.(animation.playState === 'finished');
            };
        };
        var popupBody = function (node) {
            if (node?.matches('.univer-app-account,.univer-app-news-period')) return node.querySelector(':scope > div') || node;
            return node;
        };
        var popupDuration = function (height, opacity, opening) {
            var value = opacity === '' || opacity === undefined || opacity === null ? NaN : Number(opacity);
            var fraction = Number.isFinite(value) ? Math.min(1, Math.max(0, opening ? 1 - value : value)) : 1;
            return Math.max(80, Math.round(span(height) * fraction));
        };
        var reveal = function (node, kind, delay) {
            if (!node || !node.isConnected) return;
            var gentle = reduce(), popup = kind === 'popup', ghost = ghosts.get(node);
            if (!allowed() || !node.animate || (popup && gentle)) { cancel(node); retire(ghosts, node); return; }
            var current = ghost ? getComputedStyle(ghost.visual) : motions.has(node) ? getComputedStyle(node) : null;
            var content = kind === 'content', horizontal = kind === 'forward' || kind === 'back';
            var from = current ? {opacity:current.opacity, transform:current.transform} :
                {opacity:popup ? 0 : gentle ? .4 : content ? .35 : .2, transform:gentle ? 'none' : horizontal ? 'translateX(' + (kind === 'back' ? '-16px' : '16px') + ') scale(.985)' : content ? 'translateY(8px) scale(.985)' : kind === 'tile' ? 'translateY(10px) scale(.97)' : 'translateY(-8px) scale(.97)'};
            retire(ghosts, node); cancel(node);
            var animation = node.animate([from, {opacity:1, transform:'none'}],
                {duration:popup ? popupDuration(node.getBoundingClientRect().height, from.opacity, true) : gentle ? 180 : content ? 220 : kind === 'tile' ? 200 : 240,
                    delay:gentle ? 0 : (delay || 0), easing:popup ? foldEase : ease, fill:'backwards'});
            motions.set(node, animation);
            var finish = function () { if (motions.get(node) === animation) motions.delete(node); };
            animation.onfinish = animation.oncancel = finish;
        };
        var canFold = function (node) { return node?.isConnected && allowed() && !reduce() && !!node.animate; };
        // Keep the heading in view when an article or fold closes above the reader. Input takes the scroll back immediately.
        var scrollStart = function (anchor) {
            if (anchor?.isConnected) return {y:window.scrollY || window.pageYOffset || 0, top:anchor.getBoundingClientRect().top};
            return null;
        };
        var followHeight = function (node, start, before, after) {
            if (!start || before <= after) return;
            var top = start.top, from = start.y;
            var to = Math.max(0, Math.min(from, from + top - 12));
            if (top >= 80 || from === to) return;
            var frame, stopped = false, events = ['wheel','touchstart','pointerdown','keydown'];
            var move = function (y) { window.scrollTo({top:Math.round(y), left:window.scrollX || 0, behavior:'instant'}); };
            var stopFollowing = function () {
                stopped = true; cancelAnimationFrame(frame);
                events.forEach(function (type) { window.removeEventListener(type, stopFollowing, true); });
            };
            var step = function () {
                if (!node.isConnected) { stopFollowing(); return; }
                var progress = (before - node.getBoundingClientRect().height) / (before - after);
                move(from + (to - from) * Math.min(1, Math.max(0, progress)));
                frame = requestAnimationFrame(step);
            };
            events.forEach(function (type) { window.addEventListener(type, stopFollowing, {capture:true, passive:true}); });
            frame = requestAnimationFrame(step);
            return function (finished) { if (finished && !stopped) move(to); stopFollowing(); };
        };
        var boxEdges = function (node, css) {
            if (css.boxSizing === 'border-box') return 0;
            return ['paddingTop','paddingBottom','borderTopWidth','borderBottomWidth'].reduce(function (sum, key) { return sum + (parseFloat(css[key]) || 0); }, 0);
        };
        var animateHeight = function (node, before, after, done, follow, distance) {
            if (before < 0 || after <= 0 || Math.abs(before - after) < 1) { done?.(); return false; }
            var css = getComputedStyle(node), edges = boxEdges(node, css);
            var frame = function (height) {
                var pixels = Math.max(0, height - edges) + 'px';
                return {height:pixels, maxHeight:pixels, overflow:'hidden'};
            };
            var from = frame(before), to = frame(after);
            if (before <= 1) {
                ['paddingTop','paddingBottom'].forEach(function (key) { from[key] = '0px'; to[key] = css[key] || '0px'; });
            }
            node.setAttribute('data-univer-resizing', '');
            var animation = node.animate([from, to], {duration:span(distance !== undefined ? distance : after - before), easing:foldEase, fill:'both'});
            var finishFollowing = followHeight(node, follow, before, after);
            var settled = false, finish = function () { if (settled) return; settled = true; done?.(); };
            track(layouts, node, animation, function (finished) {
                finishFollowing?.(finished); node.removeAttribute('data-univer-resizing'); finish();
                if (finished) animation.cancel();
            });
            return true;
        };
        // Accepts either (node, before, {target, distance, follow}) or the older positional (node, before, duration, measured, follow).
        var resize = function (node, before, third, measured, follow) {
            if (!canFold(node)) return false;
            var wanted = options(third);
            var anchor = wanted?.follow || follow || null;
            var start = scrollStart(anchor);
            retire(layouts, node);
            var target = wanted?.target > 0 ? wanted.target : measured > 0 ? measured : 0;
            var after = target > 0 ? target : node.getBoundingClientRect().height;
            return animateHeight(node, before, after, null, start, wanted?.distance);
        };
        // A block folds shut to nothing and only then is hidden (done), so what follows it closes up instead of jumping.
        // done runs exactly once: at the end, when stopped, or immediately without motion.
        var collapse = function (node, done, settings) {
            var wanted = options(settings);
            var height = node?.isConnected ? node.getBoundingClientRect().height : 0;
            stop(node);
            if (!canFold(node) || height < 1) { done?.(); return false; }
            var css = getComputedStyle(node), edges = boxEdges(node, css);
            var pixels = Math.max(0, height - edges) + 'px';
            node.setAttribute('data-univer-resizing', '');
            var animation = node.animate(
                [{height:pixels, maxHeight:pixels, overflow:'hidden', opacity:1},
                 {height:'0px', maxHeight:'0px', overflow:'hidden', opacity:0, paddingTop:'0px', paddingBottom:'0px', marginTop:'0px', marginBottom:'0px'}],
                {duration:span(wanted?.distance !== undefined ? wanted.distance : height), easing:foldEase, fill:'both'});
            var settled = false, finish = function () { if (settled) return; settled = true; done?.(); };
            track(layouts, node, animation, function (finished) {
                node.removeAttribute('data-univer-resizing'); finish();
                if (finished) animation.cancel();
            });
            return true;
        };
        // A box closes to `target` px with its content still in place, and only then is changed (done): nothing inside
        // vanishes while the edge is still moving. Start height is read before stopping any run, so mid-open reversals stay smooth.
        var shut = function (node, target, done, settings) {
            var wanted = options(settings);
            var start = scrollStart(wanted?.follow);
            var height = node?.isConnected ? node.getBoundingClientRect().height : 0;
            stop(node);
            if (!canFold(node) || target < 0 || height - target < 1) { done?.(); return false; }
            return animateHeight(node, height, target, done, start, wanted?.distance);
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
        var GHOST_LIMIT = 80;
        var GHOST_SKIP = /^(?:SCRIPT|STYLE|IFRAME|FRAME|OBJECT|EMBED|LINK|META)$/i;
        var GHOST_PROPS = 'display position top right bottom left box-sizing width height min-width min-height max-width max-height margin padding border border-radius background color opacity font font-family font-size font-weight line-height letter-spacing text-align text-decoration white-space overflow gap align-items justify-content flex flex-direction flex-wrap grid-template-columns grid-column transform transform-origin box-shadow list-style vertical-align object-fit -webkit-mask mask'.split(' ');
        var GHOST_BOX_PROPS = ['box-sizing','background','background-color','border','border-top','border-right','border-bottom','border-left','border-radius','box-shadow','overflow'];
        var exit = function (node) {
            if (!node) return;
            node = popupBody(node);
            if (!canFold(node)) { stop(node); return; }
            var visual = getComputedStyle(node), opacity = visual.opacity, transform = visual.transform;
            cancel(node); var rect = node.getBoundingClientRect();
            if (!rect.width || !rect.height) { stop(node); return; }
            // A passive visual copy lets the real popup close and release focus immediately.
            // Computed styles avoid duplicate adapter classes/IDs and preserve the already painted shape;
            // large subtrees fade a lightweight panel shell instead of walking every descendant's style.
            var clone, pairs = [];
            if (node.getElementsByTagName('*').length > GHOST_LIMIT) {
                clone = document.createElement('div');
                GHOST_BOX_PROPS.forEach(function (property) {
                    var value = visual.getPropertyValue(property);
                    if (value) clone.style.setProperty(property, value);
                });
                clone.style.pointerEvents = 'none'; clone.tabIndex = -1;
            } else {
                clone = node.cloneNode(true);
                (function walk(source, copy) {
                    pairs.push([source, copy]);
                    var sKids = source.children, cKids = copy.children;
                    for (var i = sKids.length - 1; i >= 0; i--) {
                        if (GHOST_SKIP.test(sKids[i].tagName)) cKids[i]?.remove();
                    }
                    var j = 0;
                    for (var k = 0; k < sKids.length; k++) {
                        if (GHOST_SKIP.test(sKids[k].tagName)) continue;
                        if (cKids[j]) walk(sKids[k], cKids[j++]);
                    }
                })(node, clone);
                var pseudos = !!window.CSS?.supports?.('content', '""');
                pairs.forEach(function (pair) {
                    var source = pair[0], copy = pair[1];
                    var css = getComputedStyle(source);
                    Array.from(copy.attributes).forEach(function (attr) { if (/^(?:id|class|name|autofocus|on)/i.test(attr.name)) copy.removeAttribute(attr.name); });
                    GHOST_PROPS.forEach(function (property) { copy.style.setProperty(property, css.getPropertyValue(property)); });
                    copy.style.pointerEvents = 'none'; copy.tabIndex = -1;
                    if (!pseudos) return;
                    ['::before','::after'].forEach(function (which) {
                        var pseudo = getComputedStyle(source, which);
                        if (!pseudo.content || pseudo.content === 'none' || pseudo.content === 'normal') return;
                        var span = document.createElement('span');
                        GHOST_PROPS.forEach(function (property) { span.style.setProperty(property, pseudo.getPropertyValue(property)); });
                        if (/^["'].*["']$/.test(pseudo.content)) span.textContent = pseudo.content.slice(1, -1);
                        if (which === '::before') copy.prepend(span); else copy.append(span);
                    });
                });
            }
            clone.setAttribute('data-univer-motion-ghost', ''); clone.setAttribute('aria-hidden', 'true'); clone.inert = true;
            clone.style.cssText += ';position:fixed!important;box-sizing:border-box!important;left:' + rect.left + 'px!important;top:' + rect.top + 'px!important;width:' + rect.width + 'px!important;height:' + rect.height + 'px!important;margin:0!important;z-index:10000!important;pointer-events:none!important;';
            stop(node); document.body.appendChild(clone);
            pairs.forEach(function (pair) {
                pair[1].scrollTop = pair[0].scrollTop; pair[1].scrollLeft = pair[0].scrollLeft;
            });
            track(ghosts, node, clone.animate([{opacity:opacity || 1, transform:transform || 'none'}, {opacity:0, transform:'translateY(-8px) scale(.97)'}],
                {duration:popupDuration(rect.height, opacity, false), easing:foldEase, fill:'forwards'}), function () { clone.remove(); });
            ghosts.get(node).visual = clone;
        };
        var zoom = function (node, ratio) {
            if (!node?.isConnected) return; stop(node);
            if (!allowed() || reduce() || !node.animate || !Number.isFinite(ratio) || ratio <= 0) return;
            var animation = node.animate([{transform:'scale(' + ratio + ')',transformOrigin:'top left'}, {transform:'none',transformOrigin:'top left'}],
                {duration:180, easing:ease, fill:'backwards'});
            motions.set(node, animation); animation.onfinish = animation.oncancel = function () { if (motions.get(node) === animation) motions.delete(node); };
        };
        var detailsSelector = 'details.univer-app-menu,details.univer-app-account,details.univer-app-faq,details.univer-app-catalog,details.univer-app-info,details.univer-app-category,details.univer-app-university,details.univer-app-useful,details.univer-app-news-period,details.univer-app-news-pages,details.univer-app-attendance-options,details.univer-app-material-details,details.univer-app-course-heading';
        var isPopup = function (details) {
            return details.classList.contains('univer-app-news-period') ||
                (details.matches('.univer-app-menu,.univer-app-account') && document.documentElement.classList.contains('univer-app-unified'));
        };
        document.addEventListener('click', function (event) {
            var summary = event.target.closest?.('summary'), details = summary?.parentElement;
            if (!details?.matches(detailsSelector) || event.defaultPrevented ||
                (event.target !== summary && event.target.closest('a,button,input,select,textarea,[contenteditable]'))) return;
            var closing = closingDetails.get(details), animate = event.detail > 0 && canFold(details);
            if (!closing && !animate) {
                if (event.detail === 0 && isPopup(details)) details.__univerMotionToggle = !details.open;
                return;
            }
            event.preventDefault();
            var open = !details.open || !!closing;
            if (!open && details.contains(document.activeElement) && !summary.contains(document.activeElement)) summary.focus({preventScroll:true});
            if (isPopup(details)) {
                if (open) { details.open = true; reveal(popupBody(details), 'popup'); }
                else { exit(details); details.open = false; }
                details.__univerMotionToggle = open;
                return;
            }
            var before = details.getBoundingClientRect();
            if (open) {
                closingDetails.delete(details); details.removeAttribute('data-univer-shutting'); stop(details);
                details.__univerMotionToggle = true;
                details.open = true;
                if (animate) resize(details, before.height);
            } else {
                // The details stays open while its edge moves, so the content is clipped in place.
                var css = getComputedStyle(details), head = summary.getBoundingClientRect();
                var target = head.bottom - before.top + (parseFloat(css.paddingBottom) || 0) + (parseFloat(css.borderBottomWidth) || 0);
                var pending = {}; closingDetails.set(details, pending);
                details.setAttribute('data-univer-shutting', '');
                shut(details, target, function () {
                    if (closingDetails.get(details) !== pending) return;
                    closingDetails.delete(details); details.removeAttribute('data-univer-shutting');
                    details.__univerMotionToggle = false; details.open = false;
                }, {follow:details});
            }
        }, true);
        window.__univerMotionUI = {reveal:reveal, stop:stop, reduced:reduce, change:change, reflow:reflow, exit:exit, resize:resize, zoom:zoom, collapse:collapse, shut:shut, span:span, easing:foldEase};
        document.addEventListener('visibilitychange', function () { if (document.hidden) stop(); });
        document.addEventListener('toggle', function (event) {
            var details = event.target;
            if (!details.matches?.(detailsSelector)) return;
            var handled = details.__univerMotionToggle === details.open; delete details.__univerMotionToggle;
            if (!details.open) { if (!handled && !ghosts.has(popupBody(details))) stop(details); return; }
            if (!handled && isPopup(details)) reveal(popupBody(details), 'popup');
        }, true);
        var preference = window.matchMedia?.('(prefers-reduced-motion: reduce)');
        if (preference?.addEventListener) preference.addEventListener('change', function () { stop(); });
        else preference?.addListener?.(function () { stop(); });
    }
})();
