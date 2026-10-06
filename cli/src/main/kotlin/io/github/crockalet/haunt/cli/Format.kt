package io.github.crockalet.haunt.cli

import io.github.crockalet.haunt.core.Fix
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.core.LoopMode
import io.github.crockalet.haunt.core.Speed
import io.github.crockalet.haunt.protocol.HauntEvent
import io.github.crockalet.haunt.protocol.Place
import io.github.crockalet.haunt.protocol.RouteResult
import java.util.Locale
import kotlin.math.roundToLong

/** Concise, human-readable renderings. Machine output uses JSON instead. */
object Format {
    fun latLng(p: LatLng) = String.format(Locale.ROOT, "%.6f, %.6f", p.lat, p.lng)

    fun distance(m: Double): String = if (m < 1000) "${m.roundToLong()} m" else String.format(Locale.ROOT, "%.1f km", m / 1000)

    fun speed(s: Speed): String = String.format(Locale.ROOT, "%.1f km/h", s.kmh)

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m${(seconds % 60).toString().padStart(2, '0')}s"
        else -> "${seconds / 3600}h${((seconds % 3600) / 60).toString().padStart(2, '0')}m"
    }

    fun fix(f: Fix): String = buildList {
        add(latLng(f.position))
        f.altitude?.let { add("alt ${it.roundToLong()} m") }
        add("±${f.accuracy.toDouble().roundToLong()} m")
        f.speed?.let { if (it > 0f) add(speed(Speed(it.toDouble()))) }
        f.bearing?.let { add("${it.toDouble().roundToLong()}°") }
    }.joinToString(" · ")

    fun state(state: HauntState): String = when (state) {
        HauntState.Idle -> "Idle (not mocking)"
        is HauntState.Holding -> listOfNotNull("Holding", state.label, fix(state.fix)).joinToString(" · ")
        is HauntState.Moving -> {
            val p = state.progress
            val pct = if (p.totalMeters > 0) " (${(100 * p.traveledMeters / p.totalMeters).roundToLong()}%)" else ""
            listOfNotNull(
                if (state.paused) "Paused" else "Moving",
                state.routeName,
                "${distance(p.traveledMeters)} / ${distance(p.totalMeters)}$pct",
                speed(state.speed),
                p.etaSeconds?.let { "ETA ${duration(it)}" },
                when (state.loop) {
                    LoopMode.Once -> null
                    LoopMode.Loop -> "loop"
                    LoopMode.PingPong -> "ping-pong"
                },
            ).joinToString(" · ") + "\n  at " + fix(state.fix)
        }
        is HauntState.Joystick ->
            "Joystick · heading ${state.headingDeg.roundToLong()}° · max ${speed(state.maxSpeed)} · ${distance(state.distanceMeters)} so far\n  at ${fix(state.fix)}"
    }

    fun route(r: RouteResult, verb: String = "Moving"): String = buildString {
        append(verb)
        r.destination?.let { append(" to ${it.name}") }
        append(" · ${distance(r.distanceM)}")
        r.etaS?.let { append(" · ETA ${duration(it)}") }
        if (r.followRoads) append(" · following roads")
        append(" · route ${r.routeId}")
        r.warning?.let { append("\nwarning: $it") }
    }

    fun place(p: Place): String = listOfNotNull(p.name, p.address, latLng(p.position)).joinToString(" · ")

    fun event(e: HauntEvent): String = when (e) {
        is HauntEvent.FixEvent -> "fix       ${fix(e.fix)}"
        is HauntEvent.StateEvent -> "state     ${state(e.state).replace("\n  ", " · ")}"
        is HauntEvent.RouteProgressEvent -> "progress  ${distance(e.progress.traveledMeters)} / ${distance(e.progress.totalMeters)}" +
            (e.progress.etaSeconds?.let { " · ETA ${duration(it)}" } ?: "")
        is HauntEvent.RouteFinishedEvent -> "finished  ${e.reason}${e.routeId?.let { " · route $it" } ?: ""}${e.fix?.let { " · " + latLng(it.position) } ?: ""}"
        is HauntEvent.ErrorEvent -> "error     ${e.message}${e.hint?.let { " ($it)" } ?: ""}"
    }
}
