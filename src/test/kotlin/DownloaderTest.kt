import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dest(name: String = "out.bin"): Path = tempDir.resolve(name)

    private fun mockClient(handler: MockRequestHandler) = HttpClient(MockEngine(handler))

    private fun headHeaders(length: Long, acceptRanges: String? = "bytes"): Headers =
        Headers.build {
            append(HttpHeaders.ContentLength, length.toString())
            if (acceptRanges != null) append(HttpHeaders.AcceptRanges, acceptRanges)
        }

    private fun MockRequestHandleScope.headOk(length: Long, acceptRanges: String? = "bytes") =
        respond(
            content = ByteArray(0),
            status = HttpStatusCode.OK,
            headers = headHeaders(length, acceptRanges),
        )

    private fun parseRange(req: HttpRequestData): Pair<Long, Long> {
        val raw = req.headers[HttpHeaders.Range] ?: error("missing Range header")
        require(raw.startsWith("bytes=")) { "unexpected Range syntax: $raw" }
        val (s, e) = raw.removePrefix("bytes=").split("-")
        return s.toLong() to e.toLong()
    }

    private fun MockRequestHandleScope.serve(payload: ByteArray, req: HttpRequestData) =
        when (req.method) {
            HttpMethod.Head -> headOk(payload.size.toLong())
            HttpMethod.Get -> {
                val (s, e) = parseRange(req)
                respond(
                    content = payload.copyOfRange(s.toInt(), (e + 1).toInt()),
                    status = HttpStatusCode.PartialContent,
                    headers = Headers.build {
                        append(HttpHeaders.ContentRange, "bytes $s-$e/${payload.size}")
                    },
                )
            }
            else -> error("unexpected method ${req.method}")
        }

    @Test
    fun `happy path multi chunk`() = runTest {
        val payload = Random(42).nextBytes(10_000)
        mockClient { req -> serve(payload, req) }.use { client ->
            val out = dest()
            download(client, "http://test/x", out, chunkSize = 2048)
            assertContentEquals(payload, Files.readAllBytes(out))
        }
    }

    @Test
    fun `smaller than chunkSize`() = runTest {
        val payload = Random(1).nextBytes(500)
        mockClient { req -> serve(payload, req) }.use { client ->
            val out = dest()
            download(client, "http://test/x", out, chunkSize = 4096)
            assertContentEquals(payload, Files.readAllBytes(out))
        }
    }

    @Test
    fun `short last chunk`() = runTest {
        val payload = Random(2).nextBytes(1000)
        mockClient { req -> serve(payload, req) }.use { client ->
            val out = dest()
            download(client, "http://test/x", out, chunkSize = 384)
            assertContentEquals(payload, Files.readAllBytes(out))
        }
    }

    @Test
    fun `range headers cover all chunks exactly once`() = runTest {
        val payload = Random(7).nextBytes(1000)
        val seen = ConcurrentLinkedQueue<String>()
        mockClient { req ->
            if (req.method == HttpMethod.Get) {
                seen += req.headers[HttpHeaders.Range] ?: error("missing Range header")
            }
            serve(payload, req)
        }.use { client ->
            download(client, "http://test/x", dest(), chunkSize = 384)
        }
        assertEquals(
            listOf("bytes=0-383", "bytes=384-767", "bytes=768-999"),
            seen.sortedBy { it.removePrefix("bytes=").substringBefore("-").toLong() },
        )
    }

    @Test
    fun `missing Accept-Ranges throws`() = runTest {
        mockClient { req ->
            require(req.method == HttpMethod.Head)
            headOk(length = 100, acceptRanges = null)
        }.use { client ->
            assertFailsWith<RangesUnsupported> {
                download(client, "http://test/x", dest())
            }
        }
    }

    @Test
    fun `Accept-Ranges of 'none' throws`() = runTest {
        mockClient { req ->
            require(req.method == HttpMethod.Head)
            headOk(length = 100, acceptRanges = "none")
        }.use { client ->
            assertFailsWith<RangesUnsupported> {
                download(client, "http://test/x", dest())
            }
        }
    }

    @Test
    fun `chunk failure propagates and download throws`() = runTest {
        val payload = Random(3).nextBytes(2000)
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> headOk(payload.size.toLong())
                HttpMethod.Get -> {
                    val (s, _) = parseRange(req)
                    if (s == 1024L) {
                        respond(ByteArray(0), HttpStatusCode.InternalServerError)
                    } else {
                        serve(payload, req)
                    }
                }
                else -> error(req.method)
            }
        }.use { client ->
            val ex = assertFailsWith<RetriesExhausted> {
                download(
                    client = client,
                    url = "http://test/x",
                    destination = dest(),
                    chunkSize = 1024,
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                )
            }
            assertEquals(true, ex.cause is UnexpectedStatus)
            assertEquals(500, (ex.cause as UnexpectedStatus).status.value)
        }
    }

    @Test
    fun `server returns 200 instead of 206 throws`() = runTest {
        val payload = Random(4).nextBytes(1000)
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> headOk(payload.size.toLong())
                HttpMethod.Get -> respond(payload, HttpStatusCode.OK)
                else -> error(req.method)
            }
        }.use { client ->
            val ex = assertFailsWith<UnexpectedStatus> {
                download(
                    client = client,
                    url = "http://test/x",
                    destination = dest(),
                    chunkSize = 256,
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                )
            }
            assertEquals(200, ex.status.value)
        }
    }

    @Test
    fun `non-2xx HEAD throws`() = runTest {
        mockClient { req ->
            require(req.method == HttpMethod.Head)
            respond(ByteArray(0), HttpStatusCode.NotFound)
        }.use { client ->
            val ex = assertFailsWith<UnexpectedStatus> {
                download(client, "http://test/x", dest())
            }
            assertEquals(404, ex.status.value)
        }
    }

    @Test
    fun `missing Content-Length throws`() = runTest {
        mockClient { req ->
            require(req.method == HttpMethod.Head)
            respond(
                ByteArray(0),
                HttpStatusCode.OK,
                Headers.build { append(HttpHeaders.AcceptRanges, "bytes") },
            )
        }.use { client ->
            val ex = assertFailsWith<MetadataMissing> {
                download(client, "http://test/x", dest())
            }
            assertEquals("Content-Length", ex.header)
        }
    }

    @Test
    fun `body length mismatch throws`() = runTest {
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> headOk(1000)
                HttpMethod.Get -> {
                    val (s, e) = parseRange(req)
                    respond(
                        ByteArray(50),
                        HttpStatusCode.PartialContent,
                        Headers.build {
                            append(HttpHeaders.ContentRange, "bytes $s-$e/1000")
                        },
                    )
                }
                else -> error(req.method)
            }
        }.use { client ->
            val ex = assertFailsWith<LengthMismatch> {
                download(
                    client = client,
                    url = "http://test/x",
                    destination = dest(),
                    chunkSize = 256,
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                )
            }
            // 1000-byte file at chunkSize=256 plans four chunks {256, 256, 256, 232}; any of them
            // can win the race to throw, so accept either expected length.
            assertEquals(50L, ex.actual)
            assertTrue(
                ex.expected in setOf(256L, 232L),
                "ex.expected=${ex.expected}, expected one of {256, 232}",
            )
        }
    }

    @Test
    fun `failed download leaves no file at destination and cleans up temp`() = runTest {
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> headOk(1000)
                HttpMethod.Get -> respond(ByteArray(0), HttpStatusCode.InternalServerError)
                else -> error(req.method)
            }
        }.use { client ->
            val out = dest("partial.bin")
            assertFailsWith<RetriesExhausted> {
                download(
                    client = client,
                    url = "http://test/x",
                    destination = out,
                    chunkSize = 256,
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                )
            }
            assertFalse(Files.exists(out), "destination should not exist after failure")
            // tmp absence also implies the FileChannel handle was released — on Windows a leaked
            // handle would have made deleteIfExists silently fail (it's wrapped in runCatching).
            val tmp = out.resolveSibling("${out.fileName}.part")
            assertFalse(Files.exists(tmp), "temp .part file should be cleaned up")
        }
    }

    @Test
    fun `happy path leaves no temp file`() = runTest {
        val payload = Random(99).nextBytes(1000)
        mockClient { req -> serve(payload, req) }.use { client ->
            val out = dest()
            download(client, "http://test/x", out, chunkSize = 256)
            assertFalse(Files.exists(out.resolveSibling("${out.fileName}.part")))
        }
    }

    @Test
    fun `wrong Content-Range fails the download`() = runTest {
        val payload = Random(13).nextBytes(500)
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> headOk(payload.size.toLong())
                HttpMethod.Get -> {
                    val (s, e) = parseRange(req)
                    respond(
                        content = payload.copyOfRange(s.toInt(), (e + 1).toInt()),
                        status = HttpStatusCode.PartialContent,
                        headers = Headers.build {
                            // Off-by-100: claim a different start than what the Range requested.
                            append(HttpHeaders.ContentRange, "bytes ${s + 100}-${e + 100}/${payload.size}")
                        },
                    )
                }
                else -> error(req.method)
            }
        }.use { client ->
            assertFailsWith<ContentRangeMismatch> {
                download(
                    client = client,
                    url = "http://test/x",
                    destination = dest(),
                    chunkSize = 256,
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                )
            }
        }
    }

    @Test
    fun `Content-Range with star total is accepted`() = runTest {
        val payload = Random(14).nextBytes(500)
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> headOk(payload.size.toLong())
                HttpMethod.Get -> {
                    val (s, e) = parseRange(req)
                    respond(
                        content = payload.copyOfRange(s.toInt(), (e + 1).toInt()),
                        status = HttpStatusCode.PartialContent,
                        headers = Headers.build {
                            append(HttpHeaders.ContentRange, "bytes $s-$e/*")
                        },
                    )
                }
                else -> error(req.method)
            }
        }.use { client ->
            val out = dest()
            download(client, "http://test/x", out, chunkSize = 256)
            assertContentEquals(payload, Files.readAllBytes(out))
        }
    }

    @Test
    fun `oversize chunk body fails and leaves no files behind`() = runTest {
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> headOk(100)
                HttpMethod.Get -> {
                    val (s, e) = parseRange(req)
                    respond(
                        ByteArray(200) { it.toByte() },
                        HttpStatusCode.PartialContent,
                        Headers.build {
                            append(HttpHeaders.ContentRange, "bytes $s-$e/100")
                        },
                    )
                }
                else -> error(req.method)
            }
        }.use { client ->
            val out = dest("oversize.bin")
            val ex = assertFailsWith<LengthMismatch> {
                download(
                    client = client,
                    url = "http://test/x",
                    destination = out,
                    chunkSize = 256,
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                )
            }
            assertEquals(100L, ex.expected)
            assertEquals(200L, ex.actual)
            assertFalse(Files.exists(out), "destination should not exist")
            val tmp = out.resolveSibling("${out.fileName}.part")
            assertFalse(Files.exists(tmp), "temp .part should be cleaned up")
        }
    }

    @Test
    fun `chunk much larger than stream buffer is assembled at correct offsets`() = runTest {
        val payload = Random(123).nextBytes(8 * 1024 * 1024)
        mockClient { req -> serve(payload, req) }.use { client ->
            val out = dest()
            // 8 MiB payload, 4 MiB chunkSize -> 2 chunks at offsets 0 and 4 MiB.
            // Each chunk passes through the 64 KiB stream buffer ~64 times.
            download(client, "http://test/x", out, chunkSize = 4L * 1024 * 1024)
            assertContentEquals(payload, Files.readAllBytes(out))
        }
    }

    @Test
    fun `existing destination is overwritten with new contents`() = runTest {
        val payload = Random(7).nextBytes(500)
        mockClient { req -> serve(payload, req) }.use { client ->
            val out = dest()
            Files.write(out, "stale".toByteArray())
            download(client, "http://test/x", out, chunkSize = 256)
            assertContentEquals(payload, Files.readAllBytes(out))
        }
    }

    @Test
    fun `empty file produces empty output`() = runTest {
        mockClient { req ->
            require(req.method == HttpMethod.Head)
            headOk(length = 0)
        }.use { client ->
            val out = dest()
            download(client, "http://test/x", out, chunkSize = 256)
            assertEquals(0L, Files.size(out))
        }
    }

    @Test
    fun `retries 503 then succeeds with exactly 3 GETs`() = runTest {
        val payload = Random(5).nextBytes(500)
        val getsByStart = ConcurrentHashMap<Long, AtomicInteger>()
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> headOk(payload.size.toLong())
                HttpMethod.Get -> {
                    val (s, _) = parseRange(req)
                    val n = getsByStart.computeIfAbsent(s) { AtomicInteger(0) }.incrementAndGet()
                    if (s == 0L && n <= 2) {
                        respond(ByteArray(0), HttpStatusCode.ServiceUnavailable)
                    } else {
                        serve(payload, req)
                    }
                }
                else -> error(req.method)
            }
        }.use { client ->
            download(client, "http://test/x", dest(), chunkSize = 256)
        }
        assertEquals(3, getsByStart[0L]!!.get())
    }

    @Test
    fun `404 fails immediately without retry`() = runTest {
        val gets = AtomicInteger(0)
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> headOk(256)
                HttpMethod.Get -> {
                    gets.incrementAndGet()
                    respond(ByteArray(0), HttpStatusCode.NotFound)
                }
                else -> error(req.method)
            }
        }.use { client ->
            val ex = assertFailsWith<UnexpectedStatus> {
                download(client, "http://test/x", dest(), chunkSize = 256)
            }
            assertEquals(404, ex.status.value)
        }
        assertEquals(1, gets.get())
    }

    @Test
    fun `503 always throws after retries exhausted`() = runTest {
        val gets = AtomicInteger(0)
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> headOk(256)
                HttpMethod.Get -> {
                    gets.incrementAndGet()
                    respond(ByteArray(0), HttpStatusCode.ServiceUnavailable)
                }
                else -> error(req.method)
            }
        }.use { client ->
            val ex = assertFailsWith<RetriesExhausted> {
                download(
                    client = client,
                    url = "http://test/x",
                    destination = dest(),
                    chunkSize = 256,
                    retryPolicy = RetryPolicy(maxAttempts = 3),
                )
            }
            assertEquals(3, ex.attempts)
            assertEquals(503, (ex.cause as UnexpectedStatus).status.value)
        }
        assertEquals(3, gets.get())
    }

    @Test
    fun `HEAD probe retries on 503 then succeeds`() = runTest {
        val payload = Random(20).nextBytes(256)
        val heads = AtomicInteger(0)
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> {
                    if (heads.incrementAndGet() == 1) {
                        respond(ByteArray(0), HttpStatusCode.ServiceUnavailable)
                    } else {
                        headOk(payload.size.toLong())
                    }
                }
                HttpMethod.Get -> serve(payload, req)
                else -> error(req.method)
            }
        }.use { client ->
            download(client, "http://test/x", dest(), chunkSize = 256)
        }
        assertEquals(2, heads.get())
    }

    @Test
    fun `HEAD probe IOException is wrapped as DownloadException`() = runTest {
        mockClient { _ -> throw java.io.IOException("simulated network failure") }.use { client ->
            assertFailsWith<RetriesExhausted> {
                download(
                    client = client,
                    url = "http://test/x",
                    destination = dest(),
                    retryPolicy = RetryPolicy(maxAttempts = 1),
                )
            }
        }
    }

    @Test
    fun `Retry-After header is honored over backoff`() = runTest {
        val payload = Random(8).nextBytes(256)
        val gets = AtomicInteger(0)
        val before = currentTime
        mockClient { req ->
            when (req.method) {
                HttpMethod.Head -> headOk(payload.size.toLong())
                HttpMethod.Get -> {
                    val n = gets.incrementAndGet()
                    if (n == 1) {
                        respond(
                            ByteArray(0),
                            HttpStatusCode.ServiceUnavailable,
                            Headers.build { append(HttpHeaders.RetryAfter, "1") },
                        )
                    } else {
                        serve(payload, req)
                    }
                }
                else -> error(req.method)
            }
        }.use { client ->
            download(client, "http://test/x", dest(), chunkSize = 256)
        }
        assertEquals(1000L, currentTime - before)
    }
}
