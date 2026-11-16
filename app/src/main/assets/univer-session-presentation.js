/* Only known Univer authentication UI. No credentials, requests or handler replacement. */
(function install() {
    'use strict';
    var origin = 'https://univer.kaznu.kz';
    if (location.origin !== origin || window.__univerSessionPresentationV1) return;
    var enabled = false, observer, style, records = new Map(), released = new WeakSet();
    var timeoutMillis = 25000;
    var loginTimer, loginReleased = false;
    var loginPage = /^\/user\/login(?:moodle)?\/?$/i.test(location.pathname);
    function visible(element) {
        if (!element || element.hidden || !element.getClientRects().length) return false;
        var css = getComputedStyle(element);
        return css.visibility !== 'hidden' && css.display !== 'none';
    }
    function authForm() {
        var form = document.querySelector('#auth_modal form#modal_auth_frm');
        if (!form) return null;
        try { if (new URL(form.action, document.URL).origin !== origin) return null; } catch (_) { return null; }
        var login = form.querySelector('input#modal_auth_login[type="text"][name="login"]');
        var password = form.querySelector('input#modal_auth_pass[type="password"][name="password"]');
        var button = form.querySelector('#modal_auth_ajl_btn[type="submit"]');
        if (!login || !password || !button || !visible(login) || !visible(password) || !visible(button) ||
            !form.querySelector('input#modal_auth_key[type="hidden"][name="key"]') ||
            !form.querySelector('input#cheat_auth[type="hidden"][name="cheat"]')) return null;
        return form;
    }
    // Half of a dialog can't be verified yet. It waits out of sight until the parser is done: then it is the known form
    // or it comes back.
    function arrivingDialog() {
        var box = document.readyState === 'loading' ? document.getElementById('auth_modal') : null;
        return visible(box) ? box : null;
    }
    function restore(element, allowAgain) {
        var record = records.get(element);
        if (!record) return;
        clearTimeout(record.timer);
        element.removeAttribute('data-univer-app-auth-pending');
        if (record.aria === null) element.removeAttribute('aria-hidden');
        else element.setAttribute('aria-hidden', record.aria);
        records.delete(element);
        if (!allowAgain) released.add(element);
    }
    function releaseLogin() {
        loginReleased = true;
        clearTimeout(loginTimer);
        document.documentElement?.removeAttribute('data-univer-app-login-pending');
        if (document.body) restore(document.body, false);
    }
    function frames(callback) {
        document.querySelectorAll('iframe,frame').forEach(function (frame) {
            try { if (frame.contentWindow.location.origin === origin) callback(frame.contentWindow); }
            catch (_) { /* Exact-origin frames only. */ }
        });
    }
    function conceal(element) {
        if (!element || records.has(element) || released.has(element)) return;
        var record = {aria:element.getAttribute('aria-hidden'), timer:null};
        records.set(element, record);
        element.setAttribute('data-univer-app-auth-pending', '');
        element.setAttribute('aria-hidden', 'true');
        record.timer = setTimeout(function () {
            observer.disconnect(); restore(element, false); observe();
        }, timeoutMillis);
    }
    function scan() {
        if (!enabled) return;
        observer.disconnect();
        try {
            if (!style && document.documentElement) {
                style = document.createElement('style');
                style.id = 'univer-app-session-style';
                style.textContent = 'html[data-univer-app-login-pending] body,[data-univer-app-auth-pending]{opacity:0!important}' +
                    'html[data-univer-app-login-pending] body,#auth_modal[data-univer-app-auth-pending],#simplemodal-container[data-univer-app-auth-pending]{pointer-events:none!important}';
                (document.head || document.documentElement).appendChild(style);
            }
            if (loginPage && !loginReleased && document.documentElement) {
                document.documentElement.setAttribute('data-univer-app-login-pending', '');
                if (!loginTimer) loginTimer = setTimeout(releaseLogin, timeoutMillis);
                if (document.body) conceal(document.body);
            }
            var form = authForm();
            var box = form?.closest('#auth_modal') || arrivingDialog();
            var container = form?.closest('#simplemodal-container');
            var overlay = container && document.getElementById('simplemodal-overlay');
            records.forEach(function (_, element) {
                if (element !== document.body && (!element.isConnected || (element !== box && element !== container && element !== overlay)))
                    restore(element, true);
            });
            conceal(box); conceal(container); conceal(overlay);
            frames(function (child) {
                if (!child.__univerSessionPresentationV1 && child.document.readyState !== 'loading')
                    child.eval('(' + install.toString() + ')()');
                child.__univerSessionPresentationV1?.setEnabled(true);
            });
        } finally { observe(); }
    }
    function observe() {
        if (enabled) observer.observe(document, {childList:true, subtree:true, attributes:true,
            attributeFilter:['id','type','action','style','class','hidden']});
    }
    function setEnabled(value, retry) {
        if (enabled === !!value && !retry) return;
        if (retry) {
            observer?.disconnect();
            records.forEach(function (_, element) { restore(element, true); });
            released = new WeakSet(); loginReleased = false;
            clearTimeout(loginTimer); loginTimer = null;
        }
        enabled = !!value;
        if (enabled) {
            if (!observer) observer = new MutationObserver(scan);
            if (retry) frames(function (child) { child.__univerSessionPresentationV1?.setEnabled(true, true); });
            document.addEventListener('load', scan, true);
            document.addEventListener('DOMContentLoaded', scan);
            scan();
        } else {
            observer?.disconnect();
            document.removeEventListener('load', scan, true);
            document.removeEventListener('DOMContentLoaded', scan);
            releaseLogin();
            records.forEach(function (_, element) { restore(element, false); });
            if (style) style.remove(); style = null;
            frames(function (child) { child.__univerSessionPresentationV1?.setEnabled(false); });
        }
    }
    window.__univerSessionPresentationV1 = {setEnabled:setEnabled};
    setEnabled(true);
})();
