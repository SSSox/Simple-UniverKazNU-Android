package com.univerflow.kaznu

import com.univerflow.kaznu.domain.DownloadHistoryRules
import com.univerflow.kaznu.domain.DownloadRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

class DownloadScreenRegressionTest {
    @Test fun anApprovedDeleteWaitsForTheRestoredFileAndUsesItsId() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val records = mutableMapOf<String, DownloadRecord>()
        val lookedUp = mutableListOf<String>()
        val approval = async(start = CoroutineStart.UNDISPATCHED) {
            DownloadHistoryRules.findAfterLoad("approved", { loaded.await() }) { id ->
                lookedUp += id
                records[id]
            }
        }
        assertFalse(approval.isCompleted)
        assertTrue(lookedUp.isEmpty())

        records["other"] = DownloadRecord("other", started = 2)
        val approved = DownloadRecord("approved", started = 1)
        records[approved.id] = approved
        loaded.complete(Unit)
        assertEquals(approved, approval.await())
        assertEquals(listOf("approved"), lookedUp)
        assertNull(DownloadHistoryRules.findAfterLoad("removed", { loaded.await() }, records::get))
    }

    @Test fun eachPermissionMessageIsAnnouncedBesideItsSwitchOnTheAppTab() {
        val settings = resource("layout/settings_sheet.xml")
        val app = settings.view("tab_app")
        for (id in listOf("class_reminders", "grade_alerts")) {
            val toggle = settings.view(id)
            val message = settings.view("${id}_feedback")
            assertSame(toggle.parentNode, message.parentNode)
            assertTrue(generateSequence(message.parentNode) { it.parentNode }.any { it === app })
            assertEquals("gone", message.android("visibility"))
            assertEquals("polite", message.android("accessibilityLiveRegion"))
        }
    }

    @Test fun theCoursePickerCanGrowForALongLabelAndHasA48DpMinimum() {
        val picker = resource("layout/activity_downloads.xml").view("downloads_subject")
        assertEquals("wrap_content", picker.android("layout_height"))
        val height = picker.android("minHeight")
        assertTrue(height.endsWith("dp") && height.removeSuffix("dp").toInt() >= 48)
        assertEquals("false", picker.android("singleLine"))
        assertTrue(picker.android("maxLines").isEmpty() || picker.android("maxLines").toInt() > 1)
    }

    @Test fun smallPdfAndPowerPointLabelsMeetContrastOnTheirOriginalPlates() {
        val light = resource("values/colors.xml").colors()
        val night = resource("values-night/colors.xml").colors()
        val surface = light.getValue("card_surface").rgb()
        for ((kind, original) in mapOf("kind_pdf" to "#C8342C", "kind_powerpoint" to "#C45224")) {
            val plate = light.getValue(kind)
            assertEquals(original, plate)
            val background = plate.rgb().zip(surface) { color, base -> (color * 0x24 + base * (255 - 0x24)) / 255 }
            val label = light.getValue("${kind}_text").rgb()
            val contrast = (background.luminance() + 0.05) / (label.luminance() + 0.05)
            assertTrue("$kind contrast was $contrast", contrast >= 4.5)
            assertEquals("@color/$kind", night.getValue("${kind}_text"))
        }
    }

    private fun resource(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File("src/main/res/$path")).documentElement

    private fun Element.android(name: String) = getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun Element.view(id: String): Element = elements("*").first { it.android("id") == "@+id/$id" }

    private fun Element.colors(): Map<String, String> = elements("color").associate { it.getAttribute("name") to it.textContent }

    private fun Element.elements(tag: String): List<Element> = getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun String.rgb() = removePrefix("#").chunked(2).map { it.toInt(16) }

    private fun List<Int>.luminance(): Double = zip(listOf(0.2126, 0.7152, 0.0722)).sumOf { (channel, weight) ->
        val value = channel / 255.0
        weight * if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
}
