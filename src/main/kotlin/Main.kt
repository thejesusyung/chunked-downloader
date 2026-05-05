import java.nio.file.Paths
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("Usage: <url> <destination>")
        exitProcess(2)
    }
    val (url, dest) = args
    try {
        createDownloadClient().use { client ->
            download(client, url, Paths.get(dest))
        }
    } catch (e: DownloadException) {
        System.err.println("Download failed: ${e.message}")
        exitProcess(1)
    }
}
