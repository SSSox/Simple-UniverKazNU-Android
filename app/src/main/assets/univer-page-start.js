(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerPresentationV1) return;
    var token = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
    var observer, timer, style, released = false;
    function install() {
        if (released || !document.documentElement) return;
        if (observer) observer.disconnect();
        document.documentElement.setAttribute('data-univer-app-pending', '');
        style = document.createElement('style');
        style.id = 'univer-app-first-paint';
        style.textContent = 'html[data-univer-app-pending] body{visibility:hidden!important}';
        (document.head || document.documentElement).appendChild(style);
    }
    function release() {
        released = true;
        if (observer) observer.disconnect();
        clearTimeout(timer);
        if (style) style.remove();
        if (document.documentElement) document.documentElement.removeAttribute('data-univer-app-pending');
    }
    window.__univerPresentationV1 = {token:token, release:release, ready:false};
    if (document.documentElement) install();
    else {
        observer = new MutationObserver(install);
        observer.observe(document, {childList:true, subtree:true});
    }
    // Fail open if the host disappears or cannot run the mobile adapter. Never conceal a page forever.
    timer = setTimeout(release, 8000);
    function ready() {
        // Run after the site's synchronous DOMContentLoaded / jQuery ready handlers.
        Promise.resolve().then(function () {
            window.__univerPresentationV1.ready = true;
            if (window === window.top && window.__univerPageEvents)
                window.__univerPageEvents.postMessage(token);
            else if (window !== window.top) {
                try {
                    if (window.parent.location.origin === location.origin)
                        window.parent.dispatchEvent(new window.parent.Event('univer-frame-ready'));
                } catch (_) { /* Cross-origin parents receive no events. */ }
            }
        });
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready, {once:true});
    else ready();
})();
