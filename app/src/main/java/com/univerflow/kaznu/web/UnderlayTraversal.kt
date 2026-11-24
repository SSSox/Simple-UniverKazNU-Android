package com.univerflow.kaznu.web

/**
 * The screen under a full-window layer (the document viewer over the site's toolbar and page) leaves the Tab order and
 * TalkBack while the layer is open: the layer already takes the taps, and a key press or a swipe must not reach controls
 * that cannot be seen. Each view's own settings are kept and given back on [restore], with the control that had focus.
 *
 * [read] and [write] carry a view's accessibility importance and descendant focusability (null for a view that is not a
 * group); [hidden] is what an excluded view gets. Android-free, so the JVM tests drive it with plain objects.
 */
class UnderlayTraversal<V : Any>(
    private val read: (V) -> Traversal,
    private val write: (V, Traversal) -> Unit,
    private val hidden: (Traversal) -> Traversal,
) {
    data class Traversal(val importance: Int, val focusability: Int?)

    private val saved = LinkedHashMap<V, Traversal>()
    private var focused: V? = null

    val excluding get() = saved.isNotEmpty()

    /**
     * Takes [views] out of traversal; [focus] is the control to come back to. A view excluded already keeps the settings
     * recorded the first time, so excluding twice never records the hidden state as its own.
     */
    fun exclude(views: List<V>, focus: V?) {
        if (saved.isEmpty() || focused == null) focused = focus
        views.forEach { view ->
            if (view in saved) return@forEach
            val own = read(view)
            saved[view] = own
            write(view, hidden(own))
        }
    }

    /** Gives every view its own settings back; returns the control that had focus when the layer opened, if any. */
    fun restore(): V? {
        saved.forEach { (view, own) -> write(view, own) }
        saved.clear()
        return focused.also { focused = null }
    }
}
