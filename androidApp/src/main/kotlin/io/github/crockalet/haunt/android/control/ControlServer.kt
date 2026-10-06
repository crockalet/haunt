package io.github.crockalet.haunt.android.control

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import io.github.crockalet.haunt.android.data.ActivityLog
import io.github.crockalet.haunt.android.data.ActivitySource
import io.github.crockalet.haunt.protocol.JsonRpcResponse
import io.github.crockalet.haunt.protocol.Protocol
import io.github.crockalet.haunt.protocol.RpcException
import io.github.crockalet.haunt.protocol.RpcServer
import io.github.crockalet.haunt.protocol.encodeLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections

/**
 * The ADB control socket (DESIGN §6.1): `LocalServerSocket(Protocol.SOCKET_NAME)` in the abstract
 * namespace, reached from the computer with `adb forward tcp:N localabstract:haunt`.
 *
 * Every accepted connection is checked against [Protocol.ALLOWED_PEER_UIDS] (adb shell / root) via
 * `SO_PEERCRED`; others get one UNAUTHORIZED error line and are closed. Each allowed connection
 * runs its own [RpcServer.serve] on IO. Hosted by `ControlService`.
 */
class ControlServer(
    private val scope: CoroutineScope,
    private val rpcServer: RpcServer,
    private val activityLog: ActivityLog,
    private val socketName: String = Protocol.SOCKET_NAME,
    private val allowedUids: Set<Int> = Protocol.ALLOWED_PEER_UIDS,
) {
    private val _connections = MutableStateFlow(0)

    /** Number of open, authorised client connections. */
    val connections: StateFlow<Int> = _connections.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var serverSocket: LocalServerSocket? = null
    private var acceptJob: Job? = null
    private val clients: MutableSet<LocalSocket> = Collections.synchronizedSet(HashSet())

    /** Starts listening; idempotent. @throws IOException if the socket name is taken. */
    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val server = LocalServerSocket(socketName)
        serverSocket = server
        _running.value = true
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
        Log.i(TAG, "Listening on @$socketName")
    }

    /** Stops listening and closes all connections; idempotent. */
    @Synchronized
    fun stop() {
        val server = serverSocket ?: return
        serverSocket = null
        _running.value = false
        // LocalServerSocket.close() doesn't reliably unblock accept(); poke it with a connection.
        runCatching { server.close() }
        runCatching { LocalSocket().use { it.connect(LocalSocketAddress(socketName)) } }
        acceptJob?.cancel()
        acceptJob = null
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
    }

    private suspend fun acceptLoop(server: LocalServerSocket) {
        while (scope.isActive && serverSocket === server) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (serverSocket !== server) break
                Log.w(TAG, "accept failed", e)
                delay(200)
                continue
            }
            if (serverSocket !== server) {
                runCatching { socket.close() }
                break
            }
            scope.launch(Dispatchers.IO) { handle(socket) }
        }
    }

    private suspend fun handle(socket: LocalSocket) {
        val uid = try {
            socket.peerCredentials.uid
        } catch (e: IOException) {
            -1
        }
        if (uid !in allowedUids) {
            Log.w(TAG, "Rejected connection from uid $uid")
            val error = RpcException.unauthorized().error
            activityLog.record(ActivitySource.Socket, "connect", "uid=$uid", error)
            runCatching {
                socket.outputStream.write((JsonRpcResponse(error = error).encodeLine() + "\n").toByteArray())
                socket.outputStream.flush()
            }
            runCatching { socket.close() }
            return
        }

        clients += socket
        _connections.update { it + 1 }
        try {
            val reader = socket.inputStream.bufferedReader(Charsets.UTF_8)
            val writer = socket.outputStream.bufferedWriter(Charsets.UTF_8)
            val incoming = flow {
                while (true) {
                    val line = try {
                        reader.readLine()
                    } catch (_: IOException) {
                        null
                    } ?: break
                    emit(line)
                }
            }.flowOn(Dispatchers.IO)
            rpcServer.serve(incoming) { line ->
                withContext(Dispatchers.IO) {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Connection closed: $e")
        } finally {
            clients -= socket
            _connections.update { it - 1 }
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val TAG = "HauntControl"
    }
}
