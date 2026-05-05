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
    val length = resp.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        ?: throw MetadataMissing(
            header = HttpHeaders.ContentLength,
            message = "HEAD response missing Content-Length",
        )
    val acceptsRanges = resp.headers[HttpHeaders.AcceptRanges]
        ?.equals("bytes", ignoreCase = true) == true
    return FileMetadata(contentLength = length, acceptsRanges = acceptsRanges)
}
