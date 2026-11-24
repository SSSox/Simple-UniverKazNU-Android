/* Verified live login DOM and user-supplied notice / AJAX reauthentication / agreement HTML.
 * Only the site's own click/submit handlers send requests. No credentials in returned status.
 * AutomationController registers this file as a document-start script of the main frame, with a per-WebView seal in
 * place of the placeholder: it then runs before any site script, defines __univerAutomationV1 so that it cannot be
 * replaced, and takes here the built-ins that touch the saved login and password. The controller hands the password
 * only to the object that carries its seal; a site script cannot install one (it never runs before this one). The copy
 * the controller evaluates later (a WebView without document-start scripts) keeps the placeholder: it confirms notices
 * and switches the language, but refuses to enter credentials. */
(function () {
    'use strict';
    var origin = 'https://univer.kaznu.kz';
    if (location.origin !== origin || window.__univerAutomationV1) return;
    var SEAL = '__UNIVER_AUTOMATION_SEAL__', sealed = /^[0-9a-f]{32}$/.test(SEAL);
    if (sealed && window !== window.top) return;
    // Taken before any site script when sealed: a later swap of these cannot see the values put into the forms.
    var call = Reflect.apply, describe = Object.getOwnPropertyDescriptor, BaseEvent = Event, SiteURL = URL;
    var holds = Node.prototype.contains, setAttribute = Element.prototype.setAttribute,
        findInDocument = Document.prototype.querySelector,
        dispatch = EventTarget.prototype.dispatchEvent, press = HTMLElement.prototype.click,
        setInputValue = describe(HTMLInputElement.prototype, 'value').set,
        inputType = describe(HTMLInputElement.prototype, 'type').get,
        inputDisabled = describe(HTMLInputElement.prototype, 'disabled').get,
        formAction = describe(HTMLFormElement.prototype, 'action').get,
        connected = describe(Node.prototype, 'isConnected').get,
        ownerOf = describe(Node.prototype, 'ownerDocument').get,
        documentUrl = describe(Document.prototype, 'URL').get,
        urlOrigin = describe(URL.prototype, 'origin').get,
        matches = RegExp.prototype.test, startDocument = document, LOGIN_PAGE = /^\/user\/login(?:moodle)?\/?$/i;
    var token = Date.now().toString(36) + Math.random().toString(36).slice(2);
    var loginClicked = false, sequence = 0, interactiveSince = 0;
    var episodes = {notice: {}, agreement: {}, reauth: {}};
    var ajax = {reauth: {state: '', challenge: ''}, agreement: {state: '', challenge: ''}};
    var hooked = new WeakSet();
    /* The account the next pages belong to, noted when the login form is sent (by the student or by automatic sign-in),
       so a page cache of one student (the syllabus tasks) is not shown to another student signing in on the same profile.
       Only a short hash of the login is kept, in the site's own storage that goes with the profile. */
    var ACCOUNT_KEY = 'univer.app.account.v1';
    function accountMark(login) {
        var text = String(login || '').trim().toLowerCase(), hash = 0x811c9dc5;
        for (var i = 0; i < text.length; i++) { hash ^= text.charCodeAt(i); hash = Math.imul(hash, 0x01000193) >>> 0; }
        return text ? 'a' + hash.toString(16) : '';
    }
    if (window === window.top && /^\/user\/login\/?$/i.test(location.pathname)) ['click', 'keydown', 'submit'].forEach(function (type) {
        document.addEventListener(type, function (event) {
            var form = event.target && event.target.closest ? event.target.closest('form#login_frm') : null;
            if (!form || (type === 'keydown' && event.key !== 'Enter')) return;
            if (type === 'click' && !event.target.closest('button,input[type=submit],input[type=button],input[type=image],a')) return;
            var field = Array.from(form.querySelectorAll('input')).find(function (input) {
                var kind = (input.getAttribute('type') || 'text').toLowerCase();
                return (kind === 'text' || kind === 'email') && input.value.trim();
            });
            var mark = field ? accountMark(field.value) : '';
            if (mark) { try { localStorage.setItem(ACCOUNT_KEY, mark); } catch (_) { } }
        }, true);
    });
    var LOGIN_PATH = /^\/user\/login(?:moodle)?\/?$/i, NOTICE_PATH = /^\/student\/attentionStudents\/\d+\/?$/i;
    // The site's own AJAX endpoints whose result settles a pending reauthentication or agreement.
    var AJAX_PATHS = {reauth: '/user/ajl', agreement: '/user/setagree'};
    // At most this many documents (the page and its same-origin frames) are searched; a form absent this long ends
    // its episode; an interactive DOM counts as ready this long after it became interactive.
    var MAX_DOCUMENTS = 8, ABSENCE_MS = 1_500, INTERACTIVE_GRACE_MS = 4000;

    /* Page helpers. */
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
    function hash(text) {
        var result = 2166136261;
        for (var i = 0; i < text.length; i++) { result ^= text.charCodeAt(i); result = Math.imul(result, 16777619); }
        return (result >>> 0).toString(16);
    }
    /** A digest of [form]'s text: a changed notice or agreement gets a new key. */
    function fingerprint(form) { return hash(normalize(form.textContent)); }
    // Whether [node] holds any non-blank text. The first word answers: textContent would copy the whole page (often the
    // site's one wrapper table) into a string on every polling tick.
    function hasText(node) {
        var walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
        for (var text = walker.nextNode(); text; text = walker.nextNode()) if (/\S/.test(text.data)) return true;
        return false;
    }
    /** Puts the saved login into [input] through the built-ins taken at document start, as typing would leave it. */
    function setLogin(input, value) {
        call(setInputValue, input, [value]);
        call(setAttribute, input, ['value', value]); // Legacy jQuery reads attr('value'). Readonly is preserved.
        call(dispatch, input, [new BaseEvent('input', {bubbles: true})]);
        call(dispatch, input, [new BaseEvent('change', {bubbles: true})]);
    }
    /**
     * Sends [target]'s form with the saved password in it for as short a time as the site's own handler needs: the
     * password goes only into the field's value, through the setter taken at document start (never into its value
     * attribute, which the page's markup, serializers and observers read, and with no input or change event for page
     * listeners to catch), the site's button runs its submit handler (jQuery 1.2's attr('value') and serialize() read the
     * value as it is then, and the browser builds a posted form's data at that moment), and the field is emptied again
     * right after, whatever the handler did.
     */
    function sendWithPassword(target, password) {
        try {
            call(setInputValue, target.password, [password]);
            call(press, target.button, []);
        } finally {
            call(setInputValue, target.password, ['']);
        }
    }
    function siteOrigin(href) { return call(urlOrigin, new SiteURL(href), []); }
    /**
     * Whether [target] is the real form a credential may go into, checked with the built-ins taken at document start:
     * the first [selector] of its own document (this page or a same-origin frame), connected and posting to the site,
     * its login a text field and its password a password field inside it, both enabled, and its button inside it too.
     * A page script that swapped querySelector, closest or the like cannot pass off a form of its own.
     */
    function genuine(target, selector) {
        try {
            var form = target.form, doc = call(ownerOf, form, []);
            if (!call(connected, form, []) || call(findInDocument, doc, [selector]) !== form) return false;
            if (siteOrigin(call(documentUrl, doc, [])) !== origin || siteOrigin(call(formAction, form, [])) !== origin) return false;
            return call(holds, form, [target.login]) && call(holds, form, [target.password]) && call(holds, form, [target.button]) &&
                call(inputType, target.login, []) === 'text' && call(inputType, target.password, []) === 'password' &&
                !call(inputDisabled, target.login, []) && !call(inputDisabled, target.password, []);
        } catch (_) { return false; }
    }

    /* The page and its same-origin frames, each with the site's AJAX results that settle a pending submission. */
    function documents() {
        var result = [document];
        for (var i = 0; i < result.length && result.length < MAX_DOCUMENTS; i++) {
            for (var frame of result[i].querySelectorAll('iframe,frame')) {
                try {
                    var doc = frame.contentDocument;
                    if (visible(frame) && doc && doc.defaultView.location.origin === origin &&
                        doc.readyState === 'complete' && !result.includes(doc)) result.push(doc);
                } catch (_) { /* Cross-origin frames remain untouched. */ }
                if (result.length === MAX_DOCUMENTS) break;
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

    /* The forms automation may fill, each only while complete, visible and posting to the trusted origin. */
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
    /**
     * The read notice's checkbox and submit button in [form]: the observed #check / #btn, otherwise a checkbox labelled
     * with the notice sentence and a submit button by its caption. Null while either is hidden or the checkbox disabled.
     */
    function noticeControls(form) {
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
        return null;
    }
    function noticeForm(docs) {
        for (var doc of docs) for (var form of doc.querySelectorAll('form#attentionSt, form[action*="/student/attentionStudents/"]')) {
            var action = siteUrl(form.action, form.ownerDocument.URL);
            if (!action || !NOTICE_PATH.test(action.pathname)) continue;
            var target = noticeControls(form);
            if (target) return target;
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

    /* An episode is one appearance of a form: it is checked and submitted at most once. */
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
            if (Date.now() - e.absent >= ABSENCE_MS) e.active = false;
        }
        return e;
    }

    /* The page state the native poller reads. */
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
    /* A hanging third-party image or counter must not block the login form for ever: an interactive DOM is
     * accepted after a short grace period, once the site's jQuery ready handlers have run. */
    function documentReady() {
        if (document.readyState === 'complete') return true;
        if (document.readyState !== 'interactive') return false;
        if (!interactiveSince) interactiveSince = Date.now();
        return Date.now() - interactiveSince >= INTERACTIVE_GRACE_MS;
    }
    // Whether the body shows any text. innerText of the entire grade/material catalogue forces layout every polling tick.
    function hasContent() {
        return !!document.body && Array.from(document.body.children).some(function (node) {
            return !/^(SCRIPT|STYLE|NOSCRIPT)$/.test(node.tagName) && visible(node) && hasText(node);
        });
    }
    function probe() {
        if (location.origin !== origin) return {trusted: false};
        var docs = documents(), login = loginForm(), notice = noticeForm(docs), agreement = agreementForm(docs), reauth = reauthForm(docs);
        var n = episode('notice', notice), a = episode('agreement', agreement), r = episode('reauth', reauth);
        // The site counts a rejected attempt in #auth_try: the pending submission has failed.
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
            noticeKey: notice ? new URL(notice.form.action).pathname + ':' + fingerprint(notice.form) : '',
            noticeEpisode: notice ? n.id : '',
            agreement: ready && !!agreement, agreementChecked: !!agreement && agreement.check.checked,
            agreementEnabled: !!agreement && !agreement.button.disabled,
            agreementKey: agreement ? 'additional:' + fingerprint(agreement.form) : '',
            agreementEpisode: agreement ? a.id : '', agreementOutcome: ajax.agreement.state,
            agreementResultEpisode: ajax.agreement.challenge,
            reauth: ready && !!reauth, reauthReady: ready && !!reauth && reauth.ready,
            reauthAttempt: reauth ? reauth.attempt : 0, reauthChallenge: reauth ? r.id : '',
            reauthOutcome: ajax.reauth.state, reauthResultChallenge: ajax.reauth.challenge,
            language: currentLanguage(), hasContent: hasContent()};
    }

    /* Actions. Each first checks that it still runs in the document [expected] names. */
    function valid(expected) { return location.origin === origin && token === expected; }
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
    function checkConfirmation(kind, expected, appearance) {
        var found = valid(expected) && confirmation(kind, appearance);
        if (!found || found.episode.checked) return 'wait';
        var target = found.target; found.episode.checked = true;
        if (!target.check.checked) target.check.click(); // Runs calc() / the agreement's change handler.
        target.check.dispatchEvent(new target.form.ownerDocument.defaultView.Event('change', {bubbles: true}));
        return target.check.checked ? 'checked' : 'wait';
    }
    function prepareNotice(expected, appearance) {
        if (!valid(expected)) return 'wait';
        checkConfirmation('notice', expected, appearance);
        return submittable(confirmation('notice', appearance)) ? 'ready' : 'wait';
    }
    function submitConfirmation(kind, expected, appearance) {
        var found = valid(expected) && confirmation(kind, appearance);
        if (!submittable(found)) return 'wait';
        found.episode.clicked = true;
        if (kind === 'agreement') ajax.agreement = {state: 'pending', challenge: found.episode.id, doc: found.target.form.ownerDocument};
        found.target.button.click();
        return 'submitted';
    }
    // Only the sealed copy enters the saved account: it alone ran before the site's scripts.
    function submitLogin(expected, login, password) {
        if (!sealed) return 'unprotected';
        if (!valid(expected) || loginClicked || !probe().loginReady) return 'wait';
        var target = loginForm();
        // The saved account goes only into the login page's own document, never a frame's or another page's form.
        function own() {
            return genuine(target, 'form#login_frm') && call(ownerOf, target.form, []) === startDocument &&
                call(matches, LOGIN_PAGE, [location.pathname]);
        }
        if (!target || !own()) return 'wait';
        setLogin(target.login, login);
        // The login's input and change events run page listeners: one may have turned the password field into another
        // kind of input or moved the form. Checked again right before the password goes in; nothing is armed until then.
        if (loginClicked || !own()) return 'wait';
        loginClicked = true;
        sendWithPassword(target, password); // Dynamic encryption/key handler remains intact.
        return 'submitted';
    }
    function reauthenticate(expected, challenge, login, password) {
        if (!sealed) return 'unprotected';
        if (!valid(expected)) return 'wait';
        var target = reauthForm(documents()), e = episode('reauth', target);
        if (!target || !target.ready || target.attempt !== 1 || e.id !== challenge || e.clicked || ajax.reauth.state === 'pending') return 'wait';
        if (!genuine(target, '#auth_modal form#modal_auth_frm')) return 'wait';
        // Do not replace the account bound by the site's readonly reauthentication field.
        // An empty readonly field names no account yet and receives the saved login.
        var bound = target.login.readOnly ? target.login.value.trim() : '';
        if (bound && bound.toLowerCase() !== String(login).trim().toLowerCase()) return 'account-mismatch';
        if (!bound) setLogin(target.login, login);
        // As for the login page: the login's events may have changed the form, so it is checked again before the
        // password goes in, and the episode counts as submitted only then.
        if (e.clicked || ajax.reauth.state === 'pending' || !genuine(target, '#auth_modal form#modal_auth_frm')) return 'wait';
        e.clicked = true; ajax.reauth = {state: 'pending', challenge: e.id, doc: target.form.ownerDocument};
        sendWithPassword(target, password); // Official ajl() serializes nonce/cheat and performs AJAX.
        return 'submitted';
    }
    function switchLanguage(code) {
        if (location.origin !== origin || !/^(ru|kz|en)$/.test(code)) return 'missing';
        var link = Array.from(document.querySelectorAll('a[href]')).find(function (a) {
            var url = siteUrl(a.href); return !!url && url.pathname === '/lang/change/' + code + '/';
        });
        if (!link) return 'missing';
        link.click(); return 'clicked';
    }

    var api = Object.freeze({
        // The controller compares it before it sends the saved account. Reading it gives a site script nothing: every
        // document of the WebView that knows it gets this object before any site script runs.
        seal: sealed ? SEAL : '',
        probe: probe,
        login: submitLogin,
        reauthenticate: reauthenticate,
        checkNotice: function (expected, appearance) { return checkConfirmation('notice', expected, appearance); },
        prepareNotice: prepareNotice,
        submitNotice: function (expected, appearance) { return submitConfirmation('notice', expected, appearance); },
        checkAgreement: function (expected, appearance) { return checkConfirmation('agreement', expected, appearance); },
        submitAgreement: function (expected, appearance) { return submitConfirmation('agreement', expected, appearance); },
        switchLanguage: switchLanguage
    });
    // Neither writable nor configurable: no later script replaces it or hides it behind a getter.
    Object.defineProperty(window, '__univerAutomationV1', {value: api, enumerable: false, configurable: false, writable: false});
})();
