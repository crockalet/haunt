package io.github.crockalet.haunt.core

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** A WGS84 coordinate in decimal degrees. */
@Serializable
data class LatLng(val lat: Double, val lng: Double)

/** One location sample handed to the platform injector. */
@Serializable
data class Fix(
    val position: LatLng,
    val altitude: Double? = null,
    val accuracy: Float = 5f,
    val bearing: Float? = null,
    val speed: Float? = null,
    val timeMillis: Long,
)

@Serializable
data class Route(
    val points: List<LatLng>,
    /** Per-point timestamps from a recorded track (GPX); null for drawn routes. */
    val timestampsMillis: List<Long>? = null,
    val name: String? = null,
)

@Serializable
enum class LoopMode { Once, Loop, PingPong }

@Serializable
@JvmInline
value class Speed(val metersPerSecond: Double) {
    val kmh: Double get() = metersPerSecond * 3.6

    companion object {
        val Walk = Speed(1.4)
        val Cycle = Speed(5.0)
        val Drive = Speed(13.9)
        fun kmh(value: Double) = Speed(value / 3.6)
    }
}

@Serializable
data class RouteProgress(
    val traveledMeters: Double,
    val totalMeters: Double,
    val etaSeconds: Long?,
)

/** Everything the UI and agents need to know about what Haunt is doing right now. */
@Serializable
sealed interface HauntState {
    @Serializable
    data object Idle : HauntState

    /** Holding a fixed fake location (pin mode). */
    @Serializable
    data class Holding(val fix: Fix, val label: String? = null) : HauntState

    /** Playing a route or track. */
    @Serializable
    data class Moving(
        val fix: Fix,
        val routeName: String?,
        val progress: RouteProgress,
        val speed: Speed,
        val loop: LoopMode,
        val paused: Boolean,
    ) : HauntState

    /** Driven by the on-screen joystick. */
    @Serializable
    data class Joystick(
        val fix: Fix,
        val maxSpeed: Speed,
        val headingDeg: Double,
        val distanceMeters: Double,
    ) : HauntState
}

val HauntState.currentFix: Fix?
    get() = when (this) {
        HauntState.Idle -> null
        is HauntState.Holding -> fix
        is HauntState.Moving -> fix
        is HauntState.Joystick -> fix
    }
