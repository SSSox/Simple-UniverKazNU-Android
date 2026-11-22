package com.univerflow.kaznu

import com.univerflow.kaznu.web.BviStub
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BviStubTest {
    private val script = "https://univer.kaznu.kz/Content/js/bvi.min.js"

    @Test fun readingLayoutGetsTheStandIn() {
        assertTrue(BviStub.stubs(script, "GET", readingLayout = true, cookies = "a=1; b=2"))
        assertTrue(BviStub.stubs(script.lowercase(), "GET", readingLayout = true, cookies = null))
    }

    @Test fun originalLayoutTheRealRequestAndOtherAddressesPassThrough() {
        assertFalse(BviStub.stubs(script, "GET", readingLayout = false, cookies = null))
        assertFalse(BviStub.stubs("$script?${BviStub.FULL}", "GET", readingLayout = true, cookies = null))
        assertFalse(BviStub.stubs(script, "POST", readingLayout = true, cookies = null))
        assertFalse(BviStub.stubs("http://univer.kaznu.kz/Content/js/bvi.min.js", "GET", readingLayout = true, cookies = null))
        assertFalse(BviStub.stubs("https://univer.kaznu.kz.example.com/Content/js/bvi.min.js", "GET", readingLayout = true, cookies = null))
        assertFalse(BviStub.stubs("https://univer.kaznu.kz/Content/js/bvi.min.js.map", "GET", readingLayout = true, cookies = null))
    }

    @Test fun aStudentWhoTurnedTheVersionOnKeepsTheRealScript() {
        assertFalse(BviStub.stubs(script, "GET", readingLayout = true, cookies = "x=1; bvi_panelActive=true; y=2"))
        assertTrue(BviStub.stubs(script, "GET", readingLayout = true, cookies = "bvi_panelActive=false"))
    }
}
