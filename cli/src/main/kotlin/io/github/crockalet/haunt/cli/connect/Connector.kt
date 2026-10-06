package io.github.crockalet.haunt.cli.connect

import io.github.crockalet.haunt.cli.HauntFailure
import io.github.crockalet.haunt.cli.adb.Adb
import io.github.crockalet.haunt.cli.adb.AdbDevice
import io.github.crockalet.haunt.protocol.ConnectionLostException
import io.github.crockalet.haunt.protocol.ErrorCodes
import io.github.crockalet.haunt.protocol.HelloParams
import io.github.crockalet.haunt.protocol.HelloResult
import io.github.crockalet.haunt.protocol.LineTransport
import io.github.crockalet.haunt.protocol.Protocol
import io.github.crockalet.haunt.protocol.Rpc
import io.github.crockalet.haunt.protocol.RpcClient
import io.github.crockalet.haunt.protocol.RpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Opens a line transport to a forwarded local TCP port. @throws IOException */
fun interface TransportFactory {
    fun open(port: Int): LineTransport
}

/** A live, handshaken connection to Haunt on one device. Close it to drop the adb forward. */
class HauntSession(
    val device: AdbDevice,
    val client: RpcClient,
    val hello: HelloResult,
    private val onClose: () -> Unit,
) : AutoCloseable {
    val connected: Boolean get() = client.connected.value

    override fun close() = onClose()
}

/**
 * Connects to Haunt over adb: pick device → `adb forward tcp:0 localabstract:haunt` → TCP → `hello`.
 * If the app isn't listening, starts its control service with `am start-foreground-service` and retries.
 */
class Connector(
    private val adb: Adb,
    private val transports: TransportFactory = TransportFactory { SocketLineTransport.connect(it) },
    private val clientName: String = "haunt-cli",
    private val callTimeout: Duration = 30.seconds,
    private val helloTimeout: Duration = 3.seconds,
    private val startupWait: Duration = 8.seconds,
    private val retryDelay: Duration = 400.milliseconds,
) {
    fun devices(): List<AdbDevice> = adb.devices()

    /** @throws HauntFailure */
    suspend fun connect(serial: String?): HauntSession {
        val device = withContext(Dispatchers.IO) { Adb.select(adb.devices(), serial) }
        val port = withContext(Dispatchers.IO) { adb.forward(device.serial) }
        try {
            return handshake(device, port)
        } catch (e: Throwable) {
            withContext(Dispatchers.IO) { adb.removeForward(device.serial, port) }
            throw e
        }
    }

    private suspend fun handshake(device: AdbDevice, port: Int): HauntSession {
        var started = false
        var waited = Duration.ZERO
        while (true) {
            val attempt = tryHello(device, port)
            if (attempt != null) return attempt
            // Nobody listening on the device side (adb accepts the TCP connection, then drops it).
            if (!started) {
                withContext(Dispatchers.IO) { adb.startControlService(device.serial) }
                started = true
            }
            if (waited >= startupWait) {
                throw HauntFailure.notConnected(
                    "Haunt is not responding on ${device.displayName}",
                    "Open the Haunt app on the phone once (and allow its notification), then retry.",
                )
            }
            delay(retryDelay)
            waited += retryDelay
        }
    }

    /** One connection attempt; null if the app isn't listening yet. */
    private suspend fun tryHello(device: AdbDevice, port: Int): HauntSession? {
        val transport = try {
            withContext(Dispatchers.IO) { transports.open(port) }
        } catch (_: IOException) {
            return null
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = RpcClient(transport, scope, callTimeout)
        val hello = try {
            client.call(Rpc.Hello, HelloParams(Protocol.VERSION, clientName), helloTimeout)
        } catch (e: RpcException) {
            client.close()
            scope.cancel()
            if (e is ConnectionLostException || e.code == ErrorCodes.TIMEOUT) return null
            throw HauntFailure.from(e)
        }
        return HauntSession(device, client, hello) {
            client.close()
            scope.cancel()
            adb.removeForward(device.serial, port)
        }
    }
}
