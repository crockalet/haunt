package io.github.crockalet.haunt.protocol

import kotlinx.serialization.json.JsonElement

/** JSON-RPC error codes. Standard codes are negative per the spec; Haunt's are in the -32000..-32099 server range. */
object ErrorCodes {
    // JSON-RPC 2.0 standard codes.
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // Haunt server errors.
    /** "Allow ADB control" is off in the app's settings. */
    const val ADB_CONTROL_DISABLED = -32000

    /** Haunt is not the selected mock location app (Developer options). */
    const val MOCK_APP_NOT_SELECTED = -32001

    /** A place, favourite or route could not be found. */
    const val NOT_FOUND = -32002

    /** Client and server protocol versions are incompatible. */
    const val PROTOCOL_MISMATCH = -32003

    /** The first request on a connection must be `hello`. */
    const val HANDSHAKE_REQUIRED = -32004

    /** The request is valid but not possible in the current state (e.g. pause while idle). */
    const val INVALID_STATE = -32005

    /** A dependency is unavailable (no network for search/routing, missing permission, …). */
    const val UNAVAILABLE = -32006

    /** Peer is not the adb shell or root user. */
    const val UNAUTHORIZED = -32007

    // Client-side only; never sent on the wire.
    /** No response within the client's timeout. */
    const val TIMEOUT = -32090

    /** The connection to the device closed (app stopped, cable unplugged, adb restarted). */
    const val CONNECTION_LOST = -32091
}

/** Default human-actionable hints per error code. */
object ErrorHints {
    const val ADB_CONTROL_DISABLED = "Open Haunt → Settings → turn on \"Allow ADB control\"."
    const val MOCK_APP_NOT_SELECTED =
        "Enable Developer options → Select mock location app → Haunt (Haunt's setup wizard walks through it)."
    const val PROTOCOL_MISMATCH = "Update the haunt CLI and the Haunt app to matching versions."
    const val CONNECTION_LOST = "Check the USB/wifi adb connection and that the Haunt app is installed and running."
    const val UNAUTHORIZED = "Only adb (shell) or root may use the control socket."
}

/**
 * An error from a JSON-RPC call. Thrown by [HauntApi] implementations to produce a specific error
 * response, and thrown by [RpcClient] when the server returns one.
 */
open class RpcException(
    val code: Int,
    message: String,
    val data: JsonElement? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    constructor(error: RpcError) : this(error.code, error.message, error.data)

    val error: RpcError get() = RpcError(code, message ?: "", data)

    /** Actionable hint from the server, or a default one for well-known codes. */
    val hint: String? get() = error.details?.hint ?: defaultHint(code)

    override fun toString(): String = "RpcException($code): $message"

    companion object {
        fun defaultHint(code: Int): String? = when (code) {
            ErrorCodes.ADB_CONTROL_DISABLED -> ErrorHints.ADB_CONTROL_DISABLED
            ErrorCodes.MOCK_APP_NOT_SELECTED -> ErrorHints.MOCK_APP_NOT_SELECTED
            ErrorCodes.PROTOCOL_MISMATCH -> ErrorHints.PROTOCOL_MISMATCH
            ErrorCodes.CONNECTION_LOST -> ErrorHints.CONNECTION_LOST
            ErrorCodes.UNAUTHORIZED -> ErrorHints.UNAUTHORIZED
            else -> null
        }

        private fun withHint(code: Int, message: String, hint: String?) = RpcException(
            code,
            message,
            hint?.let { ProtocolJson.encodeToJsonElement(ErrorData.serializer(), ErrorData(hint = it)) },
        )

        fun invalidParams(message: String) = RpcException(ErrorCodes.INVALID_PARAMS, message)
        fun notFound(message: String) = RpcException(ErrorCodes.NOT_FOUND, message)
        fun invalidState(message: String) = RpcException(ErrorCodes.INVALID_STATE, message)
        fun unavailable(message: String, hint: String? = null) = withHint(ErrorCodes.UNAVAILABLE, message, hint)

        fun adbControlDisabled() = withHint(
            ErrorCodes.ADB_CONTROL_DISABLED,
            "ADB control is disabled in Haunt",
            ErrorHints.ADB_CONTROL_DISABLED,
        )

        fun mockAppNotSelected() = withHint(
            ErrorCodes.MOCK_APP_NOT_SELECTED,
            "Haunt is not the selected mock location app",
            ErrorHints.MOCK_APP_NOT_SELECTED,
        )

        fun unauthorized() = withHint(ErrorCodes.UNAUTHORIZED, "Connection refused: not adb", ErrorHints.UNAUTHORIZED)
    }
}

/** The client gave up waiting for a response. */
class RpcTimeoutException(method: String, timeoutMillis: Long) :
    RpcException(ErrorCodes.TIMEOUT, "No response to '$method' within ${timeoutMillis}ms")

/** The transport closed or failed; pending and future calls fail with this. */
class ConnectionLostException(message: String = "Connection to Haunt lost", cause: Throwable? = null) :
    RpcException(ErrorCodes.CONNECTION_LOST, message, cause = cause)
