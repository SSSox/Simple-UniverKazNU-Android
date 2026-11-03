/* The phone layout of the reversible DOM adapter (univer-mobile.js): the stylesheet it adds and removes,
   kept apart from the adapter's logic. Loaded right before univer-mobile.js. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz') return;
    window.__univerMobileCssV1 = `
html.univer-mobile { -webkit-text-size-adjust:100%; font-size:16px; }
html.univer-mobile body { margin:0!important; padding:0!important; font-family:Roboto,system-ui,sans-serif; color:#16283b; font-size:16px!important; line-height:1.45; overflow-wrap:anywhere; }
html.univer-mobile body, html.univer-mobile td, html.univer-mobile th,
html.univer-mobile p, html.univer-mobile li, html.univer-mobile label { font-size:16px!important; }
html.univer-mobile .univer-app-shell { width:100%!important; max-width:100%!important; table-layout:fixed!important; height:auto!important; }
html.univer-mobile .univer-app-shell>tbody>tr>td { min-width:0; }
html.univer-mobile #l1 .header { font-size:12px!important; line-height:1.5; height:auto; padding:8px 12px!important; white-space:normal!important; }
html.univer-mobile #l1 .header a { display:inline-block; padding:6px 4px; }
html.univer-mobile #l1 .header .bvi-open { display:block!important; margin:0!important; width:auto!important; float:none!important; text-align:start; overflow-wrap:normal; padding:6px 0; }
html.univer-mobile #l1 img { min-width:24px; height:auto; vertical-align:middle; }
html.univer-mobile #l2, html.univer-mobile #l3, html.univer-mobile #l4, html.univer-mobile #l5 { height:auto!important; }
html.univer-mobile .univer-app-shell table.inner,
html.univer-mobile #tools, html.univer-mobile table.mt { width:100%!important; max-width:100%!important; table-layout:fixed; height:auto!important; }
html.univer-mobile .univer-app-shell table.inner>tbody>tr>td.left,
html.univer-mobile .univer-app-shell table.inner>tbody>tr>td.right,
html.univer-mobile #tools>tbody>tr>td.left, html.univer-mobile #tools>tbody>tr>td.right,
html.univer-mobile table.mt>tbody>tr>td.lt, html.univer-mobile table.mt>tbody>tr>td.rt { display:none!important; }
html.univer-mobile #l2 td.center { padding:10px 12px!important; }
html.univer-mobile #l2 #logo { float:none!important; max-width:100%; }
html.univer-mobile #l2 .links { float:none!important; min-width:0!important; padding:8px 0 0!important; overflow-wrap:anywhere; font-size:14px!important; }
html.univer-mobile #l2 .links a { display:inline-block; min-height:32px; padding:4px 8px 4px 0; }
html.univer-mobile .univer-app-shell .links { min-width:0!important; max-width:100%!important; float:none!important; white-space:normal!important; overflow-wrap:anywhere; }
html.univer-mobile .univer-app-shell .links a { float:none!important; display:inline-block; white-space:normal!important; font-size:15px!important; padding:6px 4px; }
html.univer-mobile #l3 td.center { padding:4px 8px!important; white-space:normal!important; }
html.univer-mobile #l3 a { display:inline-block!important; float:none!important; font-size:15px!important; line-height:1.4; min-height:40px; box-sizing:border-box; padding:10px 8px!important; }
html.univer-mobile #l4 td.center { padding:8px!important; }
html.univer-mobile table.mt>tbody>tr>td.ct,
html.univer-mobile table.mt>tbody>tr>td.tt { padding:8px!important; white-space:normal; overflow-wrap:anywhere; }
html.univer-mobile table.mt>tbody>tr.top>td.ct { font-weight:600; }
html.univer-mobile .univer-app-shell table.mt :is(p,li) { text-align:start!important; word-spacing:normal!important; }
html.univer-mobile .univer-app-shell table.mt :is(ol,ul) { padding-inline-start:24px!important; margin-inline:4px!important; }
html.univer-mobile #tools .tool { float:none!important; width:100%!important; height:auto!important; margin:0!important; background:white!important; border:0!important; }
html.univer-mobile #tools .top-center { height:auto!important; overflow:visible!important; padding:0!important; background:white!important; }
html.univer-mobile #tools .tool>tbody>tr { height:auto!important; }
html.univer-mobile #tools .tool>tbody>tr>td { height:auto!important; }
html.univer-mobile #tools :is(.top-left,.top-right,.bottom-left,.bottom-right,.middle-left,.middle-right) { display:none!important; }
html.univer-mobile #tools ul { padding:0!important; margin:0!important; list-style:none!important; }
html.univer-mobile #tools li { margin:0!important; border-bottom:1px solid #e2eaf3; background:none!important; list-style:none!important; }
html.univer-mobile #tools li:last-child { border-bottom:0; }
html.univer-mobile #tools li, html.univer-mobile #tools li a { line-height:1.5!important; font-size:16px!important; }
html.univer-mobile #tools li a { display:block; padding:12px!important; min-height:24px; color:#164e80; text-decoration:none; }
html.univer-mobile .univer-app-category { margin:8px; background:#fff; border:1px solid #d7e3ef; border-radius:8px; }
html.univer-mobile .univer-app-category>summary { padding:12px; min-height:24px; font-size:16px; font-weight:600; color:#184f86; cursor:pointer; }
html.univer-mobile .univer-app-category[open]>summary { border-bottom:1px solid #d7e3ef; }
html.univer-mobile #tools .univer-app-menu-footer { display:none!important; }
html.univer-mobile .univer-app-menu [hidden] { display:none!important; }
html.univer-mobile .univer-app-menu input[type=search] { display:block; box-sizing:border-box; width:calc(100% - 16px); margin:8px; padding:12px; border:1px solid #bdcfe3; border-radius:8px; min-height:48px; font:inherit; background:white; }
html.univer-mobile .univer-app-menu .univer-app-empty { margin:12px; color:#46596b; }
html.univer-mobile .univer-app-menu, html.univer-mobile .univer-app-info { margin:4px 8px 10px; background:#eef4fb; border:1px solid #c7d9ed; border-radius:10px; }
html.univer-mobile .univer-app-menu>summary, html.univer-mobile .univer-app-info>summary { cursor:pointer; min-height:48px; box-sizing:border-box; padding:12px; font-size:16px; font-weight:600; color:#184f86; }
html.univer-mobile .univer-app-menu[open]>summary, html.univer-mobile .univer-app-info[open]>summary { border-bottom:1px solid #c7d9ed; }
html.univer-mobile #login_frm table.cols,
html.univer-mobile #login_frm table.cols>tbody,
html.univer-mobile #login_frm table.cols>tbody>tr,
html.univer-mobile #login_frm table.cols>tbody>tr>td { display:block; box-sizing:border-box; width:100%!important; }
html.univer-mobile #login_frm table.cols>tbody>tr>td { padding:12px 4px; }
html.univer-mobile #login_form { max-width:480px; margin:0 auto; }
html.univer-mobile #login_form input.text { box-sizing:border-box; width:100%!important; min-height:48px; font-size:16px!important; }
html.univer-mobile input[type=submit], html.univer-mobile input[type=button], html.univer-mobile button,
html.univer-mobile select { font-size:16px!important; min-height:48px; max-width:100%; }
html.univer-mobile input[type=text], html.univer-mobile input[type=password], html.univer-mobile textarea { font-size:16px!important; max-width:100%; box-sizing:border-box; }
html.univer-mobile img { max-width:100%; height:auto; }
html.univer-mobile #attentionSt .parent { position:static!important; width:auto!important; height:auto!important; overflow:visible!important; }
html.univer-mobile:has(#attentionSt), html.univer-mobile body:has(#attentionSt) { height:auto!important; overflow:auto!important; }
html.univer-mobile #attentionSt .block { position:static!important; top:auto!important; left:auto!important; width:auto!important; height:auto!important; margin:0!important; padding:20px 16px 32px!important; }
html.univer-mobile #attentionSt #check { width:24px; height:24px; vertical-align:middle; }
html.univer-mobile #attentionSt #btn { float:none!important; width:100%; margin-top:20px; }
html.univer-mobile #copyright { width:100%!important; font-size:12px!important; }
html.univer-mobile #copyright td { font-size:12px!important; overflow-wrap:anywhere; }
html.univer-mobile .univer-app-table-scroll { width:100%; max-width:100%; overflow:auto; -webkit-overflow-scrolling:touch; overscroll-behavior-x:contain; margin:8px 0; }
html.univer-mobile .univer-app-table-scroll>table { max-width:none!important; }
html.univer-mobile .univer-app-table-scroll table:not(.inner):not(.mt) { table-layout:auto!important; }
html.univer-mobile .univer-app-table-scroll table:not(.inner):not(.mt)>:is(thead,tbody,tfoot)>tr>:is(td,th) { min-width:7rem; }
html.univer-mobile table.univer-app-data, html.univer-mobile .univer-app-shell table.inner.univer-app-data { table-layout:auto!important; width:100%!important; max-width:none!important; }
html.univer-mobile .univer-app-data-field { min-width:7rem; }
html.univer-mobile .jexcel_container, html.univer-mobile .jss_container { max-width:100%; overflow:auto; }
html.univer-mobile #list { padding:0!important; margin:0!important; }
html.univer-mobile #list .inner { max-width:100%; overflow-wrap:anywhere; }
html.univer-mobile #list img { display:block; width:auto!important; max-width:100%!important; }
html.univer-mobile #list>li { box-sizing:border-box; width:100%!important; margin:0!important; padding:12px!important; }
html.univer-mobile #list :is(.left,.right) { float:none!important; max-width:100%!important; }
html.univer-mobile .univer-app-shell table.univer-app-news,
html.univer-mobile table.univer-app-news>tbody { display:block; width:100%!important; }
html.univer-mobile .univer-app-news>tbody>tr:has(>th) { display:none; }
html.univer-mobile .univer-app-news .news { display:block; margin:0 0 8px; padding:12px; background:white; border:1px solid #d7e3ef; border-radius:8px; }
html.univer-mobile .univer-app-news .news>td { display:block; width:auto!important; padding:0!important; border:0!important; }
html.univer-mobile .univer-app-news .news>td:first-child { display:flex; gap:8px; align-items:center; margin-bottom:12px; }
html.univer-mobile .univer-app-news .news>td:first-child>div { width:auto!important; padding:4px 8px; font-size:13px!important; margin:0!important; white-space:nowrap; }
html.univer-mobile .univer-app-news .news>td:first-child .date { display:flex; align-items:baseline; gap:6px; }
html.univer-mobile .univer-app-news .date br { display:none; }
html.univer-mobile .univer-app-news .news>td:nth-child(2) :is(p,div,span) { text-indent:0!important; max-width:100%!important; box-sizing:border-box; font-size:16px!important; line-height:1.55!important; }
html.univer-mobile .univer-app-news .news>td:nth-child(2) p { margin:6px 0!important; }
html.univer-mobile .univer-app-news .news img { display:block; max-width:100%!important; width:auto!important; margin:8px auto; }
html.univer-mobile #simplemodal-overlay { position:fixed!important; inset:0!important; width:100%!important; height:100%!important; }
html.univer-mobile #simplemodal-container { position:fixed!important; box-sizing:border-box!important; left:50%!important; top:var(--univer-dialog-top,12px)!important; transform:translateX(-50%)!important; width:calc(100% - 24px)!important; max-width:560px!important; height:auto!important; max-height:var(--univer-dialog-height,calc(100dvh - 24px))!important; overflow:auto!important; padding:64px 12px 12px!important; border:1px solid #c7d9ed!important; border-radius:12px; box-shadow:0 8px 32px #193a5b55; background:white!important; color:#172b40; }
html.univer-mobile #simplemodal-container .simplemodal-wrap { height:auto!important; width:100%!important; max-width:100%; overflow:visible!important; }
html.univer-mobile #simplemodal-container .simplemodal-data { box-sizing:border-box!important; max-width:100%!important; }
html.univer-mobile .univer-app-modal-toolbar { position:fixed; top:var(--univer-dialog-top,12px); left:50%; transform:translateX(-50%); box-sizing:border-box; width:calc(100% - 24px); max-width:560px; height:60px; padding:6px 8px; display:flex; justify-content:flex-end; background:white; border-radius:12px 12px 0 0; border:1px solid #c7d9ed; border-bottom:0; }
html.univer-mobile .univer-app-modal-toolbar[hidden] { display:none!important; }
html.univer-mobile .univer-app-modal-toolbar .simplemodal-close { position:static!important; display:block!important; float:none!important; width:48px!important; height:48px!important; margin:0!important; background:white!important; border-radius:8px; color:#184f86; text-decoration:none; }
html.univer-mobile .univer-app-modal-toolbar .simplemodal-close::after { content:'×'; display:block; text-align:center; font:28px/48px sans-serif; }
html.univer-mobile #auth_modal { width:100%!important; padding:0!important; }
html.univer-mobile #modal_auth_frm>table { width:100%!important; table-layout:fixed; }
html.univer-mobile #modal_auth_frm>table>tbody>tr>td:first-child,
html.univer-mobile #modal_auth_frm>table>tbody>tr>td:last-child { display:none!important; }
html.univer-mobile #modal_auth_frm>table>tbody>tr>td:nth-child(2) { width:auto!important; height:auto!important; padding:8px!important; }
html.univer-mobile #modal_auth_frm label { display:block; margin:10px 0 4px; }
html.univer-mobile #modal_auth_frm input:not([type=hidden]) { display:block; width:100%!important; min-height:48px!important; font-size:16px!important; box-sizing:border-box; margin:4px 0!important; }
html.univer-mobile #modal_auth_frm input[type=text], html.univer-mobile #modal_auth_frm input[type=password] { border:1px solid #a9bbce; border-radius:6px; padding:10px; }
html.univer-mobile #modal_auth_login { background:#f4f7fa; color:#405468; text-align:start!important; }
html.univer-mobile #modal_auth_ajl_btn, html.univer-mobile #modal_agreement .btn-confirm,
html.univer-mobile #attentionSt #btn { background:#246298; color:white; border:0; border-radius:8px; padding:10px 12px; font-weight:600; }
html.univer-mobile #modal_auth_ajl_btn:disabled, html.univer-mobile #modal_agreement .btn-confirm:disabled,
html.univer-mobile #attentionSt #btn:disabled { opacity:0.5; }
html.univer-mobile #modal_agreement button:not(.btn-confirm) { border:1px solid #a9bbce; border-radius:8px; color:#184f86; background:#f4f7fa; padding:10px 12px; }
html.univer-mobile #modal_agreement { width:100%!important; max-width:100%!important; box-sizing:border-box!important; padding:8px!important; font-size:16px!important; }
html.univer-mobile #modal_agreement :is(p,span,label) { font-size:16px!important; line-height:1.55!important; white-space:normal!important; }
html.univer-mobile #agree_frm p { margin:12px 0!important; text-indent:0!important; text-align:start!important; }
html.univer-mobile #agree_frm :is(table,td) { max-width:100%; }
html.univer-mobile #modal_agreement #iconfirm { width:24px; height:24px; vertical-align:middle; margin:12px; }
html.univer-mobile #modal_agreement center { text-align:start; }
html.univer-mobile #modal_agreement center>div { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }
html.univer-mobile #modal_agreement center>div>div { float:none!important; width:auto!important; flex:1; min-width:120px; }
html.univer-mobile #modal_agreement button { width:100%; min-height:48px; white-space:normal; }
html.univer-mobile #simplemodal-container iframe { display:block; box-sizing:border-box; max-width:100%!important; width:100%!important; height:var(--univer-frame-height,65dvh)!important; border:0; }
/* Compact header: original links and account nodes stay interactive. */
html.univer-mobile body { font-family:system-ui,-apple-system,Arial,sans-serif!important; color:#20374d; background:#eef3f8!important; }
html.univer-mobile #l1 .header { background:#164e80!important; color:white!important; padding:0 12px!important; text-align:start!important; }
html.univer-mobile .univer-app-header { display:flex; align-items:center; justify-content:space-between; gap:8px; }
html.univer-mobile .univer-app-university { flex:1; min-width:0; }
html.univer-mobile .univer-app-university>summary { cursor:pointer; font-size:12px; padding:12px 0; min-height:20px; }
html.univer-mobile .univer-app-university>div { padding:0 0 12px; font-size:12px; }
html.univer-mobile #l1 .univer-app-flags { display:flex; align-items:center; gap:0; flex-shrink:0; }
html.univer-mobile #l1 .univer-app-flags>a, html.univer-mobile #l1 .univer-app-flags>img { box-sizing:border-box; width:48px; min-height:48px; padding:12px 10px; display:inline-flex; align-items:center; justify-content:center; }
html.univer-mobile #l1 .univer-app-flags>a img { width:24px; }
html.univer-mobile #l1 .univer-app-flags>img { object-fit:contain; height:44px; }
html.univer-mobile #l1 .univer-app-flags>img { border:1px solid #ffffff70; border-radius:8px; }
html.univer-mobile #l2 td.center { background:white!important; display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:8px; padding:8px 12px!important; }
html.univer-mobile #l2 #logo { width:140px!important; height:44px!important; background-size:contain!important; background-position:left center!important; background-repeat:no-repeat!important; margin:0!important; }
html.univer-mobile #l2 .links { width:auto!important; padding:0!important; text-align:start!important; }
html.univer-mobile #l2 .univer-app-account>summary { padding:10px 12px; border:1px solid #d7e3ef; border-radius:8px; cursor:pointer; font-size:14px; min-height:24px; color:#164e80; }
html.univer-mobile #l2 .links:has(.univer-app-account[open]) { width:100%!important; }
html.univer-mobile #l2 .univer-app-account[open]>div { padding:10px 0; }
html.univer-mobile #l2 .univer-app-account a { font-size:14px!important; line-height:1.5; min-height:32px; }
html.univer-mobile #l3 td.center { background:#fff!important; display:flex; flex-wrap:wrap; gap:6px; padding:6px 8px 10px!important; }
html.univer-mobile #l3 td.center>a { flex:1 0 auto; min-width:0; display:flex!important; align-items:center; justify-content:center; white-space:nowrap!important; overflow-wrap:normal; border-radius:8px; border:1px solid #d7e3ef; background:#f4f7fb!important; min-height:48px; padding:8px 4px!important; font-size:14px!important; text-decoration:none; color:#164e80; }
html.univer-mobile #l3 td.center>a.active { background:#164e80!important; border-color:#164e80; color:#fff!important; }
html.univer-mobile #l3 a>.left, html.univer-mobile #l3 a>.right { display:none!important; }
html.univer-mobile #l3 a>.center { height:auto!important; background:none!important; padding:0!important; float:none!important; color:inherit!important; font:inherit!important; }
/* Week controls and a day-by-day representation of the actual schedule grid. */
html.univer-mobile table.mt.univer-app-study-panel,
html.univer-mobile table.mt.univer-app-study-panel>tbody,
html.univer-mobile table.mt.univer-app-study-panel>tbody>tr { display:block; box-sizing:border-box; width:100%!important; background:none!important; height:auto!important; border:0!important; }
html.univer-mobile table.mt.univer-app-study-panel { margin:8px 0 16px; }
html.univer-mobile table.mt.univer-app-study-panel>tbody>tr>:is(.ct,.tt) { display:block; box-sizing:border-box; width:100%!important; height:auto!important; background:none!important; border:0!important; }
html.univer-mobile table.mt.univer-app-study-panel>tbody>tr:has(>.pd), html.univer-mobile table.mt.univer-app-study-panel>tbody>.bot,
html.univer-mobile table.mt.univer-app-study-panel>tbody>.empty, html.univer-mobile .univer-app-study-empty { display:none!important; }
html.univer-mobile .univer-app-study-panel .ct.message { font-size:14px!important; padding:4px 8px!important; color:#4b5b6c; }
html.univer-mobile .univer-app-study-panel .ct.message li { display:inline-block; font-size:14px!important; margin-inline-end:12px; }
html.univer-mobile .univer-app-page-link { display:block; box-sizing:border-box; margin:6px 0; padding:10px 12px; min-height:48px; border:1px solid #d7e3ef; border-radius:8px; background:white; color:#164e80; text-decoration:none; }
html.univer-mobile .univer-app-breadcrumb { display:flex!important; flex-wrap:wrap; gap:6px; }
html.univer-mobile .univer-app-breadcrumb>.univer-app-page-link { margin:0; font-size:14px!important; }
html.univer-mobile .univer-app-breadcrumb>.univer-app-page-link:last-of-type { flex-basis:100%; font-size:18px!important; font-weight:600; }
html.univer-mobile #weekSelect { display:block; box-sizing:border-box; width:100%!important; border:1px solid #bdcfe3; border-radius:8px; padding:10px 8px; background:white; color:#20374d; }
html.univer-mobile table.univer-app-schedule-source { display:none!important; }
html.univer-mobile .univer-app-schedule { min-width:0; width:100%; }
html.univer-mobile .univer-app-days { display:flex; gap:4px; overflow-x:auto; overscroll-behavior-x:contain; margin:8px 0; padding:2px; }
html.univer-mobile .univer-app-days button { flex:0 0 auto; white-space:nowrap; min-width:48px; border:1px solid #bdcfe3; border-radius:8px; padding:8px; font:inherit; background:white; color:#164e80; }
html.univer-mobile .univer-app-days button[aria-pressed=true] { background:#164e80; border-color:#164e80; color:white; }
html.univer-mobile .univer-app-day h3 { font-size:18px; margin:12px 0; color:#164e80; }
html.univer-mobile .univer-app-day[hidden] { display:none!important; }
html.univer-mobile .univer-app-lesson { background:white; border:1px solid #d7e3ef; border-left:4px solid #246298; border-radius:10px; padding:10px 12px; margin:0 0 8px; }
html.univer-mobile .univer-app-lesson time { display:inline-block; padding:4px 8px; border-radius:6px; background:#eef4fb; color:#164e80; font-size:14px; font-weight:700; }
html.univer-mobile .univer-app-lesson p { margin:4px 0 0; line-height:1.4; }
html.univer-mobile .univer-app-lesson .univer-app-subject { font-weight:700; }
html.univer-mobile .univer-app-no-classes { margin:8px 0; color:#4b5b6c; }
/* UMKD cards use the original rows, download anchors and rating handlers. */
html.univer-mobile .univer-app-shell table.inner.univer-app-materials,
html.univer-mobile .univer-app-shell table.inner.univer-app-courses { display:block; width:100%!important; min-width:0!important; table-layout:fixed!important; }
html.univer-mobile :is(.univer-app-materials,.univer-app-courses)>tbody { display:block; width:100%; }
html.univer-mobile :is(.univer-app-materials,.univer-app-courses)>tbody>tr:has(>th:nth-child(2)) { display:none!important; }
html.univer-mobile .univer-app-materials>tbody>tr:has(>th:only-child) { display:block; }
html.univer-mobile .univer-app-materials>tbody>tr>th:only-child { display:block; padding:16px 4px 8px!important; background:none!important; color:#164e80!important; text-align:start!important; line-height:1.5; border:0!important; }
html.univer-mobile .univer-app-materials tr.file, html.univer-mobile .univer-app-courses tr.link { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:4px 12px; background:white!important; border:1px solid #d7e3ef; border-radius:8px; margin:8px 0; padding:10px 12px; }
html.univer-mobile :is(.univer-app-materials,.univer-app-courses)>tbody>tr>td { display:block; width:auto!important; min-width:0!important; border:0!important; padding:0!important; text-align:start!important; line-height:1.5; background:none!important; }
html.univer-mobile :is(.univer-app-materials tr.file,.univer-app-courses tr.link)>td:first-child { display:none!important; }
html.univer-mobile :is(.univer-app-materials tr.file,.univer-app-courses tr.link)>td:nth-child(2) { grid-column:1/-1; font-weight:700; }
html.univer-mobile .univer-app-materials tr.file>td:nth-child(3), html.univer-mobile .univer-app-materials tr.file>td:last-child { grid-column:1/-1; }
html.univer-mobile .univer-app-materials td[data-univer-field]::before { content:attr(data-univer-field); display:block; font-size:12px; font-weight:500; color:#4b5b6c; }
html.univer-mobile .univer-app-materials tr.file>td:nth-child(3):empty { display:none; }
html.univer-mobile .univer-app-materials a.downLoad { display:block; min-height:48px; padding:10px 12px; border-radius:8px; background:#eef4fb; text-decoration:none; color:#164e80; overflow-wrap:anywhere; }
html.univer-mobile .univer-app-materials a.downLoad::after { content:attr(data-univer-action); display:block; font-size:14px; font-weight:600; margin-top:4px; }
html.univer-mobile .univer-app-materials .stars { display:flex; flex-wrap:wrap; gap:4px; height:auto!important; }
html.univer-mobile .univer-app-materials svg.star { box-sizing:content-box; width:23px!important; height:25px!important; padding:13px; flex-shrink:0; }
html.univer-mobile .univer-app-courses tr.link>td:last-child { grid-column:1/-1; font-size:14px!important; color:#4b5b6c; }
html.univer-mobile .univer-app-course-open { display:block; margin-top:12px; width:100%; padding:10px 12px; border:0; border-radius:8px; background:#246298; color:white; font:inherit; }
html.univer-mobile .univer-app-info[data-univer-info=links] :is(.links,table,tbody,tr,td) { display:block!important; box-sizing:border-box; width:100%!important; min-width:0!important; max-width:100%!important; height:auto!important; background:none!important; border:0!important; margin:0!important; padding:0!important; }
html.univer-mobile .univer-app-info[data-univer-info=links] .links a { display:flex!important; align-items:center; gap:8px; padding:10px 12px!important; min-height:24px; text-decoration:none; }
html.univer-mobile .univer-app-info[data-univer-info=links] .univer-app-table-scroll { margin:0!important; overflow:visible; }
html.univer-mobile #tools li:has(>a+a)>a { border-bottom:1px solid #e2eaf3; }
html.univer-mobile .univer-app-study-panel>tbody>tr>.ts { display:flow-root!important; box-sizing:border-box; width:100%!important; height:auto!important; padding:8px 0!important; white-space:normal!important; background:none!important; border:0!important; }
html.univer-mobile .univer-app-study-panel>tbody>tr:has(>.ts) { height:auto!important; }
/* Previous/next semester links: the site floats them inside a 25 px cell. */
html.univer-mobile #l4 .ts>a:is(.lt,.rt):not([hidden]):not(.syllabus-info) { display:flex!important; float:none!important; align-items:center; gap:8px; box-sizing:border-box; width:auto!important; height:auto!important; min-height:48px; margin:6px 0!important; padding:8px 12px!important; border:1px solid #d7e3ef; border-radius:10px; background:#fff; color:#164e80; text-decoration:none; font-size:14px!important; line-height:1.4; }
html.univer-mobile #l4 .ts>a.rt:not([hidden]):not(.syllabus-info) { justify-content:flex-end; text-align:end; }
html.univer-mobile #l4 a#discipline_btn, html.univer-mobile #l4 .ts:has(>a#discipline_btn):not(:has(>a:not(#discipline_btn))) { display:none!important; }
html.univer-mobile .univer-app-study-panel :is(td,th) { white-space:normal!important; }
html.univer-mobile .univer-app-pairs, html.univer-mobile .univer-app-form-table,
html.univer-mobile .univer-app-pairs>tbody, html.univer-mobile .univer-app-form-table>tbody { display:block!important; width:100%!important; min-width:0!important; table-layout:fixed!important; }
html.univer-mobile .univer-app-pairs>tbody>tr { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:4px 12px; padding:8px 12px; margin:8px 0; background:white; border:1px solid #d7e3ef; border-radius:8px; }
html.univer-mobile .univer-app-pairs>tbody>tr>td { display:block; width:auto!important; min-width:0!important; border:0!important; padding:4px 0!important; line-height:1.5; }
html.univer-mobile .univer-app-pairs>tbody>tr>td:nth-child(odd) { font-size:13px!important; color:#4b5b6c; }
@media(max-width:600px) { html.univer-mobile .univer-app-pairs>tbody>tr { grid-template-columns:minmax(0,1fr); }
html.univer-mobile .univer-app-pairs>tbody>tr>td:nth-child(even) { margin-bottom:8px; } }
html.univer-mobile .univer-app-form-table>tbody>tr, html.univer-mobile .univer-app-form-table>tbody>tr>:is(td,th) { display:block; width:100%!important; min-width:0!important; box-sizing:border-box; height:auto!important; border:0!important; padding:8px!important; }
html.univer-mobile .univer-app-form-table input:not([type=hidden]):not([type=checkbox]):not([type=radio]), html.univer-mobile .univer-app-form-table :is(select,textarea) { width:100%!important; box-sizing:border-box; min-height:48px; }
html.univer-mobile .univer-app-form-table input:is([type=checkbox],[type=radio]) { width:24px; height:24px; min-height:0; vertical-align:middle; margin:10px 8px 10px 0; }
html.univer-mobile .univer-app-form-table label:has(input:is([type=checkbox],[type=radio])) { display:flex; align-items:center; gap:8px; }
html.univer-mobile .univer-app-form-table :is(table,tbody,tr,td) { max-width:100%!important; min-width:0!important; }
html.univer-mobile .univer-app-form-table label { display:block; margin:8px 0; }
html.univer-mobile .univer-app-form-table img { max-width:100%; }
html.univer-mobile .univer-app-cards, html.univer-mobile .univer-app-shell table.inner.univer-app-cards,
html.univer-mobile .univer-app-cards>tbody, html.univer-mobile .univer-app-cards>thead { display:block; box-sizing:border-box; width:100%!important; min-width:0!important; }
html.univer-mobile .univer-app-card-header { display:none!important; }
html.univer-mobile .univer-app-card-header.univer-app-sort-head { display:flex!important; flex-wrap:wrap; gap:6px; margin:8px 0; }
html.univer-mobile .univer-app-sort-head>th { display:block!important; min-width:0!important; width:auto!important; padding:12px!important; border:1px solid #d7e3ef!important; border-radius:8px; font-size:12px!important; }
html.univer-mobile .univer-app-card-row { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:6px 12px; margin:8px 0; padding:10px 12px; background:white; border:1px solid #d7e3ef; border-radius:10px; }
html.univer-mobile .univer-app-card-row>:is(td,th) { display:block; width:auto!important; min-width:0!important; border:0!important; padding:0!important; white-space:normal!important; overflow-wrap:anywhere; line-height:1.5; }
html.univer-mobile .univer-app-card-row>td:first-child { grid-column:1/-1; font-weight:600; }
html.univer-mobile .univer-app-card-row>td[data-univer-field]::before { content:attr(data-univer-field); display:block; font-size:12px; font-weight:400; color:#4b5b6c; margin-bottom:4px; }
html.univer-mobile .univer-app-card-row>td:has(a,input,textarea,select), html.univer-mobile .univer-app-card-row>.univer-app-long-field { grid-column:1/-1; }
html.univer-mobile .univer-app-card-row a { display:block; min-height:24px; padding:10px 0; overflow-wrap:anywhere; }
html.univer-mobile .univer-app-card-section, html.univer-mobile .univer-app-card-section>:is(td,th) { display:block; box-sizing:border-box; width:100%!important; min-width:0!important; padding:8px!important; border:0!important; background:none!important; }
html.univer-mobile .univer-app-matrix :is(td,th) { min-width:3rem!important; padding:8px!important; overflow-wrap:normal!important; word-break:normal!important; }
html.univer-mobile .univer-app-matrix tr>:first-child { min-width:6rem!important; }
html.univer-mobile .univer-app-nav-table, html.univer-mobile .univer-app-nav-table>tbody { display:block; width:100%!important; min-width:0!important; }
html.univer-mobile .univer-app-nav-table>tbody>tr { display:flex; flex-wrap:wrap; gap:6px; padding:6px 0; }
html.univer-mobile .univer-app-nav-table>tbody>tr>td { display:block; flex:1; min-width:110px!important; width:auto!important; padding:6px!important; }
html.univer-mobile .univer-app-nav-table a { display:block; box-sizing:border-box; padding:10px; min-height:24px; border:1px solid #d7e3ef; border-radius:8px; background:white; text-decoration:none; }
html.univer-mobile .univer-app-list { max-width:100%!important; width:auto!important; box-sizing:border-box; margin:12px 0!important; padding-inline-start:24px!important; }
html.univer-mobile .univer-app-list>li { width:auto!important; max-width:100%!important; float:none!important; padding:8px 0!important; height:auto!important; }
html.univer-mobile .univer-app-list a { display:inline-block; min-height:24px; padding:10px 0; overflow-wrap:anywhere; }
/* Printed HTML: reflow layout containers, scroll only genuine matrices. */
html.univer-mobile.univer-app-document body { padding:12px!important; box-sizing:border-box; width:100%!important; max-width:100%!important; background:white!important; }
html.univer-mobile.univer-app-document :is(p,span,div,h1,h2,h3) { max-width:100%!important; box-sizing:border-box; white-space:normal!important; text-indent:0!important; line-height:1.6!important; }
html.univer-mobile.univer-app-document :is(p,span) { font-size:16px!important; }
html.univer-mobile.univer-app-document h1 { font-size:22px!important; }
html.univer-mobile .univer-app-document-layout, html.univer-mobile .univer-app-document-layout>tbody,
html.univer-mobile .univer-app-document-layout>tbody>tr, html.univer-mobile .univer-app-document-layout>tbody>tr>:is(td,th) { display:block!important; width:100%!important; min-width:0!important; max-width:100%!important; box-sizing:border-box; height:auto!important; padding:4px 0!important; border:0!important; }
html.univer-mobile.univer-app-document .univer-app-table-scroll { margin:12px 0; border:1px solid #d7e3ef; border-radius:8px; }
html.univer-mobile.univer-app-document .univer-app-table-scroll>table:not(.univer-app-document-layout):not(.univer-app-pairs) { width:auto!important; min-width:100%; table-layout:auto!important; }
html.univer-mobile.univer-app-document .noprint { font-size:14px!important; line-height:1.4!important; padding:4px 0!important; }
html.univer-mobile.univer-app-document .noprint br { display:none!important; }
html.univer-mobile.univer-app-document .noprint a { display:inline-flex; align-items:center; min-height:48px; padding:0 10px; font-size:14px!important; }
html.univer-mobile .univer-app-pairs li { margin:0!important; padding:0!important; list-style:none!important; }
html.univer-mobile.univer-app-document .univer-app-table-scroll>table>:is(thead,tbody,tfoot)>tr>:is(td,th) { min-width:4rem!important; max-width:24rem; font-size:14px!important; padding:8px!important; white-space:normal!important; overflow-wrap:normal!important; word-break:normal!important; }
html.univer-mobile .univer-app-scroll-hint { margin:12px 0 4px; font-size:13px!important; line-height:1.4; color:#526b82; }
html.univer-mobile .univer-app-scroll-hint[hidden] { display:none!important; }
/* Keep study content first; auxiliary information belongs after it. */
html.univer-mobile .univer-app-quick { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; margin:8px; }
html.univer-mobile .univer-app-quick a { display:flex; align-items:center; justify-content:center; min-height:48px; box-sizing:border-box; padding:10px; border:1px solid #c7d9ed; border-radius:10px; background:white; color:#164e80; text-decoration:none; text-align:center; font-weight:600; }
html.univer-mobile #tools a[aria-current=page] { background:#e4effb; color:#123f69; font-weight:700; border-left:3px solid #246298; }
html.univer-mobile .univer-app-shell :is(.scrool,.scrool200) { width:100%!important; max-width:100%!important; min-width:0!important; box-sizing:border-box; }
html.univer-mobile .univer-app-catalog .scrool200 { height:auto!important; max-height:none!important; overflow:visible!important; }
html.univer-mobile .univer-app-catalog, html.univer-mobile .univer-app-faq { margin:8px 0; border:1px solid #d7e3ef; border-radius:10px; background:white; }
html.univer-mobile :is(.univer-app-catalog,.univer-app-faq)>summary { cursor:pointer; padding:12px; min-height:24px; font-weight:600; color:#164e80; }
html.univer-mobile .univer-app-catalog-title { display:none!important; }
html.univer-mobile .univer-app-catalog>table.mt, html.univer-mobile .univer-app-catalog>table.mt>tbody,
html.univer-mobile .univer-app-catalog>table.mt>tbody>tr, html.univer-mobile .univer-app-catalog>table.mt>tbody>tr>td { display:block; width:100%!important; min-width:0!important; box-sizing:border-box; border:0!important; background:none!important; padding:0!important; }
html.univer-mobile .univer-app-catalog>table.mt>tbody>tr:has(>.ts) { display:none!important; }
html.univer-mobile .univer-app-faq-row { display:block!important; }
html.univer-mobile .univer-app-faq>table.inner, html.univer-mobile .univer-app-faq>table.inner>tbody,
html.univer-mobile .univer-app-faq>table.inner>tbody>tr { display:block; width:100%!important; border:0!important; background:none!important; }
html.univer-mobile .univer-app-faq>table.inner>tbody>tr>:is(td,th) { display:block; border:0!important; background:none!important; padding:8px 12px!important; line-height:1.5; text-align:start!important; }
html.univer-mobile .univer-app-faq>table.inner>tbody>tr>th { color:#4b5b6c; font-size:13px!important; }
html.univer-mobile .univer-app-catalog table.inner, html.univer-mobile .univer-app-catalog table.inner>tbody { display:block; width:100%!important; }
html.univer-mobile .univer-app-catalog tr.file { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:8px; padding:12px; margin:8px; border:1px solid #d7e3ef; border-radius:8px; }
html.univer-mobile .univer-app-catalog tr.file[hidden] { display:none!important; }
html.univer-mobile .univer-app-catalog tr.file>td { display:block; min-width:0!important; width:auto!important; border:0!important; padding:0!important; overflow-wrap:anywhere; }
html.univer-mobile .univer-app-catalog tr.file>td:nth-child(-n+2) { display:none; }
html.univer-mobile .univer-app-catalog tr.file>td:nth-child(3) { grid-column:1/-1; font-weight:600; }
html.univer-mobile .univer-app-catalog tr.file>td[data-univer-field]::before { content:attr(data-univer-field); display:block; color:#4b5b6c; font-size:12px; font-weight:400; }
html.univer-mobile .univer-app-catalog-search { width:100%; box-sizing:border-box; min-height:48px; margin:8px 0 12px; padding:12px; border:1px solid #bdcfe3; border-radius:8px; font:inherit; }
html.univer-mobile .univer-app-catalog-download { display:block; width:100%; margin-top:10px; border:0; border-radius:8px; background:#eef4fb; padding:10px; color:#164e80; }
html.univer-mobile .univer-app-catalog-head { display:none!important; }
html.univer-mobile .univer-app-gpa-layout, html.univer-mobile .univer-app-gpa-layout>tbody, html.univer-mobile .univer-app-gpa-layout>tbody>tr, html.univer-mobile .univer-app-gpa-layout>tbody>tr>td { display:block; width:100%!important; box-sizing:border-box; min-width:0!important; border:0!important; background:none!important; }
html.univer-mobile #gpa-table.univer-app-cards input, html.univer-mobile #gpa-table.univer-app-cards select { width:100%!important; min-height:48px; padding:10px; border:1px solid #bdcfe3; border-radius:8px; }
html.univer-mobile .univer-app-cards :is(input[type=text],input[type=number],textarea,select) { max-width:100%; min-height:48px; box-sizing:border-box; }
html.univer-mobile .univer-app-cards input:is([type=checkbox],[type=radio]) { width:24px; height:24px; vertical-align:middle; }
html.univer-mobile .univer-app-cards a, html.univer-mobile .univer-app-list a { display:inline-flex; align-items:center; min-height:48px; }
html.univer-mobile table.univer-app-sort-collapsed .univer-app-sort-head { display:none!important; }
html.univer-mobile .univer-app-sort-toggle { width:100%; text-align:start; border:1px solid #bdcfe3; border-radius:8px; padding:12px; background:#eef4fb; color:#164e80; }
html.univer-mobile :is(button,input,select,textarea,a,summary):focus-visible { outline:3px solid #2b79bd; outline-offset:2px; }
html.univer-mobile #l4 .ts { white-space:normal!important; }
html.univer-mobile #l4 td.center { background:#eef3f8!important; }
html.univer-mobile #l5, html.univer-mobile #l5 td.center, html.univer-mobile #copyright,
html.univer-mobile #copyright>tbody>tr>td { background:#eef3f8!important; border:0!important; color:#4b5b6c; }
html.univer-mobile .univer-app-study-panel>tbody>tr>.ct { font-size:18px!important; line-height:1.45; }
html.univer-mobile #l4 .ts a { display:inline-flex; align-items:center; min-height:48px; padding:0 10px; }
html.univer-mobile #l4 .univer-app-media-column { width:100%!important; float:none!important; position:static!important; margin:12px 0; }
html.univer-mobile #l4 iframe.univer-app-video { display:block; width:100%!important; height:auto!important; aspect-ratio:16/9; border:0; }
html.univer-mobile #modal_auth_frm label { margin:6px 0 2px; font-size:14px!important; }
html.univer-mobile #modal_auth_frm>table>tbody>tr>td:nth-child(2) { padding:4px!important; }
/* Today in the week schedule. */
html.univer-mobile .univer-app-day.univer-app-today h3 { display:flex; align-items:center; gap:8px; }
html.univer-mobile .univer-app-today-badge { margin-inline-start:auto; padding:2px 10px; border-radius:999px; background:#1b5fa6; color:#fff; font-size:12px; font-weight:700; }
html.univer-mobile .univer-app-days button.univer-app-today:not([aria-pressed=true]) { border-color:#1b5fa6; border-width:2px; font-weight:700; }
/* News search, dates, important-only filter, numbered pages and long-article folding. */
html.univer-mobile #l4 table[data-univer-news-source], html.univer-mobile .univer-app-news-pager[hidden] { display:none!important; }
html.univer-mobile table.univer-app-news tr.news[hidden], html.univer-mobile #l4 td.tb.univer-app-news-pager-source { display:none!important; }
html.univer-mobile .univer-app-news-tools { display:flex; align-items:flex-start; gap:6px; margin:0 0 8px; padding:0; background:transparent; border:0; }
html.univer-mobile .univer-app-news-tools [hidden] { display:none!important; }
html.univer-mobile input.univer-app-news-query { flex:1 1 0; min-width:0; box-sizing:border-box; min-height:48px; padding:10px 12px; border:1px solid #bdcfe3; border-radius:24px; font:inherit; background:#fff; }
html.univer-mobile .univer-app-news-date { display:flex; flex:1 1 140px; flex-direction:column; gap:2px; color:#4b5b6c; font-size:13px!important; }
html.univer-mobile .univer-app-news-date input { box-sizing:border-box; width:100%; min-height:48px; padding:8px; border:1px solid #bdcfe3; border-radius:8px; font:inherit; background:#fff; }
html.univer-mobile .univer-app-news-tools button { min-height:48px; padding:8px 14px; border:1px solid #bdcfe3; border-radius:22px; background:#fff; color:#164e80; font:inherit; font-size:14px!important; font-weight:600; }
html.univer-mobile .univer-app-news-tools button[aria-pressed=true] { background:#1b5fa6; border-color:#1b5fa6; color:#fff; }
html.univer-mobile .univer-app-news-count { flex-basis:100%; color:#4b5b6c; font-size:13px!important; }
html.univer-mobile .univer-app-news-results p.univer-app-news-status { margin:0 0 12px; padding:12px; border-radius:10px; background:#eef4fb; color:#20374d; }
html.univer-mobile .univer-app-news-pager { display:flex; flex-wrap:wrap; align-items:center; gap:6px; margin:4px 0 16px; }
html.univer-mobile .univer-app-news-pager>a { display:inline-flex; align-items:center; justify-content:center; box-sizing:border-box; min-width:44px; min-height:48px; padding:0 10px; border:1px solid #d7e3ef; border-radius:10px; background:#fff; color:#164e80; text-decoration:none; font-weight:600; }
html.univer-mobile .univer-app-news-pager>a[aria-current=page] { background:#1b5fa6; border-color:#1b5fa6; color:#fff; }
html.univer-mobile .univer-app-news-gap { padding:0 4px; color:#4b5b6c; }
html.univer-mobile .univer-app-news-jump { display:flex; align-items:center; gap:6px; flex-basis:100%; margin-top:4px; }
html.univer-mobile .univer-app-news-jump-label { color:#4b5b6c; font-size:14px!important; }
html.univer-mobile .univer-app-news-jump input { width:84px; min-height:48px; box-sizing:border-box; padding:8px; border:1px solid #bdcfe3; border-radius:8px; font:inherit; }
html.univer-mobile .univer-app-news-jump button { min-height:48px; padding:8px 14px; border:0; border-radius:22px; background:#1b5fa6; color:#fff; font:inherit; font-weight:600; }
html.univer-mobile .univer-app-news-body { display:flow-root; position:relative; }
html.univer-mobile .univer-app-news-body[data-collapsed=true] { max-height:248px!important; overflow:hidden!important; }
html.univer-mobile .univer-app-news-body[data-collapsed=true]::after { content:''; position:absolute; bottom:0; left:0; right:0; height:40px; pointer-events:none; background:linear-gradient(transparent,#fff); }
html.univer-mobile button.univer-app-news-toggle { max-width:100%; box-sizing:border-box; white-space:normal; text-align:start; display:inline-flex; align-items:center; gap:8px; min-height:48px; margin:8px 0 0; padding:8px 12px; border:0; border-radius:22px; background:#eef4fb; color:#164e80; font:inherit; font-size:14px!important; font-weight:600; }
html.univer-mobile button.univer-app-news-toggle[hidden] { display:none!important; }
/* Attendance: course and lesson-type filters, passive totals and one syllabus button per course. */
html.univer-mobile #l4 a[data-univer-lesson-label][hidden], html.univer-mobile #l4 .univer-app-attendance-part[hidden],
html.univer-mobile #l4 .univer-app-course-source[hidden], html.univer-mobile #l4 a[data-univer-syllabus-source][hidden] { display:none!important; }
html.univer-mobile #l4 tr.univer-app-attendance-owned, html.univer-mobile #l4 tr.univer-app-attendance-owned>td { display:block!important; box-sizing:border-box; width:100%!important; padding:0!important; border:0!important; background:none!important; }
html.univer-mobile .univer-app-attendance-tools { margin:0 0 12px; text-align:start; }
html.univer-mobile .univer-app-attendance-courses[hidden] { display:none!important; }
html.univer-mobile .univer-app-attendance-courses { margin:0 0 4px; padding:0; border:0; }
html.univer-mobile p.univer-app-attendance-course-caption, html.univer-mobile .univer-app-attendance-filter-caption { display:block; margin:0 0 8px!important; color:#4b5b6c; font-size:13px!important; }
html.univer-mobile .univer-app-attendance-course-list, html.univer-mobile .univer-app-attendance-filters { display:flex; flex-wrap:wrap; gap:6px; }
html.univer-mobile .univer-app-attendance-filter-caption { flex-basis:100%; }
html.univer-mobile .univer-app-attendance-tools button { max-width:100%; min-height:48px; padding:8px 14px; border:1px solid #bdcfe3; border-radius:22px; background:#fff; color:#164e80; font:inherit; font-size:14px!important; font-weight:600; text-align:start; white-space:normal; overflow-wrap:anywhere; }
html.univer-mobile .univer-app-attendance-tools button[aria-pressed=true] { background:#1b5fa6; border-color:#1b5fa6; color:#fff; }
html.univer-mobile p.univer-app-attendance-filter-note { margin:8px 0 0!important; color:#4b5b6c; font-size:13px!important; }
html.univer-mobile .univer-app-attendance-summary { box-sizing:border-box; width:100%; margin:0 0 8px; padding:10px 12px; border:1px solid #d7e3ef; border-radius:12px; background:#fff; color:#20374d; text-align:start; }
html.univer-mobile .univer-app-attendance-overall-block { padding:0 0 10px; margin:0 0 10px; border-bottom:1px solid #d7e3ef; }
html.univer-mobile .univer-app-attendance-summary p { display:flex; flex-wrap:wrap; gap:2px 12px; margin:4px 0!important; font-size:14px!important; line-height:1.5!important; }
html.univer-mobile .univer-app-attendance-summary strong { min-width:96px; font-weight:600; }
html.univer-mobile p.univer-app-attendance-overall, html.univer-mobile p.univer-app-attendance-summary-title { margin:0 0 6px!important; color:#4b5b6c; font-size:12px!important; font-weight:600; }
html.univer-mobile p.univer-app-attendance-overall-value { font-size:17px!important; font-weight:700; color:#16283b; }
html.univer-mobile #l4 .univer-app-lesson-label { display:flex; flex-wrap:wrap; align-items:baseline; gap:2px 12px; margin:10px 0 4px; font-size:14px!important; }
html.univer-mobile #l4 .univer-app-lesson-label strong { font-size:15px!important; font-weight:700; color:#16283b; }
html.univer-mobile #l4 .univer-app-lesson-label>span { font-size:13px!important; color:#4b5b6c; }
html.univer-mobile #l4 .univer-app-course-heading { display:block; margin:0; padding:0; border:0; text-align:start; }
html.univer-mobile #l4 .univer-app-course-title { box-sizing:border-box; min-height:48px; padding:10px 0; cursor:pointer; }
html.univer-mobile #l4 .univer-app-course-meta>a { display:block; flex-basis:100%; padding:8px 0; min-height:32px; font-size:14px!important; }
html.univer-mobile #l4 .univer-app-course-name, html.univer-mobile #l4 .univer-app-course-title>.univer-app-attendance-course { font-size:18px!important; font-weight:700; line-height:1.4; }
html.univer-mobile #l4 .univer-app-attendance-course { color:#1b5fa6; text-decoration:underline; text-underline-offset:3px; }
html.univer-mobile #l4 .univer-app-course-meta { display:flex; flex-wrap:wrap; gap:2px 14px; margin-top:4px; color:#4b5b6c; }
html.univer-mobile #l4 .univer-app-course-meta span { font-size:12px!important; font-weight:400; }
html.univer-mobile #l4 button.univer-app-course-syllabus { align-self:flex-start; min-height:48px; padding:8px 16px; border:1px solid #bdcfe3; border-radius:22px; background:#fff; color:#164e80; font:inherit; font-size:14px!important; font-weight:600; }
html.univer-mobile #l4 button.univer-app-course-syllabus[aria-expanded=true] { background:#eef4fb; border-color:#1b5fa6; }
html.univer-mobile #l4 .univer-app-syllabi { box-sizing:border-box; margin:8px 0 12px; padding:12px 14px; background:#fff; border:1px solid #c7d9ed; border-inline-start:3px solid #1b5fa6; border-radius:10px; text-align:start; font-size:14px!important; font-weight:400; }
html.univer-mobile #l4 .univer-app-syllabi[hidden] { display:none!important; }
html.univer-mobile #l4 .univer-app-syllabi ul { margin:0 0 10px!important; padding:0!important; list-style:none; }
html.univer-mobile #l4 .univer-app-syllabi li { display:flex; flex-wrap:wrap; align-items:baseline; gap:4px 12px; padding:8px 0; border-bottom:1px solid #e2eaf3; }
html.univer-mobile #l4 .univer-app-syllabi li a { flex:1 1 auto; min-width:0; min-height:24px; padding:6px 0; overflow-wrap:anywhere; }
html.univer-mobile #l4 .univer-app-syllabi li span { color:#4b5b6c; font-size:12px; }
html.univer-mobile #l4 .univer-app-syllabi p { margin:0 0 10px; }
html.univer-mobile #l4 .univer-app-syllabi button { min-height:48px; margin-inline-end:12px; padding:8px 14px; border:0; border-radius:22px; background:#1b5fa6; color:#fff; font:inherit; }

/* Android supplies global navigation; Original restores the website header. */
html.univer-mobile.univer-native-chrome :is(#l1,#l2,.univer-app-native-heading) { display:none!important; }
html.univer-mobile .univer-app-schedule-context { margin:4px 0; color:#4b5b6c; font-size:14px!important; }
html.univer-mobile .univer-app-schedule-mode { display:flex; align-items:center; gap:8px; min-height:48px; color:#1b5fa6; font-size:14px!important; }
html.univer-mobile .univer-app-schedule-mode input { width:24px; height:24px; margin:0 4px; }
html.univer-mobile .univer-app-schedule:not(.univer-app-schedule-detailed) .univer-app-lesson-detail { display:none; }
html.univer-mobile .univer-app-lesson-meta { color:#4b5b6c; font-size:14px!important; }
/* The filter toggle stays beside the search field; its panel opens on a row of its own underneath. */
html.univer-mobile .univer-app-news-tools { flex-wrap:wrap; }
html.univer-mobile .univer-app-news-tools>.univer-app-news-query { flex:1 1 150px; min-width:0; }
html.univer-mobile .univer-app-news-filter-toggle { flex:0 0 auto; min-height:48px; padding:0 12px; color:#1b5fa6; font-size:14px; }
html.univer-mobile .univer-app-news-tools>.univer-app-news-advanced { flex:1 1 100%; }
html.univer-mobile .univer-app-news-pages>summary,
html.univer-mobile .univer-app-attendance-options>summary, html.univer-mobile .univer-app-material-details>summary { box-sizing:border-box; min-height:48px; padding:12px 8px; color:#1b5fa6; font-size:14px; cursor:pointer; }
html.univer-mobile .univer-app-news-advanced>div { display:flex; flex-wrap:wrap; gap:8px; padding:8px 0; }
html.univer-mobile .univer-app-news-pages { margin:0 0 8px; }
html.univer-mobile .univer-app-attendance-course-list { display:block; }
html.univer-mobile .univer-app-attendance-course-list select { box-sizing:border-box; width:100%; min-height:48px; padding:8px; border:1px solid #bdcfe3; border-radius:8px; background:#fff; color:#16283b; }
html.univer-mobile .univer-app-attendance-options>div { padding:4px 0 8px; }
html.univer-mobile .univer-app-journal-toggle { display:block; min-height:48px; margin-top:4px; padding:8px 0; border:0; background:transparent; color:#1b5fa6; text-align:start; font:inherit; }
html.univer-mobile .univer-app-materials tr.file>td:has(.univer-app-material-details) { grid-column:1/-1; }
html.univer-mobile .univer-app-material-details>div { padding:0 0 8px; font-size:14px; }
html.univer-mobile .univer-app-material-details>div>div { margin:4px 0; }
html.univer-mobile .univer-app-materials tr.file>td:nth-child(4), html.univer-mobile .univer-app-materials tr.file>td:nth-child(5) { font-size:14px!important; color:#4b5b6c; }
html.univer-mobile .univer-app-materials tr.file>td:nth-child(4)::before, html.univer-mobile .univer-app-materials tr.file>td:nth-child(5)::before { display:none; }
html.univer-mobile .univer-app-materials tr.file>td:empty { display:none!important; }
html.univer-mobile .univer-app-materials td:has(.univer-app-material-details)::before { display:none; }
html.univer-mobile .univer-app-materials .univer-app-material-details .stars { gap:0; }
/* Shared with Windows: content columns, quiet empty results, contract records, semester navigation. */
html.univer-mobile #l4 .univer-app-content-columns,
html.univer-mobile #l4 .univer-app-content-columns>tbody,
html.univer-mobile #l4 .univer-app-content-columns>tbody>tr,
html.univer-mobile #l4 .univer-app-content-columns>tbody>tr>td { display:block!important; width:100%!important; min-width:0!important; box-sizing:border-box; padding:0!important; }
html.univer-mobile #l4 .univer-app-content-columns>tbody>tr>td+td { margin-top:16px; }
html.univer-mobile #l4 :is(input[type=button],input[type=submit],button):disabled { opacity:.55; cursor:default; }
html.univer-mobile .univer-app-empty-result { height:auto!important; min-height:64px!important; max-height:none!important; overflow:auto!important; }
html.univer-mobile .univer-app-empty-result .univer-app-card-section td { text-align:center!important; }
html.univer-mobile #l4 .univer-app-div-records { min-width:760px!important; table-layout:auto!important; }
html.univer-mobile .univer-app-div-records .full-width:has(>div) { display:grid!important; grid-template-columns:minmax(140px,1fr) minmax(200px,1.5fr) minmax(84px,.5fr) minmax(128px,1fr) minmax(96px,.7fr) minmax(84px,.5fr); width:100%!important; float:none!important; }
html.univer-mobile .univer-app-div-records .full-width>div { width:auto!important; min-width:0!important; float:none!important; padding:8px!important; box-sizing:border-box; overflow-wrap:normal!important; }
html.univer-mobile .univer-app-semester-nav { display:flex; flex-wrap:wrap; gap:8px; margin:12px 0; }
html.univer-mobile .univer-app-semester-nav a { display:inline-flex; align-items:center; min-height:44px; padding:0 14px; border:1px solid #dfe5ec; border-radius:6px; background:white; text-decoration:none; }
html.univer-mobile .univer-app-calendar-meta>tbody>tr { display:grid!important; grid-template-columns:minmax(140px,.5fr) minmax(0,1fr); gap:4px; }
html.univer-mobile .univer-app-calendar-meta>tbody>tr>td { min-width:0!important; padding:8px!important; }
html.univer-mobile .univer-app-empty-nav-cell { display:none!important; }
html.univer-mobile :is(input,select,textarea,button,a,summary):focus-visible { outline:2px solid #1b5fa6; outline-offset:2px; }
/* Wide tables (univer-tables.js): the hint row with "Expand", tables fitted to the screen, and the full-screen view. */
html:root.univer-mobile div.univer-app-scroll-hint { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
html:root.univer-mobile div.univer-app-scroll-hint[hidden] { display:none!important; }
html.univer-mobile .univer-app-scroll-caption { flex:1 1 12rem; min-width:0; text-align:start; }
html:root.univer-mobile div.univer-app-scroll-hint-expand { justify-content:flex-end; }
html.univer-mobile .univer-app-scroll-caption, html.univer-mobile div.univer-app-scroll-hint:not(.univer-app-scroll-hint-expand) { position:absolute!important; width:1px; height:1px; margin:0!important; overflow:hidden; clip-path:inset(50%); white-space:nowrap; }
html:root.univer-mobile .univer-app-table-open { flex:0 0 auto; display:inline-flex; align-items:center; gap:6px; min-height:40px; margin:0!important; padding:0 14px!important; border:1px solid #c7d9ed!important; border-radius:20px!important; background:#eef4fb!important; color:#1b5fa6!important; font:600 14px/1 Roboto,system-ui,sans-serif!important; cursor:pointer; }
html:root.univer-mobile .univer-app-table-open::before { content:''; width:16px; height:16px; background:currentColor; -webkit-mask:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z'/%3E%3C/svg%3E") center/contain no-repeat; mask:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z'/%3E%3C/svg%3E") center/contain no-repeat; }
html.univer-mobile.univer-app-table-viewing, html.univer-mobile.univer-app-table-viewing body { overflow:hidden!important; }
html:root.univer-mobile .univer-app-table-viewer { position:fixed; inset:0; z-index:2147483600; display:flex; flex-direction:column; background:#f1f4f7; color:#16283b; }
html:root.univer-mobile .univer-app-table-viewer-bar { flex:0 0 auto; display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:4px 8px; padding:8px 8px 8px 16px; background:#ffffff; border-bottom:1px solid #dfe5ec; }
html:root.univer-mobile .univer-app-table-viewer-title { font:600 16px/1.3 Roboto,system-ui,sans-serif!important; overflow:hidden; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; }
html:root.univer-mobile .univer-app-table-viewer-tools { grid-column:1 / -1; display:flex; align-items:center; gap:8px; }
html:root.univer-mobile .univer-app-table-viewer-bar button { min-width:44px; min-height:44px; margin:0!important; padding:0 12px!important; border:1px solid #dfe5ec!important; border-radius:22px!important; background:#ffffff!important; color:#1b5fa6!important; font:600 18px/1 Roboto,system-ui,sans-serif!important; cursor:pointer; }
html:root.univer-mobile .univer-app-table-viewer-bar button:disabled { opacity:.4; }
html:root.univer-mobile .univer-app-table-viewer-close { color:#16283b!important; }
html:root.univer-mobile .univer-app-table-viewer-fit { margin-left:auto!important; font-size:14px!important; }
html:root.univer-mobile .univer-app-table-viewer-fit[aria-pressed=true] { background:#eef4fb!important; border-color:#c7d9ed!important; }
html:root.univer-mobile .univer-app-table-viewer-level { min-width:52px; text-align:center; font:600 14px/1 Roboto,system-ui,sans-serif!important; color:#526b82; }
html:root.univer-mobile .univer-app-table-viewer-scroll { flex:1 1 auto; overflow:auto; padding:8px; touch-action:pan-x pan-y; overscroll-behavior:contain; -webkit-overflow-scrolling:touch; }
html:root.univer-mobile .univer-app-table-viewer .univer-app-table-viewer-stage { display:inline-block; min-width:0!important; max-width:none!important; margin:0!important; overflow:visible!important; vertical-align:top; }
html:root.univer-mobile .univer-app-table-viewer .univer-app-table-viewer-stage>table { width:100%!important; min-width:0!important; max-width:none!important; margin:0!important; }
html:root.univer-mobile .univer-app-table-viewer-stage>table>:is(thead,tbody):first-of-type>tr:first-child:not(:has(>[rowspan]:not([rowspan="1"])))>th { position:sticky; top:0; z-index:1; }
`;
})();
