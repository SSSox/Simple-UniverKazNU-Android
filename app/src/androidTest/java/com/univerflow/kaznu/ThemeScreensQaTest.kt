package com.univerflow.kaznu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.color.MaterialColors
import com.univerflow.kaznu.databinding.SettingsSheetBinding
import com.univerflow.kaznu.ui.DownloadsActivity
import com.univerflow.kaznu.ui.PinScreen
import com.univerflow.kaznu.ui.ThemeMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Downloads, the settings sheet and the PIN screen in light and dark (the sheet and PIN screen drawn into bitmaps,
 * since their windows refuse screenshots). The installed account's values and theme are not touched.
 */
@RunWith(AndroidJUnit4::class)
class ThemeScreensQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var nightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED

    @Before fun remember() { nightMode = AppCompatDelegate.getDefaultNightMode() }
    @After fun restore() { instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(nightMode) } }

    /** Draws [view] over [background] and returns the background's luminance at the top-left corner of the drawing. */
    private fun capture(name: String, view: View, background: Int): Double {
        Thread.sleep(500)
        lateinit var image: Bitmap
        instrumentation.runOnMainSync {
            assertTrue(view.width > 0 && view.height > 0)
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            Canvas(image).apply { drawColor(background); view.draw(this) }
        }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ColorUtils.calculateLuminance(image.getPixel(2, image.height - 3)).also { image.recycle() }
    }

    @Test fun downloadsSettingsAndPinScreenFollowTheTheme() {
        for (dark in listOf(false, true)) {
            instrumentation.runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
            val theme = if (dark) "dark" else "light"
            ActivityScenario.launch(DownloadsActivity::class.java).use { scenario ->
                lateinit var root: View
                var surface = 0
                scenario.onActivity { activity ->
                    assertEquals(dark, ThemeMode.isDark(activity))
                    root = activity.window.decorView
                    surface = MaterialColors.getColor(root, android.R.attr.colorBackground)
                    // Dark text and bar icons on light surfaces, light ones on dark surfaces.
                    assertEquals(!dark, androidx.core.view.WindowCompat.getInsetsController(activity.window, root).isAppearanceLightStatusBars)
                }
                val downloads = capture("theme-downloads-$theme", root, surface)
                assertTrue("Downloads $theme: $downloads", if (dark) downloads < 0.1 else downloads > 0.8)

                lateinit var form: SettingsSheetBinding
                var sheet = 0
                scenario.onActivity { activity ->
                    form = SettingsSheetBinding.inflate(LayoutInflater.from(activity))
                    form.brandBadge.visibility = View.GONE; form.credentials.visibility = View.GONE; form.moreOptions.visibility = View.GONE
                    form.securitySection.visibility = View.GONE; form.subtitle.visibility = View.GONE
                    form.title.setText(R.string.settings); form.accountStatus.setText(R.string.account_saved)
                    form.languages.check(R.id.russian); form.themes.check(if (dark) R.id.theme_dark else R.id.theme_light)
                    sheet = MaterialColors.getColor(form.root, com.google.android.material.R.attr.colorSurfaceContainerLow)
                    activity.setContentView(FrameLayout(activity).apply { addView(form.root) })
                }
                scenario.onActivity {
                    assertEquals(3, form.themes.childCount)
                    assertTrue(form.themes.isSingleSelection && form.themes.isSelectionRequired)
                    // The choice and its labels are readable: text contrast of at least 4.5:1 on the sheet.
                    val label = form.themeLabel.currentTextColor
                    assertTrue(ColorUtils.calculateContrast(label, sheet) >= 4.5)
                    assertTrue(ColorUtils.calculateContrast(form.mobileLayout.currentTextColor, sheet) >= 4.5)
                }
                val settings = capture("theme-settings-$theme", form.root, sheet)
                assertTrue("Settings $theme: $settings", if (dark) settings < 0.1 else settings > 0.8)

                lateinit var pin: PinScreen
                scenario.onActivity { activity ->
                    pin = PinScreen(activity) { activity.getString(it) }
                    pin.configure(activity.getString(R.string.lock_unlock_title), null, closeable = false, biometric = true, forgotText = activity.getString(R.string.lock_forgot))
                    activity.setContentView(pin)
                }
                val lock = capture("theme-pin-$theme", pin, 0)
                assertTrue("PIN $theme: $lock", if (dark) lock < 0.1 else lock > 0.8)
            }
        }
    }
}
