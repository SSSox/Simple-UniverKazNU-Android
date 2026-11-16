package com.univerflow.kaznu

import com.univerflow.kaznu.domain.StudyFolders
import org.junit.Assert.*
import org.junit.Test

class StudyFoldersTest {
    @Test fun materialNamesStayUnderOneCourseAndSection() {
        assertEquals("Univer УМКД/Алгоритмы/Конспекты лекций", StudyFolders.relative("Алгоритмы", "Конспекты лекций"))
        assertEquals(StudyFolders.ROOT, StudyFolders.relative("..", "../outside"))
        val path = StudyFolders.relative("../../ курс", "../../описание: вопросы?")
        assertEquals(path, StudyFolders.checked(path))
        assertFalse(path.substringAfter('/').contains("../"))
    }
    @Test fun storageRejectsArbitraryPathsAndBoundsSurrogatePairs() {
        listOf("../secret", "/Download/other", "Univer УМКД/../other", "Univer УМКД/one/two/three", "Univer УМКД/one\\two").forEach {
            assertNull(it, StudyFolders.checked(it))
        }
        val name = StudyFolders.name("😀".repeat(50))
        assertTrue(name.length <= 64)
        assertFalse(name.last().isHighSurrogate())
    }
}
