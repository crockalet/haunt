package io.github.crockalet.haunt.android.control

import io.github.crockalet.haunt.protocol.Broadcast
import io.github.crockalet.haunt.protocol.ErrorCodes
import io.github.crockalet.haunt.protocol.HauntApi
import io.github.crockalet.haunt.protocol.ProtocolJson
import io.github.crockalet.haunt.protocol.RpcError
import io.github.crockalet.haunt.protocol.RpcException
import io.github.crockalet.haunt.protocol.SetLocationParams
import io.github.crockalet.haunt.protocol.SetLocationResult
import io.github.crockalet.haunt.protocol.StatusResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The broadcast fallback channel (DESIGN §6.2) without the Android parts: maps an action + extras
 * to [HauntApi] calls and renders the JSON that `AdbCommandReceiver` returns via `setResultData`:
 * the method's result on success, `{"error":{code,message,data?}}` on failure.
 */
object BroadcastCommands {

    /** Result of one broadcast command. */
    data class Outcome(val method: String, val json: String, val error: RpcError?)

    suspend fun execute(api: HauntApi, action: String?, extras: Map<String, Any?>): Outcome {
        val method = action ?: "(none)"
        return try {
            val json = when (action) {
                Broadcast.ACTION_SET -> {
                    val params = SetLocationParams(
                        lat = extras.double(Broadcast.EXTRA_LAT),
                        lng = extras.double(Broadcast.EXTRA_LNG),
                        altitude = extras.double(Broadcast.EXTRA_ALTITUDE),
                        accuracy = extras.double(Broadcast.EXTRA_ACCURACY)?.toFloat(),
                        query = (extras[Broadcast.EXTRA_QUERY] as? String)?.takeIf { it.isNotBlank() },
                    )
                    params.validate()
                    ProtocolJson.encodeToString(SetLocationResult.serializer(), api.setLocation(params))
                }
                Broadcast.ACTION_STOP -> api.stopLocation().let { "{}" }
                Broadcast.ACTION_PAUSE -> api.pause().let { "{}" }
                Broadcast.ACTION_RESUME -> api.resume().let { "{}" }
                Broadcast.ACTION_STATUS -> ProtocolJson.encodeToString(StatusResult.serializer(), api.status())
                else -> throw RpcException(
                    ErrorCodes.METHOD_NOT_FOUND,
                    "Unknown action \"$action\" (known: ${listOf(Broadcast.ACTION_SET, Broadcast.ACTION_STOP, Broadcast.ACTION_PAUSE, Broadcast.ACTION_RESUME, Broadcast.ACTION_STATUS).joinToString()})",
                )
            }
            Outcome(method, json, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            errorOutcome(method, e.error)
        } catch (e: IllegalArgumentException) {
            errorOutcome(method, RpcError(ErrorCodes.INVALID_PARAMS, "Invalid params: ${e.message}"))
        } catch (e: Exception) {
            errorOutcome(method, RpcError(ErrorCodes.INTERNAL_ERROR, "Internal error: ${e.message ?: e::class.simpleName}"))
        }
    }

    fun errorOutcome(method: String, error: RpcError) = Outcome(
        method,
        ProtocolJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("error" to ProtocolJson.encodeToJsonElement(RpcError.serializer(), error)))),
        error,
    )

    /** Accepts `--ed` (Double), `--ef` (Float), `--ei`/`--el` and `--es` ("35.6") extras alike. */
    private fun Map<String, Any?>.double(key: String): Double? = when (val v = this[key]) {
        null -> null
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: throw RpcException.invalidParams("$key must be a number (got \"$v\")")
        else -> throw RpcException.invalidParams("$key must be a number")
    }
}

/** Short, human-readable rendering of JSON-RPC params for the activity log (large strings elided). */
fun summarizeParams(params: JsonElement?): String = when (params) {
    null -> ""
    is JsonObject -> params.entries.joinToString(", ") { (k, v) -> "$k=${summarizeValue(v)}" }
    else -> summarizeValue(params)
}

private fun summarizeValue(value: JsonElement): String = when (value) {
    is JsonPrimitive -> if (value.isString && value.content.length > 40) "<${value.content.length} chars>" else value.toString()
    is JsonArray -> "[${value.size} items]"
    is JsonObject -> if (value.toString().length > 60) "{…}" else value.toString()
}

/** Builds `{"key":value,…}` for logging broadcast extras. */
fun summarizeExtras(extras: Map<String, Any?>): String =
    summarizeParams(buildJsonObject { extras.forEach { (k, v) -> put(k, JsonPrimitive(v?.toString())) } })
