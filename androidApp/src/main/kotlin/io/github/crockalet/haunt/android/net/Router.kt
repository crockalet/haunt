package io.github.crockalet.haunt.android.net

import io.github.crockalet.haunt.core.Geo
import io.github.crockalet.haunt.core.LatLng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/** A road-following path between waypoints. */
data class RoutedPath(
    val points: List<LatLng>,
    val distanceMeters: Double,
    /** Router's travel-time estimate for its own profile (not Haunt's playback speed). */
    val durationSeconds: Double? = null,
)

class RoutingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Road routing. */
interface Router {
    /** @throws IOException (incl. [RoutingException]) when no route could be computed. */
    suspend fun route(waypoints: List<LatLng>): RoutedPath
}

/**
 * [OSRM](https://project-osrm.org/) router:
 * `GET {base}/route/v1/{profile}/{lng,lat;lng,lat;…}?overview=full&geometries=geojson`.
 */
class OsrmRouter(
    private val http: HttpClient,
    private val baseUrl: () -> String,
    private val profile: () -> String = { "driving" },
) : Router {
    override suspend fun route(waypoints: List<LatLng>): RoutedPath {
        require(waypoints.size >= 2) { "need at least 2 waypoints" }
        return OsrmParser.parse(http.get(buildUrl(baseUrl(), profile(), waypoints)))
    }

    companion object {
        /** OSRM's demo server rejects more than this many coordinates. */
        const val MAX_WAYPOINTS = 100

        fun buildUrl(base: String, profile: String, waypoints: List<LatLng>): String {
            if (waypoints.size > MAX_WAYPOINTS) throw RoutingException("Too many waypoints for routing (${waypoints.size} > $MAX_WAYPOINTS)")
            val coords = waypoints.joinToString(";") { "${it.lng.coord()},${it.lat.coord()}" }
            return "${base.trim().trimEnd('/')}/route/v1/${urlEncode(profile.ifBlank { "driving" })}/$coords?overview=full&geometries=geojson"
        }
    }
}

/** Parses an OSRM `route` response (GeoJSON geometry) into a [RoutedPath]. */
object OsrmParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** @throws RoutingException when the response is an error or malformed. */
    fun parse(body: String): RoutedPath {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw RoutingException("Unexpected routing response", e)
        }
        val code = (root["code"] as? JsonPrimitive)?.content
        if (code != "Ok") {
            val message = (root["message"] as? JsonPrimitive)?.content
            throw RoutingException(
                when (code) {
                    "NoRoute" -> "no road route between these stops"
                    "NoSegment" -> "a stop is too far from any road"
                    else -> "Routing failed: ${code ?: "no code"}${message?.let { " ($it)" } ?: ""}"
                },
            )
        }
        val route = (root["routes"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw RoutingException("Routing returned no route")
        val coordinates = ((route["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray)
            ?: throw RoutingException("Routing response has no GeoJSON geometry")
        val points = coordinates.mapNotNull { c ->
            val pair = c as? JsonArray ?: return@mapNotNull null
            val lng = (pair.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lat = (pair.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            LatLng(lat, lng).takeIf { Geo.isValid(it) }
        }
        if (points.size < 2) throw RoutingException("Routing returned an empty geometry")
        val distance = (route["distance"] as? JsonPrimitive)?.doubleOrNull ?: Geo.polylineLength(points)
        val duration = (route["duration"] as? JsonPrimitive)?.doubleOrNull
        return RoutedPath(points, distance, duration)
    }
}
