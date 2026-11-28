/* Read-only links from attendance to the observed UMKD course list and its syllabi. */
(function () {
    'use strict';
    if (location.origin !== 'https://univer.kaznu.kz' || window.__univerStudyV1) return;
    var dom = window.__univerDomV1, clean = dom.clean;
    var enabled = false, language = 'ru', generation = 0, courses = null, pending = null, attempted = false, owner = '', style;
    var changed = new Map(), headings = new Map(), panels = new Map(), controllers = new Set(), materials = new Map();
    var warmed = new Set(), warming = false;
    var COURSE = /^\/student\/umkd\/\d+\/\d{4}\/[12]\/?$/;
    function syllabus() { return window.__univerSyllabusV1; }
    function account() { return syllabus()?.account() || ''; }
    function fold(value) { return value.normalize('NFC').toLocaleLowerCase().replace(/ё/g,'е'); }
    function text() {
        return language === 'en' ? {loading:'Loading syllabi…', empty:'No syllabi have been uploaded for this course.', failed:'Could not load the syllabi. Try again or open the course materials.', retry:'Retry', umkd:'Open course materials', print:'Interactive syllabus', printType:'Print version', file:'File'} :
            language === 'kz' ? {loading:'Силлабустар жүктелуде…', empty:'Бұл пәнге силлабустар жүктелмеген.', failed:'Силлабустарды жүктеу мүмкін болмады. Қайталаңыз немесе оқу материалдарын ашыңыз.', retry:'Қайталау', umkd:'Оқу материалдарын ашу', print:'Интерактивті силлабус', printType:'Баспа нұсқасы', file:'Файл'} :
            {loading:'Загрузка силлабусов…', empty:'Для этого предмета силлабусы не загружены.', failed:'Не удалось загрузить силлабусы. Повторите или откройте УМКД.', retry:'Повторить', umkd:'Открыть УМКД предмета', print:'Интерактивный силлабус', printType:'Печатная версия', file:'Файл'};
    }
    function semester() {
        var path = location.pathname.match(/^\/student\/attendance\/full\/(\d{4})\/([12])\/?$/) ||
            location.pathname.match(/^\/student\/attendance\/show\/\d+\/(\d{4})\/([12])\/?$/);
        if (path) return '/' + path[1] + '/' + path[2];
        var next = document.querySelector('#l4 .ts a.rt[href*="/attendance/full/"]');
        var match = next?.getAttribute('href').match(/\/attendance\/full\/(\d{4})\/([12])\/?$/);
        return match ? '/' + (+match[1] - (match[2] === '1' ? 1 : 0)) + '/' + (match[2] === '1' ? '2' : '1') : '';
    }
    function listHref() { var term = semester(); return '/student/umkd/' + (term ? '0' + term : ''); }
    async function read(url) {
        var abort = new AbortController(), timeout = setTimeout(() => abort.abort(),10000); controllers.add(abort);
        try {
            var response = await fetch(url,{credentials:'same-origin',cache:'no-store',redirect:'error',signal:abort.signal});
            var final = new URL(response.url,location.origin), expected = new URL(url,location.origin);
            if (response.status !== 200 || final.origin !== location.origin || final.pathname.replace(/\/$/,'') !== expected.pathname.replace(/\/$/,'') || +response.headers?.get('content-length') > 4*1024*1024) throw new Error('Unexpected UMKD response');
            var html = await dom.boundedText(response, 4 * 1024 * 1024);
            var doc = new DOMParser().parseFromString(html,'text/html');
            if (doc.querySelector('#login_frm')) throw new Error('Session expired');
            return doc;
        } finally { clearTimeout(timeout); abort.abort(); controllers.delete(abort); }
    }
    function current(epoch, who) { return enabled && generation === epoch && who === account(); }
    async function loadCourses() {
        if (courses) return courses;
        if (pending) return pending;
        var epoch = generation, who = owner, lang = language, address = listHref(); attempted = true;
        var saved = syllabus()?.journal(address, lang, who);
        if (saved?.length && saved.every(course => typeof course?.name === 'string' && COURSE.test(course.href)) &&
            Array.from(headings.values()).every(record => courseOf(record, saved))) {
            courses = saved; connectCourses(); return courses;
        }
        pending = (async function () {
            var doc = await read(address);
            // The observed dblclick handler is /student/umkd/ + this.id + /year/term.
            // Parse only that route; never execute a fetched site's scripts.
            var term = semester() || Array.from(doc.scripts).map(s=>s.textContent).join('\n').match(/["']\/student\/umkd\/["']\s*\+\s*this\.id\s*\+\s*["'](\/\d{4}\/[12])["']/)?.[1];
            if (!term) throw new Error('Course semester unavailable');
            var found = Array.from(doc.querySelectorAll('#l4 table.inner tr.link[id]')).filter(r=>/^\d+$/.test(r.id) && r.cells.length===3)
                .map(r=>({name:clean(r.cells[1]),href:'/student/umkd/'+r.id+term,id:r.id}));
            if (!found.length) throw new Error('Course list unavailable');
            if (current(epoch, who)) { syllabus()?.journal(address, lang, who, found); courses=found; connectCourses(); }
            return found;
        })();
        try { return await pending; } finally { if (generation===epoch) pending=null; }
    }
    function remember(link) {
        if (!changed.has(link)) changed.set(link,['href','role','aria-controls','aria-expanded','hidden','tabindex','aria-hidden','data-univer-syllabus-source'].map(name=>[name,link.getAttribute(name)]));
    }
    function courseOf(record, list) {
        var name = fold(clean(record.name));
        return list?.find(course => name === fold(course.name));
    }
    function connectCourses() {
        if (!enabled) return;
        document.querySelectorAll('#l4 .ct').forEach(cell=>{
            if (cell.closest('#tools,.univer-app-attendance-summary')) return;
            var record=headings.get(cell);
            if (!record) {
                var heading=cell;
                if (cell.querySelector('a.syllabus-info')) {
                    heading=cell.cloneNode(true);
                    heading.querySelectorAll('a.syllabus-info').forEach(link=>link.remove());
                }
                var parts=clean(heading).match(/^(.*?)\s*(\(\[.*?)?\s*(\d{2}\.\d{2}\.\d{4}\s*[-–]\s*\d{2}\.\d{2}\.\d{4})$/);
                if (!parts || cell.querySelector('form,input,select,button')) return;
                var original=document.createElement('span'); original.className='univer-app-course-source'; original.hidden=true; original.append(...cell.childNodes);
                var header=document.createElement('div'); header.className='univer-app-course-heading';
                var title=document.createElement('div'); title.className='univer-app-course-title';
                var name=document.createElement('span'); name.className='univer-app-course-name'; name.textContent=parts[1]; title.appendChild(name);
                var meta=document.createElement('div'); meta.className='univer-app-course-meta';
                [parts[3],parts[2]].filter(Boolean).forEach(value=>{var line=document.createElement('span');line.textContent=value;meta.appendChild(line);});title.appendChild(meta);
                var button=document.createElement('button');button.type='button';button.className='univer-app-course-syllabus';button.setAttribute('aria-expanded','false');
                header.append(title,button);cell.append(original,header);
                record={original,header,title,name,button,course:null,link:null};headings.set(cell,record);
                button.addEventListener('click',event=>{event.stopPropagation();show(button,false,event);});
            }
            if (record.button.textContent!==text().print) record.button.textContent=text().print;
            var course = courseOf(record, courses);
            if (course && !record.link) {
                record.course=course;
                var link=anchor(course.href,course.name);link.className='univer-app-attendance-course';link.title=text().umkd;
                record.name.replaceWith(link);record.link=link;
            }
            if (record.link && record.link.title !== text().umkd) record.link.title = text().umkd;
        });
        document.querySelectorAll('#l4 a.syllabus-info').forEach(link=>{
            if (!headings.size) return;
            if (!link.hidden || link.getAttribute('data-univer-syllabus-source') !== 'true') {
                remember(link);link.hidden=true;link.tabIndex=-1;link.setAttribute('aria-hidden','true');link.setAttribute('data-univer-syllabus-source','true');
            }
        });
    }
    function courseFor(link) {
        var found=null;
        headings.forEach((record,cell)=>{ if (record.button===link || cell.compareDocumentPosition(link)&Node.DOCUMENT_POSITION_FOLLOWING) found=record.course; });
        return found;
    }
    function headingFor(button) { return Array.from(headings.values()).find(record=>record.button===button); }
    function anchor(href,title) { var a=document.createElement('a'); a.href=href; a.textContent=title; return a; }
    function materialItem(href, name) {
        var url; try { url = new URL(href, location.origin); } catch (_) { return; }
        if (url.origin !== location.origin || url.search || url.hash) return;
        var api = syllabus();
        var path = api?.printPath ? api.printPath(url.href) : /^\/student\/interactive\/syllabus\/print\/\d+\/\d+\/?$/.test(url.pathname) ? url.pathname.replace(/\/$/, '') : '';
        var print = !!path;
        var file = /^\/student\/umkd\/get\/\d+\/\d+\/\d{4}\/[12]\/?$/.test(url.pathname) && /силлаб|syllab/i.test(name);
        if (print || file) return {href:url.href, name:name, print:print, path:path};
    }
    function loadItems(course, force, background) {
        var href = course.href, who = owner, lang = language, epoch = generation;
        if (!COURSE.test(href)) return Promise.reject(new Error('Course materials unavailable'));
        if (!force && materials.has(href)) return materials.get(href);
        var saved = background && syllabus()?.journal(href, lang, who);
        if (saved) return Promise.resolve(saved.map(item => typeof item?.name === 'string' && materialItem(item.href, item.name)).filter(Boolean));
        var request = (async function () {
            var doc = await read(href), box = doc.getElementById('l4'), seen = new Set(), items = [];
            if (!box || !current(epoch, who)) throw new Error('Course materials unavailable');
            box.querySelectorAll('a[href]').forEach(link => {
                var item = materialItem(link.getAttribute('href'), clean(link) || course.name), key = item?.path || item?.href;
                if (!item || seen.has(key)) return;
                seen.add(key); items.push(item);
            });
            syllabus()?.journal(href, lang, who, items);
            return items;
        })();
        materials.set(href, request);
        request.catch(() => { if (materials.get(href) === request) materials.delete(href); });
        return request;
    }
    function printed(items) { return items.filter(item => item.print).map(item => item.href); }
    async function warmOwn(api, epoch, who) {
        try {
            await loadCourses();
            var queue = new Set(Array.from(headings.values(), heading => heading.course).filter(Boolean));
            for (var course of queue) {
                if (!current(epoch, who) || warmed.size >= 20) return;
                if (warmed.has(course.href)) continue;
                warmed.add(course.href);
                try {
                    var hrefs = printed(await loadItems(course, false, true));
                    if (current(epoch, who) && hrefs.length && api.peekOwn(hrefs, course.href) === undefined) await api.own(hrefs, course.href);
                } catch (_) { }
            }
        } catch (_) { }
        finally { if (generation === epoch) warming = false; }
    }
    function markOwn(record, epoch, who) {
        var api = syllabus(), hrefs = printed(record.items), list = record.list;
        if (!api?.peekOwn || !hrefs.length) return;
        var finish = path => {
            if (!current(epoch, who) || record.list !== list || !list.isConnected) return;
            record.ownStatus?.remove(); record.ownStatus = null;
            var item = record.items.find(item => item.path === path);
            if (!item) return;
            var tag = record.tag = document.createElement('span');
            tag.className = 'univer-app-own-tag'; tag.id = record.panel.id + '-own';
            tag.textContent = api.ownText(language).tag;
            item.link.appendChild(tag);
            item.link.setAttribute('aria-describedby', tag.id);
            item.li.classList.add('univer-app-syllabus-own');
            record.order.move(() => { if (list.firstElementChild !== item.li) list.insertBefore(item.li, list.firstChild); });
        };
        var cached = api.peekOwn(hrefs, record.course);
        if (cached !== undefined) { finish(cached); return; }
        var status = record.ownStatus = document.createElement('p');
        status.className = 'univer-app-own-status'; status.setAttribute('role', 'status');
        status.textContent = api.ownText(language).pending;
        list.before(status);
        record.ownAttempts++;
        api.own(hrefs, record.course).then(finish, () => {
            finish(null);
            if (current(epoch, who) && record.list === list) record.ownFailed = true;
        });
    }
    async function show(link, force, event) {
        var record=panels.get(link);
        var motion=window.__univerMotionUI;
        var animate=event?.detail>0 && !document.documentElement.classList.contains('univer-app-still') && !motion?.reduced?.();
        if (record && !force) {
            var opening=record.panel.hidden;
            if (!opening && animate) motion?.exit(record.panel); else motion?.stop(record.panel);
            if (opening && record.ownFailed && record.ownAttempts < 2) {
                record.ownFailed=false; markOwn(record,generation,owner);
            }
            if (!opening && record.panel.contains(document.activeElement)) link.focus();
            record.panel.hidden=!opening; link.setAttribute('aria-expanded',String(opening));
            if (!opening) record.order?.flush();
            if (opening && animate) { motion?.resize(record.panel,1,220); motion?.reveal(record.panel,'content'); }
            return;
        }
        if (!record) {
            var panel=document.createElement('section'); panel.className='univer-app-syllabi'; panel.id='univer-syllabi-'+panels.size; panel.setAttribute('aria-label',text().print);
            (headingFor(link)?.header || link.closest('.ts') || link).after(panel); record={panel,items:null}; panels.set(link,record);
            remember(link); link.setAttribute('aria-controls',panel.id);
        }
        var epoch=generation, who=owner, panel=record.panel, request={}; record.request=request;
        var valid=()=>current(epoch, who) && record.request===request && panel.isConnected;
        record.order?.release(); record.order=null; record.list=null; record.tag=null; record.ownStatus=null; record.ownAttempts=0; record.ownFailed=false;
        record.outcome='loading'; record.retry=null; record.footer=null;
        if (panel.contains(document.activeElement)) link.focus();
        panel.hidden=false; panel.textContent=''; link.setAttribute('aria-expanded','true');
        var status=document.createElement('p'); status.setAttribute('role','status'); status.className='univer-app-pending'; status.textContent=text().loading; panel.appendChild(status);
        record.status=status;
        if (animate) motion?.reveal(panel,'content');
        var course, items;
        try {
            await loadCourses(); if (!valid()) return;
            course=courseFor(link); if (!course) throw new Error('Course not found');
            items=(await loadItems(course, force, false)).map(item=>({...item}));
        } catch (_) { items=null; }
        if (!valid()) return;
        motion?.stop(panel);
        record.items=items; record.course=course?.href;
        if (!items?.length) {
            record.outcome=items ? 'empty' : 'failed';
            status.className=''; status.textContent=text()[record.outcome];
        } else {
            record.outcome=''; record.status=null; status.remove();
            var list=record.list=document.createElement('ul');
            list.setAttribute('role','list');
            items.forEach((item,index)=>{
                var li=item.li=document.createElement('li'), name=document.createElement('span'), type=item.type=document.createElement('span');
                name.className='univer-app-syllabus-title'; name.id=panel.id+'-title-'+index; name.textContent=item.name;
                type.className='univer-app-syllabus-type'; type.textContent=item.print?text().printType:text().file;
                item.link=anchor(item.href,''); item.link.setAttribute('aria-labelledby',name.id);
                item.link.append(name,type); li.appendChild(item.link); list.appendChild(li);
            });
            panel.appendChild(list);
            if (syllabus()?.stableOrder) {
                record.order=syllabus().stableOrder(list,()=>panel.hidden);
                markOwn(record,epoch,who);
            }
        }
        var actions=document.createElement('div'); actions.className='univer-app-syllabus-actions';
        if (!items) {
            var retry=document.createElement('button'); retry.type='button'; retry.textContent=text().retry; retry.addEventListener('click',event=>show(link,true,event));
            record.retry=retry; actions.appendChild(retry);
        }
        record.footer=anchor(course?.href||listHref(),text().umkd);
        actions.appendChild(record.footer); panel.appendChild(actions);
    }
    document.addEventListener('click',event=>{
        if (!enabled || event.button!==0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        var link=event.target.closest?.('#l4 a.syllabus-info');
        if (link) { event.preventDefault(); event.stopImmediatePropagation(); if(!link.hidden)show(link,false,event); }
        else if (event.target.closest?.('.univer-app-attendance-course')) event.stopPropagation();
    },true);
    document.addEventListener('keydown',event=>{
        if (enabled && event.key===' ' && event.target.matches?.('#l4 a.syllabus-info') && !event.target.hidden) {event.preventDefault();event.target.click();}
    });
    function mountStyle() {
        if (style?.isConnected) return;
        style=document.createElement('style'); style.id='univer-journal-syllabus-style';
        var css=[
            'html:root:root:root:root.univer-mobile #l4 button.univer-app-course-syllabus { min-height:44px!important; height:auto!important; max-width:100%; white-space:normal; }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabi { min-width:0; padding:8px 12px!important; background:var(--u-surface)!important; border:1px solid var(--u-line)!important; }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabi ul { margin:0 0 4px!important; }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabi li { display:block; padding:0; border-bottom:1px solid var(--u-line); }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabi li a { display:flex; flex-direction:column; align-items:flex-start; gap:3px; box-sizing:border-box; min-height:44px; padding:9px 8px; line-height:1.35; white-space:normal; overflow-wrap:break-word; overflow-wrap:anywhere; text-align:start; }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabi .univer-app-syllabus-title { color:inherit; font-size:inherit; }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabi .univer-app-syllabus-own a { border-inline-start:3px solid var(--u-brand); padding-inline-start:9px; border-radius:4px; background:var(--u-brand-soft); }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabi .univer-app-syllabus-own .univer-app-own-tag { order:-1; background:var(--u-surface); color:var(--u-brand)!important; border-radius:4px; font-size:12px; font-weight:600; line-height:1.4; padding:2px 6px; }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabi .univer-app-own-status { margin:0; padding:8px; color:var(--u-muted)!important; font-size:13px; line-height:1.4; }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabus-actions { display:flex; flex-wrap:wrap; align-items:center; gap:4px 10px; }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabus-actions a { display:inline-flex; align-items:center; box-sizing:border-box; min-height:44px; max-width:100%; padding:6px 8px; white-space:normal; }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabus-actions button { margin:0; }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabi a:focus, html:root:root.univer-mobile #l4 .univer-app-syllabi button:focus, html:root:root.univer-mobile #l4 button.univer-app-course-syllabus:focus { outline:2px solid var(--u-brand); outline-offset:-2px; }',
            'html:root:root.univer-mobile #l4 .univer-app-syllabi a:active { background:var(--u-brand-soft); }'
        ].join('\n');
        if (window.__univerPaletteV1) window.__univerPaletteV1.fill(style,css); else style.textContent=css;
        (document.head || document.documentElement).appendChild(style);
    }
    function apply(lang) {
        language=lang;
        if (document.readyState==='loading') return;
        if (!/^\/student\/attendance\/(full|show)(\/|$)/.test(location.pathname)) return;
        var who=account();
        if (enabled && who!==owner) release();
        enabled=true; owner=who;
        mountStyle();
        connectCourses();
        if (!courses && !pending && !attempted) loadCourses().catch(()=>{});
        var api=syllabus(), epoch=generation, labels=text();
        if (!warming && api?.peekOwn && (pending || Array.from(headings.values()).some(record=>record.course && !warmed.has(record.course.href))) && warmed.size<20) {
            warming=true; warmOwn(api,epoch,who);
        }
        panels.forEach(record=>{
            dom.attr(record.panel,'aria-label',labels.print);
            if (record.status) dom.setText(record.status,labels[record.outcome]);
            if (record.retry) dom.setText(record.retry,labels.retry);
            if (record.footer) dom.setText(record.footer,labels.umkd);
            if (record.tag) dom.setText(record.tag,api.ownText(language).tag);
            if (record.ownStatus) dom.setText(record.ownStatus,api.ownText(language).pending);
            record.items?.forEach(item=>dom.setText(item.type,item.print?labels.printType:labels.file));
        });
    }
    function release() {
        enabled=false; generation++; controllers.forEach(abort=>abort.abort()); controllers.clear();
        courses=pending=null; attempted=warming=false; materials.clear(); warmed.clear();
        panels.forEach(record=>{record.order?.release();window.__univerMotionUI?.stop(record.panel);record.panel.remove();}); panels.clear();
        headings.forEach(record=>{record.header.remove();record.original.replaceWith(...record.original.childNodes);});headings.clear();
        changed.forEach((attrs,link)=>attrs.forEach(a=>{ if (a[1]===null) link.removeAttribute(a[0]); else link.setAttribute(a[0],a[1]); })); changed.clear();
        style?.remove(); style=null;
    }
    window.__univerStudyV1={apply,release};
})();
