package io.github.crockalet.haunt.android

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.util.Log
import io.github.crockalet.haunt.android.inject.TestProviderInjector

/** Platform checks for the onboarding wizard and the API. */
object MockLocationCheck {
    private const val TAG = "HauntMockCheck"
    private const val PROBE_PROVIDER = "haunt_probe"

    /**
     * Whether Haunt is the selected mock location app (Developer options → Select mock location app,
     * or `adb shell appops set <pkg> android:mock_location allow`). Uses `AppOpsManager`
     * (`OPSTR_MOCK_LOCATION`); if that is inconclusive, probes by adding and removing a test provider.
     */
    fun isMockAppSelected(context: Context): Boolean {
        val mode = try {
            val ops = context.getSystemService(AppOpsManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AppOps check failed; probing", e)
            null
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_IGNORED -> false
            else -> probe(context)
        }
    }

    /** Adds and removes a throw-away test provider; [SecurityException] means "not selected". */
    fun probe(context: Context): Boolean {
        val probe = TestProviderInjector(context.getSystemService(LocationManager::class.java), listOf(PROBE_PROVIDER))
        return try {
            probe.start()
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Mock location probe failed", e)
            false
        } finally {
            probe.stop()
        }
    }

    /** ACCESS_FINE or ACCESS_COARSE granted (needed for a `location` foreground service on Android 14+). */
    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Whether the location foreground service can be started (permission only matters on API 34+). */
    fun canRunLocationService(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || hasLocationPermission(context)

    /** POST_NOTIFICATIONS granted (API 33+; always true below). */
    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
