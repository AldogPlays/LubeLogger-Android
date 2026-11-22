package app.lubelogger.wrapper

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Uses platform HTTP/TLS validation; no alternate trust or hostname policy. */
internal class HttpServerProbe {
    fun validates(address: ServerAddress, timeoutMs: Int): Boolean {
        var current = URL(address.url)
        repeat(4) {
            if (Thread.currentThread().isInterrupted) return false
            var connection: HttpURLConnection? = null
            try {
                connection = current.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.setRequestProperty("User-Agent", "LubeLogger Android")
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return false
                    val next = current.toURI().resolve(location)
                    if (!address.sameOrigin(next.toString())) return false
                    current = next.toURL()
                    return@repeat
                }
                if (status !in 200..299) return false
                val contentType = connection.contentType.orEmpty().lowercase()
                if (!contentType.contains("text/html") && !contentType.contains("application/xhtml+xml")) return false
                val html = connection.inputStream.use(::readLimitedHtml)
                return Regex("(?is)<title[^>]*>.*?lube\\s*logger.*?</title>").containsMatchIn(html) ||
                    Regex("(?i)lube\\s*logger").containsMatchIn(html)
            } catch (_: Exception) {
                return false
            } finally {
                connection?.disconnect()
            }
        }
        return false
    }

    private fun readLimitedHtml(input: InputStream): String {
        val bytes = ByteArray(512 * 1024)
        var total = 0
        while (total < bytes.size) {
            if (Thread.currentThread().isInterrupted) break
            val count = input.read(bytes, total, bytes.size - total)
            if (count < 0) break
            total += count
        }
        return String(bytes, 0, total, Charsets.UTF_8)
    }
}
