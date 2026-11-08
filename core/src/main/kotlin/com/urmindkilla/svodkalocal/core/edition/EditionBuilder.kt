package com.urmindkilla.svodkalocal.core.edition

import com.urmindkilla.svodkalocal.core.classify.Classifier
import com.urmindkilla.svodkalocal.core.model.Category
import com.urmindkilla.svodkalocal.core.model.Edition
import com.urmindkilla.svodkalocal.core.model.EditionCounts
import com.urmindkilla.svodkalocal.core.model.EditionItem
import com.urmindkilla.svodkalocal.core.model.ParsedEntry
import com.urmindkilla.svodkalocal.core.model.SeenStory
import com.urmindkilla.svodkalocal.core.model.SourceStatus
import com.urmindkilla.svodkalocal.core.model.FeedSource
import com.urmindkilla.svodkalocal.core.model.RelatedLink
import com.urmindkilla.svodkalocal.core.model.TopicCoverage
import com.urmindkilla.svodkalocal.core.model.titleRu
import com.urmindkilla.svodkalocal.core.net.UrlCanonicalizer
import com.urmindkilla.svodkalocal.core.text.RussianText
import com.urmindkilla.svodkalocal.core.text.Tokens
import com.urmindkilla.svodkalocal.core.time.EditionCalendar
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

data class FetchedSource(
    val source: FeedSource,
    val entries: List<ParsedEntry>,
    val status: SourceStatus,
)

data class BuiltEdition(
    val edition: Edition,
    val seen: List<SeenStory>,
)

class EditionBuilder(
    private val classifier: Classifier,
) {
    fun build(
        now: Instant,
        fetches: List<FetchedSource>,
        seen: List<SeenStory>,
        zone: java.time.ZoneId = EditionCalendar.ZONE,
    ): BuiltEdition {
        val date = EditionCalendar.editionDate(now, zone).toString()
        val freshSeen = seen.filter { it.editionDate >= EditionCalendar.editionDate(now, zone).minusDays(7).toString() }
        val accepted = mutableListOf<EditionItem>()
        var seenCount = 0
        var duplicates = 0
        var outOfWindow = 0
        var excluded = 0
        var low = 0
        var badDate = 0
        var notRussian = 0

        for (fetch in fetches) {
            if (!fetch.source.enabled || !fetch.status.ok) continue
            for (entry in fetch.entries) {
                seenCount += 1
                if (!RussianText.enoughCyrillic(entry.title)) {
                    notRussian += 1
                    continue
                }
                val published = entry.publishedAt
                if (published == null) {
                    badDate += 1
                    continue
                }
                if (!EditionCalendar.inWindow(published, now)) {
                    outOfWindow += 1
                    continue
                }
                if (classifier.hardExcluded(entry.title, entry.excerpt) != null) {
                    excluded += 1
                    continue
                }
                val classified = classifier.classify(entry.title, entry.excerpt, fetch.source)
                if (classified == null) {
                    low += 1
                    continue
                }
                val canonical = canonicalUrl(entry.url, fetch.source.url)
                val tokens = Tokens.titleTokens(entry.title)
                if (accepted.any { it.url == canonical }) {
                    duplicates += 1
                    continue
                }
                val seenHit = freshSeen.firstOrNull { it.url == canonical || Tokens.similar(it.tokens, tokens) }
                val continuation = seenHit != null && isContinuation(entry.title, tokens, seenHit.tokens)
                if (seenHit != null && !continuation) {
                    duplicates += 1
                    continue
                }
                val labels = classified.match.labels.toMutableList()
                if (continuation) {
                    labels += "похоже на продолжение уже виденного заголовка: в заголовке новый номер или версия. Это не проверка факта."
                }
                val item = EditionItem(
                    id = sha(canonical),
                    title = entry.title,
                    url = canonical,
                    category = classified.category,
                    sourceId = fetch.source.id,
                    sourceName = fetch.source.name,
                    sourceFeed = fetch.source.url,
                    publishedAt = published.toString(),
                    excerpt = entry.excerpt,
                    excerptTruncated = entry.excerptTruncated,
                    excerptOrigin = entry.excerptOrigin,
                    match = classified.match.copy(labels = labels),
                )
                accepted += item
            }
        }

        val clustered = cluster(accepted)
        duplicates += clustered.second
        val eligible = clustered.first
        val picked = balance(eligible)
        val publishedSeen = freshSeen.filter { it.editionDate != date }.toMutableList()
        for (item in picked) {
            publishedSeen += SeenStory(
                url = item.url,
                tokens = Tokens.titleTokens(item.title),
                editionDate = date,
            )
        }
        val statuses = fetches.map { fetch ->
            fetch.status.copy(itemsUsed = picked.count { it.sourceId == fetch.source.id })
        }
        val partial = statuses.any { it.enabled && !it.ok }
        val coverage = coverage(fetches, eligible, picked)
        val byCategory = Category.entries.mapNotNull { category ->
            val count = picked.count { it.category == category }
            if (count == 0) null else "${category.titleRu()} $count"
        }
        val summary = if (picked.isEmpty()) {
            "За окно ${EditionCalendar.WINDOW_HOURS} ч правила не пропустили ни одного материала. Пустые темы не заполняются."
        } else {
            "В окне ${EditionCalendar.WINDOW_HOURS} ч в выпуске ${picked.size} материалов" +
                (if (byCategory.isEmpty()) "" else ": ${byCategory.joinToString(", ")}") +
                ". Это счётчик правил, не обзор и не проверка фактов." +
                if (partial) " Часть источников не ответила." else ""
        }
        val edition = Edition(
            editionDate = date,
            timezone = zone.id,
            generatedAt = now.atOffset(ZoneOffset.UTC).toInstant().toString(),
            partial = partial,
            summary = summary,
            items = picked,
            sources = statuses,
            counts = EditionCounts(
                seen = seenCount,
                published = picked.size,
                duplicates = duplicates,
                outOfWindow = outOfWindow,
                excluded = excluded,
                lowConfidence = low,
                badDate = badDate,
                notRussian = notRussian,
            ),
            coverage = coverage,
        )
        return BuiltEdition(edition, publishedSeen)
    }

    private fun cluster(items: List<EditionItem>): Pair<List<EditionItem>, Int> {
        val clusters = mutableListOf<MutableCluster>()
        var merged = 0
        for (item in items) {
            val tokens = Tokens.titleTokens(item.title)
            val hit = clusters.firstOrNull { Tokens.similar(it.tokens, tokens) || it.item.url == item.url }
            if (hit == null) {
                clusters += MutableCluster(item, tokens)
            } else if (hit.item.url != item.url && hit.item.related.none { it.url == item.url }) {
                hit.item = hit.item.copy(
                    related = hit.item.related + RelatedLink(item.sourceName, item.url, item.title),
                )
                merged += 1
            } else {
                merged += 1
            }
        }
        val clustered = clusters.map { cluster ->
            if (cluster.item.related.isEmpty()) {
                cluster.item
            } else {
                val note = "тот же сюжет встретился ещё в ${cluster.item.related.size} лентах. Это не независимая проверка факта."
                cluster.item.copy(
                    match = cluster.item.match.copy(labels = cluster.item.match.labels + note),
                )
            }
        }
        return clustered to merged
    }

    private fun balance(items: List<EditionItem>, limit: Int = 20): List<EditionItem> {
        val pools = Category.entries.associateWith { category ->
            items.filter { it.category == category }.sortedWith(displayOrder()).toMutableList()
        }
        val picked = mutableListOf<EditionItem>()
        while (picked.size < limit && pools.values.any { it.isNotEmpty() }) {
            for (category in Category.entries) {
                if (picked.size == limit) break
                val next = pools.getValue(category).removeFirstOrNull() ?: continue
                picked += next
            }
        }
        return picked.sortedWith(displayOrder())
    }

    private fun coverage(
        fetches: List<FetchedSource>,
        eligible: List<EditionItem>,
        picked: List<EditionItem>,
    ): List<TopicCoverage> {
        return Category.entries.map { category ->
            val sources = fetches.filter { category in it.source.groups && it.source.enabled }
            val domains = sources.map { hostOf(it.source.url) }.filter { it.isNotEmpty() }.distinct()
            val eligibleCount = eligible.count { it.category == category }
            val published = picked.count { it.category == category }
            val note = when {
                sources.isEmpty() -> "включённых источников с этой группой нет"
                sources.all { !it.status.ok } -> "источники этой группы не ответили"
                eligibleCount == 0 -> "в окне ${EditionCalendar.WINDOW_HOURS} ч порог не прошёл ни один пункт"
                published < eligibleCount -> "в выпуске $published из $eligibleCount прошедших порог"
                else -> "в выпуске $published"
            }
            TopicCoverage(category, sources.size, domains.size, eligibleCount, published, note)
        }
    }

    private fun canonicalUrl(url: String, feedUrl: String): String {
        val canonical = UrlCanonicalizer.canonicalize(url) ?: url
        if (feedUrl.startsWith("https://") && canonical.startsWith("http://")) {
            return "https://" + canonical.removePrefix("http://")
        }
        return canonical
    }

    private fun isContinuation(title: String, tokens: List<String>, seenTokens: List<String>): Boolean {
        val lower = title.lowercase().replace('ё', 'е')
        val marker = listOf("вышел", "вышла", "вышло", "обновлен", "патч", "сезон", "релиз", "версия")
            .any { lower.contains(it) }
        if (!marker) return false
        val freshNumber = tokens.any { it.any(Char::isDigit) && it !in seenTokens }
        return freshNumber
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

    private class MutableCluster(var item: EditionItem, val tokens: List<String>)

    private fun displayOrder() = compareByDescending<EditionItem> { it.match.confidence }
        .thenByDescending { it.publishedAt }
        .thenBy { it.url }

    private fun sha(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
