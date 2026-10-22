package eu.kanade.tachiyomi.sync

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import xyz.altaf.kioku.sync.PROTOCOL_HEADER
import xyz.altaf.kioku.sync.PROTOCOL_VERSION
import xyz.altaf.kioku.sync.proto.Frame
import xyz.altaf.kioku.sync.proto.HelloRequest
import xyz.altaf.kioku.sync.proto.HelloResponse
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * One WebSocket session with the sync server. The handshake carries the
 * protocol version in both directions — a server that does not name the
 * same version is refused before any frame is exchanged. Frames arrive on a
 * channel; sending is suspending and fails fast once the session is closed.
 */
class SyncConnection(
    private val network: NetworkHelper,
    private val serverUrl: String,
    private val apiKey: String,
) {
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    private val inbound = Channel<Frame>(capacity = Channel.BUFFERED)
    private val closed = Channel<Unit>(capacity = 1)

    val isOpen: Boolean get() = !closed.isClosedForReceive

    suspend fun open(hello: HelloRequest, timeout: Duration): HelloResponse {
        // A private pool: this connection must never disturb, or be disturbed
        // by, the app's other sockets when it closes.
        val base = network.client.newBuilder()
            .connectionPool(ConnectionPool())
            .pingInterval(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        client = base

        val helloDeferred = kotlinx.coroutines.CompletableDeferred<HelloResponse>()
        socket = base.newWebSocket(
            Request.Builder()
                .url(serverUrl.trimEnd('/') + "/ws")
                .header("Authorization", "Bearer $apiKey")
                .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val version = response.header(PROTOCOL_HEADER)
                    if (version != PROTOCOL_VERSION) {
                        helloDeferred.completeExceptionally(
                            SyncException(
                                if (version == null) {
                                    "the server did not identify its sync protocol version; upgrade kioku-sync"
                                } else {
                                    "the server speaks sync protocol $version, this app speaks $PROTOCOL_VERSION; upgrade Kioku and kioku-sync to matching versions"
                                },
                            ),
                        )
                        webSocket.close(1000, null)
                        close()
                        return
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    val frame = Frame.parseFrom(bytes.toByteArray())
                    if (!helloDeferred.isCompleted) {
                        val response = frame.helloResponse
                        if (response != null) {
                            helloDeferred.complete(response)
                            return
                        }
                    }
                    inbound.trySend(frame)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    if (!helloDeferred.isCompleted) {
                        val detail = when {
                            response == null -> t.message ?: t.javaClass.simpleName
                            response.code == 400 -> "the server refused this sync protocol version; upgrade Kioku and kioku-sync to matching versions"
                            response.code == 401 -> "the server rejected the API key"
                            else -> "HTTP ${response.code}"
                        }
                        helloDeferred.completeExceptionally(SyncException(detail))
                    }
                    close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close()
                }
            },
        )

        val response = withTimeout(timeout) {
            send(
                Frame.newBuilder()
                    .setHelloRequest(hello)
                    .build(),
            )
            helloDeferred.await()
        }
        if (!response.error.isEmpty()) {
            throw SyncException("Server refused the session: ${response.error}")
        }
        return response
    }

    suspend fun receive(): Frame? = inbound.receiveCatching().getOrNull()

    /**
     * Suspends until an inbound frame arrives or [signal] fires, so new local
     * writes can wake an otherwise quiet session. Returns the frame, or null
     * when woken by the signal or after the connection closed ([isOpen]).
     */
    suspend fun awaitFrameOr(signal: ReceiveChannel<Unit>): Frame? = select {
        inbound.onReceiveCatching { it.getOrNull() }
        signal.onReceive { null }
    }

    suspend fun send(frame: Frame): Boolean {
        val ws = socket ?: return false
        return ws.send(frame.toByteArray().toByteString())
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
        client?.connectionPool?.evictAll()
        client = null
        inbound.close()
        closed.close()
    }

    companion object {
        fun hello(deviceId: String, deviceName: String, appVersion: String, lastSeq: Long): HelloRequest =
            HelloRequest.newBuilder()
                .setDeviceId(deviceId)
                .setDeviceName(deviceName)
                .setAppVersion(appVersion)
                .setLastSeq(lastSeq)
                .build()
    }
}

class SyncException(message: String) : Exception(message)

/**
 * Convenience builders for the frames the engine sends.
 */
fun changeBatchFrame(changes: List<xyz.altaf.kioku.sync.proto.Change>): Frame = Frame.newBuilder()
    .setChangeBatch(
        xyz.altaf.kioku.sync.proto.ChangeBatch.newBuilder().addAllChanges(changes),
    )
    .build()

fun snapshotRequestFrame(): Frame = Frame.newBuilder()
    .setSnapshotRequest(xyz.altaf.kioku.sync.proto.SnapshotRequest.getDefaultInstance())
    .build()
