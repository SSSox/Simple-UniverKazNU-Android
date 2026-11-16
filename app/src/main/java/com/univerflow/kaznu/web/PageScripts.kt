package com.univerflow.kaznu.web

/** Read-only scripts the main screen runs on Univer pages; each first checks that it is on the trusted origin. */
object PageScripts {
    /** Only names beside the exact file link, never form values or the rest of the student's page. */
    fun materialPlace(page: String, target: String) = """(function(){
        if(top!==self||location.origin!==${jsString(SitePolicy.ORIGIN)}||location.href!==${jsString(page)}||
            !/^\/student\/umkd\/\d+\/\d{4}\/[12]\/?$/.test(location.pathname))return null;
        var target=${jsString(target)},link=Array.from(document.querySelectorAll('#l4 a.downLoad[href]')).find(a=>a.href===target);
        if(!link)return null;
        var section='';for(var row=link.closest('tr')?.previousElementSibling;row;row=row.previousElementSibling)
            if(row.cells?.length===1&&row.cells[0].tagName==='TH'){section=row.cells[0].textContent;break;}
        var crumbs=document.querySelectorAll('#l4 tr.top>td.ct a[href*="/student/umkd/"]');
        return JSON.stringify({course:crumbs.length>1?crumbs[crumbs.length-1].textContent:'',section:section});
    })()"""
    /** [materialPlace] for every file of a «Скачать все» in one pass: {address: {course, section}} for the links found. */
    fun materialPlaces(page: String, targets: List<String>) = """(function(){
        if(top!==self||location.origin!==${jsString(SitePolicy.ORIGIN)}||location.href!==${jsString(page)}||
            !/^\/student\/umkd\/\d+\/\d{4}\/[12]\/?$/.test(location.pathname))return null;
        var wanted=[${targets.joinToString(",") { jsString(it) }}],found={};
        var crumbs=document.querySelectorAll('#l4 tr.top>td.ct a[href*="/student/umkd/"]'),course=crumbs.length>1?crumbs[crumbs.length-1].textContent:'';
        Array.from(document.querySelectorAll('#l4 a.downLoad[href]')).forEach(function(link){
            if(wanted.indexOf(link.href)<0||found[link.href])return;
            var section='';for(var row=link.closest('tr')?.previousElementSibling;row;row=row.previousElementSibling)
                if(row.cells?.length===1&&row.cells[0].tagName==='TH'){section=row.cells[0].textContent;break;}
            found[link.href]={course:course,section:section};
        });
        return JSON.stringify(found);
    })()"""
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
