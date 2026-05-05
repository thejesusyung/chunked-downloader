# Parallel chunked file downloader

## What it does

Downloads large files faster by fetching byte ranges in parallel from any HTTP server that supports `Range` requests. The output is byte-identical to the source, written atomically (no partial files on failure), with bounded memory regardless of file size.

## Quick start

Requires JDK 21. Set `JAVA_HOME` to your JDK 21 install if Gradle can't auto-detect it.

Stand up a 20 MiB random payload behind Apache httpd, download it, verify it:

```bash
mkdir -p /tmp/dlsrc
# macOS uses lowercase `bs=1m`; on Linux use `bs=1M`.
dd if=/dev/urandom of=/tmp/dlsrc/payload.bin bs=1m count=20
docker run --rm -d -p 8080:80 -v /tmp/dlsrc:/usr/local/apache2/htdocs --name dlhttpd httpd:latest

./gradlew run --args="http://localhost:8080/payload.bin /tmp/payload.out"

cmp /tmp/dlsrc/payload.bin /tmp/payload.out   # silent on byte-perfect match
docker stop dlhttpd
```

Build and test:

```bash
./gradlew clean test
```

## What makes it solid

- **Failed downloads leave no half-written files.** Bytes go to `<dest>.part`; on success, an atomic rename to `<dest>`. On failure, the temp is removed and the destination is untouched. Either it's byte-perfect or it doesn't exist.
- **Transient server hiccups are absorbed automatically.** Per-chunk retry with full-jitter exponential backoff covers 408/429/500/502/503/504 and `IOException`. `Retry-After` from the server overrides the computed backoff. Non-retryable failures surface immediately instead of burning attempts.
- **Stalled or unreachable servers fail fast with a clear error.** Explicit connect, socket, and request timeouts (10s / 30s / 60s by default) bound every phase of the exchange. No silent hangs.
- **Bounded memory regardless of file size.** Chunk bodies stream through a 64 KiB buffer with a `maxBytes` cap, so a 20 GiB download has the same memory footprint as a 20 MiB one. No buffering of full responses.
- **The server can't corrupt the output by lying about ranges.** `Content-Range` is parsed and validated against the requested range and total length on every chunk; a mismatched, malformed, or missing header fails the chunk with a typed exception before any bytes are written outside its reserved region.
- **Errors carry enough context to act on.** A sealed `DownloadException` hierarchy (`RangesUnsupported`, `UnexpectedStatus`, `LengthMismatch`, `ContentRangeMismatch`, `MetadataMissing`, `RetriesExhausted`) lets callers branch on the failure shape without parsing strings.

## Architecture

Four roles, one file each. `Metadata.kt` does the HEAD probe and confirms `Accept-Ranges: bytes`. `Planner.kt` splits the file into contiguous, non-overlapping byte ranges; it's a pure function with exhaustive unit coverage. `Downloader.kt` orchestrates N concurrent chunk fetches inside a `coroutineScope`, wrapping each chunk in the retry envelope from `Retry.kt`. `ChannelExt.kt` streams each response body through a small buffer and writes via `FileChannel.write(ByteBuffer, position)`, so chunks land at their correct offsets without a second concatenation pass. Cancellation propagates through structured concurrency: any chunk's failure cancels its in-flight siblings.

```
HEAD url
  -> plan ranges [0..a-1, a..b-1, ...]
  -> N parallel GETs with Range header
       (retry w/ jittered backoff, Retry-After honored)
  -> positional writes into <dest>.part
  -> Files.move(.part -> <dest>, ATOMIC_MOVE)
```

```
src/main/kotlin/
  Metadata.kt    HEAD probe, parse Content-Length / Accept-Ranges
  Planner.kt     compute contiguous chunk ranges
  Downloader.kt  orchestrate parallel chunk fetches + atomic move
  Retry.kt       RetryPolicy, isRetryable classifier, backoff loop
  Client.kt      TimeoutConfig + Ktor CIO HttpClient factory
  ChannelExt.kt  streaming positional writes to FileChannel
  Exceptions.kt  sealed DownloadException hierarchy
  Main.kt        CLI entry point: <url> <destination>
```

## Testing

34 unit tests, ~2 seconds warm:

| Class            | Tests | What it covers                                                                                  |
|------------------|------:|-------------------------------------------------------------------------------------------------|
| `DownloaderTest` |    23 | end-to-end downloads against Ktor `MockEngine`: happy paths, Content-Range edge cases, retry behavior, atomic cleanup |
| `PlannerTest`    |     9 | property-style coverage: chunks are contiguous, non-overlapping, sum to total length            |
| `ClientTest`     |     2 | `TimeoutConfig` validation                                                                      |

Retry tests use `kotlinx-coroutines-test` virtual time, so backoff sleeps don't slow the suite. A separate Docker httpd end-to-end check (`docker run httpd:latest` + 20 MiB urandom payload + `cmp`) confirmed byte-perfect output against real Apache; that check lives in the Quick start above rather than the gradle suite because it requires Docker.

## Configuration

Public entry point:

```kotlin
suspend fun download(
    client: HttpClient,
    url: String,
    destination: Path,
    chunkSize: Long = 4L * 1024 * 1024,   // bytes per Range request
    parallelism: Int = 4,                 // max concurrent in-flight chunks
    retryPolicy: RetryPolicy = RetryPolicy(),
)
```

```kotlin
data class RetryPolicy(
    val maxAttempts: Int = 4,      // total attempts per chunk (1 = no retry)
    val baseDelayMs: Long = 200,   // backoff seed; grown by `factor` per attempt
    val factor: Double = 2.0,      // exponential growth factor
    val maxDelayMs: Long = 30_000, // cap on a single computed wait
)

data class TimeoutConfig(
    val requestMs: Long = 60_000,  // ceiling on a single chunk's whole GET
    val connectMs: Long = 10_000,  // TCP handshake budget
    val socketMs: Long = 30_000,   // max gap between bytes mid-stream
)
```

Build a configured client with `createDownloadClient(timeouts: TimeoutConfig = TimeoutConfig())`. Note that `TimeoutConfig` is applied at the `HttpClient` level, not per-call (see Out of scope).

## Out of scope

Deliberate non-goals for this pass:

- **Resumability.** A `TODO(resumability)` hook point in `Downloader.kt` (inside the `coroutineScope` in `download`) marks where a sidecar of completed chunk indices would plug in.
- **Progress reporting.** No `Flow<Progress>` of bytes-written events.
- **MockWebServer integration tests.** Coverage is unit-level against Ktor's `MockEngine`.
- **Per-download timeouts.** `TimeoutConfig` is applied per-`HttpClient`; per-call override is not exposed.
