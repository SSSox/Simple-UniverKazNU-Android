package com.univerflow.kaznu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.main.UpdateFlow
import com.univerflow.kaznu.ui.context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The dialog for a found update, drawn by the app's own builder in each language into qa/update-dialog-*.png: its three
 * answers (Don't show again, Later, Install) are all on screen, each label whole on one line.
 */
@RunWith(AndroidJUnit4::class)
class UpdateDialogQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun theUpdateDialogShowsItsThreeAnswersInFullInEveryLanguage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            AppLanguage.entries.forEach { language ->
                lateinit var dialog: AlertDialog
                scenario.onActivity { activity ->
                    dialog = UpdateFlow.promptDialog(activity, language.context(activity), "2.5.0", "2.4.0", {}, {}, {}).create()
                    dialog.show()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = dialog.window!!.decorView
                    val density = activity.resources.displayMetrics.density
                    listOf(AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE).forEach { which ->
                        val button = dialog.getButton(which)
                        val name = "${language.siteCode}: ${button.text}"
                        assertTrue("$name is shown", button.isShown)
                        val at = IntArray(2).also(button::getLocationInWindow)
                        assertTrue("$name stays inside the dialog", at[0] >= 0 && at[0] + button.width <= root.width)
                        assertEquals("$name is on one line", 1, button.lineCount)
                        assertEquals("$name is not cut", 0, button.layout.getEllipsisCount(0))
                        assertTrue("$name keeps a 40 dp target", button.height >= (40 * density).toInt())
                    }
                    save("update-dialog-${language.siteCode}", root)
                    dialog.dismiss()
                }
            }
        }
    }

    private fun save(name: String, view: View) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
