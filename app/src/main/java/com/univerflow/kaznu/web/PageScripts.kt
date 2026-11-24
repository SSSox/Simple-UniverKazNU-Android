package com.univerflow.kaznu.web

/** Read-only scripts the main screen runs on Univer pages; each first checks that it is on the trusted origin. */
object PageScripts {
    /** Only names beside the exact file link, never form values or the rest of the student's page. */
    fun materialPlace(page: String, target: String) = """(function(){
        if(top!==self||location.origin!==${jsString(SitePolicy.ORIGIN)}||location.href!==${jsString(page)}||
            !/^\/student\/umkd\/\d+\/\d{4}\/\d+\/?$/.test(location.pathname))return null;
        var target=${jsString(target)},link=Array.from(document.querySelectorAll('#l4 a.downLoad[href]')).find(a=>a.href===target);
        if(!link)return null;
        var section='';for(var row=link.closest('tr')?.previousElementSibling;row;row=row.previousElementSibling)
            if(row.cells?.length===1&&row.cells[0].tagName==='TH'){section=row.cells[0].textContent;break;}
        var crumbs=document.querySelectorAll('#l4 tr.top>td.ct a[href*="/student/umkd/"]');
        return JSON.stringify({course:crumbs.length>1?crumbs[crumbs.length-1].textContent:'',section:section});
    })()"""
    /**
     * Where the open UMKD page lists [targets], for «Скачать все»: {count, files: {address: {course, section, head,
     * tapped}}} (see [DownloadBatch.places]), read by the keyed channel's lookup (univer-channel.js) with built-ins taken
     * at document start, so a site script that swapped querySelectorAll, a getter or JSON.stringify cannot make up the
     * answer. [mark] is the number the channel gave the tapped heading row; tapped says whether a file's heading is that
     * very row. [senders] is [ChannelGuard.senders]; without the keyed channel there is no lookup and the answer is null.
     */
    fun sectionPlaces(senders: String, page: String, targets: List<String>, mark: Int): String {
        val list = targets.joinToString(",") { jsString(it) }
        // tests/dom/download-all-channel.test.cjs reads these literals: keep them plain and concatenated.
        return "(function(){var s=$senders;return s&&s.keyed===true&&typeof s.places==='function'?" +
            "s.places(${jsString(page)},[$list],$mark):null;})()"
    }
    /**
     * The visible page heading: the current #tools link or the first visible heading
     * (the last breadcrumb link of an adapted heading). No form data or text beyond it is returned.
     */
    val HEADING = """(function(){
        if(location.origin!=='${SitePolicy.ORIGIN}')return null;
        function t(e){return e?(e.textContent||'').replace(/\s+/g,' ').trim():'';}
        if(document.querySelector('#login_frm'))return ['',''];
        var current=document.querySelector('#tools a[aria-current=page]');
        function head(e){if(e.matches('.univer-app-breadcrumb')){var l=e.querySelectorAll('a');return t(l[l.length-1]);}return t(e);}
        // A section's own page (Бакалавр, E-услуги) is named by its tab; the app's summary panels never name the page.
        var here=location.pathname.replace(/\/$/,'');
        var tab=here?Array.from(document.querySelectorAll('#l3 td.center>a[href],.univer-app-tabs>a[href]')).find(function(a){return new URL(a.href,location.href).pathname.replace(/\/$/,'')===here;}):null;
        var heading=Array.from(document.querySelectorAll('#l4 tr.top>td.ct,#l4 h1,#l4 h2,h1')).filter(function(e){return !e.closest('.univer-app-overview')&&(e.getClientRects().length||e.classList.contains('univer-app-native-heading'));})
            .map(head).find(function(x){return x.length>1&&x.length<140;})||'';
        return [(t(current)||t(tab)||heading).slice(0,120)];
    })()"""

    /** Opens the website's full section list (the adapted #tools menu) on the current page; false when it has none. */
    val SECTIONS = """(function(){
        if(location.origin!=='${SitePolicy.ORIGIN}')return false;
        var menu=document.querySelector('.univer-app-menu');
        if(menu){menu.open=true;menu.scrollIntoView({block:'start'});menu.querySelector('summary').focus({preventScroll:true});return true;}
        return false;
    })()"""

    /**
     * Whether a pull may load the page again: "dialog" while a window of the site is open, "edited" once the student
     * typed or chose something in its forms (the app's own controls aside), "ok" otherwise. Only that word returns.
     */
    val REFRESH_GUARD = """(function(){
        if(location.origin!=='${SitePolicy.ORIGIN}')return 'ok';
        function shown(e){if(!e.getClientRects().length)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none';}
        var windows=document.querySelectorAll('#simplemodal-container,.modal.in,.modal.show,dialog[open],[role="dialog"],[aria-modal="true"]');
        for(var i=0;i<windows.length;i++)if(shown(windows[i]))return 'dialog';
        // The app marks its own fields (searches, switches) on the field or its label; the page and the boxes the
        // layout wraps around the site's forms carry univer-app classes too, so a wider match would skip every field.
        function own(f){var p=f.parentElement;return f.matches('[class*="univer-app"]')||!!p&&p.tagName==='LABEL'&&p.matches('[class*="univer-app"]');}
        var fields=document.querySelectorAll('input,textarea,select');
        for(var j=0;j<fields.length;j++){
            var f=fields[j];
            if(f.disabled||f.readOnly||own(f))continue;
            if(f.tagName==='SELECT'){
                var opts=f.options;
                if(f.multiple){for(var k=0;k<opts.length;k++)if(opts[k].selected!==opts[k].defaultSelected)return 'edited';}
                else{
                    var def=-1;
                    for(var k=0;k<opts.length;k++)if(opts[k].defaultSelected)def=k;
                    if(def<0&&!(f.size>1))for(var m=0;m<opts.length;m++){var o=opts[m],p=o.parentElement;if(!o.disabled&&!(p&&p.tagName==='OPTGROUP'&&p.disabled)){def=m;break;}}
                    if(f.selectedIndex!==def)return 'edited';
                }
                continue;
            }
            var type=(f.type||'').toLowerCase();
            if(type==='hidden'||type==='submit'||type==='button'||type==='reset'||type==='image')continue;
            if(type==='checkbox'||type==='radio'){if(f.checked!==f.defaultChecked)return 'edited';continue;}
            if(type==='file'){if(f.files&&f.files.length)return 'edited';continue;}
            if(f.value!==f.defaultValue)return 'edited';
        }
        return 'ok';
    })()"""

    /** The document's HTTP status from Navigation Timing; some providers miss the error callback on redirected error pages. */
    const val STATUS = "(function(){var n=performance.getEntriesByType('navigation')[0];return n&&n.responseStatus||0;})()"

    /**
     * Legacy print pages close themselves with window.close()/history.back(), which have no usable source history in
     * the document window: their "Назад" links go to a marker address the window answers by closing.
     */
    val DOCUMENT_BACK = """(function(){
        if(location.origin!=='${SitePolicy.ORIGIN}')return;
        document.querySelectorAll('a').forEach(function(a){
            if(!/^(назад|back|артқа|кері)\.?$/i.test(a.textContent.trim())||a.__univerDocBack)return;
            a.__univerDocBack=true;
            a.addEventListener('click',function(e){e.preventDefault();e.stopImmediatePropagation();location.href='${SitePolicy.ORIGIN}/__univer_document_back__';},true);
        });
    })();"""

    /** Observed on dl.kaznu.edu.kz: an expired session becomes guest access with a "Log in" link. */
    const val MOODLE_SIGNED_OUT = "(function(){var b=document.body;if(!b)return false;" +
        "if(b.classList.contains('notloggedin'))return true;" +
        "return !!document.querySelector('.usermenu .login a[href*=\"/login/index.php\"],.logininfo a[href*=\"/login/index.php\"]');})()"

    /** Moodle's own sign-in form is on the page (its session ended). */
    const val MOODLE_LOGIN_FORM = "!!document.querySelector('form#login input[type=password]')"
}
