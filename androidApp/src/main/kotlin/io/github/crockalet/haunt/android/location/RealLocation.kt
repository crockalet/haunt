package io.github.crockalet.haunt.android.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import io.github.crockalet.haunt.core.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A position reported by the device's real (non-test) location providers. */
data class RealFix(
    val position: LatLng,
    val accuracyMeters: Float?,
    val timeMillis: Long,
    val mock: Boolean = false,
)

/** Why the real location couldn't be found; [hint] tells the user what to do. */
class RealLocationException(message: String, val hint: String? = null) : Exception(message)

/** Choosing between candidate fixes (pure; unit-tested). */
object RealFixes {
    /** Fixes older than this aren't "where I am right now". */
    const val MAX_AGE_MILLIS = 2 * 60_000L

    /** While Haunt is faking, the last real fix is used if it's at most this old. */
    const val MAX_CACHED_AGE_MILLIS = 30 * 60_000L

    /**
     * Best real fix among [candidates]: never a mock fix (Haunt's own, or another app's), none older
     * than [maxAgeMillis]; the most accurate wins, recency breaks ties.
     */
    fun best(candidates: List<RealFix>, nowMillis: Long, maxAgeMillis: Long = MAX_AGE_MILLIS): RealFix? =
        candidates
            .filter { !it.mock && nowMillis - it.timeMillis in 0..maxAgeMillis }
            .minWithOrNull(compareBy<RealFix> { it.accuracyMeters ?: Float.MAX_VALUE }.thenByDescending { it.timeMillis })
}

/**
 * The device's *real* position, for "start from where I am".
 *
 * While Haunt is idle its test providers are gone, so GPS / network / fused report the real
 * position and [current] asks them for a fresh fix. While Haunt is faking, every provider returns the
 * fake position; [current] then falls back to the last real fix it saw (see [last]), or explains
 * that faking has to stop first.
 */
class RealLocation(
    context: Context,
    private val isFaking: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val hasPermission: () -> Boolean = {
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private val _last = MutableStateFlow<RealFix?>(null)

    /** The most recent real fix seen (any age). */
    val last: StateFlow<RealFix?> = _last.asStateFlow()

    /** Quick answer from the providers' caches (no waiting); null while faking or when nothing recent is cached. */
    fun lastKnown(): RealFix? {
        if (isFaking() || !hasPermission()) return null
        return RealFixes.best(cachedFixes(), clock())?.also(::remember)
    }

    /**
     * Where the device really is. Waits up to [timeoutMillis] for a fresh fix when idle.
     * @throws RealLocationException with a user-facing message and hint.
     */
    suspend fun current(timeoutMillis: Long = 12_000): RealFix {
        if (!hasPermission()) {
            throw RealLocationException("Haunt doesn't have location permission", "Allow location for Haunt in Android's app settings.")
        }
        if (isFaking()) {
            _last.value?.takeIf { clock() - it.timeMillis <= RealFixes.MAX_CACHED_AGE_MILLIS }?.let { return it }
            throw RealLocationException(
                "Haunt is faking your location right now",
                "Stop haunting (■), then tap the locate button again to find where you really are.",
            )
        }
        val providers = enabledProviders()
        if (providers.isEmpty()) {
            throw RealLocationException("Location is turned off", "Turn on location in Android's quick settings.")
        }
        val fresh = withTimeoutOrNull(timeoutMillis) { firstFix(providers) }
        val fix = fresh ?: RealFixes.best(cachedFixes(), clock())
            ?: throw RealLocationException("Couldn't find your location", "Move somewhere with a clearer sky or Wi-Fi and try again.")
        remember(fix)
        return fix
    }

    private fun remember(fix: RealFix) {
        val previous = _last.value
        if (previous == null || fix.timeMillis >= previous.timeMillis) _last.value = fix
    }

    private fun enabledProviders(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

    @SuppressLint("MissingPermission") // checked by the callers
    private fun cachedFixes(): List<RealFix> = enabledProviders().mapNotNull { p ->
        runCatching { locationManager.getLastKnownLocation(p) }.getOrNull()?.toRealFix()
    }

    /** First non-mock fix from any of [providers]; listeners are removed when this returns or is cancelled. */
    @SuppressLint("MissingPermission")
    private suspend fun firstFix(providers: List<String>): RealFix = withContext(Dispatchers.Main) {
        val fixes = Channel<RealFix>(Channel.CONFLATED)
        val listener = LocationListener { l -> l.toRealFix().takeIf { !it.mock }?.let { fixes.trySend(it) } }
        try {
            for (p in providers) {
                runCatching { locationManager.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) }
            }
            fixes.receive()
        } finally {
            locationManager.removeUpdates(listener)
        }
    }

    private fun Location.toRealFix() = RealFix(
        position = LatLng(latitude, longitude),
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        timeMillis = time,
        mock = isMockFix(),
    )

    @Suppress("DEPRECATION")
    private fun Location.isMockFix(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider
}
