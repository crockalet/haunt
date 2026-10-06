package io.github.crockalet.haunt.android.inject

import io.github.crockalet.haunt.core.Fix

/**
 * Pushes [Fix]es into the platform (DESIGN §5). Implementations throw [SecurityException] from
 * [start] / [push] when Haunt isn't the selected mock location app.
 */
interface LocationInjector {
    /** Registers providers / enables mock mode. Idempotent. */
    fun start()

    /** Injects one fix (called ~1 Hz). */
    fun push(fix: Fix)

    /** Removes providers / disables mock mode. Idempotent; never throws. */
    fun stop()
}

/** Runs several injectors together; [start] rolls back on failure. */
class CompositeInjector(private val injectors: List<LocationInjector>) : LocationInjector {
    override fun start() {
        val started = mutableListOf<LocationInjector>()
        try {
            for (injector in injectors) {
                injector.start()
                started += injector
            }
        } catch (e: Exception) {
            started.forEach { it.stop() }
            throw e
        }
    }

    override fun push(fix: Fix) = injectors.forEach { it.push(fix) }

    override fun stop() = injectors.forEach { runCatching { it.stop() } }
}
