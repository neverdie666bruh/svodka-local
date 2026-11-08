package com.urmindkilla.svodkalocal.core.rss

import com.urmindkilla.svodkalocal.core.model.FeedSource
import com.urmindkilla.svodkalocal.core.model.ParsedEntry
import com.urmindkilla.svodkalocal.core.net.UrlPolicy
import com.urmindkilla.svodkalocal.core.text.HtmlText
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId

object HtmlIndexParser {
    const val ANIMAG = "animag"
    private val allowed = setOf(ANIMAG)
    private val heading = Regex("""(?is)<h3 class="field-content">(.*?)</h3>(.{0,700})""")
    private val href = Regex("""(?is)href="([^"]+)"""")
    private val date = Regex(
        """(янв|фев|мар|апр|ма[яй]|июн|июл|авг|сен|окт|ноя|дек)[а-я]*\s+(\d{1,2}),\s+(20\d{2})\s+(\d{1,2}):(\d{2})""",
        RegexOption.IGNORE_CASE,
    )
    private val months = mapOf(
        "янв" to 1, "фев" to 2, "мар" to 3, "апр" to 4, "май" to 5, "мая" to 5,
        "июн" to 6, "июл" to 7, "авг" to 8, "сен" to 9, "окт" to 10, "ноя" to 11, "дек" to 12,
    )
    private val moscow = ZoneId.of("Europe/Moscow")

    fun parse(source: FeedSource, bytes: ByteArray): List<ParsedEntry> {
        if (source.id !in allowed || source.format != "html-index") {
            throw FeedParseException("html-индекс не из разрешённого списка")
        }
        if (bytes.size > 500_000) throw FeedParseException("страница больше лимита")
        val host = runCatching { URI(source.url).host?.lowercase() }.getOrNull()
        if (host != "animag.ru") throw FeedParseException("хост html-индекса не animag.ru")
        val html = bytes.toString(Charsets.UTF_8)
        val items = mutableListOf<ParsedEntry>()
        for (match in heading.findAll(html)) {
            if (items.size == 30) break
            val headingHtml = match.groupValues[1]
            val link = href.find(headingHtml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (!link.startsWith("/news/")) continue
            val absolute = "https://animag.ru$link"
            if (UrlPolicy.rejection(absolute) != null) continue
            val resolvedHost = runCatching { URI(absolute).host?.lowercase() }.getOrNull()
            if (resolvedHost != "animag.ru") continue
            val title = HtmlText.toPlain(headingHtml, 240).text
            if (title.isBlank()) continue
            val tail = match.groupValues[2]
            val stamp = date.find(tail) ?: continue
            val month = months[stamp.groupValues[1].lowercase().take(3)] ?: continue
            val day = stamp.groupValues[2].toIntOrNull() ?: continue
            val year = stamp.groupValues[3].toIntOrNull() ?: continue
            val hour = stamp.groupValues[4].toIntOrNull() ?: continue
            val minute = stamp.groupValues[5].toIntOrNull() ?: continue
            val published = runCatching {
                LocalDateTime.of(year, month, day, hour, minute).atZone(moscow).toInstant()
            }.getOrNull() ?: continue
            val blurb = HtmlText.toPlain(tail.substring(0, stamp.range.first), source.excerptMaxChars)
            items += ParsedEntry(
                title = title,
                url = absolute,
                guid = absolute,
                publishedRaw = stamp.value,
                publishedAt = published,
                excerpt = blurb.text,
                excerptTruncated = blurb.truncated,
                excerptOrigin = "index_excerpt",
            )
        }
        if (items.isEmpty()) throw FeedParseException("на странице нет датированных новостей /news/")
        return items
    }
}
