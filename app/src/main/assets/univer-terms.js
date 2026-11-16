/* Semester switches (td.ts): the site's long previous/next links become one compact row of plain links to the same
 * href. The originals stay in place, hidden in the phone view; Original shows them unchanged. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerTermsV1) return;
    var dom = window.__univerDomV1, clean = dom.clean;
    var language = 'ru', navs = new Map();
    var seasons = [['spring', /весен|көктем|spring/i], ['autumn', /осен|күз|autumn|fall/i], ['summer', /летн|жазғы|summer/i], ['winter', /зимн|қысқы|winter/i]];
    function text() {
        return language === 'en' ? {region:'Semesters', previous:'Previous', next:'Next', spring:'Spring semester', autumn:'Fall semester', summer:'Summer semester', winter:'Winter semester'} :
            language === 'kz' ? {region:'Семестрлер', previous:'Алдыңғы', next:'Келесі', spring:'Көктемгі семестр', autumn:'Күзгі семестр', summer:'Жазғы семестр', winter:'Қысқы семестр'} :
            {region:'Семестры', previous:'Предыдущий', next:'Следующий', spring:'Весенний семестр', autumn:'Осенний семестр', summer:'Летний семестр', winter:'Зимний семестр'};
    }
    // Only the observed semester links: an arrow icon and a "YYYY-YYYY" academic year, or the one year of a UMKD term.
    function period(link) {
        var years = clean(link).match(/\b(\d{4})\s*[-–]\s*(\d{4})\b/);
        if (years) return +years[2] - +years[1] === 1 ? years[1] + '–' + years[2] : '';
        return link.pathname.match(/^\/student\/umkd\/\d+\/(\d{4})\/[12]\/?$/)?.[1] || '';
    }
    function term(link) {
        var icon = link.querySelector('img[src*="tb_left"],img[src*="tb_right"]'), years = period(link);
        var href = link.getAttribute('href');
        // The copy navigates like the original, so only Univer's own semester addresses qualify.
        if (!icon || !years || !href || href.charAt(0) === '#' || link.origin !== location.origin || link.protocol !== 'https:') return null;
        var season = seasons.find(function (s) { return s[1].test(clean(link)); });
        return {link:link, years:years, season:season ? season[0] : '', next:/tb_right/.test(icon.getAttribute('src'))};
    }
    function drop(cell) {
        var record = navs.get(cell); if (!record) return;
        record.nav.remove();
        record.links.forEach(function (link) { link.classList.remove('univer-app-term-source'); if (!link.className) link.removeAttribute('class'); });
        navs.delete(cell);
    }
    function render(cell, terms, signature) {
        var nav = document.createElement('nav'); nav.className = 'univer-app-term-nav'; nav.setAttribute('aria-label', text().region);
        terms.slice().sort(function (a, b) { return a.next - b.next; }).forEach(function (item) {
            var a = document.createElement('a'); a.href = item.link.href; a.className = 'univer-app-term univer-app-term-' + (item.next ? 'next' : 'prev');
            var caption = document.createElement('span'); caption.className = 'univer-app-term-caption'; caption.textContent = item.next ? text().next : text().previous;
            var value = document.createElement('span'); value.className = 'univer-app-term-value';
            // Year and season sit on their own lines: the separator is for screen readers and never ends a line.
            (item.season ? [item.years, ' · ', text()[item.season]] : [item.years]).forEach(function (part, index) {
                var line = document.createElement('span'); line.textContent = part;
                if (index === 1) line.className = 'univer-app-term-separator';
                value.appendChild(line);
            });
            a.append(caption, value); a.setAttribute('aria-label', caption.textContent + ': ' + clean(item.link)); nav.appendChild(a);
        });
        terms[0].link.before(nav);
        terms.forEach(function (item) { item.link.classList.add('univer-app-term-source'); });
        navs.set(cell, {nav:nav, links:terms.map(function (item) { return item.link; }), signature:signature});
    }
    function apply(lang) {
        language = lang;
        navs.forEach(function (record, cell) { if (!cell.isConnected || !record.nav.isConnected) drop(cell); });
        document.querySelectorAll('#l4 td.ts').forEach(function (cell) {
            if (cell.closest('#tools')) return;
            var terms = Array.from(cell.children).filter(function (a) { return a.tagName === 'A'; }).map(term).filter(Boolean);
            // At most one previous and one next semester; anything else is not the observed switch.
            if (!terms.length || terms.length > 2 || (terms.length === 2 && terms[0].next === terms[1].next)) { drop(cell); return; }
            var signature = language + '|' + terms.map(function (item) { return item.link.href + '|' + clean(item.link); }).join('|');
            if (navs.get(cell)?.signature === signature) return;
            drop(cell); render(cell, terms, signature);
        });
    }
    function release() { Array.from(navs.keys()).forEach(drop); }
    window.__univerTermsV1 = {apply:apply, release:release};
})();
