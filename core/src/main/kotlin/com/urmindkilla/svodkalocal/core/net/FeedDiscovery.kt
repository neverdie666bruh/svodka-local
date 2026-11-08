package com.urmindkilla.svodkalocal.core.net

import java.net.URI

object FeedDiscovery {
    private val link = Regex("(?is)<link\\b[^>]*>")
    private val href = Regex("""(?i)href\s*=\s*["']([^"']+)["']""")

    fun alternate(body: ByteArray, pageUrl: String): String? {
        val page = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        val pageHost = page.host?.lowercase() ?: return null
        val html = String(body, 0, minOf(body.size, 80_000), Charsets.UTF_8)
        for (tag in link.findAll(html).take(40)) {
            val raw = tag.value.lowercase()
            if (!raw.contains("alternate")) continue
            if (!raw.contains("rss") && !raw.contains("atom")) continue
            val target = href.find(tag.value)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (target.isEmpty()) continue
            val resolved = runCatching { page.resolve(target).toString() }.getOrNull() ?: continue
            if (UrlPolicy.rejection(resolved) != null) continue
            val host = runCatching { URI(resolved).host?.lowercase() }.getOrNull()
            if (host != pageHost) continue
            if (resolved == pageUrl) continue
            return resolved
        }
        return null
    }
}
