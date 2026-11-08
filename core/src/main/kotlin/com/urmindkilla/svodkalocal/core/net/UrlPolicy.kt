package com.urmindkilla.svodkalocal.core.net

import java.net.InetAddress
import java.net.URI

object UrlPolicy {
    private val blockedHosts = setOf(
        "localhost",
        "localhost.localdomain",
        "metadata.google.internal",
        "metadata",
    )

    fun rejection(raw: String, allowLoopback: Boolean = false): String? {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            return "адрес не разбирается"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && !(allowLoopback && scheme == "http")) {
            return "нужен https"
        }
        if (uri.userInfo != null) return "адрес с паролем запрещён"
        val host = uri.host?.lowercase() ?: return "нет имени хоста"
        if (host in blockedHosts || host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".internal")) {
            return "служебный хост запрещён"
        }
        val literal = parseLiteral(host)
        if (literal != null) {
            if (!addressAllowed(literal, allowLoopback)) return "непубличный адрес"
            return null
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (_: Exception) {
            return "имя не резолвится"
        }
        if (addresses.isEmpty()) return "имя не резолвится"
        if (addresses.any { !addressAllowed(it, allowLoopback) }) return "непубличный адрес"
        return null
    }

    fun addressAllowed(address: InetAddress, allowLoopback: Boolean): Boolean {
        if (address.isAnyLocalAddress || address.isMulticastAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
            return false
        }
        if (address.isLoopbackAddress) return allowLoopback
        val bytes = address.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            if (b0 == 0 || b0 == 10 || b0 == 127) return false
            if (b0 == 169 && b1 == 254) return false
            if (b0 == 172 && b1 in 16..31) return false
            if (b0 == 192 && b1 == 168) return false
            if (b0 == 100 && b1 in 64..127) return false
            if (b0 == 192 && b1 == 0 && (bytes[2].toInt() and 0xff) == 2) return false
            if (b0 == 198 && b1 == 51 && (bytes[2].toInt() and 0xff) == 100) return false
            if (b0 == 203 && b1 == 0 && (bytes[2].toInt() and 0xff) == 113) return false
        }
        if (bytes.size == 16) {
            val b0 = bytes[0].toInt() and 0xff
            if (b0 and 0xfe == 0xfc) return false
        }
        return true
    }

    private fun parseLiteral(host: String): InetAddress? {
        val cleaned = host.trim('[', ']')
        val looksLikeIp = cleaned.all { it.isDigit() || it == '.' } || cleaned.contains(':')
        if (!looksLikeIp) return null
        return try {
            val addr = InetAddress.getByName(cleaned)
            val resolvedHost = addr.hostAddress?.trim('[', ']') ?: return null
            if (resolvedHost.equals(cleaned, ignoreCase = true)) addr else null
        } catch (_: Exception) {
            null
        }
    }
}

object IndexLocator {
    private val fileName = Regex("""^[0-9]{4}-[0-9]{2}-[0-9]{2}\.json$""")

    fun editionUrl(indexUrl: String, file: String): String? {
        if (!fileName.matches(file)) return null
        if (file.contains("..") || file.contains('\\') || file.contains("://")) return null
        val base = indexUrl.substringBeforeLast('/', missingDelimiterValue = "")
        if (base.isEmpty()) return null
        val resolved = "$base/$file"
        if (UrlPolicy.rejection(resolved) != null) return null
        return resolved
    }
}
