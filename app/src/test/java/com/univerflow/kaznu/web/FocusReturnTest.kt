package com.univerflow.kaznu.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Closing the document window gives focus back without bringing the keyboard up after a tap. */
class FocusReturnTest {
    @Test fun afterATapThePageUnderTheWindowIsNotRefocused() {
        // The main WebView takes focus in touch mode, and would refocus the page's last field.
        assertFalse(FocusReturn.request(touchMode = true, focusableInTouchMode = true, input = true))
        assertFalse(FocusReturn.request(touchMode = true, focusableInTouchMode = false, input = false))
    }

    @Test fun afterATapAControlThatIsNoFieldStillGetsFocusBack() {
        assertTrue(FocusReturn.request(touchMode = true, focusableInTouchMode = true, input = false))
        assertFalse(FocusReturn.hideKeyboard(requested = true, input = false))
    }

    @Test fun keyboardNavigationAlwaysGetsFocusBackAndNeverTheOnScreenKeyboard() {
        assertTrue(FocusReturn.request(touchMode = false, focusableInTouchMode = true, input = true))
        assertTrue(FocusReturn.request(touchMode = false, focusableInTouchMode = false, input = false))
        assertTrue(FocusReturn.hideKeyboard(requested = true, input = true))
        assertFalse(FocusReturn.hideKeyboard(requested = false, input = true))
    }
}
