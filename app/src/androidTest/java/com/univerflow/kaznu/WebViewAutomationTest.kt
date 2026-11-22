package com.univerflow.kaznu

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream

/** Real Android WebView, offline DOM fixtures. Never submits test credentials to Univer. */
@RunWith(AndroidJUnit4::class)
class WebViewAutomationTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var web: WebView
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val library get() = instrumentation.targetContext.assets.open("univer-automation.js").bufferedReader().use { it.readText() }
    private val jquery get() = instrumentation.context.assets.open("site-jquery.js").bufferedReader().use { it.readText() }

    @SuppressLint("SetJavaScriptEnabled")
    @Before fun setup() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            web = WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.useWideViewPort = true
            web.settings.loadWithOverviewMode = false
            activity.setContentView(web)
        }
    }
    @After fun close() {
        instrumentation.runOnMainSync { web.destroyFixture() }
        scenario.close()
    }

    private fun load(body: String, scripts: String = "", origin: String = "https://univer.kaznu.kz/user/login") {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
            }
            web.loadDataWithBaseURL(origin, "<html><head><script>$scripts</script></head><body>$body</body></html>", "text/html", "UTF-8", origin)
        }
        assertTrue("Fixture loaded", loaded.await(15, TimeUnit.SECONDS))
        eval(library)
    }
    private fun eval(script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("JavaScript callback", latch.await(10, TimeUnit.SECONDS))
        return result
    }
    // The fixture answers after 2 s, leaving the pending checks independent of emulator speed.
    private fun awaitReauthOutcome(expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (probe().getString("reauthOutcome") != expected && System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals(expected, probe().getString("reauthOutcome"))
    }
    private fun probe() = JSONObject(JSONTokener(eval("JSON.stringify(window.__univerAutomationV1.probe())")).nextValue() as String)
    private fun call(method: String) = eval("window.__univerAutomationV1.$method")
    private fun token() = JSONObject.quote(probe().getString("document"))

    @Test fun dynamicInputsUseRealLegacyJquerySubmitHandlerExactlyOnce() {
        load("""<form id="login_frm" action="/" method="post"><input type="text" id="_random_alpha">
            <input type="password" id="_random_beta"><input name="btn" type="submit" value="Войти в систему"></form>
            <div style="display:none"><input type="password" id="decoy"></div>""", jquery + """
            window.submissions=0; window.captured=[];
            $(document).ready(function(){ $('#login_frm').submit(function(){
              window.submissions++; window.captured=[$('#_random_alpha').attr('value'),$('#_random_beta').attr('value')]; return false;
            });});""")
        assertTrue(probe().getBoolean("loginReady"))
        val doc = token()
        call("login($doc, 'fixture-login', 'fixture-password')")
        call("login($doc, 'second', 'second')")
        assertEquals("1", eval("window.submissions"))
        assertEquals("[\"fixture-login\",\"fixture-password\"]", eval("window.captured"))
        assertEquals("\"\"", eval("document.getElementById('decoy').value"))
    }
    @Test fun lateLoginElementsAreDetectedWithoutReload() {
        load("<div id='target'></div>", jquery)
        assertFalse(probe().getBoolean("loginReady"))
        eval("setTimeout(function(){document.getElementById('target').innerHTML='<form id=login_frm action=/><input type=text><input type=password><input name=btn type=submit></form>';},100)")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var ready = false
        while (!ready && System.nanoTime() < deadline) { Thread.sleep(100); ready = probe().getBoolean("loginReady") }
        assertTrue(ready)
    }
    @Test fun noticeClickRunsCalcThenSubmitsOnceWithHiddenFieldsIntact() {
        load("""<form id="attentionSt" method="post" action="/student/attentionStudents/42">
            <input name="attentions[]" value="-1" hidden><p>Semester notice fixture</p>
            <input type="checkbox" id="check" onclick="calc();"><label for="check">«Мен бұл хабарламаны оқығанымды растаймын» / «I confirm that I have read this notice» / «Я подтверждаю, что прочел данное уведомление»</label>
            <input id="btn" class="att" type="submit" value="Жiберу/Submit/Отправить" disabled></form>""",
            """window.submissions=0; function calc(){document.getElementById('btn').disabled=!document.getElementById('check').checked;}
            window.onload=function(){document.getElementById('attentionSt').onsubmit=function(e){e.preventDefault();window.submissions++;};};""",
            "https://univer.kaznu.kz/student/attentionStudents/42")
        assertTrue(probe().getBoolean("notice"))
        assertFalse(probe().getBoolean("submitEnabled"))
        val doc = token()
        call("submitNotice($doc)")
        assertEquals("0", eval("window.submissions"))
        call("checkNotice($doc)")
        assertTrue(probe().getBoolean("checked"))
        assertTrue(probe().getBoolean("submitEnabled"))
        call("submitNotice($doc)"); call("submitNotice($doc)")
        assertEquals("1", eval("window.submissions"))
        assertEquals("\"-1\"", eval("document.querySelector('[name=\"attentions[]\"]').value"))
    }
    @Test fun asynchronousNoticeButtonWaitsUntilEnabled() {
        load("""<form id="attentionSt" action="/student/attentionStudents/42"><input id="check" type="checkbox" onclick="calc()">
            <label for="check">I confirm that I have read this notice</label><input id="btn" type="submit" disabled></form>""",
            "function calc(){setTimeout(function(){document.getElementById('btn').disabled=false;},2000);}",
            "https://univer.kaznu.kz/student/attentionStudents/42")
        val doc = token()
        call("checkNotice($doc)")
        assertFalse(probe().getBoolean("submitEnabled"))
        assertEquals("\"wait\"", call("submitNotice($doc)"))
        // The fixture enables the button after 2 s so a slow emulator cannot outrun the "wait" checks.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!probe().getBoolean("submitEnabled") && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue(probe().getBoolean("submitEnabled"))
    }
    @Test fun unrelatedConfirmationAndExternalFormAreIgnored() {
        load("""<form id="attentionSt" action="/student/attentionStudents/42"><input id="check" type="checkbox">
            <label for="check">I confirm my exam choice</label><input id="btn" type="submit"></form>""",
            origin = "https://univer.kaznu.kz/student/attentionStudents/42")
        assertFalse(probe().getBoolean("notice"))
        eval("document.querySelector('label').textContent='I confirm that I have read this notice';document.querySelector('form').action='https://evil.test/student/attentionStudents/42'")
        assertFalse(probe().getBoolean("notice"))
    }
    @Test fun noticePreparationReturnsReadyAfterCalcWithoutSubmittingOrDoubleClicking() {
        load("""<form id=attentionSt action=/student/attentionStudents/42><input id=check type=checkbox onclick=calc()>
            <label for=check>I confirm that I have read this notice</label><input id=btn type=submit disabled>
            <input name="attentions[]" value="-1" hidden></form>""",
            """window.checks=0;window.posts=0;function calc(){window.checks++;document.getElementById('btn').disabled=false;}
            window.onload=function(){document.getElementById('attentionSt').onsubmit=function(e){e.preventDefault();window.posts++;};};""",
            "https://univer.kaznu.kz/student/attentionStudents/42")
        val doc = token()
        val appearance = JSONObject.quote(probe().getString("noticeEpisode"))
        assertEquals("\"ready\"", call("prepareNotice($doc,$appearance)"))
        assertEquals("\"ready\"", call("prepareNotice($doc,$appearance)"))
        assertEquals("1", eval("window.checks"))
        assertEquals("0", eval("window.posts"))
        call("submitNotice($doc,$appearance)"); call("submitNotice($doc,$appearance)")
        assertEquals("1", eval("window.posts"))
        assertEquals("\"-1\"", eval("document.querySelector('[name=\"attentions[]\"]').value"))
    }
    @Test fun noticePreparationWaitsForActualButtonEnableAndRejectsWrongEpisode() {
        load("""<form id=attentionSt action=/student/attentionStudents/42><input id=check type=checkbox onclick=calc()>
            <label for=check>I confirm that I have read this notice</label><input id=btn type=submit disabled></form>""",
            "window.checks=0;function calc(){window.checks++;}", "https://univer.kaznu.kz/student/attentionStudents/42")
        val doc=token(); val appearance=JSONObject.quote(probe().getString("noticeEpisode"))
        assertEquals("\"wait\"",call("prepareNotice($doc,'wrong-episode')"))
        assertEquals("0",eval("window.checks"))
        assertEquals("\"wait\"",call("prepareNotice($doc,$appearance)"))
        assertEquals("\"wait\"",call("prepareNotice($doc,$appearance)"))
        eval("document.getElementById('btn').disabled=false")
        assertEquals("\"ready\"",call("prepareNotice($doc,$appearance)"))
        assertEquals("1",eval("window.checks"))
    }
    @Test fun flagsUseRealChangeEndpointAndNoticeHtmlLangDoesNotCauseLoop() {
        load("""<div class="header"><img src="/content/icons/kz.png"><a href="/lang/change/ru/">Русский</a><a href="/lang/change/en/">English</a></div>""",
            "window.switches=0;window.onload=function(){document.querySelector('a').onclick=function(e){e.preventDefault();window.switches++;};};")
        assertEquals("kz", probe().getString("language"))
        call("switchLanguage('ru')")
        assertEquals("1", eval("window.switches"))
        eval("document.querySelector('.header').remove();document.documentElement.lang='kz'")
        assertEquals("", probe().getString("language"))
    }
    @Test fun untrustedOriginHasNoAutomationObject() {
        load("<p>External page</p>", origin = "https://univer.kaznu.kz.evil.test/user/login")
        assertEquals("\"undefined\"", eval("typeof window.__univerAutomationV1"))
    }

    private fun mobile(enabled: Boolean = true) {
        val script = com.univerflow.kaznu.web.MobilePageAdapter.SCRIPTS.joinToString("\n") { name ->
            instrumentation.targetContext.assets.open(name).bufferedReader().use { it.readText() } }
        eval(script + "\nwindow.__univerMobileV1?.setEnabled($enabled,'ru');")
    }

    @Test fun mobileLoginStacksColumnsAndRetainsLegacyFormHandler() {
        load("""<table style="width:1000px"><tr id="l1"><td>Header</td></tr><tr id="l4"><td>
            <form id="login_frm"><table class="cols"><tr><td><div id="login_form"><input type="text" class="text" id="u">
            <input type="password" class="text" id="p"><input name="btn" type="submit"></div></td>
            <td>Help text</td></tr></table></form></td></tr></table>""", jquery + """
            window.submissions=0;$(document).ready(function(){$('#login_frm').submit(function(){window.submissions++;return false;});});""")
        mobile()
        assertEquals("\"block\"", eval("getComputedStyle(document.querySelector('.cols td')).display"))
        assertEquals("\"16px\"", eval("getComputedStyle(document.getElementById('u')).fontSize"))
        assertEquals("true", eval("document.querySelector('.univer-app-shell').getBoundingClientRect().width<=document.documentElement.clientWidth+1"))
        call("login(${token()},'fixture','fixture-password')")
        assertEquals("1", eval("window.submissions"))
        mobile(false)
        assertEquals("\"table-cell\"", eval("getComputedStyle(document.querySelector('.cols td')).display"))
        assertEquals("0", eval("document.querySelectorAll('.univer-app-table-scroll,.univer-app-menu').length"))
    }

    @Test fun mobileTablesScrollLocallyAndMenuPreservesLinksAndLateTables() {
        load("""<table class="wrappertable" style="width:1000px"><tr id="l1"><td>Header</td></tr>
            <tr id="l4"><td><table id="tools"><tr><td><table class="tool"><tr><td><a href="/student/umkd/">УМКД</a></td></tr></table></td></tr></table>
            <table class="mt"><tr><td>it@kaznu.kz wifi-support@kaznu.kz help@kaznu.kz</td></tr></table>
            <table id="schedule" style="width:1200px"><tr><th>Day</th><th>Course</th></tr><tr><td>Monday</td><td>Fixture</td></tr></table><div id="late"></div></td></tr></table>""")
        mobile()
        assertEquals("false", eval("document.querySelector('details').open"))
        assertEquals("false", eval("document.querySelector('.univer-app-info').open"))
        assertEquals("\"/student/umkd/\"", eval("document.querySelector('#tools a').getAttribute('href')"))
        assertEquals("true", eval("(function(){var box=document.getElementById('schedule').parentElement;return box.classList.contains('univer-app-table-scroll')&&box.scrollWidth>box.clientWidth;})()"))
        assertEquals("\"table-cell\"", eval("getComputedStyle(document.querySelector('#schedule td')).display"))
        eval("document.getElementById('late').innerHTML='<table id=lateTable><tr><td>Late data</td></tr></table>'")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var adapted = false
        while (!adapted && System.nanoTime() < deadline) {
            Thread.sleep(100)
            adapted = eval("document.getElementById('lateTable').parentElement.classList.contains('univer-app-table-scroll')") == "true"
        }
        assertTrue(adapted)
        mobile(false)
        assertEquals("true", eval("!!document.querySelector('#tools a') && document.querySelectorAll('.univer-app-table-scroll,.univer-app-menu,.univer-app-info').length===0"))
    }

    @Test fun mobileNoticeFitsPhoneAndStillCallsCalc() {
        load("""<form id="attentionSt" action="/student/attentionStudents/42"><div class="parent" style="position:absolute;overflow:auto">
            <div class="block" style="width:800px;position:absolute;left:40%;top:40%;margin:-300px 0 0 -300px">
            <p>Notice fixture</p><input type="checkbox" id="check" onclick="calc()"><label for="check">I confirm that I have read this notice</label>
            <input id="btn" type="submit" disabled></div></div></form>""",
            "function calc(){document.getElementById('btn').disabled=false;}", "https://univer.kaznu.kz/student/attentionStudents/42")
        mobile()
        assertEquals("\"static\"", eval("getComputedStyle(document.querySelector('.block')).position"))
        assertEquals("true", eval("document.querySelector('.block').getBoundingClientRect().right<=document.documentElement.clientWidth+1"))
        call("checkNotice(${token()})")
        assertTrue(probe().getBoolean("checked"))
        assertTrue(probe().getBoolean("submitEnabled"))
    }

    private val reauthFixture = """<div id="auth_modal" style="display:none"><form id="modal_auth_frm" method="post" action="#">
        <table><tr><td></td><td>Введите ваш логин и пароль (№ <b id="auth_try">1</b>)</td><td></td></tr>
        <tr><td></td><td><label>Логин</label><input readonly name="login" id="modal_auth_login" type="text" value="fixture-user">
        <label>Пароль</label><input name="password" id="modal_auth_pass" type="password"><input id="modal_auth_ajl_btn" type="submit" value="Войти">
        <input type="hidden" name="key" id="modal_auth_key" value="fixture-nonce"><input type="hidden" name="cheat" id="cheat_auth" value="0"></td><td></td></tr></table></form></div>"""

    private val reauthScript = jquery + """
        window.authPosts=0;window.authCaptured=false;window.authFails=false;
        function ajl(){
            window.authPosts++;
            window.authCaptured=$('#modal_auth_login').attr('value')==='fixture-user' &&
                $('#modal_auth_pass').attr('value')==='fixture-password' &&
                $('#modal_auth_key').attr('value')==='fixture-nonce' && $('#cheat_auth').attr('value')==='0';
            $('#auth_modal').hide();
            setTimeout(function(){
                if(window.authFails){$('#auth_try').html('2');$('#auth_modal').show();}
                $(document).trigger('ajaxSuccess',[{responseText:JSON.stringify({code:window.authFails?0:1,obj:'fixture-updated-key'})},{url:'/user/ajl',type:'POST'}]);
                $('#modal_auth_pass').attr('value','');
            },2000);
        }
        $(document).ready(function(){$('#modal_auth_frm').submit(function(){ajl();return false;});});
    """

    @Test fun lateAjaxReauthenticationPreservesReadonlyAndHiddenFieldsAndWaitsForResult() {
        load("<p>Dashboard fixture</p>$reauthFixture", reauthScript, "https://univer.kaznu.kz/student")
        assertFalse(probe().getBoolean("reauth"))
        eval("document.getElementById('auth_modal').style.display='block'")
        val p = probe()
        assertTrue(p.getBoolean("reauthReady"))
        val args = "${token()},${JSONObject.quote(p.getString("reauthChallenge"))},'fixture-user','fixture-password'"
        assertEquals("\"submitted\"", call("reauthenticate($args)"))
        assertEquals("\"wait\"", call("reauthenticate($args)"))
        assertEquals("true", eval("window.authCaptured"))
        assertEquals("true", eval("document.getElementById('modal_auth_login').readOnly"))
        assertFalse(probe().getBoolean("reauth"))
        assertEquals("pending", probe().getString("reauthOutcome"))
        awaitReauthOutcome("success")
        assertEquals("1", eval("window.authPosts"))
        eval("document.getElementById('auth_modal').style.display='block'")
        val later = probe()
        assertNotEquals(p.getString("reauthChallenge"), later.getString("reauthChallenge"))
        call("reauthenticate(${token()},${JSONObject.quote(later.getString("reauthChallenge"))},'fixture-user','fixture-password')")
        assertEquals("2", eval("window.authPosts"))
    }

    @Test fun reauthenticationStopsWhenReadonlyLoginNamesAnotherAccount() {
        load("<p>Dashboard fixture</p>${reauthFixture.replace("value=\"fixture-user\"", "value=\"fixture-other\"")}", reauthScript,
            "https://univer.kaznu.kz/student")
        eval("document.getElementById('auth_modal').style.display='block'")
        val args = "${token()},${JSONObject.quote(probe().getString("reauthChallenge"))},'fixture-user','fixture-password'"
        assertEquals("\"account-mismatch\"", call("reauthenticate($args)"))
        assertEquals("0", eval("window.authPosts"))
        assertEquals("\"fixture-other\"", eval("document.getElementById('modal_auth_login').value"))
        assertEquals("\"\"", eval("document.getElementById('modal_auth_pass').value"))
    }

    @Test fun rejectedAjaxReauthenticationDoesNotRepeatAndIgnoresUnrelatedSuccess() {
        load("<p>Dashboard fixture</p>$reauthFixture", reauthScript, "https://univer.kaznu.kz/student")
        eval("window.authFails=true;document.getElementById('auth_modal').style.display='block'")
        val p = probe()
        val args = "${token()},${JSONObject.quote(p.getString("reauthChallenge"))},'fixture-user','fixture-password'"
        call("reauthenticate($args)")
        eval("$(document).trigger('ajaxSuccess',[{responseText:'{\"code\":1}'},{url:'https://external.test/user/ajl',type:'POST'}])")
        assertEquals("pending", probe().getString("reauthOutcome"))
        awaitReauthOutcome("failure")
        assertEquals(2, probe().getInt("reauthAttempt"))
        call("reauthenticate($args)")
        assertEquals("1", eval("window.authPosts"))
        assertFalse(probe().toString().contains("fixture-password"))
        assertFalse(probe().toString().contains("fixture-nonce"))
    }

    @Test fun noticeCanAppearAgainOnSameDashboardAfterStableAbsence() {
        load("""<div id="notice" style="display:none"><form id="attentionSt" action="/student/attentionStudents/42">
            <p>Repeated notice fixture</p><input type="checkbox" id="check" onclick="calc()">
            <label for="check">I confirm that I have read this notice</label><input type="submit" id="btn" disabled></form></div>""",
            """window.posts=0;function calc(){document.getElementById('btn').disabled=false;}
            window.onload=function(){document.getElementById('attentionSt').onsubmit=function(e){e.preventDefault();window.posts++;document.getElementById('notice').style.display='none';};};""",
            "https://univer.kaznu.kz/student")
        assertFalse(probe().getBoolean("notice"))
        eval("document.getElementById('notice').style.display='block'")
        val first = probe().getString("noticeEpisode")
        call("checkNotice(${token()},${JSONObject.quote(first)})")
        call("submitNotice(${token()},${JSONObject.quote(first)})")
        probe(); Thread.sleep(1_700); probe()
        eval("document.getElementById('check').checked=false;document.getElementById('btn').disabled=true;document.getElementById('notice').style.display='block'")
        val second = probe().getString("noticeEpisode")
        assertNotEquals(first, second)
        assertEquals("\"wait\"", call("checkNotice(${token()},${JSONObject.quote(first)})"))
        call("checkNotice(${token()},${JSONObject.quote(second)})")
        call("submitNotice(${token()},${JSONObject.quote(second)})")
        assertEquals("2", eval("window.posts"))
    }

    private val agreementFixture = """<div id="modal_agreement" style="display:none;background:white;width:90%;padding:5%">
        <form id="agree_frm" method="post" action="#"><p class="MsoTitle">Дополнительное соглашение — тестовый документ</p>
        <p class="MsoNormal"><span style="font-size:10pt">Текст тестового соглашения без персональных данных.</span></p></form>
        <center><label for="iconfirm">Прочитал договор</label><input type="checkbox" id="iconfirm" value="0">
        <div style="position:relative"><div id="confirm-block" style="float:left;width:50%">
        <button onclick="agree()" class="btn-confirm" disabled>Согласен</button></div>
        <div style="float:left;width:50%"><button onclick="document.getElementById('modal_agreement').style.display='none'">Закрыть</button></div></div></center></div>"""

    private val agreementScript = jquery + """
        window.completeAgreement=function(){$(document).trigger('ajaxSuccess',[{responseText:''},{url:'/user/setagree',type:'POST'}]);};
        window.agreementPosts=0;function agree(){window.agreementPosts++;$('#modal_agreement').hide();
          if(!window.manualAgreementCompletion)setTimeout(window.completeAgreement,400);return false;}
        $(document).ready(function(){$('#iconfirm').change(function(){document.querySelector('.btn-confirm').disabled=!this.checked;});});
    """

    @Test fun knownAdditionalAgreementUsesChangeAndAgreeHandlerOnce() {
        load("<p>Dashboard</p>$agreementFixture", agreementScript + "window.manualAgreementCompletion=true;", "https://univer.kaznu.kz/student")
        assertFalse(probe().getBoolean("agreement"))
        eval("document.getElementById('modal_agreement').style.display='block'")
        val appearance = JSONObject.quote(probe().getString("agreementEpisode"))
        call("submitAgreement(${token()},$appearance)")
        assertEquals("0", eval("window.agreementPosts"))
        call("checkAgreement(${token()},$appearance)")
        assertTrue(probe().getBoolean("agreementEnabled"))
        call("submitAgreement(${token()},$appearance)")
        call("submitAgreement(${token()},$appearance)")
        assertEquals("pending", probe().getString("agreementOutcome"))
        eval("window.completeAgreement()")
        assertEquals("success", probe().getString("agreementOutcome"))
        assertEquals("1", eval("window.agreementPosts"))
    }

    private fun phoneWidth(dp: Int) {
        instrumentation.runOnMainSync { web.layoutParams = web.layoutParams.apply { width = (dp * web.resources.displayMetrics.density).toInt() } }
        Thread.sleep(200)
    }

    @Test fun mobileAjaxDialogFitsNarrowPhoneAndKeyboardViewport() {
        load("""<div id="simplemodal-overlay"></div><div id="simplemodal-container" style="left:1000px;top:800px;height:200px;width:300px;position:fixed">
            <a class="simplemodal-close" title="Close"></a><div class="simplemodal-wrap" style="height:100%;width:100%">$reauthFixture</div></div>""",
            reauthScript, "https://univer.kaznu.kz/student")
        phoneWidth(320)
        // The keyboard reaches the adapter as the visual viewport's resize event; a repeated render reads nothing.
        eval("""(function(){var l=[];window.__vvHeight=null;Object.defineProperty(window,'visualViewport',{value:{
            get height(){return window.__vvHeight==null?innerHeight:window.__vvHeight;},offsetTop:0,
            addEventListener:function(t,f){if(t==='resize')l.push(f);},removeEventListener:function(t,f){l=l.filter(function(g){return g!==f;});}}});
            window.__vvResize=function(h){window.__vvHeight=h;l.forEach(function(f){f();});};})()""")
        eval("document.getElementById('auth_modal').style.display='block'")
        mobile()
        assertEquals("true", eval("(function(){var r=document.getElementById('simplemodal-container').getBoundingClientRect();return r.left>=0&&r.right<=document.documentElement.clientWidth&&r.bottom<=innerHeight;})()"))
        assertEquals("true", eval("document.getElementById('modal_auth_pass').getBoundingClientRect().width>200 && getComputedStyle(document.getElementById('modal_auth_pass')).fontSize==='16px'"))
        mobile()
        eval("window.__vvResize(260)")
        assertEquals("true", eval("document.getElementById('simplemodal-container').getBoundingClientRect().bottom<=248.5"))
        assertEquals("true", eval("document.getElementById('simplemodal-container').scrollHeight>document.getElementById('simplemodal-container').clientHeight"))
        mobile(false)
        assertEquals("\"1000px\"", eval("getComputedStyle(document.getElementById('simplemodal-container')).left"))
        assertEquals("0", eval("document.querySelectorAll('.univer-app-modal-toolbar').length"))
    }

    @Test fun mobileLongAgreementScrollsWithoutClippingTextOrControls() {
        load("""<div id="simplemodal-container"><a class="simplemodal-close"></a><div class="simplemodal-wrap">$agreementFixture</div></div>""",
            agreementScript, "https://univer.kaznu.kz/student")
        phoneWidth(320)
        eval("document.getElementById('modal_agreement').style.display='block';var p=document.querySelector('#agree_frm p.MsoNormal');for(var i=0;i<20;i++)p.parentNode.appendChild(p.cloneNode(true));")
        mobile()
        assertEquals("true", eval("(function(){var m=document.getElementById('simplemodal-container');return m.scrollHeight>m.clientHeight&&m.scrollWidth<=m.clientWidth+1;})()"))
        assertEquals("\"16px\"", eval("getComputedStyle(document.querySelector('#agree_frm span')).fontSize"))
        eval("document.getElementById('simplemodal-container').scrollTop=100000")
        assertEquals("true", eval("(function(){var m=document.getElementById('simplemodal-container').getBoundingClientRect(),b=document.querySelector('.btn-confirm').getBoundingClientRect();return b.left>=m.left&&b.right<=m.right&&b.bottom<=m.bottom;})()"))
        assertEquals("true", eval("(function(){var m=document.getElementById('simplemodal-container').getBoundingClientRect(),c=document.querySelector('.simplemodal-close').getBoundingClientRect();return c.top>=m.top&&c.bottom<=m.bottom&&c.height>=44;})()"))
        assertEquals("true", eval("(function(){var c=document.querySelector('.simplemodal-close'),r=c.getBoundingClientRect();return document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)?.closest('.simplemodal-close')===c;})()"))
        call("checkAgreement(${token()})")
        assertTrue(probe().getBoolean("agreementEnabled"))
    }

    @Test fun mobileMenuGroupsSearchesAndRestoresOriginalLinksAndHandlers() {
        load("""<table class="wrappertable" style="width:1000px"><tr id="l4"><td>
            <table id="tools"><tr><td><table class="tool" id="group1"><tr><td class="top-left"></td><td class="top-center"><ul>
              <li><a id="scheduleLink" href="/fixture/schedule">Расписание занятий</a></li><li><a href="/fixture/umkd">УМКД</a></li></ul></td><td class="top-right"></td></tr>
              <tr><td></td><td class="bottom-center">Обучение</td><td></td></tr></table>
            <table class="tool" id="group2"><tr><td class="top-center"><ul><li><a href="/fixture/orders">Приказы</a></li></ul></td></tr>
              <tr><td class="bottom-center">Документы</td></tr></table></td></tr></table></td></tr></table>""",
            "window.linkClicks=0;window.onload=function(){document.getElementById('scheduleLink').onclick=function(e){e.preventDefault();window.linkClicks++;};};")
        phoneWidth(320); mobile()
        assertEquals("2", eval("document.querySelectorAll('.univer-app-category').length"))
        assertEquals("Only the first group starts open", "true,false", eval("Array.from(document.querySelectorAll('.univer-app-category')).map(d=>d.open).join()").trim('"'))
        eval("document.querySelector('.univer-app-menu').open=true;var s=document.querySelector('.univer-app-menu-search');s.value='приказ';s.dispatchEvent(new Event('input'))")
        assertEquals("Search opens a closed group with matches", "true", eval("document.getElementById('group2').closest('details').open"))
        eval("var s=document.querySelector('.univer-app-menu-search');s.value='';s.dispatchEvent(new Event('input'))")
        assertEquals("Clearing the search restores the closed group", "false", eval("document.getElementById('group2').closest('details').open"))
        eval("var s=document.querySelector('.univer-app-menu-search');s.value='расписание';s.dispatchEvent(new Event('input'))")
        assertEquals("true", eval("document.querySelector('.univer-app-category').open && document.getElementById('scheduleLink').closest('li').hidden===false"))
        assertEquals("true", eval("document.getElementById('group2').closest('details').hidden"))
        assertEquals("true", eval("document.documentElement.scrollWidth<=document.documentElement.clientWidth+1"))
        eval("document.getElementById('scheduleLink').click()")
        assertEquals("1", eval("window.linkClicks"))
        eval("var s=document.querySelector('.univer-app-menu-search');s.value='missing-section';s.dispatchEvent(new Event('input'))")
        assertEquals("false", eval("document.querySelector('.univer-app-empty').hidden"))
        eval("var s=document.querySelector('.univer-app-menu-search');s.value='';s.dispatchEvent(new Event('input'))")
        assertEquals("\"Свернуть все разделы\"", eval("document.querySelector('.univer-app-menu-collapse').textContent"))
        eval("document.querySelector('.univer-app-menu-collapse').click()")
        assertEquals("The button at the end closes the menu", "false", eval("document.querySelector('.univer-app-menu').open"))
        mobile(false)
        assertEquals("0", eval("document.querySelectorAll('.univer-app-category,.univer-app-menu,.univer-app-menu-collapse').length"))
        assertEquals("0", eval("document.querySelectorAll('#tools li[hidden]').length"))
        eval("document.getElementById('scheduleLink').click()")
        assertEquals("2", eval("window.linkClicks"))
    }


    @Test fun lateSameOriginNoticeIframeIsAdaptedAndSubmittedButExternalFrameIsIgnored() {
        load("<p>Dashboard</p><div id='frames'></div>", origin = "https://univer.kaznu.kz/student")
        val frameHtml = """<html><body><form id="attentionSt" action="/student/attentionStudents/42"><div class="parent"><div class="block" style="position:absolute;width:800px;left:40%;margin:-300px">
            <p>Iframe notice fixture</p><input id="check" type="checkbox" onclick="document.getElementById('btn').disabled=false">
            <label for="check">I confirm that I have read this notice</label><input id="btn" type="submit" disabled></div></div></form>
            <script>window.posts=0;document.querySelector('form').onsubmit=function(e){e.preventDefault();window.posts++;};</script></body></html>"""
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(frameHtml.toByteArray()))
            }
        }
        eval("document.getElementById('frames').innerHTML='<iframe id=same src=https://univer.kaznu.kz/fixture/frame></iframe><iframe id=external src=https://external.test/fixture/frame></iframe>'")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!probe().getBoolean("notice") && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue(probe().getBoolean("notice"))
        mobile()
        assertEquals("true", eval("!!document.getElementById('same').contentWindow.__univerMobileV1"))
        assertEquals("\"static\"", eval("document.getElementById('same').contentWindow.getComputedStyle(document.getElementById('same').contentDocument.querySelector('.block')).position"))
        call("checkNotice(${token()})")
        call("submitNotice(${token()})")
        assertEquals("1", eval("document.getElementById('same').contentWindow.posts"))
        eval("document.getElementById('same').style.display='none'")
        assertFalse(probe().getBoolean("notice"))
        mobile(false)
        assertEquals("false", eval("document.getElementById('same').contentDocument.documentElement.classList.contains('univer-mobile')"))
    }


    @Test fun innerDataTableUsesLocalScrollAndNewsCardsKeepImagesWithinPhone() {
        load("""<table class="wrappertable" style="width:1000px"><tr id="l4"><td>
            <table class="inner" id="realData"><tr><th></th><th>Course</th><th>Teacher</th><th>Week</th><th>Status</th></tr>
            <tr><td>→</td><td>Fixture course</td><td>Fixture teacher</td><td>Monday</td><td>Open</td></tr></table>
            <table class="inner" id="news"><tr><th>Дата</th><th>Новость</th></tr><tr class="news">
            <td><div class="date">06<br>10.2026</div><div style="width:50px;background:red">Важное</div></td>
            <td><p>Public news fixture</p><div style="text-indent:47px"><img width="600" height="100" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"></div></td></tr></table>
            </td></tr></table>""")
        phoneWidth(320); mobile()
        assertEquals("\"auto\"", eval("getComputedStyle(document.getElementById('realData')).tableLayout"))
        assertEquals("true", eval("(function(){var b=document.getElementById('realData').parentElement;return b.classList.contains('univer-app-table-scroll')&&b.scrollWidth>b.clientWidth;})()"))
        assertEquals("false", eval("document.querySelector('#realData td').classList.contains('univer-app-data-field')"))
        assertEquals("true", eval("document.querySelector('#news img').getBoundingClientRect().right<=document.documentElement.clientWidth+1"))
        assertEquals("\"block\"", eval("getComputedStyle(document.querySelector('#news tr.news')).display"))
        mobile(false)
        assertEquals("\"table-row\"", eval("getComputedStyle(document.querySelector('#news tr.news')).display"))
        assertEquals("0", eval("document.querySelectorAll('.univer-app-news,.univer-app-data').length"))
    }


    @Test fun compactHeaderPreservesAccountFlagsAndRestoresOriginalNodes() {
        load("""<table class="wrappertable"><tr id="l1"><td class="header"><a class="bvi-open" href="#">Accessibility</a>KazNU
            <a id="flag-kz" href="/lang/change/kz/"><img src="/content/icons/kz.png"></a><img src="/content/icons/ru.png"><a href="/lang/change/en/"><img src="/content/icons/en.png"></a></td></tr>
            <tr id="l2"><td><table class="inner"><tr><td class="center"><div id="logo" style="width:260px;height:70px;background:url(data:,test)"></div><div class="links">
            <a id="account-link" href="/user/pass/">Synthetic account with long text</a><br><a href="/user/logout">Exit</a></div></td></tr></table></td></tr>
            <tr id="l3"><td><table class="inner"><tr><td class="center"><a href="/">Home<div class="left"></div></a><a class="active" href="/student/bachelor/">Bachelor</a><a href="/ssc/">Services</a></td></tr></table></td></tr></table>""")
        eval("window.originalAccount=document.getElementById('account-link');window.originalFlag=document.getElementById('flag-kz');window.accountClicks=0;window.originalAccount.onclick=function(e){e.preventDefault();window.accountClicks++;}")
        phoneWidth(320); mobile()
        assertEquals("true", eval("document.querySelector('#l3').getBoundingClientRect().bottom<=210 && document.documentElement.scrollWidth<=innerWidth+1"))
        assertEquals("true", eval("document.querySelector('.univer-app-flags>a').getBoundingClientRect().height>=44 && document.querySelector('.univer-app-account').open===false"))
        eval("document.querySelector('.univer-app-account').open=true;document.getElementById('account-link').click()")
        assertEquals("1", eval("window.accountClicks"))
        mobile(false)
        assertEquals("true", eval("window.originalAccount===document.querySelector('#l2 .links>a') && window.originalFlag.parentElement.matches('#l1 .header') && !document.querySelector('.univer-app-header,.univer-app-account')"))
        mobile()
        assertEquals("1", eval("document.querySelectorAll('.univer-app-header').length"))
    }

    private fun studyPanel(content: String) = """<table class="wrappertable"><tr id="l4"><td class="center"><table class="mt"><tr class="top"><td class="lt"></td><td class="ct">Study page</td><td class="rt"></td></tr>
        <tr class="mid"><td class="lt"></td><td class="tt">$content</td><td class="rt"></td></tr><tr class="empty"><td colspan="9"></td></tr></table></td></tr></table>"""

    @Test fun mobileScheduleMapsRowspansDaysAndKeepsOriginalWeekChange() {
        load(studyPanel("""<select id="weekSelect" onchange="window.weekChanges++"><option>Week 01. 31.08.2026–06.09.2026</option><option>Week 02</option></select>
            <table class="schedule"><tr><th></th><th>ПН</th><th>ВТ</th><th>СР</th></tr>
            <tr><td><div class="divIE" style="display:none">09:00-09:50</div><object></object></td><td rowspan="2"><div class="groups"><div><p class="teacher">Long subject name for mobile layout</p><p class="teacher">Test Teacher</p><p class="params"><span class="aud_faculty" title="Аудитория факультета: ФИТ">ФИТ</span><span>Ауд.: 205</span></p><p class="params"><span class="dateStartLbl">Период с 01.09 по 12.12</span></p></div></div></td><td></td><td></td></tr>
            <tr><td>10:00-10:50</td><td><div class="groups"><div><p>Tuesday class</p></div></div></td><td></td></tr></table>"""), "window.weekChanges=0", "https://univer.kaznu.kz/student/myschedule/")
        eval("window.source=document.querySelector('table.schedule');window.sourceHtml=window.source.innerHTML")
        phoneWidth(320); mobile()
        assertEquals("2", eval("document.querySelectorAll('.univer-app-lesson').length"))
        assertEquals("\"09:00–10:50\"", eval("document.querySelector('.univer-app-lesson time').getAttribute('aria-label')"))
        // Teacher, room and faculty are separate labelled facts; the course dates wait for the detailed view.
        assertEquals("\"Test Teacher\"", eval("document.querySelector('.univer-app-lesson .univer-app-fact-teacher').lastChild.textContent"))
        assertEquals("\"Аудитория: Ауд. 205Факультет: ФИТ\"", eval("document.querySelector('.univer-app-lesson .univer-app-fact-place').textContent"))
        assertEquals("\"Long subject name for mobile layout\"", eval("document.querySelector('.univer-app-lesson .univer-app-subject').textContent"))
        assertEquals("true", eval("getComputedStyle(document.querySelector('.univer-app-lesson .univer-app-fact-dates')).display==='none' && !document.querySelector('.univer-app-lesson').innerText.includes('Период') && document.documentElement.scrollWidth<=innerWidth+1"))
        assertEquals("true", eval("!document.querySelector('.univer-app-week-label') && document.getElementById('weekSelect').options[0].textContent.includes('06.09.2026')"))
        eval("document.querySelectorAll('.univer-app-days button')[1].click()")
        assertEquals("\"Вторник\"", eval("document.querySelector('.univer-app-day:not([hidden]) h3').firstChild.textContent"))
        assertEquals("\"10:00–10:50\"", eval("document.querySelector('.univer-app-day:not([hidden]) .univer-app-day-range').textContent"))
        eval("document.querySelectorAll('.univer-app-days button')[2].click()")
        assertEquals("\"Нет занятий\"", eval("document.querySelector('.univer-app-day:not([hidden]) .univer-app-no-classes').textContent"))
        eval("document.querySelector('.univer-app-days button:last-child').click();document.getElementById('weekSelect').dispatchEvent(new Event('change'))")
        assertEquals("1", eval("window.weekChanges"))
        assertEquals("3", eval("document.querySelectorAll('.univer-app-day:not([hidden])').length"))
        mobile(false)
        assertEquals("true", eval("window.source.innerHTML===window.sourceHtml && !document.querySelector('.univer-app-schedule,.univer-app-week-label') && getComputedStyle(window.source).display!=='none'"))
    }

    @Test fun detailedScheduleShowsPeriodFromHoverWithoutDuplicatingVisibleDates() {
        load(studyPanel("""<table class="schedule"><tr><th></th><th>ПН</th><th>ВТ</th></tr>
            <tr><td>09:00–09:50</td><td><div class="groups"><div id="periodSource" title="Период: 01.09.2026 – 12.12.2026"><p>First subject</p><p>Teacher</p></div></div></td>
            <td><div class="groups"><div title="01.09.2026 – 12.12.2026"><p>Second subject</p><p>Период: 01.09.2026 – 12.12.2026</p></div></div></td></tr></table>"""), "", "https://univer.kaznu.kz/student/myschedule/")
        // Captured before the mobile layout: on Mondays and Tuesdays it marks a header cell of this table as today.
        eval("window.source=document.querySelector('table.schedule');window.sourceHtml=window.source.innerHTML")
        phoneWidth(320); mobile()
        assertEquals("One period per class", "2", eval("document.querySelectorAll('.univer-app-lesson-period').length"))
        assertEquals("Period stays in detailed mode", "true", eval("Array.from(document.querySelectorAll('.univer-app-lesson-period')).every(p=>getComputedStyle(p).display==='none')"))
        eval("document.querySelector('.univer-app-days button').click();document.querySelector('.univer-app-schedule-mode input').click()")
        assertEquals("Detailed period is readable", "true", eval("var p=document.querySelector('.univer-app-lesson-period');p.textContent==='Даты курса: 01.09.2026 – 12.12.2026'&&getComputedStyle(p).display!=='none'&&document.documentElement.scrollWidth<=innerWidth+1"))
        eval("document.getElementById('periodSource').title='Период: 08.09.2026 – 19.12.2026'")
        Thread.sleep(350)
        assertEquals("Late period updates retain detailed mode and selected day", "true", eval("var p=document.querySelector('.univer-app-lesson-period');p.textContent.includes('19.12.2026')&&getComputedStyle(p).display!=='none'&&document.querySelector('.univer-app-days button').getAttribute('aria-pressed')==='true'"))
        eval("window.__univerMobileV1.setEnabled(true,'en')")
        assertEquals("English period label", "true", eval("document.querySelector('.univer-app-lesson-period').textContent.startsWith('Course dates:')"))
        eval("window.__univerMobileV1.setEnabled(true,'kz')")
        assertEquals("Kazakh period label", "true", eval("document.querySelector('.univer-app-lesson-period').textContent.startsWith('Курс мерзімі:')"))
        mobile(false)
        assertEquals("No period label is left behind", "true", eval("!document.querySelector('.univer-app-lesson-period')"))
        assertEquals("The hover text keeps the late update", "true", eval("document.getElementById('periodSource').title.includes('19.12.2026')"))
        eval("document.getElementById('periodSource').title='Период: 01.09.2026 – 12.12.2026'")
        assertEquals("Apart from the site's own late update, the original DOM is intact", "true", eval("window.source.innerHTML===window.sourceHtml"))
    }

    @Test fun mobileScheduleRefreshesLateDataWithoutDuplicateCardsOrResettingDay() {
        load(studyPanel("<div id='late'></div>"), "", "https://univer.kaznu.kz/student/myschedule/2026/1")
        phoneWidth(320); mobile()
        eval("document.getElementById('late').innerHTML='<table class=schedule><tr><th></th><th>ПН</th><th>ВТ</th></tr><tr><td>08:00-08:50</td><td><p id=lesson>First</p></td><td><p>Second</p></td></tr></table>'")
        Thread.sleep(400)
        eval("document.querySelectorAll('.univer-app-days button')[1].click();document.getElementById('lesson').firstChild.data='Updated'")
        Thread.sleep(400)
        assertEquals("1", eval("document.querySelectorAll('.univer-app-schedule').length"))
        assertEquals("2", eval("document.querySelectorAll('.univer-app-lesson').length"))
        assertEquals("true", eval("document.querySelector('.univer-app-schedule').textContent.includes('Updated') && document.querySelectorAll('.univer-app-days button')[1].getAttribute('aria-pressed')==='true'"))
    }

    @Test fun mobileUmkdCardsPreserveDownloadsRatingsAndColspanLayout() {
        load(studyPanel("""<div class="inner"><table class="inner"><tr><th></th><th>Название файла</th><th>Описание</th><th>Язык. гр.</th><th>Размер</th><th>Дата загрузки</th><th>Оцените качество материала</th></tr>
            <tr><th colspan="7">Силлабус</th></tr><tr class="file"><td><img></td><td><a id="file-link" class="downLoad" href="/student/umkd/get/42/99/2026/1">Очень длинное название учебного материала для проверки переноса строк.pdf</a></td><td>Описание материала</td><td>Русский</td><td>631 Кб</td><td>04.10.2026</td><td><div class="stars"><svg class="star rating" id="rating" height="25" width="23"><polygon points="0,0 23,0 0,25"></polygon></svg></div></td></tr></table></div>"""), "window.downloadClicks=0;window.ratingClicks=0", "https://univer.kaznu.kz/student/umkd/99/2026/1")
        eval("window.originalFile=document.getElementById('file-link');window.originalRating=document.getElementById('rating');window.originalFile.onclick=function(e){e.preventDefault();window.downloadClicks++};window.originalRating.onclick=function(){window.ratingClicks++}")
        phoneWidth(320); mobile()
        assertEquals("true", eval("document.documentElement.scrollWidth<=innerWidth+1 && document.querySelector('tr.file').getBoundingClientRect().width>260 && document.querySelector('tr.file').textContent.includes('Описание материала')"))
        assertEquals("\"Размер\"", eval("document.querySelector('tr.file').cells[4].getAttribute('data-univer-field')"))
        assertEquals("true", eval("document.getElementById('file-link').getBoundingClientRect().height>=44 && document.getElementById('rating').getBoundingClientRect().height>=44"))
        assertEquals("true", eval("document.getElementById('rating').getBoundingClientRect().width>=44"))
        eval("document.getElementById('file-link').click();document.getElementById('rating').dispatchEvent(new MouseEvent('click',{bubbles:true}))")
        assertEquals("1", eval("window.downloadClicks")); assertEquals("1", eval("window.ratingClicks"))
        mobile(false)
        assertEquals("true", eval("window.originalFile===document.getElementById('file-link') && window.originalRating===document.getElementById('rating') && !document.querySelector('[data-univer-field],.univer-app-materials,.univer-app-study-panel')"))
    }

    @Test fun menuPunctuationAndResourceBordersRestoreWithoutChangingLinks() {
        load(studyPanel("""<table id="tools"><tr><td><ul><li><a id="transcript" href="/student/transcript/">Транскрипт</a> (<a href="/student/recbook/">Зачетная книжка</a>)</li></ul></td></tr></table>
            <div class="links" style="min-width:600px"><table style="background:linear-gradient(blue,white);border:3px solid blue"><tr><td class="center" style="border-bottom:3px solid blue">
            <a href="https://apps.apple.com/fixture">iOS</a><a href="https://play.google.com/fixture">Android</a><a href="#">Moodle</a><a href="#">Library</a><a href="#">Facebook</a><a href="#">WiFi</a><a href="#">Twitter</a><a href="#">Open</a></td></tr></table></div>"""), origin = "https://univer.kaznu.kz/student")
        eval("window.item=document.querySelector('#tools li');window.originalText=window.item.textContent;window.originalLink=document.getElementById('transcript')")
        phoneWidth(320); mobile()
        eval("document.querySelector('.univer-app-info').open=true")
        assertEquals("true", eval("!window.item.textContent.includes('(')&&!window.item.textContent.includes(')')&&document.getElementById('transcript')===window.originalLink"))
        assertEquals("\"0px\"", eval("getComputedStyle(document.querySelector('.univer-app-info td')).borderBottomWidth"))
        assertEquals("true", eval("document.documentElement.scrollWidth<=innerWidth+1"))
        mobile(false)
        assertEquals("true", eval("window.item.textContent===window.originalText&&document.getElementById('transcript')===window.originalLink"))
    }

    @Test fun recordCardsKeepFieldsSectionsSortAndLateRows() {
        load(studyPanel("""<table class="inner table_sort" id="records"><tr><th>#</th><th>Дисциплина</th><th>Кред.</th><th>Статус</th></tr>
            <tr><td colspan="4">1 семестр</td></tr><tr id="record"><td>1</td><td>Длинное название дисциплины для проверки переноса</td><td>5</td><td><input type="checkbox" id="selectRecord"></td></tr></table>"""), origin="https://univer.kaznu.kz/student/educplan/")
        eval("window.sorted=0;document.querySelector('#records th').onclick=function(){window.sorted++};window.control=document.getElementById('selectRecord')")
        phoneWidth(320); mobile()
        assertEquals("true", eval("document.getElementById('records').classList.contains('univer-app-cards')&&document.querySelector('.univer-app-card-section').textContent==='1 семестр'"))
        assertEquals("\"Дисциплина\"", eval("document.getElementById('record').cells[1].dataset.univerField"))
        assertEquals("true", eval("document.documentElement.scrollWidth<=innerWidth+1&&!!document.querySelector('.univer-app-sort-toggle')"))
        eval("document.querySelector('.univer-app-sort-toggle').click()")
        assertEquals("true",eval("getComputedStyle(document.querySelector('#records tr')).display!=='none'"))
        eval("document.querySelector('#records th').click();document.getElementById('selectRecord').click();document.getElementById('records').insertAdjacentHTML('beforeend','<tr id=lateRecord><td>2</td><td>Late subject</td><td>3</td><td>Open</td></tr>')")
        Thread.sleep(400)
        assertEquals("1", eval("window.sorted"))
        assertEquals("true", eval("window.control.checked&&document.getElementById('lateRecord').classList.contains('univer-app-card-row')"))
        eval("window.adapterMutations=0;window.idleObserver=new MutationObserver(function(r){window.adapterMutations+=r.length});window.idleObserver.observe(document.getElementById('records'),{subtree:true,attributes:true,childList:true})")
        Thread.sleep(400)
        assertEquals("0", eval("window.adapterMutations"))
        eval("window.idleObserver.disconnect()")
        mobile(false)
        assertEquals("true", eval("window.control===document.getElementById('selectRecord')&&!document.querySelector('[data-univer-field],.univer-app-card-row')"))
    }

    @Test fun personalPairsAndEditPhotoFitNarrowPhoneKeepForm() {
        load(studyPanel("""<table class="inner" id="pairs"><tr><td>Факультет:</td><td>Тестовый факультет</td><td>Курс:</td><td>2</td></tr></table>
            <form id="edit"><table class="inner" id="photo"><tr><th>E-mail</th><td rowspan="3"></td><th>Фотография</th></tr>
            <tr><td rowspan="2"><input id="email" type="text" name="studentMail"></td><td><input type="file" name="photo"><input type="submit"></td></tr></table></form>"""), origin="https://univer.kaznu.kz/student/mydata/edit/")
        eval("window.originalFile=document.querySelector('input[type=file]');window.saved=0;document.getElementById('edit').onsubmit=function(e){e.preventDefault();window.saved++}")
        phoneWidth(320); mobile()
        assertEquals("true", eval("document.getElementById('pairs').classList.contains('univer-app-pairs')&&document.getElementById('photo').classList.contains('univer-app-form-table')&&document.documentElement.scrollWidth<=innerWidth+1"))
        eval("document.getElementById('email').value='fixture@example.test';document.querySelector('input[type=submit]').click()")
        assertEquals("1", eval("window.saved"))
        mobile(false)
        assertEquals("true", eval("window.originalFile===document.querySelector('input[type=file]')&&document.getElementById('email').value==='fixture@example.test'&&!document.querySelector('.univer-app-pairs,.univer-app-form-table')"))
    }

    @Test fun documentTextReflowsAndNestedGridsScrollSeparatelyAndRestore() {
        load("""<table id="sheet" width="1900"><tr><td><p style="font-size:10pt;text-indent:50px">Readable print document text which should wrap on a small phone screen.</p>
            <table id="documentProfile"><tr><td>Ф.И.О.</td><td colspan="3">Test student</td></tr><tr><td>Факультет</td><td>Information technology</td><td>Курс</td><td>2</td></tr></table>
            <table id="grades"><tr><td rowspan="2">Subject</td><td colspan="3">Grades</td></tr><tr><td>Exam</td><td>Midterm</td><td>Total</td></tr><tr><td>Long subject name for print</td><td>95</td><td>90</td><td>93</td></tr></table></td></tr></table>""", origin="https://univer.kaznu.kz/student/transcript/2/")
        eval("window.grades=document.getElementById('grades');window.originalRows=window.grades.innerHTML")
        phoneWidth(320); mobile()
        assertEquals("true", eval("document.getElementById('sheet').classList.contains('univer-app-document-layout')&&document.getElementById('grades').parentElement.classList.contains('univer-app-table-scroll')&&document.documentElement.scrollWidth<=innerWidth+1"))
        assertEquals("\"16px\"", eval("getComputedStyle(document.querySelector('p')).fontSize"))
        assertEquals("true", eval("document.getElementById('documentProfile').classList.contains('univer-app-pairs')&&document.getElementById('documentProfile').getBoundingClientRect().right<=innerWidth+1"))
        assertEquals("true", eval("window.grades.innerHTML===window.originalRows&&document.querySelector('p').getBoundingClientRect().right<=innerWidth+1"))
        mobile(false)
        assertEquals("true", eval("window.grades.innerHTML===window.originalRows&&!document.querySelector('.univer-app-document-layout,.univer-app-table-scroll')&&document.getElementById('sheet').getAttribute('width')==='1900'"))
    }

    @Test fun splitExamHeaderLabelsLateRowsAndAttendanceKeepsMatrix() {
        load(studyPanel("""<table class="inner" id="examHead"><tr><th>Дисциплина</th><th>Преподаватель</th><th>Аудитория</th></tr></table>
            <table class="inner" id="scheduleList"><tr><td colspan="3">Расписание отсутствует</td></tr></table>"""), origin="https://univer.kaznu.kz/student/myexam/schedule/")
        phoneWidth(320); mobile()
        assertEquals("true", eval("document.getElementById('scheduleList').classList.contains('univer-app-empty-note')"))
        eval("document.getElementById('scheduleList').innerHTML='<tr id=exam><td>Test subject</td><td>Test teacher</td><td>205</td></tr>'")
        Thread.sleep(400)
        assertEquals("\"Аудитория\"", eval("document.getElementById('exam').cells[2].dataset.univerField"))
        load(studyPanel("""<table class="inner" id="journal"><tr><th rowspan="2">РК1</th><th>01.09</th><th>02.09</th><th>03.09</th><th>04.09</th><th>05.09</th><th>Итого</th></tr><tr><td>95</td><td>90</td><td>89</td><td>96</td><td>97</td><td>94</td></tr></table>"""), origin="https://univer.kaznu.kz/student/attendance/full/")
        phoneWidth(320); mobile()
        assertEquals("true", eval("document.getElementById('journal').classList.contains('univer-app-matrix')&&!document.getElementById('journal').classList.contains('univer-app-cards')&&document.querySelector('#journal th').rowSpan===2&&document.documentElement.scrollWidth<=innerWidth+1"))
    }

    @Test fun mobileUmkdCourseButtonInvokesOriginalLegacyDoubleClickOnce() {
        load(studyPanel("""<table class="inner"><tr><th></th><th>Дисциплина</th><th>Тип дисциплины</th></tr><tr class="link" id="42"><td><img></td><td>Test course with a long readable title</td><td>Обязательная</td></tr></table>"""), jquery + """window.opened=0;$(document).ready(function(){$('tr.link').dblclick(function(){window.opened++})})""", "https://univer.kaznu.kz/student/umkd/")
        phoneWidth(320); mobile()
        assertEquals("true", eval("document.querySelector('tr.link').getBoundingClientRect().width>260 && document.querySelector('.univer-app-course-open').getBoundingClientRect().height>=44"))
        eval("document.querySelector('.univer-app-course-open').click()")
        assertEquals("1", eval("window.opened"))
        mobile(false)
        assertEquals("0", eval("document.querySelectorAll('.univer-app-course-open').length"))
        eval("$('tr.link').trigger('dblclick')")
        assertEquals("2", eval("window.opened"))
    }

    @Test fun quickNavigationMovesOnlyHelpAndReturnsToOriginalMenuWithoutLosingHandlers() {
        load("""<table class=wrappertable><tr id=l4><td><table id=tools><tr><td><ul>
            <li><a id=quickSource href=/student/myschedule/>Расписание</a></li><li><a href=/student/umkd/>УМКД</a></li></ul></td></tr></table>
            <table class=mt id=help><tr><td>it@kaznu.kz wifi-support@kaznu.kz help@kaznu.kz</td></tr></table>
            <p id=content style=height:1800px>Study content</p></td></tr><tr id=l5><td class=center><table id=copyright><tr><td>Footer</td></tr></table></td></tr></table>""",
            "window.opened=0;window.onload=function(){document.getElementById('quickSource').onclick=function(e){e.preventDefault();window.opened++}};",
            "https://univer.kaznu.kz/student/bachelor/")
        phoneWidth(320); mobile()
        assertEquals("true",eval("document.getElementById('help').closest('#l5')!==null"))
        eval("document.querySelector('.univer-app-quick a').click()")
        assertEquals("1",eval("window.opened"))
        eval("window.scrollTo(0,700)")
        Thread.sleep(200)
        assertEquals("No navigation overlays", "0",eval("document.querySelectorAll('.univer-app-menu-return').length"))
        eval("""window.scrollMutations=0;window.scrollObserver=new MutationObserver(function(records){window.scrollMutations+=records.length});
            window.scrollObserver.observe(document.querySelector('.univer-app-menu'),{attributes:true,attributeFilter:['hidden']});
            for(var i=0;i<10;i++)window.dispatchEvent(new Event('scroll'));""")
        Thread.sleep(200)
        assertEquals("Unchanged scroll position must not trigger DOM adaptation", "0", eval("window.scrollMutations"))
        eval("window.scrollObserver.disconnect()")
        eval("var m=document.querySelector('.univer-app-menu');m.open=true;m.scrollIntoView({block:'start'})")
        assertEquals("true",eval("var m=document.querySelector('.univer-app-menu');m.open && Math.abs(m.getBoundingClientRect().top) < 60"))
        val dismissed=CountDownLatch(1); var result=false
        instrumentation.runOnMainSync { com.univerflow.kaznu.web.MobilePageAdapter(web).dismissNavigation { result=it; dismissed.countDown() } }
        assertTrue(dismissed.await(5,TimeUnit.SECONDS)); assertTrue(result)
        mobile(false)
        assertEquals("true",eval("document.getElementById('help').closest('#l4')!==null"))
        assertEquals("0",eval("document.querySelectorAll('.univer-app-quick,.univer-app-menu-return').length"))
    }
    @Test fun generalDocumentsSearchDownloadAndLateRowsKeepOriginalCatalogHandlers() {
        fun group(id:String,title:String,name:String)="""<table class=mt id=$id><tr><td class=ct>$title</td></tr><tr><td class=ts></td></tr><tr><td>
            <table class=inner><tr><td></td><td>Название файла</td><td>Дата</td><td>Размер</td><td>Скачали</td><td></td></tr></table>
            <div class="inner scrool200" style="width:900px;height:200px"><table class=inner><tr class="file link" id=file$id><td></td><td>1</td><td>$name</td><td>06.10.2026</td><td>20 Кб</td><td>3</td></tr></table></div></td></tr></table>"""
        load(studyPanel(group("a","Каталог","Алгебра")+group("b","Инструкции","Биология")),
            jquery+"window.downloads=0;$(document).ready(function(){ $('tr.file').dblclick(function(){window.downloads++}) });",
            "https://univer.kaznu.kz/my/docs/")
        phoneWidth(320);mobile()
        assertEquals("2",eval("document.querySelectorAll('.univer-app-catalog').length"))
        eval("var s=document.querySelector('.univer-app-catalog-search');s.value='алгебра';s.dispatchEvent(new Event('input'))")
        assertEquals("1",eval("document.querySelectorAll('.univer-app-catalog:not([hidden])').length"))
        eval("document.querySelector('.univer-app-catalog-download').click()")
        assertEquals("1",eval("window.downloads"))
        assertEquals("true",eval("document.documentElement.scrollWidth<=document.documentElement.clientWidth+1"))
        eval("var r=document.getElementById('filea').cloneNode(true);r.id='late';r.cells[2].textContent='Астрономия';document.getElementById('filea').after(r)")
        Thread.sleep(250)
        assertEquals("true",eval("document.getElementById('late').hidden && !!document.getElementById('late').querySelector('.univer-app-catalog-download')"))
        mobile(false)
        assertEquals("0",eval("document.querySelectorAll('.univer-app-catalog,.univer-app-catalog-download,tr.file[hidden]').length"))
        eval("$('#filea').trigger('dblclick')")
        assertEquals("2",eval("window.downloads"))
    }
    @Test fun gpaInputCardsKeepColspansValuesAndCalculationEventsWithLocalScaleScroll() {
        load(studyPanel("""<table id=layout><tr><td><table id=gpa-table class=inner><tr><th colspan=2>Дисциплина</th><th colspan=2>Кредиты</th><th colspan=2>Оценка</th></tr>
            <tr><td colspan=2><input id=subject type=text></td><td colspan=2><input id=credits type=number oninput="window.changes++"></td><td colspan=2><select><option>A</option></select></td></tr></table></td><td></td><td><table style=width:900px><tr><th>Шкала</th><th>Балл</th></tr><tr><td rowspan=2>A</td><td>4.0</td></tr><tr><td>3.67</td></tr></table></td></tr></table>"""),
            "window.changes=0;","https://univer.kaznu.kz/my/calculatorgpa/")
        phoneWidth(320);mobile()
        assertEquals("true",eval("document.getElementById('layout').classList.contains('univer-app-gpa-layout')"))
        assertEquals("true",eval("document.getElementById('credits').getBoundingClientRect().height>=44 && document.documentElement.scrollWidth<=document.documentElement.clientWidth+1"))
        eval("document.getElementById('credits').value='5';document.getElementById('credits').dispatchEvent(new Event('input'))")
        assertEquals("1",eval("window.changes"))
        mobile(false)
        assertEquals("\"5\"",eval("document.getElementById('credits').value"))
        assertEquals("2",eval("document.getElementById('credits').parentElement.colSpan"))
        assertEquals("0",eval("document.querySelectorAll('.univer-app-gpa-layout,.univer-app-cards').length"))
    }
    @Test fun gpaTableWithoutRowsYetDoesNotStopTheRestOfTheAdaptation() {
        // The calculator fills #gpa-table itself. Before its rows exist the pass must still reach later steps
        // (here the scroll box of a wide grid) and turn the rows into cards once they arrive.
        load(studyPanel("""<table id=gpa-table class=inner></table><table class=inner id=wide><tr><td>Шкала</td><td>4.0</td></tr></table>"""),
            "","https://univer.kaznu.kz/my/calculatorgpa/")
        phoneWidth(320);mobile()
        assertEquals("true",eval("!!document.getElementById('wide').closest('.univer-app-table-scroll')"))
        eval("document.getElementById('gpa-table').innerHTML='<tr><th>Дисциплина</th><th>Кредиты</th><th>Оценка</th></tr><tr><td>Физика</td><td>5</td><td>A</td></tr>'")
        Thread.sleep(250)
        assertEquals("true",eval("document.getElementById('gpa-table').classList.contains('univer-app-cards') && document.querySelector('#gpa-table td').dataset.univerField==='Дисциплина'"))
        mobile(false)
        assertEquals("0",eval("document.querySelectorAll('.univer-app-cards,.univer-app-table-scroll').length"))
    }
    @Test fun faqFoldAndSortTogglePreserveWebsiteNodesAndRestoreOriginalDisplay() {
        load(studyPanel("""<table class=mt><tr><td class=ct>Вопрос?</td></tr><tr class=asdfasd style="display:none" id=answerRow><td class=tt><table class=inner id=answer><tr><th>Ответ:</th></tr><tr><td><a id=faqLink href=# onclick="window.links++;return false">Помощь</a></td></tr></table></td></tr></table>"""),
            "window.links=0;","https://univer.kaznu.kz/my/faq/")
        phoneWidth(320);mobile()
        assertEquals("1",eval("document.querySelectorAll('.univer-app-faq').length"))
        assertEquals("true",eval("document.querySelector('.univer-app-faq>summary').getClientRects().length>0"))
        eval("document.querySelector('.univer-app-faq').open=true;document.getElementById('faqLink').click()")
        assertEquals("1",eval("window.links"))
        assertEquals("true",eval("document.getElementById('faqLink').getClientRects().length>0"))
        mobile(false)
        assertEquals("true",eval("document.getElementById('answer').parentElement.className==='tt'"))
        assertEquals("true",eval("getComputedStyle(document.getElementById('answerRow')).display==='none'"))
        load(studyPanel("""<table class="inner table_sort" id=sort><tr><th onclick="window.sorts++">Дата</th><th>Статус</th></tr><tr><td>06.10.2026</td><td>Готово</td></tr></table>"""),
            "window.sorts=0;","https://univer.kaznu.kz/ssc/StatementList")
        phoneWidth(320);mobile()
        assertEquals("true",eval("document.getElementById('sort').classList.contains('univer-app-sort-collapsed')"))
        eval("document.querySelector('.univer-app-sort-toggle').click();document.querySelector('#sort th').click()")
        assertEquals("1",eval("window.sorts"))
        mobile(false)
        assertEquals("0",eval("document.querySelectorAll('.univer-app-sort-toggle,.univer-app-sort-collapsed').length"))
    }
    @Test fun testingInstructionVideoStacksWithoutChangingExternalFrameOrOriginalInlineStyles() {
        load(studyPanel("""<div><div id=links style="float:left;width:40%"><a href=#>Инструкция для студентов</a></div><div id=video style="float:left;width:50%">
            <iframe width=560 height=315 src="https://www.youtube.com/embed/offline-fixture" srcdoc="<p>Offline video placeholder</p>"></iframe><p>Техническая поддержка</p></div></div>"""),
            origin="https://univer.kaznu.kz/student/TestList")
        phoneWidth(320);mobile()
        assertEquals("true",eval("document.documentElement.scrollWidth<=document.documentElement.clientWidth+1 && document.querySelector('iframe').getBoundingClientRect().width<=320"))
        assertEquals("true",eval("document.getElementById('video').getBoundingClientRect().top>=document.getElementById('links').getBoundingClientRect().bottom"))
        mobile(false)
        assertEquals("\"50%\"",eval("document.getElementById('video').style.width"))
        assertEquals("\"560\"",eval("document.querySelector('iframe').getAttribute('width')"))
        assertEquals("0",eval("document.querySelectorAll('.univer-app-video,.univer-app-media-column').length"))
    }
    @Test fun headerUsesTheFullWidthBelowTheNativeAppBarAndRestores() {
        load("""<table><tr id="l1"><td class="header">KazNU<a href="/lang/change/kz/"><img src="/Content/icons/kz.png"></a><img src="/Content/icons/ru.png"><a href="/lang/change/en/"><img src="/Content/icons/en.png"></a></td></tr><tr id="l4"><td>Test content</td></tr></table>""")
        phoneWidth(320); mobile()
        // The native app bar replaced the floating gear: no corner of the page is reserved any more.
        assertEquals("true", eval("var h=document.querySelector('#l1 .header'),s=getComputedStyle(h);parseFloat(s.paddingLeft)<=12&&parseFloat(s.paddingRight)<=12&&!document.documentElement.dataset.univerCorner"))
        assertEquals("true", eval("Array.from(document.querySelectorAll('.univer-app-flags>*')).every(e=>e.getBoundingClientRect().right<=innerWidth)&&document.documentElement.scrollWidth<=innerWidth+1"))
        assertEquals("false", eval("'setSettingsCorner' in window.__univerMobileV1"))
        mobile(false)
        assertEquals("false", eval("!!document.querySelector('.univer-app-header')"))
    }

    @Test fun wideMatricesGetLocalizedScrollHintWithoutChangingRowsOrCheckboxSize() {
        load(studyPanel("""<table class="inner" id="journal"><tr><th rowspan="2">РК1</th><th>01.09.2026</th><th>02.09.2026</th><th>03.09.2026</th><th>04.09.2026</th><th>05.09.2026</th><th>Итого</th></tr><tr><td>95</td><td>90</td><td>89</td><td>96</td><td>97</td><td>94</td></tr></table>"""), origin="https://univer.kaznu.kz/student/attendance/full/")
        eval("window.rowShape=function(){return JSON.stringify(Array.from(document.getElementById('journal').rows).map(r=>Array.from(r.cells).map(c=>[c.tagName,c.textContent,c.colSpan,c.rowSpan])))};window.rows=window.rowShape()")
        phoneWidth(320); mobile()
        assertEquals("Scroll hint visible", "true", eval("!!document.querySelector('.univer-app-scroll-hint:not([hidden])')"))
        assertEquals("Cells and merged spans unchanged", "true", eval("window.rows===window.rowShape()&&document.documentElement.scrollWidth<=innerWidth+1"))
        eval("window.__univerMobileV1.setEnabled(true,'en')")
        assertEquals("true", eval("document.querySelector('.univer-app-scroll-hint').textContent.includes('Swipe')"))
        mobile(false)
        assertEquals("0", eval("document.querySelectorAll('.univer-app-scroll-hint').length"))
        load(studyPanel("""<form><table class="inner"><tr><td><label><input id="option" type="checkbox">Optional field</label><input type="text"></td><td><input type="file"></td></tr></table></form>"""), origin="https://univer.kaznu.kz/student/mydata/edit/")
        phoneWidth(320); mobile()
        assertEquals("true", eval("document.getElementById('option').getBoundingClientRect().width<=24.5&&document.getElementById('option').getBoundingClientRect().height<=24.5"))
    }

    @Test fun umkdBreadcrumbHidesOnlyLooseSeparatorsAndRestoresOriginalText() {
        load("""<div id="l4"><table class="mt"><tr><td class="lt"></td><td class="ct"><a href="/student/umkd/">УМКД</a> \\ <a href="/student/umkd/42/2026/1">A long course title</a></td><td class="rt"></td></tr></table></div>""", origin="https://univer.kaznu.kz/student/umkd/42/2026/1")
        phoneWidth(320); mobile()
        assertEquals("true", eval("!!document.querySelector('.univer-app-breadcrumb')&&!document.querySelector('.ct').textContent.includes('\\\\')&&document.documentElement.scrollWidth<=innerWidth+1"))
        mobile(false)
        assertEquals("true", eval("document.querySelector('.ct').textContent.includes('\\\\')"))
    }
}
