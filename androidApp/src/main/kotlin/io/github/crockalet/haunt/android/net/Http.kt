package io.github.crockalet.haunt.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Tiny HTTP abstraction so the geocoder / router can be tested without the network. */
fun interface HttpClient {
    /** GETs [url] and returns the body. @throws HttpException on non-2xx, IOException on network failure. */
    suspend fun get(url: String): String
}

class HttpException(val status: Int, message: String) : IOException(message)

/** `User-Agent` sent with every request (required by the public Photon / OSRM / tile servers' usage policies). */
fun hauntUserAgent(appVersion: String) = "Haunt/$appVersion (+https://github.com/crockalet/haunt)"

/** [HttpClient] on [HttpURLConnection]; runs on [Dispatchers.IO]. */
class UrlConnectionHttpClient(
    private val userAgent: String,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
    private val maxBodyBytes: Int = 8 * 1024 * 1024,
) : HttpClient {
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.use { it.readBytes().take(300).toByteArray().decodeToString() }.orEmpty()
                throw HttpException(status, "HTTP $status from ${URL(url).host}${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            connection.inputStream.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > maxBodyBytes) throw IOException("Response too large (${bytes.size} bytes)")
                bytes.decodeToString()
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun Double.coord(): String = String.format(java.util.Locale.ROOT, "%.6f", this)
