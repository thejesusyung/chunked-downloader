import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RetryTest {

    @Test
    fun `parseRetryAfterSeconds returns ms for valid value`() {
        assertEquals(0L, parseRetryAfterSeconds("0", "test"))
        assertEquals(5000L, parseRetryAfterSeconds("5", "test"))
        assertEquals(86_400_000L, parseRetryAfterSeconds("86400", "test"))
    }

    @Test
    fun `parseRetryAfterSeconds returns null for null input`() {
        assertNull(parseRetryAfterSeconds(null, "test"))
    }

    @Test
    fun `parseRetryAfterSeconds returns null for unparseable value`() {
        // HTTP-date form is not supported; falls back to backoff.
        assertNull(parseRetryAfterSeconds("Wed, 21 Oct 2015 07:28:00 GMT", "test"))
        assertNull(parseRetryAfterSeconds("abc", "test"))
        assertNull(parseRetryAfterSeconds("", "test"))
    }

    @Test
    fun `parseRetryAfterSeconds returns null for negative seconds`() {
        // Without this guard, delay(negativeMs) is a no-op and the retry loop hot-spins.
        assertNull(parseRetryAfterSeconds("-1", "test"))
        assertNull(parseRetryAfterSeconds("-9999", "test"))
    }

    @Test
    fun `parseRetryAfterSeconds returns null for absurdly large value`() {
        // Without this guard, seconds*1000 may overflow Long, or we'd sleep for centuries.
        assertNull(parseRetryAfterSeconds("86401", "test"))
        assertNull(parseRetryAfterSeconds("9999999999", "test"))
        assertNull(parseRetryAfterSeconds(Long.MAX_VALUE.toString(), "test"))
    }

    @Test
    fun `RetryPolicy rejects maxDelayMs above 1 day`() {
        // Prevents `Random.nextLong(maxDelayMs + 1L)` from overflowing in nextBackoffMs.
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxDelayMs = Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxDelayMs = 86_400_001) }
    }

    @Test
    fun `RetryPolicy rejects negative maxDelayMs`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxDelayMs = -1) }
    }
}
