package io.github.crockalet.haunt.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** The JSON configuration used on the wire. Lenient on input (unknown keys), compact on output (no nulls). */
@OptIn(ExperimentalSerializationApi::class)
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}

const val JSONRPC_VERSION = "2.0"

/** Any JSON-RPC 2.0 message. One message is serialised per line (see [encodeLine] / [parseRpcMessage]). */
sealed interface JsonRpcMessage

/**
 * A call expecting a [JsonRpcResponse]. [id] is a JSON number or string.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcRequest(
    val id: JsonPrimitive,
    val method: String,
    val params: JsonElement? = null,
    @EncodeDefault val jsonrpc: String = JSONRPC_VERSION,
) : JsonRpcMessage

/** A one-way message without an id. Server → client events use these. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcNotification(
    val method: String,
    val params: JsonElement? = null,
    @EncodeDefault val jsonrpc: String = JSONRPC_VERSION,
) : JsonRpcMessage

/** A reply to a [JsonRpcRequest]; exactly one of [result] / [error] is set. [id] is `null` for parse errors. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcResponse(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val id: JsonPrimitive = JsonNull,
    val result: JsonElement? = null,
    val error: RpcError? = null,
    @EncodeDefault val jsonrpc: String = JSONRPC_VERSION,
) : JsonRpcMessage {
    init {
        require((result == null) != (error == null)) { "exactly one of result/error must be set" }
    }
}

/** JSON-RPC error object. [code] is one of [ErrorCodes]; [data] may carry an [ErrorData]. */
@Serializable
data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
) {
    /** The structured [ErrorData] if the server sent one. */
    val details: ErrorData?
        get() = (data as? JsonObject)?.let {
            runCatching { ProtocolJson.decodeFromJsonElement<ErrorData>(it) }.getOrNull()
        }
}

/** Optional structured error payload; [hint] is a short, actionable fix for humans/agents. */
@Serializable
data class ErrorData(
    val hint: String? = null,
    val serverProtocol: Int? = null,
    val minProtocol: Int? = null,
)

/** Serialises one message as a single line (no trailing newline; JSON never contains raw newlines). */
fun JsonRpcMessage.encodeLine(json: Json = ProtocolJson): String = when (this) {
    is JsonRpcRequest -> json.encodeToString(JsonRpcRequest.serializer(), this)
    is JsonRpcNotification -> json.encodeToString(JsonRpcNotification.serializer(), this)
    is JsonRpcResponse -> json.encodeToString(JsonRpcResponse.serializer(), this)
}

/** Why a line could not be turned into a [JsonRpcMessage]. [id] is set when the request id was readable. */
class RpcParseException(val error: RpcError, val id: JsonPrimitive = JsonNull) : Exception(error.message)

/**
 * Parses one line into a request, notification or response.
 * @throws RpcParseException with [ErrorCodes.PARSE_ERROR] or [ErrorCodes.INVALID_REQUEST].
 */
fun parseRpcMessage(line: String, json: Json = ProtocolJson): JsonRpcMessage {
    val element = try {
        json.parseToJsonElement(line)
    } catch (e: SerializationException) {
        throw RpcParseException(RpcError(ErrorCodes.PARSE_ERROR, "Parse error: ${e.message?.lineSequence()?.first()}"))
    }
    val obj = element as? JsonObject
        ?: throw RpcParseException(RpcError(ErrorCodes.INVALID_REQUEST, "Invalid request: expected a JSON object"))
    val id = obj["id"]
    val idPrimitive = (id as? JsonPrimitive)?.takeIf { it is JsonNull || it.isString || it.content.toDoubleOrNull() != null }
    fun invalid(msg: String): Nothing =
        throw RpcParseException(RpcError(ErrorCodes.INVALID_REQUEST, "Invalid request: $msg"), idPrimitive ?: JsonNull)

    if (obj["jsonrpc"]?.let { (it as? JsonPrimitive)?.content } != JSONRPC_VERSION) invalid("\"jsonrpc\" must be \"2.0\"")
    if (id != null && idPrimitive == null) invalid("\"id\" must be a string, number or null")
    val method = obj["method"]
    return try {
        when {
            method != null -> {
                if (method !is JsonPrimitive || !method.isString) invalid("\"method\" must be a string")
                if (id == null) {
                    json.decodeFromJsonElement(JsonRpcNotification.serializer(), obj)
                } else {
                    json.decodeFromJsonElement(JsonRpcRequest.serializer(), obj)
                }
            }
            "result" in obj || "error" in obj -> {
                if ("result" in obj && "error" in obj) invalid("response has both result and error")
                // "result": null is a valid result; keep it distinguishable from "no result".
                val result = if ("result" in obj) obj["result"] ?: JsonNull else null
                val error = obj["error"]?.let { json.decodeFromJsonElement(RpcError.serializer(), it) }
                JsonRpcResponse(id = idPrimitive ?: JsonNull, result = result, error = error)
            }
            else -> invalid("missing \"method\" or \"result\"/\"error\"")
        }
    } catch (e: SerializationException) {
        invalid(e.message?.lineSequence()?.first() ?: "malformed message")
    } catch (e: IllegalArgumentException) {
        invalid(e.message ?: "malformed message")
    }
}

/** A JSON-RPC id as a Kotlin value: [Long] for numeric ids, [String] otherwise. */
internal fun JsonPrimitive.idKey(): Any? = when {
    this is JsonNull -> null
    isString -> content
    else -> content.toLongOrNull() ?: content
}
