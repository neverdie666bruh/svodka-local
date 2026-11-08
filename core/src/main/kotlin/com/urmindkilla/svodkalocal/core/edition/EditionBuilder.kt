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
        val acceptedTokens = mutableListOf<List<String>>()
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
                val canonical = UrlCanonicalizer.canonicalize(entry.url) ?: entry.url
                val tokens = Tokens.titleTokens(entry.title)
                val duplicate = freshSeen.any { it.url == canonical || Tokens.similar(it.tokens, tokens) } ||
                    accepted.any { it.url == canonical } ||
                    acceptedTokens.any { Tokens.similar(it, tokens) }
                if (duplicate) {
                    duplicates += 1
                    continue
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
                    match = classified.match,
                )
                accepted += item
                acceptedTokens += tokens
            }
        }

        val picked = diversify(accepted)
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
        val summary = if (picked.isEmpty()) {
            "За окно ${EditionCalendar.WINDOW_HOURS} ч правила не пропустили ни одного материала. Пустой выпуск нормален: список не дополняется посторонними темами."
        } else {
            "В окне ${EditionCalendar.WINDOW_HOURS} ч правила оставили ${picked.size} материалов. Это не оценка важности и не редакционный обзор." +
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
        )
        return BuiltEdition(edition, publishedSeen)
    }

    private fun diversify(items: List<EditionItem>): List<EditionItem> {
        val sorted = items.sortedWith(
            compareByDescending<EditionItem> { it.match.confidence }
                .thenByDescending { it.publishedAt }
                .thenBy { it.url },
        )
        val picked = mutableListOf<EditionItem>()
        val perCategory = mutableMapOf<Category, Int>()
        val deferred = mutableListOf<EditionItem>()
        for (item in sorted) {
            val count = perCategory[item.category] ?: 0
            if (count >= 4) {
                deferred += item
                continue
            }
            picked += item
            perCategory[item.category] = count + 1
            if (picked.size == 20) return picked.sortedWith(displayOrder())
        }
        for (item in deferred) {
            if (picked.size == 20) break
            picked += item
        }
        return picked.sortedWith(displayOrder())
    }

    private fun displayOrder() = compareByDescending<EditionItem> { it.match.confidence }
        .thenByDescending { it.publishedAt }
        .thenBy { it.url }

    private fun sha(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
