import io.ktor.http.HttpStatusCode

sealed class DownloadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class RangesUnsupported(message: String) : DownloadException(message)

class UnexpectedStatus(
    val status: HttpStatusCode,
    val retryAfterMs: Long?,
    message: String,
    cause: Throwable? = null,
) : DownloadException(message, cause)

class LengthMismatch(
    val expected: Long,
    val actual: Long,
    message: String,
    cause: Throwable? = null,
) : DownloadException(message, cause)

class ContentRangeMismatch(
    val expected: String,
    val actual: String,
    message: String,
    cause: Throwable? = null,
) : DownloadException(message, cause)

class MetadataMissing(
    val header: String,
    message: String,
    cause: Throwable? = null,
) : DownloadException(message, cause)

class RetriesExhausted(
    val attempts: Int,
    cause: Throwable,
) : DownloadException(
    "Retries exhausted after $attempts attempt${if (attempts == 1) "" else "s"}: ${cause.message ?: cause::class.simpleName ?: "unknown"}",
    cause,
)
