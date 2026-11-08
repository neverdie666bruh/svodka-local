package com.urmindkilla.svodkalocal.core.net

import java.net.URI

object UrlCanonicalizer {
    private val tracking = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "fbclid", "gclid", "yclid", "mc_cid", "mc_eid", "_openstat", "ref", "from",
        "feature", "campaign", "spm", "igshid", "si", "vero_id",
    )

    fun canonicalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.userInfo != null) return null
        var path = uri.rawPath ?: "/"
        if (path.isEmpty()) path = "/"
        if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
        val query = uri.rawQuery
            ?.split("&")
            ?.mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val key = part.substringBefore("=").lowercase()
                if (key in tracking || key.startsWith("utm_")) null else part
            }
            ?.sorted()
            ?.joinToString("&")
            .orEmpty()
        val port = when {
            uri.port == -1 -> ""
            scheme == "https" && uri.port == 443 -> ""
            scheme == "http" && uri.port == 80 -> ""
            else -> ":${uri.port}"
        }
        val q = if (query.isEmpty()) "" else "?$query"
        return "$scheme://$host$port$path$q"
    }
}
