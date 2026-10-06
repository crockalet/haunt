package io.github.crockalet.haunt.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.github.crockalet.haunt.core.HauntState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

/**
 * Lightweight foreground service hosting the ADB control socket ([HauntRuntime.controlServer]).
 * The CLI starts it with
 * `adb shell am start-foreground-service -n io.github.crockalet.haunt/.android.ControlService`
 * ([io.github.crockalet.haunt.protocol.Protocol.CONTROL_SERVICE_COMPONENT]).
 *
 * It calls `startForeground` immediately (type `specialUse` on API 34+, sharing [HauntService]'s
 * notification), keeps running while clients are connected or mocking is active, and stops itself
 * after [IDLE_TIMEOUT] otherwise. When "Allow ADB control" is off the socket still accepts, but every
 * call fails with ADB_CONTROL_DISABLED so the CLI can show the hint.
 */
class ControlService : Service() {
    private lateinit var runtime: HauntRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        runtime = HauntRuntime.from(this)
        goForeground()
        try {
            runtime.controlServer.start()
        } catch (e: IOException) {
            Log.e(TAG, "Could not open the control socket", e)
            stopSelf()
            return
        }
        scope.launch {
            combine(runtime.controlServer.connections, runtime.state) { clients, state -> clients > 0 || state != HauntState.Idle }
                .distinctUntilChanged()
                .collectLatest { busy ->
                    if (!busy) {
                        delay(IDLE_TIMEOUT)
                        Log.i(TAG, "Idle for $IDLE_TIMEOUT; stopping")
                        stopSelf()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        try {
            HauntNotifications.startForeground(this, runtime.buildNotification(), HauntNotifications.ForegroundType.SpecialUse)
            if (!foreground) {
                foreground = true
                runtime.onServiceForeground(true)
            }
        } catch (e: Exception) {
            // Keep serving anyway: the process may live long enough for the CLI's call.
            Log.w(TAG, "startForeground failed", e)
        }
    }

    override fun onDestroy() {
        runtime.controlServer.stop()
        if (foreground) {
            foreground = false
            runtime.onServiceForeground(false)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "HauntControlService"
        val IDLE_TIMEOUT = 3.minutes
    }
}
