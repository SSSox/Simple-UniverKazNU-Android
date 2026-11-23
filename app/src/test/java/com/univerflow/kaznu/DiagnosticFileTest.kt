package com.univerflow.kaznu

import com.univerflow.kaznu.domain.DiagnosticFile
import java.util.Date
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFileTest {
    private val login = "student.ivanov"
    private val cookie = "ASP.NET_SessionId=abcd1234efgh5678ijkl"
    private val tokenUrl = "https://univer.kaznu.kz/student/attentionStudents/123456?token=SECRETTOKENVALUE99&sid=77"

    @Test fun `cookies, token addresses, e-mail and phone numbers never reach the file`() {
        val text = DiagnosticFile.build(listOf(
            "App" to "1.2.3 (40)",
            "Header" to "Cookie: $cookie",
            "Page" to tokenUrl,
            "Mail" to "$login@kaznu.kz phone +7 701 123 45 67",
            "Device" to "Pixel 8"), Date(0))
        listOf(login, "abcd1234efgh5678ijkl", "SECRETTOKENVALUE99", "123456", "token=", "701 123").forEach {
            assertFalse("leaked: $it\n$text", text.contains(it))
        }
        assertTrue(text.contains("Device: Pixel 8"))
        assertTrue(text.contains("App: 1.2.3 (40)"))
        assertTrue(text.contains("univer.kaznu.kz/student/attentionStudents/{n}"))
    }

    // The login and password are never given to the file, so no flag or word turns into a mark that would show them.
    @Test fun `flags and words stay as they are whatever the password is`() {
        val text = DiagnosticFile.build(listOf("Saved credentials" to "yes", "Build type" to "release", "Device" to "samsung SM-A515F"), Date(0))
        assertTrue(text, text.contains("Saved credentials: yes\nBuild type: release\nDevice: samsung SM-A515F"))
        assertFalse(text.contains("<hidden>"))
    }

    @Test fun `file name has the date and a txt extension`() {
        assertTrue(Regex("Univer-diagnostics-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.txt").matches(DiagnosticFile.fileName(Date(0))))
    }

    @Test fun `control characters and long lines are cut`() {
        val line = DiagnosticFile.clean("a\nb" + "x".repeat(2000))
        assertFalse(line.contains('\n'))
        assertTrue(line.length <= DiagnosticFile.MAX_LINE)
    }
}
