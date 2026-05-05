import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ClientTest {

    @Test
    fun `TimeoutConfig rejects zero values`() {
        assertFailsWith<IllegalArgumentException> { TimeoutConfig(requestMs = 0) }
        assertFailsWith<IllegalArgumentException> { TimeoutConfig(connectMs = 0) }
        assertFailsWith<IllegalArgumentException> { TimeoutConfig(socketMs = 0) }
    }

    @Test
    fun `TimeoutConfig rejects negative values`() {
        assertFailsWith<IllegalArgumentException> { TimeoutConfig(requestMs = -1) }
        assertFailsWith<IllegalArgumentException> { TimeoutConfig(connectMs = -1) }
        assertFailsWith<IllegalArgumentException> { TimeoutConfig(socketMs = -1) }
    }
}
