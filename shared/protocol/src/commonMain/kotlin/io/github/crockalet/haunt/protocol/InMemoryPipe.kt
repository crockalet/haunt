package io.github.crockalet.haunt.protocol

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

/** One end of an in-memory, line-oriented connection. */
class ChannelLineTransport(private val inbox: Channel<String>, private val outbox: Channel<String>) : LineTransport {
    override val incoming: Flow<String> = inbox.consumeAsFlow()
    override suspend fun send(line: String) = outbox.send(line)
    override fun close() {
        outbox.close()
        inbox.cancel()
    }
}

/**
 * An in-process, full-duplex connection between an [RpcClient] ([client]) and an [RpcServer]
 * ([serverIncoming] / [serverSend]). For tests and for running client and server in one process.
 * ```
 * val pipe = InMemoryPipe()
 * scope.launch { server.serve(pipe.serverIncoming, pipe::serverSend); pipe.serverHangUp() }
 * val client = RpcClient(pipe.client, scope)
 * ```
 */
class InMemoryPipe {
    private val toServer = Channel<String>(Channel.UNLIMITED)
    private val toClient = Channel<String>(Channel.UNLIMITED)

    val client: LineTransport = ChannelLineTransport(toClient, toServer)
    val serverIncoming: Flow<String> = toServer.consumeAsFlow()

    suspend fun serverSend(line: String) = toClient.send(line)

    /** Closes the server → client direction (the client sees the connection end). */
    fun serverHangUp() {
        toClient.close()
    }
}
