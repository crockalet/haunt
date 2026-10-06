package io.github.crockalet.haunt.ui.state

import androidx.compose.runtime.Immutable
import io.github.crockalet.haunt.core.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.crockalet.haunt.core.Geo as CoreGeo

/** What the Search screen shows from [PlaceSearch]. */
@Immutable
data class PlaceSearchState(
    /** The (trimmed) query [results] belong to. */
    val query: String = "",
    val results: List<Place> = emptyList(),
    /** A search for [query] is pending (debouncing or in flight). */
    val searching: Boolean = false,
    val error: String? = null,
    /** Where [nearby] was looked up. */
    val nearbyOf: LatLng? = null,
    val nearby: List<Place> = emptyList(),
    /** Short area name around [nearbyOf] ("Dogenzaka, Shibuya"). */
    val area: String? = null,
) {
    /** [nearby] if it was looked up close enough to [position]. */
    fun nearbyFor(position: LatLng?): List<Place> =
        if (position != null && nearbyOf != null && PlaceSearch.samePlace(position, nearbyOf)) nearby else emptyList()

    fun areaFor(position: LatLng?): String? =
        if (position != null && nearbyOf != null && PlaceSearch.samePlace(position, nearbyOf)) area else null
}

/**
 * Debounced place search for the Search screen. Feed it the query and the point of interest with
 * [update] (on every keystroke is fine); it waits [debounceMillis] after the last change, cancels
 * stale requests, and publishes [state]. Network calls run on [scope] (the lambdas should switch to
 * an IO dispatcher themselves if they block).
 *
 * @param search free-text search, biased towards `near`.
 * @param nearby places around a point (reverse geocoding); re-queried only when the point moves
 *   more than [NearbyRadiusMeters].
 */
class PlaceSearch(
    scope: CoroutineScope,
    private val search: suspend (query: String, near: LatLng?) -> List<Place>,
    private val nearby: suspend (LatLng) -> List<Place> = { emptyList() },
    private val debounceMillis: Long = 350,
    private val minQueryLength: Int = 2,
    private val errorMessage: (Throwable) -> String = { it.message ?: "Search failed" },
) {
    private val query = MutableStateFlow("")
    private val near = MutableStateFlow<LatLng?>(null)
    private val _state = MutableStateFlow(PlaceSearchState())

    val state: StateFlow<PlaceSearchState> = _state.asStateFlow()

    init {
        scope.launch {
            query.map { it.trim() }.distinctUntilChanged().collectLatest { q ->
                if (q.length < minQueryLength) {
                    _state.update { it.copy(query = q, results = emptyList(), searching = false, error = null) }
                    return@collectLatest
                }
                // Keep the previous results on screen while typing.
                _state.update { it.copy(searching = true, error = null) }
                delay(debounceMillis)
                val outcome = runCatchingNonCancel { search(q, near.value) }
                _state.update {
                    outcome.fold(
                        onSuccess = { r -> it.copy(query = q, results = r, searching = false, error = null) },
                        onFailure = { e -> it.copy(query = q, results = emptyList(), searching = false, error = errorMessage(e)) },
                    )
                }
            }
        }
        scope.launch {
            near.distinctUntilChanged { a, b -> a == b || (a != null && b != null && samePlace(a, b)) }.collectLatest { p ->
                if (p == null) return@collectLatest
                delay(debounceMillis)
                // Nearby is a nice-to-have: on failure just show nothing.
                val places = runCatchingNonCancel { nearby(p) }.getOrDefault(emptyList())
                val first = places.firstOrNull()
                _state.update { it.copy(nearbyOf = p, nearby = places, area = first?.subtitle ?: first?.name) }
            }
        }
    }

    /** The current query and point of interest (detected coordinates, or the current location). */
    fun update(query: String, near: LatLng?) {
        this.query.value = query
        this.near.value = near
    }

    private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        /** Moves smaller than this don't re-query nearby places. */
        const val NearbyRadiusMeters = 50.0

        fun samePlace(a: LatLng, b: LatLng): Boolean = CoreGeo.distanceMeters(a, b) < NearbyRadiusMeters
    }
}
