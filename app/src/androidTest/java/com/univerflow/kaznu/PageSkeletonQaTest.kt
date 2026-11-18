package com.univerflow.kaznu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.ui.PageSkeleton
import com.univerflow.kaznu.web.PageShape
import com.univerflow.kaznu.web.PageShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Draws the loading placeholder of every student page at the phone's width into qa/skeleton-*.png, to lay beside a
 * screenshot of the loaded page. Each drawing must differ from the plain list, so no page silently falls back to it.
 */
@RunWith(AndroidJUnit4::class)
class PageSkeletonQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun render(url: String, tiles: Int = 6, widthDp: Int? = null): Bitmap {
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val metrics = context.resources.displayMetrics
            val view = PageSkeleton(context).apply { this.tiles = tiles; show(url) }
            val width = widthDp?.let { (it * metrics.density).toInt() } ?: metrics.widthPixels
            val height = (metrics.heightPixels * .86f).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun save(name: String, bitmap: Bitmap) {
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(folder, "skeleton-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyStudentPageDrawsItsOwnPlaceholder() {
        val list = render("https://univer.kaznu.kz/no/such/page/")
        save("list", list)
        PageShape.entries.filter { it != PageShape.LIST }.forEach { shape ->
            val url = PAGES.getValue(shape)
            assertEquals(url, shape, PageShapes.of(url))
            val bitmap = render(url)
            save(shape.name.lowercase(), bitmap)
            assertFalse(shape.name, bitmap.sameAs(list))
            bitmap.recycle()
        }
        // The home placeholder ends where the student's own tiles do.
        val more = render("https://univer.kaznu.kz/", tiles = 9)
        save("news-9-tiles", more)
        assertFalse(more.sameAs(render("https://univer.kaznu.kz/")))
    }

    /** A 320 dp phone: fixed-width chips and buttons are squeezed in, so nothing is drawn in the right margin. */
    @Test fun onANarrowPhoneNoPlaceholderRunsIntoTheRightMargin() {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        PageShape.entries.forEach { shape ->
            val bitmap = render(PAGES[shape] ?: "https://univer.kaznu.kz/no/such/page/", widthDp = 320)
            if (shape == PageShape.CALENDAR || shape == PageShape.CONTRACTS) save(shape.name.lowercase() + "-320", bitmap)
            val ground = bitmap.getPixel(0, 0)
            val margin = (8 * density).toInt()
            // The journal's course chips scroll sideways on the page, so their row runs into the edge on purpose.
            val sideways = if (shape == PageShape.JOURNAL) (271 * density).toInt()..(315 * density).toInt() else IntRange.EMPTY
            val drawn = (bitmap.width - margin until bitmap.width).any { x ->
                (0 until bitmap.height).any { y -> y !in sideways && bitmap.getPixel(x, y) != ground }
            }
            assertFalse(shape.name + " draws into the right margin", drawn)
            bitmap.recycle()
        }
    }

    private companion object {
        val PAGES = mapOf(
            PageShape.NEWS to "https://univer.kaznu.kz/news/student/1/", PageShape.BACHELOR to "https://univer.kaznu.kz/student/bachelor/",
            PageShape.SERVICES to "https://univer.kaznu.kz/ssc/", PageShape.NEWS_LIST to "https://univer.kaznu.kz/student/monrknews/",
            PageShape.FAQ to "https://univer.kaznu.kz/my/faq/", PageShape.CATALOG to "https://univer.kaznu.kz/my/docs/",
            PageShape.MESSAGES to "https://univer.kaznu.kz/my/messages/index/", PageShape.CALCULATOR to "https://univer.kaznu.kz/my/calculatorgpa/",
            PageShape.APPEALS to "https://univer.kaznu.kz/my/appeals/index/", PageShape.SURVEYS to "https://univer.kaznu.kz/student/rating/",
            PageShape.SURVEY to "https://univer.kaznu.kz/student/rating/208", PageShape.QUESTIONS to "https://univer.kaznu.kz/student/rating/208/1/",
            PageShape.FORM to "https://univer.kaznu.kz/student/mydata/edit/", PageShape.PRACTICE to "https://univer.kaznu.kz/student/practik/indexlist/",
            PageShape.ADVISOR to "https://univer.kaznu.kz/student/advicer/", PageShape.CONTRACTS to "https://univer.kaznu.kz/student/contract/list/",
            PageShape.CALENDAR to "https://univer.kaznu.kz/student/academcalendar/", PageShape.EXAMS to "https://univer.kaznu.kz/student/myexam/schedule/",
            PageShape.MIDTERM to "https://univer.kaznu.kz/student/mymidterm/schedule/", PageShape.PLAN to "https://univer.kaznu.kz/student/educplan/",
            PageShape.ATTESTATION to "https://univer.kaznu.kz/student/attestation/", PageShape.PROFILE to "https://univer.kaznu.kz/student/profile/",
            PageShape.IGPA to "https://univer.kaznu.kz/student/stud/rating/anket/", PageShape.TESTS to "https://univer.kaznu.kz/student/TestList",
            PageShape.DEBTS to "https://univer.kaznu.kz/student/fail/", PageShape.RETAKES to "https://univer.kaznu.kz/student/failfx/",
            PageShape.RAISE to "https://univer.kaznu.kz/student/gpaincrease/", PageShape.SCHEDULE to "https://univer.kaznu.kz/student/myschedule/",
            PageShape.JOURNAL to "https://univer.kaznu.kz/student/attendance/full/", PageShape.MATERIALS to "https://univer.kaznu.kz/student/umkd/",
            PageShape.DISTANCE to "https://univer.kaznu.kz/student/discussion/index/",
        )
    }
}
