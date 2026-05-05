import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

private val log: Logger = LoggerFactory.getLogger("Downloader")
private val CONTENT_RANGE = Regex("""^bytes (\d+)-(\d+)/(\d+|\*)$""")

suspend fun download(
    client: HttpClient,
    url: String,
    destination: Path,
    chunkSize: Long = 4L * 1024 * 1024,
    parallelism: Int = 4,
    retryPolicy: RetryPolicy = RetryPolicy(),
) {
    require(chunkSize > 0)   { "chunkSize must be > 0, got $chunkSize" }
    require(parallelism > 0) { "parallelism must be > 0, got $parallelism" }

    val meta = retrying(retryPolicy, "HEAD probe") { fetchMetadata(client, url) }
    if (!meta.acceptsRanges) {
        throw RangesUnsupported("Server does not advertise Accept-Ranges: bytes")
    }

    val chunks = planChunks(meta.contentLength, chunkSize)

    val tmp = destination.resolveSibling("${destination.fileName}.part")
    try {
        RandomAccessFile(tmp.toFile(), "rw").use { raf ->
            raf.setLength(meta.contentLength)
            val channel = raf.channel

            val limiter = Semaphore(parallelism)
            coroutineScope {
                // TODO(resumability): filter `chunks` against a sidecar of completed indices and persist
                // each chunk's completion before writing; remove the sidecar alongside .part on success.
                chunks.map { chunk ->
                    async {
                        limiter.withPermit {
                            downloadChunk(client, url, chunk, channel, retryPolicy, meta.contentLength)
                        }
                    }
                }.awaitAll()
            }
        }
        Files.move(tmp, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (e: Exception) {
        runCatching { Files.deleteIfExists(tmp) }
            .onFailure { log.warn("Failed to delete temp file {}", tmp, it) }
        throw e
    }
}

private suspend fun downloadChunk(
    client: HttpClient,
    url: String,
    chunk: ChunkRange,
    channel: FileChannel,
    retryPolicy: RetryPolicy,
    totalLength: Long,
) {
    retrying(retryPolicy, "chunk ${chunk.index}") {
        val resp: HttpResponse = client.get(url) {
            header(HttpHeaders.Range, "bytes=${chunk.start}-${chunk.endInclusive}")
        }
        if (resp.status != HttpStatusCode.PartialContent) {
            val retryAfterMs = if (resp.status.value == 429 || resp.status.value == 503) {
                parseRetryAfterSeconds(resp.headers[HttpHeaders.RetryAfter], "chunk ${chunk.index}")
            } else null
            throw UnexpectedStatus(
                status = resp.status,
                retryAfterMs = retryAfterMs,
                message = "Expected 206 PartialContent for chunk ${chunk.index} (bytes=${chunk.start}-${chunk.endInclusive}), got ${resp.status}",
            )
        }
        validateContentRange(resp, chunk, totalLength)
        val body = resp.bodyAsChannel()
        val copied = withContext(Dispatchers.IO) {
            channel.streamFrom(body, chunk.start, maxBytes = chunk.length)
        }
        if (copied != chunk.length) {
            throw LengthMismatch(
                expected = chunk.length,
                actual = copied,
                message = "Chunk ${chunk.index}: expected ${chunk.length} bytes but stream ended after $copied",
            )
        }
        if (!body.isClosedForRead) {
            val extra = drainAndCount(body)
            throw LengthMismatch(
                expected = chunk.length,
                actual = chunk.length + extra,
                message = "Chunk ${chunk.index}: server returned ${chunk.length + extra} bytes for range ${chunk.start}-${chunk.endInclusive} (expected ${chunk.length})",
            )
        }
    }
}

private fun validateContentRange(resp: HttpResponse, chunk: ChunkRange, totalLength: Long) {
    val expected = "bytes ${chunk.start}-${chunk.endInclusive}/$totalLength"
    val raw = resp.headers[HttpHeaders.ContentRange]
        ?: throw MetadataMissing(
            header = HttpHeaders.ContentRange,
            message = "Chunk ${chunk.index}: missing Content-Range header (expected $expected)",
        )
    val match = CONTENT_RANGE.matchEntire(raw)
        ?: throw ContentRangeMismatch(
            expected = expected,
            actual = raw,
            message = "Chunk ${chunk.index}: malformed Content-Range '$raw' (expected $expected)",
        )
    val (s, e, t) = match.destructured
    if (s.toLong() != chunk.start || e.toLong() != chunk.endInclusive) {
        throw ContentRangeMismatch(
            expected = expected,
            actual = raw,
            message = "Chunk ${chunk.index}: Content-Range range mismatch, expected $expected got '$raw'",
        )
    }
    if (t != "*" && t.toLong() != totalLength) {
        throw ContentRangeMismatch(
            expected = expected,
            actual = raw,
            message = "Chunk ${chunk.index}: Content-Range total mismatch, expected $totalLength got $t (raw '$raw')",
        )
    }
}

private suspend fun drainAndCount(source: ByteReadChannel): Long {
    val sink = ByteArray(8 * 1024)
    var count = 0L
    while (true) {
        val n = source.readAvailable(sink, 0, sink.size)
        if (n <= 0) break
        count += n
    }
    return count
}
