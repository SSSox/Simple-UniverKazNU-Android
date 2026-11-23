package com.univerflow.kaznu

import com.univerflow.kaznu.main.StorageWaiters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageWaitersTest {
    @Test fun `every download tapped while the dialog is open is served by the one answer`() {
        val waiting = StorageWaiters<String> { it }
        assertTrue("the first asks", waiting.add("a.pdf"))
        assertFalse("later ones wait without asking again", waiting.add("b.pdf"))
        assertFalse(waiting.add("a.pdf"))
        var ran = 0
        assertFalse(waiting.await { ran++ })
        val (action, requests) = waiting.take()
        action?.invoke()
        assertEquals(listOf("a.pdf", "b.pdf"), requests)
        assertEquals(1, ran)
        assertFalse(waiting.asking)
        assertTrue("after the answer the next one asks again", waiting.add("c.pdf"))
    }

    @Test fun `the list is bounded and survives a recreated screen without the app's own file`() {
        val waiting = StorageWaiters<String>(limit = 3) { it }
        (1..5).forEach { waiting.add("$it") }
        assertEquals(listOf("1", "2", "3"), waiting.pending())
        waiting.await { }
        val again = StorageWaiters<String>(limit = 3) { it }
        again.restore(waiting.pending())
        assertEquals(listOf("1", "2", "3"), again.take().second)
    }
}
