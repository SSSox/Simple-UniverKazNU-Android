package com.univerflow.kaznu.web

/**
 * Where focus goes when a full-window layer (the document viewer) closes. Android-free, so the JVM tests drive it.
 *
 * Focus is given back for keyboard and switch users, who need a place to continue from. A touch user gets it back only
 * on a control that takes focus in touch mode and is not a text field or a page (a WebView refocuses the field the page
 * last had and may raise the keyboard): a tap never asked for the keyboard, so closing a window must not bring it up.
 */
object FocusReturn {
    /** Whether [requestFocus] is called on the target. [input]: a text field, or a WebView whose page may hold one. */
    fun request(touchMode: Boolean, focusableInTouchMode: Boolean, input: Boolean): Boolean =
        !touchMode || (focusableInTouchMode && !input)

    /** Whether the soft keyboard is put away after focus went back to [input] (a hardware keyboard needs none on screen). */
    fun hideKeyboard(requested: Boolean, input: Boolean): Boolean = requested && input
}
