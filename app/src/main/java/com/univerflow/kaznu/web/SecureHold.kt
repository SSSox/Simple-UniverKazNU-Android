package com.univerflow.kaznu.web

/**
 * Screenshot protection over a closing window (Moodle): it may be lifted only once the window's layer has left the
 * view tree and a frame without it has been drawn. While the layer fades it still shows the page, so lifting the flag
 * when the close starts would let a screenshot or the recents preview catch it.
 *
 * Every closed layer is held until it is gone, whatever happens meanwhile: a window opened (or a session ended) during
 * the fade voids the [release] of the closes before it, but not the hold itself, since the old layer still shows
 * through the new window's entrance. When the last of those voided layers is gone, [settled] lets the owner work the
 * flag out again for what is open now.
 */
class SecureHold(private val release: () -> Unit, private val settled: () -> Unit = {}) {
    private class Layer(val secure: Boolean) { var removed = false }

    private var turn = 0
    /** Closes up to this turn may no longer lift the flag. */
    private var voidedThrough = 0
    private val layers = LinkedHashMap<Int, Layer>()

    /** A window starts closing; the returned turn goes with its layer to [removed] and [drawn]. [secure]: its page is kept out of screenshots. */
    fun closing(secure: Boolean = true): Int {
        turn++
        layers[turn] = Layer(secure)
        return turn
    }

    /** A window opened or the session ended at once: whatever was waiting to lift the flag no longer may. */
    fun cancel() {
        voidedThrough = turn
    }

    /** The closed layer [turn] is out of the view tree; the flag waits for the next frame. */
    fun removed(turn: Int) {
        layers[turn]?.removed = true
    }

    /** A frame was drawn after the layer of [turn] was removed: once no closed layer is left, the flag can go. */
    fun drawn(turn: Int) {
        if (layers[turn]?.removed != true) return
        layers.remove(turn)
        if (layers.isNotEmpty()) return
        if (this.turn > voidedThrough) release() else settled()
    }

    /** True while a closed window whose page is kept out of screenshots still shows (fading, or not yet drawn away). */
    val holding get() = layers.values.any { it.secure }
}
