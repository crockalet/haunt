package io.github.crockalet.haunt.android.inject

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import io.github.crockalet.haunt.core.Fix
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Play Services fused-provider mock (`setMockMode(true)` + `setMockLocation`), the reliable path for
 * apps that read location through `FusedLocationProviderClient` (DESIGN §2.3). Play flavour only.
 *
 * Blocking: call off the main thread. If Play Services is missing or fails for any reason other than
 * a [SecurityException] (mock app not selected), this injector disables itself and the test
 * providers carry on alone.
 */
class FusedMockInjector(context: Context) : LocationInjector {
    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @Volatile
    private var enabled = false

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start() {
        if (enabled) return
        enabled = try {
            await(client.setMockMode(true))
            true
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fused mock mode unavailable; using test providers only", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun push(fix: Fix) {
        if (!enabled) return
        // Fire and forget: don't block the 1 Hz loop on Play Services.
        client.setMockLocation(fix.toLocation(FUSED_PROVIDER_NAME))
            .addOnFailureListener { Log.w(TAG, "setMockLocation failed", it) }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun stop() {
        if (!enabled) return
        enabled = false
        try {
            await(client.setMockMode(false))
        } catch (e: Exception) {
            Log.w(TAG, "setMockMode(false) failed", e)
        }
    }

    private fun <T> await(task: Task<T>): T = try {
        Tasks.await(task, 5, TimeUnit.SECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: TimeoutException) {
        throw IllegalStateException("Play Services did not respond", e)
    }

    private companion object {
        const val TAG = "HauntFusedMock"

        /** `LocationManager.FUSED_PROVIDER` is API 31+; the string is the same on all versions. */
        const val FUSED_PROVIDER_NAME = "fused"
    }
}
