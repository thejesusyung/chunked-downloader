import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlannerTest {

    @Test
    fun `length 0 yields empty list`() {
        assertTrue(planChunks(totalLength = 0, chunkSize = 1024).isEmpty())
    }

    @Test
    fun `length less than chunkSize yields one short chunk`() {
        val chunks = planChunks(totalLength = 100, chunkSize = 1024)
        assertEquals(listOf(ChunkRange(0, 0, 99)), chunks)
        assertEquals(100L, chunks.single().length)
    }

    @Test
    fun `length equal to chunkSize yields one full chunk`() {
        val chunks = planChunks(totalLength = 1024, chunkSize = 1024)
        assertEquals(listOf(ChunkRange(0, 0, 1023)), chunks)
        assertEquals(1024L, chunks.single().length)
    }

    @Test
    fun `length not divisible by chunkSize yields short last chunk`() {
        assertEquals(
            listOf(
                ChunkRange(0, 0, 399),
                ChunkRange(1, 400, 799),
                ChunkRange(2, 800, 999),
            ),
            planChunks(totalLength = 1000, chunkSize = 400),
        )
    }

    @Test
    fun `length exactly divisible by chunkSize yields equal full chunks`() {
        val chunks = planChunks(totalLength = 1200, chunkSize = 400)
        assertEquals(3, chunks.size)
        chunks.forEach { assertEquals(400L, it.length) }
    }

    @Test
    fun `negative totalLength throws`() {
        assertFailsWith<IllegalArgumentException> {
            planChunks(totalLength = -1, chunkSize = 1024)
        }
    }

    @Test
    fun `zero chunkSize throws`() {
        assertFailsWith<IllegalArgumentException> {
            planChunks(totalLength = 1000, chunkSize = 0)
        }
    }

    @Test
    fun `negative chunkSize throws`() {
        assertFailsWith<IllegalArgumentException> {
            planChunks(totalLength = 1000, chunkSize = -5)
        }
    }

    @Test
    fun `chunks are contiguous and cover the whole length`() {
        val cases = listOf(
            1L to 1L,
            7L to 3L,
            1000L to 400L,
            1200L to 400L,
            1L to 1024L,
            1024L to 1L,
            10_000L to 2_048L,
        )
        cases.forEach { (total, chunk) ->
            val chunks = planChunks(total, chunk)
            assertEquals(total, chunks.sumOf { it.length },
                "coverage mismatch for total=$total chunk=$chunk")
            for (i in 1 until chunks.size) {
                assertEquals(chunks[i - 1].endInclusive + 1, chunks[i].start,
                    "non-contiguous at index $i for total=$total chunk=$chunk")
            }
            chunks.forEachIndexed { i, c ->
                assertEquals(i, c.index,
                    "index mismatch at $i for total=$total chunk=$chunk")
            }
        }
    }
}
