package com.univerflow.kaznu.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LinkFileLabelTest {
    @Test fun theSiteStaysInFrontOfALongFileName() {
        val host = "drive.google.com"
        val raw = "lecture-notes-final-version-2026.pdf"
        val name = LinkFileLabel.visibleName(raw)!!
        assertTrue(name.length < raw.length)
        assertEquals(LinkFileLabel.NAME_LIMIT, name.codePointCount(0, name.length))
        assertTrue(name.startsWith("lecture"))
        assertTrue(name.endsWith(".pdf"))
        assertTrue(name.contains("…"))
        assertTrue(raw.startsWith(name.substringBefore("…")))
        assertTrue(raw.endsWith(name.substringAfter("…")))
        listOf("values", "values-en", "values-kk").forEach { locale ->
            val named = resource(locale, "link_file")
            val unnamed = resource(locale, "link_file_unnamed")
            assertTrue(locale, named.startsWith("%1\$s"))
            assertTrue(locale, named.indexOf("%1\$s") < named.indexOf("%2\$s"))
            assertTrue(locale, unnamed.startsWith("%1\$s"))
            assertFalse(locale, unnamed.contains("%2\$s"))
            val text = named.format(host, name)
            assertTrue(text, text.startsWith(host))
            assertTrue(text, text.indexOf(host) < text.indexOf(name))
            assertTrue(unnamed.format(host).startsWith(host))
        }
    }

    @Test fun aNameThatFitsIsKeptAndABlankOneIsDropped() {
        val exact = "a".repeat(LinkFileLabel.NAME_LIMIT)
        assertEquals(exact, LinkFileLabel.visibleName(exact))
        assertEquals("a.pdf", LinkFileLabel.visibleName("a.pdf"))
        assertNull(LinkFileLabel.visibleName(null))
        assertNull(LinkFileLabel.visibleName(" \u0000\u2028 "))
    }

    @Test fun lineBreaksAndBidirectionalMarksCannotMoveTheSite() {
        val name = LinkFileLabel.visibleName("report\u202Epdf.exe\u2028\u2029")!!
        assertEquals("reportpdf.exe", name)
        assertFalse(name.any { it.isISOControl() || it == '\u2028' || it == '\u2029' || it == '\u202E' })
        val host = LinkFileLabel.visibleHost("drive.google.com\u202E\u200F.evil.test")
        assertEquals("drive.google.com.evil.test", host)
        assertTrue(resource("values", "link_file").format(host, name).startsWith(host))
    }

    @Test fun everyInvisibleFormatMarkIsLeftOutByCodePoint() {
        val marks = listOf(0x2061, 0x2062, 0x2063, 0x2064, 0x206A, 0x206B, 0x206C, 0x206D, 0x206E, 0x206F,
            0xFFF9, 0xFFFA, 0xFFFB, 0xE0000, 0xE0002, 0xE0001, 0xE0020, 0xE0041, 0xE007F, 0x200E, 0x202E, 0x2066, 0x0085, 0x0007)
        marks.forEach { point ->
            val mark = String(Character.toChars(point))
            assertEquals(Integer.toHexString(point), "example.org", LinkFileLabel.visibleHost("exa${mark}mple.org"))
            assertEquals(Integer.toHexString(point), "ab.pdf", LinkFileLabel.visibleName("a${mark}b.pdf"))
        }
        // A whole tag sequence after a name vanishes; a lone surrogate is not left behind either.
        val tags = "󠁴󠁥󠁸󠁴󠁿"
        assertEquals("a.pdf", LinkFileLabel.visibleName("a.pdf$tags"))
        assertEquals("a.pdf", LinkFileLabel.visibleName("a\uD800.pdf\uDC00"))
        // Visible letters outside the first plane stay whole.
        assertEquals("𝐀.pdf", LinkFileLabel.visibleName("𝐀.pdf"))
        assertNull(LinkFileLabel.visibleName(tags))
    }

    @Test fun aLongHostKeepsItsLastLabels() {
        val tail = "evil.test"
        val host = "docs.google.com\u202E\u200F." + "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + ".$tail"
        assertTrue(host.length > 200)
        val shown = LinkFileLabel.visibleHost(host)
        assertTrue(shown, shown.startsWith("…"))
        assertTrue(shown, shown.endsWith(tail))
        assertFalse(shown, shown.contains("docs"))
        assertFalse(shown, shown.contains("google"))
        assertFalse(shown, shown.contains("a".repeat(10)))
        assertTrue(shown, shown.codePointCount(0, shown.length) <= LinkFileLabel.HOST_LIMIT)
        assertFalse(shown.any { it == '\u202E' || it == '\u200F' })
        val labels = host.replace("\u202E", "").replace("\u200F", "").split('.')
        val kept = shown.removePrefix("…").split('.')
        assertEquals(labels.takeLast(kept.size), kept)
        val blob = "docs.google.com" + "c".repeat(200)
        val cut = LinkFileLabel.visibleHost(blob)
        assertTrue(blob.length > 200)
        assertTrue(cut.startsWith("…"))
        assertTrue(cut.endsWith("c".repeat(20)))
        assertFalse(cut.contains("docs"))
        assertTrue(wellFormed(cut))
        assertEquals(LinkFileLabel.HOST_LIMIT, cut.codePointCount(0, cut.length))
    }

    @Test fun theConfirmationSaysTheLinkOpensInAnotherApp() {
        mapOf("values" to "Откроется вне приложения.", "values-en" to "Opens in another app.",
            "values-kk" to "Басқа қолданбада ашылады.").forEach { (locale, sentence) ->
            assertTrue(locale, resource(locale, "link_file").endsWith(sentence))
            assertTrue(locale, resource(locale, "link_file_unnamed").endsWith(sentence))
        }
    }

    @Test fun theConfirmationFitsOnThreeLines() {
        // 14sp at font scale 1.15 beside «Открыть» on a 360dp phone: about 27 letters on each of 3 lines.
        val budget = 27 * 3
        val host = LinkFileLabel.visibleHost("h".repeat(LinkFileLabel.HOST_LIMIT))
        val name = LinkFileLabel.visibleName("n".repeat(LinkFileLabel.NAME_LIMIT))!!
        assertEquals(LinkFileLabel.HOST_LIMIT, host.codePointCount(0, host.length))
        assertEquals(LinkFileLabel.NAME_LIMIT, name.codePointCount(0, name.length))
        listOf("values", "values-en", "values-kk").forEach { locale ->
            val named = resource(locale, "link_file").format(host, name)
            val unnamed = resource(locale, "link_file_unnamed").format(host)
            assertTrue(named, named.codePointCount(0, named.length) <= budget)
            assertTrue(unnamed, unnamed.codePointCount(0, unnamed.length) <= budget)
            assertTrue(named.startsWith(host))
            assertTrue(unnamed.startsWith(host))
        }
    }

    @Test fun shorteningANameDoesNotSplitACharacter() {
        val page = "\uD83D\uDCC4"
        val over = page.repeat(LinkFileLabel.NAME_LIMIT + 1)
        val cut = LinkFileLabel.visibleName(over)!!
        assertTrue(wellFormed(cut))
        assertEquals(LinkFileLabel.NAME_LIMIT, cut.codePointCount(0, cut.length))
        assertEquals(page.repeat(LinkFileLabel.NAME_LIMIT), LinkFileLabel.visibleName(page.repeat(LinkFileLabel.NAME_LIMIT)))
    }

    private fun wellFormed(value: String): Boolean = value.indices.all { index ->
        val ch = value[index]
        when {
            ch.isHighSurrogate() -> index + 1 < value.length && value[index + 1].isLowSurrogate()
            ch.isLowSurrogate() -> index > 0 && value[index - 1].isHighSurrogate()
            else -> true
        }
    }

    private fun resource(locale: String, name: String): String {
        val file = sequenceOf(File("src/main/res/$locale/strings.xml"), File("app/src/main/res/$locale/strings.xml")).first { it.isFile }
        val match = Regex("""<string name="$name">([^<]*)</string>""").find(file.readText())
        return match!!.groupValues[1]
    }
}
