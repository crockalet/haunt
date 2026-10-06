package io.github.crockalet.haunt.android.inject

import android.util.Log
import io.github.crockalet.haunt.core.HauntController
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.currentFix
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether fixes are currently reaching the platform. */
sealed interface InjectionStatus {
    /** Not mocking (controller idle). */
    data object Inactive : InjectionStatus

    /** Test providers registered; fixes are being injected. */
    data object Active : InjectionStatus

    /** The platform refused injection (Haunt isn't the selected mock location app). Mocking was stopped. */
    data class Denied(val message: String) : InjectionStatus

    /** Injection failed for another reason. Mocking was stopped. */
    data class Failed(val message: String) : InjectionStatus
}

/**
 * Pushes the controller's fixes into a [LocationInjector] while the controller isn't Idle:
 * starts the injector (registering test providers) when mocking starts, re-injects the current
 * fix right away, then every tick; stops it (removing the providers) when the controller goes Idle.
 *
 * Owned by the process-wide runtime rather than a service, so injection never depends on whether a
 * foreground service could be started; `HauntService` keeps the process alive and shows the
 * notification. All injector calls run on [dispatcher] (one at a time).
 *
 * On [SecurityException] (mock app deselected) the controller is stopped and [onDenied] is called.
 */
class InjectionPipeline(
    scope: CoroutineScope,
    private val controller: HauntController,
    private val dispatcher: CoroutineDispatcher,
    injectorFactory: () -> LocationInjector,
    private val onDenied: (InjectionStatus) -> Unit = {},
) {
    private val injector by lazy(injectorFactory)
    private val _status = MutableStateFlow<InjectionStatus>(InjectionStatus.Inactive)

    val status: StateFlow<InjectionStatus> = _status.asStateFlow()

    init {
        scope.launch(dispatcher) {
            controller.state.map { it !is HauntState.Idle }.distinctUntilChanged().collectLatest { active ->
                if (active) runInjection()
            }
        }
    }

    private suspend fun runInjection() {
        try {
            injector.start()
            _status.value = InjectionStatus.Active
            controller.state.value.currentFix?.let(injector::push)
            controller.fixes.collect { injector.push(it) }
        } catch (e: SecurityException) {
            fail(InjectionStatus.Denied(e.message ?: "Mock location not allowed"), e)
        } catch (e: IllegalArgumentException) {
            fail(InjectionStatus.Failed(e.message ?: e.toString()), e)
        } catch (e: IllegalStateException) {
            fail(InjectionStatus.Failed(e.message ?: e.toString()), e)
        } finally {
            withContext(NonCancellable) {
                runCatching { injector.stop() }
                if (_status.value == InjectionStatus.Active) _status.value = InjectionStatus.Inactive
            }
        }
    }

    private fun fail(status: InjectionStatus, e: Exception) {
        Log.w(TAG, "Injection stopped: $status", e)
        _status.value = status
        onDenied(status)
        controller.stop()
    }

    private companion object {
        const val TAG = "HauntInjection"
    }
}
