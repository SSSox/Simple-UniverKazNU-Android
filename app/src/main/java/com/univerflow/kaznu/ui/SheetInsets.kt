package com.univerflow.kaznu.ui

/**
 * Bottom padding the sheet's content still needs: the Material sheet already pads its container by the system bars
 * and keyboard, and padding again doubled the keyboard height.
 */
fun missingBottomInset(needed: Int, alreadyApplied: Int): Int = (needed - alreadyApplied).coerceAtLeast(0)
