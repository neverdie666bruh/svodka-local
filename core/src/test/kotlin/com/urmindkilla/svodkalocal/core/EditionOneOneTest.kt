package com.urmindkilla.svodkalocal.core

import com.urmindkilla.svodkalocal.core.classify.Classifier
import com.urmindkilla.svodkalocal.core.edition.CollectService
import com.urmindkilla.svodkalocal.core.edition.EditionBuilder
import com.urmindkilla.svodkalocal.core.edition.FetchedSource
import com.urmindkilla.svodkalocal.core.json.Codec
import com.urmindkilla.svodkalocal.core.json.RuleFile
import com.urmindkilla.svodkalocal.core.model.Category
import com.urmindkilla.svodkalocal.core.model.Edition
import com.urmindkilla.svodkalocal.core.model.EditionItem
import com.urmindkilla.svodkalocal.core.model.FeedSource
import com.urmindkilla.svodkalocal.core.model.FeedTransport
import com.urmindkilla.svodkalocal.core.model.MatchInfo
import com.urmindkilla.svodkalocal.core.model.ParsedEntry
import com.urmindkilla.svodkalocal.core.model.SourceStatus
import com.urmindkilla.svodkalocal.core.model.TransportResponse
import com.urmindkilla.svodkalocal.core.net.FeedDiscovery
import com.urmindkilla.svodkalocal.core.store.FileDigestStore
import com.urmindkilla.svodkalocal.core.text.Tokens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.readText

class EditionOneOneTest {
    private val now = Instant.parse("2026-11-08T08:00:00Z")
    private val rules = Codec.json.decodeFromString<RuleFile>(repoRoot().resolve("sources/rules.json").readText())
    private val classifier = Classifier(rules)
    private val builder = EditionBuilder(classifier)

    @Test
    fun everyTopicHasPositiveAndExclusion() {
        val cases = listOf(
            Triple(Category.ai, "Вышла языковая модель для исследований", "описание модели"),
            Triple(Category.gadgets, "Xiaomi обновила Poco и HyperOS на смартфоне", "камера"),
            Triple(Category.programming, "Вышел язык программирования и открытый исходный код", "kotlin"),
            Triple(Category.hardware, "Новая видеокарта GeForce получила больше видеопамяти", "тест"),
            Triple(Category.security, "Закрыта уязвимость CVE-2026-1000 в библиотеке", "патч"),
            Triple(Category.space, "Телескоп нашёл экзопланету у соседней звезды", "астрономы"),
            Triple(Category.brain, "Нейронаука показала, как мозг держит рабочую память", "опыт"),
            Triple(Category.games, "В Steam вышла новая инди игра", "релиз"),
            Triple(Category.anime, "Вышел новый сезон аниме и опенинг", "студия"),
            Triple(Category.music, "Певица выпустила новый альбом в цифровых магазинах", "запись"),
            Triple(Category.books, "Издательство выпустило роман в жанре фантастики", "книга"),
            Triple(Category.oddities, "Учёные впервые обнаружили странную находку", "заметка"),
            Triple(Category.world, "Страна объявила войну и ввела чрезвычайное положение", "сводка"),
        )
        assertEquals(Category.entries.size, cases.size)
        for ((category, title, excerpt) in cases) {
            val source = FeedSource(category.name, category.name, "https://example.com/${category.name}.xml", listOf(category), licenseNote = "тест")
            val result = classifier.classify(title, excerpt, source)
            assertEquals(category, result?.category, title)
        }
        assertEquals("биткоин", classifier.hardExcluded("Смартфон Poco и биткоин в одном заголовке", "камера"))
        assertEquals("футбол", classifier.hardExcluded("Игра и футбол в одном абзаце", "матч"))
        assertEquals("гороскоп", classifier.hardExcluded("Гороскоп на неделю для всех знаков", "звёзды"))
        assertTrue(classifier.classify("SpaceX показала телескоп", "запуск", source(Category.space)) == null)
        assertTrue(classifier.classify("Вышел новый фильм Netflix", "премьера", source(Category.games)) == null)
        assertTrue(classifier.classify("Певица анонсировала концерт и турне", "билеты", source(Category.music)) == null)
    }

    @Test
    fun balanceKeepsAWeakTopicBesideTech() {
        val tech = (1..20).map { entry("Нейросеть номер$it открыла архив района$it", "https://example.com/ai/$it") }
        val anime = entry("Студия показала новый сезон аниме", "https://example.com/anime")
        val source = FeedSource("mix", "mix", "https://example.com/mix.xml", listOf(Category.ai, Category.anime), licenseNote = "тест")
        val built = builder.build(now, listOf(fetched(tech + anime, source)), emptyList())
        assertTrue(built.edition.items.any { it.category == Category.anime })
        assertTrue(built.edition.items.size <= 20)
        assertEquals(1, built.edition.coverage.first { it.category == Category.anime }.published)
    }

    @Test
    fun syndicationIsOneStoryWithRelatedLinks(@TempDir dir: Path) {
        val first = entry("Новый телескоп нашёл далёкую экзопланету у звезды", "https://example.com/a")
        val second = entry("Новый телескоп нашёл далёкую экзопланету у звезды", "https://other.example/b")
        val built = builder.build(
            now,
            listOf(
                fetched(listOf(first), source("alpha", Category.space, "https://alpha.example/rss")),
                fetched(listOf(second), source("beta", Category.space, "https://beta.example/rss")),
            ),
            emptyList(),
        )
        assertEquals(1, built.edition.items.size)
        val item = built.edition.items.single()
        assertEquals(1, item.related.size)
        assertTrue(item.match.labels.any { it.contains("не независимая проверка") })
        assertTrue(built.edition.counts.duplicates >= 1)
        val oldJson = """
            {"schema":1,"editionDate":"2026-11-08","timezone":"Asia/Dushanbe","generatedAt":"2026-11-08T08:00:00Z","partial":false,"summary":"старый","items":[]}
        """.trimIndent()
        val decoded = Codec.edition(oldJson)
        assertEquals(1, decoded.schema)
        assertTrue(decoded.coverage.isEmpty())
        assertFalse(decoded.collectionFailed)
        FileDigestStore(dir).writeEdition(decoded)
    }

    @Test
    fun httpLinkFromHttpsFeedBecomesHttps() {
        val built = builder.build(
            now,
            listOf(fetched(listOf(entry("Телескоп нашёл новую экзопланету у звезды", "http://example.com/story")), source("space", Category.space, "https://example.com/rss"))),
            emptyList(),
        )
        assertEquals("https://example.com/story", built.edition.items.single().url)
    }

    @Test
    fun allSourcesDownDoesNotPretendTheDayWasEmpty(@TempDir dir: Path) {
        val store = FileDigestStore(dir)
        val service = CollectService(
            FeedTransport { TransportResponse(503, null, "HTTP 503", 1, it) },
            classifier,
            clock = { now },
            sleep = {},
        )
        val source = FeedSource("space", "space", "https://example.com/space.xml", listOf(Category.space), licenseNote = "тест")
        val failed = service.run(store, listOf(source), false)
        val edition = store.readEdition("2026-11-08")!!
        assertEquals(2, failed.exitCode)
        assertTrue(edition.collectionFailed)
        assertTrue(edition.summary.contains("ошибка сбора"))
        assertEquals(0, edition.items.size)
        store.writeEdition(edition.copy(items = listOf(sampleItem()), collectionFailed = false, partial = true, summary = "хороший"))
        val again = service.run(store, listOf(source), false)
        val kept = store.readEdition("2026-11-08")!!
        assertTrue(again.partial)
        assertEquals(1, kept.items.size)
        assertTrue(kept.collectionFailed)
        assertTrue(kept.summary.contains("Сохранён предыдущий список"))
        assertTrue(store.readHealth().sources.single().consecutiveFailures >= 1)
    }

    @Test
    fun discoveryStaysOnTheSameHost() {
        val html = """<html><link rel="alternate" type="application/rss+xml" href="/news.xml"><link rel="alternate" type="application/rss+xml" href="https://evil.example/rss"></html>"""
        assertEquals("https://example.com/news.xml", FeedDiscovery.alternate(html.toByteArray(), "https://example.com/page"))
    }

    @Test
    fun continuationNeedsANewNumber() {
        val ai = source(Category.ai)
        val seen = com.urmindkilla.svodkalocal.core.model.SeenStory(
            "https://example.com/old",
            Tokens.titleTokens("Вышла большая языковая модель Альфа для исследований лаборатории"),
            "2026-11-07",
        )
        val repeat = builder.build(
            now,
            listOf(fetched(listOf(entry("Вышла большая языковая модель Альфа для исследований лаборатории", "https://example.com/new")), ai)),
            listOf(seen),
        )
        assertTrue(repeat.edition.items.isEmpty())
        val next = builder.build(
            now,
            listOf(fetched(listOf(entry("Вышла большая языковая модель Альфа2 для исследований лаборатории", "https://example.com/new2")), ai)),
            listOf(seen),
        )
        assertEquals(1, next.edition.items.size)
        assertTrue(next.edition.items.single().match.labels.any { it.contains("продолжение") })
    }

    private fun source(category: Category) =
        FeedSource(category.name, category.name, "https://example.com/${category.name}.xml", listOf(category), licenseNote = "тест")

    private fun source(id: String, category: Category, url: String) =
        FeedSource(id, id, url, listOf(category), licenseNote = "тест")

    private fun fetched(entries: List<ParsedEntry>, source: FeedSource = source(Category.space)) = FetchedSource(
        source,
        entries,
        SourceStatus(source.id, source.name, source.url, true, true, 200, itemsSeen = entries.size),
    )

    private fun entry(title: String, url: String) = ParsedEntry(
        title, url, null, "Sun, 08 Nov 2026 01:00:00 GMT", now.minus(2, ChronoUnit.HOURS),
        "Короткий фрагмент ленты.", false, "feed_description",
    )

    private fun sampleItem() = EditionItem(
        id = "keep", title = "Телескоп нашёл комету", url = "https://example.com/keep", category = Category.space,
        sourceId = "space", sourceName = "space", sourceFeed = "https://example.com/space.xml", publishedAt = now.toString(),
        excerpt = "фрагмент", excerptTruncated = false, excerptOrigin = "feed_description",
        match = MatchInfo(0.8, listOf("детерминированное совпадение"), listOf("телескоп")),
    )
}
