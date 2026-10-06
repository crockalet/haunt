package io.github.crockalet.haunt.android.inject

import android.content.Context
import android.location.LocationManager

/** Flavour-specific injection (foss: `LocationManager` test providers only; no Play Services). */
object FlavourInjection {
    const val FLAVOUR = "foss"

    fun createInjector(context: Context): LocationInjector =
        TestProviderInjector(context.getSystemService(LocationManager::class.java))
}
