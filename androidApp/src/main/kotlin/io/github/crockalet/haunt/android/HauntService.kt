package io.github.crockalet.haunt.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.github.crockalet.haunt.android.overlay.JoystickOverlay
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.protocol.RpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service (type `location`) that runs while Haunt is mocking: it keeps the process alive
 * and shows the ongoing notification (place / coordinates, mode, Pause/Resume/Stop). It stops itself
 * as soon as the controller goes Idle.
 *
 * Fixes are pushed to the platform by the runtime's [io.github.crockalet.haunt.android.inject.InjectionPipeline]
 * (started the moment mocking starts), so injection never waits for, or depends on, this service.
 * Started by [HauntRuntime.startMockingService] (automatically whenever the state leaves Idle).
 * It also hosts the floating joystick ([JoystickOverlay]) while joystick mode runs in the background.
 */
class HauntService : Service() {
    private lateinit var runtime: HauntRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var foreground = false
    private lateinit var overlay: JoystickOverlay

    override fun onCreate() {
        super.onCreate()
        runtime = HauntRuntime.from(this)
        overlay = JoystickOverlay(this, runtime)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> runtime.controller.pause()
            ACTION_RESUME -> runtime.controller.resume()
            ACTION_STOP -> runtime.controller.stop()
        }
        // Always satisfy the startForegroundService() contract first, even if we stop right away.
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (runtime.state.value == HauntState.Idle) {
            shutdown()
            return START_NOT_STICKY
        }
        overlay.start(scope)
        if (watchJob == null) {
            watchJob = scope.launch {
                runtime.state.first { it == HauntState.Idle }
                shutdown()
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        HauntNotifications.startForeground(this, runtime.buildNotification(), HauntNotifications.ForegroundType.Location)
        if (!foreground) {
            foreground = true
            runtime.onServiceForeground(true)
        }
        true
    } catch (e: Exception) {
        // Android 14+: a `location` FGS needs a granted location permission (also when started via adb).
        Log.w(TAG, "startForeground(location) failed", e)
        runtime.reportError(
            RpcException.unavailable("Haunt's location service could not start: ${e.message}", HauntRuntime.LOCATION_PERMISSION_HINT),
        )
        false
    }

    private fun shutdown() {
        overlay.stop()
        if (foreground) {
            foreground = false
            runtime.onServiceForeground(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        overlay.stop()
        if (foreground) {
            foreground = false
            runtime.onServiceForeground(false)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "HauntService"
        const val ACTION_PAUSE = "io.github.crockalet.haunt.action.PAUSE"
        const val ACTION_RESUME = "io.github.crockalet.haunt.action.RESUME"
        const val ACTION_STOP = "io.github.crockalet.haunt.action.STOP"
    }
}
