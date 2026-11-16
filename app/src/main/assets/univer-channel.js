/* Document-start key of the app's message channels (ChannelGuard.kt replaces the secret). It runs before any site
   script in the main frame: Reflect.apply and each channel's postMessage are taken here, so a site script that later
   replaces them neither sees nor stops the app's messages. __univerClaim cannot be redefined and hands the senders,
   which put the secret in front of each message, only to a caller that knows the secret: the app's modules get it
   inside their injected source, the site never does. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window !== window.top) return;
    var secret = '__UNIVER_CHANNEL_SECRET__', apply = Reflect.apply, freeze = Object.freeze;
    function sender(channel) {
        var post = channel && channel.postMessage;
        if (typeof post !== 'function') return null;
        return function (value) { if (typeof value === 'string') apply(post, channel, [secret + value]); };
    }
    var senders = freeze({app: sender(window.__univerAppEvents), notice: sender(window.__univerNoticeEvents)});
    try {
        Object.defineProperty(window, '__univerClaim', {value: freeze(function (key) { return key === secret ? senders : null; })});
    } catch (_) { /* Nothing defined it before: this runs ahead of every site script of the document. */ }
})();
