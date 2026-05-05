import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

fun FileChannel.writeAllAt(buffer: ByteBuffer, position: Long) {
    var offset = position
    while (buffer.hasRemaining()) {
        val written = write(buffer, offset)
        check(written > 0) { "FileChannel.write returned $written (no progress)" }
        offset += written
    }
}

suspend fun FileChannel.streamFrom(
    source: ByteReadChannel,
    position: Long,
    bufferSize: Int = 64 * 1024,
    maxBytes: Long = Long.MAX_VALUE,
): Long {
    require(bufferSize > 0) { "bufferSize must be > 0, got $bufferSize" }
    require(maxBytes >= 0)  { "maxBytes must be >= 0, got $maxBytes" }
    val buf = ByteArray(bufferSize)
    var written = 0L
    while (written < maxBytes) {
        val toRead = minOf(buf.size.toLong(), maxBytes - written).toInt()
        val read = source.readAvailable(buf, 0, toRead)
        if (read < 0) break
        check(read > 0) { "ByteReadChannel.readAvailable returned $read (no progress)" }
        writeAllAt(ByteBuffer.wrap(buf, 0, read), position + written)
        written += read
    }
    return written
}
