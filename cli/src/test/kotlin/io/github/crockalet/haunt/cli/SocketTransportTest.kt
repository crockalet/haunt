package io.github.crockalet.haunt.cli

import io.github.crockalet.haunt.cli.connect.SocketLineTransport
import io.github.crockalet.haunt.core.HauntState
import io.github.crockalet.haunt.protocol.ConnectionLostException
import io.github.crockalet.haunt.protocol.HauntEvent
import io.github.crockalet.haunt.protocol.RpcClient
import io.github.crockalet.haunt.protocol.RpcServer
import io.github.crockalet.haunt.protocol.SetLocationParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Client and server over a real loopback TCP socket, as with `adb forward`. */
class SocketTransportTest {
    @Test
    fun roundTripOverTcpAndDisconnect(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(20_000) {
                val api = TestApi()
                val server = RpcServer(api, MutableSharedFlow<HauntEvent>())
                val listener = ServerSocket(0)
                val serverSide = CompletableDeferred<SocketLineTransport>()
                scope.launch {
                    val transport = SocketLineTransport(withContext(Dispatchers.IO) { listener.accept() })
                    serverSide.complete(transport)
                    server.serve(transport.incoming) { transport.send(it) }
                }
                val client = RpcClient(SocketLineTransport.connect(listener.localPort), scope)
                assertEquals("foss", client.hello("test").flavour)
                client.setLocation(SetLocationParams(-33.85, 151.21))
                assertIs<HauntState.Holding>(client.status().state)

                // Server goes away → client notices and fails fast.
                listener.close()
                serverSide.await().close()
                client.connected.first { !it }
                assertFailsWith<ConnectionLostException> { client.status() }
            }
        } finally {
            scope.cancel()
        }
    }
}
