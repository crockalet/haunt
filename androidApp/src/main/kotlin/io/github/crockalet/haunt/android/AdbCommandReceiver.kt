package io.github.crockalet.haunt.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.crockalet.haunt.android.control.BroadcastCommands
import io.github.crockalet.haunt.android.control.summarizeExtras
import io.github.crockalet.haunt.android.data.ActivitySource
import io.github.crockalet.haunt.protocol.ErrorCodes
import io.github.crockalet.haunt.protocol.RpcError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Broadcast fallback channel (DESIGN §6.2), for agents without the CLI:
 * ```
 * adb shell am broadcast -n io.github.crockalet.haunt/.android.AdbCommandReceiver \
 *     -a haunt.SET --ed lat 35.6586 --ed lng 139.7454
 * ```
 * Exported but guarded by `android.permission.DUMP` (held by the shell user only). Calls the same
 * ADB-gated API as the socket and returns JSON via `setResultData`, which `am broadcast` prints.
 */
class AdbCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = HauntRuntime.from(context)
        val extras = intent.extras?.let { bundle ->
            @Suppress("DEPRECATION")
            bundle.keySet().associateWith { bundle.get(it) }
        }.orEmpty()
        val pending = goAsync()
        runtime.scope.launch {
            val outcome = try {
                withTimeout(TIMEOUT_MS) { BroadcastCommands.execute(runtime.adbApi, intent.action, extras) }
            } catch (e: TimeoutCancellationException) {
                BroadcastCommands.errorOutcome(intent.action ?: "(none)", RpcError(ErrorCodes.UNAVAILABLE, "Timed out after ${TIMEOUT_MS}ms"))
            }
            runtime.activityLog.record(ActivitySource.Broadcast, outcome.method, summarizeExtras(extras), outcome.error)
            pending.resultCode = if (outcome.error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED
            pending.resultData = outcome.json
            pending.finish()
        }
    }

    private companion object {
        /** Ordered broadcasts to background receivers time out after ~10 s (foreground) / 60 s. */
        const val TIMEOUT_MS = 8_000L
    }
}
