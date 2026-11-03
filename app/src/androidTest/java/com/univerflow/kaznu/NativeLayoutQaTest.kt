package com.univerflow.kaznu

import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univerflow.kaznu.ui.context
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.databinding.SettingsSheetBinding
import com.univerflow.kaznu.databinding.ActivityMainBinding
import com.univerflow.kaznu.ui.DocumentViewer
import com.univerflow.kaznu.web.AutomationController
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

/** Empty native forms only: never exposes the installed account's values. */
@RunWith(AndroidJUnit4::class)
class NativeLayoutQaTest {
    @Test fun syntheticFirstLaunchAndCompactSettingsScreenshots() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun capture(name:String,view:android.view.View){
            Thread.sleep(400)
            lateinit var image:Bitmap
            instrumentation.runOnMainSync{
                assertTrue(view.width>0&&view.height>0)
                image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(image);canvas.drawColor(view.context.getColor(R.color.md_surface));view.draw(canvas)
            }
            val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"qa").apply{mkdirs()}
            File(directory,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            lateinit var form:SettingsSheetBinding
            scenario.onActivity{activity->
                val root=FrameLayout(activity)
                form=SettingsSheetBinding.inflate(LayoutInflater.from(AppLanguage.RUSSIAN.context(activity)))
                form.handle.visibility=android.view.View.GONE;form.closeSettings.visibility=android.view.View.GONE
                form.accountSummary.visibility=android.view.View.GONE
                form.editAccount.visibility=android.view.View.GONE;form.preferences.visibility=android.view.View.GONE
                form.logout.visibility=android.view.View.GONE;form.delete.visibility=android.view.View.GONE;form.downloads.visibility=android.view.View.GONE
                val labels=AppLanguage.RUSSIAN.context(activity)
                form.title.text=labels.getString(R.string.welcome);form.subtitle.text=labels.getString(R.string.welcome_hint)+"\n\n"+labels.getString(R.string.secure_hint)
                form.save.text=labels.getString(R.string.save_enter)
                root.addView(form.root);activity.setContentView(root)
                ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
                ViewCompat.requestApplyInsets(root)
            }
            capture("usability-first-launch",form.root)
            scenario.onActivity{activity->
                form.brandBadge.visibility=android.view.View.GONE;form.handle.visibility=android.view.View.VISIBLE;form.closeSettings.visibility=android.view.View.VISIBLE
                form.accountSummary.visibility=android.view.View.VISIBLE;form.editAccount.visibility=android.view.View.VISIBLE;form.credentials.visibility=android.view.View.GONE
                form.preferences.visibility=android.view.View.VISIBLE;form.moreOptions.visibility=android.view.View.GONE
                form.logout.visibility=android.view.View.VISIBLE;form.delete.visibility=android.view.View.VISIBLE;form.downloads.visibility=android.view.View.VISIBLE
                val labels=AppLanguage.RUSSIAN.context(activity)
                form.title.text=labels.getString(R.string.settings);form.subtitle.text=labels.getString(R.string.secure_hint);form.languages.check(R.id.russian)
                form.accountStatus.text=labels.getString(R.string.account_saved);form.textSizeValue.text="100%"
                form.russian.jumpDrawablesToCurrentState()
                form.subtitle.visibility=android.view.View.GONE
            }
            capture("usability-settings",form.root)
        }
    }
    @Test fun firstLaunchShowsBothFieldsAndSaveBeforeOptionalPreferencesAndSettingsHaveCompactDefaults() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // The form is built by the screen's settings part (main/SettingsForms).
                val forms=MainActivity::class.java.getDeclaredField("forms").apply { isAccessible=true }.get(activity)
                val make=com.univerflow.kaznu.main.SettingsForms::class.java.getDeclaredMethod("make",Boolean::class.javaPrimitiveType).apply { isAccessible=true }
                fun form(first:Boolean)=make.invoke(forms,first) as SettingsSheetBinding
                val first=form(true)
                assertEquals(android.view.View.VISIBLE,first.credentials.visibility)
                assertEquals(android.view.View.GONE,first.preferences.visibility)
                assertEquals(android.view.View.GONE,first.closeSettings.visibility)
                assertEquals(android.view.View.GONE,first.accountSummary.visibility)
                fun belongs(view:android.view.View,parent:android.view.ViewGroup):Boolean {var n=view.parent;while(n is android.view.View){if(n===parent)return true;n=n.parent};return false}
                assertTrue(belongs(first.login,first.credentials));assertTrue(belongs(first.password,first.credentials));assertTrue(belongs(first.save,first.credentials))
                first.save.performClick()
                assertNotNull(first.loginLayout.error);assertNotNull(first.passwordLayout.error)
                first.moreOptions.performClick();assertEquals(android.view.View.VISIBLE,first.preferences.visibility)
                val settings=form(false)
                assertEquals(android.view.View.GONE,settings.credentials.visibility)
                assertEquals(android.view.View.VISIBLE,settings.preferences.visibility)
                assertEquals(android.view.View.VISIBLE,settings.accountSummary.visibility)
                assertEquals(android.view.View.GONE,settings.brandBadge.visibility)
                assertEquals(android.view.View.GONE,settings.subtitle.visibility)
                assertEquals(100f,settings.textSize.value)
                settings.editAccount.performClick();assertEquals(android.view.View.VISIBLE,settings.credentials.visibility)
                // Settings carry no explanatory text; it belongs to the first-run screen only.
                assertEquals(android.view.View.GONE,settings.subtitle.visibility)
                settings.password.text?.clear()
            }
        }
    }
    @Test fun settingsControlsFitLargeFontInEveryLocaleAndDocumentToolbarFitsAPhone() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val width = (320 * density).toInt()
                AppLanguage.entries.forEach { language ->
                    val scaled = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { fontScale = 1.8f })
                    val chrome = ActivityMainBinding.inflate(LayoutInflater.from(language.context(scaled))).toolbar
                    chrome.title = language.context(scaled).getString(R.string.nav_schedule)
                    chrome.subtitle = null
                    chrome.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((56 * density).toInt(), MeasureSpec.EXACTLY))
                    chrome.layout(0, 0, width, chrome.measuredHeight)
                    val title = (0 until chrome.childCount).map(chrome::getChildAt).filterIsInstance<TextView>().first { it.text == chrome.title }
                    assertTrue("Large title fits the compact bar", title.top >= 0 && title.bottom <= chrome.height)
                    assertTrue("Navigation keeps a 48 dp touch target", chrome.getChildAt(0).height >= (48 * density).toInt())
                    val form = SettingsSheetBinding.inflate(LayoutInflater.from(language.context(scaled)))
                    form.root.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    form.root.layout(0, 0, width, form.root.measuredHeight)
                    listOf(form.save, form.logout, form.delete, form.russian, form.kazakh, form.english,form.editAccount,form.closeSettings,form.downloads,form.moreOptions).forEach { button ->
                        assertTrue("${language.siteCode}: ${button.id} text fits", button.layout.height + button.compoundPaddingTop + button.compoundPaddingBottom <= button.height)
                        assertTrue(button.height >= (48 * density).toInt())
                    }
                }
                val host = FrameLayout(activity)
                activity.setContentView(host)
                val model = ViewModelProvider(activity)[UniverViewModel::class.java]
                val viewer = DocumentViewer(activity, host, { AutomationController(it, model, activity.lifecycleScope) {} }, {}, { _, _, _, _ -> }, {})
                viewer.open("about:blank", AppLanguage.KAZAKH)
                viewer.update(AppLanguage.KAZAKH)
                host.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((600 * density).toInt(), MeasureSpec.EXACTLY))
                host.layout(0, 0, width, host.measuredHeight)
                val back = activity.findViewById<TextView>(R.id.document_back)
                val print = activity.findViewById<TextView>(R.id.document_print)
                val mode = activity.findViewById<TextView>(R.id.document_mode)
                val title = activity.findViewById<TextView>(R.id.document_title)
                // Back, title, print and mode share one 320 dp row.
                fun left(view: android.view.View) = IntArray(2).also { view.getLocationInWindow(it) }[0] - IntArray(2).also { host.getLocationInWindow(it) }[0]
                assertTrue(left(back) + back.width <= left(title) && left(title) + title.width <= left(print) &&
                    left(print) + print.width <= left(mode) && left(mode) + mode.width <= width)
                assertTrue(title.width > 0 && back.width >= (48 * density).toInt() && print.width >= (48 * density).toInt())
                assertEquals("Түпнұсқа", mode.text.toString())
                viewer.close(false)
            }
        }
    }
}
