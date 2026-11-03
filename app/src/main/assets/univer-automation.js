/* Verified live login DOM and user-supplied notice / AJAX reauthentication / agreement HTML.
 * Only the site's own click/submit handlers send requests. No credentials in returned status. */
(function () {
    'use strict';
    var origin = 'https://univer.kaznu.kz';
    if (location.origin !== origin || window.__univerAutomationV1) return;
    var token = Date.now().toString(36) + Math.random().toString(36).slice(2);
    var loginClicked = false, sequence = 0;
    var episodes = {notice: {}, agreement: {}, reauth: {}};
    var ajax = {reauth: {state: '', challenge: ''}, agreement: {state: '', challenge: ''}};
    var hooked = new WeakSet();
    var LOGIN_PATH = /^\/user\/login(?:moodle)?\/?$/i, NOTICE_PATH = /^\/student\/attentionStudents\/\d+\/?$/i;
    // The site's own AJAX endpoints whose result settles a pending reauthentication or agreement.
    var AJAX_PATHS = {reauth: '/user/ajl', agreement: '/user/setagree'};
    function visible(el) {
        if (!el || el.hidden || !el.getClientRects().length) return false;
        var win = el.ownerDocument.defaultView;
        var style = win.getComputedStyle(el);
        if (style.visibility === 'hidden' || style.display === 'none') return false;
        try { if (win !== window && (!win.frameElement || !visible(win.frameElement))) return false; }
        catch (_) { return false; }
        return true;
    }
    function normalize(text) { return (text || '').replace(/\s+/g, ' ').trim().toLowerCase(); }
    /** [href] resolved against [base] when it stays on the trusted origin, otherwise null. */
    function siteUrl(href, base) {
        try { var url = new URL(href, base || location.href); return url.origin === origin ? url : null; }
        catch (_) { return null; }
    }
    function trustedAction(form) { return !!siteUrl(form.action, form.ownerDocument.URL); }
    function documents() {
        var result = [document];
        for (var i = 0; i < result.length && result.length < 8; i++) {
            for (var frame of result[i].querySelectorAll('iframe,frame')) {
                try {
                    var doc = frame.contentDocument;
                    if (visible(frame) && doc && doc.defaultView.location.origin === origin &&
                        doc.readyState === 'complete' && !result.includes(doc)) result.push(doc);
                } catch (_) { /* Cross-origin frames remain untouched. */ }
                if (result.length === 8) break;
            }
        }
        result.forEach(hookAjax);
        return result;
    }
    function endpoint(settings, path) {
        var url = siteUrl(settings.url);
        return !!url && url.pathname.replace(/\/$/, '') === path && (settings.type || '').toUpperCase() === 'POST';
    }
    /** Whether [settings] is the AJAX call that [kind]'s pending submission in [doc] waits for. */
    function awaited(kind, doc, settings) {
        var call = ajax[kind];
        return call.state === 'pending' && call.doc === doc && endpoint(settings, AJAX_PATHS[kind]);
    }
    function hookAjax(doc) {
        var jq = doc.defaultView.jQuery;
        if (!jq || hooked.has(doc)) return;
        hooked.add(doc);
        jq(doc).bind('ajaxSuccess.univerApp', function (_, xhr, settings) {
            if (awaited('reauth', doc, settings)) {
                // Retain only the result code; the site's handler owns the updated hidden key.
                try { ajax.reauth.state = JSON.parse(xhr.responseText).code === 1 ? 'success' : 'failure'; }
                catch (_) { ajax.reauth.state = 'failure'; }
                if (ajax.reauth.state === 'success') episodes.reauth.active = false;
            }
            if (awaited('agreement', doc, settings)) ajax.agreement.state = 'success';
        });
        jq(doc).bind('ajaxError.univerApp', function (_, xhr, settings) {
            for (var kind in AJAX_PATHS) if (awaited(kind, doc, settings)) ajax[kind].state = 'failure';
        });
    }
    function loginForm() {
        if (!LOGIN_PATH.test(location.pathname)) return null;
        var form = document.querySelector('form#login_frm');
        if (!form || !trustedAction(form)) return null;
        var login = Array.from(form.querySelectorAll('input[type="text"]')).find(visible);
        var password = Array.from(form.querySelectorAll('input[type="password"]')).find(visible);
        var button = form.querySelector('input[name="btn"][type="submit"]') || form.querySelector('[type="submit"]');
        return login && password && visible(button) && !login.disabled && !password.disabled && !button.disabled
            ? {form: form, login: login, password: password, button: button} : null;
    }
    function readNotice(text) {
        return /i confirm that i have read this notice|я подтверждаю,? что проч[её]л данное уведомление|мен бұл хабарламаны оқығанымды растаймын/i.test(normalize(text));
    }
    /** The same #iconfirm sentence. Russian is the observed label; Kazakh and English are that sentence. */
    function readAgreement(text) {
        return /прочитал(?:а)? договор|i have read the (?:agreement|contract)|шартты оқыдым|келісімді оқыдым/.test(normalize(text));
    }
    function noticeForm(docs) {
        for (var doc of docs) for (var form of doc.querySelectorAll('form#attentionSt, form[action*="/student/attentionStudents/"]')) {
            var action = siteUrl(form.action, form.ownerDocument.URL);
            if (!action || !NOTICE_PATH.test(action.pathname)) continue;
            var check = form.querySelector('input#check[type="checkbox"]');
            var label = form.querySelector('label[for="check"]');
            var button = form.querySelector('input#btn[type="submit"], button#btn[type="submit"]');
            if (!check || !label) {
                for (var candidate of form.querySelectorAll('input[type="checkbox"]')) {
                    var candidateLabel = Array.from(form.querySelectorAll('label')).find(function (l) {
                        return l.htmlFor === candidate.id || l.contains(candidate);
                    });
                    if (candidateLabel && readNotice(candidateLabel.textContent)) { check = candidate; label = candidateLabel; break; }
                }
            }
            if (!button) button = Array.from(form.querySelectorAll('input[type="submit"], button[type="submit"]'))
                .find(function (b) { return /submit|отправить|ж[іi]беру/i.test(b.value || b.textContent); });
            if (visible(check) && !check.disabled && label && readNotice(label.textContent) && visible(button))
                return {form: form, check: check, button: button};
        }
        return null;
    }
    function reauthForm(docs) {
        for (var doc of docs) {
            var form = doc.querySelector('#auth_modal form#modal_auth_frm');
            if (!form || !trustedAction(form)) continue;
            var login = form.querySelector('input#modal_auth_login[type="text"][name="login"]');
            var password = form.querySelector('input#modal_auth_pass[type="password"][name="password"]');
            var button = form.querySelector('#modal_auth_ajl_btn[type="submit"]');
            if (!visible(login) || !visible(password) || !visible(button)) continue;
            return {form: form, login: login, password: password, button: button,
                attempt: parseInt(doc.getElementById('auth_try')?.textContent || '1', 10),
                ready: !!doc.defaultView.jQuery && typeof doc.defaultView.ajl === 'function' &&
                    !!form.querySelector('input#modal_auth_key[type="hidden"][name="key"]') &&
                    !login.disabled && !password.disabled && !button.disabled};
        }
        return null;
    }
    function agreementForm(docs) {
        for (var doc of docs) {
            var box = doc.querySelector('#modal_agreement');
            var form = box?.querySelector('form#agree_frm');
            var check = box?.querySelector('input#iconfirm[type="checkbox"]');
            var button = box?.querySelector('button.btn-confirm[onclick*="agree()"]');
            var label = box?.querySelector('label[for="iconfirm"]');
            if (form && trustedAction(form) && visible(check) && !check.disabled && visible(button) &&
                label && readAgreement(label.textContent) && doc.defaultView.jQuery &&
                typeof doc.defaultView.agree === 'function') return {form: form, box: box, check: check, button: button};
        }
        return null;
    }
    function hash(text) {
        var result = 2166136261;
        for (var i = 0; i < text.length; i++) { result ^= text.charCodeAt(i); result = Math.imul(result, 16777619); }
        return (result >>> 0).toString(16);
    }
    function episode(kind, target) {
        var e = episodes[kind];
        if (target) {
            if (!e.active || e.node !== target.form) {
                e.node = target.form; e.active = true; e.id = token + ':' + kind + ':' + (++sequence);
                e.checked = false; e.clicked = false;
            }
            e.absent = 0;
        } else if (e.active && ajax[kind]?.state !== 'pending') {
            if (!e.absent) e.absent = Date.now();
            if (Date.now() - e.absent >= 1_500) e.active = false;
        }
        return e;
    }
    function currentLanguage() {
        for (var flag of document.querySelectorAll('.header img')) {
            if (flag.closest('a')) continue;
            var match = new URL(flag.src, location.href).pathname.match(/\/(ru|kz|en)\.png$/i);
            if (match) return match[1].toLowerCase();
        }
        // The notice has static lang="kz" even when another language is selected.
        return '';
    }
    function signedIn() {
        return Array.from(document.querySelectorAll('a[href*="/user/logout"]')).some(function (a) {
            var url = siteUrl(a.href); return !!url && /^\/user\/logout\/?$/.test(url.pathname);
        });
    }
    var interactiveSince = 0;
    /* A hanging third-party image or counter must not block the login form for ever: an interactive DOM is
     * accepted after a short grace period, once the site's jQuery ready handlers have run. */
    function documentReady() {
        if (document.readyState === 'complete') return true;
        if (document.readyState !== 'interactive') return false;
        if (!interactiveSince) interactiveSince = Date.now();
        return Date.now() - interactiveSince >= 4000;
    }
    function probe() {
        if (location.origin !== origin) return {trusted: false};
        var docs = documents(), login = loginForm(), notice = noticeForm(docs), agreement = agreementForm(docs), reauth = reauthForm(docs);
        var n = episode('notice', notice), a = episode('agreement', agreement), r = episode('reauth', reauth);
        if (reauth && reauth.attempt > 1 && ajax.reauth.state === 'pending') ajax.reauth.state = 'failure';
        var ready = documentReady();
        var loginPage = LOGIN_PATH.test(location.pathname);
        return {trusted: true, document: token, ready: ready, domReady: document.readyState !== 'loading',
            // The header's own Logout link appears only for a signed-in account. The read notice page has no
            // header, but it and the agreement are shown only to a signed-in account too.
            authenticated: ready && !loginPage && !login && !reauth && (signedIn() || !!notice || !!agreement),
            hasPendingForm: !!login || !!notice || !!agreement || !!reauth,
            loginPage: loginPage, loginReady: ready && !!window.jQuery && !!login,
            // Wall-clock age of this document, including time asleep: a long-open login form has an expired key.
            age: Math.max(0, Math.round(Date.now() - (performance.timeOrigin || Date.now()))),
            notice: ready && !!notice, checked: !!notice && notice.check.checked, submitEnabled: !!notice && !notice.button.disabled,
            noticeKey: notice ? new URL(notice.form.action).pathname + ':' + hash(normalize(notice.form.textContent)) : '',
            noticeEpisode: notice ? n.id : '',
            agreement: ready && !!agreement, agreementChecked: !!agreement && agreement.check.checked,
            agreementEnabled: !!agreement && !agreement.button.disabled,
            agreementKey: agreement ? 'additional:' + hash(normalize(agreement.form.textContent)) : '',
            agreementEpisode: agreement ? a.id : '', agreementOutcome: ajax.agreement.state,
            agreementResultEpisode: ajax.agreement.challenge,
            reauth: ready && !!reauth, reauthReady: ready && !!reauth && reauth.ready,
            reauthAttempt: reauth ? reauth.attempt : 0, reauthChallenge: reauth ? r.id : '',
            reauthOutcome: ajax.reauth.state, reauthResultChallenge: ajax.reauth.challenge,
            // innerText of the entire grade/material catalogue forces layout every polling tick.
            language: currentLanguage(), hasContent: !!document.body && Array.from(document.body.children).some(function (node) {
                return !/^(SCRIPT|STYLE|NOSCRIPT)$/.test(node.tagName) && visible(node) && (node.textContent || '').trim().length > 0;
            })};
    }
    function valid(expected) { return location.origin === origin && token === expected; }
    function setValue(input, value) {
        var win = input.ownerDocument.defaultView;
        Object.getOwnPropertyDescriptor(win.HTMLInputElement.prototype, 'value').set.call(input, value);
        input.setAttribute('value', value); // Legacy jQuery reads attr('value'). Readonly is preserved.
        input.dispatchEvent(new win.Event('input', {bubbles: true}));
        input.dispatchEvent(new win.Event('change', {bubbles: true}));
    }
    var CONFIRMATIONS = {notice: noticeForm, agreement: agreementForm};
    /** The notice or agreement form with its episode, or null while it is absent or [appearance] names an earlier one. */
    function confirmation(kind, appearance) {
        var target = CONFIRMATIONS[kind](documents()), e = episode(kind, target);
        return target && (!appearance || appearance === e.id) ? {target: target, episode: e} : null;
    }
    /** Checked, enabled and not yet submitted in this episode. */
    function submittable(found) {
        return !!found && !found.episode.clicked && found.target.check.checked && !found.target.button.disabled;
    }
    function check(kind, expected, appearance) {
        var found = valid(expected) && confirmation(kind, appearance);
        if (!found || found.episode.checked) return 'wait';
        var target = found.target; found.episode.checked = true;
        if (!target.check.checked) target.check.click(); // Runs calc() / the agreement's change handler.
        target.check.dispatchEvent(new target.form.ownerDocument.defaultView.Event('change', {bubbles: true}));
        return target.check.checked ? 'checked' : 'wait';
    }
    function prepareNotice(expected, appearance) {
        if (!valid(expected)) return 'wait';
        check('notice', expected, appearance);
        return submittable(confirmation('notice', appearance)) ? 'ready' : 'wait';
    }
    function submit(kind, expected, appearance) {
        var found = valid(expected) && confirmation(kind, appearance);
        if (!submittable(found)) return 'wait';
        found.episode.clicked = true;
        if (kind === 'agreement') ajax.agreement = {state: 'pending', challenge: found.episode.id, doc: found.target.form.ownerDocument};
        found.target.button.click();
        return 'submitted';
    }
    window.__univerAutomationV1 = {
        probe: probe,
        login: function (expected, login, password) {
            if (!valid(expected) || loginClicked || !probe().loginReady) return 'wait';
            var target = loginForm(); loginClicked = true;
            setValue(target.login, login); setValue(target.password, password);
            target.button.click(); // Dynamic encryption/key handler remains intact.
            return 'submitted';
        },
        reauthenticate: function (expected, challenge, login, password) {
            if (!valid(expected)) return 'wait';
            var target = reauthForm(documents()), e = episode('reauth', target);
            if (!target || !target.ready || target.attempt !== 1 || e.id !== challenge || e.clicked || ajax.reauth.state === 'pending') return 'wait';
            // Do not replace the account bound by the site's readonly reauthentication field.
            // An empty readonly field names no account yet and receives the saved login.
            var bound = target.login.readOnly ? target.login.value.trim() : '';
            if (bound && bound.toLowerCase() !== String(login).trim().toLowerCase()) return 'account-mismatch';
            e.clicked = true; ajax.reauth = {state: 'pending', challenge: e.id, doc: target.form.ownerDocument};
            if (!bound) setValue(target.login, login);
            setValue(target.password, password);
            target.button.click(); // Official ajl() serializes nonce/cheat and performs AJAX.
            return 'submitted';
        },
        checkNotice: function (expected, appearance) { return check('notice', expected, appearance); },
        prepareNotice: prepareNotice,
        submitNotice: function (expected, appearance) { return submit('notice', expected, appearance); },
        checkAgreement: function (expected, appearance) { return check('agreement', expected, appearance); },
        submitAgreement: function (expected, appearance) { return submit('agreement', expected, appearance); },
        switchLanguage: function (code) {
            if (location.origin !== origin || !/^(ru|kz|en)$/.test(code)) return 'missing';
            var link = Array.from(document.querySelectorAll('a[href]')).find(function (a) {
                var url = siteUrl(a.href); return !!url && url.pathname === '/lang/change/' + code + '/';
            });
            if (!link) return 'missing';
            link.click(); return 'clicked';
        }
    };
})();
