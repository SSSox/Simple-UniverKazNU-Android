/* Moodle pages wider than the phone (breadcrumbs, the submission table, long links) made every page scroll sideways
 * (a 1197 px layout on a 427 px screen). Styles only: Moodle's DOM, values and handlers are untouched. */
(function () {
    'use strict';
    if (!/^(dl\.kaznu\.kz|dl\.kaznu\.edu\.kz)$/i.test(location.hostname) || location.protocol !== 'https:') return;
    if (document.getElementById('univer-moodle-fit')) return;
    var style = document.createElement('style');
    style.id = 'univer-moodle-fit';
    style.textContent =
        '.breadcrumb,.breadcrumb-item{white-space:normal!important;flex-wrap:wrap!important;max-width:100%!important}' +
        // Wide tables scroll inside themselves instead of widening the page.
        '#page table:not(.calendarmonth){display:block;max-width:100%;overflow-x:auto;-webkit-overflow-scrolling:touch}' +
        '#page :is(a,p,td,th,li,h1,h2,h3,h4,h5,h6,span,div.no-overflow){overflow-wrap:anywhere}' +
        '#page :is(img,video,iframe,embed,object){max-width:100%!important}#page img,#page video{height:auto}' +
        // The file picker dialog stays within the screen.
        '.moodle-dialogue-base .moodle-dialogue{max-width:100vw!important}';
    (document.head || document.documentElement).appendChild(style);
})();
