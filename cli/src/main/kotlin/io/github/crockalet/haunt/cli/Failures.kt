package io.github.crockalet.haunt.cli

import io.github.crockalet.haunt.protocol.ErrorCodes
import io.github.crockalet.haunt.protocol.RpcException

/** Process exit codes (DESIGN §6.3). */
enum class ExitCode(val code: Int) {
    OK(0),
    ERROR(1),

    /** No device, adb missing, app not installed/reachable, connection lost. */
    NOT_CONNECTED(2),
    MOCK_APP_NOT_SELECTED(3),
    ADB_CONTROL_DISABLED(4),
}

/** A failure to report to the user: [message] plus an optional actionable [hint]. */
class HauntFailure(
    val exit: ExitCode,
    override val message: String,
    val hint: String? = null,
    val rpcCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** One or two lines: message, then hint. */
    fun describe(): String = if (hint != null) "$message\nhint: $hint" else message

    /** Single sentence form for MCP tool errors. */
    fun describeInline(): String = if (hint != null) "$message. $hint" else message

    companion object {
        fun from(e: RpcException): HauntFailure = HauntFailure(exitFor(e.code), e.message ?: "Error ${e.code}", e.hint, e.code, e)

        fun exitFor(rpcCode: Int): ExitCode = when (rpcCode) {
            ErrorCodes.MOCK_APP_NOT_SELECTED -> ExitCode.MOCK_APP_NOT_SELECTED
            ErrorCodes.ADB_CONTROL_DISABLED -> ExitCode.ADB_CONTROL_DISABLED
            ErrorCodes.CONNECTION_LOST, ErrorCodes.UNAUTHORIZED, ErrorCodes.PROTOCOL_MISMATCH -> ExitCode.NOT_CONNECTED
            else -> ExitCode.ERROR
        }

        fun notConnected(message: String, hint: String? = null) = HauntFailure(ExitCode.NOT_CONNECTED, message, hint)
    }
}
