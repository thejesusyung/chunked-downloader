import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

data class FileMetadata(val contentLength: Long, val acceptsRanges: Boolean)

suspend fun fetchMetadata(client: HttpClient, url: String): FileMetadata {
    val resp: HttpResponse = client.head(url)
    if (!resp.status.isSuccess()) {
        throw UnexpectedStatus(
            status = resp.status,
            retryAfterMs = null,
            message = "HEAD $url -> ${resp.status}",
        )
    }
    val rawLength = resp.headers[HttpHeaders.ContentLength]
    val length = rawLength?.toLongOrNull()?.takeIf { it >= 0 }
        ?: throw MetadataMissing(
            header = HttpHeaders.ContentLength,
            message = "HEAD response missing or invalid Content-Length: '$rawLength'",
        )
    val acceptsRanges = resp.headers[HttpHeaders.AcceptRanges]
        ?.equals("bytes", ignoreCase = true) == true
    return FileMetadata(contentLength = length, acceptsRanges = acceptsRanges)
}
