/* Presentation layer on the reversible DOM adapter. Motion, tokens and component styles are shared with the Windows
   app (desktop.js), so change both; the final blocks are phone-specific. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz') return;
    // Feedback motion (window.__univerMotionUI) comes from univer-motion.js, loaded just before this file.
    if (!window.__univerStyleV1) {
        // Pin requests and a UMKD section's «Скачать все» cross to Android only through __univerNative, which
        // MobilePageAdapter gives the modules inside their closure with the channel key; the site never sees it.
        // A hardware keyboard cancels decorative movement without delaying focus or the action. The class is written
        // only when the input really switches: every write of <html class> starts an adapter pass, even an unchanged one.
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Tab' || /^Arrow|^(Home|End|Enter| )$/.test(event.key)) {
                var root = document.documentElement;
                if (!root.classList.contains('univer-app-keyboard')) root.classList.add('univer-app-keyboard');
                window.__univerMotionUI.stop();
            }
        }, true);
        document.addEventListener('pointerdown', function () {
            var root = document.documentElement;
            if (root.classList.contains('univer-app-keyboard')) root.classList.remove('univer-app-keyboard');
        }, true);
    }
    // Icons are inline SVG masks: no network requests and they follow the text colour.
    function icon(path, color) { return 'url("data:image/svg+xml,' + encodeURIComponent("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='" + (color || 'black') + "' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>" + path + '</svg>') + '")'; }
    var icons = {
        person: icon("<circle cx='12' cy='8' r='4'/><path d='M4 21c1.6-4 4.6-6 8-6s6.4 2 8 6'/>"),
        download: icon("<path d='M12 3v12'/><path d='M7 10l5 5 5-5'/><path d='M4 17v4h16v-4'/>"),
        downloadBrand: icon("<path d='M12 3v12'/><path d='M7 10l5 5 5-5'/><path d='M4 17v4h16v-4'/>", '#1b5fa6'),
        print: icon("<path d='M7 9V3h10v6'/><rect x='3' y='9' width='18' height='8' rx='2'/><path d='M7 14h10v7H7z'/>"),
        doc: icon("<path d='M14 3H6v18h12V7z'/><path d='M14 3v4h4'/><path d='M9 13h6M9 17h6'/>"),
        search: icon("<circle cx='11' cy='11' r='7'/><path d='M20 20l-3.5-3.5'/>", '#7a8a9a'),
        close: icon("<path d='M6 6l12 12M18 6L6 18'/>"),
        arrow: icon("<path d='M5 12h14'/><path d='M13 6l6 6-6 6'/>"),
        external: icon("<path d='M14 4h6v6'/><path d='M20 4l-9 9'/><path d='M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5'/>"),
        play: icon("<path d='M8 5v14l11-7z'/>"),
        folder: icon("<path d='M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'/>"),
        people: icon("<circle cx='9' cy='8' r='3.5'/><path d='M2.5 20c.9-3.4 3.4-5.5 6.5-5.5s5.6 2.1 6.5 5.5'/><path d='M16 4.6a3.5 3.5 0 0 1 0 6.8M18.5 14.8c1.5.9 2.5 2.6 3 5.2'/>"),
        chat: icon("<path d='M4 5h16v11H9l-5 4z'/><path d='M8 9.5h8M8 12.5h5'/>"),
        info: icon("<circle cx='12' cy='12' r='9'/><path d='M12 11v6M12 7.5v.5'/>"),
        note: icon("<circle cx='12' cy='12' r='9'/><path d='M12 7.5v5.5M12 16.5v.5'/>")
    };
    window.__univerStyleV1 = {
        apply: function (enabled) {
            function apply(doc) {
                // The app's animation setting turns feedback motion off; system reduction is handled by CSS.
                var still = window.__univerMotion === false;
                if (still || !enabled) window.__univerMotionUI.stop();
                if (doc.documentElement.classList.contains('univer-app-still') !== still) doc.documentElement.classList.toggle('univer-app-still', still);
                // The theme of every adapter stylesheet in this document; the dark class only while the layout is adapted.
                if (window.__univerPaletteV1) window.__univerPaletteV1.sync(doc, enabled);
                var style = doc.getElementById('univer-style');
                if (!enabled) {
                    doc.documentElement.classList.remove('univer-app-keyboard', 'univer-app-still');
                    if (style) style.remove(); return;
                }
                if (style) return;
                style = doc.createElement('style'); style.id = 'univer-style';
                // ":root.univer-mobile" outranks the adapter's "html.univer-mobile" rules without rewriting them;
                // the phone block uses "html:root" to outrank the shared layer in turn.
                var source = `
:root { color-scheme:light; --univer-arrow-icon:${icons.arrow}; --univer-external-icon:${icons.external}; --univer-play-icon:${icons.play};
  --univer-folder-icon:${icons.folder}; --univer-people-icon:${icons.people}; --univer-chat-icon:${icons.chat}; --univer-info-icon:${icons.info}; }
:root.univer-mobile {
  --u-brand:#1b5fa6; --u-brand-strong:#154d88; --u-brand-soft:#e9f1fb; --u-brand-line:#bfd3ea;
  --u-ink:#16283b; --u-text:#2a3d51; --u-muted:#5b6b7c; --u-line:#dfe5ec; --u-line-strong:#c8d3df;
  --u-bg:#f1f4f7; --u-surface:#fff; --u-surface-2:#f7f9fb;
  --u-teal:#0f766e; --u-teal-soft:#e3f3ef; --u-danger:#b42318; --u-danger-soft:#fdeceb;
  --u-band-low:#d77a70; --u-band-low-ink:#a2453d; --u-band-low-soft:#fbf3f2; --u-band-pass:#cc9a4c; --u-band-pass-ink:#87591a; --u-band-pass-soft:#faf5ec;
  --u-band-good:#5fa67f; --u-band-good-ink:#2e6f4c; --u-band-good-soft:#eef6f1; --u-band-top:#6b97cc; --u-band-top-ink:#30629c; --u-band-top-soft:#eef3f9;
  --u-shadow:none;
  --u-shadow-pop:0 12px 36px rgba(16,40,70,.12),0 2px 6px rgba(16,40,70,.06);
  --u-font:Roboto,'Noto Sans','Segoe UI',system-ui,sans-serif;
  --u-ease-out:cubic-bezier(.23,1,.32,1); --u-press:140ms; --u-release:100ms;
  /* Phone type scale: captions only for short labels, secondary text from 14px, running text 16px. */
  --u-fs-caption:12px; --u-fs-meta:14px; --u-fs-body:15px; --u-fs-strong:16px; --u-fs-section:17px; --u-fs-title:20px; --u-fs-display:24px;
  --u-header:52px; --u-page:1400px;
  background:var(--u-bg); scroll-padding-top:calc(var(--u-header) + 16px);
}
:root.univer-mobile body { font:16px/1.55 var(--u-font)!important; background:var(--u-bg)!important; color:var(--u-text)!important; }
:root.univer-mobile :is(td,th,p,li,label,h1,h2,h3,h4,h5,h6,div,span,a,input,select,textarea,button,summary,b,strong,font) { font-family:var(--u-font)!important; }
:root.univer-mobile :is(td,th,p,li,label) { line-height:1.55!important; }
:root.univer-mobile a { color:var(--u-brand); text-underline-offset:3px; text-decoration-thickness:1px; }
:root.univer-mobile :is(input,select,textarea,button,a,summary,[tabindex]):focus-visible { outline:2px solid var(--u-brand)!important; outline-offset:2px!important; }
:root.univer-mobile input:is([type=checkbox],[type=radio]) { accent-color:var(--u-brand); }
:root.univer-mobile :is(input[type=text],input[type=password],input[type=number],input[type=search],input[type=date],input[type=email],input[type=tel],input:not([type]),select,textarea) { min-height:40px; padding:8px 12px!important; border:1px solid var(--u-line-strong)!important; border-radius:8px!important; background-color:#fff; color:var(--u-ink); box-sizing:border-box!important; font-size:15px!important; }
:root.univer-mobile :is(input,select,textarea):not([type=checkbox]):not([type=radio]):focus { outline:none!important; border-color:var(--u-brand)!important; box-shadow:0 0 0 3px rgba(27,95,166,.16)!important; }
:root.univer-mobile :is(input,textarea)[readonly] { background-color:var(--u-surface-2); color:var(--u-muted); }
:root.univer-mobile :is(button,input[type=submit],input[type=button],summary) { cursor:pointer; }
:root.univer-mobile :is(button,input[type=submit],input[type=button]):disabled { cursor:default; opacity:.5; }
:root.univer-mobile details>summary { list-style:none; }
:root.univer-mobile details>summary::-webkit-details-marker { display:none; }
:root.univer-mobile .univer-app-shell { max-width:none!important; }
:root.univer-mobile .univer-app-menu-return { display:none!important; }

/* One compact sticky bar: brand, real site tabs, all sections, language flags and account. */
:root.univer-mobile #l1 { height:auto!important; }
:root.univer-mobile #l1 .header { position:sticky; top:0; z-index:800; background:#fff!important; color:var(--u-ink)!important; padding:0 24px!important; border-bottom:1px solid var(--u-line)!important; box-shadow:none; height:auto!important; }
:root.univer-mobile .univer-app-header { display:flex!important; align-items:center; flex-wrap:wrap; gap:8px 16px; min-height:var(--u-header)!important; max-width:var(--u-page); margin:0 auto; }
:root.univer-mobile .univer-app-brand { display:flex; align-items:center; flex:0 0 auto; }
:root.univer-mobile .univer-app-brand:empty { display:none; }
:root.univer-mobile .univer-app-brand #logo { width:112px!important; height:34px!important; margin:0!important; float:none!important; background-size:contain!important; background-repeat:no-repeat!important; background-position:left center!important; }
:root.univer-mobile .univer-app-tabs { display:flex; align-items:center; gap:4px; flex:1 1 auto; min-width:0; }
:root.univer-mobile .univer-app-tabs:empty { display:none; }
:root.univer-mobile .univer-app-tabs>a { display:inline-flex!important; align-items:center; height:36px; min-height:0!important; padding:0 14px!important; margin:0!important; border:0!important; border-radius:8px!important; background:none!important; color:var(--u-muted)!important; font-size:15px!important; font-weight:600!important; text-decoration:none!important; white-space:nowrap; float:none!important; width:auto!important; flex:0 0 auto!important; }
:root.univer-mobile .univer-app-tabs>a.active { background:var(--u-brand)!important; color:#fff!important; }
:root.univer-mobile .univer-app-tabs>a.active * { color:inherit!important; }
:root.univer-mobile .univer-app-tabs>a>:is(.left,.right) { display:none!important; }
:root.univer-mobile .univer-app-tabs>a>.center { display:inline!important; float:none!important; height:auto!important; padding:0!important; background:none!important; color:inherit!important; font:inherit!important; line-height:inherit!important; }
/* The structural adapter's #l1 header rule forces inherited colours with !important. Match its ID scope. */
:root.univer-mobile #l1 .header .univer-app-tabs>a { color:var(--u-muted)!important; }
:root.univer-mobile #l1 .header .univer-app-tabs>a.active { color:#fff!important; }
:root.univer-mobile #l1 .header .univer-app-tabs>a.active * { color:inherit!important; }
:root.univer-mobile .univer-app-actions { display:flex; align-items:center; gap:10px; margin-left:auto; flex:0 0 auto; }
:root.univer-mobile .univer-app-header>.univer-app-university { flex:0 1 auto; order:5; }
:root.univer-mobile #l1 .univer-app-sections { display:inline-flex!important; align-items:center; gap:8px; height:36px; min-height:0!important; box-sizing:border-box; padding:0 14px 0 12px!important; margin:0!important; border:1px solid var(--u-line-strong)!important; border-radius:8px!important; background:#fff!important; color:var(--u-ink)!important; font-size:15px!important; font-weight:600!important; white-space:nowrap; }
:root.univer-mobile #l1 .univer-app-sections::before { content:''; width:16px; height:12px; background:linear-gradient(currentColor,currentColor) top/100% 2px no-repeat,linear-gradient(currentColor,currentColor) center/100% 2px no-repeat,linear-gradient(currentColor,currentColor) bottom/100% 2px no-repeat; }
:root.univer-mobile #l1 .univer-app-sections[hidden] { display:none!important; }
:root.univer-mobile #l1 .univer-app-flags { display:flex!important; align-items:center; gap:2px; padding:3px; border:1px solid var(--u-line); border-radius:10px; background:var(--u-surface-2); }
:root.univer-mobile #l1 .univer-app-flags>:is(a,img) { box-sizing:border-box; width:34px!important; height:28px!important; min-height:0!important; padding:0!important; margin:0!important; display:inline-flex!important; align-items:center; justify-content:center; border:0!important; border-radius:7px!important; }
:root.univer-mobile #l1 .univer-app-flags>a img { width:20px!important; min-width:0!important; height:auto!important; opacity:.7; border:0!important; }
:root.univer-mobile #l1 .univer-app-flags>img { padding:6px 7px!important; object-fit:contain!important; background:#fff; outline:1.5px solid var(--u-brand); outline-offset:-1.5px; }
:root.univer-mobile .univer-app-actions .links { position:relative; display:flex!important; align-items:center; gap:8px; width:auto!important; min-width:0!important; max-width:none!important; padding:0!important; margin:0!important; float:none!important; text-align:start!important; font-size:15px!important; white-space:nowrap!important; }
:root.univer-mobile .univer-app-actions .links>a { display:inline-flex!important; align-items:center; height:36px; padding:0 10px!important; border-radius:8px; color:var(--u-muted)!important; font-size:15px!important; font-weight:600; text-decoration:none!important; }
:root.univer-mobile .univer-app-actions .univer-app-account { position:relative; }
:root.univer-mobile .univer-app-actions .univer-app-account>summary { display:inline-flex!important; align-items:center; gap:8px; height:36px; min-height:0!important; box-sizing:border-box; padding:0 12px!important; border:1px solid var(--u-line-strong)!important; border-radius:8px!important; background:#fff!important; color:var(--u-ink)!important; font-size:15px!important; font-weight:600!important; }
:root.univer-mobile .univer-app-actions .univer-app-account>summary::before { content:''; width:18px; height:18px; background:currentColor; -webkit-mask:${icons.person} center/contain no-repeat; opacity:.75; }
:root.univer-mobile .univer-app-actions .univer-app-account>summary::after { content:''; width:6px; height:6px; margin:-3px 0 0 2px; border-right:2px solid currentColor; border-bottom:2px solid currentColor; transform:rotate(45deg); opacity:.55; }
:root.univer-mobile .univer-app-actions .univer-app-account[open]>div { position:absolute; right:0; top:calc(100% + 8px); z-index:850; box-sizing:border-box; width:max-content; min-width:260px; max-width:min(380px,calc(100vw - 32px)); padding:8px!important; background:#fff; border:1px solid var(--u-line); border-radius:12px; box-shadow:var(--u-shadow-pop); white-space:normal; }
:root.univer-mobile .univer-app-actions .univer-app-account>div a { display:block!important; min-height:0!important; line-height:1.45!important; padding:6px 12px!important; margin:0!important; border-radius:8px; color:var(--u-ink)!important; font-size:15px!important; font-weight:500; text-decoration:none!important; float:none!important; }
:root.univer-mobile .univer-app-actions .univer-app-account>div a :is(span,div,b,strong,font) { display:inline!important; float:none!important; width:auto!important; color:inherit!important; }
:root.univer-mobile .univer-app-actions .univer-app-account>div a[href*="/user/pass"] { font-weight:600!important; }
:root.univer-mobile .univer-app-actions .univer-app-account>div a[href*="/user/logout"] * { color:inherit!important; }
:root.univer-mobile .univer-app-actions .univer-app-account>div a[href*="/user/logout"] { color:var(--u-danger)!important; border-top:1px solid var(--u-line); border-radius:0 0 8px 8px; margin-top:4px!important; }
:root.univer-mobile .univer-app-academic-meta { list-style:none; display:flex; flex-wrap:wrap; gap:6px; padding:8px 12px 4px!important; margin:4px 0 0!important; border-top:1px solid var(--u-line); }
:root.univer-mobile .univer-app-academic-meta li { padding:2px 8px!important; border-radius:999px; background:var(--u-surface-2); color:var(--u-muted); font-size:12px!important; }
:root.univer-mobile.univer-app-unified :is(#l2,#l3).univer-app-header-row { display:none!important; }

/* "All sections" opens as a floating panel instead of pushing the page down. */
:root.univer-mobile .univer-app-menu { scroll-margin-top:calc(var(--u-header) + 12px); }
:root.univer-mobile.univer-app-unified .univer-app-menu:not([open]) { display:none!important; }
:root.univer-mobile.univer-app-unified .univer-app-menu[open] { position:fixed!important; z-index:900; top:calc(var(--u-header) + 8px); left:24px; right:24px; max-width:1200px!important; margin:0 auto!important; box-sizing:border-box; max-height:calc(100vh - var(--u-header) - 24px); overflow:auto; padding:16px 16px 20px!important; background:#fff!important; border:1px solid var(--u-line)!important; border-radius:14px!important; box-shadow:var(--u-shadow-pop); }
:root.univer-mobile.univer-app-unified .univer-app-menu>summary { display:none!important; }
:root.univer-mobile .univer-app-menu-close { display:none; }
:root.univer-mobile.univer-app-unified .univer-app-menu-close { display:block; position:absolute; top:16px; right:16px; width:40px!important; height:40px!important; min-height:0!important; padding:0!important; border:1px solid var(--u-line)!important; border-radius:8px!important; background:#fff!important; color:var(--u-muted)!important; font-size:0!important; }
:root.univer-mobile.univer-app-unified .univer-app-menu-close { display:grid; place-items:center; }
:root.univer-mobile .univer-app-menu-close::before { content:''; width:16px; height:16px; background:currentColor; -webkit-mask:${icons.close} center/contain no-repeat; }
:root.univer-mobile .univer-app-menu input[type=search] { display:block; width:100%!important; height:40px; margin:0 0 14px!important; padding-left:38px!important; background:#fff ${icons.search} 12px center/18px no-repeat!important; }
:root.univer-mobile.univer-app-unified .univer-app-menu input[type=search] { width:calc(100% - 52px)!important; }
:root.univer-mobile .univer-app-menu .univer-app-empty { margin:8px 4px; color:var(--u-muted); }
:root.univer-mobile #tools { width:100%!important; background:none!important; border:0!important; }
:root.univer-mobile #tools>tbody>tr>td.center { display:grid!important; grid-template-columns:repeat(auto-fill,minmax(250px,1fr)); gap:12px; align-items:start; padding:0!important; background:none!important; }
:root.univer-mobile .univer-app-category { margin:0!important; overflow:hidden; background:#fff!important; border:1px solid var(--u-line)!important; border-radius:10px!important; }
:root.univer-mobile .univer-app-category>summary { padding:10px 14px!important; min-height:0!important; background:var(--u-surface-2); border-bottom:1px solid var(--u-line); color:var(--u-ink)!important; font-size:14px!important; font-weight:600!important; }
:root.univer-mobile #tools li { border:0!important; }
:root.univer-mobile #tools li a { display:block; padding:7px 14px!important; color:var(--u-ink)!important; font-size:15px!important; line-height:1.45!important; text-decoration:none; outline:none!important; }
:root.univer-mobile #tools li a:focus-visible { background:var(--u-brand-soft)!important; box-shadow:inset 0 0 0 2px var(--u-brand); }
:root.univer-mobile #tools a[aria-current=page] { background:var(--u-brand-soft)!important; color:var(--u-brand)!important; font-weight:600!important; box-shadow:inset 3px 0 0 var(--u-brand)!important; border-left:0!important; }

/* Page body. */
:root.univer-mobile #l4 td.center { padding:8px 32px 40px!important; background:transparent!important; }
:root.univer-mobile #l4 td.center>*, :root.univer-mobile #l5 td.center>* { max-width:var(--u-page); margin-left:auto!important; margin-right:auto!important; box-sizing:border-box; }
:root.univer-mobile table.mt { background:transparent!important; border:0!important; }
:root.univer-mobile #l4 table.mt>tbody>tr.top>:is(.ct,.tt) { padding:16px 0 8px!important; background:none!important; color:var(--u-ink)!important; font-size:var(--u-fs-title)!important; font-weight:600!important; line-height:1.35!important; letter-spacing:-.005em; }
:root.univer-mobile #l4 td.center>:is(table.mt:first-of-type,table.univer-app-empty-panel+table.mt):not(.univer-app-panel)>tbody>tr.top:first-child>:is(.ct,.tt) { padding-top:0!important; }
:root.univer-mobile #l4 table.mt>tbody>tr>.tt { padding:4px 0 8px!important; }
:root.univer-mobile #l4 .univer-app-study-panel .ct.message { font-size:var(--u-fs-meta)!important; color:var(--u-muted)!important; }
:root.univer-mobile #l4 :is(h1,h2,h3) { color:var(--u-ink); line-height:1.3; letter-spacing:-.015em; }
:root.univer-mobile #l4 h4 { margin:12px 0 8px!important; padding:0!important; border:0!important; background:none!important; color:var(--u-ink)!important; font-size:var(--u-fs-meta)!important; font-weight:600!important; text-align:start!important; }
:root.univer-mobile .univer-app-nav-table>tbody>tr { gap:0!important; padding:0!important; border-bottom:1px solid var(--u-line); }
:root.univer-mobile .univer-app-nav-table>tbody>tr:last-child { border-bottom:0; }
:root.univer-mobile .univer-app-table-scroll .univer-app-nav-table>tbody>tr>td { border:0!important; padding:12px 14px!important; }
:root.univer-mobile table.univer-app-empty-panel, :root.univer-mobile .univer-app-study-panel>tbody>tr>.univer-app-secondary-heading { display:none!important; }

/* Site links that act as navigation are chips, not text boxes. */
:root.univer-mobile #l4 .ts { display:flex!important; flex-wrap:wrap; align-items:center; gap:8px; height:auto!important; min-height:0!important; padding:4px 0 12px!important; overflow:visible!important; }
:root.univer-mobile #l4 :is(.ts>a,a.univer-app-page-link,.univer-app-semester-nav a,.univer-app-nav-table a) { display:inline-flex!important; align-items:center; gap:8px; box-sizing:border-box; width:auto!important; max-width:100%; min-height:38px!important; flex:0 1 auto!important; float:none!important; margin:0!important; padding:7px 14px!important; border:1px solid var(--u-line)!important; border-radius:10px!important; background:#fff!important; box-shadow:var(--u-shadow); color:var(--u-brand)!important; font-size:15px!important; font-weight:600!important; line-height:1.35!important; text-align:start!important; text-decoration:none!important; }
:root.univer-mobile #l4 .ts>a>img:is([src*="tb_left"],[src*="tb_right"],[src$="/left.png"],[src$="/right.png"]) { display:none!important; }
:root.univer-mobile #l4 .ts>a>img { width:16px; height:auto; }
:root.univer-mobile #l4 .ts>a:has(>img:is([src*="tb_left"],[src*="tb_right"],[src$="/left.png"],[src$="/right.png"])) { color:var(--u-muted)!important; font-weight:500!important; }
:root.univer-mobile #l4 .ts>a:has(>img:is([src*="tb_right"],[src$="/right.png"])) { margin-left:auto!important; }
:root.univer-mobile #l4 .ts>a:has(>img:is([src*="tb_left"],[src$="/left.png"]))::before { content:'←'; font-weight:600; }
:root.univer-mobile #l4 .ts>a:has(>img:is([src*="tb_right"],[src$="/right.png"]))::after { content:'→'; font-weight:600; }
:root.univer-mobile #l4 .ct>a.univer-app-page-link:only-child { min-height:0!important; padding:0!important; border:0!important; background:none!important; box-shadow:none!important; color:var(--u-ink)!important; font-size:20px!important; font-weight:600!important; }
:root.univer-mobile #l4 .ts>a.syllabus-info::before { content:''; width:16px; height:16px; flex:0 0 auto; background:currentColor; -webkit-mask:${icons.doc} center/contain no-repeat; }
:root.univer-mobile #l4 .univer-app-breadcrumb { display:flex!important; flex-wrap:wrap; align-items:center; gap:4px 8px; padding:4px 0 8px!important; }
:root.univer-mobile #l4 .univer-app-breadcrumb>a.univer-app-page-link { min-height:0!important; padding:0!important; border:0!important; background:none!important; box-shadow:none!important; color:var(--u-muted)!important; font-size:15px!important; font-weight:500!important; }
:root.univer-mobile #l4 .univer-app-breadcrumb>a.univer-app-page-link:not(:last-of-type)::after { content:'/'; margin-left:8px; color:var(--u-line-strong); }
:root.univer-mobile #l4 .univer-app-breadcrumb>a.univer-app-page-link:last-of-type { flex-basis:100%; color:var(--u-ink)!important; font-size:20px!important; font-weight:600!important; }
:root.univer-mobile .univer-app-quick { max-width:var(--u-page); margin:16px 32px 4px!important; display:grid!important; grid-template-columns:repeat(auto-fill,minmax(200px,1fr)); gap:10px; }
:root.univer-mobile .univer-app-quick a { display:flex!important; align-items:center; min-height:56px; box-sizing:border-box; padding:12px 16px!important; border:0!important; border-radius:8px!important; background:#fff!important; box-shadow:none; color:var(--u-ink)!important; font-size:15px!important; font-weight:600; text-align:start!important; justify-content:flex-start!important; text-decoration:none; }
:root.univer-mobile .univer-app-quick a::after { content:'→'; margin-left:auto; padding-left:12px; color:var(--u-brand); }

/* Buttons that the adapter adds, and the site's own content buttons. */
:root.univer-mobile #l4 :is(input[type=button],input[type=submit],button):not(.univer-app-days button):not(.univer-app-schedule-modes button) { border-radius:10px!important; font-weight:600; }
:root.univer-mobile .univer-app-course-open, :root.univer-mobile .univer-app-catalog-download { display:inline-flex!important; align-items:center; justify-content:center; gap:6px; width:auto!important; min-width:96px; min-height:34px!important; margin:0!important; padding:6px 14px!important; border:0!important; border-radius:8px!important; background:var(--u-brand)!important; color:#fff!important; font-size:var(--u-fs-body)!important; font-weight:600!important; cursor:pointer; }
:root.univer-mobile .univer-app-catalog-download::before { content:''; width:14px; height:14px; background:currentColor; -webkit-mask:${icons.download} center/contain no-repeat; }
:root.univer-mobile a.univer-app-action-link { display:inline-flex!important; align-items:center; min-height:32px!important; padding:5px 12px!important; border:1px solid var(--u-brand-line); border-radius:8px; background:var(--u-brand-soft); color:var(--u-brand)!important; font-size:14px!important; font-weight:600; white-space:nowrap; text-decoration:none; }

/* Tables. */
:root.univer-mobile .univer-app-table-scroll { margin:6px 0 16px!important; overflow:auto; background:#fff!important; border:1px solid var(--u-line)!important; border-radius:12px!important; box-shadow:var(--u-shadow); scrollbar-color:#a9b8c8 #eef2f6; }
:root.univer-mobile .univer-app-table-scroll>table { border:0!important; background:none!important; margin:0!important; }
:root.univer-mobile .univer-app-table-scroll table { border-collapse:collapse!important; border-spacing:0!important; }
:root.univer-mobile .univer-app-table-scroll :is(th,td) { border:0!important; border-bottom:1px solid var(--u-line)!important; padding:10px 14px!important; vertical-align:middle!important; font-size:15px!important; }
:root.univer-mobile .univer-app-table-scroll tr:last-child>td { border-bottom:0!important; }
:root.univer-mobile .univer-app-table-scroll th { background:var(--u-surface-2)!important; color:var(--u-muted)!important; font-size:14px!important; font-weight:600!important; text-align:start; }
:root.univer-mobile .univer-app-scroll-hint { margin:8px 0 -2px!important; font-size:var(--u-fs-meta)!important; color:var(--u-muted)!important; }
:root.univer-mobile .univer-app-matrix { table-layout:auto!important; }
:root.univer-mobile .univer-app-table-scroll .univer-app-matrix :is(th,td) { white-space:normal!important; text-align:center!important; border-right:1px solid #edf1f5!important; }
:root.univer-mobile .univer-app-table-scroll .univer-app-matrix tr>:first-child { text-align:start!important; }
:root.univer-mobile .univer-app-table-scroll .univer-app-matrix tr>:last-child { border-right:0!important; font-weight:600; }
/* Empty results are one quiet line: no tall placeholder and no column headings over nothing. */
:root.univer-mobile #l4 tr.univer-app-empty-row>:is(td,th) { padding:12px 16px!important; background:var(--u-surface-2)!important; color:var(--u-muted)!important; font-size:var(--u-fs-meta)!important; font-weight:500!important; text-align:start!important; }
:root.univer-mobile #l4 tr.univer-app-empty-row * { color:inherit!important; font-weight:inherit!important; font-size:inherit!important; }
:root.univer-mobile #l4 .univer-app-table-scroll>table:has(tr.univer-app-empty-row):not(:has(tr:not(.univer-app-empty-row)>td)) tr:has(>th) { display:none!important; }
:root.univer-mobile .univer-app-table-scroll:has(>table tr.univer-app-empty-row):not(:has(tr:not(.univer-app-empty-row)>td)) { box-shadow:none; border-style:dashed!important; }
:root.univer-mobile .univer-app-table-scroll>table { height:auto!important; }
:root.univer-mobile table.univer-app-empty-note { width:100%!important; margin:6px 0 12px; border-collapse:separate!important; background:none!important; border:0!important; }
:root.univer-mobile table.univer-app-empty-note td { padding:12px 16px!important; border:1px dashed var(--u-line-strong)!important; border-radius:10px; background:var(--u-surface-2)!important; color:var(--u-muted)!important; font-size:var(--u-fs-meta)!important; font-weight:500!important; text-align:start!important; }
:root.univer-mobile table.univer-app-empty-note td :is(b,strong,font,span,p) { color:inherit!important; font-weight:inherit!important; font-size:inherit!important; }
:root.univer-mobile .univer-app-card-row { border-radius:12px!important; border-color:var(--u-line)!important; box-shadow:var(--u-shadow); }
:root.univer-mobile #l4 .univer-app-form-table>tbody>tr.univer-app-form-spacer { display:none!important; }
:root.univer-mobile #l4 .univer-app-form-table:not(.univer-app-form-table .univer-app-form-table) { box-sizing:border-box; padding:4px 16px!important; background:#fff!important; border:1px solid var(--u-line)!important; border-radius:12px!important; box-shadow:var(--u-shadow); }
:root.univer-mobile #l4 .univer-app-form-table:not(.univer-app-form-table .univer-app-form-table)>tbody>tr { border-bottom:1px solid var(--u-line)!important; background:none!important; }
:root.univer-mobile #l4 .univer-app-form-table:not(.univer-app-form-table .univer-app-form-table)>tbody>tr:last-child { border-bottom:0!important; }
:root.univer-mobile #l4 .univer-app-form-table>tbody>tr>:is(td,th) { background:none!important; }
:root.univer-mobile #l4 .univer-app-form-table li { list-style:none!important; margin:0!important; padding:0!important; color:var(--u-muted); font-size:14px!important; font-weight:600; }
:root.univer-mobile #l4 .univer-app-sort-head>th { cursor:pointer; }
:root.univer-mobile .univer-app-sort-toggle { border-radius:8px!important; }
:root.univer-mobile .univer-app-pairs>tbody>tr { border-radius:12px!important; border-color:var(--u-line)!important; box-shadow:var(--u-shadow); background:#fff!important; }
:root.univer-mobile .univer-app-pairs>tbody>tr>td:nth-child(odd) { color:var(--u-muted)!important; font-size:14px!important; font-weight:600; }

/* УМКД: course list and material files. */
:root.univer-mobile .univer-app-courses tr.link { cursor:default; }
:root.univer-mobile .univer-app-courses tr.link>td:nth-child(2) { color:var(--u-ink)!important; font-weight:600; }
:root.univer-mobile .univer-app-courses tr.link>td:last-child { color:var(--u-muted); font-size:14px!important; }
:root.univer-mobile .univer-app-courses tr.link>td:last-child .univer-app-course-open { margin-left:12px!important; vertical-align:middle; }
:root.univer-mobile input[type=file] { box-sizing:border-box; max-width:100%; min-height:44px; padding:6px!important; border:1px dashed var(--u-line-strong)!important; border-radius:10px!important; background:var(--u-surface-2)!important; color:var(--u-muted); font-size:14px!important; }
:root.univer-mobile input[type=file]::file-selector-button { margin-right:12px; padding:7px 14px; border:1px solid var(--u-brand-line); border-radius:8px; background:#fff; color:var(--u-brand); font:600 14px var(--u-font); cursor:pointer; }
:root.univer-mobile .univer-app-materials>tbody>tr>th:only-of-type { padding:14px 14px 8px!important; background:#fff!important; color:var(--u-brand)!important; font-size:14px!important; font-weight:600!important; letter-spacing:.02em; text-align:start!important; }
:root.univer-mobile .univer-app-materials a.downLoad { display:inline-flex!important; align-items:center; gap:8px; min-height:32px!important; padding:5px 12px!important; border:1px solid var(--u-brand-line)!important; border-radius:8px!important; background:var(--u-brand-soft)!important; color:var(--u-brand)!important; font-size:var(--u-fs-body)!important; font-weight:600!important; text-decoration:none!important; overflow-wrap:anywhere; }
:root.univer-mobile .univer-app-materials a.downLoad::before { content:''; width:15px; height:15px; flex:0 0 auto; background:currentColor; -webkit-mask:${icons.download} center/contain no-repeat; }
:root.univer-mobile :is(.univer-app-courses,.univer-app-materials) td { overflow-wrap:break-word!important; }

/* Schedule: a day agenda at every width, plus the unchanged source grid on request. */
:root.univer-mobile .univer-app-schedule { display:block!important; width:100%; }
:root.univer-mobile .univer-app-schedule-modes { display:flex; gap:2px; margin:4px 0 12px; padding:3px; width:max-content; max-width:100%; border:1px solid var(--u-line); border-radius:10px; background:#e9eef4; }
:root.univer-mobile #l4 .univer-app-schedule-modes button { min-height:34px!important; padding:6px 16px!important; border:0!important; border-radius:8px!important; background:transparent!important; color:var(--u-muted)!important; font-size:15px!important; font-weight:600!important; }
:root.univer-mobile #l4 .univer-app-schedule-modes button[aria-pressed=true] { background:#fff!important; color:var(--u-brand)!important; box-shadow:0 1px 3px rgba(16,40,70,.12); }
:root.univer-mobile .univer-app-days { display:flex!important; flex-wrap:wrap; gap:6px; margin:4px 0 16px!important; }
:root.univer-mobile #l4 .univer-app-days button { display:inline-flex; align-items:center; gap:8px; min-height:36px!important; padding:6px 12px!important; border:1px solid var(--u-line)!important; border-radius:6px!important; background:#fff!important; color:var(--u-ink)!important; font-size:15px!important; font-weight:500!important; }
:root.univer-mobile #l4 .univer-app-days button[aria-pressed=true] { background:var(--u-brand)!important; border-color:var(--u-brand)!important; color:#fff!important; }
:root.univer-mobile .univer-app-days button:last-child { order:-1; }
:root.univer-mobile .univer-app-day-count { min-width:20px; padding:0 6px; box-sizing:border-box; border-radius:999px; background:var(--u-surface-2); color:var(--u-muted); font-size:var(--u-fs-caption)!important; font-weight:600; line-height:20px; text-align:center; }
:root.univer-mobile .univer-app-days button[aria-pressed=true] .univer-app-day-count { background:rgba(255,255,255,.22); color:#fff; }
/* All days: up to six columns, so a full-width window shows the week in one row; narrower windows reflow. */
:root.univer-mobile .univer-app-schedule[data-all-days=true] { display:grid!important; grid-template-columns:repeat(auto-fill,minmax(220px,1fr)); gap:12px; align-items:start; }
:root.univer-mobile .univer-app-schedule-modes, :root.univer-mobile .univer-app-days { grid-column:1/-1; }
:root.univer-mobile .univer-app-schedule[data-all-days=true] .univer-app-days { margin:0!important; }
:root.univer-mobile .univer-app-day { min-width:0; overflow:hidden; background:#fff; border:1px solid var(--u-line); border-radius:12px; box-shadow:var(--u-shadow); }
:root.univer-mobile .univer-app-day h3 { margin:0!important; padding:10px 14px!important; background:var(--u-surface-2); border-bottom:1px solid var(--u-line); color:var(--u-ink)!important; font-size:var(--u-fs-strong)!important; font-weight:600; }
:root.univer-mobile .univer-app-lesson { margin:0!important; padding:12px 14px!important; background:#fff!important; border:0!important; border-bottom:1px solid #edf1f5!important; border-radius:0!important; }
:root.univer-mobile .univer-app-lesson:last-child { border-bottom:0!important; }
:root.univer-mobile .univer-app-lesson time { display:inline-block; padding:2px 8px!important; border-radius:6px!important; background:var(--u-teal-soft)!important; color:var(--u-teal)!important; font-size:var(--u-fs-meta)!important; font-weight:600!important; font-variant-numeric:tabular-nums; }
:root.univer-mobile .univer-app-lesson p { margin:4px 0 0!important; font-size:var(--u-fs-body)!important; line-height:1.45!important; color:var(--u-muted); }
:root.univer-mobile .univer-app-lesson p.univer-app-subject { margin-top:6px!important; color:var(--u-ink)!important; font-size:var(--u-fs-strong)!important; font-weight:600!important; line-height:1.35!important; }
:root.univer-mobile .univer-app-lesson a.univer-app-page-link { margin-top:8px!important; min-height:32px!important; padding:4px 10px!important; font-size:var(--u-fs-meta)!important; box-shadow:none; }
:root.univer-mobile .univer-app-no-classes { margin:0!important; padding:12px 14px!important; background:transparent!important; border:0!important; color:var(--u-muted)!important; font-size:var(--u-fs-meta)!important; }
/* A free day in All days is one compact line instead of a tall empty card. */
:root.univer-mobile .univer-app-schedule[data-all-days=true] .univer-app-day:has(>.univer-app-no-classes) { display:flex; align-items:center; flex-wrap:wrap; background:var(--u-surface-2); box-shadow:none; }
:root.univer-mobile .univer-app-schedule[data-all-days=true] .univer-app-day:has(>.univer-app-no-classes) h3 { flex:1 1 auto; border-bottom:0; background:none; color:var(--u-muted)!important; }
:root.univer-mobile .univer-app-schedule[data-all-days=true] .univer-app-day:has(>.univer-app-no-classes)>.univer-app-no-classes { flex:0 0 auto; padding:10px 14px 10px 0!important; }
/* Current and next classes of today. */
:root.univer-mobile .univer-app-lesson.univer-app-lesson-now { background:var(--u-brand-soft)!important; box-shadow:inset 3px 0 0 var(--u-brand); }
:root.univer-mobile .univer-app-lesson.univer-app-lesson-next { box-shadow:inset 3px 0 0 var(--u-brand-line); }
:root.univer-mobile .univer-app-lesson-badge { display:inline-block; margin-left:8px; padding:1px 8px; border-radius:999px; background:var(--u-brand); color:#fff; font-size:var(--u-fs-caption); font-weight:600; vertical-align:1px; }
:root.univer-mobile .univer-app-lesson-next .univer-app-lesson-badge { background:var(--u-surface-2); color:var(--u-brand); box-shadow:inset 0 0 0 1px var(--u-brand-line); }
:root.univer-mobile .univer-app-lesson.univer-app-lesson-done>:not(.univer-app-lesson-badge) { opacity:.55; }
:root.univer-mobile .univer-app-lesson-done .univer-app-lesson-badge { background:var(--u-surface-2); color:var(--u-muted); }
:root.univer-mobile .univer-app-day h3 .univer-app-day-range { margin-left:8px; color:var(--u-muted); font-size:var(--u-fs-meta); font-weight:500; font-variant-numeric:tabular-nums; }
/* Summary of the day: class in progress, break or countdown to the next class. */
:root.univer-mobile .univer-app-now { grid-column:1/-1; margin:0 0 12px; padding:14px 16px; background:#fff; border:1px solid var(--u-line); border-radius:12px; box-shadow:var(--u-shadow); }
:root.univer-mobile .univer-app-now[hidden], :root.univer-mobile .univer-app-now [hidden] { display:none!important; }
:root.univer-mobile .univer-app-schedule[data-mode=grid]>.univer-app-now { display:none!important; }
:root.univer-mobile .univer-app-now[data-state=now] { border-left-color:var(--u-brand); background:var(--u-brand-soft); }
:root.univer-mobile .univer-app-now:is([data-state=pause],[data-state=gap],[data-state=first]) { border-left-color:var(--u-teal); }
:root.univer-mobile .univer-app-now-head { display:flex; align-items:baseline; justify-content:space-between; gap:12px; flex-wrap:wrap; }
:root.univer-mobile .univer-app-now-title { color:var(--u-muted); font-size:var(--u-fs-meta); font-weight:600; }
:root.univer-mobile .univer-app-now[data-state=now] .univer-app-now-title { color:var(--u-brand); }
:root.univer-mobile .univer-app-now-count { color:var(--u-ink); font-size:var(--u-fs-title); font-weight:600; font-variant-numeric:tabular-nums; }
:root.univer-mobile .univer-app-now[data-state=now] .univer-app-now-count { color:var(--u-brand); }
:root.univer-mobile .univer-app-now:is([data-state=pause],[data-state=gap],[data-state=first]) .univer-app-now-count { color:var(--u-teal); }
:root.univer-mobile #l4 .univer-app-now p { margin:6px 0 0!important; line-height:1.4!important; }
:root.univer-mobile #l4 .univer-app-now p.univer-app-now-subject { color:var(--u-ink)!important; font-size:var(--u-fs-section)!important; font-weight:600!important; }
:root.univer-mobile #l4 .univer-app-now p.univer-app-now-meta { color:var(--u-muted)!important; font-size:var(--u-fs-body)!important; }
:root.univer-mobile .univer-app-now-progress { height:6px; margin-top:10px; border-radius:999px; background:var(--u-brand-line); overflow:hidden; }
:root.univer-mobile .univer-app-now-progress>span { display:block; height:100%; border-radius:inherit; background:var(--u-brand); }
:root.univer-mobile .univer-app-now-foot { display:flex; align-items:center; justify-content:space-between; gap:8px 12px; flex-wrap:wrap; margin-top:10px; }
:root.univer-mobile .univer-app-now-foot:not(:has(>:not([hidden]))) { display:none; }
:root.univer-mobile .univer-app-now-note { color:var(--u-text); font-size:var(--u-fs-meta); }
:root.univer-mobile #l4 .univer-app-now-action { min-height:32px!important; padding:4px 14px!important; border:1px solid var(--u-brand-line)!important; border-radius:8px!important; background:#fff!important; color:var(--u-brand)!important; font-size:var(--u-fs-meta)!important; font-weight:600!important; cursor:pointer; }
:root.univer-mobile #l4 .univer-app-now-action:focus-visible { outline:2px solid var(--u-brand); outline-offset:2px; }
:root.univer-mobile .univer-app-schedule[data-mode=grid] { display:block!important; }
:root.univer-mobile .univer-app-schedule[data-mode=grid]>.univer-app-day, :root.univer-mobile .univer-app-schedule[data-mode=grid]>.univer-app-days { display:none!important; }
:root.univer-mobile table.univer-app-schedule-source { display:none!important; }
:root.univer-mobile table.univer-app-schedule-source.univer-app-schedule-grid-visible { display:table!important; width:100%!important; min-width:1100px!important; table-layout:auto!important; }
:root.univer-mobile .univer-app-schedule-scroll { display:none!important; }
:root.univer-mobile .univer-app-schedule-scroll.univer-app-schedule-grid-visible { display:block!important; }
:root.univer-mobile #l4 .univer-app-schedule-content { height:auto!important; max-height:none!important; overflow:visible!important; position:static!important; }
:root.univer-mobile.univer-app-schedule-page #l4 .ct.univer-app-schedule-title { display:flex!important; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:8px 16px; padding:4px 0 8px!important; }
:root.univer-mobile.univer-app-schedule-page #l4 .ct.univer-app-schedule-title>a:first-child { flex:1 1 420px; min-height:0!important; padding:0!important; border:0!important; background:none!important; box-shadow:none!important; color:var(--u-ink)!important; font-size:20px!important; font-weight:600!important; line-height:1.35!important; }
:root.univer-mobile.univer-app-schedule-page #l4 .ct>a.univer-app-print-action { flex:0 0 auto!important; color:var(--u-ink)!important; font-weight:500!important; }
:root.univer-mobile.univer-app-schedule-page #l4 .ct>a.univer-app-print-action::before { content:''; width:16px; height:16px; background:currentColor; -webkit-mask:${icons.print} center/contain no-repeat; }
:root.univer-mobile table.schedule :is(th,td) { font-size:var(--u-fs-body)!important; white-space:normal!important; min-width:0!important; vertical-align:top!important; padding:8px!important; border:1px solid var(--u-line)!important; }
:root.univer-mobile table.schedule th { background:var(--u-surface-2)!important; color:var(--u-ink)!important; font-weight:600; }
:root.univer-mobile table.schedule td.time { width:100px!important; color:var(--u-teal); font-variant-numeric:tabular-nums; background:var(--u-surface-2)!important; text-align:center!important; vertical-align:middle!important; }
:root.univer-mobile table.schedule tr:first-child>:first-child, :root.univer-mobile table.schedule col:first-child { width:96px!important; min-width:96px!important; }
:root.univer-mobile table.schedule td.time { min-width:96px!important; }
:root.univer-mobile table.schedule tr:first-child>th:not(:first-child) { width:14%; }
:root.univer-mobile table.schedule td.time[data-univer-time]>* { display:none!important; }
:root.univer-mobile table.schedule td.time[data-univer-time]::before { content:attr(data-univer-time); display:block; color:var(--u-teal); font-size:var(--u-fs-meta); font-weight:600; line-height:1.4; white-space:nowrap; }
:root.univer-mobile table.schedule .groups>div { box-sizing:border-box; padding:8px!important; border:0!important; border-radius:8px; }
:root.univer-mobile table.schedule .groups p { margin:0 0 3px!important; padding:0!important; border:0!important; background:none!important; line-height:1.4!important; }
:root.univer-mobile table.schedule :is(p,.groups) { width:auto!important; font-size:var(--u-fs-meta)!important; overflow-wrap:break-word; }
:root.univer-mobile table.schedule .groups p:first-child { color:var(--u-ink); font-size:var(--u-fs-body)!important; font-weight:600; }
:root.univer-mobile #l4 .univer-app-schedule-controls { display:block!important; height:auto!important; min-height:0!important; position:static!important; padding:4px 0 8px!important; clear:both; }
:root.univer-mobile #l4 .univer-app-schedule-controls>div { float:none!important; position:static!important; width:100%!important; height:auto!important; }
:root.univer-mobile #weekSelect { display:block!important; width:100%!important; max-width:420px!important; min-height:40px!important; margin:0!important; font-size:15px!important; font-weight:600; color:var(--u-ink)!important; }
:root.univer-mobile.univer-app-schedule-page #l4 .ct { clear:both; height:auto!important; }

/* Documents, FAQ, GPA, news and the website footer. */
:root.univer-mobile #l4 input.univer-app-catalog-search { display:block; clear:both; width:100%!important; max-width:560px!important; height:42px; margin:4px 0 16px!important; padding-left:38px!important; background:#fff ${icons.search} 12px center/18px no-repeat!important; }
:root.univer-mobile :is(.univer-app-catalog,.univer-app-faq,.univer-app-info,#l5 .univer-app-university) { margin:0 0 10px!important; overflow:hidden; background:#fff!important; border:1px solid var(--u-line)!important; border-radius:12px!important; box-shadow:var(--u-shadow); }
:root.univer-mobile :is(.univer-app-catalog,.univer-app-faq,.univer-app-info,#l5 .univer-app-university)>summary { display:flex!important; align-items:center; gap:12px; min-height:0!important; padding:14px 18px!important; color:var(--u-ink)!important; font-size:16px!important; font-weight:600!important; }
:root.univer-mobile :is(.univer-app-catalog,.univer-app-faq,.univer-app-info,#l5 .univer-app-university)>summary::after { content:''; width:8px; height:8px; margin:-4px 2px 0 auto; flex:0 0 auto; border-right:2px solid var(--u-muted); border-bottom:2px solid var(--u-muted); transform:rotate(45deg); }
:root.univer-mobile :is(.univer-app-catalog,.univer-app-faq,.univer-app-info,#l5 .univer-app-university)[open]>summary::after { margin-top:4px; transform:rotate(-135deg); }
:root.univer-mobile :is(.univer-app-catalog,.univer-app-faq,.univer-app-info,#l5 .univer-app-university)[open]>summary { border-bottom:1px solid var(--u-line); }
:root.univer-mobile #l5 .univer-app-university>div { padding:12px 18px!important; font-size:14px!important; color:var(--u-muted); }
:root.univer-mobile .univer-app-catalog tr.file { margin:0!important; border:0!important; border-bottom:1px solid var(--u-line)!important; border-radius:0!important; padding:12px 18px!important; }
:root.univer-mobile .univer-app-catalog tr.file:last-child { border-bottom:0!important; }
:root.univer-mobile .univer-app-catalog tr.file>td:nth-child(3) { font-weight:600; font-style:normal; color:var(--u-ink); }
:root.univer-mobile .univer-app-catalog tr.file>td:nth-child(3) :is(i,em) { font-style:normal; }
:root.univer-mobile .univer-app-catalog tr.file>td[data-univer-field]::before { color:var(--u-muted)!important; font-size:12px!important; }
:root.univer-mobile .univer-app-faq>table.inner>tbody>tr>:is(td,th) { padding:12px 18px!important; }
:root.univer-mobile .univer-app-news .news { margin:0 0 16px!important; padding:20px 24px!important; background:#fff!important; border:1px solid var(--u-line)!important; border-radius:14px!important; box-shadow:var(--u-shadow); }
:root.univer-mobile .univer-app-news .news>td:first-child { padding-bottom:12px!important; margin-bottom:12px!important; border-bottom:1px solid var(--u-line)!important; }
:root.univer-mobile .univer-app-news .news>td:first-child .date { padding:4px 10px!important; border:0!important; border-radius:8px!important; background:var(--u-brand-soft)!important; color:var(--u-brand)!important; font-weight:600; }
:root.univer-mobile .univer-app-news .news>td:first-child>div:not(.date) { padding:3px 10px!important; border:0!important; border-radius:999px!important; background:var(--u-danger-soft)!important; color:var(--u-danger)!important; font-size:12px!important; font-weight:600; }
:root.univer-mobile .univer-app-news .news>td:first-child>div:not(.date) * { color:inherit!important; background:none!important; }
:root.univer-mobile .univer-app-news .news>td:nth-child(2) :is(table) { max-width:100%; }
:root.univer-mobile table.univer-app-news { overflow-anchor:none; }
:root.univer-mobile .univer-app-news-body { display:flow-root; position:relative; }
:root.univer-mobile .univer-app-news-body[data-collapsed=true] { max-height:248px!important; overflow:hidden!important; }
:root.univer-mobile .univer-app-news-body[data-collapsed=true]::after { content:''; position:absolute; bottom:0; left:0; right:0; height:36px; pointer-events:none; background:linear-gradient(transparent,#fff); }
:root.univer-mobile #l4 .univer-app-news button.univer-app-news-toggle { display:inline-flex!important; align-items:center; gap:10px; min-height:38px!important; margin:8px 0 0!important; padding:6px 10px!important; border:0!important; border-radius:6px!important; background:transparent!important; color:var(--u-brand)!important; font:600 15px/1.4 var(--u-font)!important; box-shadow:none!important; }
:root.univer-mobile #l4 .univer-app-news button.univer-app-news-toggle[hidden] { display:none!important; }
:root.univer-mobile .univer-app-news-toggle::after { content:''; width:6px; height:6px; border-right:1.5px solid currentColor; border-bottom:1.5px solid currentColor; transform:translateY(-2px) rotate(45deg); }
:root.univer-mobile .univer-app-news-toggle[aria-expanded=true]::after { transform:translateY(2px) rotate(225deg); }
/* Course links keep the grade matrices intact; syllabi form a compact list below their trigger. */
:root.univer-mobile #l4 .univer-app-course-source[hidden], :root.univer-mobile #l4 a[data-univer-syllabus-source][hidden] { display:none!important; }
:root.univer-mobile #l4 .univer-app-course-heading { display:flex; align-items:flex-start; gap:18px; margin:12px 0 8px; padding-top:16px; border-top:1px solid var(--u-line-strong); text-align:left; }
:root.univer-mobile #l4 .univer-app-course-title { flex:1; min-width:0; }
:root.univer-mobile #l4 .univer-app-course-name, :root.univer-mobile #l4 .univer-app-course-title>.univer-app-attendance-course { display:inline; font-size:20px!important; font-weight:600; line-height:1.4; }
:root.univer-mobile #l4 .univer-app-course-meta { display:flex; flex-wrap:wrap; gap:3px 18px; margin-top:4px; color:var(--u-muted); font-size:var(--u-fs-meta)!important; font-weight:400; }
:root.univer-mobile #l4 .univer-app-course-meta span { font-size:var(--u-fs-meta)!important; }
:root.univer-mobile #l4 button.univer-app-course-syllabus { display:inline-flex; align-items:center; justify-content:center; gap:8px; flex:0 0 auto; min-height:40px!important; padding:9px 14px!important; border:1px solid var(--u-brand-line)!important; border-radius:8px!important; background:#fff!important; color:var(--u-brand)!important; font-family:inherit!important; font-size:15px!important; font-weight:600; cursor:pointer; }
:root.univer-mobile #l4 button.univer-app-course-syllabus::before { content:''; width:16px; height:16px; background:currentColor; -webkit-mask:${icons.doc} center/contain no-repeat; }
:root.univer-mobile #l4 button.univer-app-course-syllabus::after { content:''; width:6px; height:6px; margin:-3px 1px 0 5px; border-right:1.5px solid currentColor; border-bottom:1.5px solid currentColor; transform:rotate(45deg); }
:root.univer-mobile #l4 button.univer-app-course-syllabus[aria-expanded=true]::after { margin-top:3px; transform:rotate(225deg); }
:root.univer-mobile #l4 button.univer-app-course-syllabus[aria-expanded=true] { background:var(--u-brand-soft)!important; border-color:var(--u-brand)!important; }
:root.univer-mobile #l4 .univer-app-attendance-course { color:var(--u-brand)!important; text-decoration:underline; text-decoration-thickness:1px; text-underline-offset:3px; }
/* Lesson names are reading labels; the independent filter controls carry the interaction. */
:root.univer-mobile #l4 a[data-univer-lesson-label][hidden], :root.univer-mobile #l4 .univer-app-attendance-part[hidden] { display:none!important; }
:root.univer-mobile #l4 .univer-app-lesson-label { display:flex; align-items:baseline; flex-wrap:wrap; gap:4px 14px; float:none!important; margin:8px 0 2px; color:var(--u-text)!important; font-size:15px!important; font-weight:400; cursor:text; }
:root.univer-mobile #l4 .univer-app-lesson-label strong { font-size:16px!important; font-weight:600; color:var(--u-ink); }
:root.univer-mobile #l4 .univer-app-lesson-label>span { font-size:14px!important; color:var(--u-muted); }
:root.univer-mobile #l4 .univer-app-attendance-tools { margin:0 0 8px; }
:root.univer-mobile #l4 .univer-app-attendance-courses { margin:0 0 14px; padding:0 0 14px; border-bottom:1px solid var(--u-line); text-align:left; }
:root.univer-mobile #l4 .univer-app-attendance-courses[hidden] { display:none!important; }
:root.univer-mobile #l4 p.univer-app-attendance-course-caption { margin:0 0 8px!important; color:var(--u-muted); font-size:14px!important; }
:root.univer-mobile #l4 .univer-app-attendance-course-list { display:flex; flex-wrap:wrap; gap:6px; }
:root.univer-mobile #l4 .univer-app-attendance-courses button { max-width:100%; min-height:38px!important; padding:8px 14px!important; border:1px solid var(--u-line)!important; border-radius:8px!important; background:#fff!important; color:var(--u-brand)!important; font:600 15px/1.4 var(--u-font)!important; text-align:left; white-space:normal; overflow-wrap:anywhere; }
:root.univer-mobile #l4 .univer-app-attendance-courses button[aria-pressed=true] { background:var(--u-brand)!important; color:#fff!important; border-color:var(--u-brand)!important; }
:root.univer-mobile #l4 .univer-app-attendance-filters { display:flex; flex-wrap:wrap; align-items:center; gap:6px; margin:0; padding:6px; width:fit-content; max-width:100%; box-sizing:border-box; background:#fff; border:1px solid var(--u-line); border-radius:10px; }
:root.univer-mobile #l4 .univer-app-attendance-filter-caption { padding:0 10px; color:var(--u-muted); font-size:14px!important; }
:root.univer-mobile #l4 p.univer-app-attendance-filter-note { margin:8px 0 0!important; color:var(--u-muted); font-size:14px!important; }
:root.univer-mobile #l4 .univer-app-attendance-filters button { min-height:38px!important; padding:8px 16px!important; border:1px solid transparent!important; border-radius:7px!important; background:transparent!important; color:var(--u-muted)!important; font-family:inherit!important; font-size:15px!important; font-weight:600!important; cursor:pointer; }
:root.univer-mobile #l4 .univer-app-attendance-filters button[aria-pressed=true] { background:var(--u-brand-soft)!important; color:var(--u-brand-strong)!important; border-color:var(--u-brand-line)!important; }
:root.univer-mobile #l4 tr.univer-app-attendance-owned, :root.univer-mobile #l4 tr.univer-app-attendance-owned>td { display:block!important; box-sizing:border-box; width:100%!important; }
:root.univer-mobile #l4 .univer-app-attendance-summary { display:grid; grid-template-columns:minmax(220px,1fr) minmax(280px,1.5fr); gap:12px 24px; box-sizing:border-box; width:100%; margin:0 0 12px; padding:14px 16px; border:1px solid var(--u-line); border-radius:10px; background:#fff; color:var(--u-text); text-align:left; }
:root.univer-mobile #l4 .univer-app-attendance-summary:not(:has(.univer-app-attendance-overall-block)) { grid-template-columns:1fr; }
:root.univer-mobile #l4 .univer-app-attendance-summary p { display:flex; flex-wrap:wrap; gap:4px 18px; margin:4px 0!important; font-size:15px!important; line-height:1.6!important; }
:root.univer-mobile #l4 .univer-app-attendance-summary strong { min-width:96px; font-weight:600; }
:root.univer-mobile #l4 .univer-app-attendance-summary p.univer-app-attendance-summary-title { margin:0 0 6px!important; font-size:var(--u-fs-meta)!important; font-weight:600; color:var(--u-muted); }
:root.univer-mobile #l4 .univer-app-type-row { display:flex; flex-wrap:wrap; align-items:center; gap:8px 12px; padding:8px 0; border-top:1px solid var(--u-line); }
:root.univer-mobile #l4 .univer-app-attendance-summary-title+.univer-app-type-row { border-top:0; padding-top:2px; }
:root.univer-mobile #l4 .univer-app-type-label { display:inline-flex; align-items:center; gap:8px; min-width:120px; color:var(--u-ink); font-size:15px; font-weight:600; }
:root.univer-mobile #l4 .univer-app-type-label::before { content:''; width:10px; height:10px; border-radius:3px; background:var(--u-brand); }
:root.univer-mobile #l4 .univer-app-type-row[data-kind=seminar] .univer-app-type-label::before { background:var(--u-teal); }
:root.univer-mobile #l4 .univer-app-type-row[data-kind=sro] .univer-app-type-label::before { background:#b45309; }
:root.univer-mobile #l4 .univer-app-type-empty { color:var(--u-muted); font-size:14px; }
:root.univer-mobile #l4 .univer-app-attendance-overall-block { border-right:1px solid var(--u-line); padding-right:24px; }
:root.univer-mobile #l4 p.univer-app-attendance-overall { margin:0 0 8px!important; font-size:var(--u-fs-meta)!important; font-weight:600; color:var(--u-muted); }
:root.univer-mobile #l4 p.univer-app-attendance-overall-value { color:var(--u-ink); font-size:var(--u-fs-section)!important; font-weight:600; }
/* Control periods (РК1, РК2, exam) are score tiles: the number leads, the maximum and a bar give scale. */
:root.univer-mobile #l4 :is(.univer-app-attendance-overview,.univer-app-attendance-limit,.univer-app-attendance-filter-note,.univer-app-attendance-overall-value)[hidden] { display:none!important; }
:root.univer-mobile #l4 p.univer-app-attendance-limit { margin:0 0 12px!important; padding:10px 14px!important; border-radius:12px; background:var(--u-band-soft); color:var(--u-band-ink)!important; font-size:var(--u-fs-meta)!important; font-weight:600; line-height:1.4!important; text-align:left; }
:root.univer-mobile #l4 .univer-app-attendance-scores { display:flex; flex-wrap:wrap; gap:8px; }
:root.univer-mobile #l4 .univer-app-score { position:relative; display:grid; grid-template-columns:auto 1fr; align-items:baseline; column-gap:4px; min-width:104px; box-sizing:border-box; padding:8px 12px 12px; overflow:hidden; border:1px solid var(--u-brand-line); border-radius:10px; background:var(--u-brand-soft); }
:root.univer-mobile #l4 .univer-app-score-label { grid-column:1/-1; color:var(--u-brand); font-size:var(--u-fs-caption); font-weight:600; letter-spacing:.02em; }
:root.univer-mobile #l4 .univer-app-score-value { color:var(--u-ink); font-size:var(--u-fs-display); font-weight:600; line-height:1.2; font-variant-numeric:tabular-nums; }
:root.univer-mobile #l4 .univer-app-score-max { color:var(--u-muted); font-size:var(--u-fs-meta); font-variant-numeric:tabular-nums; }
:root.univer-mobile #l4 .univer-app-score-bar { position:absolute; left:0; right:0; bottom:0; height:4px; background:var(--u-brand-line); }
:root.univer-mobile #l4 .univer-app-score-bar::after { content:''; position:absolute; top:0; bottom:0; left:0; width:var(--univer-score); background:var(--u-brand); }
:root.univer-mobile #l4 .univer-app-attendance-scores-compact { gap:6px; }
:root.univer-mobile #l4 .univer-app-attendance-scores-compact .univer-app-score { display:inline-flex; align-items:baseline; gap:6px; min-width:0; padding:3px 10px; border-radius:999px; }
:root.univer-mobile #l4 .univer-app-attendance-scores-compact .univer-app-score-value { font-size:var(--u-fs-body); }
:root.univer-mobile #l4 .univer-app-attendance-scores-compact .univer-app-score-max { font-size:var(--u-fs-caption); }
/* Course summary at the top of the journal; a course name opens that course. */
:root.univer-mobile #l4 .univer-app-attendance-overview { margin:12px 0 0; padding:2px 16px 4px; background:#fff; border:1px solid var(--u-line); border-radius:12px; box-shadow:var(--u-shadow); text-align:left; }
:root.univer-mobile #l4 p.univer-app-attendance-overview-title { margin:10px 0 6px!important; color:var(--u-muted); font-size:var(--u-fs-meta)!important; font-weight:600; }
:root.univer-mobile #l4 ul.univer-app-attendance-overview-list { margin:0!important; padding:0!important; list-style:none; }
:root.univer-mobile #l4 ul.univer-app-attendance-overview-list>li { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:6px 16px; margin:0!important; padding:8px 0!important; border-top:1px solid var(--u-line); list-style:none; }
:root.univer-mobile #l4 button.univer-app-attendance-overview-name { flex:1 1 240px; min-width:0; min-height:32px!important; padding:4px 0!important; border:0!important; background:none!important; color:var(--u-brand)!important; font:600 var(--u-fs-body)/1.4 var(--u-font)!important; text-align:left; white-space:normal; overflow-wrap:anywhere; cursor:pointer; }
:root.univer-mobile #l4 .univer-app-attendance-overview-empty { color:var(--u-muted); font-size:var(--u-fs-meta); }
/* The class log opens on request; an empty course is one line. */
:root.univer-mobile #l4 button.univer-app-attendance-details-toggle { grid-column:1/-1; justify-self:start; display:inline-flex; align-items:center; gap:8px; min-height:32px!important; margin:0 0 -4px -8px!important; padding:4px 8px!important; border:0!important; border-radius:6px!important; background:transparent!important; color:var(--u-brand)!important; font:600 var(--u-fs-body)/1.4 var(--u-font)!important; cursor:pointer; }
:root.univer-mobile #l4 button.univer-app-attendance-details-toggle::after { content:''; width:6px; height:6px; border-right:1.5px solid currentColor; border-bottom:1.5px solid currentColor; transform:translateY(-2px) rotate(45deg); }
:root.univer-mobile #l4 button.univer-app-attendance-details-toggle[aria-expanded=true]::after { transform:translateY(2px) rotate(225deg); }
:root.univer-mobile #l4 .univer-app-attendance-summary.univer-app-attendance-summary-empty { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:4px 16px; padding:10px 16px; border-style:dashed; background:var(--u-surface-2); color:var(--u-muted); }
:root.univer-mobile #l4 .univer-app-attendance-summary-empty p { margin:0!important; font-size:var(--u-fs-meta)!important; }
@media(max-width:860px) {
 :root.univer-mobile #l4 .univer-app-course-heading { flex-direction:column; gap:12px; }
 :root.univer-mobile #l4 .univer-app-attendance-summary { grid-template-columns:1fr; gap:12px; }
 :root.univer-mobile #l4 .univer-app-attendance-overall-block { border-right:0; border-bottom:1px solid var(--u-line); padding:0 0 10px; }
 :root.univer-mobile #l4 .univer-app-attendance-filter-caption { flex-basis:100%; padding:6px 10px 2px; }
}
:root.univer-mobile #l4 .univer-app-syllabi { box-sizing:border-box; margin:10px 0 14px; padding:12px 18px; background:#fff; border:1px solid var(--u-brand-line); border-radius:8px; text-align:left; font-size:15px!important; font-weight:400; }
:root.univer-mobile #l4 .univer-app-syllabi[hidden] { display:none!important; }
:root.univer-mobile #l4 .univer-app-syllabi ul { margin:0 0 12px!important; padding:0!important; list-style:none; }
:root.univer-mobile #l4 .univer-app-syllabi a { font-size:15px!important; font-weight:400; }
:root.univer-mobile #l4 .univer-app-syllabi li { display:flex; align-items:baseline; gap:12px; padding:10px 0; border-bottom:1px solid var(--u-line); }
:root.univer-mobile #l4 .univer-app-syllabi li a { flex:1; min-width:0; overflow-wrap:anywhere; }
:root.univer-mobile #l4 .univer-app-syllabi li span { color:var(--u-muted); font-size:var(--u-fs-meta); }
:root.univer-mobile #l4 .univer-app-syllabi p { margin:0 0 10px; }
:root.univer-mobile #l4 .univer-app-syllabi button { margin-right:12px; }
/* News: pagination always describes the currently filtered results. */
:root.univer-mobile #l4 table[data-univer-news-source], :root.univer-mobile #l4 .univer-app-news-pager[hidden] { display:none!important; }
:root.univer-mobile #l4 .univer-app-news-results { padding:0; background:none; border:0; border-radius:0; }
:root.univer-mobile #l4 .univer-app-news-results p.univer-app-news-status { margin:0 0 12px!important; padding:8px 0; }
:root.univer-mobile table.univer-app-news tr.news[hidden] { display:none!important; }
:root.univer-mobile #l4 td.tb.univer-app-news-pager-source { display:none!important; }
:root.univer-mobile #l4 .univer-app-news-tools { display:flex; flex-wrap:wrap; align-items:center; gap:8px 10px; margin:0 0 12px!important; padding:12px; background:#fff; border:1px solid var(--u-line); border-radius:12px; box-shadow:var(--u-shadow); }
:root.univer-mobile #l4 input.univer-app-news-query { flex:1 1 260px; min-width:200px; height:40px; padding-left:38px!important; background:#fff ${icons.search} 12px center/18px no-repeat!important; }
:root.univer-mobile #l4 .univer-app-news-date { display:inline-flex; align-items:center; gap:6px; margin:0!important; color:var(--u-muted); font-size:14px!important; }
:root.univer-mobile #l4 .univer-app-news-date input { width:150px; height:40px; }
:root.univer-mobile #l4 .univer-app-news-tools button { min-height:40px!important; padding:0 14px!important; border:1px solid var(--u-line-strong)!important; border-radius:8px!important; background:#fff!important; color:var(--u-ink)!important; font-size:var(--u-fs-body)!important; font-weight:600!important; }
:root.univer-mobile #l4 .univer-app-news-tools button[aria-pressed=true] { background:var(--u-brand-soft)!important; border-color:var(--u-brand-line)!important; color:var(--u-brand)!important; }
:root.univer-mobile #l4 .univer-app-news-tools button.univer-app-news-all { background:var(--u-brand)!important; border-color:var(--u-brand)!important; color:#fff!important; }
:root.univer-mobile #l4 .univer-app-news-tools button[hidden] { display:none!important; }
:root.univer-mobile .univer-app-news-count { margin-left:auto; color:var(--u-muted); font-size:14px!important; }
:root.univer-mobile .univer-app-news-pager { display:flex; flex-wrap:wrap; align-items:center; gap:6px; margin:4px 0 16px!important; }
:root.univer-mobile .univer-app-news-pager>a { display:inline-flex; align-items:center; justify-content:center; min-width:38px; height:38px; box-sizing:border-box; padding:0 12px; border:1px solid var(--u-line); border-radius:10px; background:#fff; color:var(--u-brand); font-weight:600; text-decoration:none; }
:root.univer-mobile .univer-app-news-pager>a.univer-app-news-current { background:var(--u-brand); border-color:var(--u-brand); color:#fff; }
:root.univer-mobile .univer-app-news-gap { padding:0 5px; color:var(--u-muted); }
:root.univer-mobile .univer-app-news-jump { display:inline-flex; align-items:center; gap:6px; margin-left:auto; }
:root.univer-mobile .univer-app-news-jump input { width:76px; height:38px!important; min-height:38px!important; }
:root.univer-mobile #l4 .univer-app-news-jump button { min-height:38px!important; padding:0 14px!important; border:1px solid var(--u-line-strong)!important; border-radius:8px!important; background:#fff!important; color:var(--u-brand)!important; font-size:var(--u-fs-body)!important; font-weight:600!important; cursor:pointer; }
:root.univer-mobile .univer-app-news-jump-label { color:var(--u-muted); font-size:14px!important; }
:root.univer-mobile .univer-app-news-results { margin:0 0 16px!important; padding:14px 16px; background:var(--u-brand-soft); border:1px solid var(--u-brand-line); border-radius:12px; }
:root.univer-mobile .univer-app-news-results[hidden] { display:none!important; }
:root.univer-mobile .univer-app-news-results h3 { margin:0 0 10px!important; font-size:16px!important; color:var(--u-ink); }
:root.univer-mobile .univer-app-news-status { margin:-4px 0 10px!important; color:var(--u-muted); font-size:14px!important; }
:root.univer-mobile .univer-app-news-hit { margin:0 0 8px; background:#fff; border:1px solid var(--u-line); border-radius:10px; }
:root.univer-mobile .univer-app-news-hit>summary { display:flex; align-items:center; gap:12px; padding:10px 14px; cursor:pointer; }
:root.univer-mobile .univer-app-news-hit>summary time { flex:0 0 auto; padding:2px 8px; border-radius:6px; background:var(--u-brand-soft); color:var(--u-brand); font-size:var(--u-fs-meta); font-weight:600; }
:root.univer-mobile .univer-app-news-hit>summary span { flex:1 1 auto; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:var(--u-ink); }
:root.univer-mobile .univer-app-news-hit>summary a { flex:0 0 auto; font-size:14px; font-weight:600; }
:root.univer-mobile .univer-app-news-hit-body { padding:0 14px 12px; max-height:520px; overflow:auto; }
:root.univer-mobile .univer-app-news-hit-body img { max-width:100%; height:auto; }
/* УМКД: the duplicate "view by discipline" entry reloads the same list. */
:root.univer-mobile #l4 a#discipline_btn, :root.univer-mobile #l4 .ts:has(>a#discipline_btn):not(:has(>a:not(#discipline_btn))) { display:none!important; }
/* Schedule: today. */
:root.univer-mobile .univer-app-day.univer-app-today { border-color:var(--u-brand)!important; box-shadow:0 0 0 1px var(--u-brand),var(--u-shadow); }
:root.univer-mobile .univer-app-day.univer-app-today h3 { display:flex; align-items:center; gap:8px; background:var(--u-brand-soft)!important; color:var(--u-brand)!important; }
:root.univer-mobile .univer-app-today-badge { margin-left:auto; padding:1px 8px; border-radius:999px; background:var(--u-brand); color:#fff; font-size:var(--u-fs-caption); font-weight:600; }
:root.univer-mobile #l4 .univer-app-days button.univer-app-today:not([aria-pressed=true]) { border-color:var(--u-brand)!important; color:var(--u-brand)!important; font-weight:600!important; }
:root.univer-mobile table.schedule th.univer-app-today-col { background:var(--u-brand)!important; color:#fff!important; }
:root.univer-mobile #l4 iframe.univer-app-video { display:none!important; }
:root.univer-mobile .univer-app-video-link { display:inline-flex; align-items:center; gap:8px; min-height:40px; box-sizing:border-box; padding:8px 16px; margin:8px 0; border:1px solid var(--u-line); border-radius:10px; background:#fff; box-shadow:var(--u-shadow); color:var(--u-brand); font-weight:600; text-decoration:none; }
:root.univer-mobile .univer-app-video-link::before { content:'▶'; font-size:11px; }
:root.univer-mobile #l5, :root.univer-mobile #l5 td.center, :root.univer-mobile #copyright, :root.univer-mobile #copyright>tbody>tr>td { background:transparent!important; border:0!important; }
:root.univer-mobile #l5 td.center { padding:8px 32px 32px!important; }
:root.univer-mobile #l5 td.center>#copyright { width:100%!important; margin:0 auto 12px!important; }
:root.univer-mobile #copyright td { padding:0!important; text-align:start!important; }
:root.univer-mobile #copyright td { color:var(--u-muted)!important; font-size:12px!important; }
:root.univer-mobile .univer-app-info[data-univer-info=links] .links a { color:var(--u-brand)!important; }

/* Site dialogs and the login page. */
:root.univer-mobile #simplemodal-container { border:1px solid var(--u-line)!important; border-radius:14px!important; box-shadow:var(--u-shadow-pop)!important; }
:root.univer-mobile .univer-app-modal-toolbar { border-color:var(--u-line)!important; border-radius:14px 14px 0 0!important; }
:root.univer-mobile #auth_modal, :root.univer-mobile #modal_agreement, :root.univer-mobile .modal { max-width:calc(100vw - 36px)!important; max-height:calc(100vh - 36px)!important; overflow:auto!important; box-sizing:border-box!important; }
:root.univer-mobile #modal_agreement .modal-body { max-height:65vh!important; overflow:auto!important; line-height:1.6!important; }
:root.univer-mobile .modal .modal-footer { padding:14px!important; }
:root.univer-mobile #attentionSt { line-height:1.65!important; }
:root.univer-mobile #login_form { box-sizing:border-box!important; padding:28px!important; background:#fff!important; border:1px solid var(--u-line)!important; border-radius:16px!important; box-shadow:var(--u-shadow); }
:root.univer-mobile #login_form :is(td,p,strong,b,label,span) { text-align:start!important; }
:root.univer-mobile #login_frm :is(td,p,strong,li) { font-size:15px!important; line-height:1.6!important; }
:root.univer-mobile #login_frm :is(input[type=text],input[type=password]) { min-height:42px!important; background:#fff!important; }
:root.univer-mobile #login_frm :is(input[type=submit],button[type=submit]) { width:100%; min-height:44px!important; padding:10px 22px!important; border:0!important; border-radius:8px!important; background:var(--u-brand)!important; color:#fff!important; font-weight:600!important; }

/* Printed documents open in their own viewer as a paper sheet. */
:root.univer-mobile.univer-app-document, :root.univer-mobile.univer-app-document body { background:var(--u-bg)!important; }
:root.univer-mobile.univer-app-document body { box-sizing:border-box; max-width:1040px!important; margin:24px auto!important; padding:40px 48px!important; background:#fff!important; border:1px solid var(--u-line); border-radius:12px; box-shadow:var(--u-shadow); color:#1d2b39!important; }
:root.univer-mobile.univer-app-document .noprint a { border-radius:8px; }
:root.univer-mobile.univer-app-document .univer-app-table-scroll { box-shadow:none; border-radius:8px!important; }
:root.univer-mobile.univer-app-document .univer-app-table-scroll .univer-app-table-scroll { border:0!important; margin:0!important; }
:root.univer-mobile.univer-app-document .univer-app-pairs>tbody>tr { margin:0!important; border:0!important; border-bottom:1px solid var(--u-line)!important; border-radius:0!important; box-shadow:none!important; }
:root.univer-mobile.univer-app-document .univer-app-pairs>tbody>tr>td { border:0!important; }

@media (min-width:900px) {
 :root.univer-mobile .univer-app-cards>thead { display:table-header-group!important; }
 :root.univer-mobile .univer-app-cards>tfoot { display:table-footer-group!important; }
 :root.univer-mobile #l4 table.univer-app-sort-collapsed .univer-app-sort-head { display:table-row!important; }
 :root.univer-mobile #l4 .univer-app-cards :is(td,th) { overflow-wrap:normal!important; word-break:normal!important; }
 :root.univer-mobile #l4 .univer-app-cards .univer-app-data-field { min-width:8ch!important; }
 :root.univer-mobile .univer-app-cards .univer-app-card-row { box-shadow:none; }
 :root.univer-mobile .univer-app-cards td:has(>a.univer-app-action-link:only-child) { width:1%; white-space:nowrap; }
 :root.univer-mobile #l4 .univer-app-form-table>tbody>tr:has(>:nth-child(2)):not(:has(>:nth-child(5))):not(:has(>:first-child :is(input,select,textarea,img,table))):not(.univer-app-form-spacer) { display:grid!important; grid-template-columns:minmax(160px,260px) minmax(0,1fr); column-gap:20px; align-items:center; }
 :root.univer-mobile #l4 .univer-app-form-table>tbody>tr:has(>:nth-child(2)):not(:has(>:nth-child(5))):not(:has(>:first-child :is(input,select,textarea,img,table)))>:is(td,th) { width:auto!important; }
 :root.univer-mobile .univer-app-shell table.inner.univer-app-courses { table-layout:fixed!important; }
 :root.univer-mobile .univer-app-shell .univer-app-courses>tbody>tr>:is(td,th):first-child { width:36px!important; min-width:36px!important; max-width:36px!important; padding:10px 8px 10px 14px!important; }
 :root.univer-mobile .univer-app-shell .univer-app-courses>tbody>tr>:is(td,th):nth-child(2) { width:auto!important; min-width:240px!important; }
 :root.univer-mobile .univer-app-shell .univer-app-courses>tbody>tr>:is(td,th):last-child { width:250px!important; }
 :root.univer-mobile .univer-app-shell .univer-app-materials>tbody>tr.file>td:first-child, :root.univer-mobile .univer-app-shell .univer-app-materials>tbody>tr:has(>th:nth-child(2))>th:first-child { display:none!important; }
 :root.univer-mobile .univer-app-shell .univer-app-materials>tbody>tr>:is(td,th):nth-child(2) { min-width:220px!important; }
 :root.univer-mobile .univer-app-shell .univer-app-materials>tbody>tr>:is(td,th):nth-child(n+3) { min-width:9ch!important; overflow-wrap:normal!important; word-break:normal!important; }
 :root.univer-mobile .univer-app-shell .univer-app-materials>tbody>tr>:is(td,th):last-child { min-width:140px!important; }
 :root.univer-mobile .univer-app-materials .stars { gap:0; flex-wrap:nowrap; }
 :root.univer-mobile .univer-app-materials svg.star { width:16px!important; height:18px!important; padding:4px!important; }
 :root.univer-mobile .univer-app-catalog tr.file { display:grid!important; grid-template-columns:minmax(0,1fr) 110px 100px 90px; align-items:center; gap:4px 16px; }
 :root.univer-mobile .univer-app-catalog tr.file>td:nth-child(3) { grid-column:1!important; display:flex!important; align-items:center; gap:12px; justify-content:space-between; }
 :root.univer-mobile .univer-app-catalog tr.file>td[data-univer-field] { font-size:14px!important; color:var(--u-text); }
}
@media (max-width:899px) {
 :root.univer-mobile .univer-app-table-scroll:has(>:is(.univer-app-cards,.univer-app-courses,.univer-app-materials)) { background:none!important; border:0!important; box-shadow:none!important; overflow:visible; }
 :root.univer-mobile .univer-app-table-scroll :is(.univer-app-cards,.univer-app-courses,.univer-app-materials) :is(td,th) { border-bottom:0!important; padding:0!important; }
 :root.univer-mobile .univer-app-table-scroll :is(.univer-app-courses,.univer-app-materials)>tbody>tr:is(.link,.file) { box-shadow:var(--u-shadow); border-color:var(--u-line)!important; border-radius:12px!important; }
 :root.univer-mobile #l4 td.center { padding:16px 16px 32px!important; }
 :root.univer-mobile #l1 .header { padding:0 16px!important; }
}
@media (max-width:1040px) {
 :root.univer-mobile .univer-app-brand { display:none!important; }
}
@media (max-width:860px) {
 :root.univer-mobile .univer-app-header { padding:8px 0; }
 :root.univer-mobile .univer-app-tabs { order:3; flex-basis:100%; overflow-x:auto; }
 :root.univer-mobile.univer-app-unified .univer-app-menu[open] { top:calc(var(--u-header) + 52px); left:12px; right:12px; }
}
@media (max-width:700px) {
 :root.univer-mobile .univer-app-schedule[data-all-days=true] { grid-template-columns:minmax(0,1fr); }
 :root.univer-mobile .univer-app-courses .univer-app-course-open { margin-top:8px!important; width:100%!important; }
}
@media print {
 :root.univer-mobile #l1 .header { position:static; box-shadow:none; }
 :root.univer-mobile :is(.univer-app-sections,.univer-app-menu,.univer-app-actions .univer-app-account) { display:none!important; }
 :root.univer-mobile.univer-app-document body { max-width:none!important; margin:0!important; padding:0!important; border:0; box-shadow:none; }
}
:root.univer-app-still * { scroll-behavior:auto!important; animation:none!important; transition:none!important; }
:root.univer-mobile .univer-app-menu { transform-origin:top center; }
:root.univer-mobile .univer-app-account>:not(summary) { transform-origin:top right; }


/* Loading text remains readable, without a perpetual sweep across the workspace. */
:root.univer-mobile .univer-app-pending { width:fit-content; max-width:100%; padding:6px 12px; border-radius:6px; background:var(--u-surface-2); color:var(--u-muted)!important; }
/* Pointer hover and press retarget smoothly; keyboard selection never waits for movement. */
:root.univer-mobile :is(a,button,summary,label,select,textarea,input) { transition:none; }
:root.univer-mobile summary::after { transition:none; }
:root.univer-mobile:not(.univer-app-still):not(.univer-app-keyboard) :is(a,button,summary,label,select,textarea,input) { transition:background-color 140ms var(--u-ease-out),color 140ms var(--u-ease-out),border-color 140ms var(--u-ease-out); }
:root.univer-mobile .univer-app-tabs>a.active { transition:background-color 140ms var(--u-ease-out); }
:root.univer-mobile:not(.univer-app-still):not(.univer-app-keyboard) summary::after { transition:transform 180ms var(--u-ease-out); }
:root.univer-mobile .univer-app-account[open]>summary::after { transform:rotate(-135deg); }
@media (hover:hover) and (pointer:fine) {
 :root.univer-mobile:not(.univer-app-still):not(.univer-app-keyboard) :is(button:not(.univer-app-unpin,.univer-app-reorder),input[type=button],input[type=submit],input[type=reset],.univer-app-quick a) { transition:transform var(--u-release) var(--u-ease-out),background-color 140ms var(--u-ease-out),color 140ms var(--u-ease-out),border-color 140ms var(--u-ease-out); }
 :root.univer-mobile:not(.univer-app-still):not(.univer-app-keyboard) :is(button:not(.univer-app-unpin,.univer-app-reorder),input[type=button],input[type=submit],input[type=reset],.univer-app-quick a):active:not(:disabled) { transform:scale(.97); transition-duration:var(--u-press); }
 :root.univer-mobile:not(.univer-app-still):not(.univer-app-keyboard) .univer-app-quick a { transition:transform 180ms var(--u-ease-out); }
 :root.univer-mobile:not(.univer-app-still):not(.univer-app-keyboard) .univer-app-quick a:hover { transform:translateY(-2px); }
 :root.univer-mobile:not(.univer-app-still):not(.univer-app-keyboard) .univer-app-quick a:active { transform:translateY(1px) scale(.98); transition-duration:140ms; }
}
@media (prefers-reduced-motion:reduce) {
 :root.univer-mobile *, :root.univer-mobile *::before, :root.univer-mobile *::after { animation:none!important; scroll-behavior:auto!important; }
 :root.univer-mobile :is(a,button,summary,label,select,textarea,input) { transition:none!important; }
 :root.univer-mobile .univer-app-quick a:hover { transform:none!important; }
 :root.univer-mobile :is(button:not(.univer-app-unpin),input[type=button],input[type=submit],input[type=reset],.univer-app-quick a):active { transform:none!important; }
}
@media (hover:hover) {
 :root.univer-mobile a:hover { color:var(--u-brand-strong); }
 :root.univer-mobile :is(input,select,textarea):not([type=checkbox]):not([type=radio]):hover { border-color:#9fb2c6!important; }
 :root.univer-mobile .univer-app-tabs>a:hover { background:var(--u-surface-2)!important; color:var(--u-ink)!important; }
 :root.univer-mobile .univer-app-tabs>a.active:hover { background:var(--u-brand-strong)!important; color:#fff!important; }
 :root.univer-mobile #l1 .header .univer-app-tabs>a:hover { color:var(--u-ink)!important; }
 :root.univer-mobile #l1 .univer-app-sections:is(:hover,[aria-expanded=true]) { background:var(--u-brand-soft)!important; border-color:var(--u-brand-line)!important; color:var(--u-brand)!important; }
 :root.univer-mobile #l1 .univer-app-flags>a:hover { background:#fff; }
 :root.univer-mobile #l1 .univer-app-flags>a:hover img { opacity:1; }
 :root.univer-mobile .univer-app-actions .links>a:hover { background:var(--u-surface-2); color:var(--u-ink)!important; }
 :root.univer-mobile .univer-app-actions .univer-app-account:is([open],:hover)>summary { background:var(--u-brand-soft)!important; border-color:var(--u-brand-line)!important; color:var(--u-brand)!important; }
 :root.univer-mobile .univer-app-actions .univer-app-account>div a:hover { background:var(--u-surface-2); }
 :root.univer-mobile .univer-app-menu-close:hover { background:var(--u-surface-2)!important; color:var(--u-ink)!important; }
 :root.univer-mobile #tools li a:hover { background:var(--u-brand-soft)!important; color:var(--u-brand)!important; }
 :root.univer-mobile #l4 :is(.ts>a,a.univer-app-page-link,.univer-app-semester-nav a,.univer-app-nav-table a):hover { background:var(--u-brand-soft)!important; border-color:var(--u-brand-line)!important; color:var(--u-brand-strong)!important; }
 :root.univer-mobile .univer-app-quick a:hover { border-color:var(--u-brand-line)!important; background:var(--u-brand-soft)!important; }
 :root.univer-mobile .univer-app-course-open:hover, :root.univer-mobile .univer-app-catalog-download:hover { background:var(--u-brand-strong)!important; }
 :root.univer-mobile a.univer-app-action-link:hover { background:var(--u-brand)!important; color:#fff!important; }
 :root.univer-mobile .univer-app-table-scroll tbody>tr:hover>td { background:#fafcfe; }
 :root.univer-mobile .univer-app-materials a.downLoad:hover { background:var(--u-brand)!important; border-color:var(--u-brand)!important; color:#fff!important; }
 :root.univer-mobile #l4 .univer-app-days button:hover { border-color:var(--u-brand-line)!important; background:var(--u-brand-soft)!important; }
 :root.univer-mobile #l4 .univer-app-now-action:hover { background:var(--u-brand-soft)!important; }
 :root.univer-mobile :is(.univer-app-catalog,.univer-app-faq,.univer-app-info,#l5 .univer-app-university):hover>summary { background:var(--u-surface-2); }
 :root.univer-mobile #l4 .univer-app-news button.univer-app-news-toggle:hover { background:var(--u-brand-soft)!important; color:var(--u-brand-strong)!important; }
 :root.univer-mobile #l4 button.univer-app-course-syllabus:hover { background:var(--u-brand-soft)!important; border-color:var(--u-brand)!important; }
 :root.univer-mobile #l4 .univer-app-attendance-course:hover { color:var(--u-brand-strong)!important; }
 :root.univer-mobile #l4 .univer-app-attendance-courses button:hover { background:var(--u-brand-soft)!important; border-color:var(--u-brand-line)!important; }
 :root.univer-mobile #l4 .univer-app-attendance-filters button:hover { color:var(--u-brand)!important; background:var(--u-surface-2)!important; }
 :root.univer-mobile #l4 button.univer-app-attendance-overview-name:hover { color:var(--u-brand-strong)!important; text-decoration:underline; text-underline-offset:3px; }
 :root.univer-mobile #l4 button.univer-app-attendance-details-toggle:hover { background:var(--u-brand-soft)!important; }
 :root.univer-mobile #l4 .univer-app-news-tools button:hover { background:var(--u-brand-soft)!important; border-color:var(--u-brand-line)!important; color:var(--u-brand)!important; }
 :root.univer-mobile .univer-app-news-pager>a:hover { background:var(--u-brand-soft); border-color:var(--u-brand-line); }
 :root.univer-mobile #l4 .univer-app-news-jump button:hover { background:var(--u-brand-soft)!important; border-color:var(--u-brand-line)!important; }
 :root.univer-mobile #login_frm :is(input[type=submit],button[type=submit]):hover { background:var(--u-brand-strong)!important; }
}
/* Phone layer: fits the rules above to a 320–412dp portrait screen under the native app bar (touch-sized controls,
   no hover, compact grids). */
html:root.univer-mobile { --u-header:0px; scroll-padding-top:12px; -webkit-tap-highlight-color:transparent; }
html:root.univer-mobile body { font-size:16px!important; -webkit-text-size-adjust:100%; }
html:root.univer-mobile ::selection { background:#cfe0f4; color:var(--u-ink); }
html:root.univer-mobile :is(h1,h2,h3,.univer-app-overview-head>h2,.univer-app-now-subject) { text-wrap:balance; }
html:root.univer-mobile #l4 :is(p,li) { text-wrap:pretty; }
html:root.univer-mobile #l4 td.center { padding:12px 16px 32px!important; }
html:root.univer-mobile #l4 table.mt>tbody>tr.top>:is(.ct,.tt) { font-size:20px!important; padding:12px 0 6px!important; }

/* Site tabs under the native bar: pill tabs with their icons, all three kept in the page. */
html:root.univer-mobile #l3 td.center { display:flex!important; gap:6px; padding:8px 16px 4px!important; background:var(--u-bg)!important; border:0!important; overflow-x:auto; scrollbar-width:none; }
html:root.univer-mobile #l3 td.center::-webkit-scrollbar { display:none; }
html:root.univer-mobile #l3 td.center>a { flex:1 1 0; display:inline-flex!important; align-items:center; justify-content:center; gap:6px; min-width:max-content; min-height:44px; height:44px; box-sizing:border-box; padding:0 12px!important; margin:0!important; border:1px solid var(--u-line)!important; border-radius:10px!important; background:var(--u-surface)!important; color:var(--u-muted)!important; font-size:15px!important; font-weight:600!important; text-decoration:none!important; white-space:nowrap!important; }
html:root.univer-mobile #l3 td.center>a * { color:inherit!important; font:inherit!important; }
html:root.univer-mobile #l3 td.center>a.active { background:var(--u-brand)!important; border-color:var(--u-brand)!important; color:#fff!important; box-shadow:0 1px 2px rgba(16,40,70,.15); }
html:root.univer-mobile #l3 td.center>a.univer-app-tab-icon::before { content:''; width:18px; height:18px; flex:0 0 auto; background:currentColor; -webkit-mask:var(--glyph) center/contain no-repeat; }
/* The English site names its first tab "home" in lower case beside "Bachelor" and "E-services": only the drawn letter changes. */
html:root.univer-mobile #l3 td.center>a .center::first-letter { text-transform:uppercase; }
/* The site's red unread count inside a tab becomes a small count badge. */
html:root.univer-mobile #l3 td.center>a .center span[style*="red"] { display:inline-grid; place-items:center; min-width:20px; height:20px; box-sizing:border-box; margin-left:6px; padding:0 6px; border-radius:999px; background:var(--u-danger); color:#fff!important; font-size:12px!important; font-weight:600!important; line-height:1; }
html:root.univer-mobile #l3 td.center>a.active .center span[style*="red"] { background:#fff; color:var(--u-danger)!important; }
@media (max-width:379px) { html:root.univer-mobile #l3 td.center>a.univer-app-tab-icon::before { display:none; } html:root.univer-mobile #l3 td.center>a { padding:0 8px!important; font-size:14px!important; } }

/* "All sections" stays in the page flow on a phone: one card with a menu icon and a chevron. */
/* On news and Е-услуги the site renders the menu in its own row, outside the padded page body. */
html:root.univer-mobile:not(.univer-app-unified) .univer-app-menu:not(#l4 *) { margin-left:16px!important; margin-right:16px!important; }
html:root.univer-mobile:not(.univer-app-unified) .univer-app-menu { margin:4px 0 16px!important; overflow:hidden; background:var(--u-surface)!important; border:1px solid var(--u-line)!important; border-radius:12px!important; }
html:root.univer-mobile:not(.univer-app-unified) .univer-app-menu>summary { display:flex!important; align-items:center; gap:12px; min-height:52px; box-sizing:border-box; padding:0 16px!important; border:0!important; color:var(--u-ink)!important; font-size:16px!important; font-weight:600!important; }
html:root.univer-mobile:not(.univer-app-unified) .univer-app-menu>summary::before { content:''; width:18px; height:14px; flex:0 0 auto; background:linear-gradient(currentColor,currentColor) top/100% 2px no-repeat,linear-gradient(currentColor,currentColor) center/100% 2px no-repeat,linear-gradient(currentColor,currentColor) bottom/100% 2px no-repeat; color:var(--u-brand); }
html:root.univer-mobile:not(.univer-app-unified) .univer-app-menu[open]>summary { border-bottom:1px solid var(--u-line)!important; background:var(--u-surface-2); }
html:root.univer-mobile:not(.univer-app-unified) .univer-app-menu>:not(summary) { margin-left:12px!important; margin-right:12px!important; }
html:root.univer-mobile:not(.univer-app-unified) .univer-app-menu input[type=search] { width:calc(100% - 24px)!important; margin:12px 12px 12px!important; min-height:48px; }
html:root.univer-mobile:not(.univer-app-unified) .univer-app-menu #tools { width:calc(100% - 24px)!important; margin-bottom:12px!important; }
html:root.univer-mobile #tools>tbody>tr>td.center { grid-template-columns:minmax(0,1fr)!important; gap:10px!important; }
html:root.univer-mobile #tools li a { min-height:44px; box-sizing:border-box; display:flex!important; align-items:center; padding:10px 14px!important; font-size:15px!important; }
/* The page-body centring rule (#l4 td.center>*) must not shrink the menu's own td.center columns. */
html:root.univer-mobile #tools td.center>* { margin-left:0!important; margin-right:0!important; max-width:none!important; }
html:root.univer-mobile #tools .univer-app-category { display:block!important; width:auto!important; justify-self:stretch; box-sizing:border-box; }
html:root.univer-mobile .univer-app-category>summary { min-height:44px!important; display:flex!important; align-items:center; font-size:14px!important; }
html:root.univer-mobile .univer-app-pin { width:44px!important; height:44px!important; margin:0!important; opacity:1!important; }
html:root.univer-mobile .univer-app-useful ul { grid-template-columns:minmax(0,1fr)!important; padding:10px!important; }
html:root.univer-mobile .univer-app-useful li>a { min-height:48px; box-sizing:border-box; }

/* Chevrons for the disclosures Android adds (filters, pages, lesson types, material details, course facts). */
html:root.univer-mobile :is(.univer-app-news-pages,.univer-app-attendance-options,.univer-app-material-details,#l4 details.univer-app-course-heading,.univer-app-menu,.univer-app-category,.univer-app-info,.univer-app-university)>summary { display:flex!important; align-items:center; gap:8px; cursor:pointer; }
html:root.univer-mobile :is(.univer-app-news-pages,.univer-app-attendance-options,.univer-app-material-details,#l4 details.univer-app-course-heading,.univer-app-menu,.univer-app-category)>summary::after { content:''; width:7px; height:7px; margin:-4px 4px 0 auto; flex:0 0 auto; border-right:2px solid currentColor; border-bottom:2px solid currentColor; transform:rotate(45deg); opacity:.6; }
html:root.univer-mobile :is(.univer-app-news-pages,.univer-app-attendance-options,.univer-app-material-details,#l4 details.univer-app-course-heading,.univer-app-menu,.univer-app-category)[open]>summary::after { margin-top:4px; transform:rotate(-135deg); }
html:root.univer-mobile :is(.univer-app-news-pages,.univer-app-attendance-options,.univer-app-material-details)>summary { min-height:44px; box-sizing:border-box; padding:0 12px!important; border:1px solid var(--u-line-strong); border-radius:10px; background:var(--u-surface); color:var(--u-brand)!important; font-size:15px!important; font-weight:600; }
html:root.univer-mobile :is(.univer-app-news-pages,.univer-app-attendance-options)[open]>summary { background:var(--u-brand-soft); border-color:var(--u-brand-line); }
html:root.univer-mobile .univer-app-material-details>summary { border:0; padding:0!important; background:none; }
html:root.univer-mobile #l4 details.univer-app-course-heading { display:block!important; }
html:root.univer-mobile #l4 details.univer-app-course-heading>summary { align-items:flex-start; min-height:48px; padding:6px 0; }
html:root.univer-mobile #l4 details.univer-app-course-heading>summary::after { margin-top:10px; }
html:root.univer-mobile #l4 details.univer-app-course-heading[open]>summary::after { margin-top:14px; }
html:root.univer-mobile #l4 details.univer-app-course-heading>:not(summary) { margin-top:8px; }

/* News: search, filters and pages fit one phone row; the result count wraps under them. */
html:root.univer-mobile #l4 .univer-app-news-tools { padding:10px!important; gap:8px!important; align-items:flex-start; }
html:root.univer-mobile #l4 input.univer-app-news-query { flex:1 1 160px; min-width:0; height:48px; }
html:root.univer-mobile #l4 .univer-app-news-date { flex:1 1 140px; flex-direction:column; align-items:stretch; }
html:root.univer-mobile #l4 .univer-app-news-date input { width:100%; height:48px; }
html:root.univer-mobile .univer-app-news .news { padding:16px!important; border-radius:12px!important; }
html:root.univer-mobile .univer-app-news .news>td:nth-child(2) { font-size:16px!important; color:var(--u-ink)!important; }
html:root.univer-mobile .univer-app-news-jump { margin-left:0; flex-basis:100%; }
html:root.univer-mobile .univer-app-news-hit>summary span { white-space:normal; }

/* Schedule: the whole week fits one strip, each day with its number of classes under it. */
html:root.univer-mobile .univer-app-days { flex-wrap:nowrap!important; gap:4px!important; margin:0 0 4px!important; padding:0; }
/* A scrolling row inside the site's table cells must not widen the cell to its own content. Rows the adapter found
   empty stay hidden: the bottom tool row (td.tb) is only the site's light gradient image, a bright bar in the dark theme. */
html:root.univer-mobile.univer-app-schedule-page #l4 table.mt:has(.univer-app-schedule), html:root.univer-mobile.univer-app-schedule-page #l4 table.mt:has(.univer-app-schedule)>tbody,
html:root.univer-mobile.univer-app-schedule-page #l4 table.mt:has(.univer-app-schedule)>tbody>tr:not(.univer-app-study-empty), html:root.univer-mobile.univer-app-schedule-page #l4 table.mt:has(.univer-app-schedule)>tbody>tr:not(.univer-app-study-empty)>td:not(.lt,.rt,.pd,.univer-app-secondary-heading,.univer-app-schedule-title) { display:block!important; width:auto!important; min-width:0!important; box-sizing:border-box; }
html:root.univer-mobile.univer-app-schedule-page #l4 table.mt:has(.univer-app-schedule)>tbody>tr>td:is(.lt,.rt,.pd,.univer-app-secondary-heading) { display:none!important; }
html:root.univer-mobile .univer-app-schedule-modes { width:fit-content; max-width:100%; }
html:root.univer-mobile #l4 .univer-app-days button { flex:1 1 0; flex-direction:column; justify-content:center; gap:2px!important; min-width:0; min-height:56px!important; padding:4px 2px!important; line-height:1.2!important; text-align:center; white-space:normal!important; }
/* "All days" is the one two-word label: it takes a little more of the strip than a day does. */
html:root.univer-mobile #l4 .univer-app-days button:last-child { flex-grow:1.4; font-size:13px!important; }
html:root.univer-mobile #l4 .univer-app-days button { border-radius:8px!important; }
html:root.univer-mobile #l4 .univer-app-days .univer-app-day-count { min-width:0!important; height:auto!important; padding:0!important; background:none!important; font-size:12px!important; font-weight:500!important; line-height:1.3!important; }
html:root.univer-mobile .univer-app-schedule-modes { margin:4px 0 8px; }
html:root.univer-mobile #l4 .univer-app-schedule-modes button { min-height:40px!important; }
html:root.univer-mobile .univer-app-schedule-mode { color:var(--u-ink)!important; font-size:15px!important; }
html:root.univer-mobile .univer-app-schedule-mode input { accent-color:var(--u-brand); }
html:root.univer-mobile .univer-app-schedule-context { color:var(--u-muted)!important; }
html:root.univer-mobile .univer-app-now { padding:14px!important; }
html:root.univer-mobile .univer-app-now-count { font-size:22px!important; }
html:root.univer-mobile #l4 .univer-app-now-action { min-height:44px!important; }
html:root.univer-mobile #weekSelect { max-width:none!important; min-height:48px!important; }
html:root.univer-mobile .univer-app-week-nav { display:flex; align-items:center; gap:8px; }
html:root.univer-mobile .univer-app-week-nav #weekSelect { flex:1 1 auto; width:auto!important; min-width:0; }
html:root.univer-mobile #l4 .univer-app-week-nav button.univer-app-week-step { flex:0 0 auto; width:48px!important; min-width:48px!important; height:48px; min-height:48px!important; margin:0!important; padding:0!important; border:1px solid var(--u-line-strong)!important; border-radius:10px!important; background:var(--u-surface)!important; color:var(--u-brand-strong)!important; font:inherit; font-size:24px!important; line-height:1; }
html:root.univer-mobile #l4 .univer-app-week-nav button.univer-app-week-step:disabled { opacity:.4; }
html:root.univer-mobile .univer-app-lesson-meta { color:var(--u-muted)!important; }

/* Grades: the course picker is a native list, filters open on request, score tiles fit two per row. */
html:root.univer-mobile .univer-app-attendance-course-list select { min-height:48px; font-size:16px!important; font-weight:600; color:var(--u-ink); }
html:root.univer-mobile #l4 .univer-app-attendance-courses { margin:0 0 10px!important; padding:0 0 10px!important; }
html:root.univer-mobile #l4 .univer-app-attendance-options>div { padding:10px 0 4px; }
html:root.univer-mobile #l4 .univer-app-attendance-filters { width:auto; }
html:root.univer-mobile #l4 .univer-app-attendance-filters button { min-height:44px!important; padding:8px 14px!important; }
html:root.univer-mobile #l4 .univer-app-attendance-scores:not(.univer-app-attendance-scores-compact) .univer-app-score { min-width:0; flex:1 1 96px; }
html:root.univer-mobile #l4 .univer-app-attendance-scores-compact .univer-app-score { flex:0 0 auto; white-space:nowrap; }
html:root.univer-mobile #l4 .univer-app-attendance-summary { padding:12px 14px!important; }
html:root.univer-mobile #l4 button.univer-app-attendance-details-toggle, html:root.univer-mobile #l4 button.univer-app-attendance-overview-name { min-height:44px!important; }
html:root.univer-mobile #l4 button.univer-app-course-syllabus { min-height:44px!important; }

/* Home: tiles are a two-column grid with the icon above the name; reorder and unpin are thumb-sized. */
@media (max-width:599px) {
 html:root.univer-mobile.univer-app-home #l4>td { padding:12px 12px 32px!important; }
 html:root.univer-mobile .univer-app-quick { grid-template-columns:repeat(2,minmax(0,1fr))!important; gap:10px!important; margin:4px 0 20px!important; }
 html:root.univer-mobile .univer-app-quick .univer-app-tile>a { flex-direction:column; align-items:flex-start!important; justify-content:flex-start!important; gap:10px!important; min-height:88px; height:100%; padding:12px 14px!important; border:1px solid var(--u-line)!important; border-radius:12px!important; font-size:15px!important; line-height:1.3!important; }
 html:root.univer-mobile .univer-app-quick .univer-app-tile.univer-app-pinned>a { padding-right:14px!important; }
 html:root.univer-mobile .univer-app-quick .univer-app-link-icon { width:36px; height:36px; border-radius:10px; }
 html:root.univer-mobile .univer-app-quick .univer-app-reorder { top:4px; right:4px; transform:none; width:44px; height:44px; border-radius:10px!important; }
 html:root.univer-mobile .univer-app-quick .univer-app-unpin { top:4px; right:46px; transform:none; width:44px; height:44px; border-radius:10px!important; }
 html:root.univer-mobile .univer-app-gpa { grid-template-columns:repeat(2,minmax(0,1fr))!important; gap:10px!important; margin:0 0 16px!important; }
 html:root.univer-mobile .univer-app-gpa>div { padding:12px 14px!important; }
 html:root.univer-mobile .univer-app-gpa b { font-size:26px!important; }
}
html:root.univer-mobile .univer-app-quick .univer-app-tile.univer-app-external .univer-app-tile-label::after { content:''!important; display:inline-block; width:12px; height:12px; margin-left:6px; background:var(--u-muted); -webkit-mask:var(--univer-external-icon) center/contain no-repeat; }
html:root.univer-mobile .univer-app-quick .univer-app-tile.univer-app-lifted { z-index:3; opacity:1!important; }
html:root.univer-mobile .univer-app-quick .univer-app-tile.univer-app-lifted>a { box-shadow:0 12px 28px rgba(16,40,70,.18),0 2px 6px rgba(16,40,70,.08)!important; border-color:var(--u-brand-line)!important; }
html:root.univer-mobile .univer-app-quick .univer-app-drop-target>a { border-color:var(--u-brand)!important; background:var(--u-brand-soft)!important; }
html:root.univer-mobile .univer-app-quick .univer-app-drop-target { outline:0; }

/* УМКД course cards: the open action spans the card without running past its edge. */
@media (max-width:700px) { html:root.univer-mobile .univer-app-courses .univer-app-course-open { display:flex!important; box-sizing:border-box; width:100%!important; margin:10px 0 0!important; min-height:44px!important; } }
/* Panels and summaries never force a column wider than the phone. */
html:root.univer-mobile .univer-app-overview-grid { grid-template-columns:repeat(auto-fit,minmax(min(340px,100%),1fr))!important; gap:12px!important; }
html:root.univer-mobile .univer-app-overview-head { padding:12px 16px!important; }
html:root.univer-mobile .univer-app-overview-link { display:inline-flex; align-items:center; min-height:44px; }
html:root.univer-mobile .univer-app-overview-body { padding:10px 16px 14px!important; }
/* A lesson row: time with its status under it, the subject keeps the full remaining width. */
html:root.univer-mobile .univer-app-overview li.univer-app-overview-lesson { display:grid!important; grid-template-columns:minmax(84px,max-content) minmax(0,1fr); column-gap:12px; row-gap:6px; align-items:start; }
html:root.univer-mobile .univer-app-overview-lesson>time { grid-column:1; grid-row:1; }
html:root.univer-mobile .univer-app-overview-lesson>.univer-app-overview-lesson-main { grid-column:2; grid-row:1 / span 2; }
html:root.univer-mobile .univer-app-overview-lesson>.univer-app-overview-state { grid-column:1; grid-row:2; justify-self:start; white-space:nowrap; font-size:13px; padding:2px 8px; }
html:root.univer-mobile li.univer-app-overview-now { margin:0 -10px!important; padding:10px!important; }
html:root.univer-mobile .univer-app-overview-more { min-height:44px!important; }
html:root.univer-mobile .univer-app-overview-courses>li { flex-wrap:wrap; gap:6px 12px!important; }
html:root.univer-mobile .univer-app-overview-courses>li>.univer-app-overview-course-link { display:flex!important; align-items:center; flex-basis:100%!important; min-height:40px!important; padding:0!important; font-weight:600!important; }
html:root.univer-mobile .univer-app-overview-scores { justify-content:flex-start; }
html:root.univer-mobile #l4 td.ct.univer-app-panel-head { padding:12px 16px!important; }
html:root.univer-mobile #l4 :is(td.ct,td.tt).univer-app-panel-body { padding:12px 16px!important; }
html:root.univer-mobile .univer-app-ssc-nav { display:flex; width:100%; box-sizing:border-box; margin:8px 0 14px; }
html:root.univer-mobile .univer-app-ssc-nav a { flex:1 1 0; justify-content:center; min-height:44px; }
html:root.univer-mobile #l4 :is(.ts>a,a).univer-app-service-card { flex:1 1 100%!important; width:100%!important; padding:14px!important; }
html:root.univer-mobile .univer-app-service-search { max-width:none; height:48px; }
html:root.univer-mobile .univer-app-credential-button { min-height:44px!important; }
html:root.univer-mobile .univer-app-credentials { padding:14px 16px!important; }
@media (max-width:640px) {
 html:root.univer-mobile .univer-app-credential, html:root.univer-mobile #l4 td.univer-app-credentials-host .univer-app-credential { grid-template-columns:minmax(0,1fr) auto!important; gap:2px 12px!important; }
 html:root.univer-mobile .univer-app-credential-label { grid-column:1/-1; }
}
html:root.univer-mobile #l4 a.univer-app-action-link { min-height:40px!important; }

/* Site chips: drawn arrows instead of text glyphs, and touch-sized. */
html:root.univer-mobile #l4 :is(.ts>a,a.univer-app-page-link,.univer-app-semester-nav a,.univer-app-nav-table a) { min-height:44px!important; }
html:root.univer-mobile #l4 .ts>a:has(>img:is([src*="tb_left"],[src$="/left.png"]))::before, html:root.univer-mobile #l4 .ts>a:has(>img:is([src*="tb_right"],[src$="/right.png"]))::after { content:''!important; width:16px; height:16px; flex:0 0 auto; background:currentColor; -webkit-mask:var(--univer-arrow-icon) center/contain no-repeat; }
html:root.univer-mobile #l4 .ts>a:has(>img:is([src*="tb_left"],[src$="/left.png"]))::before { transform:scaleX(-1); }
html:root.univer-mobile .univer-app-video-link::before { content:''!important; width:16px; height:16px; background:currentColor; -webkit-mask:var(--univer-play-icon) center/contain no-repeat; }
html:root.univer-mobile .univer-app-video-link, html:root.univer-mobile .univer-app-semester-nav a { min-height:44px; }

/* Tables keep their scroll; a phone shows the first columns with room to swipe. */
html:root.univer-mobile .univer-app-table-scroll { -webkit-overflow-scrolling:touch; overscroll-behavior-x:contain; }
html:root.univer-mobile .univer-app-table-scroll :is(th,td) { padding:10px 12px!important; }
html:root.univer-mobile :is(input[type=text],input[type=password],input[type=number],input[type=search],input[type=date],input[type=email],input[type=tel],input:not([type]),select,textarea) { min-height:48px; font-size:16px!important; }
html:root.univer-mobile #l4 :is(input[type=button],input[type=submit],button):not(.univer-app-pin):not(.univer-app-reorder):not(.univer-app-unpin) { min-height:44px; }
html:root.univer-mobile #login_form { padding:20px!important; }
html:root.univer-mobile.univer-app-document body { margin:0!important; padding:16px!important; border:0; border-radius:0; }
/* A document's facts: the value right under its label, the space between facts larger than inside one. */
html:root.univer-mobile.univer-app-document .univer-app-pairs>tbody>tr { gap:2px!important; padding:10px 12px!important; }
html:root.univer-mobile.univer-app-document .univer-app-pairs>tbody>tr>td { padding:0!important; }
html:root.univer-mobile.univer-app-document .univer-app-pairs>tbody>tr>td:nth-child(3) { margin-top:10px!important; }

/* Touch press feedback: a short scale on press, released quickly. Keyboard, Still and reduced motion skip it. */
@media (hover:none) {
 html:root.univer-mobile:not(.univer-app-still):not(.univer-app-keyboard) :is(button:not(.univer-app-reorder):not(.univer-app-unpin):not(.univer-app-pin),input[type=button],input[type=submit],.univer-app-quick .univer-app-tile>a,a.univer-app-service-card,#l3 td.center>a,.ts>a,.univer-app-overview-request) { transition:transform var(--u-release) var(--u-ease-out),background-color 140ms var(--u-ease-out),color 140ms var(--u-ease-out),border-color 140ms var(--u-ease-out),box-shadow 150ms var(--u-ease-out); }
 html:root.univer-mobile:not(.univer-app-still):not(.univer-app-keyboard) :is(button:not(.univer-app-reorder):not(.univer-app-unpin):not(.univer-app-pin),input[type=button],input[type=submit],#l3 td.center>a,.ts>a):active:not(:disabled) { transform:scale(.97); transition-duration:var(--u-press); }
 html:root.univer-mobile:not(.univer-app-still):not(.univer-app-keyboard) :is(.univer-app-quick .univer-app-tile>a,a.univer-app-service-card,.univer-app-overview-request):active { transform:scale(.98); background:var(--u-surface-2)!important; transition-duration:var(--u-press); }
 html:root.univer-mobile :is(.univer-app-pin,.univer-app-reorder,.univer-app-unpin):active { background:var(--u-surface-2)!important; }
}
@media (prefers-reduced-motion:reduce) {
 html:root.univer-mobile :is(button,input,a):active { transform:none!important; }
}

/* Reordering without dragging: the handle reveals earlier/later buttons on the tile. */
html:root.univer-mobile .univer-app-quick .univer-app-move { display:none; position:absolute; left:4px; top:4px; gap:4px; }
/* While moving, the arrows take the icon's place and unpinning waits. */
html:root.univer-mobile .univer-app-quick .univer-app-moving :is(.univer-app-link-icon,.univer-app-unpin) { visibility:hidden; }
html:root.univer-mobile .univer-app-quick .univer-app-moving .univer-app-move { display:flex; }
html:root:root.univer-mobile .univer-app-quick .univer-app-tile.univer-app-moving>a { border-color:var(--u-brand)!important; box-shadow:0 0 0 1px var(--u-brand) inset!important; }
html:root.univer-mobile .univer-app-quick .univer-app-moving .univer-app-reorder { background:var(--u-brand-soft)!important; color:var(--u-brand)!important; }
html:root.univer-mobile .univer-app-move>button { display:grid; place-items:center; width:40px; height:44px; min-height:44px!important; margin:0!important; padding:0!important; border:1px solid var(--u-brand-line)!important; border-radius:10px!important; background:var(--u-surface)!important; color:var(--u-brand)!important; }
html:root.univer-mobile .univer-app-move>button::before { content:''; width:18px; height:18px; background:currentColor; -webkit-mask:var(--univer-arrow-icon) center/contain no-repeat; }
html:root.univer-mobile .univer-app-move>.univer-app-move-earlier::before { transform:scaleX(-1); }
html:root.univer-mobile .univer-app-move>button[aria-disabled=true] { color:var(--u-line-strong)!important; border-color:var(--u-line)!important; }
/* Finished classes stay readable: a quieter colour instead of transparency, which fell below 4.5:1. */
html:root.univer-mobile .univer-app-lesson.univer-app-lesson-done>:not(.univer-app-lesson-badge) { opacity:1!important; }
html:root.univer-mobile .univer-app-lesson.univer-app-lesson-done :is(p,p.univer-app-subject) { color:var(--u-muted)!important; }
html:root.univer-mobile .univer-app-lesson.univer-app-lesson-done time { background:var(--u-surface-2)!important; color:var(--u-muted)!important; }
html:root.univer-mobile .univer-app-overview-done { opacity:1!important; }
html:root.univer-mobile .univer-app-overview-done :is(time,b,span) { color:var(--u-muted)!important; }

/* Compact phone rows: 44px targets, secondary facts in 14px muted text, surfaces apart by tone, no card inside a card. */
html:root.univer-mobile { --u-icon-person:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'%3E%3Ccircle cx='12' cy='8' r='3.5'/%3E%3Cpath d='M5 20c1-3.5 3.8-5.5 7-5.5s6 2 7 5.5'/%3E%3C/svg%3E");
 --u-icon-place:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11z'/%3E%3Ccircle cx='12' cy='10' r='2.3'/%3E%3C/svg%3E");
 --u-icon-dates:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'%3E%3Crect x='4' y='5.5' width='16' height='14.5' rx='2'/%3E%3Cpath d='M4 10h16M8.5 3.5v4M15.5 3.5v4'/%3E%3C/svg%3E");
 --u-icon-chevron:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M9 6l6 6-6 6'/%3E%3C/svg%3E"); }
html:root.univer-mobile .univer-app-sr { position:absolute!important; width:1px!important; height:1px!important; margin:-1px!important; padding:0!important; overflow:hidden!important; clip:rect(0 0 0 0)!important; white-space:nowrap!important; border:0!important; }
html:root.univer-mobile #l4 td.center { padding:8px 12px 24px!important; }

/* Class card: time and type on one line, subject, then teacher and place with their own icons. */
html:root.univer-mobile .univer-app-lesson { padding:10px 14px!important; }
html:root.univer-mobile .univer-app-lesson-head { display:flex; align-items:center; flex-wrap:wrap; gap:6px; }
html:root.univer-mobile .univer-app-lesson-head>time { margin:0!important; font-variant-numeric:tabular-nums; }
html:root.univer-mobile .univer-app-lesson-head>.univer-app-lesson-badge { margin:0!important; }
html:root.univer-mobile .univer-app-lesson-type { margin-left:auto; padding:1px 8px; border-radius:6px; background:var(--u-surface-2); color:var(--u-text); font-size:13px; font-weight:600; line-height:20px; }
html:root.univer-mobile .univer-app-lesson-type[data-kind=lecture] { background:var(--u-brand-soft); color:var(--u-brand-strong,#154c86); }
html:root.univer-mobile .univer-app-lesson-type[data-kind=seminar] { background:#e5f3ea; color:#1d6b3b; }
html:root.univer-mobile .univer-app-lesson-type[data-kind=practice] { background:var(--u-teal-soft); color:#0d5c58; }
html:root.univer-mobile .univer-app-lesson-type[data-kind=lab] { background:#fbeedb; color:#7a4300; }
html:root.univer-mobile .univer-app-lesson p.univer-app-subject { margin:4px 0 0!important; font-size:16px!important; line-height:1.3!important; }
html:root.univer-mobile .univer-app-lesson-facts { display:grid; gap:2px; margin:4px 0 0!important; padding:0!important; list-style:none!important; }
html:root.univer-mobile .univer-app-lesson-facts>li.univer-app-fact { display:flex; align-items:flex-start; flex-wrap:wrap; gap:0 6px; margin:0!important; padding:0!important; list-style:none!important; color:var(--u-muted)!important; font-size:14px!important; line-height:20px!important; }
html:root.univer-mobile .univer-app-fact::before { content:''; flex:0 0 16px; height:16px; margin:2px 2px 0 0; background:currentColor; -webkit-mask:var(--u-fact-icon) center/contain no-repeat; mask:var(--u-fact-icon) center/contain no-repeat; }
html:root.univer-mobile .univer-app-fact-teacher { --u-fact-icon:var(--u-icon-person); }
html:root.univer-mobile .univer-app-lesson-facts>li.univer-app-fact-teacher { color:var(--u-text)!important; }
html:root.univer-mobile .univer-app-fact-place { --u-fact-icon:var(--u-icon-place); }
html:root.univer-mobile .univer-app-fact-dates { --u-fact-icon:var(--u-icon-dates); }
html:root.univer-mobile .univer-app-fact-note::before { visibility:hidden; }
html:root.univer-mobile .univer-app-fact-place>span+span::before { content:'·'; margin-right:6px; }
html:root.univer-mobile .univer-app-schedule:not(.univer-app-schedule-detailed) .univer-app-fact.univer-app-lesson-detail { display:none!important; }

/* Бакалавр "Today": the same card, laid flat inside its panel. */
html:root.univer-mobile .univer-app-overview li.univer-app-overview-lesson:has(>.univer-app-lesson) { display:block!important; margin:0!important; padding:0!important; }
html:root.univer-mobile .univer-app-overview-lesson>.univer-app-lesson { padding:10px 0!important; background:none!important; }
html:root.univer-mobile li.univer-app-overview-now:has(>.univer-app-lesson) { margin:0 -10px!important; padding:0 10px!important; border-radius:10px; }
html:root.univer-mobile .univer-app-lesson-head>.univer-app-overview-state { margin:0!important; padding:1px 8px!important; font-size:13px!important; line-height:20px; }
html:root.univer-mobile .univer-app-overview-body { padding:6px 14px 10px!important; }
html:root.univer-mobile .univer-app-overview-head { padding:6px 14px!important; }
html:root.univer-mobile .univer-app-overview-head>h2 { font-size:17px!important; }
html:root.univer-mobile :is(.univer-app-overview-caption,.univer-app-overview-empty,.univer-app-overview-next-day) { margin:2px 0!important; font-size:14px!important; }
/* УМКД sections fold under their headings (univer-folds.js): the heading is a full-width button with a chevron and the
   count of what it holds. */
html:root:root:root.univer-mobile #l4 table.univer-app-materials>tbody>tr>th[data-univer-fold], html:root:root.univer-mobile #l4 td.tt h4[data-univer-fold] { display:flex!important; align-items:center; gap:10px; box-sizing:border-box; min-height:48px; padding:10px 14px!important; color:var(--u-ink)!important; font-size:15px!important; font-weight:600!important; line-height:1.35!important; text-align:start!important; cursor:pointer; user-select:none; -webkit-tap-highlight-color:transparent; transition:background-color .14s ease; }
html:root:root.univer-mobile #l4 td.tt h4[data-univer-fold] { margin:12px 0 0!important; border:1px solid var(--u-line)!important; border-radius:12px!important; background:var(--u-surface)!important; transition:border-radius .26s cubic-bezier(.4,0,.2,1); }
html:root:root.univer-mobile #l4 td.tt h4[data-univer-fold=open] { border-radius:12px 12px 0 0!important; }
html:root:root:root.univer-mobile #l4 table.univer-app-materials>tbody>tr>th[data-univer-fold]::before, html:root:root.univer-mobile #l4 td.tt h4[data-univer-fold]::before { content:''; flex:0 0 18px; height:18px; background:var(--u-brand); -webkit-mask:var(--u-icon-chevron) center/contain no-repeat; transform:rotate(90deg); transition:transform .26s cubic-bezier(.22,1,.36,1); }
html:root:root:root.univer-mobile #l4 table.univer-app-materials>tbody>tr>th[data-univer-fold=closed]::before, html:root:root.univer-mobile #l4 td.tt h4[data-univer-fold=closed]::before { transform:none; }
html:root:root:root.univer-mobile #l4 table.univer-app-materials>tbody>tr>th[data-univer-fold]::after, html:root:root.univer-mobile #l4 td.tt h4[data-univer-fold]::after { content:attr(data-univer-count); display:inline-flex; align-items:center; justify-content:center; box-sizing:border-box; min-width:28px; height:24px; margin-left:auto; padding:0 9px; border-radius:999px; background:var(--u-brand-soft); color:var(--u-brand); font-size:13px; font-weight:600; }
html:root:root:root.univer-mobile #l4 table.univer-app-materials>tbody>tr>th[data-univer-fold]:active, html:root:root.univer-mobile #l4 td.tt h4[data-univer-fold]:active { background:var(--u-brand-soft)!important; }
html:root:root:root.univer-mobile #l4 table.univer-app-materials>tbody>tr>th[data-univer-fold]:focus-visible, html:root:root.univer-mobile #l4 td.tt h4[data-univer-fold]:focus-visible { outline:2px solid var(--u-brand); outline-offset:-2px; }
/* «Скачать все» of a files section (univer-folds.js) stands in the heading's row under its title, so the title keeps its
   width and the label never breaks; from 600 px it moves beside the title. A row child, not inside the heading button. */
html:root:root:root:root.univer-mobile #l4 button.univer-app-fold-all { display:inline-flex!important; align-items:center; justify-content:center; gap:8px; box-sizing:border-box; width:auto!important; min-width:0!important; height:40px!important; min-height:0!important; margin:0 14px 10px 42px!important; padding:0 14px!important; border:1px solid var(--u-line)!important; border-radius:10px!important; background:var(--u-surface)!important; color:var(--u-brand)!important; font:inherit; font-size:14px!important; font-weight:600!important; line-height:1!important; white-space:nowrap; box-shadow:none!important; -webkit-tap-highlight-color:transparent; }
html:root:root:root:root.univer-mobile #l4 button.univer-app-fold-all::before { content:''; flex:0 0 18px; height:18px; background:currentColor; -webkit-mask:${icons.download} center/contain no-repeat; }
html:root:root:root:root.univer-mobile #l4 button.univer-app-fold-all::after { content:attr(data-label); }
html:root:root:root:root.univer-mobile #l4 button.univer-app-fold-all:active { border-color:var(--u-brand)!important; background:var(--u-brand-soft)!important; }
html:root:root:root:root.univer-mobile #l4 button.univer-app-fold-all:focus-visible { outline:2px solid var(--u-brand); outline-offset:2px; }
@media (min-width:600px) {
 html:root:root:root:root.univer-mobile #l4 table.univer-app-materials>tbody>tr:has(>button.univer-app-fold-all) { position:relative!important; }
 html:root:root:root:root:root.univer-mobile #l4 table.univer-app-materials>tbody>tr:has(>button.univer-app-fold-all)>th[data-univer-fold] { padding-right:200px!important; }
 html:root:root:root:root:root.univer-mobile #l4 button.univer-app-fold-all { position:absolute!important; top:0!important; bottom:0!important; right:8px!important; margin:auto 0!important; }
}
/* The table row form outranks the file cards' tr.file:is(.file) display rule. */ html:root:root:root.univer-mobile #l4 [data-univer-folded-row]:not([data-univer-sliding]), html:root:root:root.univer-mobile #l4 table.univer-app-materials>tbody>tr[data-univer-folded-row]:not([data-univer-sliding]) { display:none!important; }
/* A row sliding shut or open (univer-folds.js slideRow): --univer-slide scales its height, vertical padding, margins and borders,
   and it fades in the first part of a fold and the last part of an unfold. Its links take no taps until it settles. */
html:root:root:root:root:root:root.univer-mobile #l4 [data-univer-sliding] { box-sizing:border-box!important; height:calc(var(--univer-slide-h,0px) * var(--univer-slide,1))!important; min-height:0!important; max-height:none!important; padding-top:calc(var(--univer-slide-pt,0px) * var(--univer-slide,1))!important; padding-bottom:calc(var(--univer-slide-pb,0px) * var(--univer-slide,1))!important; margin-top:calc(var(--univer-slide-mt,0px) * var(--univer-slide,1))!important; margin-bottom:calc(var(--univer-slide-mb,0px) * var(--univer-slide,1))!important; border-top-width:calc(var(--univer-slide-bt,0px) * var(--univer-slide,1))!important; border-bottom-width:calc(var(--univer-slide-bb,0px) * var(--univer-slide,1))!important; overflow:hidden!important; opacity:calc(var(--univer-slide,1) * 1.6 - .6)!important; pointer-events:none!important; }
/* Rating stars: rounded amber stars for the given mark, quiet outlines for the rest; the site's large touch padding stays. */
html:root:root.univer-mobile .univer-app-materials svg.star { color:var(--u-line-strong,#c8d3df); overflow:visible; -webkit-tap-highlight-color:transparent; transition:color .12s ease, transform .12s ease; }
html:root:root.univer-mobile .univer-app-materials svg.star polygon { fill:currentColor!important; stroke:currentColor!important; stroke-width:2.4px; stroke-linejoin:round; transform:translate(1.2px,.1px) scale(.69); }
html:root:root.univer-mobile .univer-app-materials .stars[data-stars]:not([data-stars="0"]) svg.star { color:#f5a524; }
html:root:root.univer-mobile .univer-app-materials .stars[data-stars="1"] svg.star:nth-child(1)~svg.star, html:root:root.univer-mobile .univer-app-materials .stars[data-stars="2"] svg.star:nth-child(2)~svg.star,
html:root:root.univer-mobile .univer-app-materials .stars[data-stars="3"] svg.star:nth-child(3)~svg.star, html:root:root.univer-mobile .univer-app-materials .stars[data-stars="4"] svg.star:nth-child(4)~svg.star { color:var(--u-line-strong,#c8d3df); }
html:root:root.univer-mobile .univer-app-materials svg.star:active { transform:scale(1.15); }
/* Five stars stay on one line on narrow phones: the side padding gives way first (a share of the row's width), the touch height stays. */
html:root:root.univer-mobile .univer-app-materials .univer-app-material-details .stars { flex-wrap:nowrap; }
html:root:root.univer-mobile .univer-app-materials .univer-app-material-details svg.star { padding:13px min(13px, calc((100% - 115px) / 10)); }
/* Room after the last row of a page for «Наверх» (48 px, 28 px over the bottom edge, kept in its corner): the last row scrolls clear of it. */
html:root.univer-mobile:not(.univer-app-document) body::after { content:''; display:block; height:92px; }
/* A row's action (Е-услуги → Услуги: «Подать заявку») ends before the button's column, so the button never covers it at any
   scroll stop: the window less the row's start (40 px), the button's edge and gap (24 px) and the widest label, «Back to top»
   (58 px of icon and padding and 5.4em of text, growing with the text size as the button does). A longer label wraps.
   Only below 900 px: wider, the rows are a table whose action cell is as narrow as its label, and wrapping would break it. */
@media (max-width:899.98px) { html:root.univer-mobile:not(.univer-app-document) a.univer-app-action-link { max-width:calc(100vw - 122px - 5.4em); white-space:normal!important; } }
html:root.univer-mobile .univer-app-overview-upnext { display:flex; flex-direction:column; gap:2px; margin:6px 0!important; padding:10px 14px; border-radius:12px; background:var(--u-teal-soft,#e3f3ef); }
html:root.univer-mobile .univer-app-overview-upnext>b { color:var(--u-teal,#0f766e); font-size:15px; font-weight:600; line-height:1.35; }
html:root.univer-mobile .univer-app-overview-upnext>span { color:var(--u-ink,#16283b); font-size:13px; line-height:1.4; overflow-wrap:anywhere; }
html:root.univer-mobile .univer-app-overview-upnext[data-state=now] { background:var(--u-brand-soft,#e9f1fb); }
html:root.univer-mobile .univer-app-overview-upnext[data-state=now]>b { color:var(--u-brand,#1b5fa6); }

/* Grade summaries: one row per course, name on the left, period scores on the right. */
html:root.univer-mobile .univer-app-overview-courses>li { display:grid!important; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:2px 10px!important; padding:4px 0!important; }
html:root.univer-mobile .univer-app-overview-courses>li>.univer-app-overview-course-link { min-height:44px!important; font-size:15px!important; line-height:1.3!important; }
/* "Show all (N)" hides the items past the limit with [hidden]; the grid rules above must not undo it. */
html:root:root:root:root.univer-mobile :is(.univer-app-overview-lessons,.univer-app-overview-courses,.univer-app-overview-requests)>li[hidden][hidden] { display:none!important; }
html:root.univer-mobile .univer-app-overview-scores { flex-wrap:nowrap!important; gap:6px!important; }
html:root.univer-mobile .univer-app-overview-score { padding:4px 8px!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overview-list>li { display:grid!important; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:2px 10px; padding:2px 0!important; }
html:root.univer-mobile #l4 button.univer-app-attendance-overview-name { justify-content:flex-start; text-align:start; font-size:15px!important; line-height:1.3!important; padding:6px 0!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overview-list .univer-app-attendance-scores { flex-wrap:nowrap!important; gap:6px!important; margin:0!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overview { padding:8px 12px!important; }

/* Course journal: the course total is a row of compact scores instead of two large tiles. */
html:root.univer-mobile #l4 .univer-app-attendance-summary { padding:10px 12px!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overall-block { display:flex; align-items:center; flex-wrap:wrap; gap:6px 10px; margin:0 0 6px!important; padding:0!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overall-block>p { margin:0!important; font-size:14px!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overall-block .univer-app-attendance-scores { display:flex!important; flex-wrap:nowrap; gap:6px!important; margin:0!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overall-block .univer-app-score { flex:0 0 auto!important; min-width:0!important; padding:4px 10px!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overall-block .univer-app-score-value { font-size:17px!important; }
html:root.univer-mobile #l4 .univer-app-attendance-type-totals { margin:0!important; padding:4px 0 0!important; }
html:root.univer-mobile #l4 .univer-app-type-row { min-height:36px; padding:2px 0!important; }
html:root.univer-mobile #l4 details.univer-app-course-heading>summary { min-height:44px; padding:2px 0; }
html:root.univer-mobile #l4 table.mt>tbody>tr.top>td.ct:has(details.univer-app-course-heading) { padding:8px 0 2px!important; }

/* Home: tiles are one compact row each (icon beside the name); reordering lives behind a long press. */
@media (max-width:599px) {
 html:root.univer-mobile .univer-app-quick { gap:8px!important; margin:4px 0 4px!important; }
 html:root.univer-mobile .univer-app-quick .univer-app-tile>a { flex-direction:row!important; align-items:center!important; gap:10px!important; min-height:52px!important; height:100%; padding:8px 10px!important; border:0!important; font-size:15px!important; line-height:1.25!important; -webkit-user-select:none; user-select:none; -webkit-touch-callout:none; }
 html:root.univer-mobile .univer-app-quick .univer-app-link-icon { flex:0 0 auto; width:32px!important; height:32px!important; border-radius:8px!important; }
 html:root.univer-mobile .univer-app-quick .univer-app-tile:not(.univer-app-moving) :is(.univer-app-reorder,.univer-app-unpin):not(:focus-visible) { opacity:0; pointer-events:none; }
 html:root.univer-mobile .univer-app-quick .univer-app-tile.univer-app-moving { grid-column:1/-1; }
 /* Edit mode: three 40px buttons (earlier, later, remove) sit at the left edge and the drag handle at the right; the label sits between them. */
 html:root:root.univer-mobile .univer-app-quick .univer-app-tile.univer-app-moving>a { padding-left:144px!important; padding-right:60px!important; }
 html:root.univer-mobile .univer-app-quick .univer-app-moving .univer-app-link-icon { display:none; }
 html:root.univer-mobile .univer-app-quick :is(.univer-app-reorder,.univer-app-unpin,.univer-app-move) { top:50%!important; transform:translateY(-50%)!important; }
 html:root.univer-mobile .univer-app-gpa { display:flex!important; gap:0!important; margin:0 0 12px!important; border-radius:12px; background:var(--u-surface); overflow:hidden; }
 html:root.univer-mobile .univer-app-gpa>div { flex:1 1 0; display:flex; align-items:baseline; gap:8px; padding:10px 14px!important; border:0!important; border-radius:0!important; box-shadow:none!important; background:none!important; }
 html:root.univer-mobile .univer-app-gpa>div+div { box-shadow:inset 1px 0 0 var(--u-line)!important; }
 html:root.univer-mobile .univer-app-gpa b { order:2; font-size:20px!important; }
 html:root.univer-mobile .univer-app-gpa span { order:1; margin:0!important; font-size:13px!important; }
}
html:root.univer-mobile .univer-app-quick .univer-app-tile.univer-app-lifted>a { box-shadow:0 10px 24px rgba(16,40,70,.16),0 2px 6px rgba(16,40,70,.08)!important; }

/* Profile and record facts: label and value side by side; long values wrap under their own column. */
html:root.univer-mobile #l4 table.univer-app-pairs>tbody>tr { display:grid!important; grid-template-columns:minmax(92px,38%) minmax(0,1fr)!important; gap:2px 12px!important; margin:0!important; padding:8px 0!important; border:0!important; border-bottom:1px solid var(--u-line)!important; border-radius:0!important; background:none!important; box-shadow:none!important; }
html:root.univer-mobile #l4 table.univer-app-pairs>tbody>tr:last-child { border-bottom:0!important; }
html:root.univer-mobile #l4 table.univer-app-pairs>tbody>tr>td { margin:0!important; padding:2px 0!important; font-size:15px!important; line-height:1.35!important; }
html:root.univer-mobile #l4 table.univer-app-pairs>tbody>tr>td:nth-child(odd) { color:var(--u-muted)!important; font-size:13px!important; font-weight:500!important; }
html:root.univer-mobile #l4 table.univer-app-pairs>tbody>tr>td:nth-child(odd) * { color:inherit!important; font:inherit!important; }
html:root.univer-mobile #l4 :is(td.ct,td.tt).univer-app-panel-body { padding:8px 14px!important; }
html:root.univer-mobile #l4 td.ct.univer-app-panel-head { padding:8px 14px!important; }
html:root.univer-mobile .univer-app-credentials { padding:8px 14px!important; }
html:root.univer-mobile .univer-app-credential { padding:6px 0!important; }

/* Е-услуги: the two desk entries become a compact pair; their descriptions stay for screen readers. */
html:root.univer-mobile .univer-app-desk-cards { display:grid!important; grid-template-columns:1fr 1fr; gap:8px!important; margin:4px 0 12px!important; }
html:root.univer-mobile a.univer-app-desk-card { flex-direction:row!important; align-items:center!important; gap:8px!important; min-height:48px!important; padding:6px 10px!important; }
html:root.univer-mobile a.univer-app-desk-card .univer-app-link-icon { width:28px!important; height:28px!important; flex:0 0 auto; }
html:root.univer-mobile a.univer-app-overview-request { min-height:52px!important; padding:6px 0!important; }

/* УМКД: a course is one tappable row with a chevron; the open button covers the row. */
html:root.univer-mobile #l4 table.univer-app-courses tr.link { position:relative!important; overflow:hidden; min-height:56px; padding:10px 52px 10px 14px!important; }
html:root.univer-mobile #l4 table.univer-app-courses tr.link>td { padding:0!important; }
html:root.univer-mobile #l4 table.univer-app-courses .univer-app-course-open { position:absolute!important; top:50%!important; right:6px!important; width:44px!important; min-height:44px!important; height:44px!important; margin:0!important; padding:0!important; transform:translateY(-50%); border:0!important; border-radius:10px!important; background:transparent!important; color:var(--u-brand)!important; font-size:0!important; box-shadow:none!important; }
html:root.univer-mobile #l4 table.univer-app-courses .univer-app-course-open::before { content:''; display:block; width:20px; height:20px; margin:auto; background:currentColor; -webkit-mask:var(--u-icon-chevron) center/contain no-repeat; mask:var(--u-icon-chevron) center/contain no-repeat; }
html:root.univer-mobile #l4 table.univer-app-courses .univer-app-course-open::after { content:''; position:absolute; top:-200px; bottom:-200px; right:-6px; width:100vw; }
html:root.univer-mobile #l4 td.ts:has(>a.univer-app-page-link) { display:flex!important; flex-wrap:wrap; gap:6px; padding:4px 0 8px!important; }
html:root.univer-mobile #l4 td.ts>a.univer-app-page-link { flex:1 1 auto; margin:0!important; padding:6px 10px!important; font-size:14px!important; }

/* News: a five-line preview, the date on one quiet line, no rule under it. */
html:root.univer-mobile .univer-app-news .news { padding:12px 14px!important; }
html:root.univer-mobile .univer-app-news .news>td:first-child { display:flex!important; align-items:center; flex-wrap:wrap; gap:6px; margin:0 0 6px!important; padding:0!important; border:0!important; }
html:root.univer-mobile .univer-app-news .news>td:first-child .date { display:inline-flex!important; gap:0!important; padding:2px 8px!important; font-size:13px!important; font-weight:600; }
html:root.univer-mobile .univer-app-news .news>td:first-child .date * { margin:0!important; padding:0!important; font-size:inherit!important; font-weight:inherit!important; line-height:20px!important; }
html:root.univer-mobile .univer-app-news .news>td:first-child .date .day::after { content:'.'; }
html:root.univer-mobile .univer-app-news-body[data-collapsed=true] { max-height:132px!important; }
html:root.univer-mobile #l4 .univer-app-news-tools { padding:8px!important; }

/* All sections: flat groups inside the one menu card, headings as quiet text, rows at the touch minimum. */
html:root.univer-mobile:not(.univer-app-unified) .univer-app-menu>summary { min-height:48px; }
html:root.univer-mobile #tools .univer-app-category { margin:0!important; border:0!important; border-radius:0!important; background:none!important; box-shadow:none!important; }
html:root.univer-mobile #tools .univer-app-category>summary { min-height:40px!important; padding:0 4px!important; background:none!important; border:0!important; color:var(--u-muted)!important; font-size:13px!important; font-weight:600!important; }
html:root.univer-mobile #tools .univer-app-category li { margin:0!important; border:0!important; }
html:root.univer-mobile #tools li a { min-height:44px!important; padding:8px 4px!important; border:0!important; border-radius:8px!important; background:none!important; font-size:15px!important; }
html:root.univer-mobile #tools>tbody>tr>td.center { gap:4px!important; }
html:root.univer-mobile .univer-app-pin { opacity:.7!important; }
/* Facts sit flush under the subject on every page, without the site's list indents or row rules. */
html:root.univer-mobile #l4 .univer-app-lesson ul.univer-app-lesson-facts { margin:4px 0 0!important; padding:0!important; }
html:root.univer-mobile #l4 .univer-app-lesson ul.univer-app-lesson-facts>li { margin:0!important; padding:0!important; border:0!important; background:none!important; }
/* Emphasis by tone, never by a coloured side stripe. */
html:root.univer-mobile .univer-app-now { border:0!important; background:var(--u-surface)!important; box-shadow:none!important; }
html:root.univer-mobile #l4 .univer-app-syllabi { border:0!important; background:var(--u-brand-soft)!important; }
html:root.univer-mobile #l4 td.ct.univer-app-panel-body ol.univer-app-list>li { border:0!important; background:#fdf3e4!important; }
html:root.univer-mobile .univer-app-lesson.univer-app-lesson-now { box-shadow:none!important; background:var(--u-brand-soft)!important; }
html:root.univer-mobile .univer-app-lesson.univer-app-lesson-next { box-shadow:none!important; }
html:root.univer-mobile #tools a[aria-current=page] { box-shadow:none!important; border:0!important; }
/* Journal: course headings close to their summary; scores read as one line "РК1 60 / 100". */
html:root.univer-mobile #l4 details.univer-app-course-heading { margin:4px 0 2px!important; padding-top:6px!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overall-block .univer-app-score { position:relative; display:inline-flex!important; align-items:baseline; gap:4px; overflow:hidden; padding:4px 10px 6px!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overall-block .univer-app-score-label { font-size:13px!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overall-block .univer-app-score-bar { position:absolute!important; left:0; right:0; bottom:0; margin:0!important; }
html:root.univer-mobile #l4 .univer-app-attendance-summary { margin-bottom:8px!important; }
/* Credential actions: compact buttons that still meet the 44px touch height. */
html:root.univer-mobile .univer-app-credential-button { min-height:44px!important; padding:0 12px!important; font-size:14px!important; }
/* Е-услуги: both desk entries in one row inside the page margins; the hint text stays in the link's data only. */
html:root.univer-mobile #l4 .univer-app-desk-cards:has(>a.univer-app-service-card) { display:grid!important; grid-template-columns:minmax(0,1fr) minmax(0,1fr)!important; gap:8px!important; margin:4px 12px 12px!important; }
html:root.univer-mobile #l4 a.univer-app-service-card.univer-app-desk-card { display:flex!important; flex:none!important; align-items:center!important; gap:8px!important; width:auto!important; min-width:0!important; min-height:48px!important; box-sizing:border-box!important; padding:6px 10px!important; font-size:15px!important; }
html:root.univer-mobile #l4 a.univer-app-service-card.univer-app-desk-card::after { display:none!important; }
html:root.univer-mobile #l4 .univer-app-overview-requests-panel { margin:0 12px 12px!important; }
html:root.univer-mobile #l4 .univer-app-overview-requests>li>a.univer-app-overview-request { margin:0!important; padding:6px 0!important; }
/* Cards stand one above the other on the same pages: they share one inner edge. */
html:root.univer-mobile #l4 table.mt.univer-app-panel>tbody>tr>td:is(.ct.univer-app-panel-head,.univer-app-panel-body) { padding-left:14px!important; padding-right:14px!important; }
html:root.univer-mobile #l4 td.univer-app-credentials-host>.univer-app-credentials { padding:0!important; }
html:root.univer-mobile #l4 table.mt.univer-app-panel>tbody>tr.univer-app-study-empty { display:none!important; }
html:root.univer-mobile #l4 table.mt.univer-app-panel>tbody>tr>td.ts { padding-left:14px!important; padding-right:14px!important; }
/* Both service lists: the tabs stay inside the page's margins, whatever an older rule says of their width. */
html:root.univer-mobile #l4 .univer-app-ssc-nav { width:auto!important; }
/* A request's card: its status cell carries the status dot on the pseudo-element a field's label needs, so the label wins. */
html:root.univer-mobile #l4 tr.univer-app-card-row>td.univer-app-status[data-univer-field]::before { width:auto!important; height:auto!important; margin:0!important; border-radius:0!important; background:none!important; }
html:root.univer-mobile #l4 tr>:is(td,th).univer-app-blank-field { display:none!important; }
html:root.univer-mobile #l4 table.univer-app-pairs>tbody>tr:not(:has(>td:not(.univer-app-blank-field))) { display:none!important; }
html:root.univer-mobile #l4 table.xp.univer-app-pairs { box-sizing:border-box; padding:4px 14px!important; }
/* On the narrowest phones a word must not split: labels get their room and a record's fields one column. */
@media (max-width:360px) {
  html:root.univer-mobile #l4 table.univer-app-pairs>tbody>tr { grid-template-columns:minmax(120px,38%) minmax(0,1fr)!important; }
  html:root.univer-mobile #l4 tr.univer-app-card-row { grid-template-columns:minmax(0,1fr)!important; }
}
html:root.univer-mobile #l4 table.xp.univer-app-pairs>tbody>tr>td { border:0!important; }
html:root.univer-mobile #l4 .univer-app-table-scroll:has(>table.univer-app-empty-note) { overflow:hidden!important; }
/* A table with nothing in it says so: no column headings over the note, nothing to swipe. */
html:root.univer-mobile #l4 table.inner:has(tr.univer-app-empty-row):not(:has(tr:not(.univer-app-empty-row)>td)) tr:has(>th) { display:none!important; }
html:root.univer-mobile #l4 .univer-app-table-scroll>table:has(tr.univer-app-empty-row):not(:has(tr:not(.univer-app-empty-row)>td)) { width:100%!important; min-width:0!important; }
/* The questionnaire and the adviser's page: facts stand on the page itself, at its full width. */
html:root.univer-mobile #l4 table.univer-app-pairs>tbody>tr>td { background:none!important; }
html:root.univer-mobile #l4 td.tt>div.inner:has(>table.univer-app-pairs) { display:block!important; width:auto!important; }
html:root.univer-mobile #l4 td.tt>div.inner>table.inner:not(.univer-app-pairs,.univer-app-form-table,.univer-app-matrix,.univer-app-cards,.univer-app-data)>tbody>tr>td { background:none!important; }
html:root.univer-mobile #l4 td.tt>div.inner table td>li { margin:0!important; padding:0!important; list-style:none!important; }
html:root.univer-mobile #l4 td.tt hr { height:1px!important; margin:8px 0!important; border:0!important; background:var(--u-line)!important; }
/* A form table's read-only row is a fact too: its value stands right under the label. */
html:root.univer-mobile #l4 .univer-app-form-table>tbody>tr:not(:has(input,select,textarea,button,img,table))>:is(td,th):nth-child(odd) { padding:10px 0 2px!important; }
html:root.univer-mobile #l4 .univer-app-form-table>tbody>tr:not(:has(input,select,textarea,button,img,table))>:is(td,th):nth-child(even) { padding:0 0 10px!important; }
/* The site's 16px raster door blurs on a phone; the action gets a line icon like every other. */
html:root.univer-mobile #l4 a#openButton>img { display:none!important; }
html:root.univer-mobile #l4 a#openButton::before { content:''; width:16px; height:16px; flex:0 0 auto; background:currentColor; -webkit-mask:${icons.doc} center/contain no-repeat; mask:${icons.doc} center/contain no-repeat; }
/* Sorting opens and closes: it reads as a control, not as a heading. */
html:root.univer-mobile .univer-app-sort-toggle { display:flex; align-items:center; justify-content:space-between; gap:8px; min-height:44px; padding:0 12px!important; border-radius:10px!important; }
html:root.univer-mobile .univer-app-sort-toggle::after { content:''; width:16px; height:16px; flex:0 0 auto; background:var(--u-select-arrow) center/contain no-repeat; }
html:root.univer-mobile .univer-app-sort-toggle[aria-expanded=true]::after { transform:rotate(180deg); }
html:root.univer-mobile #l4 .univer-app-sort-head>th { display:inline-flex!important; align-items:center; min-height:44px; box-sizing:border-box; padding:0 12px!important; border:1px solid var(--u-line)!important; border-radius:10px!important; background:var(--u-surface)!important; color:var(--u-ink)!important; font-size:14px!important; font-weight:500!important; }
html:root.univer-mobile #l4 .univer-app-sort-head>th:empty { display:none!important; }
/* News: the date chip is as tall as its text. */
html:root.univer-mobile .univer-app-news .news>td:first-child .date, html:root.univer-mobile .univer-app-news .news>td:first-child .date * { height:auto!important; min-height:0!important; }
html:root.univer-mobile .univer-app-news .news>td:first-child .date { align-items:center!important; }
/* All sections: the pin stays at the row's end whatever the label length. */
html:root.univer-mobile #tools .univer-app-pin-row { display:flex!important; align-items:center; gap:4px; width:100%; }
html:root.univer-mobile #tools .univer-app-pin-row>a { flex:1 1 auto; min-width:0; }
html:root.univer-mobile #tools .univer-app-pin-row>.univer-app-pin { flex:0 0 44px; }
/* The site's decorative panel footer row adds a gradient bar under phone content. */
html:root.univer-mobile #l4 table.mt>tbody>tr.empty { display:none!important; }
/* УМКД: the course type is an icon after the name (asterisk: required, fork: elective); the words stay spoken. */
html:root.univer-mobile #l4 table.univer-app-courses tr.link[data-univer-course-kind]>td:nth-child(3) { height:0!important; overflow:visible; padding:0!important; font-size:0!important; line-height:0!important; color:transparent!important; }
html:root.univer-mobile #l4 table.univer-app-courses tr.link[data-univer-course-kind]>td:nth-child(2)::after { content:''; display:inline-block; width:16px; height:16px; margin-left:8px; vertical-align:-2px; -webkit-mask:var(--u-course-kind) center/contain no-repeat; mask:var(--u-course-kind) center/contain no-repeat; }
html:root.univer-mobile #l4 table.univer-app-courses tr.link[data-univer-course-kind=required]>td:nth-child(2)::after { background:var(--u-brand); --u-course-kind:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2.4' stroke-linecap='round'%3E%3Cpath d='M12 4v16M5.1 8l13.8 8M5.1 16l13.8-8'/%3E%3C/svg%3E"); }
html:root.univer-mobile #l4 table.univer-app-courses tr.link[data-univer-course-kind=elective]>td:nth-child(2)::after { background:var(--u-teal); --u-course-kind:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Ccircle cx='6' cy='5' r='2'/%3E%3Ccircle cx='18' cy='5' r='2'/%3E%3Ccircle cx='12' cy='19' r='2'/%3E%3Cpath d='M6 7v2a4 4 0 0 0 4 4h4a4 4 0 0 0 4-4V7M12 13v4'/%3E%3C/svg%3E"); }
html:root.univer-mobile #l4 table.univer-app-courses tr.link[data-univer-course-kind] { padding-top:14px!important; padding-bottom:14px!important; }
/* Three or more scores (with an exam) move under the course name, which keeps its words whole. */
html:root.univer-mobile .univer-app-overview-courses>li:has(.univer-app-overview-score:nth-child(3)), html:root.univer-mobile #l4 .univer-app-attendance-overview-list>li:has(.univer-app-score:nth-child(3)) { grid-template-columns:minmax(0,1fr)!important; }
html:root.univer-mobile :is(.univer-app-overview-course-link,button.univer-app-attendance-overview-name) { word-break:normal!important; overflow-wrap:normal!important; hyphens:manual; }
html:root.univer-mobile .univer-app-overview-courses>li:has(.univer-app-overview-score:nth-child(3)) .univer-app-overview-scores { flex-wrap:wrap!important; }
/* УМКД course materials: section names as quiet headings, each file a compact row (name, then language · size, then details). */
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr>th:only-of-type { margin:0!important; padding:14px 2px 6px!important; border:0!important; border-radius:0!important; background:none!important; box-shadow:none!important; color:var(--u-muted)!important; font-size:13px!important; font-weight:600!important; line-height:1.35!important; text-align:start!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr:has(>th:only-of-type) { margin:0!important; padding:0!important; border:0!important; background:none!important; box-shadow:none!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file { display:flex!important; flex-wrap:wrap; align-items:center; gap:2px 0!important; margin:0 0 6px!important; padding:10px 12px 4px!important; border:0!important; border-radius:12px!important; background:var(--u-surface)!important; box-shadow:none!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file>td { flex:0 0 auto; margin:0!important; padding:0!important; min-width:0!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file>td:nth-child(2) { flex:1 1 100%; font-weight:600!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file>td:nth-child(3):empty { display:none!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file>td:nth-child(3):not(:empty) { flex:1 1 100%; color:var(--u-text)!important; font-size:14px!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file>td:is(:nth-child(4),:nth-child(5)) { color:var(--u-muted)!important; font-size:13px!important; line-height:20px!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file>td:is(:nth-child(4),:nth-child(5))::before { content:none!important; display:none!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file>td:nth-child(5) { padding-left:6px!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file>td:nth-child(5)::before { content:'· '!important; display:inline!important; font-size:inherit!important; color:inherit!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file>td:has(.univer-app-material-details) { flex:1 1 100%; }
html:root.univer-mobile #l4 table.univer-app-materials a.downLoad { display:flex!important; align-items:flex-start; gap:8px!important; min-height:44px!important; margin:0!important; padding:2px 0!important; border:0!important; border-radius:0!important; background:none!important; box-shadow:none!important; color:var(--u-brand-strong,#154c86)!important; font-size:15px!important; font-weight:600!important; line-height:1.35!important; text-decoration:none!important; overflow-wrap:anywhere; }
html:root.univer-mobile #l4 table.univer-app-materials a.downLoad::before { flex:0 0 18px!important; width:18px!important; height:18px!important; margin-top:1px; }
html:root.univer-mobile #l4 table.univer-app-materials a.downLoad::after { content:none!important; display:none!important; }
html:root.univer-mobile #l4 .univer-app-material-details>summary { min-height:40px!important; padding:0!important; color:var(--u-brand)!important; font-size:13px!important; font-weight:600!important; }
html:root.univer-mobile #l4 .univer-app-material-details>div { padding:0 0 6px!important; font-size:14px!important; }
html:root.univer-mobile #l4 div.inner>a.univer-app-page-link { display:inline-flex!important; margin:0 6px 6px 0!important; padding:6px 10px!important; font-size:14px!important; }
/* УМКД list: the type icon stays beside the last line of the course name instead of wrapping alone. */
html:root.univer-mobile #l4 table.univer-app-courses tr.link[data-univer-course-kind]>td:nth-child(2) { display:flex!important; align-items:center; gap:8px; }
html:root.univer-mobile #l4 table.univer-app-courses tr.link[data-univer-course-kind]>td:nth-child(2)::after { flex:0 0 16px; margin-left:0!important; }
/* News filters: the toggle keeps its place beside the search field; the panel opens below the whole row. */
html:root.univer-mobile #l4 .univer-app-news-tools { display:flex!important; flex-wrap:wrap!important; align-items:center!important; gap:8px!important; }
html:root.univer-mobile #l4 .univer-app-news-tools>input.univer-app-news-query { flex:1 1 150px!important; min-width:0!important; }
/* "Collapse all sections" closes the menu from its end; quiet, full-width, at the touch minimum. */
html:root.univer-mobile .univer-app-menu>button.univer-app-menu-collapse { display:flex!important; align-items:center; justify-content:center; gap:8px; width:calc(100% - 24px)!important; min-height:44px!important; margin:4px 12px 12px!important; padding:0 12px!important; border:0!important; border-radius:10px!important; background:var(--u-surface-2)!important; color:var(--u-brand)!important; font-size:15px!important; font-weight:600!important; }
html:root.univer-mobile .univer-app-menu>button.univer-app-menu-collapse::after { content:''; width:7px; height:7px; margin-top:4px; border-right:2px solid currentColor; border-bottom:2px solid currentColor; transform:rotate(-135deg); opacity:.7; }
html:root.univer-mobile .univer-app-menu:has(input[type=search]:not(:placeholder-shown)) .univer-app-menu-collapse { display:none!important; }
/* УМКД list: the chevron button is exactly 44px, so it never sits over the course name. */
html:root.univer-mobile #l4 table.univer-app-courses .univer-app-course-open { min-width:44px!important; max-width:44px!important; }
/* УМКД materials: the file name sits tight above its language and size. */
html:root.univer-mobile #l4 table.univer-app-materials a.downLoad { align-items:center!important; min-height:36px!important; padding:0!important; }
/* All sections: "Useful links" is a flat group like the others, not a card inside the menu. */
html:root.univer-mobile .univer-app-menu>.univer-app-useful { margin:0 12px!important; padding:0!important; border:0!important; border-radius:0!important; background:none!important; box-shadow:none!important; }
html:root.univer-mobile .univer-app-menu>.univer-app-useful>summary { min-height:40px!important; padding:0 4px!important; border:0!important; background:none!important; color:var(--u-muted)!important; font-size:13px!important; font-weight:600!important; }
html:root.univer-mobile .univer-app-menu>.univer-app-useful ul { padding:0!important; gap:0!important; }
html:root.univer-mobile .univer-app-menu>.univer-app-useful li>a { min-height:44px!important; padding:6px 4px!important; border:0!important; border-radius:8px!important; background:none!important; box-shadow:none!important; font-size:15px!important; }
html:root.univer-mobile .univer-app-menu>.univer-app-useful li { margin:0!important; padding:0!important; border:0!important; border-radius:0!important; background:none!important; box-shadow:none!important; }
/* All sections: every group's list spans the full width, so pins line up on one right edge. */
html:root.univer-mobile #tools table.tool, html:root.univer-mobile #tools table.tool>tbody, html:root.univer-mobile #tools table.tool td.top-center { width:100%!important; box-sizing:border-box; }
html:root.univer-mobile #tools table.tool ul { width:100%!important; box-sizing:border-box; margin:0!important; padding:0!important; }
/* Group names read as headings: darker, bold, with room above; their items sit indented in regular weight. */
html:root.univer-mobile #tools .univer-app-category>summary, html:root.univer-mobile .univer-app-menu>.univer-app-useful>summary { min-height:44px!important; margin-top:6px!important; padding:0 4px!important; color:var(--u-ink)!important; font-size:15px!important; font-weight:600!important; }
html:root.univer-mobile #tools .univer-app-category li a, html:root.univer-mobile .univer-app-menu>.univer-app-useful li>a { padding-left:16px!important; color:var(--u-text)!important; font-weight:400!important; }
html:root.univer-mobile #tools .univer-app-category:not([open])>summary, html:root.univer-mobile .univer-app-menu>.univer-app-useful:not([open])>summary { color:var(--u-text)!important; }
html:root.univer-mobile #tools a[aria-current=page] { color:var(--u-brand)!important; font-weight:600!important; }
/* Some site items are inline-blocks holding two sections; each row still spans the menu width. */
html:root.univer-mobile #tools table.tool li { display:block!important; width:100%!important; box-sizing:border-box; }
/* УМКД, subjects: one compact row per subject - its number, the name, and the type in words (required / elective) -
   the whole row opens the subject. The selectors repeat [id] and :is(.link) only to outrank the earlier layers above. */
html:root.univer-mobile #l4 table.univer-app-courses>tbody { counter-reset:umkd-course; }
html:root.univer-mobile #l4 table.univer-app-courses>tbody>tr.link[id]:is(.link) { counter-increment:umkd-course; position:relative!important; display:grid!important; grid-template-columns:36px minmax(0,1fr); grid-template-rows:auto auto; column-gap:12px; row-gap:2px; align-items:center; min-height:0!important; margin:0 0 8px!important; padding:11px 52px 11px 12px!important; border:1px solid var(--u-line)!important; border-radius:14px!important; background:var(--u-surface)!important; box-shadow:none!important; }
html:root.univer-mobile #l4 table.univer-app-courses>tbody>tr.link[id]:is(.link)::before { content:counter(umkd-course); grid-column:1; grid-row:1/3; display:grid; place-items:center; width:36px; height:36px; border-radius:10px; background:var(--u-brand-soft); color:var(--u-brand); font-size:14px; font-weight:600; font-variant-numeric:tabular-nums; }
html:root.univer-mobile #l4 table.univer-app-courses>tbody>tr.link[id][data-univer-course-kind=elective]:is(.link)::before { background:var(--u-teal-soft); color:var(--u-teal); }
html:root.univer-mobile #l4 table.univer-app-courses>tbody>tr.link[id]:is(.link)>td:nth-child(2) { grid-column:2; grid-row:1; display:block!important; min-width:0!important; padding:0!important; color:var(--u-ink)!important; font-size:15px!important; font-weight:600!important; line-height:1.3!important; }
html:root.univer-mobile #l4 table.univer-app-courses>tbody>tr.link[id]:is(.link)>td:nth-child(2)::after { content:none!important; display:none!important; }
html:root.univer-mobile #l4 table.univer-app-courses>tbody>tr.link[id]:is(.link)>td:nth-child(3) { grid-column:2; grid-row:2; display:flex!important; align-items:center; gap:6px; min-width:0!important; height:auto!important; padding:0!important; overflow:visible; color:var(--u-muted)!important; font-size:13px!important; line-height:1.3!important; }
html:root.univer-mobile #l4 table.univer-app-courses>tbody>tr.link[id]:is(.link)>td:nth-child(3)::before { content:''; flex:0 0 8px; width:8px; height:8px; border-radius:50%; background:var(--u-brand); }
html:root.univer-mobile #l4 table.univer-app-courses>tbody>tr.link[id][data-univer-course-kind=elective]:is(.link)>td:nth-child(3)::before { background:var(--u-teal); }
html:root.univer-mobile #l4 table.univer-app-courses>tbody>tr.link[id]:is(.link) .univer-app-course-open { right:4px!important; background:transparent!important; color:var(--u-muted)!important; box-shadow:none!important; }
html:root.univer-mobile #l4 table.univer-app-courses>tbody>tr.link[id]:is(.link):active { background:var(--u-surface-2)!important; }
/* УМКД, materials: a file is a compact card - a type badge, the name, "language · size · date", a download mark, and a fold with the description and rating. The whole card downloads. */
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr:has(>th:only-of-type)>th { padding:18px 2px 8px!important; color:var(--u-ink)!important; font-size:14px!important; font-weight:600!important; letter-spacing:0; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file) { position:relative; display:flex!important; flex-wrap:wrap; align-items:center; gap:0 8px!important; min-height:64px; margin:0 0 8px!important; padding:10px 56px 4px 64px!important; border:1px solid var(--u-line)!important; border-radius:14px!important; background:var(--u-surface)!important; box-shadow:none!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file)::before { content:attr(data-univer-ext-label); position:absolute; left:12px; top:12px; display:grid; place-items:center; box-sizing:border-box; width:40px; height:40px; border-radius:10px; background:var(--ext,#64748b); color:#fff; font-size:11px; font-weight:600; line-height:1; overflow:hidden; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file)::after { content:''; position:absolute; right:12px; top:14px; width:36px; height:36px; border-radius:50%; background:var(--u-brand-soft) ${icons.downloadBrand} center/18px no-repeat; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file[data-univer-ext=pdf] { --ext:#d92d20; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file[data-univer-ext=doc] { --ext:#1d5fbf; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file[data-univer-ext=xls] { --ext:#15803d; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file[data-univer-ext=ppt] { --ext:#d9560b; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file[data-univer-ext=zip] { --ext:#7a4fd6; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file[data-univer-ext=img] { --ext:#0e8f84; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file[data-univer-ext=media] { --ext:#c11574; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file[data-univer-ext=txt] { --ext:#475467; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file)>td:nth-child(2) { flex:1 1 100%; order:1; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file) a.downLoad { display:flex!important; align-items:center; min-height:44px!important; padding:0!important; color:var(--u-ink)!important; font-size:15px!important; font-weight:600!important; line-height:1.3!important; overflow-wrap:anywhere; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file) a.downLoad::before { content:''!important; position:absolute; inset:0; flex:none!important; width:auto!important; height:auto!important; margin:0!important; background:none!important; -webkit-mask:none!important; mask:none!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file)>td:nth-child(3) { display:none!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file)>td:is(:nth-child(4),:nth-child(5),:nth-child(6)) { order:2; flex:0 0 auto; color:var(--u-muted)!important; font-size:13px!important; line-height:20px!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file)>td:is(:nth-child(4),:nth-child(5),:nth-child(6)):empty { display:none!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file)>td:is(:nth-child(4),:nth-child(5),:nth-child(6))::before { content:none!important; display:none!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file)>td:is(:nth-child(5),:nth-child(6)):not(:empty)::before { content:'·'!important; display:inline!important; margin:0 8px 0 0; color:var(--u-line-strong); font-size:inherit!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file)>td:nth-child(5):not(:empty) { padding-left:0!important; }
html:root.univer-mobile #l4 table.univer-app-materials>tbody>tr.file:is(.file)>td:has(.univer-app-material-details) { position:relative; z-index:1; order:3; flex:1 1 100%; margin-top:2px!important; }
html:root.univer-mobile #l4 .univer-app-materials .univer-app-material-details>summary { min-height:32px!important; font-size:13px!important; }

/* Semester switch (terms.js): previous on the left, next on the right; each names the semester it opens. */
html:root.univer-mobile #l4 td.ts>a.univer-app-term-source.univer-app-term-source { display:none!important; }
html:root.univer-mobile #l4 nav.univer-app-term-nav { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; flex:1 1 100%; width:100%; margin:0; }
html:root.univer-mobile #l4 nav.univer-app-term-nav>a.univer-app-term { display:grid!important; grid-template-columns:18px minmax(0,1fr); grid-template-rows:auto auto; column-gap:10px; align-items:center; min-height:56px!important; box-sizing:border-box; margin:0!important; padding:8px 12px!important; border:1px solid var(--u-line)!important; border-radius:12px!important; background:var(--u-surface)!important; color:var(--u-ink)!important; text-decoration:none!important; }
html:root.univer-mobile #l4 nav.univer-app-term-nav>a.univer-app-term::before { content:''; grid-column:1; grid-row:1/3; width:18px; height:18px; background:var(--u-brand); -webkit-mask:var(--univer-arrow-icon) center/contain no-repeat; mask:var(--univer-arrow-icon) center/contain no-repeat; transform:scaleX(-1); }
html:root.univer-mobile #l4 nav.univer-app-term-nav>a.univer-app-term>span { grid-column:2; min-width:0; }
html:root.univer-mobile #l4 nav.univer-app-term-nav>a.univer-app-term-next { grid-column:2; grid-template-columns:minmax(0,1fr) 18px; text-align:end; }
html:root.univer-mobile #l4 nav.univer-app-term-nav>a.univer-app-term-next::before { grid-column:2; transform:none; }
html:root.univer-mobile #l4 nav.univer-app-term-nav>a.univer-app-term-next>span { grid-column:1; }
html:root.univer-mobile #l4 .univer-app-term-caption { color:var(--u-muted); font-size:12px; font-weight:500; line-height:1.3; }
html:root.univer-mobile #l4 .univer-app-term-value { color:var(--u-ink); font-size:14px; font-weight:600; line-height:1.3; }
html:root.univer-mobile #l4 nav.univer-app-term-nav>a.univer-app-term:active { background:var(--u-surface-2)!important; }

/* Текущая аттестация (attestation.js): the semester summary replaces the long heading, then one card per course. */
html:root.univer-mobile #l4 .univer-app-attestation-source, html:root.univer-mobile #l4 .univer-app-table-scroll:has(>.univer-app-attestation-source) { display:none!important; }
html:root.univer-mobile #l4 table.mt>tbody>tr>td.ct.univer-app-attestation-heading.univer-app-attestation-heading { padding:0 0 8px!important; font-size:0!important; line-height:0!important; text-align:start!important; background:none!important; }
html:root.univer-mobile #l4 .univer-app-attestation-summary { display:block; box-sizing:border-box; padding:14px 16px; border:1px solid var(--u-line); border-radius:14px; background:var(--u-surface); color:var(--u-text); font-size:15px; line-height:1.4; }
html:root.univer-mobile #l4 h2.univer-app-attestation-term { margin:0!important; padding:0!important; border:0!important; color:var(--u-ink)!important; font-size:17px!important; font-weight:600!important; line-height:1.3!important; }
html:root.univer-mobile #l4 p.univer-app-attestation-level { margin:2px 0 0!important; color:var(--u-muted)!important; font-size:14px!important; line-height:1.4!important; }
html:root.univer-mobile #l4 .univer-app-attestation-stats { display:grid; grid-template-columns:repeat(auto-fit,minmax(92px,1fr)); gap:8px; margin-top:12px; }
html:root.univer-mobile #l4 .univer-app-attestation-stat { display:flex; flex-direction:column; gap:2px; min-width:0; padding:8px 10px; border-radius:10px; background:var(--u-brand-soft); }
html:root.univer-mobile #l4 .univer-app-attestation-stat-value { color:var(--u-brand-strong); font-size:20px; font-weight:600; line-height:1.2; font-variant-numeric:tabular-nums; }
html:root.univer-mobile #l4 .univer-app-attestation-stat-label { color:var(--u-text); font-size:12px; line-height:1.3; overflow-wrap:anywhere; }
html:root.univer-mobile #l4 .univer-app-attestation-list { display:grid; gap:8px; margin:0 0 4px; }
html:root.univer-mobile #l4 .univer-app-attestation-course { padding:12px 14px 4px; border:1px solid var(--u-line); border-radius:14px; background:var(--u-surface); text-align:start; }
html:root.univer-mobile #l4 .univer-app-attestation-head { display:flex; align-items:flex-start; gap:10px; }
html:root.univer-mobile #l4 h3.univer-app-attestation-name { flex:1 1 auto; min-width:0; margin:0!important; padding:0!important; color:var(--u-ink)!important; font-size:16px!important; font-weight:600!important; line-height:1.3!important; overflow-wrap:anywhere; }
html:root.univer-mobile #l4 .univer-app-attestation-letter { flex:0 0 auto; display:grid; place-items:center; min-width:36px; height:32px; box-sizing:border-box; padding:0 8px; border-radius:8px; background:var(--u-surface-2); color:var(--u-text); font-size:16px; font-weight:600; line-height:1; }
html:root.univer-mobile #l4 .univer-app-attestation-letter[data-grade=a] { background:#e3f3ea; color:#1d6b3b; }
html:root.univer-mobile #l4 .univer-app-attestation-letter[data-grade=b] { background:var(--u-brand-soft); color:var(--u-brand-strong); }
html:root.univer-mobile #l4 .univer-app-attestation-letter[data-grade=c] { background:#fbf1d9; color:#7a4b00; }
html:root.univer-mobile #l4 .univer-app-attestation-letter[data-grade=d] { background:#fdebd8; color:#8a3c00; }
html:root.univer-mobile #l4 .univer-app-attestation-letter[data-grade=f] { background:var(--u-danger-soft); color:var(--u-danger); }
html:root.univer-mobile #l4 .univer-app-attestation-foot { display:flex; align-items:center; flex-wrap:wrap; gap:0 12px; }
html:root.univer-mobile #l4 p.univer-app-attestation-meta { flex:1 1 auto; min-width:0; margin:0!important; color:var(--u-muted)!important; font-size:14px!important; line-height:1.4!important; }
html:root.univer-mobile #l4 .univer-app-attestation-scores { display:flex!important; flex-wrap:nowrap!important; gap:6px!important; margin:10px 0 0!important; }
html:root.univer-mobile #l4 .univer-app-attestation-scores>.univer-app-score { flex:1 1 0!important; min-width:0!important; padding:6px 8px 10px!important; }
html:root.univer-mobile #l4 .univer-app-attestation-scores .univer-app-score-value { font-size:20px!important; }
html:root.univer-mobile #l4 .univer-app-attestation-scores>.univer-app-attestation-total { background:var(--u-surface)!important; border-color:var(--u-brand)!important; }
html:root.univer-mobile #l4 a.univer-app-attestation-journal { flex:0 0 auto; display:inline-flex!important; align-items:center; gap:4px; min-height:44px; margin:2px 0 0 auto!important; padding:0!important; border:0!important; background:none!important; color:var(--u-brand)!important; font-size:14px!important; font-weight:600!important; text-decoration:none!important; }
html:root.univer-mobile #l4 a.univer-app-attestation-journal::after { content:''; width:16px; height:16px; background:currentColor; -webkit-mask:var(--u-icon-chevron) center/contain no-repeat; mask:var(--u-icon-chevron) center/contain no-repeat; }
html:root.univer-mobile #l4 td.ct.univer-app-attestation-note { display:block!important; margin:8px 0 0!important; padding:10px 12px!important; border-left:3px solid #d97706!important; border-radius:10px!important; background:var(--u-surface-2)!important; color:var(--u-text)!important; font-size:13px!important; font-weight:400!important; line-height:1.45!important; text-align:start!important; }
html:root.univer-mobile #l4 td.ct.univer-app-attestation-note b { color:inherit!important; font-size:13px!important; font-weight:400!important; }
html:root.univer-mobile #l4 td.ct.univer-app-attestation-note>b:first-child { display:block; margin-bottom:2px; font-weight:600!important; }
html:root.univer-mobile #l4 td.ct.univer-app-attestation-note br { display:none; }

/* Дистанционные курсы (discussion.js): one tappable card per discipline, plan, week or discussion; folded instructions. */
html:root.univer-mobile #l4 .univer-app-dist-source, html:root.univer-mobile #l4 td.ts>a.univer-app-dist-source.univer-app-dist-source, html:root.univer-mobile #l4 .univer-app-table-scroll:has(>.univer-app-dist-source) { display:none!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 tr:has(>td.ts):not(:has(>td.ts>:not(.univer-app-dist-source):not(.univer-app-term-source))) { display:none!important; }
html:root.univer-mobile #l4 td.ct.univer-app-dist-head { padding:4px 0 8px!important; color:var(--u-ink)!important; font-size:15px!important; text-align:start!important; background:none!important; }
html:root.univer-mobile #l4 td.ct.univer-app-dist-head>span { display:block!important; float:none!important; text-align:start!important; }
html:root.univer-mobile #l4 td.ct.univer-app-dist-head a:not([href]) { display:none!important; }
/* Without the dead "Назад" link its " / " separator is a stray text node; only the links keep a size. */
html:root.univer-mobile #l4 td.ct.univer-app-dist-head>span:has(>a:not([href])) { font-size:0!important; }
html:root.univer-mobile #l4 td.ct.univer-app-dist-head>span { color:var(--u-muted)!important; font-size:14px!important; font-weight:500!important; line-height:1.5!important; }
html:root.univer-mobile #l4 td.ct.univer-app-dist-head a[href] { color:var(--u-muted)!important; font-size:14px!important; font-weight:500!important; text-decoration:none!important; }
/* Links to parent lists read as breadcrumbs; the index's self link (href="") and the semester row are the page titles. */
html:root.univer-mobile #l4 td.ct.univer-app-dist-head a[href=""] { display:block!important; color:var(--u-ink)!important; font-size:17px!important; font-weight:600!important; line-height:1.3!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 tr.brk>td.ct { padding:8px 2px 6px!important; background:none!important; color:var(--u-ink)!important; font-size:17px!important; font-weight:600!important; line-height:1.3!important; text-align:start!important; }
html:root.univer-mobile #l4 .univer-app-dist-list { display:grid; gap:8px; margin:0 0 8px; }
html:root.univer-mobile #l4 .univer-app-dist-card { position:relative; border:1px solid var(--u-line); border-radius:14px; background:var(--u-surface); text-align:start; overflow:hidden; }
html:root.univer-mobile #l4 .univer-app-dist-card[data-current=true] { border-color:var(--u-brand); box-shadow:0 0 0 1px var(--u-brand) inset; }
html:root.univer-mobile #l4 .univer-app-dist-main { position:relative; display:grid!important; grid-template-columns:40px minmax(0,1fr) auto; align-items:center; column-gap:12px; width:100%!important; min-height:64px!important; box-sizing:border-box; margin:0!important; padding:10px 12px!important; border:0!important; border-radius:0!important; background:none!important; box-shadow:none!important; color:var(--u-ink)!important; font:inherit!important; text-align:start!important; }
html:root.univer-mobile #l4 button.univer-app-dist-main { padding-right:40px!important; cursor:pointer; }
html:root.univer-mobile #l4 button.univer-app-dist-main::after { content:''; position:absolute; right:14px; top:50%; width:18px; height:18px; margin-top:-9px; background:var(--u-muted); -webkit-mask:var(--u-icon-chevron) center/contain no-repeat; mask:var(--u-icon-chevron) center/contain no-repeat; }
html:root.univer-mobile #l4 button.univer-app-dist-main:active { background:var(--u-surface-2)!important; }
html:root.univer-mobile #l4 .univer-app-dist-lead { display:grid; place-items:center; width:40px; height:40px; border-radius:10px; background:var(--u-brand-soft); color:var(--u-brand); font-size:15px; font-weight:600; font-variant-numeric:tabular-nums; }
html:root.univer-mobile #l4 .univer-app-dist-lead:empty::before { content:''; width:20px; height:20px; background:currentColor; -webkit-mask:var(--univer-folder-icon) center/contain no-repeat; mask:var(--univer-folder-icon) center/contain no-repeat; }
html:root.univer-mobile #l4 .univer-app-dist-card[data-kind=people] .univer-app-dist-lead { background:var(--u-teal-soft); color:var(--u-teal); }
html:root.univer-mobile #l4 .univer-app-dist-card[data-kind=people] .univer-app-dist-lead::before { -webkit-mask-image:var(--univer-people-icon); mask-image:var(--univer-people-icon); }
html:root.univer-mobile #l4 .univer-app-dist-card[data-kind=file] .univer-app-dist-lead::before { -webkit-mask-image:var(--univer-chat-icon); mask-image:var(--univer-chat-icon); }
html:root.univer-mobile #l4 .univer-app-dist-card[data-current=true] .univer-app-dist-lead { background:var(--u-brand); color:#fff; }
html:root.univer-mobile #l4 .univer-app-dist-body { display:flex; flex-direction:column; align-items:flex-start; gap:2px; min-width:0; }
html:root.univer-mobile #l4 .univer-app-dist-title { color:var(--u-ink); font-size:15px; font-weight:600; line-height:1.3; overflow-wrap:anywhere; }
html:root.univer-mobile #l4 .univer-app-dist-meta { color:var(--u-muted); font-size:13px; font-weight:400; line-height:1.35; }
html:root.univer-mobile #l4 .univer-app-dist-badge { padding:1px 8px; border-radius:6px; background:var(--u-brand); color:#fff; font-size:12px; font-weight:600; line-height:18px; }
html:root.univer-mobile #l4 .univer-app-dist-count { display:grid; place-items:center; min-width:28px; height:24px; box-sizing:border-box; padding:0 8px; border-radius:999px; background:var(--u-brand); color:#fff; font-size:13px; font-weight:600; font-variant-numeric:tabular-nums; }
html:root.univer-mobile #l4 .univer-app-dist-count[hidden] { display:none; }
html:root.univer-mobile #l4 .univer-app-dist-count[data-state=closed] { background:var(--u-surface-2); color:var(--u-muted); }
html:root.univer-mobile #l4 .univer-app-dist-tasks[hidden] { display:none; }
html:root.univer-mobile #l4 .univer-app-dist-tasks { color:var(--u-text); }
html:root.univer-mobile #l4 .univer-app-dist-actions { display:flex; flex-wrap:wrap; gap:0 16px; margin:-10px 12px 2px 64px; }
html:root.univer-mobile #l4 details.univer-app-dist-description+.univer-app-dist-actions { margin-top:-12px; }
html:root.univer-mobile #l4 .univer-app-dist-actions>button.univer-app-dist-secondary { min-height:44px!important; margin:0!important; padding:0!important; border:0!important; background:none!important; font-size:14px!important; }
/* Open tasks across the disciplines, nearest deadline first. */
html:root.univer-mobile #l4 section.univer-app-dist-due { margin:0 0 12px; padding:12px 14px; border:1px solid var(--u-brand-line); border-radius:14px; background:var(--u-brand-soft); text-align:start; }
html:root.univer-mobile #l4 h2.univer-app-dist-due-title { margin:0!important; padding:0!important; border:0!important; color:var(--u-ink)!important; font-size:16px!important; font-weight:600!important; line-height:1.3!important; }
html:root.univer-mobile #l4 p.univer-app-dist-due-status { margin:4px 0 0!important; color:var(--u-muted)!important; font-size:14px!important; line-height:1.4!important; }
html:root.univer-mobile #l4 p.univer-app-dist-due-status[hidden] { display:none!important; }
html:root.univer-mobile #l4 .univer-app-dist-due-list { display:grid; gap:6px; }
html:root.univer-mobile #l4 .univer-app-dist-due-list:not(:empty) { margin-top:10px; }
html:root.univer-mobile #l4 a.univer-app-dist-due-item { position:relative; display:flex!important; flex-direction:column; gap:2px; min-height:52px; box-sizing:border-box; padding:8px 40px 8px 12px!important; border:1px solid var(--u-line)!important; border-radius:10px!important; background:var(--u-surface)!important; color:var(--u-ink)!important; text-decoration:none!important; }
html:root.univer-mobile #l4 a.univer-app-dist-due-item::after { content:''; position:absolute; right:12px; top:50%; width:18px; height:18px; margin-top:-9px; background:var(--u-muted); -webkit-mask:var(--u-icon-chevron) center/contain no-repeat; mask:var(--u-icon-chevron) center/contain no-repeat; }
html:root.univer-mobile #l4 .univer-app-dist-due-name { color:var(--u-ink); font-size:15px; font-weight:600; line-height:1.3; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
html:root.univer-mobile #l4 .univer-app-dist-due-meta { color:var(--u-muted); font-size:13px; line-height:1.35; }
html:root.univer-mobile #l4 a.univer-app-dist-due-item[data-soon=true] .univer-app-dist-due-meta { color:#a15c00; font-weight:600; }
/* Headings: parent links read as breadcrumbs separated by "/", the remaining text is the page title. */
html:root.univer-mobile #l4 table.mt>tbody>tr.top>td.ct.univer-app-dist-head { color:var(--u-ink)!important; font-size:17px!important; font-weight:600!important; line-height:1.35!important; }
html:root.univer-mobile #l4 td.ct.univer-app-dist-head>a[href]+a[href]::before { content:'/'; margin:0 6px; color:var(--u-line-strong); }
html:root.univer-mobile.univer-app-discussion-page #l4 a#help_btn { font-size:14px!important; }
/* Task page (task/preview): the site fixes the task box at 110px, which hid the deadline, attempts and maximum score. */
html:root.univer-mobile.univer-app-discussion-page #l4 tr.message .inf { height:auto!important; max-height:none!important; width:auto!important; overflow:visible!important; box-sizing:border-box; padding:10px 12px!important; border-radius:10px!important; line-height:1.5!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 tr.message>td { background:none!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 tr.message .inf .btn { float:right; }
/* Message form: one column that fits the phone; the recipient list and Send span the width. */
html:root.univer-mobile.univer-app-discussion-page #l4 form#send_message :is(table,tbody,tr,td) { display:block!important; width:auto!important; text-align:start!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 form#send_message select { display:block; width:100%!important; max-width:100%!important; margin-top:4px; }
html:root.univer-mobile.univer-app-discussion-page #l4 form#send_message input[type=button] { width:100%!important; margin-top:8px!important; border:0!important; border-radius:10px!important; background:var(--u-brand)!important; color:#fff!important; font-weight:600!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 .univer-app-table-scroll:has(>table form,form) { overflow:visible!important; }
/* Course facts (subject, calendar, current week…): one line that opens to the full list. */
html:root.univer-mobile #l4 details.univer-app-dist-facts { margin:0 0 4px!important; padding:0!important; border:1px solid var(--u-line)!important; border-radius:12px!important; background:var(--u-surface)!important; text-align:start; }
html:root.univer-mobile #l4 details.univer-app-dist-facts>summary { display:flex; align-items:center; min-height:48px!important; padding:0 14px!important; border:0!important; background:none!important; color:var(--u-ink)!important; font-size:15px!important; font-weight:600!important; line-height:1.35!important; cursor:pointer; }
html:root.univer-mobile #l4 details.univer-app-dist-facts>dl { display:grid; grid-template-columns:minmax(0,2fr) minmax(0,3fr); gap:6px 12px; margin:0!important; padding:0 14px 12px!important; }
html:root.univer-mobile #l4 details.univer-app-dist-facts dt { margin:0!important; color:var(--u-muted); font-size:13px; line-height:1.4; }
html:root.univer-mobile #l4 details.univer-app-dist-facts dd { margin:0!important; color:var(--u-ink); font-size:14px; line-height:1.4; overflow-wrap:anywhere; }
html:root.univer-mobile #l4 details.univer-app-dist-description { margin:-4px 12px 8px 64px!important; padding:0!important; border:0!important; background:none!important; }
html:root.univer-mobile #l4 details.univer-app-dist-description>summary { display:flex; align-items:center; min-height:44px!important; padding:0!important; border:0!important; background:none!important; color:var(--u-brand)!important; font-size:14px!important; font-weight:600!important; cursor:pointer; }
html:root.univer-mobile #l4 details.univer-app-dist-description>p { margin:0 0 4px!important; color:var(--u-text)!important; font-size:14px!important; line-height:1.5!important; white-space:pre-line; overflow-wrap:anywhere; }
html:root.univer-mobile #l4 button.univer-app-dist-secondary { display:inline-flex!important; align-items:center; width:auto!important; min-height:40px!important; margin:-4px 12px 10px 64px!important; padding:0 12px!important; border:1px solid var(--u-line)!important; border-radius:8px!important; background:var(--u-surface)!important; box-shadow:none!important; color:var(--u-brand)!important; font-size:14px!important; font-weight:600!important; }
html:root.univer-mobile #l4 details.univer-app-dist-help { margin:0 0 12px!important; padding:0!important; border:1px solid var(--u-line)!important; border-radius:12px!important; background:var(--u-surface)!important; overflow:hidden; }
html:root.univer-mobile #l4 details.univer-app-dist-help>summary { display:flex; align-items:center; gap:10px; min-height:48px!important; padding:0 14px!important; border:0!important; background:none!important; color:var(--u-ink)!important; font-size:15px!important; font-weight:600!important; cursor:pointer; }
html:root.univer-mobile #l4 details.univer-app-dist-help>summary::before { content:''; flex:0 0 18px; width:18px; height:18px; background:var(--u-brand); -webkit-mask:var(--univer-info-icon) center/contain no-repeat; mask:var(--univer-info-icon) center/contain no-repeat; }
html:root.univer-mobile #l4 details.univer-app-dist-help>table.mt { margin:0!important; border:0!important; box-shadow:none!important; }
html:root.univer-mobile #l4 details.univer-app-dist-help td.ct { padding:0 14px 8px!important; background:none!important; }
html:root.univer-mobile #l4 details.univer-app-dist-help a[href] { display:flex!important; align-items:center; gap:10px; min-height:44px!important; padding:6px 0!important; color:var(--u-brand)!important; font-size:14px!important; font-weight:500!important; line-height:1.35!important; text-decoration:none!important; }
html:root.univer-mobile #l4 details.univer-app-dist-help a[href]>img { flex:0 0 18px; width:18px!important; height:auto!important; }
html:root.univer-mobile #l4 details.univer-app-dist-help br { display:none; }
/* A discipline's weeks: tasks first, empty weeks behind "Show all weeks". */
html:root.univer-mobile #l4 .univer-app-dist-list:not(.univer-app-dist-expanded)>[data-quiet=true] { display:none!important; }
html:root.univer-mobile #l4 button.univer-app-dist-more { display:flex!important; align-items:center; justify-content:center; width:100%!important; min-height:48px!important; margin:0 0 12px!important; padding:0 16px!important; border:1px dashed var(--u-line-strong)!important; border-radius:12px!important; background:none!important; box-shadow:none!important; color:var(--u-brand)!important; font-size:15px!important; font-weight:600!important; }
html:root.univer-mobile #l4 a.univer-app-dist-due-item[data-closed=true] { background:var(--u-surface-2)!important; }
html:root.univer-mobile #l4 a.univer-app-dist-due-item[data-closed=true] .univer-app-dist-due-name { color:var(--u-text); font-weight:500; }
html:root.univer-mobile #l4 section.univer-app-dist-week-tasks { margin-top:0; }
/* Task page: one card with the name, deadline, facts and description; the answer form below it. */
html:root.univer-mobile #l4 table.mt>tbody>tr.top>td.ct.univer-app-task-head.univer-app-task-head { font-size:0!important; line-height:0!important; padding:4px 0 8px!important; }
html:root.univer-mobile #l4 td.ct.univer-app-task-head>a[href] { display:inline-block; font-size:14px!important; line-height:1.6!important; }
html:root.univer-mobile #l4 td.ct.univer-app-task-head>a[href]+a[href]::before { content:'/'; margin:0 6px; color:var(--u-line-strong); }
html:root.univer-mobile #l4 section.univer-app-task { margin:0 0 12px; padding:16px; border:1px solid var(--u-line); border-radius:16px; background:var(--u-surface); color:var(--u-text); text-align:start; }
html:root.univer-mobile #l4 p.univer-app-task-course { margin:0 0 4px!important; color:var(--u-brand)!important; font-size:13px!important; font-weight:600!important; letter-spacing:.02em; }
html:root.univer-mobile #l4 h2.univer-app-task-title { margin:0!important; padding:0!important; border:0!important; color:var(--u-ink)!important; font-size:19px!important; font-weight:600!important; line-height:1.3!important; overflow-wrap:anywhere; }
html:root.univer-mobile #l4 p.univer-app-task-author { margin:6px 0 0!important; color:var(--u-muted)!important; font-size:14px!important; }
html:root.univer-mobile #l4 p.univer-app-task-author::before { content:''; display:inline-block; width:14px; height:14px; margin:0 6px -2px 0; background:currentColor; -webkit-mask:var(--u-icon-person) center/contain no-repeat; mask:var(--u-icon-person) center/contain no-repeat; }
html:root.univer-mobile #l4 .univer-app-task-deadline { display:flex; flex-wrap:wrap; align-items:baseline; justify-content:space-between; gap:2px 12px; margin:14px 0 0; padding:10px 12px; border-radius:12px; background:var(--u-brand-soft); color:var(--u-brand-strong); }
html:root.univer-mobile #l4 .univer-app-task-deadline>strong { font-size:16px; font-weight:600; }
html:root.univer-mobile #l4 .univer-app-task-deadline>span { font-size:14px; font-weight:600; }
html:root.univer-mobile #l4 .univer-app-task-deadline[data-state=soon] { background:#fdf0dc; color:#7a4300; }
html:root.univer-mobile #l4 .univer-app-task-deadline[data-state=closed] { background:var(--u-danger-soft); color:var(--u-danger); }
html:root.univer-mobile #l4 .univer-app-task-deadline[data-state=upcoming] { background:var(--u-surface-2); color:var(--u-text); }
html:root.univer-mobile #l4 .univer-app-task-chips { display:flex; flex-wrap:wrap; gap:6px; margin:12px 0 0; }
html:root.univer-mobile #l4 .univer-app-task-chip { padding:4px 10px; border-radius:999px; background:var(--u-surface-2); border:1px solid var(--u-line); color:var(--u-text); font-size:13px; font-weight:500; line-height:1.4; }
html:root.univer-mobile #l4 h3.univer-app-task-label { margin:16px 0 4px!important; padding:0!important; border:0!important; color:var(--u-muted)!important; font-size:13px!important; font-weight:600!important; text-transform:none; }
html:root.univer-mobile #l4 p.univer-app-task-description { margin:0!important; color:var(--u-ink)!important; font-size:15px!important; line-height:1.55!important; white-space:pre-line; overflow-wrap:anywhere; }
html:root.univer-mobile #l4 p.univer-app-task-description[data-folded=true] { display:-webkit-box; -webkit-line-clamp:7; -webkit-box-orient:vertical; overflow:hidden; }
html:root.univer-mobile #l4 button.univer-app-task-more { display:inline-flex!important; align-items:center; min-height:44px!important; margin:2px 0 0!important; padding:0!important; border:0!important; background:none!important; box-shadow:none!important; color:var(--u-brand)!important; font-size:14px!important; font-weight:600!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form { display:block; margin:0!important; padding:14px 16px 16px!important; border:1px solid var(--u-line); border-radius:16px; background:var(--u-surface); }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form td { padding:0!important; border:0!important; background:none!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form :is(.univer-app-table-scroll,table) { margin:0!important; padding:0!important; border:0!important; border-radius:0!important; background:none!important; box-shadow:none!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form label { display:block; margin:10px 0 4px!important; color:var(--u-muted)!important; font-size:13px!important; font-weight:600!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form tr:first-child label { margin-top:0!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form a#fileS { display:inline-flex!important; align-items:center; gap:8px; min-height:44px!important; margin:2px 0 0!important; padding:0 16px!important; border:1px solid var(--u-brand-line)!important; border-radius:10px!important; background:var(--u-brand-soft)!important; color:var(--u-brand-strong)!important; font-size:15px!important; font-weight:600!important; text-decoration:none!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form a#fileS::before { content:'+'; font-size:20px; font-weight:400; line-height:1; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form #files_div>b { display:flex; flex-wrap:wrap; align-items:center; gap:4px 12px; margin:8px 0 0; padding:8px 10px; border:1px solid var(--u-line); border-radius:10px; background:var(--u-surface-2); font-weight:400!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form #files_div>b>br { display:none; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form #files_div input[type=file] { flex:1 1 200px; width:auto!important; max-width:100%!important; min-height:0!important; margin:0!important; padding:4px 0!important; border:0!important; background:none!important; font-size:14px!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form #files_div input[type=file]::file-selector-button { margin-right:10px; padding:8px 12px; border:1px solid var(--u-line-strong); border-radius:8px; background:#fff; color:var(--u-ink); font:600 14px var(--u-font); }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form #files_div a[id^=fileH] { display:inline-flex; align-items:center; min-height:40px; color:var(--u-danger)!important; font-size:14px!important; font-weight:600; text-decoration:none!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form #files_div span[id^=b-error] { flex-basis:100%; color:var(--u-danger)!important; font-size:13px!important; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form p.univer-app-task-hint { margin:8px 0 0!important; color:var(--u-muted)!important; font-size:13px!important; line-height:1.45!important; overflow-wrap:anywhere; }
html:root.univer-mobile.univer-app-discussion-page #l4 form.univer-app-task-form label#time { margin:12px 0 0!important; padding:8px 10px; border-radius:8px; background:var(--u-surface-2); color:var(--u-text)!important; font-weight:500!important; text-align:start; }

/* One page gutter on a phone: the tabs, the section menu and the page body share the same two edges. */
html:root.univer-mobile #l3 td.center { padding-inline:12px!important; }
html:root.univer-mobile:not(.univer-app-unified) .univer-app-menu:not(#l4 *) { margin-left:12px!important; margin-right:12px!important; }
html:root.univer-mobile:not(.univer-app-unified) .univer-app-menu { margin-bottom:8px!important; }

/* Headings are small on a phone, where tight tracking only costs legibility. */
html:root.univer-mobile #l4 :is(h1,h2,h3) { letter-spacing:0; }

/* Semester switch: the same card on every page, its year and season on their own lines. */
html:root.univer-mobile #l4 nav.univer-app-term-nav>a.univer-app-term { row-gap:0!important; min-height:64px!important; }
html:root.univer-mobile #l4 nav.univer-app-term-nav>a.univer-app-term-next { text-align:end!important; }
html:root.univer-mobile #l4 .univer-app-term-value>span { display:block; }
html:root.univer-mobile #l4 .univer-app-term-value>.univer-app-term-separator { position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0 0 0 0); white-space:pre; }

/* Native select arrows differ per system: one chevron, inset like the other disclosure controls. */
html:root.univer-mobile { --u-select-arrow:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%235b6b7c' stroke-width='2.4' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M6 9l6 6 6-6'/%3E%3C/svg%3E"); }
html:root.univer-mobile #l4 select:not([multiple]):not([size]) { -webkit-appearance:none; appearance:none; padding-inline-end:40px!important; background-image:var(--u-select-arrow)!important; background-repeat:no-repeat!important; background-position:right 12px center!important; background-size:18px!important; }

/* A new page: only its body arrives; the tabs and the app bar above it stay where they are. */
@keyframes univer-page-in { from { opacity:0; transform:translateY(8px); } to { opacity:1; transform:none; } }
html:root.univer-mobile[data-univer-app-enter] #l4>td { animation:univer-page-in 200ms var(--u-ease-out); }

/* A blank paragraph marked by the news adapter takes no room; paragraph margins already separate the lines. */
html:root.univer-mobile .univer-app-news-body>[data-univer-blank] { display:none!important; }

/* One system for the phone layer: controls are 44px with a 10px radius, cards a 12px radius and the hairline, chips 8px.
   Page blocks start on the page's own edges, and each screen keeps its colour for the one thing it is about. */
html:root.univer-mobile #l4>td td.center { padding-top:12px!important; }
html:root.univer-mobile #l4 td.center>table.univer-app-study-panel:first-of-type { margin-top:0!important; }
html:root.univer-mobile #l4 :is(select:not([multiple]):not([size]),input[type=search],input[type=text]),
html:root.univer-mobile .univer-app-menu-search, html:root.univer-mobile #l4 .univer-app-news-tools>button.univer-app-news-filter-toggle.univer-app-news-filter-toggle { min-height:44px!important; height:44px!important; border-radius:10px!important; box-sizing:border-box!important; }
html:root.univer-mobile .univer-app-quick .univer-app-tile>a { box-sizing:border-box!important; border:1px solid var(--u-line)!important; overflow-wrap:normal!important; word-break:normal!important; }
html:root.univer-mobile .univer-app-quick.univer-app-quick-stacked .univer-app-tile>a { flex-direction:column!important; align-items:flex-start!important; justify-content:center!important; gap:6px!important; height:auto!important; min-height:84px!important; padding:10px 12px!important; }
html:root.univer-mobile #l4 select:not([multiple]):not([size]) { text-overflow:ellipsis; font-weight:500!important; }
@media (max-width:359px) {
 html:root.univer-mobile #l4 table.univer-app-study-panel>tbody>tr.top:first-child>td.ct, html:root.univer-mobile #l4 table.univer-app-study-panel>tbody>tr.top:first-child>td.ct>a { font-size:18px!important; line-height:1.3!important; }
}
html:root.univer-mobile #l5 td.center { padding:8px 12px 24px!important; }
html:root.univer-mobile #l5 #copyright td { padding-inline:0!important; }
html:root.univer-mobile .univer-app-gpa { box-sizing:border-box; border:1px solid var(--u-line); }
/* The site's raster info icon floats into the greeting and breaks its first line. */
html:root.univer-mobile #l4 td.ct.univer-app-panel-body>img[src*='info.png'] { display:none!important; }

html:root.univer-mobile .univer-app-ssc-nav { display:flex!important; box-sizing:border-box; margin:0 12px 12px!important; }
html:root.univer-mobile .univer-app-ssc-nav a { flex:1 1 0; justify-content:center; }
html:root.univer-mobile #l4 table.mt.univer-app-panel { border-radius:12px!important; }
/* A row's running number says nothing on a card. */
html:root.univer-mobile #l4 tr.univer-app-card-row>td[data-univer-field='#'] { display:none!important; }

/* Semester switch: the arrow already says which way, so the caption is for screen readers only. */
html:root.univer-mobile #l4 .univer-app-term-caption { position:absolute!important; width:1px!important; height:1px!important; overflow:hidden!important; clip:rect(0 0 0 0)!important; white-space:nowrap!important; }
html:root.univer-mobile #l4 nav.univer-app-term-nav>a.univer-app-term { min-height:52px!important; padding-block:6px!important; }

/* News: the search row needs no card of its own, chips share one shape and the link starts on the text's edge. */
html:root.univer-mobile #l4 .univer-app-news-tools { padding:0!important; background:transparent!important; border:0!important; border-radius:0!important; }
html:root.univer-mobile #l4 .univer-app-news button.univer-app-news-toggle { min-height:40px!important; margin:4px 0 0!important; padding-inline:0!important; }
html:root.univer-mobile #l4 tr.news td div[style*="background-color"] { display:inline-flex!important; align-items:center!important; box-sizing:border-box!important; height:24px!important; border-radius:8px!important; }
html:root.univer-mobile .univer-app-news-body[data-collapsed=true] { max-height:var(--univer-news-fold,132px)!important; }
html:root.univer-mobile .univer-app-news-body[data-collapsed=true]::after { display:none!important; }

/* Schedule: what comes next leads the page, the week reads as one strip, and print sits beside the title. */
html:root.univer-mobile.univer-app-schedule-page #l4 .ct.univer-app-schedule-title { display:flex!important; flex-wrap:nowrap!important; align-items:flex-start!important; gap:12px!important; box-sizing:border-box; width:auto!important; min-width:0!important; padding-bottom:8px!important; }
html:root.univer-mobile.univer-app-schedule-page #l4 .ct.univer-app-schedule-title>a:first-child { flex:1 1 auto!important; min-width:0!important; }
html:root.univer-mobile.univer-app-schedule-page #l4 .ct>a.univer-app-print-action { justify-content:center!important; gap:0!important; width:44px!important; height:44px!important; min-height:44px!important; padding:0!important; font-size:0!important; }
html:root.univer-mobile.univer-app-schedule-page #l4 .ct>a.univer-app-print-action::before { width:20px!important; height:20px!important; }
html:root.univer-mobile section.univer-app-schedule:not([data-mode=grid]):not([data-all-days=true]) { display:flex!important; flex-direction:column; }
html:root.univer-mobile section.univer-app-schedule>.univer-app-now { order:-1; }
html:root.univer-mobile section.univer-app-schedule>.univer-app-schedule-modes { align-self:flex-start; margin:0 0 12px!important; }
html:root.univer-mobile #l4 section.univer-app-day.univer-app-today { border-color:var(--u-brand-line)!important; }
html:root.univer-mobile label.univer-app-schedule-mode { flex-direction:row-reverse!important; justify-content:space-between!important; min-height:44px!important; margin:0 0 4px!important; }
html:root.univer-mobile label.univer-app-schedule-mode input[type=checkbox] { -webkit-appearance:none!important; appearance:none!important; position:relative!important; flex:none!important; width:40px!important; height:24px!important; min-height:0!important; margin:0!important; padding:0!important; border:0!important; border-radius:12px!important; background:var(--u-line-strong)!important; transition:background-color var(--u-press) var(--u-ease-out); }
/* The knob is white ink on the track in both themes: painted through currentColor, the dark palette leaves it white. */
html:root.univer-mobile label.univer-app-schedule-mode input[type=checkbox]::before { content:''; position:absolute; top:3px; left:3px; width:18px; height:18px; border-radius:50%; color:#fff; background:currentColor; transition:transform var(--u-press) var(--u-ease-out); }
html:root.univer-mobile label.univer-app-schedule-mode input[type=checkbox]:checked { background:var(--u-brand-fill,var(--u-brand))!important; }
html:root.univer-mobile label.univer-app-schedule-mode input[type=checkbox]:checked::before { transform:translateX(16px); }

/* Week grid: the site's room facts are two floats with their own borders; in a narrow cell they split "ФИТ" in two. */
html:root.univer-mobile table.univer-app-schedule-source .params span { display:inline!important; float:none!important; width:auto!important; padding:0!important; border:0!important; overflow-wrap:normal; }
html:root.univer-mobile table.univer-app-schedule-source .params .aud_faculty::after { content:' · '; }

/* Grades: one quiet chip everywhere, wide enough that each period's figures stand in one column. The scale is said once,
   in the course total; a period without points recedes so the eye lands on the real grades. */
html:root.univer-mobile #l4 .univer-app-attendance-scores-compact .univer-app-score, html:root.univer-mobile .univer-app-overview-courses .univer-app-overview-score { box-sizing:border-box; justify-content:space-between; gap:8px!important; min-width:4.8em!important; padding:4px 8px!important; border-color:transparent!important; border-radius:8px!important; background:var(--u-brand-soft)!important; }
html:root.univer-mobile #l4 .univer-app-attendance-scores-compact .univer-app-score-max, html:root.univer-mobile .univer-app-overview-score-bar { display:none!important; }
html:root.univer-mobile .univer-app-overview-score-label { color:var(--u-brand)!important; font-weight:600!important; }
html:root.univer-mobile .univer-app-overview-score-value { font-size:15px!important; font-weight:600!important; }
html:root.univer-mobile #l4 .univer-app-score[aria-label$=': 0'] .univer-app-score-value, html:root.univer-mobile .univer-app-overview-score[aria-label*=': 0 /'] .univer-app-overview-score-value { color:var(--u-muted)!important; font-weight:500!important; }
html:root.univer-mobile #l4 .univer-app-type-row .univer-app-attendance-scores { margin-inline-start:auto!important; }
html:root.univer-mobile #l4 .univer-app-attendance-overall-block { justify-content:space-between; }
html:root.univer-mobile #l4 .univer-app-attendance-overall-block .univer-app-score { justify-content:space-between; min-width:6.2em!important; border-color:transparent!important; border-radius:8px!important; }
html:root.univer-mobile #l4 nav.univer-app-attendance-courses { margin-bottom:8px!important; padding-bottom:0!important; border:0!important; }
html:root.univer-mobile .univer-app-attendance-options>summary { color:var(--u-ink)!important; font-weight:500!important; }

/* Attestation: four plain figures with thin bars; only the total keeps a surface, because it is the number that counts. */
html:root.univer-mobile #l4 :is(.univer-app-attestation-summary,.univer-app-attestation-course) { border-radius:12px!important; }
html:root.univer-mobile #l4 .univer-app-attestation-scores { gap:4px!important; }
html:root.univer-mobile #l4 .univer-app-attestation-scores>.univer-app-score { padding:6px 8px 10px!important; border-color:transparent!important; border-radius:8px!important; background:none!important; }
html:root.univer-mobile #l4 .univer-app-attestation-scores>.univer-app-score:first-child { padding-inline-start:0!important; }
html:root.univer-mobile #l4 .univer-app-attestation-scores .univer-app-score-label { color:var(--u-muted)!important; font-weight:500!important; }
html:root.univer-mobile #l4 .univer-app-attestation-scores .univer-app-score-bar { left:8px!important; right:8px!important; bottom:4px!important; height:3px!important; overflow:hidden; border-radius:2px!important; background:var(--u-line)!important; }
html:root.univer-mobile #l4 .univer-app-attestation-scores>.univer-app-score:first-child .univer-app-score-bar { left:0!important; }
html:root.univer-mobile #l4 .univer-app-attestation-scores>.univer-app-attestation-total { background:var(--u-brand-soft)!important; }
html:root.univer-mobile #l4 .univer-app-attestation-total .univer-app-score-label { color:var(--u-brand)!important; font-weight:600!important; }
html:root.univer-mobile #l4 .univer-app-attestation-total .univer-app-score-bar { background:var(--u-brand-line)!important; }
html:root.univer-mobile #l4 td.ct.univer-app-attestation-note { position:relative; padding:12px 14px 12px 42px!important; border:0!important; border-radius:12px!important; background:#fdf3e4!important; }
html:root.univer-mobile #l4 td.ct.univer-app-attestation-note::before { content:''; position:absolute; left:14px; top:13px; width:18px; height:18px; color:#7a4300; background:currentColor; -webkit-mask:${icons.note} center/contain no-repeat; }

/* УМКД: the site's section heading is a heading of this page, on its edge and in its ink. */
html:root.univer-mobile #l4 tr.brk>td.ct { padding:8px 0!important; color:var(--u-ink)!important; font-size:var(--u-fs-section)!important; font-weight:600!important; }

/* The app's dark theme (univer-palette.js adds the class and redraws the light colours above). */
/* The Windows app's design at phone size: the same parts as the last layer of its desktop.js. A control is 44 px here
   against 40 there, for a finger; everything else keeps the desktop's shapes and words. */
/* Header: the site's tabs stand in one tray, the chosen one filled. */
html:root:root.univer-mobile #l3 td.center { gap:4px; margin:8px 12px 4px!important; padding:4px!important; border-radius:14px; background:var(--u-surface-2)!important; box-shadow:inset 0 0 0 1px var(--u-line); }
html:root:root.univer-mobile #l3 td.center>a { height:40px; min-height:40px; border:0!important; border-radius:10px!important; background:transparent!important; color:var(--u-ink)!important; font-weight:600!important; box-shadow:none!important; }
html:root:root.univer-mobile #l3 td.center>a.active { background:var(--u-brand)!important; color:#fff!important; box-shadow:0 1px 3px rgba(16,40,70,.28)!important; }
html:root:root.univer-mobile:not(.univer-app-unified) .univer-app-menu { border-color:var(--u-line-strong)!important; }
html:root:root.univer-mobile:not(.univer-app-unified) .univer-app-menu>summary { min-height:48px; }
/* Home tiles: an outline at rest, a plaque for the icon; the last place in the grid adds a section. */
html:root:root.univer-mobile .univer-app-quick { gap:10px!important; margin:4px 0 12px!important; }
html:root:root.univer-mobile .univer-app-quick .univer-app-tile>a { min-height:60px!important; padding:10px 12px!important; border:1px solid var(--u-line-strong)!important; border-radius:12px!important; background:var(--u-surface)!important; box-shadow:none!important; font-weight:600!important; }
html:root:root.univer-mobile .univer-app-quick .univer-app-add-tile { display:flex; align-items:center; gap:10px; min-width:0; min-height:60px; box-sizing:border-box; margin:0!important; padding:10px 12px!important; border:1px dashed var(--u-line-strong)!important; border-radius:12px!important; background:transparent!important; box-shadow:none!important; color:var(--u-muted)!important; font:500 15px/1.3 var(--u-font)!important; text-align:start; cursor:pointer; }
html:root:root.univer-mobile .univer-app-quick.univer-app-quick-stacked .univer-app-add-tile { flex-direction:column; align-items:flex-start; }
html:root.univer-mobile .univer-app-add-icon { flex:0 0 auto; display:grid; place-items:center; width:32px; height:32px; border-radius:8px; background:var(--u-surface-2); }
html:root.univer-mobile .univer-app-add-icon::before { content:''; width:18px; height:18px; background:currentColor; -webkit-mask:var(--univer-plus-icon) center/contain no-repeat; }
/* New section: the desktop's dialog as a sheet from the bottom of the screen. */
html:root.univer-mobile .univer-app-add-layer { position:fixed; inset:0; z-index:1100; display:flex; align-items:flex-end; justify-content:center; background:rgba(16,28,44,.32); }
html:root.univer-mobile .univer-app-add-panel { display:flex; flex-direction:column; width:100%; max-width:560px; max-height:min(80vh,640px); box-sizing:border-box; padding:16px 16px calc(12px + env(safe-area-inset-bottom)); border-radius:16px 16px 0 0; background:var(--u-surface); color:var(--u-ink); box-shadow:var(--u-shadow-pop); outline:none; }
/* The grip says the sheet can be pulled down; the grip and the title row take the drag, the list keeps scrolling. */
html:root.univer-mobile .univer-app-add-grip { flex:0 0 auto; display:block; width:36px; height:4px; margin:-6px auto 10px; border-radius:2px; background:var(--u-line-strong); }
html:root.univer-mobile :is(.univer-app-add-grip,.univer-app-add-head) { touch-action:none; }
html:root.univer-mobile .univer-app-add-head { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:0 0 10px; }
html:root.univer-mobile .univer-app-add-head>h2 { margin:0!important; padding:0!important; color:var(--u-ink)!important; font:600 18px/1.3 var(--u-font)!important; }
html:root:root.univer-mobile .univer-app-add-close { display:grid; place-items:center; width:44px; height:44px; min-height:0!important; margin:-6px -8px -6px 0!important; padding:0!important; border:0!important; border-radius:10px!important; background:transparent!important; box-shadow:none!important; color:var(--u-muted)!important; }
html:root.univer-mobile .univer-app-add-close::before { content:''; width:20px; height:20px; background:currentColor; -webkit-mask:var(--univer-close-icon) center/contain no-repeat; }
html:root:root.univer-mobile .univer-app-add-search { display:block; flex:0 0 auto; width:100%; height:44px; min-height:44px; box-sizing:border-box; margin:0 0 6px; padding:0 14px!important; border:1px solid var(--u-line-strong); border-radius:10px; background-color:var(--u-surface); color:var(--u-ink); font:16px var(--u-font); }
html:root.univer-mobile .univer-app-add-list { flex:1 1 auto; min-height:0; margin:0 -8px; padding:0 8px; overflow-y:auto; overscroll-behavior:contain; }
html:root.univer-mobile .univer-app-add-group { margin:14px 0 4px!important; padding:0 8px; color:var(--u-muted); font:600 13px var(--u-font)!important; }
html:root:root.univer-mobile .univer-app-add-row { display:flex; align-items:center; gap:12px; width:100%; min-height:52px; box-sizing:border-box; margin:0!important; padding:6px 8px!important; border:0!important; border-radius:10px!important; background:transparent!important; box-shadow:none!important; color:var(--u-ink)!important; font:500 15px/1.3 var(--u-font)!important; text-align:start; }
html:root:root.univer-mobile .univer-app-add-row:active { background:var(--u-brand-soft)!important; }
html:root.univer-mobile .univer-app-add-row .univer-app-link-icon { flex:0 0 auto; width:32px; height:32px; border-radius:8px; }
html:root.univer-mobile .univer-app-add-name { flex:1 1 auto; min-width:0; overflow-wrap:break-word; }
html:root.univer-mobile .univer-app-add-plus { flex:0 0 auto; width:18px; height:18px; background:var(--u-brand); -webkit-mask:var(--univer-plus-icon) center/contain no-repeat; }
html:root.univer-mobile .univer-app-add-empty { margin:24px 0!important; color:var(--u-muted); font:15px var(--u-font)!important; text-align:center; }
/* The bin: a calm strip with a thin frame; over it the frame lights up. */
html:root:root.univer-mobile .univer-app-trash { height:44px; min-height:44px; padding:0 20px; border:1px solid rgba(180,35,24,.3); border-radius:10px; background:var(--u-danger-soft); color:var(--u-danger); font-size:15px; font-weight:600; }
html:root:root.univer-mobile .univer-app-trash.univer-app-trash-hot { border:1px solid var(--u-danger); background:var(--u-danger-soft); color:var(--u-danger); box-shadow:0 0 0 3px rgba(180,35,24,.16); }
html:root:root.univer-mobile .univer-app-trash-icon { width:18px; height:18px; }
/* GPA: two cards, the number above its name. */
html:root:root.univer-mobile .univer-app-gpa { display:grid!important; grid-template-columns:repeat(2,minmax(0,1fr))!important; gap:10px!important; margin:0 0 12px!important; border:0!important; border-radius:0; background:none; overflow:visible; }
html:root:root.univer-mobile .univer-app-gpa>div { display:block; padding:12px 14px!important; border:1px solid var(--u-line)!important; border-radius:12px!important; background:var(--u-surface)!important; box-shadow:none!important; }
html:root:root.univer-mobile .univer-app-gpa b { display:block; font-size:26px!important; line-height:1.15; font-weight:650!important; }
html:root:root.univer-mobile .univer-app-gpa span { display:block; margin:2px 0 0!important; font-size:13px!important; }
/* A class reads alike on the home screen and in the schedule: the time first, then what, where and who. */
html:root:root:root.univer-mobile .univer-app-lesson { display:grid!important; grid-template-columns:52px minmax(0,1fr); column-gap:12px; align-items:start; padding:12px 14px!important; cursor:pointer; }
html:root:root:root.univer-mobile .univer-app-lesson>time { display:flex!important; flex-direction:column; align-items:flex-start; min-width:0; margin:0!important; padding:0!important; border-radius:0!important; background:none!important; color:inherit!important; font-variant-numeric:tabular-nums; line-height:1.25; }
html:root:root:root.univer-mobile .univer-app-lesson>time>b { color:var(--u-ink); font-size:17px; font-weight:700; }
html:root:root:root.univer-mobile .univer-app-lesson>time>span { margin-top:2px; color:var(--u-muted); font-size:14px; font-weight:400; }
html:root:root:root.univer-mobile .univer-app-lesson-main { min-width:0; }
html:root:root:root.univer-mobile .univer-app-lesson p.univer-app-subject { display:flex; flex-wrap:wrap; align-items:center; gap:2px 8px; margin:0!important; font-size:16px!important; line-height:1.3!important; }
html:root:root:root.univer-mobile a.univer-app-lesson-name, html:root:root:root:root.univer-mobile #l4 a.univer-app-lesson-name { display:inline!important; min-height:0!important; height:auto!important; margin:0!important; padding:0!important; border:0!important; background:none!important; box-shadow:none!important; color:var(--u-ink)!important; font:inherit!important; font-weight:600!important; text-decoration:none!important; }
html:root:root:root.univer-mobile .univer-app-lesson :is(.univer-app-lesson-badge,.univer-app-overview-state) { display:inline-flex; align-items:center; min-height:20px; box-sizing:border-box; margin:0!important; padding:0 9px!important; border-radius:999px!important; font-size:12px!important; font-weight:600; line-height:1; white-space:nowrap; }
html:root:root:root.univer-mobile .univer-app-lesson ul.univer-app-lesson-facts { display:block; margin:3px 0 0!important; }
html:root:root:root.univer-mobile .univer-app-lesson ul.univer-app-lesson-facts>li.univer-app-fact { display:flex; flex-wrap:wrap; align-items:center; gap:0 10px; color:var(--u-text)!important; font-size:14px!important; line-height:1.4!important; }
html:root:root:root.univer-mobile .univer-app-lesson .univer-app-fact::before, html:root:root:root.univer-mobile .univer-app-fact-place>span+span::before { display:none; content:none; }
html:root:root:root.univer-mobile .univer-app-lesson ul.univer-app-lesson-facts>li.univer-app-fact-teacher { color:var(--u-muted)!important; }
html:root:root:root.univer-mobile .univer-app-fact-room { color:var(--u-ink)!important; font-weight:600; }
html:root:root:root.univer-mobile .univer-app-lesson-type { margin:0!important; padding:0!important; border-radius:0!important; background:none!important; color:#b45309; font-size:14px; font-weight:600; line-height:inherit; }
html:root:root:root.univer-mobile .univer-app-lesson-type[data-kind=lecture] { color:var(--u-brand); }
html:root:root:root.univer-mobile .univer-app-lesson-type[data-kind=seminar] { color:var(--u-teal); }
html:root:root:root.univer-mobile .univer-app-lesson.univer-app-lesson-done :is(time>b,a.univer-app-lesson-name,.univer-app-fact-room,.univer-app-lesson-type) { color:var(--u-muted)!important; }
html:root:root:root.univer-mobile .univer-app-lesson a.univer-app-page-link { margin-top:8px!important; }
/* «В календарь» on a class: a quiet outlined chip under the facts, out of the card's own tap. */
html:root:root:root.univer-mobile .univer-app-lesson button.univer-app-calendar { display:inline-flex!important; align-items:center; gap:6px; box-sizing:border-box; min-height:44px; margin:0 0 -10px -8px!important; padding:0 8px!important; border:0!important; border-radius:10px!important; background:transparent!important; color:var(--u-brand)!important; font-family:inherit; font-size:14px!important; font-weight:500; line-height:1.2; cursor:pointer; }
html:root:root:root.univer-mobile .univer-app-lesson button.univer-app-calendar::before { content:''; flex:none; width:16px; height:16px; background:currentColor; -webkit-mask:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Crect x='3' y='5' width='18' height='16' rx='2'/%3E%3Cpath d='M16 3v4M8 3v4M3 10h18M12 13v5M9.5 15.5h5'/%3E%3C/svg%3E") center/16px no-repeat; mask:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Crect x='3' y='5' width='18' height='16' rx='2'/%3E%3Cpath d='M16 3v4M8 3v4M3 10h18M12 13v5M9.5 15.5h5'/%3E%3C/svg%3E") center/16px no-repeat; }
html:root:root:root.univer-mobile .univer-app-overview-lesson>.univer-app-lesson { padding:12px 0!important; }
html:root:root:root.univer-mobile li.univer-app-overview-now:has(>.univer-app-lesson) { box-sizing:border-box; width:calc(100% + 24px); margin:0 -12px!important; padding:0 12px!important; border-radius:10px; background:var(--u-brand-soft); }
@media (max-width:359px) { html:root:root:root.univer-mobile .univer-app-lesson { grid-template-columns:46px minmax(0,1fr); column-gap:10px; } }
/* The class in progress: tinted like the desktop's, one frame around it. */
html:root:root.univer-mobile .univer-app-now { border:1px solid var(--u-brand-line)!important; }
html:root:root.univer-mobile .univer-app-now[data-state=now] { background:var(--u-brand-soft)!important; }
html:root:root.univer-mobile .univer-app-now[data-state=now] :is(.univer-app-now-title,.univer-app-now-count) { color:var(--u-ink)!important; }
/* A grade reads the same on the home screen and in the journal: one outlined chip with the score along its foot. */
html:root:root:root.univer-mobile .univer-app-overview-scores { display:inline-flex; flex-wrap:nowrap; gap:6px; }
html:root:root:root.univer-mobile :is(.univer-app-overview-score,.univer-app-overview-courses .univer-app-overview-score) { position:relative; display:inline-flex; align-items:baseline; justify-content:space-between; gap:6px; box-sizing:border-box; min-width:68px!important; padding:3px 8px 6px!important; flex:0 0 auto; border:1px solid var(--u-line)!important; border-radius:8px!important; overflow:hidden; background:var(--u-surface)!important; white-space:nowrap; }
html:root:root:root.univer-mobile .univer-app-overview-score-label { color:var(--u-muted)!important; font-size:12px; font-weight:500!important; }
html:root:root:root.univer-mobile .univer-app-overview-score-value { color:var(--u-ink)!important; font-size:15px!important; font-weight:650!important; font-variant-numeric:tabular-nums; }
html:root:root:root.univer-mobile .univer-app-overview-score-bar { display:block!important; position:absolute; left:0; bottom:0; height:3px; width:var(--univer-score); background:var(--u-teal); }
html:root:root:root.univer-mobile .univer-app-overview-courses>li, html:root:root:root:root.univer-mobile #l4 ul.univer-app-attendance-overview-list>li { display:flex!important; flex-wrap:nowrap; align-items:center; justify-content:space-between; gap:12px; padding:8px 0!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-overview-courses>li>a.univer-app-overview-course-link, html:root:root:root.univer-mobile .univer-app-overview-courses>li>a.univer-app-overview-course-link { display:block!important; flex:1 1 auto; min-width:0; min-height:0!important; padding:0!important; line-height:1.3!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-overview-work-list .univer-app-overview-lesson-main>a.univer-app-overview-course-link { display:block!important; min-height:0!important; margin:0!important; padding:0!important; line-height:1.4!important; }
html:root:root:root.univer-mobile .univer-app-overview-work-list>li { padding:12px 0!important; }
/* Journal filters: subjects as chips in a row that scrolls sideways, class types as a segment under their name. */
html:root:root:root:root.univer-mobile #l4 .univer-app-attendance-tools { margin:0 0 12px!important; padding:0!important; border:0!important; background:none!important; box-shadow:none!important; }
html:root:root:root:root.univer-mobile #l4 button.univer-app-attendance-report { margin:12px 0 0!important; }
html:root:root:root:root.univer-mobile #l4 :is(p.univer-app-attendance-course-caption,p.univer-app-attendance-filter-caption) { display:block; margin:0 0 6px!important; padding:0; color:var(--u-ink)!important; font-size:14px!important; font-weight:600; }
html:root:root:root:root.univer-mobile #l4 p.univer-app-attendance-filter-caption { margin-top:12px!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-attendance-course-list { display:flex; flex-wrap:nowrap!important; gap:6px; margin:0 -12px; padding:0 12px 2px; overflow-x:auto; scrollbar-width:none; overscroll-behavior-x:contain; }
html:root:root:root:root.univer-mobile #l4 .univer-app-attendance-course-list::-webkit-scrollbar { display:none; }
html:root:root:root:root.univer-mobile #l4 .univer-app-attendance-course-list>button { flex:0 0 auto; min-height:44px!important; height:44px; margin:0!important; padding:0 14px!important; border:1px solid var(--u-line)!important; border-radius:10px!important; background:var(--u-surface)!important; box-shadow:none!important; color:var(--u-ink)!important; font-size:14px!important; font-weight:600!important; white-space:nowrap; }
html:root:root:root:root.univer-mobile #l4 .univer-app-attendance-course-list>button[aria-pressed=true] { border-color:var(--u-brand)!important; background:var(--u-brand)!important; color:#fff!important; }
html:root:root:root:root.univer-mobile #l4 p.univer-app-attendance-filter-note { margin:8px 0 0!important; color:var(--u-muted)!important; font-size:13px!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-attendance-overview { margin:12px 0 0!important; padding:0!important; overflow:clip; border:1px solid var(--u-line); border-radius:12px; background:var(--u-surface); }
html:root:root:root:root.univer-mobile #l4 p.univer-app-attendance-overview-title { display:flex; align-items:center; min-height:48px; box-sizing:border-box; margin:0!important; padding:12px 14px!important; border-bottom:1px solid var(--u-line); background:var(--u-surface-2); color:var(--u-ink)!important; font-size:16px!important; font-weight:600!important; }
html:root:root:root:root.univer-mobile #l4 ul.univer-app-attendance-overview-list { margin:0!important; padding:4px 14px 8px!important; }
html:root:root:root:root.univer-mobile #l4 ul.univer-app-attendance-overview-list>li:first-child { border-top:0; }
html:root:root:root:root.univer-mobile #l4 button.univer-app-attendance-overview-name { flex:1 1 auto; min-width:0; min-height:0!important; height:auto!important; padding:0!important; border:0!important; background:none!important; box-shadow:none!important; color:var(--u-brand)!important; font:500 15px/1.3 var(--u-font)!important; text-align:start; }
/* A course: its name, one small table of numbers, and the class log folded under it. */
html:root:root:root:root.univer-mobile #l4 .univer-app-course-heading { display:block; margin:6px 0 8px; padding-top:14px; text-align:start; }
html:root:root:root:root.univer-mobile #l4 :is(.univer-app-course-name,.univer-app-course-title>.univer-app-attendance-course) { display:inline; min-height:0!important; height:auto!important; padding:0!important; border:0!important; background:none!important; box-shadow:none!important; color:var(--u-ink)!important; font-size:18px!important; font-weight:600!important; line-height:1.3; text-decoration:none!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-course-meta { display:flex; flex-wrap:wrap; gap:0 12px; margin-top:2px; color:var(--u-muted); font-size:13px!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-course-meta span { font-size:13px!important; }
html:root:root:root:root.univer-mobile #l4 button.univer-app-course-syllabus { min-height:40px!important; height:40px; margin:8px 0 0!important; padding:0 12px!important; border-radius:10px!important; font-size:14px!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-attendance-summary { display:block; width:100%; box-sizing:border-box; margin:0 0 6px!important; padding:0!important; overflow:hidden; border:1px solid var(--u-line); border-radius:12px; background:var(--u-surface); box-shadow:none; }
html:root:root:root:root.univer-mobile #l4 .univer-app-score-table>div { display:grid; grid-template-columns:minmax(0,1fr) repeat(var(--univer-columns,2),minmax(52px,76px)); align-items:center; min-height:40px; box-sizing:border-box; padding:4px 14px; border:0; border-top:1px solid var(--u-line); }
html:root:root:root:root.univer-mobile #l4 .univer-app-score-table>div>:not(:first-child) { text-align:end; }
html:root:root:root:root.univer-mobile #l4 .univer-app-score-head { min-height:38px!important; border-top:0!important; background:var(--u-surface-2); color:var(--u-muted); font-size:13px; font-weight:600; }
html:root:root:root:root.univer-mobile #l4 .univer-app-score-head small { display:block; color:var(--u-muted); font-size:12px; font-weight:400; }
html:root:root:root:root.univer-mobile #l4 .univer-app-score-table .univer-app-type-row { gap:0; flex-wrap:nowrap; min-height:40px; padding:4px 14px!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-type-label { min-width:0; color:var(--u-ink); font-size:15px; font-weight:500; }
html:root:root:root:root.univer-mobile #l4 .univer-app-score-table .univer-app-score { display:block; position:static; min-width:0!important; padding:0!important; border:0; border-radius:0; background:none; box-shadow:none; color:var(--u-ink); font-size:15px; font-weight:500; font-variant-numeric:tabular-nums; }
html:root:root:root:root.univer-mobile #l4 p.univer-app-attendance-nothing, html:root:root:root:root.univer-mobile #l4 .univer-app-attendance-summary>.univer-app-type-row, html:root:root:root:root.univer-mobile #l4 p.univer-app-attendance-overall-value { margin:0!important; padding:10px 14px!important; }
html:root:root:root:root.univer-mobile #l4 button.univer-app-attendance-details-toggle { display:flex!important; align-items:center; width:100%; height:44px!important; min-height:44px!important; margin:0!important; padding:0 14px!important; border:0!important; border-top:1px solid var(--u-line)!important; border-radius:0!important; background:none!important; box-shadow:none!important; color:var(--u-brand)!important; font-size:14px!important; font-weight:600!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-lesson-label { gap:2px 10px; margin:12px 0 6px; }
@media (max-width:359px) {
 html:root:root:root:root.univer-mobile #l4 .univer-app-score-table>div { grid-template-columns:minmax(0,1fr) repeat(var(--univer-columns,2),minmax(44px,60px)); padding-left:12px; padding-right:12px; }
 html:root:root:root.univer-mobile .univer-app-overview-courses>li, html:root:root:root:root.univer-mobile #l4 ul.univer-app-attendance-overview-list>li { flex-wrap:wrap; gap:4px 12px; }
}
/* Segments: a quiet track and a raised thumb that slides to the choice (univer-kit.js places it). */
html:root:root:root.univer-mobile :is(.univer-app-schedule-modes,#l4 .univer-app-attendance-filters) { position:relative; display:flex; flex-wrap:wrap; gap:0; width:auto; max-width:100%; box-sizing:border-box; margin:0; padding:2px!important; border:0!important; border-radius:10px; background:#e9eef4; box-shadow:none; }
html:root:root:root.univer-mobile .univer-app-schedule-modes { margin:0 0 8px; }
html:root:root:root:root.univer-mobile #l4 :is(.univer-app-schedule-modes,.univer-app-attendance-filters) button { position:relative; z-index:1; flex:1 1 auto; min-height:40px!important; height:40px; margin:0!important; padding:0 10px!important; border:0!important; border-radius:8px!important; background:transparent!important; box-shadow:none!important; color:var(--u-muted)!important; font-size:14px!important; font-weight:600!important; white-space:nowrap; }
html:root:root:root:root.univer-mobile #l4 :is(.univer-app-schedule-modes,.univer-app-attendance-filters) button[aria-pressed=true] { background:#fff!important; color:var(--u-brand)!important; box-shadow:0 1px 2px rgba(16,40,70,.1),0 0 0 .5px rgba(16,40,70,.08)!important; }
html:root.univer-mobile .univer-kit-thumb { position:absolute; left:0; top:0; border-radius:8px; background:#fff; box-shadow:0 1px 2px rgba(16,40,70,.1),0 0 0 .5px rgba(16,40,70,.08); transition:transform .3s var(--u-ease-out),width .3s var(--u-ease-out),height .3s var(--u-ease-out); }
html:root:root:root:root.univer-mobile #l4 .univer-kit-slid button[aria-pressed=true] { background:transparent!important; box-shadow:none!important; color:var(--u-brand)!important; }
html:root.univer-mobile.univer-app-still .univer-kit-thumb { transition:none; }
@media (prefers-reduced-motion:reduce) { html:root.univer-mobile .univer-kit-thumb { transition:none; } }
@media (max-width:359px) { html:root:root:root:root.univer-mobile #l4 :is(.univer-app-schedule-modes,.univer-app-attendance-filters) button { padding:0 6px!important; font-size:13px!important; } }
/* News: a search field and two quiet filters; what they found is said only when something is filtered. */
html:root:root:root:root.univer-mobile #l4 .univer-app-news-tools { display:flex!important; flex-wrap:wrap!important; align-items:center!important; gap:8px!important; margin:0 0 10px!important; padding:0!important; border:0!important; border-radius:0!important; background:none!important; box-shadow:none!important; }
html:root:root:root:root.univer-mobile #l4 input.univer-app-news-query { flex:1 0 100%; width:100%; min-width:0; height:44px; background-color:var(--u-surface)!important; }
html:root.univer-mobile .univer-app-news-period { position:relative; flex:1 1 0; min-width:0; }
html:root:root.univer-mobile .univer-app-news-period>summary { display:flex; align-items:center; justify-content:center; gap:8px; height:44px; box-sizing:border-box; padding:0 14px; border:1px solid var(--u-line-strong); border-radius:10px; background:var(--u-surface); color:var(--u-ink); font-size:14px; font-weight:600; list-style:none; cursor:pointer; }
html:root.univer-mobile .univer-app-news-period>summary::-webkit-details-marker { display:none; }
html:root.univer-mobile .univer-app-news-period>summary::after { content:''; width:6px; height:6px; margin:-3px 2px 0 2px; border-right:2px solid var(--u-muted); border-bottom:2px solid var(--u-muted); transform:rotate(45deg); }
html:root:root.univer-mobile :is(.univer-app-news-period[open],.univer-app-news-period-on)>summary { border-color:var(--u-brand); background:var(--u-brand-soft); color:var(--u-brand); }
html:root.univer-mobile .univer-app-news-period-box { position:absolute; top:calc(100% + 6px); left:0; z-index:40; display:flex; flex-direction:column; gap:8px; width:min(280px,calc(100vw - 24px)); box-sizing:border-box; padding:10px; border:1px solid var(--u-line-strong); border-radius:12px; background:var(--u-surface); box-shadow:var(--u-shadow-pop); }
html:root.univer-mobile .univer-app-news-presets { display:flex; flex-direction:column; gap:2px; padding-bottom:8px; border-bottom:1px solid var(--u-line); }
html:root:root:root:root.univer-mobile #l4 button.univer-app-news-preset { display:block; width:100%; height:44px!important; min-height:44px!important; padding:0 10px!important; border:0!important; border-radius:8px!important; background:transparent!important; box-shadow:none!important; color:var(--u-ink)!important; font-size:15px!important; font-weight:500!important; text-align:start; }
html:root:root:root:root.univer-mobile #l4 .univer-app-news-date { display:grid; grid-template-columns:28px minmax(0,1fr); flex:none; align-items:center; gap:6px; }
html:root:root:root:root.univer-mobile #l4 .univer-app-news-date input { width:100%!important; height:44px; }
html:root:root:root:root.univer-mobile #l4 button.univer-app-news-important { flex:1 1 0; min-width:0; height:44px!important; min-height:44px!important; padding:0 10px!important; border:1px solid var(--u-line-strong)!important; border-radius:10px!important; background:var(--u-surface)!important; box-shadow:none!important; color:var(--u-ink)!important; font-size:14px!important; font-weight:600!important; }
html:root:root:root:root.univer-mobile #l4 button.univer-app-news-important[aria-pressed=true] { border-color:var(--u-brand)!important; background:var(--u-brand-soft)!important; color:var(--u-brand)!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-news-state { flex:1 0 100%; display:flex; flex-wrap:wrap; align-items:center; gap:4px 14px; min-height:24px; }
html:root:root:root:root.univer-mobile #l4 .univer-app-news-state[hidden] { display:none!important; }
html:root:root:root:root.univer-mobile .univer-app-news-count { margin:0; color:var(--u-muted); font-size:14px!important; }
html:root:root:root:root.univer-mobile #l4 button:is(.univer-app-news-reset,.univer-app-news-all) { height:auto!important; min-height:32px!important; padding:0!important; border:0!important; background:none!important; box-shadow:none!important; color:var(--u-brand)!important; font-size:14px!important; font-weight:600!important; }
html:root:root:root:root.univer-mobile #l4 input.univer-app-news-query { flex:1 0 100%!important; width:100%!important; max-width:none!important; }
html:root:root.univer-mobile .univer-app-news-period>summary { white-space:nowrap; }
html:root:root:root:root.univer-mobile .univer-app-news-pages { display:none!important; }
/* All sections: each section is a small outlined row, its pin at the row's end; groups fold on a phone. */
html:root:root:root.univer-mobile :is(#tools .univer-app-category,.univer-app-menu>.univer-app-useful) ul { display:flex!important; flex-direction:column; gap:6px; margin:0!important; padding:2px 0 8px!important; list-style:none!important; }
html:root:root:root.univer-mobile :is(#tools .univer-app-category,.univer-app-menu>.univer-app-useful) li:not([hidden]) { min-height:48px; box-sizing:border-box; margin:0!important; padding:0 2px 0 0!important; border:1px solid var(--u-line)!important; border-radius:10px!important; background:var(--u-surface)!important; }
html:root:root:root.univer-mobile :is(#tools .univer-app-category,.univer-app-menu>.univer-app-useful) li:has(a[aria-current=page]) { border-color:var(--u-brand)!important; background:var(--u-brand-soft)!important; }
html:root:root:root.univer-mobile :is(#tools .univer-app-category,.univer-app-menu>.univer-app-useful) li a { padding:8px 12px!important; color:var(--u-ink)!important; font-weight:500!important; }
/* An item that holds two links (Расписание and Расписание экзаменов) is two cards, each with its own outline, press state and pin. */
html:root:root:root:root.univer-mobile #tools .univer-app-category li:not([hidden]):has(>.univer-app-pin-row~.univer-app-pin-row) { display:flex!important; flex-direction:column!important; gap:6px; min-height:0; padding:0!important; border:0!important; background:none!important; }
html:root:root:root:root.univer-mobile #tools .univer-app-category li:has(>.univer-app-pin-row~.univer-app-pin-row)>br { display:none!important; }
html:root:root:root:root.univer-mobile #tools .univer-app-category li:has(>.univer-app-pin-row~.univer-app-pin-row)>.univer-app-pin-row { min-height:48px; box-sizing:border-box; padding:0 2px 0 0; border:1px solid var(--u-line); border-radius:10px; background:var(--u-surface); }
html:root:root:root:root.univer-mobile #tools .univer-app-category li:has(>.univer-app-pin-row~.univer-app-pin-row)>.univer-app-pin-row:has(a[aria-current=page]) { border-color:var(--u-brand); background:var(--u-brand-soft); }
html:root:root:root:root.univer-mobile #tools .univer-app-category li:has(>.univer-app-pin-row~.univer-app-pin-row)>.univer-app-pin-row:active { background:var(--u-brand-soft); }
/* Score bands (dom.scoreBand): below 50 short, 50–69 admitted, 70–89 good, 90 and up excellent. The colour always
   comes with words (band name, "До 70 не хватает 7", the legend), so it is never the only signal. Muted on purpose:
   --u-band fills bars, --u-band-ink colours words (4.5:1 and up on --u-band-soft), --u-band-soft is the background. */
html:root:root:root:root.univer-mobile [data-band=low] { --u-band:var(--u-band-low); --u-band-ink:var(--u-band-low-ink); --u-band-soft:var(--u-band-low-soft); }
html:root:root:root:root.univer-mobile [data-band=pass] { --u-band:var(--u-band-pass); --u-band-ink:var(--u-band-pass-ink); --u-band-soft:var(--u-band-pass-soft); }
html:root:root:root:root.univer-mobile [data-band=good] { --u-band:var(--u-band-good); --u-band-ink:var(--u-band-good-ink); --u-band-soft:var(--u-band-good-soft); }
html:root:root:root:root.univer-mobile [data-band=top] { --u-band:var(--u-band-top); --u-band-ink:var(--u-band-top-ink); --u-band-soft:var(--u-band-top-soft); }
html:root:root:root:root.univer-mobile .univer-app-overview-score[data-band] { background:var(--u-band-soft)!important; border-color:transparent!important; }
html:root:root:root:root.univer-mobile .univer-app-overview-score-bar[data-band] { background:var(--u-band)!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-score[data-band] .univer-app-score-bar { background:var(--u-band-soft)!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-score-bar[data-band]::after { background:var(--u-band)!important; }
/* A course's totals: each control period against the marks 50, 70 and 90, with how far the next mark is. */
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-list { grid-column:1/-1; display:grid; gap:6px; min-width:0; padding:10px 14px; }
html:root:root:root:root.univer-mobile #l4 .univer-app-score-table+.univer-app-progress-list { border-top:1px solid var(--u-line); }
html:root:root:root:root.univer-mobile #l4 p.univer-app-progress-title { margin:0!important; color:var(--u-muted); font-size:var(--u-fs-meta)!important; font-weight:600; line-height:1.4!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress { display:grid; gap:4px; min-width:0; padding:10px 14px 8px; border:1px solid var(--u-line); border-radius:10px; background:var(--u-surface); }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress:not([data-band]) { padding:0 14px; border-color:transparent; background:none; }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress:not([data-band]) .univer-app-progress-value>b { font-size:var(--u-fs-body); }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress[data-band] { border-color:transparent; background:var(--u-band-soft); }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-head { display:flex; flex-wrap:wrap; align-items:baseline; gap:2px 10px; }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-label { color:var(--u-ink); font-size:var(--u-fs-body); font-weight:600; }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-band { color:var(--u-band-ink); font-size:var(--u-fs-meta); font-weight:600; }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-value { margin-left:auto; color:var(--u-muted); font-size:var(--u-fs-meta); font-variant-numeric:tabular-nums; white-space:nowrap; }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-value>b { color:var(--u-ink); font-size:var(--u-fs-section); font-weight:600; }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-track { position:relative; height:10px; margin:2px 0 0; border-radius:999px; background:var(--u-surface); box-shadow:inset 0 0 0 1px var(--u-line); overflow:hidden; }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-fill { position:absolute; top:0; bottom:0; left:0; width:var(--univer-score); border-radius:inherit; background:var(--u-band); }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-track>i { position:absolute; z-index:1; top:0; bottom:0; left:var(--at); width:2px; margin-left:-1px; background:var(--u-line-strong); }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-track>i.univer-app-progress-passed { background:var(--u-surface); }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-scale { position:relative; height:14px; color:var(--u-muted); font-size:var(--u-fs-caption); line-height:14px; font-variant-numeric:tabular-nums; }
html:root:root:root:root.univer-mobile #l4 .univer-app-progress-scale>span { position:absolute; left:var(--at); transform:translateX(-50%); }
html:root:root:root:root.univer-mobile #l4 p.univer-app-progress-next { display:block!important; margin:0!important; color:var(--u-text); font-size:var(--u-fs-meta)!important; line-height:1.4!important; }
html:root:root:root:root.univer-mobile #l4 p.univer-app-band-legend { display:flex!important; flex-wrap:wrap; gap:4px 14px; margin:4px 0 10px!important; padding:10px 14px 0; border-top:1px solid var(--u-line); color:var(--u-muted); font-size:var(--u-fs-caption)!important; line-height:1.5!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-band-legend-title { flex-basis:100%; color:var(--u-ink); font-weight:600; }
html:root:root:root:root.univer-mobile #l4 .univer-app-band-legend-item { display:inline-flex; align-items:center; gap:6px; }
html:root:root:root:root.univer-mobile #l4 .univer-app-band-legend-item::before { content:''; flex:none; width:10px; height:10px; border-radius:3px; background:var(--u-band); }
/* The goal: four chips that wrap on a narrow phone. What is left to it is the line a parent reads first, so it is set
   heavier, in the band's ink; a reached goal stays plain. */
html:root:root:root:root.univer-mobile #l4 .univer-app-goal { margin:12px 0 0!important; padding:0!important; border:0!important; background:none!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-goal[hidden] { display:none!important; }
html:root:root:root:root.univer-mobile #l4 p.univer-app-goal-caption { display:block; margin:0 0 6px!important; padding:0; color:var(--u-ink)!important; font-size:14px!important; font-weight:600; }
html:root:root:root:root.univer-mobile #l4 .univer-app-goal-list { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:6px; }
@media (min-width:640px) { html:root:root:root:root.univer-mobile #l4 .univer-app-goal-list { grid-template-columns:repeat(4,max-content); } }
html:root:root:root:root.univer-mobile #l4 .univer-app-goal-list>button { min-width:0; min-height:44px!important; height:auto; margin:0!important; padding:8px 12px!important; text-align:center; border:1px solid var(--u-line)!important; border-radius:10px!important; background:var(--u-surface)!important; box-shadow:none!important; color:var(--u-ink)!important; font-size:14px!important; font-weight:600!important; line-height:1.2!important; }
html:root:root:root:root.univer-mobile #l4 .univer-app-goal-list>button[aria-pressed=true] { border-color:var(--u-brand)!important; background:var(--u-brand-soft)!important; color:var(--u-brand-strong)!important; }
html:root:root:root:root.univer-mobile #l4 p.univer-app-progress-next[data-univer-left] { color:var(--u-band-ink)!important; font-size:var(--u-fs-body)!important; font-weight:650; }
html:root:root:root:root.univer-mobile #l4 .univer-app-fresh { padding:1px 7px; border-radius:999px; background:var(--u-brand); color:#fff; font-size:var(--u-fs-caption); font-weight:600; line-height:1.5; }
html:root:root:root:root.univer-mobile.univer-app-dark #l4 .univer-app-fresh { color:var(--u-bg); }
html:root:root:root:root.univer-mobile .univer-app-overview-score[data-univer-fresh]::after { content:''; position:absolute; top:4px; right:4px; width:7px; height:7px; border-radius:50%; background:var(--u-brand); }
html:root:root:root:root.univer-mobile #l4 p.univer-app-attendance-absences { margin:0!important; padding:10px 14px!important; border-top:1px solid var(--u-line); color:var(--u-text)!important; font-size:var(--u-fs-meta)!important; line-height:1.4!important; }
html:root:root:root:root.univer-mobile #l4 p.univer-app-attendance-absences>b { margin-left:4px; color:var(--u-ink); font-weight:650; }
html:root:root:root:root.univer-mobile #l4 p.univer-app-attendance-absences>span { color:var(--u-muted); }
:root.univer-mobile.univer-app-dark { color-scheme:dark;
  --u-brand:#8ebbf0; --u-brand-strong:#b5d3f6; --u-brand-soft:#1a2b40; --u-brand-line:#34507a;
  --u-ink:#e6ecf3; --u-text:#cfd8e2; --u-muted:#9fadbc; --u-line:#2b3746; --u-line-strong:#3b4a5b;
  --u-bg:#0f151c; --u-surface:#17202a; --u-surface-2:#1d2733;
  --u-teal:#5fd0c2; --u-teal-soft:#13302c; --u-danger:#ff8c82; --u-danger-soft:#3a1d1b;
  --u-band-low:#b06f68; --u-band-low-ink:#d99088; --u-band-low-soft:#2a2020; --u-band-pass:#a3824c; --u-band-pass-ink:#cfa86a; --u-band-pass-soft:#2a2519;
  --u-band-good:#558c6c; --u-band-good-ink:#80bb98; --u-band-good-soft:#1b2821; --u-band-top:#6487b2; --u-band-top-ink:#93b3d9; --u-band-top-soft:#1c2531;
  --u-shadow-pop:0 12px 36px rgba(0,0,0,.5),0 2px 6px rgba(0,0,0,.35);
  --u-brand-fill:#1b5fa6; --u-brand-strong-fill:#154d88; --u-muted-fill:#5b6b7c; --u-teal-fill:#0f766e; --u-danger-fill:#b42318;
  --u-select-arrow:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%239fadbc' stroke-width='2.4' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M6 9l6 6 6-6'/%3E%3C/svg%3E");
}
/* The site paints a lesson with inline colours, out of the palette's reach: in the dark it takes the same role's tint. */
@media screen { html:root.univer-mobile.univer-app-dark table.schedule :is(td.field:has(.groups),.groups>div) { background-color:#22395a!important; color:#cfd8e2!important; } }
@media print { :root.univer-mobile.univer-app-dark, :root.univer-mobile.univer-app-dark * { color:#000!important; background:transparent!important; border-color:#999!important; box-shadow:none!important; } }
`;
                if (window.__univerPaletteV1) window.__univerPaletteV1.fill(style, source); else style.textContent = source;
                (doc.head || doc.documentElement).appendChild(style);
            }
            apply(document);
            if (window.__univerKitV1) window.__univerKitV1.configure(enabled);
            // Same-origin frames only, such as the known notice; the page content is not rewritten.
            Array.from(document.querySelectorAll('iframe,frame')).forEach(function (frame) {
                try { if (frame.contentWindow.location.origin === location.origin && frame.contentDocument) apply(frame.contentDocument); } catch (_) { }
            });
        }
    };
})();
