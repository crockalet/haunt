package io.github.crockalet.haunt.protocol

/**
 * Wire protocol shared by the Android control server and the `haunt` CLI (DESIGN §6).
 *
 * Transport: newline-delimited JSON-RPC 2.0 over the abstract-namespace socket [SOCKET_NAME]
 * (reached from the computer with `adb forward tcp:0 localabstract:haunt`). The first request on a
 * connection must be [Methods.HELLO].
 */
object Protocol {
    /** Bumped on incompatible wire changes. Additive changes (new optional fields/methods) keep it. */
    const val VERSION = 1

    /** Oldest client protocol version a server still accepts. */
    const val MIN_SUPPORTED_VERSION = 1

    /** Abstract-namespace socket name the app listens on (`adb forward tcp:N localabstract:haunt`). */
    const val SOCKET_NAME = "haunt"

    /** Application ID of the Android app. */
    const val APP_PACKAGE = "io.github.crockalet.haunt"

    /** Fully-qualified class of the lightweight foreground service that hosts the control socket. */
    const val CONTROL_SERVICE_CLASS = "io.github.crockalet.haunt.android.ControlService"

    /** Component name for `adb shell am start-foreground-service -n <component>`. */
    const val CONTROL_SERVICE_COMPONENT = "$APP_PACKAGE/.android.ControlService"

    /** Fully-qualified class of the broadcast-fallback receiver (DESIGN §6.2). */
    const val COMMAND_RECEIVER_CLASS = "io.github.crockalet.haunt.android.AdbCommandReceiver"

    /** Component name for `adb shell am broadcast -n <component> -a haunt.SET ...`. */
    const val COMMAND_RECEIVER_COMPONENT = "$APP_PACKAGE/.android.AdbCommandReceiver"

    /** Linux uids allowed to connect to the control socket: shell (adb) and root. */
    val ALLOWED_PEER_UIDS: Set<Int> = setOf(2000, 0)
}

/**
 * Broadcast fallback channel (DESIGN §6.2), for agents without the CLI:
 * ```
 * adb shell am broadcast -n io.github.crockalet.haunt/.android.AdbCommandReceiver \
 *     -a haunt.SET --ed lat 35.6586 --ed lng 139.7454
 * ```
 * The receiver answers via `setResultData` with a JSON document: on success the JSON-RPC result of
 * the equivalent method, otherwise `{"error": {code, message, data?}}` (see [RpcError]).
 */
object Broadcast {
    const val ACTION_SET = "haunt.SET"
    const val ACTION_STOP = "haunt.STOP"
    const val ACTION_PAUSE = "haunt.PAUSE"
    const val ACTION_RESUME = "haunt.RESUME"
    const val ACTION_STATUS = "haunt.STATUS"

    /** Double extras (`--ed`). */
    const val EXTRA_LAT = "lat"
    const val EXTRA_LNG = "lng"
    const val EXTRA_ALTITUDE = "alt"

    /** Float extra (`--ef`). */
    const val EXTRA_ACCURACY = "acc"

    /** String extra (`--es`): geocoded on device instead of lat/lng. */
    const val EXTRA_QUERY = "query"

    /** Permission guarding the receiver; held by the shell user, not grantable to third-party apps. */
    const val RECEIVER_PERMISSION = "android.permission.DUMP"
}
