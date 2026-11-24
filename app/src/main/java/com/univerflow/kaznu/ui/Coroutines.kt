package com.univerflow.kaznu.ui

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [block]'s result, or [fallback] when it fails. Cancellation is never a failure: a closing screen or a newer request
 * ends the caller as well, so it is thrown on instead of being turned into [fallback].
 */
suspend fun <T> attempt(fallback: T, block: suspend () -> T): T =
    try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { fallback }

/**
 * [block] with a deadline of its own. Running out of time is a failure of the work, a [TimeoutException] the caller
 * reports like any other. withTimeout's own exception is a CancellationException instead, which every caller here
 * passes on as "the screen is closing", so the failure would end the work without a word to the student.
 * Cancellation of the caller itself is still passed on.
 */
suspend fun <T> withDeadline(millis: Long, block: suspend CoroutineScope.() -> T): T {
    var finished = false
    var value: T? = null
    withTimeoutOrNull(millis) { value = block(); finished = true }
    if (!finished) throw TimeoutException("No answer in $millis ms")
    @Suppress("UNCHECKED_CAST")
    return value as T
}

/**
 * Runs [block] in this scope. A failure goes to [onFailure] (a message to the student, usually) instead of ending the
 * app; cancellation ends it quietly.
 */
fun CoroutineScope.launchCatching(onFailure: (Exception) -> Unit = {}, block: suspend () -> Unit): Job =
    launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { onFailure(failure) }
    }

/**
 * Runs [block] for as long as this screen lives. A failure goes to [onFailure] (a message to the student, usually)
 * instead of ending the app; cancellation, when the screen closes, ends it quietly.
 */
fun LifecycleOwner.launchCatching(onFailure: (Exception) -> Unit = {}, block: suspend () -> Unit): Job =
    lifecycleScope.launchCatching(onFailure, block)
