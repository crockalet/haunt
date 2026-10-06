package io.github.crockalet.haunt.android.net

import io.github.crockalet.haunt.core.Geo
import io.github.crockalet.haunt.core.LatLng
import io.github.crockalet.haunt.protocol.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** Place search / forward geocoding. */
interface Geocoder {
    /** @throws java.io.IOException on network / server failure. */
    suspend fun search(query: String, limit: Int = 5, near: LatLng? = null): List<Place>

    /** Places at / around [position], nearest first (reverse geocoding). Empty if unsupported. @throws java.io.IOException */
    suspend fun reverse(position: LatLng, limit: Int = 5): List<Place> = emptyList()
}

/**
 * [Photon](https://github.com/komoot/photon) geocoder: `GET {base}/api/?q=…&limit=…[&lat=…&lon=…]`.
 * [baseUrl] is read on every call so a changed setting applies immediately.
 */
class PhotonGeocoder(
    private val http: HttpClient,
    private val baseUrl: () -> String,
) : Geocoder {
    override suspend fun search(query: String, limit: Int, near: LatLng?): List<Place> =
        PhotonParser.parse(http.get(buildUrl(baseUrl(), query, limit, near)))

    override suspend fun reverse(position: LatLng, limit: Int): List<Place> =
        PhotonParser.parse(http.get(buildReverseUrl(baseUrl(), position, limit)))

    companion object {
        /** `GET {base}/reverse?lat=…&lon=…&limit=…` (a base ending in `/api` is accepted too). */
        fun buildReverseUrl(base: String, position: LatLng, limit: Int): String {
            val root = base.trim().trimEnd('/').removeSuffix("/api")
            return "$root/reverse?lat=${position.lat.coord()}&lon=${position.lng.coord()}&limit=${limit.coerceIn(1, 50)}"
        }

        fun buildUrl(base: String, query: String, limit: Int, near: LatLng?): String {
            val trimmed = base.trim().trimEnd('/')
            val endpoint = if (trimmed.endsWith("/api")) "$trimmed/" else "$trimmed/api/"
            return buildString {
                append(endpoint).append("?q=").append(urlEncode(query)).append("&limit=").append(limit.coerceIn(1, 50))
                if (near != null && Geo.isValid(near)) append("&lat=").append(near.lat.coord()).append("&lon=").append(near.lng.coord())
            }
        }
    }
}

/** Parses Photon's GeoJSON `FeatureCollection` into [Place]s. */
object PhotonParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** @throws IllegalArgumentException if [body] isn't a FeatureCollection. */
    fun parse(body: String): List<Place> {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Unexpected search response", e)
        }
        val features = root["features"] as? JsonArray ?: throw IllegalArgumentException("Unexpected search response: no features")
        return features.mapNotNull { feature -> (feature as? JsonObject)?.let(::toPlace) }
    }

    private fun toPlace(feature: JsonObject): Place? {
        val coordinates = (feature["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray ?: return null
        val lng = (coordinates.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val lat = (coordinates.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        val position = LatLng(lat, lng)
        if (!Geo.isValid(position)) return null
        val p = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
        fun prop(key: String): String? = (p[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

        val streetLine = listOfNotNull(prop("street"), prop("housenumber")).joinToString(" ").ifEmpty { null }
        val name = prop("name") ?: streetLine ?: prop("city") ?: prop("county") ?: prop("state") ?: prop("country") ?: return null
        val address = listOfNotNull(
            streetLine,
            prop("district") ?: prop("locality"),
            prop("city"),
            prop("state"),
            prop("country"),
        ).distinct().filterNot { it == name }.joinToString(", ").ifEmpty { null }
        return Place(name = name, position = position, address = address, kind = prop("osm_value") ?: prop("type"))
    }
}
