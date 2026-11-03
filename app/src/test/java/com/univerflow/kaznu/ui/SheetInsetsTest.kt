package com.univerflow.kaznu.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetInsetsTest {
    @Test fun keyboardHeightIsNotAppliedASecondTimeWhenTheSheetAlreadyPaddedIt() {
        assertEquals("The sheet padded the keyboard itself: no extra blank block", 0, missingBottomInset(needed = 900, alreadyApplied = 900))
    }
    @Test fun missingPartIsStillAddedWhenTheSheetPaddedLess() {
        assertEquals(48, missingBottomInset(needed = 900, alreadyApplied = 852))
        assertEquals("Nothing was applied: the whole inset is needed", 132, missingBottomInset(needed = 132, alreadyApplied = 0))
    }
    @Test fun anAlreadyLargerPaddingNeverGivesNegativeValues() {
        assertEquals(0, missingBottomInset(needed = 100, alreadyApplied = 300))
    }
}
