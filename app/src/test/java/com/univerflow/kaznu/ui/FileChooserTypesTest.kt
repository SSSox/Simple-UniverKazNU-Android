package com.univerflow.kaznu.ui

import org.junit.Assert.*
import org.junit.Test

class FileChooserTypesTest {
    private val known = mapOf("pdf" to "application/pdf", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "jpg" to "image/jpeg")
    private fun types(vararg accept: String) = FileChooserTypes.mimeTypes(arrayOf(*accept)) { known[it] }

    @Test fun moodleExtensionListsBecomeMimeTypes() {
        assertEquals(listOf("application/pdf", known["docx"]), types(".pdf,.docx"))
        assertEquals(listOf("application/pdf", "image/jpeg"), types(".PDF", "image/jpeg", ".jpg"))
        assertEquals(listOf("image/*"), types("image/*"))
    }
    @Test fun anythingUnclearAllowsEveryFileAndOffersThePhoto() {
        assertEquals(emptyList<String>(), types())
        assertEquals(emptyList<String>(), types(""))
        assertEquals(emptyList<String>(), types(".pdf,.unknownext"))
        assertEquals(emptyList<String>(), types("*/*"))
        assertEquals(emptyList<String>(), types("not a type"))
        assertTrue(FileChooserTypes.acceptsPhotos(emptyList()))
        assertTrue(FileChooserTypes.acceptsPhotos(types("image/*")))
        assertFalse(FileChooserTypes.acceptsPhotos(types(".pdf")))
    }
}
