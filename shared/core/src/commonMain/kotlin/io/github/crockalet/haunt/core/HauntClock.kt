package io.github.crockalet.haunt.core

import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * Time for the movement engine: a wall clock for [Fix.timeMillis] and a monotonic source for
 * measuring how far to move between ticks. Inject a fake in tests (see [HauntClock.of]).
 */
interface HauntClock {
    /** Wall-clock time, epoch milliseconds (stamped on every [Fix]). */
    fun nowMillis(): Long

    /** Monotonic mark used to measure elapsed time between ticks. */
    fun markNow(): ComparableTimeMark

    companion object {
        /** Real time: `kotlin.time.Clock.System` + `TimeSource.Monotonic`. */
        @OptIn(ExperimentalTime::class)
        val System: HauntClock = object : HauntClock {
            override fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
            override fun markNow(): ComparableTimeMark = TimeSource.Monotonic.markNow()
        }

        /**
         * A clock driven entirely by [timeSource]; wall time is [epochMillis] plus the time elapsed
         * on [timeSource] since this call. With kotlinx-coroutines-test use
         * `HauntClock.of(testScheduler.timeSource, epoch)` for fully deterministic virtual time.
         */
        fun of(timeSource: TimeSource.WithComparableMarks, epochMillis: Long = 0L): HauntClock =
            object : HauntClock {
                private val origin = timeSource.markNow()
                override fun nowMillis(): Long = epochMillis + origin.elapsedNow().inWholeMilliseconds
                override fun markNow(): ComparableTimeMark = timeSource.markNow()
            }
    }
}

/** Tunable defaults applied to every emitted [Fix] unless a command overrides them. */
data class HauntDefaults(
    /** Horizontal accuracy (metres) reported on fixes. */
    val accuracy: Float = 5f,
    /** Altitude (metres) when neither the command nor the route provides one; null = none. */
    val altitude: Double? = null,
)
