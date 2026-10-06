package io.github.crockalet.haunt.android.data

import io.github.crockalet.haunt.protocol.RpcError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where a logged call came from. */
enum class ActivitySource { Socket, Broadcast }

/**
 * One entry of the Agent activity log (DESIGN C3): a call made over ADB.
 *
 * @property summary short human-readable rendering of the params (e.g. `lat=35.6, lng=139.7`), may be empty.
 * @property errorCode / [errorMessage] are null on success.
 */
data class ActivityEntry(
    val timeMillis: Long,
    val source: ActivitySource,
    val method: String,
    val summary: String,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
) {
    val ok: Boolean get() = errorCode == null
}

/** Ring buffer of the most recent [capacity] ADB calls, newest last. Thread-safe. */
class ActivityLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _entries = MutableStateFlow<List<ActivityEntry>>(emptyList())

    /** Most recent entries, oldest first. */
    val entries: StateFlow<List<ActivityEntry>> = _entries.asStateFlow()

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    fun record(source: ActivitySource, method: String, summary: String = "", error: RpcError? = null) {
        val entry = ActivityEntry(clock(), source, method, summary.take(MAX_SUMMARY), error?.code, error?.message)
        _entries.update { (it + entry).takeLast(capacity) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
        private const val MAX_SUMMARY = 200
    }
}
