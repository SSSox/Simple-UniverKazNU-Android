package com.univerflow.kaznu

import com.univerflow.kaznu.domain.DiagnosticFile
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFileTest {
    private val login = "student.ivanov"
    private val password = "Zx9!hunter2pass"
    private val cookie = "ASP.NET_SessionId=abcd1234efgh5678ijkl"
    private val tokenUrl = "https://univer.kaznu.kz/student/attentionStudents/123456?token=SECRETTOKENVALUE99&sid=77"

    @Test fun `login password cookie and token url never reach the file`() {
        val text = DiagnosticFile.build(listOf(
            "App" to "1.2.3 (40)",
            "Note" to "signed in as $login with $password",
            "Header" to "Cookie: $cookie",
            "Page" to tokenUrl,
            "Mail" to "$login@kaznu.kz phone +7 701 123 45 67",
            "Device" to "Pixel 8"), listOf(login, password), Date(0))
        listOf(login, password, "abcd1234efgh5678ijkl", "SECRETTOKENVALUE99", "123456", "token=", "701 123").forEach {
            assertFalse("leaked: $it\n$text", text.contains(it))
        }
        assertTrue(text.contains("Device: Pixel 8"))
        assertTrue(text.contains("App: 1.2.3 (40)"))
        assertTrue(text.contains("univer.kaznu.kz/student/attentionStudents/{n}"))
    }

    @Test fun `short secrets are ignored and words stay whole`() {
        assertEquals("Users: on", DiagnosticFile.clean("Users: on", listOf("a", "Use")))
        assertEquals("<hidden> x", DiagnosticFile.clean("Use x", listOf("Use")))
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
