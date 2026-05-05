import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

data class TimeoutConfig(
    val requestMs: Long = 60_000,
    val connectMs: Long = 10_000,
    val socketMs: Long = 30_000,
) {
    init {
        require(requestMs > 0) { "requestMs must be > 0, got $requestMs" }
        require(connectMs > 0) { "connectMs must be > 0, got $connectMs" }
        require(socketMs > 0)  { "socketMs must be > 0, got $socketMs" }
    }
}

// request = ceiling on a single chunk's whole HTTP exchange (every GET we issue is one chunk).
// connect = TCP handshake before any bytes flow; cheap to fail fast on dead hosts.
// socket = max gap between bytes mid-stream; catches a server that accepts the connection then stalls.
fun createDownloadClient(
    timeouts: TimeoutConfig = TimeoutConfig(),
): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = timeouts.requestMs
        connectTimeoutMillis = timeouts.connectMs
        socketTimeoutMillis = timeouts.socketMs
    }
}
