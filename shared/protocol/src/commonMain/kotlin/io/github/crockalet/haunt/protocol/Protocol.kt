package io.github.crockalet.haunt.protocol

/** Wire protocol shared by the Android control server and the `haunt` CLI. */
object Protocol {
    const val VERSION = 1

    /** Abstract-namespace socket name the app listens on (`adb forward tcp:N localabstract:haunt`). */
    const val SOCKET_NAME = "haunt"
}
