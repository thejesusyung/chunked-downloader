data class ChunkRange(val index: Int, val start: Long, val endInclusive: Long) {
    init {
        require(start >= 0)            { "start must be >= 0, got $start" }
        require(endInclusive >= start) { "endInclusive ($endInclusive) must be >= start ($start)" }
    }
    val length: Long get() = endInclusive - start + 1
}

fun planChunks(totalLength: Long, chunkSize: Long): List<ChunkRange> {
    require(totalLength >= 0) { "totalLength must be >= 0, got $totalLength" }
    require(chunkSize > 0)    { "chunkSize must be > 0, got $chunkSize" }
    if (totalLength == 0L) return emptyList()

    val out = mutableListOf<ChunkRange>()
    var offset = 0L
    var index = 0
    while (offset < totalLength) {
        val end = minOf(offset + chunkSize - 1, totalLength - 1)
        out += ChunkRange(index++, offset, end)
        offset = end + 1
    }
    return out
}
