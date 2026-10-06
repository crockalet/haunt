package io.github.crockalet.haunt.android.inject

import android.content.Context
import android.location.LocationManager

/** Flavour-specific injection (play: test providers + Play Services fused mock). */
object FlavourInjection {
    const val FLAVOUR = "play"

    fun createInjector(context: Context): LocationInjector = CompositeInjector(
        listOf(
            TestProviderInjector(context.getSystemService(LocationManager::class.java)),
            FusedMockInjector(context),
        ),
    )
}
