package com.urmindkilla.svodkalocal.core.edition

import com.urmindkilla.svodkalocal.core.classify.Classifier
import com.urmindkilla.svodkalocal.core.model.DigestIndex
import com.urmindkilla.svodkalocal.core.model.Edition
import com.urmindkilla.svodkalocal.core.model.ConditionalFeedTransport
import com.urmindkilla.svodkalocal.core.model.FeedSource
import com.urmindkilla.svodkalocal.core.model.FeedTransport
import com.urmindkilla.svodkalocal.core.model.IndexRow
import com.urmindkilla.svodkalocal.core.model.ParsedEntry
import com.urmindkilla.svodkalocal.core.model.HealthState
import com.urmindkilla.svodkalocal.core.model.SourceHealth
import com.urmindkilla.svodkalocal.core.model.SourceStatus
import com.urmindkilla.svodkalocal.core.net.FeedDiscovery
import com.urmindkilla.svodkalocal.core.net.UrlPolicy
import com.urmindkilla.svodkalocal.core.rss.FeedParseException
import com.urmindkilla.svodkalocal.core.rss.HtmlIndexParser
import com.urmindkilla.svodkalocal.core.rss.RssDates
import com.urmindkilla.svodkalocal.core.rss.SafeFeedParser
import com.urmindkilla.svodkalocal.core.store.DataBranchReadme
import com.urmindkilla.svodkalocal.core.store.DigestStore
import com.urmindkilla.svodkalocal.core.time.EditionCalendar
import com.urmindkilla.svodkalocal.core.time.RunDecision
import java.time.Instant
import java.time.ZoneId

data class CollectOutcome(
    val exitCode: Int,
    val editionDate: String,
    val skipped: Boolean,
    val partial: Boolean,
    val published: Int,
)

class CollectService(
    private val transport: FeedTransport,
    private val classifier: Classifier,
    private val parser: SafeFeedParser = SafeFeedParser(),
    private val zone: ZoneId = EditionCalendar.ZONE,
    private val clock: () -> Instant = Instant::now,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val attempts: Int = 3,
) {
    fun run(store: DigestStore, sources: List<FeedSource>, force: Boolean): CollectOutcome {
        val now = clock()
        val date = EditionCalendar.editionDate(now, zone).toString()
        val existing = try {
            store.readEdition(date)
        } catch (_: Exception) {
            null
        }
        val decision = EditionCalendar.decide(existing, force)
        if (decision == RunDecision.SKIP_COMPLETE && existing != null) {
            touch(store, existing, now, "skip-complete")
            return CollectOutcome(0, date, skipped = true, partial = false, published = existing.items.size)
        }
        val failedIds = existing?.sources?.filter { it.enabled && !it.ok }?.map { it.id }?.toSet().orEmpty()
        val enabled = sources.filter { it.enabled }
        val toFetch = if (decision == RunDecision.RETRY_PARTIAL && existing != null) {
            enabled.filter { it.id in failedIds }
        } else {
            enabled
        }
        val health = store.readHealth().sources.associateBy { it.id }
        val useValidators = force && existing != null
        val fetched = toFetch.map { source ->
            val prior = health[source.id]
            fetchOne(
                source,
                now,
                if (useValidators) prior?.etag else null,
                if (useValidators) prior?.lastModified else null,
                existing,
                prior?.consecutiveFailures ?: 0,
                force,
            )
        }
        val combined = if (decision == RunDecision.RETRY_PARTIAL && existing != null) {
            reconstructed(existing, sources) + fetched
        } else {
            val disabled = sources.filter { !it.enabled }.map { disabledStatus(it) }
            fetched + disabled.map { FetchedSource(it.first, emptyList(), it.second) }
        }
        val seenForBuild = store.readSeen().stories.filter { it.editionDate != date }
        val built = EditionBuilder(classifier).build(now, combined, seenForBuild, zone)
        val enabledStatuses = built.edition.sources.filter { it.enabled }
        val allDown = enabledStatuses.isNotEmpty() && enabledStatuses.all { !it.ok }
        val edition = when {
            allDown && existing != null && existing.items.isNotEmpty() -> existing.copy(
                partial = true,
                collectionFailed = true,
                generatedAt = now.toString(),
                summary = "Новый обход не удался: ни один включённый источник не ответил. " +
                    "Сохранён предыдущий список этой даты. Это не вывод, что новостей не было.",
                sources = built.edition.sources,
                coverage = built.edition.coverage,
            )
            allDown -> built.edition.copy(
                collectionFailed = true,
                partial = true,
                summary = "Сбор не удался: ни один включённый источник не ответил. " +
                    "Пустой список — ошибка сбора, а не сообщение, что важных новостей не было.",
            )
            else -> built.edition
        }
        store.writeEdition(edition)
        if (!(allDown && existing != null && existing.items.isNotEmpty())) {
            store.writeSeen(com.urmindkilla.svodkalocal.core.model.SeenState(built.seen))
        }
        writeHealth(store, edition, now)
        val mode = when {
            edition.collectionFailed -> "failed"
            edition.partial -> "partial"
            else -> "built"
        }
        touch(store, edition, now, mode)
        val code = if (edition.partial) 2 else 0
        return CollectOutcome(code, date, skipped = false, partial = edition.partial, published = edition.items.size)
    }

    private fun writeHealth(store: DigestStore, edition: Edition, now: Instant) {
        val previous = store.readHealth().sources.associateBy { it.id }
        val next = edition.sources.map { status ->
            val before = previous[status.id]
            val failures = if (status.ok) 0 else (before?.consecutiveFailures ?: 0) + 1
            SourceHealth(
                id = status.id,
                ok = status.ok,
                httpStatus = status.httpStatus,
                error = status.error,
                checkedAt = now.toString(),
                consecutiveFailures = failures,
                etag = status.etag ?: before?.etag,
                lastModified = status.lastModified ?: before?.lastModified,
            )
        }
        store.writeHealth(HealthState(next))
    }

    private fun touch(store: DigestStore, edition: Edition, now: Instant, mode: String) {
        val previous = store.readIndex()
        val rows = (previous?.editions.orEmpty().filter { it.date != edition.editionDate } + IndexRow(
            date = edition.editionDate,
            file = "${edition.editionDate}.json",
            itemCount = edition.items.size,
            partial = edition.partial,
        )).sortedByDescending { it.date }
        store.writeIndex(
            DigestIndex(
                latest = rows.firstOrNull()?.date,
                checkedAt = now.toString(),
                editions = rows,
            ),
        )
        store.writeReadme(DataBranchReadme.TEXT.trim() + "\n")
        store.writeLastRun("${now} $mode edition=${edition.editionDate}\n")
    }

    private fun reconstructed(existing: Edition, sources: List<FeedSource>): List<FetchedSource> {
        val byId = sources.associateBy { it.id }
        return existing.sources.filter { it.enabled && it.ok }.map { status ->
            val source = byId[status.id] ?: FeedSource(
                id = status.id,
                name = status.name,
                url = status.url,
                groups = emptyList(),
                licenseNote = "восстановлено из выпуска",
            )
            val entries = existing.items.filter { it.sourceId == status.id }.map { item ->
                ParsedEntry(
                    title = item.title,
                    url = item.url,
                    guid = item.id,
                    publishedRaw = item.publishedAt,
                    publishedAt = RssDates.parse(item.publishedAt) ?: Instant.parse(item.publishedAt),
                    excerpt = item.excerpt,
                    excerptTruncated = item.excerptTruncated,
                    excerptOrigin = item.excerptOrigin,
                )
            }
            FetchedSource(source, entries, status)
        } + existing.sources.filter { !it.enabled }.map { status ->
            val source = byId[status.id] ?: FeedSource(status.id, status.name, status.url, emptyList(), enabled = false, licenseNote = "")
            FetchedSource(source, emptyList(), status)
        }
    }

    private fun disabledStatus(source: FeedSource) = source to SourceStatus(
        id = source.id,
        name = source.name,
        url = source.url,
        enabled = false,
        ok = true,
        attempts = 0,
    )

    private fun fetchOne(
        source: FeedSource,
        now: Instant,
        etag: String?,
        modifiedSince: String?,
        existing: Edition?,
        consecutiveFailures: Int,
        force: Boolean,
    ): FetchedSource {
        if (!force && consecutiveFailures >= 4) {
            return FetchedSource(
                source,
                emptyList(),
                SourceStatus(
                    source.id, source.name, source.url, true, false,
                    error = "пауза после повторных сбоев",
                    fetchedAt = now.toString(), attempts = 0,
                    note = "источник пропускается, пока сбор не запустят с force",
                ),
            )
        }
        val blocked = UrlPolicy.rejection(source.url)
        if (blocked != null) {
            return FetchedSource(
                source,
                emptyList(),
                SourceStatus(source.id, source.name, source.url, true, false, error = blocked, fetchedAt = now.toString(), attempts = 0),
            )
        }
        var lastError = "источник не ответил"
        var lastStatus: Int? = null
        val backoffs = listOf(0L, 1000L, 4000L)
        var used = 0
        for (attempt in 1..attempts) {
            used = attempt
            if (attempt > 1) sleep(backoffs.getOrElse(attempt - 1) { 4000L })
            val response = try {
                val conditional = transport as? ConditionalFeedTransport
                if (conditional != null && (etag != null || modifiedSince != null)) {
                    conditional.get(source.url, etag, modifiedSince)
                } else {
                    transport.get(source.url)
                }
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName
                continue
            }
            lastStatus = response.status
            if (response.status == 304 && existing != null) {
                val entries = existing.items.filter { it.sourceId == source.id }.map { item ->
                    ParsedEntry(
                        title = item.title,
                        url = item.url,
                        guid = item.id,
                        publishedRaw = item.publishedAt,
                        publishedAt = RssDates.parse(item.publishedAt) ?: Instant.parse(item.publishedAt),
                        excerpt = item.excerpt,
                        excerptTruncated = item.excerptTruncated,
                        excerptOrigin = item.excerptOrigin,
                    )
                }
                return FetchedSource(
                    source,
                    entries,
                    SourceStatus(
                        source.id, source.name, source.url, true, true, 304,
                        fetchedAt = now.toString(), attempts = attempt, itemsSeen = entries.size,
                        note = "лента не изменилась с прошлой проверки этой даты",
                        etag = response.etag ?: etag, lastModified = response.lastModified ?: modifiedSince,
                    ),
                )
            }
            if (response.error != null || response.body == null || response.status !in 200..299) {
                lastError = response.error ?: "HTTP ${response.status}"
                continue
            }
            var body = response.body
            var note: String? = null
            if (source.format != "html-index" && looksLikeHtml(body)) {
                val alternate = FeedDiscovery.alternate(body, source.url)
                val discovered = if (alternate == null) null else try {
                    transport.get(alternate)
                } catch (e: Exception) {
                    lastError = e.javaClass.simpleName
                    null
                }
                val discoveredBody = discovered?.body
                if (alternate == null || discovered == null || discovered.error != null || discoveredBody == null ||
                    discovered.status !in 200..299 || looksLikeHtml(discoveredBody)
                ) {
                    lastError = discovered?.error ?: "ответ похож на HTML, не на ленту"
                    continue
                }
                body = discoveredBody
                note = "лента найдена на том же сайте"
            }
            val parsed = try {
                if (source.format == "html-index") HtmlIndexParser.parse(source, body) else parser.parse(body, source.excerptMaxChars)
            } catch (e: FeedParseException) {
                return FetchedSource(
                    source,
                    emptyList(),
                    SourceStatus(
                        id = source.id, name = source.name, url = source.url, enabled = true, ok = false,
                        httpStatus = response.status, error = e.message ?: "лента не разобрана",
                        fetchedAt = now.toString(), attempts = attempt,
                    ),
                )
            }
            return try {
                val entries = parsed
                FetchedSource(
                    source,
                    entries,
                    SourceStatus(
                        id = source.id,
                        name = source.name,
                        url = source.url,
                        enabled = true,
                        ok = true,
                        httpStatus = response.status,
                        fetchedAt = now.toString(),
                        attempts = attempt,
                        itemsSeen = entries.size,
                        note = note,
                        etag = response.etag,
                        lastModified = response.lastModified,
                    ),
                )
            } catch (e: FeedParseException) {
                lastError = e.message ?: "лента не разобрана"
                continue
            }
        }
        return FetchedSource(
            source,
            emptyList(),
            SourceStatus(
                id = source.id,
                name = source.name,
                url = source.url,
                enabled = true,
                ok = false,
                httpStatus = lastStatus,
                error = lastError,
                fetchedAt = now.toString(),
                attempts = used,
            ),
        )
    }

    private fun looksLikeHtml(body: ByteArray): Boolean {
        val head = String(body, 0, minOf(200, body.size), Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }
}
