package io.github.crockalet.haunt.android.inject

import android.annotation.SuppressLint
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.crockalet.haunt.core.Fix

/**
 * Feeds `LocationManager` test providers: GPS, NETWORK and (API 31+) FUSED (DESIGN §5).
 * Needs Haunt to be the selected mock location app; otherwise [start] throws [SecurityException].
 */
class TestProviderInjector(
    private val locationManager: LocationManager,
    private val providers: List<String> = defaultProviders(),
) : LocationInjector {

    private val active = LinkedHashSet<String>()

    @Synchronized
    override fun start() {
        for (provider in providers) {
            if (provider in active) continue
            addProvider(provider)
            active += provider
        }
    }

    @Synchronized
    override fun push(fix: Fix) {
        val elapsed = SystemClock.elapsedRealtimeNanos()
        for (provider in active) {
            locationManager.setTestProviderLocation(provider, fix.toLocation(provider, elapsed))
        }
    }

    @Synchronized
    override fun stop() {
        for (provider in active) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (e: Exception) {
                Log.w(TAG, "removeTestProvider($provider) failed", e)
            }
        }
        active.clear()
    }

    private fun addProvider(provider: String) {
        // A provider left behind by a crash makes addTestProvider throw IllegalArgumentException.
        try {
            locationManager.removeTestProvider(provider)
        } catch (_: IllegalArgumentException) {
        }
        val isGps = provider == LocationManager.GPS_PROVIDER
        val isNetwork = provider == LocationManager.NETWORK_PROVIDER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val properties = ProviderProperties.Builder()
                .setHasNetworkRequirement(isNetwork)
                .setHasSatelliteRequirement(isGps)
                .setHasCellRequirement(false)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(if (isGps) ProviderProperties.POWER_USAGE_HIGH else ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(if (isNetwork) ProviderProperties.ACCURACY_COARSE else ProviderProperties.ACCURACY_FINE)
                .build()
            locationManager.addTestProvider(provider, properties)
        } else {
            addLegacy(provider, isGps, isNetwork)
        }
        locationManager.setTestProviderEnabled(provider, true)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    private fun addLegacy(provider: String, isGps: Boolean, isNetwork: Boolean) {
        locationManager.addTestProvider(
            provider,
            /* requiresNetwork = */ isNetwork,
            /* requiresSatellite = */ isGps,
            /* requiresCell = */ false,
            /* hasMonetaryCost = */ false,
            /* supportsAltitude = */ true,
            /* supportsSpeed = */ true,
            /* supportsBearing = */ true,
            /* powerRequirement = */ if (isGps) Criteria.POWER_HIGH else Criteria.POWER_LOW,
            /* accuracy = */ if (isNetwork) Criteria.ACCURACY_COARSE else Criteria.ACCURACY_FINE,
        )
    }

    companion object {
        private const val TAG = "HauntInjector"

        fun defaultProviders(): List<String> = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }
    }
}

/** Builds a complete [Location] (all fields `setTestProviderLocation` requires) for [provider]. */
fun Fix.toLocation(provider: String, elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()): Location =
    Location(provider).also { l ->
        l.latitude = position.lat
        l.longitude = position.lng
        l.time = timeMillis
        l.elapsedRealtimeNanos = elapsedRealtimeNanos
        l.accuracy = accuracy
        altitude?.let { l.altitude = it }
        speed?.let { l.speed = it }
        bearing?.let { l.bearing = it }
        // API 26+ (our minSdk): the extra accuracy fields apps increasingly check.
        if (altitude != null) l.verticalAccuracyMeters = (accuracy * 1.5f).coerceAtLeast(1f)
        if (speed != null) l.speedAccuracyMetersPerSecond = 0.5f
        if (bearing != null) l.bearingAccuracyDegrees = if ((speed ?: 0f) > 0.5f) 5f else 30f
    }
