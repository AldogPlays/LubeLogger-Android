package app.lubelogger.wrapper

import java.net.URI
import java.util.Locale

sealed class AddressConfiguration {
    data class Valid(val addresses: ServerAddresses) : AddressConfiguration()
    data class Invalid(val errors: List<AddressError>) : AddressConfiguration()
}

enum class AddressField { PREFERRED, FALLBACK }
data class AddressError(val field: AddressField, val message: String)

class ServerAddresses private constructor(val preferred: ServerAddress, val fallback: ServerAddress?) {
    val ordered: List<ServerAddress> = listOfNotNull(preferred, fallback).distinct()
    fun allowsNavigation(pageUrl: String): Boolean = ordered.any { it.sameOrigin(pageUrl) }

    companion object {
        fun parse(preferredText: String, fallbackText: String? = null): AddressConfiguration {
            val errors = mutableListOf<AddressError>()
            fun parseField(text: String, field: AddressField): ServerAddress? =
                ServerAddress.parse(text).getOrElse {
                    errors += AddressError(field, it.message ?: "Enter a valid HTTP or HTTPS address")
                    null
                }
            val preferred = parseField(preferredText, AddressField.PREFERRED)
            val fallback = fallbackText?.takeIf { it.isNotBlank() }?.let { parseField(it, AddressField.FALLBACK) }
            return if (errors.isEmpty() && preferred != null) {
                AddressConfiguration.Valid(ServerAddresses(preferred, fallback))
            } else AddressConfiguration.Invalid(errors)
        }
    }
}

class ServerAddress private constructor(val url: String) {
    private val uri = URI(url)

    fun sameOrigin(pageUrl: String): Boolean = runCatching {
        val page = URI(pageUrl)
        page.rawUserInfo == null && page.host != null &&
            uri.scheme.equals(page.scheme, true) && uri.host.equals(page.host, true) &&
            effectivePort(uri) == effectivePort(page) && page.port in -1..65535 &&
            !page.rawAuthority.endsWith(':')
    }.getOrDefault(false)

    fun equivalentPage(pageUrl: String?, replacement: ServerAddress): String {
        if (pageUrl == null || !sameOrigin(pageUrl)) return replacement.url
        val page = runCatching { URI(pageUrl) }.getOrNull() ?: return replacement.url
        val path = page.rawPath.orEmpty()
        val base = uri.rawPath.orEmpty()
        if (ambiguousPath(path) || (path != base && !path.startsWith("$base/"))) return replacement.url
        return replacement.url + path.substring(base.length) +
            (page.rawQuery?.let { "?$it" } ?: "") + (page.rawFragment?.let { "#$it" } ?: "")
    }

    override fun equals(other: Any?): Boolean = other is ServerAddress && url == other.url
    override fun hashCode(): Int = url.hashCode()
    override fun toString(): String = url

    companion object {
        internal fun parse(text: String): Result<ServerAddress> = runCatching {
            val uri = URI(text.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme == "http" || scheme == "https") { "Use an HTTP or HTTPS address" }
            val host = uri.host?.lowercase(Locale.ROOT)
            require(!host.isNullOrBlank()) { "Enter a valid server hostname" }
            require(uri.rawUserInfo == null) { "Remove credentials from the server address" }
            require(uri.rawQuery == null && uri.rawFragment == null) { "Use a server base address without a query or fragment" }
            require(uri.port == -1 || uri.port in 1..65535) { "Enter a port between 1 and 65535" }
            require(!uri.rawAuthority.endsWith(':')) { "Enter a valid server port" }
            require(!ambiguousPath(uri.rawPath.orEmpty())) { "Remove dot segments or encoded separators from the base path" }
            val defaultPort = if (scheme == "https") 443 else 80
            val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
            ServerAddress("$scheme://$host$port${uri.rawPath.orEmpty().trimEnd('/')}")
        }
    }
}

private fun ambiguousPath(path: String): Boolean =
    Regex("(?i)%2f|%5c").containsMatchIn(path) ||
        path.split('/').any { segment ->
            val dots = segment.replace(Regex("(?i)%2e"), ".")
            dots == "." || dots == ".."
        }

private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
