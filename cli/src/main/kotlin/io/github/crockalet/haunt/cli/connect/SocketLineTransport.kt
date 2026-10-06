package io.github.crockalet.haunt.cli.connect

import io.github.crockalet.haunt.protocol.LineTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/** Newline-delimited UTF-8 lines over a TCP socket (the `adb forward` end of the control socket). */
class SocketLineTransport(private val socket: Socket) : LineTransport {
    private val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
    private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
    private val writeLock = Any()

    override val incoming: Flow<String> = flow {
        while (true) {
            val line = try {
                reader.readLine()
            } catch (e: SocketException) {
                if (socket.isClosed) null else throw e
            } ?: break
            emit(line)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun send(line: String) = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            if (socket.isClosed) throw IOException("socket closed")
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** Connects to `127.0.0.1:[port]`. @throws IOException */
        fun connect(port: Int, timeoutMillis: Int = 3_000): SocketLineTransport {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMillis)
            return SocketLineTransport(socket)
        }
    }
}
