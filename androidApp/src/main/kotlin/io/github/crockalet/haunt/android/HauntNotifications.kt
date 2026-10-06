package io.github.crockalet.haunt.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import io.github.crockalet.haunt.android.settings.Units
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.core.currentFix
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The single ongoing notification shared by [HauntService] and [ControlService] (same id, so the
 * user sees one entry while either runs; Android keeps it while any foreground service uses the id).
 */
object HauntNotifications {
    const val CHANNEL_ID = "haunt.status"
    const val NOTIFICATION_ID = 0x4A17

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Mock location status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows the fake location while Haunt is mocking or controlled over ADB"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: HauntState, units: Units, adbClients: Int): Notification {
        ensureChannel(context)
        val (title, text) = describe(state, units, adbClients)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_haunt)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val paused = (state as? HauntState.Moving)?.paused ?: (state as? HauntState.Joystick)?.paused
        if (paused != null) {
            builder.addAction(
                if (paused) action(context, HauntService.ACTION_RESUME, "Resume") else action(context, HauntService.ACTION_PAUSE, "Pause"),
            )
        }
        if (state != HauntState.Idle) builder.addAction(action(context, HauntService.ACTION_STOP, "Stop"))
        return builder.build()
    }

    /** Title and text for [state]; pure, so it's unit-testable. */
    fun describe(state: HauntState, units: Units, adbClients: Int): Pair<String, String> {
        val adb = if (adbClients > 0) " · ADB connected" else ""
        return when (state) {
            HauntState.Idle -> "Haunt" to "ADB control ready$adb"
            is HauntState.Holding -> "Holding ${state.label ?: "location"}" to coords(state) + adb
            is HauntState.Moving -> {
                val p = state.progress
                val title = (if (state.paused) "Paused" else "Moving") + (state.routeName?.let { " · $it" } ?: "")
                val eta = p.etaSeconds?.let { " · ETA ${duration(it)}" }.orEmpty()
                title to "${distance(p.traveledMeters, units)} of ${distance(p.totalMeters, units)}$eta$adb"
            }
            is HauntState.Joystick -> (if (state.paused) "Joystick (paused)" else "Joystick") to coords(state) + adb
        }
    }

    private fun coords(state: HauntState): String =
        state.currentFix?.position?.let { String.format(Locale.ROOT, "%.5f, %.5f", it.lat, it.lng) }.orEmpty()

    fun distance(meters: Double, units: Units): String = when (units) {
        Units.Metric -> if (meters < 1000) "${meters.roundToLong()} m" else String.format(Locale.ROOT, "%.1f km", meters / 1000)
        Units.Imperial -> {
            val feet = meters * 3.28084
            if (feet < 1000) "${feet.roundToLong()} ft" else String.format(Locale.ROOT, "%.1f mi", meters / 1609.344)
        }
    }

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }

    private fun action(context: Context, action: String, label: String): Notification.Action {
        val intent = Intent(context, HauntService::class.java).setAction(action)
        val pending = PendingIntent.getService(context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_haunt), label, pending).build()
    }

    /** Which foreground-service type a service runs as. */
    enum class ForegroundType { Location, SpecialUse }

    /**
     * `startForeground` with the right `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` where the platform
     * supports types. @throws SecurityException / IllegalStateException when not allowed.
     */
    fun startForeground(service: Service, notification: Notification, type: ForegroundType) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> service.startForeground(
                NOTIFICATION_ID,
                notification,
                when (type) {
                    ForegroundType.Location -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    ForegroundType.SpecialUse -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                },
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == ForegroundType.Location ->
                service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            // Before API 34 `specialUse` doesn't exist; the manifest type applies.
            else -> service.startForeground(NOTIFICATION_ID, notification)
        }
    }
}
