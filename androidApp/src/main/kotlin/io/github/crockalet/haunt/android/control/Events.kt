package io.github.crockalet.haunt.android.control

import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.protocol.HauntEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Process-wide hot stream of [HauntEvent]s; [RpcServer][io.github.crockalet.haunt.protocol.RpcServer] fans it out per connection. */
class EventHub(capacity: Int = 256) {
    private val _events = MutableSharedFlow<HauntEvent>(extraBufferCapacity = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: SharedFlow<HauntEvent> = _events.asSharedFlow()

    fun emit(event: HauntEvent) {
        _events.tryEmit(event)
    }
}

/**
 * Pure derivation of [HauntEvent.StateEvent] and throttled [HauntEvent.RouteProgressEvent] from
 * observed states. A StateEvent goes out only on a *meaningful* change (mode, place, label, speed,
 * pause, loop, playback rate) — not for every tick's fresh fix, which [HauntEvent.FixEvent] covers.
 * Not thread-safe: feed it from one collector.
 */
class StateEventDeriver(
    private val progressIntervalMillis: Long = 1000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var lastKey: Any? = null
    private var lastProgressAt: Long? = null

    fun onState(state: HauntState, routeId: String?): List<HauntEvent> {
        val events = ArrayList<HauntEvent>(2)
        val key = keyOf(state)
        val changed = key != lastKey
        if (changed) {
            lastKey = key
            events += HauntEvent.StateEvent(state)
        }
        if (state is HauntState.Moving) {
            val now = nowMillis()
            val last = lastProgressAt
            // Allow 10% jitter so a 1 s tick isn't skipped every other time.
            val due = last == null || now - last >= progressIntervalMillis * 9 / 10
            if (changed || (due && !state.paused)) {
                lastProgressAt = now
                events += HauntEvent.RouteProgressEvent(routeId, state.progress)
            }
        } else {
            lastProgressAt = null
        }
        return events
    }

    companion object {
        /** What counts as a state change for [HauntEvent.StateEvent]. */
        fun keyOf(state: HauntState): Any = when (state) {
            HauntState.Idle -> HauntState.Idle
            is HauntState.Holding -> listOf("Holding", state.fix.position, state.fix.altitude, state.fix.accuracy, state.label)
            is HauntState.Moving -> listOf("Moving", state.routeName, state.progress.totalMeters, state.speed, state.loop, state.paused, state.playbackRate)
            is HauntState.Joystick -> listOf("Joystick", state.maxSpeed, state.paused)
        }
    }
}

/**
 * Wires controller output into the [EventHub]: a FixEvent per fix, StateEvent / RouteProgressEvent
 * via [StateEventDeriver], and arrival detection via [TrackedController.onStateObserved]
 * (RouteFinishedEvents are emitted by the [TrackedController] itself).
 */
class EventPipeline(
    scope: CoroutineScope,
    private val controller: TrackedController,
    private val hub: EventHub,
    private val deriver: StateEventDeriver = StateEventDeriver(),
) {
    init {
        scope.launch { controller.fixes.collect { hub.emit(HauntEvent.FixEvent(it)) } }
        scope.launch {
            controller.state.collect { state ->
                controller.onStateObserved()
                deriver.onState(state, controller.currentRouteId).forEach(hub::emit)
            }
        }
    }
}
