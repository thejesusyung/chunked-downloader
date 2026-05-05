import kotlinx.coroutines.delay
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.math.pow
import kotlin.random.Random

data class RetryPolicy(
    val maxAttempts: Int = 4,
    val baseDelayMs: Long = 200,
    val factor: Double = 2.0,
    val maxDelayMs: Long = 30_000,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
        require(baseDelayMs >= 0) { "baseDelayMs must be >= 0, got $baseDelayMs" }
        require(factor >= 1.0)    { "factor must be >= 1.0, got $factor" }
        require(maxDelayMs >= 0)  { "maxDelayMs must be >= 0, got $maxDelayMs" }
    }
}

internal val RETRYABLE_STATUSES: Set<Int> = setOf(408, 429, 500, 502, 503, 504)

internal fun isRetryable(t: Throwable): Boolean = when (t) {
    is UnexpectedStatus -> t.status.value in RETRYABLE_STATUSES
    is IOException      -> true
    else                -> false
}

private val log: Logger = LoggerFactory.getLogger("Downloader")

internal fun parseRetryAfterSeconds(raw: String?, label: String): Long? {
    if (raw == null) return null
    val seconds = raw.toLongOrNull()
    if (seconds == null) {
        log.warn("{}: unparseable Retry-After header '{}', falling back to backoff", label, raw)
        return null
    }
    return seconds * 1000
}

internal suspend fun <T> retrying(
    policy: RetryPolicy,
    label: String,
    block: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        attempt++
        val failure: Throwable
        val retryAfterMs: Long?
        try {
            return block()
        } catch (e: Exception) {
            if (!isRetryable(e)) throw e
            failure = e
            retryAfterMs = (e as? UnexpectedStatus)?.retryAfterMs
        }
        if (attempt >= policy.maxAttempts) {
            throw RetriesExhausted(attempts = attempt, cause = failure)
        }
        val waitMs = retryAfterMs ?: nextBackoffMs(policy, attempt)
        log.warn(
            "{} attempt {}/{} failed ({}), retrying in {}ms",
            label, attempt, policy.maxAttempts, failure.message, waitMs,
        )
        delay(waitMs)
    }
}

private fun nextBackoffMs(policy: RetryPolicy, attempt: Int): Long {
    val cap = (policy.baseDelayMs.toDouble() * policy.factor.pow(attempt - 1))
        .toLong()
        .coerceAtMost(policy.maxDelayMs)
    return if (cap <= 0L) 0L else Random.nextLong(cap + 1L)
}
