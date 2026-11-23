package com.univerflow.kaznu.main

/**
 * Everything waiting for one storage-permission answer on Android 9 and older: site downloads (in the order tapped, one
 * per [key], at most [limit]) and the latest file the app writes itself. Only the first waiter asks; the answer serves all.
 */
class StorageWaiters<T>(private val limit: Int = LIMIT, private val key: (T) -> String?) {
    private val requests = mutableListOf<T>()
    private var action: (() -> Unit)? = null

    val asking: Boolean get() = action != null || requests.isNotEmpty()

    /** Adds [request]; true when nothing was waiting, so the caller asks for the permission. */
    fun add(request: T): Boolean {
        val first = !asking
        val name = key(request)
        if (requests.size < limit && (name == null || requests.none { key(it) == name })) requests += request
        return first
    }

    /** Waits [run] in place of an earlier file of the app's own; true when nothing was waiting. */
    fun await(run: () -> Unit): Boolean {
        val first = !asking
        action = run
        return first
    }

    /** Hands everything over once and forgets it. */
    fun take(): Pair<(() -> Unit)?, List<T>> = (action to requests.toList()).also { action = null; requests.clear() }

    /** Site downloads only: a file of the app's own is not kept across a recreated Activity. */
    fun pending(): List<T> = requests.toList()

    fun restore(saved: List<T>) { requests.clear(); saved.take(limit).forEach(::add) }

    fun clear() { action = null; requests.clear() }

    companion object {
        const val LIMIT = 20
    }
}
