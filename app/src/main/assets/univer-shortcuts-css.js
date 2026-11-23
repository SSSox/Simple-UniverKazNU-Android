/* The home tiles' stylesheet (univer-shortcuts.js: pinned tiles, the quick bar, credential panels and hubs),
   kept apart from the tiles' logic. Loaded right before univer-shortcuts.js. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz') return;
    window.__univerShortcutsCssV1 = `
/* Home and Bachelor share a content gutter; tiles, headings and panels start on one line. */
:root.univer-mobile.univer-app-home { --u-home-gutter:24px; }
:root.univer-mobile.univer-app-home #l4>td { padding:24px var(--u-home-gutter) 32px!important; box-sizing:border-box; }
:root.univer-mobile.univer-app-home #l4 td.center.univer-app-home-body { padding:0!important; }
:root.univer-mobile.univer-app-home #l4>td>* { max-width:var(--u-page); margin-left:auto!important; margin-right:auto!important; box-sizing:border-box; }
:root.univer-mobile.univer-app-home .univer-app-quick { margin:0 auto 24px!important; align-items:stretch; gap:12px; }
:root.univer-mobile.univer-app-home #l4 h1 { margin-top:24px; margin-bottom:12px; }
:root.univer-mobile.univer-app-home #l4 table.mt td.univer-app-credentials-host { padding:8px 18px 12px!important; }
:root.univer-mobile #l4 td.univer-app-credentials-host .univer-app-credential { display:grid!important; grid-template-columns:110px minmax(0,1fr) auto; }
:root.univer-mobile #l4 td.univer-app-credentials-host .univer-app-credential-value { min-width:0; }
@media (max-width:900px) { :root.univer-mobile.univer-app-home { --u-home-gutter:16px; } }
@media (max-width:640px) { :root.univer-mobile #l4 td.univer-app-credentials-host .univer-app-credential { grid-template-columns:minmax(0,1fr); } }
:root.univer-mobile .univer-app-quick .univer-app-tile { position:relative; display:flex; min-width:0; }
:root.univer-mobile .univer-app-quick .univer-app-tile>a { flex:1 1 auto; min-width:0; overflow-wrap:break-word; }
:root.univer-mobile .univer-app-quick .univer-app-tile.univer-app-pinned>a { padding-right:46px!important; }
/* The icon already says where a tile leads; only links that leave the site keep a small outward arrow. */
:root.univer-mobile .univer-app-quick .univer-app-tile>a::after { content:none!important; }
:root.univer-mobile .univer-app-quick .univer-app-tile.univer-app-external .univer-app-tile-label::after { content:'↗'; margin-left:6px; color:var(--u-muted,#5b6b7c); font-weight:400; }
:root.univer-mobile .univer-app-unpin { position:absolute; top:50%; right:10px; display:grid; place-items:center; width:28px; height:28px; margin:0!important; padding:0!important; border:0!important; border-radius:8px!important; background:transparent!important; color:var(--u-muted,#5b6b7c)!important; cursor:pointer; transform:translateY(-50%); }
:root.univer-mobile .univer-app-unpin::before { content:''; width:14px; height:14px; background:currentColor; -webkit-mask:var(--univer-close-icon) center/contain no-repeat; }
:root.univer-mobile .univer-app-tile>a { padding-right:40px!important; }
:root.univer-mobile .univer-app-tile.univer-app-pinned>a { padding-right:72px!important; }
:root.univer-mobile .univer-app-reorder { position:absolute; right:8px; top:50%; transform:translateY(-50%); width:28px; height:28px; padding:0!important; margin:0!important; border:0!important; border-radius:6px; background:transparent!important; color:var(--u-muted,#5b6b7c)!important; cursor:grab; font-size:20px!important; touch-action:none; }
:root.univer-mobile .univer-app-pinned .univer-app-reorder { right:40px; }
:root.univer-mobile .univer-app-reorder:focus-visible { outline:2px solid var(--u-brand,#1b5fa6); outline-offset:1px; }
:root.univer-mobile .univer-app-reorder::before { content:''; display:block; width:16px; height:16px; margin:auto; background:currentColor; -webkit-mask:var(--univer-reorder-icon) center/contain no-repeat; }
:root.univer-mobile .univer-app-dragging { opacity:.55; }
:root.univer-mobile .univer-app-drop-target { outline:2px solid var(--u-brand,#1b5fa6); outline-offset:2px; }
/* The bin appears while a tile is dragged; over it the tile is removed from the home screen. */
:root.univer-mobile .univer-app-trash { position:fixed; left:50%; bottom:max(20px,env(safe-area-inset-bottom)); z-index:2147483000; display:flex; align-items:center; justify-content:center; gap:10px; box-sizing:border-box; min-width:min(260px,calc(100% - 32px)); max-width:calc(100% - 32px); padding:14px 22px; border:2px dashed var(--u-danger,#b42318); border-radius:999px; background:#fff; color:var(--u-danger,#b42318); font-size:15px; font-weight:600; line-height:1.2; box-shadow:0 10px 30px rgba(16,40,70,.22); opacity:0; pointer-events:none; transform:translate(-50%,24px) scale(.96); transition:opacity .16s ease-out,transform .16s ease-out,background-color .12s ease-out,color .12s ease-out; }
:root.univer-mobile.univer-app-tile-drag .univer-app-trash { opacity:1; pointer-events:auto; transform:translate(-50%,0) scale(1); }
:root.univer-mobile .univer-app-trash.univer-app-trash-hot { border-style:solid; background:var(--u-danger,#b42318); color:#fff; transform:translate(-50%,0) scale(1.06); }
:root.univer-mobile .univer-app-trash-icon { flex:0 0 auto; width:24px; height:24px; background:currentColor; -webkit-mask:var(--univer-trash-icon) center/contain no-repeat; mask:var(--univer-trash-icon) center/contain no-repeat; }
:root.univer-mobile.univer-app-still .univer-app-trash { transition:none; }
:root.univer-mobile .univer-app-move>.univer-app-move-remove { color:var(--u-danger,#b42318)!important; border-color:#f0c9c5!important; }
:root.univer-mobile .univer-app-move>.univer-app-move-remove::before { -webkit-mask-image:var(--univer-trash-icon)!important; mask-image:var(--univer-trash-icon)!important; transform:none!important; }
:root.univer-mobile .univer-app-after-credentials { display:none!important; }
:root.univer-mobile .univer-app-unpin:focus-visible { background:var(--u-surface-2,#f3f6f9)!important; color:var(--u-danger,#b42318)!important; outline:2px solid var(--u-brand,#1b5fa6); outline-offset:-2px; }
:root.univer-mobile #tools .univer-app-pin-row { display:flex; align-items:center; width:100%; min-width:0; }
:root.univer-mobile #tools .univer-app-pin-row>a { flex:1 1 auto; min-width:0; display:flex!important; align-items:center; white-space:normal!important; overflow-wrap:anywhere; text-indent:0!important; }
:root.univer-mobile #tools .univer-app-menu-label { display:block; min-width:0; }
:root.univer-mobile .univer-app-pin { flex:0 0 auto; width:30px; height:30px; margin:0 6px 0 2px!important; padding:0!important; border:0!important; border-radius:8px!important; background:transparent!important; color:var(--u-muted,#5b6b7c)!important; cursor:pointer; opacity:.7; }
:root.univer-mobile .univer-app-pin::before { content:''; display:block; width:16px; height:16px; margin:auto; background:currentColor; -webkit-mask:var(--univer-pin-icon) center/contain no-repeat; }
:root.univer-mobile li:focus-within>.univer-app-pin, :root.univer-mobile .univer-app-pin[aria-pressed=true] { opacity:1; }
:root.univer-mobile .univer-app-pin[aria-pressed=true] { color:var(--u-brand,#1b5fa6)!important; }
:root.univer-mobile .univer-app-pin[aria-pressed=true]::before { -webkit-mask-image:var(--univer-pinned-icon); }
:root.univer-mobile .univer-app-pin:focus-visible { background:var(--u-brand-soft,#e9f1fb)!important; color:var(--u-brand,#1b5fa6)!important; outline:2px solid var(--u-brand,#1b5fa6); outline-offset:-2px; }
:root.univer-mobile .univer-app-useful ul { display:grid!important; grid-template-columns:repeat(auto-fill,minmax(230px,1fr)); gap:8px; margin:0!important; padding:12px!important; list-style:none!important; }
:root.univer-mobile .univer-app-useful li { display:flex!important; align-items:center; margin:0!important; padding:0 4px 0 0!important; list-style:none!important; border:1px solid var(--u-line,#dfe5ec); border-radius:8px; background:#fff; }
:root.univer-mobile .univer-app-useful li:focus-within { border-color:var(--u-brand-line,#bfd3ea); box-shadow:var(--u-shadow); }
:root.univer-mobile .univer-app-useful li[hidden] { display:none!important; }
:root.univer-mobile .univer-app-useful li>a { flex:1 1 auto; min-width:0; display:flex!important; align-items:center; gap:10px; padding:7px 8px!important; border-radius:9px; color:var(--u-ink,#16283b)!important; font-size:15px!important; line-height:1.35!important; text-decoration:none; }
:root.univer-mobile .univer-app-useful li>a:focus-visible { color:var(--u-brand,#1b5fa6)!important; outline:none; }
:root.univer-mobile .univer-app-useful li>a:focus-visible { box-shadow:inset 0 0 0 2px var(--u-brand,#1b5fa6); }
:root.univer-mobile .univer-app-link-icon { flex:0 0 auto; display:grid; place-items:center; width:30px; height:30px; border-radius:6px; background:var(--tint,#1b5fa6); box-shadow:none; }
:root.univer-mobile .univer-app-link-icon::before { content:''; width:18px; height:18px; background:#fff; -webkit-mask:var(--glyph) center/contain no-repeat; }
:root.univer-mobile .univer-app-link-icon.univer-app-link-internal { background:var(--u-brand-soft,#e9f1fb); }
:root.univer-mobile .univer-app-link-icon.univer-app-link-internal::before { background:var(--u-brand,#1b5fa6); }
:root.univer-mobile .univer-app-link-icon.univer-app-link-art { box-shadow:inset 0 0 0 1px var(--u-line,#dfe5ec); }
:root.univer-mobile .univer-app-link-icon.univer-app-link-art::before { background:var(--art) center/contain no-repeat; -webkit-mask:none; }
:root.univer-mobile .univer-app-quick .univer-app-tile>a { gap:12px; }
:root.univer-mobile .univer-app-quick .univer-app-link-icon { width:32px; height:32px; }
:root.univer-mobile .univer-app-quick .univer-app-tile-label { flex:1 1 auto; min-width:0; }
:root.univer-mobile .univer-app-menu>.univer-app-useful { margin-top:12px!important; }
/* Header tabs carry an icon for what each one holds. */
:root.univer-mobile .univer-app-tabs>a.univer-app-tab-icon::before { content:''; width:18px; height:18px; margin-right:8px; flex:0 0 auto; background:currentColor; -webkit-mask:var(--glyph) center/contain no-repeat; }
/* Бакалавр: the two GPA figures as the page's lead. */
:root.univer-mobile .univer-app-gpa { display:grid; grid-template-columns:repeat(auto-fit,minmax(180px,240px)); gap:12px; margin:4px 0 20px; }
:root.univer-mobile .univer-app-gpa>div { padding:14px 18px; border:1px solid var(--u-line,#dfe5ec); border-radius:12px; background:#fff; box-shadow:var(--u-shadow); }
:root.univer-mobile .univer-app-gpa b { display:block; color:var(--u-ink,#16283b); font-size:28px; line-height:1.15; font-weight:650; font-variant-numeric:tabular-nums; }
:root.univer-mobile .univer-app-gpa span { display:block; margin-top:2px; color:var(--u-muted,#5b6b7c); font-size:14px; }
:root.univer-mobile .univer-app-gpa-source { display:none!important; }
:root.univer-mobile .univer-app-gpa-source-table { display:none!important; }
/* Е-услуги: one switch between the catalogue and the student's own requests on every services page. */
:root.univer-mobile .univer-app-ssc-nav { display:inline-flex; gap:4px; margin:16px 0 18px; padding:4px; border:1px solid var(--u-line,#dfe5ec); border-radius:12px; background:#fff; }
:root.univer-mobile .univer-app-ssc-nav[hidden] { display:none!important; }
:root.univer-mobile .univer-app-ssc-nav a { display:inline-flex; align-items:center; gap:8px; min-height:36px; padding:0 14px; border-radius:9px; color:var(--u-muted,#5b6b7c)!important; font-size:15px; font-weight:600; text-decoration:none!important; }
:root.univer-mobile .univer-app-ssc-nav a::before { content:''; width:18px; height:18px; background:currentColor; -webkit-mask:var(--glyph) center/contain no-repeat; }
:root.univer-mobile .univer-app-ssc-nav a[aria-current=page] { background:var(--u-brand-soft,#e9f1fb); color:var(--u-brand,#1b5fa6)!important; }
:root.univer-mobile .univer-app-ssc-nav a:focus-visible { outline:2px solid var(--u-brand,#1b5fa6); outline-offset:1px; }
:root.univer-mobile #l4 :is(.ts,.univer-app-desk-cards):has(>a.univer-app-service-card) { display:flex!important; flex-wrap:wrap; gap:12px; margin:0 0 16px; }
:root.univer-mobile #l4 a.univer-app-desk-card { border:1px solid var(--u-line,#dfe5ec); border-radius:14px; background:#fff; box-shadow:var(--u-shadow); }
:root.univer-mobile #l4 td.ct.univer-app-panel-head:not(:first-child), :root.univer-mobile #l4 tr:not(:first-child)>td.ct.univer-app-panel-head { border-top:1px solid var(--u-line,#dfe5ec)!important; }
:root.univer-mobile #l4 :is(.ts>a,a).univer-app-service-card { flex:1 1 300px; box-sizing:border-box; height:auto!important; display:grid!important; grid-template-columns:44px minmax(0,1fr); grid-template-rows:auto auto; column-gap:14px; align-items:center; min-height:76px; padding:14px 18px!important; text-align:start!important; text-decoration:none!important; color:var(--u-ink,#16283b)!important; font-size:17px!important; font-weight:650; }
:root.univer-mobile #l4 :is(.ts>a,a).univer-app-service-card>.univer-app-link-icon { grid-row:1/3; width:44px; height:44px; border-radius:12px; }
:root.univer-mobile #l4 :is(.ts>a,a).univer-app-service-card>.univer-app-link-icon::before { width:24px; height:24px; }
:root.univer-mobile #l4 :is(.ts>a,a).univer-app-service-card>:not(.univer-app-link-icon) { grid-column:2; }
:root.univer-mobile #l4 :is(.ts>a,a).univer-app-service-card * { text-align:start!important; }
:root.univer-mobile .univer-app-service-search { display:block; width:100%; max-width:560px; height:42px; box-sizing:border-box; margin:0 0 14px!important; padding-left:38px!important; }
:root.univer-mobile .univer-app-service-empty { margin:4px 0 14px!important; color:var(--u-muted,#5b6b7c); }
:root.univer-mobile tr.univer-app-service-hidden { display:none!important; }
:root.univer-mobile .univer-app-status { white-space:nowrap; }
:root.univer-mobile .univer-app-status::before { content:''; display:inline-block; width:8px; height:8px; margin-right:8px; border-radius:50%; background:var(--u-muted,#5b6b7c); vertical-align:1px; }
:root.univer-mobile .univer-app-status[data-univer-tone=done]::before { background:#15803d; }
:root.univer-mobile .univer-app-status[data-univer-tone=done] { color:#166534!important; font-weight:600; }
:root.univer-mobile .univer-app-status[data-univer-tone=wait]::before { background:#d97706; }
:root.univer-mobile .univer-app-status[data-univer-tone=fail]::before { background:#b42318; }
:root.univer-mobile .univer-app-status[data-univer-tone=fail] { color:#b42318!important; font-weight:600; }
/* Print and Wi-Fi sign-in as its own panel: login visible, password hidden until shown, both copyable. */
:root.univer-mobile .univer-app-credentials-source { display:none!important; }
:root.univer-mobile #l4 td.univer-app-credentials-host { font-size:0!important; }
:root.univer-mobile #l4 td.univer-app-credentials-host>:not(.univer-app-credentials) { display:none!important; }
:root.univer-mobile #l4 td.univer-app-credentials-host>.univer-app-credentials { margin:0; padding:0; border:0; box-shadow:none; font-size:15px; }
:root.univer-mobile #l4 td.univer-app-credentials-host .univer-app-credential:first-child { border-top:0; }
:root.univer-mobile .univer-app-credentials { margin:8px 0 16px; padding:16px 20px; border:1px solid var(--u-line,#dfe5ec); border-radius:14px; background:#fff; box-shadow:var(--u-shadow); }
:root.univer-mobile .univer-app-credentials>h3 { display:flex; align-items:center; gap:12px; margin:0 0 12px!important; color:var(--u-ink,#16283b)!important; font-size:17px!important; font-weight:650; }
:root.univer-mobile .univer-app-credentials>h3>.univer-app-link-icon { width:32px; height:32px; border-radius:9px; }
:root.univer-mobile .univer-app-credential { display:grid; grid-template-columns:110px minmax(0,1fr) auto; align-items:center; gap:12px; padding:10px 0; border-top:1px solid var(--u-line,#dfe5ec); }
:root.univer-mobile .univer-app-credential-label { color:var(--u-muted,#5b6b7c); font-size:14px; }
:root.univer-mobile .univer-app-credential-value { color:var(--u-ink,#16283b); font-size:16px; font-weight:600; font-variant-numeric:tabular-nums; letter-spacing:.01em; overflow-wrap:anywhere; }
:root.univer-mobile .univer-app-credential-hidden { letter-spacing:.18em; color:var(--u-muted,#5b6b7c); }
:root.univer-mobile .univer-app-credential-actions { display:flex; gap:8px; }
:root.univer-mobile .univer-app-credential-button { min-height:34px; padding:0 14px!important; border:1px solid var(--u-line,#dfe5ec)!important; border-radius:8px!important; background:#fff!important; color:var(--u-ink,#16283b)!important; font-size:14px!important; font-weight:600; cursor:pointer; }
:root.univer-mobile .univer-app-credential-button:focus-visible { outline:2px solid var(--u-brand,#1b5fa6); outline-offset:2px; }
:root.univer-mobile .univer-app-credential-button.univer-app-copied { border-color:#86c7a0!important; background:#ebf8f1!important; color:#166534!important; }
@media (max-width:640px) { :root.univer-mobile .univer-app-credential { grid-template-columns:minmax(0,1fr); gap:6px; } }
/* Бакалавр and Е-услуги summaries: today's classes, grades and the latest requests as panels. */
:root.univer-mobile .univer-app-overview-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(340px,1fr)); gap:16px; margin:0 0 16px; align-items:start; }
:root.univer-mobile .univer-app-overview { margin:0 0 16px; border:1px solid var(--u-line,#dfe5ec); border-radius:14px; background:#fff; box-shadow:var(--u-shadow); overflow:hidden; }
:root.univer-mobile .univer-app-overview-grid>.univer-app-overview { margin:0; }
:root.univer-mobile .univer-app-overview-head { display:flex; align-items:center; gap:12px; padding:14px 20px; border-bottom:1px solid var(--u-line,#dfe5ec); background:var(--u-surface-2,#f7f9fb); }
:root.univer-mobile .univer-app-overview-head>h2 { flex:1 1 auto; margin:0!important; color:var(--u-ink,#16283b)!important; font-size:17px!important; font-weight:650; }
:root.univer-mobile .univer-app-overview-link { color:var(--u-brand,#1b5fa6)!important; font-size:14px!important; font-weight:600; text-decoration:none!important; white-space:nowrap; }
:root.univer-mobile .univer-app-overview-body { padding:12px 20px 16px; }
/* The schedule page's export buttons. */
:root.univer-mobile .univer-app-schedule-export { display:flex; flex-wrap:wrap; gap:8px; margin:8px 0 0; }
:root.univer-mobile .univer-app-schedule-export button { box-sizing:border-box; min-height:44px; padding:0 14px!important; border:1px solid var(--u-line,#dfe5ec)!important; border-radius:10px!important; background:var(--u-surface,#fff)!important; color:var(--u-brand,#1b5fa6)!important; font-family:inherit; font-size:14px!important; font-weight:600; line-height:1.2; cursor:pointer; }
:root.univer-mobile .univer-app-schedule-export button[aria-busy=true] { opacity:.6; }
:root.univer-mobile .univer-app-schedule-export button:focus-visible { outline:2px solid var(--u-brand,#1b5fa6); outline-offset:2px; }
:root.univer-mobile .univer-app-overview-caption { margin:0 0 8px!important; color:var(--u-muted,#5b6b7c); font-size:14px!important; }
:root.univer-mobile .univer-app-overview-empty { margin:4px 0!important; color:var(--u-muted,#5b6b7c); }
:root.univer-mobile .univer-app-overview-stale { margin:10px 0 0!important; color:var(--u-muted,#5b6b7c); font-size:13px!important; }
:root.univer-mobile .univer-app-overview :is(ol,ul) { margin:0!important; padding:0!important; list-style:none!important; }
:root.univer-mobile .univer-app-overview li { display:flex; align-items:flex-start; gap:14px; margin:0!important; padding:10px 0!important; border-top:1px solid var(--u-line,#dfe5ec); list-style:none!important; }
:root.univer-mobile .univer-app-overview li:first-child { border-top:0; }
:root.univer-mobile .univer-app-overview-lesson>time { flex:0 0 96px; padding-top:1px; color:var(--u-muted,#5b6b7c); font-size:14px; font-variant-numeric:tabular-nums; }
:root.univer-mobile .univer-app-overview-lesson-main { flex:1 1 auto; min-width:0; }
:root.univer-mobile .univer-app-overview-lesson-main>b { display:block; color:var(--u-ink,#16283b); font-size:15px; font-weight:600; }
:root.univer-mobile .univer-app-overview-lesson-main>span { display:block; margin-top:2px; color:var(--u-muted,#5b6b7c); font-size:14px; }
:root.univer-mobile .univer-app-overview-done { opacity:.6; }
:root.univer-mobile .univer-app-overview-now { margin:0 -12px!important; padding:10px 12px!important; border-radius:10px; background:var(--u-brand-soft,#e9f1fb); border-top-color:transparent!important; }
:root.univer-mobile .univer-app-overview-state { flex:0 0 auto; padding:2px 10px; border-radius:999px; background:var(--u-surface-2,#f3f6f9); color:var(--u-muted,#5b6b7c); font-size:12px; font-weight:600; }
:root.univer-mobile .univer-app-overview-courses>li { align-items:center; gap:12px; }
:root.univer-mobile .univer-app-overview-courses>li>.univer-app-overview-course-link { flex:1 1 160px; min-width:0; color:var(--u-brand,#1b5fa6)!important; font-size:15px; font-weight:500; text-decoration:none; }
:root.univer-mobile .univer-app-overview-scores { display:flex; flex:0 1 auto; flex-wrap:wrap; justify-content:flex-end; gap:6px; }
:root.univer-mobile .univer-app-overview-courses .univer-app-overview-score { min-width:70px; padding:4px 8px 7px; }
:root.univer-mobile .univer-app-overview-more { display:block; width:100%; margin:8px 0 0; min-height:34px; border:1px solid var(--u-line,#dfe5ec)!important; border-radius:8px!important; background:#fff!important; color:var(--u-brand,#1b5fa6)!important; font-size:14px!important; font-weight:600; cursor:pointer; }
:root.univer-mobile .univer-app-overview li[hidden] { display:none!important; }
:root.univer-mobile .univer-app-overview-next-day { margin:10px 0 2px!important; color:var(--u-ink,#16283b); font-size:14px!important; font-weight:600; }
:root.univer-mobile .univer-app-overview-request { display:flex; align-items:center; gap:12px; width:100%; margin:-6px -10px; padding:6px 10px; border-radius:9px; color:inherit!important; text-decoration:none!important; }
:root.univer-mobile .univer-app-overview-request:focus-visible { outline:2px solid var(--u-brand,#1b5fa6); outline-offset:0; }
:root.univer-mobile .univer-app-overview-request>.univer-app-status { margin-left:auto; }
/* The week's independent work under the two panels: course, its entry and score, and the task in two lines. */
:root.univer-mobile .univer-app-overview-work-list .univer-app-overview-course-link { display:block; color:var(--u-brand,#1b5fa6)!important; font-size:14px; font-weight:500; line-height:1.4; text-decoration:none; }
:root.univer-mobile .univer-app-overview-work-list .univer-app-overview-course-link:hover { text-decoration:underline; }
:root.univer-mobile .univer-app-overview-work-topic { display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; margin:4px 0 0!important; overflow:hidden; color:var(--u-ink,#16283b); font-size:13px!important; line-height:1.45; overflow-wrap:anywhere; }
:root.univer-mobile .univer-app-overview-score { position:relative; display:inline-flex; align-items:baseline; gap:6px; min-width:84px; padding:6px 10px 9px; border:1px solid var(--u-line,#dfe5ec); border-radius:9px; overflow:hidden; }
:root.univer-mobile .univer-app-overview-score-label { color:var(--u-muted,#5b6b7c); font-size:12px; }
:root.univer-mobile .univer-app-overview-score-value { color:var(--u-ink,#16283b); font-size:16px; font-weight:650; font-variant-numeric:tabular-nums; }
:root.univer-mobile .univer-app-overview-score-bar { position:absolute; left:0; bottom:0; height:3px; width:var(--univer-score); background:var(--u-teal,#0f766e); }
:root.univer-mobile .univer-app-overview-requests>li { align-items:center; }
/* Two large study tabs; Е-услуги lives on the home screen as a tile. */
:root.univer-mobile .univer-app-tabs>a.univer-app-tab-hidden { display:none!important; }
:root.univer-mobile .univer-app-tabs>a.univer-app-tab-icon { height:44px!important; padding:0 20px!important; border-radius:10px!important; font-size:16px!important; font-weight:600!important; }:root.univer-mobile .univer-app-tabs>a.univer-app-tab-icon::before { width:20px!important; height:20px!important; }
:root.univer-mobile .univer-app-tabs>a.univer-app-tab-icon.active { background:var(--u-brand,#1b5fa6)!important; color:#fff!important; box-shadow:0 1px 2px rgba(16,40,70,.15); }
:root.univer-mobile .univer-app-tabs>a.univer-app-tab-icon:focus-visible { outline:2px solid var(--u-brand,#1b5fa6); outline-offset:2px; }
/* Services: a compact list; request rows open the request. */
:root.univer-mobile #l4 table.univer-app-service-table :is(td,th) { padding-top:6px!important; padding-bottom:6px!important; }
:root.univer-mobile #l4 table.univer-app-service-table tr { min-height:0!important; height:auto!important; }
:root.univer-mobile #l4 table.univer-app-service-table a { min-height:30px!important; padding:4px 12px!important; }
:root.univer-mobile #l4 table.univer-app-request-table tr[id] { cursor:pointer; }
/* Titled panels for the site's own sections on Бакалавр and Е-услуги. */
:root.univer-mobile #l4 table.mt.univer-app-panel { display:block!important; width:auto!important; margin:0 0 16px!important; overflow:hidden; background:#fff!important; border:1px solid var(--u-line,#dfe5ec)!important; border-radius:14px!important; box-shadow:var(--u-shadow); }
:root.univer-mobile #l4 table.mt.univer-app-panel>tbody, :root.univer-mobile #l4 table.mt.univer-app-panel>tbody>tr { display:block!important; }
:root.univer-mobile #l4 table.mt.univer-app-panel :is(td.lt,td.rt,td.pd) { display:none!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-head { display:flex!important; align-items:center; gap:12px; padding:14px 20px!important; border-bottom:1px solid var(--u-line,#dfe5ec)!important; background:var(--u-surface-2,#f7f9fb)!important; color:var(--u-ink,#16283b)!important; font-size:17px!important; font-weight:650!important; text-align:start!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-head>.univer-app-link-icon { width:32px; height:32px; border-radius:9px; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body { display:block!important; padding:16px 20px!important; text-align:start!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body :is(h1,h2,h3,h4) { margin:0 0 8px!important; color:var(--u-ink,#16283b)!important; font-size:17px!important; line-height:1.4!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body :is(ol,ul):not(.univer-app-gpa) { margin:4px 0!important; padding-left:20px!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body li { margin:0 0 8px!important; color:var(--u-text,#2a3d51)!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body font { color:inherit!important; }
/* Compact, contrasting content: the info icon goes, notices get a coloured edge, documents become link chips. */
:root.univer-mobile #l4 table.mt.univer-app-panel>tbody>tr>td.ct.univer-app-panel-head { padding:12px 18px!important; font-size:16px!important; }
:root.univer-mobile #l4 table.mt.univer-app-panel>tbody>tr>td.univer-app-panel-body { display:block!important; padding:12px 18px!important; box-sizing:border-box; }
:root.univer-mobile.univer-app-home #l4 .univer-app-panel-body table.univer-app-pairs>tbody>tr { display:grid!important; grid-template-columns:140px minmax(0,1fr); gap:4px 16px; margin:0!important; padding:12px 0!important; border:0!important; border-bottom:1px solid var(--u-line)!important; border-radius:0!important; background:none!important; }
:root.univer-mobile.univer-app-home #l4 .univer-app-panel-body table.univer-app-pairs>tbody>tr:last-child { border-bottom:0!important; }
:root.univer-mobile.univer-app-home #l4 .univer-app-panel-body table.univer-app-pairs>tbody>tr>td { padding:4px 0!important; margin:0!important; }
@media (min-width:1100px) { :root.univer-mobile.univer-app-home #l4 .univer-app-panel-body table.univer-app-pairs>tbody>tr { grid-template-columns:140px minmax(0,1fr) 140px minmax(0,1fr); } }
@media (max-width:640px) { :root.univer-mobile.univer-app-home #l4 .univer-app-panel-body table.univer-app-pairs>tbody>tr { grid-template-columns:minmax(0,1fr); gap:4px; } }
:root.univer-mobile #l4 td.ct.univer-app-panel-body img[src*="info"] { display:none!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body>b { display:block; margin:0 0 8px; color:var(--u-ink,#16283b); font-size:16px; font-weight:600; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body>b+br { display:none; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body>b.univer-app-inline-bold { display:inline; margin:0; font-size:inherit; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body ol.univer-app-list { padding:0!important; list-style:none!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body ol.univer-app-list>li { margin:0 0 8px!important; padding:10px 14px!important; border-left:3px solid #d97706; border-radius:8px; background:#fff8eb; color:var(--u-ink,#16283b)!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body ol.univer-app-list>li br:last-child { display:none; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body ul.univer-app-list { display:flex; flex-wrap:wrap; gap:8px; padding:0!important; list-style:none!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body ul.univer-app-list>li { margin:0!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body ul.univer-app-list>li>a { display:inline-flex!important; align-items:center; gap:8px; padding:8px 14px!important; border:1px solid var(--u-brand-line,#bfd3ea)!important; border-radius:10px!important; background:var(--u-brand-soft,#e9f1fb)!important; color:var(--u-brand-strong,#154d88)!important; font-weight:600; text-decoration:none!important; }
:root.univer-mobile #l4 td.ct.univer-app-panel-body ul.univer-app-list>li>a::before { content:''; width:16px; height:16px; background:currentColor; -webkit-mask:var(--univer-doc-icon) center/contain no-repeat; }

@media (hover:hover) {
 :root.univer-mobile .univer-app-unpin:hover { background:var(--u-surface-2,#f3f6f9)!important; color:var(--u-danger,#b42318)!important; outline:2px solid var(--u-brand,#1b5fa6); outline-offset:-2px; }
 :root.univer-mobile li:hover>.univer-app-pin { opacity:1; }
 :root.univer-mobile .univer-app-pin:hover { background:var(--u-brand-soft,#e9f1fb)!important; color:var(--u-brand,#1b5fa6)!important; outline:2px solid var(--u-brand,#1b5fa6); outline-offset:-2px; }
 :root.univer-mobile .univer-app-useful li:hover { border-color:var(--u-brand-line,#bfd3ea); box-shadow:var(--u-shadow); }
 :root.univer-mobile .univer-app-useful li>a:hover { color:var(--u-brand,#1b5fa6)!important; outline:none; }
 :root.univer-mobile .univer-app-ssc-nav a:hover { background:var(--u-surface-2,#f3f6f9); color:var(--u-ink,#16283b)!important; }
 :root.univer-mobile #l4 a.univer-app-desk-card:hover { border-color:var(--u-brand-line,#bfd3ea); }
 :root.univer-mobile .univer-app-credential-button:hover { background:var(--u-surface-2,#f3f6f9)!important; }
 :root.univer-mobile .univer-app-overview-link:hover { text-decoration:underline!important; }
 :root.univer-mobile .univer-app-overview-course-link:hover { text-decoration:underline; }
 :root.univer-mobile .univer-app-overview-more:hover { background:var(--u-brand-soft,#e9f1fb)!important; }
 :root.univer-mobile .univer-app-overview-request:hover { background:var(--u-brand-soft,#e9f1fb); }
 :root.univer-mobile .univer-app-tabs>a.univer-app-tab-icon:hover { background:var(--u-brand-soft,#e9f1fb)!important; color:var(--u-brand,#1b5fa6)!important; box-shadow:inset 0 0 0 1px var(--u-brand-line,#bfd3ea); }
 :root.univer-mobile .univer-app-tabs>a.univer-app-tab-icon.active:hover { background:var(--u-brand-strong,#154d88)!important; color:#fff!important; }
 :root.univer-mobile #l4 table.univer-app-request-table tr[id]:hover>td { background:var(--u-brand-soft,#e9f1fb)!important; }
 :root.univer-mobile #l4 td.ct.univer-app-panel-body ul.univer-app-list>li>a:hover { background:#dbe8f7!important; }
}
`;
})();
