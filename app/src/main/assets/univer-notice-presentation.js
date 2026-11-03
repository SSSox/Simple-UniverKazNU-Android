/* Presentation only. Never submits a form or conceals an unknown confirmation. */
(function install() {
    'use strict';
    var origin = 'https://univer.kaznu.kz';
    if (location.origin !== origin) return;
    if (window.__univerNoticePresentationV1) return;
    var enabled = false, observer, style, records = new Map(), released = new WeakSet();
    var timeoutMillis = 25000;
    function known(form) {
        try {
            var action = new URL(form.action, document.URL);
            if (action.origin !== origin || !/^\/student\/attentionStudents\/\d+\/?$/.test(action.pathname)) return false;
            var check = form.querySelector('input#check[type="checkbox"]');
            var label = form.querySelector('label[for="check"]');
            var button = form.querySelector('input#btn[type="submit"],button#btn[type="submit"]');
            return !!check && !!button && !!label &&
                /i confirm that i have read this notice|я подтверждаю,? что проч[её]л данное уведомление|мен бұл хабарламаны оқығанымды растаймын/i.test(label.textContent.replace(/\s+/g, ' '));
        } catch (_) { return false; }
    }
    function restore(form, allowAgain) {
        var record = records.get(form);
        if (!record) return;
        clearTimeout(record.timer);
        form.removeAttribute('data-univer-app-notice-pending');
        if (record.aria === null) form.removeAttribute('aria-hidden');
        else form.setAttribute('aria-hidden', record.aria);
        records.delete(form);
        if (!allowAgain) released.add(form);
    }
    function frames(callback) {
        document.querySelectorAll('iframe,frame').forEach(function (frame) {
            try {
                var child = frame.contentWindow;
                if (child && child.location.origin === origin) callback(child);
            } catch (_) { /* External frames receive no code or configuration. */ }
        });
    }
    function scan() {
        if (!enabled) return;
        // Disconnect during adapter-owned attributes/style/ARIA mutations.
        observer.disconnect();
        try {
            if (!style && document.documentElement) {
                style = document.createElement('style');
                style.id = 'univer-app-notice-style';
                // Opacity retains layout and the site's handlers, and does not defeat automation's visible() check.
                style.textContent = 'form[data-univer-app-notice-pending]{opacity:0!important;pointer-events:none!important}';
                (document.head || document.documentElement).appendChild(style);
            }
            records.forEach(function (_, form) {
                if (!form.isConnected || !known(form)) restore(form, true);
            });
            document.querySelectorAll('form#attentionSt').forEach(function (form) {
                if (!known(form) || released.has(form) || records.has(form)) return;
                var record = {aria:form.getAttribute('aria-hidden'), timer:null};
                records.set(form, record);
                form.setAttribute('data-univer-app-notice-pending', '');
                form.setAttribute('aria-hidden', 'true');
                record.timer = setTimeout(function () {
                    observer.disconnect();
                    restore(form, false);
                    observe();
                }, timeoutMillis);
            });
            // Fallback for existing same-origin frames when document-start is unavailable.
            frames(function (child) {
                if (!child.__univerNoticePresentationV1 && child.document.readyState !== 'loading')
                    child.eval('(' + install.toString() + ')()');
                child.__univerNoticePresentationV1?.setEnabled(true);
            });
        } finally { observe(); }
    }
    function observe() {
        if (enabled) observer.observe(document, {childList:true, subtree:true, attributes:true,
            attributeFilter:['id','type','action','for']});
    }
    function setEnabled(value, retry) {
        if (retry) released = new WeakSet();
        if (enabled === !!value && !retry) return;
        enabled = !!value;
        if (enabled) {
            if (!observer) observer = new MutationObserver(scan);
            if (retry) frames(function (child) { child.__univerNoticePresentationV1?.setEnabled(true, true); });
            document.addEventListener('load', scan, true);
            document.addEventListener('DOMContentLoaded', scan);
            scan();
        } else {
            observer?.disconnect();
            document.removeEventListener('load', scan, true);
            document.removeEventListener('DOMContentLoaded', scan);
            records.forEach(function (_, form) { restore(form, false); });
            if (style) style.remove();
            style = null;
            frames(function (child) { child.__univerNoticePresentationV1?.setEnabled(false); });
        }
    }
    // Named entry allows the same code to initialize accessible frames without a JavascriptInterface.
    window.__univerNoticePresentationV1 = {setEnabled:setEnabled, get enabled() {return enabled;}};
    setEnabled(true);
})();
