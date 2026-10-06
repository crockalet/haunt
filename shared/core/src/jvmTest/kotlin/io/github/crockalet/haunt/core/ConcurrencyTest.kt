package io.github.crockalet.haunt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Hammers the controller from many real threads; the published state must match the last command. */
class ConcurrencyTest {
    @Test
    fun commandsFromManyThreadsKeepStateConsistent() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = DefaultHauntController(scope, tickInterval = 1.milliseconds)
            val route = Route(listOf(LatLng(0.0, 0.0), LatLng(0.01, 0.0)))
            val threads = 8
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            repeat(threads) { t ->
                pool.execute {
                    start.await()
                    try {
                        repeat(2_000) { i ->
                            when ((i + t) % 7) {
                                0 -> controller.setLocation(LatLng(t.toDouble(), i % 90.0))
                                1 -> controller.playRoute(route, Speed.Drive, LoopMode.PingPong)
                                2 -> controller.pause()
                                3 -> controller.resume()
                                4 -> controller.setSpeed(Speed(i % 30.0))
                                5 -> controller.startJoystick(Speed.Walk, LatLng(1.0, 1.0))
                                else -> controller.joystickInput(i.toDouble(), 0.5)
                            }
                        }
                    } catch (e: Throwable) {
                        errors += e
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            assertTrue(errors.isEmpty(), errors.joinToString())

            controller.setLocation(LatLng(12.0, 34.0), label = "final")
            val state = controller.state.value
            assertTrue(state is HauntState.Holding && state.label == "final", "state was $state")
            controller.stop()
            assertEquals(HauntState.Idle, controller.state.value)
        } finally {
            scope.cancel()
        }
    }
}
