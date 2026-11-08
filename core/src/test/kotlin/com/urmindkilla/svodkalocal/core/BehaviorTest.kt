package com.urmindkilla.svodkalocal.core

import com.urmindkilla.svodkalocal.core.classify.Classifier
import com.urmindkilla.svodkalocal.core.edition.CollectService
import com.urmindkilla.svodkalocal.core.edition.EditionBuilder
import com.urmindkilla.svodkalocal.core.edition.FetchedSource
import com.urmindkilla.svodkalocal.core.feedback.LocalRanker
import com.urmindkilla.svodkalocal.core.json.Codec
import com.urmindkilla.svodkalocal.core.json.RuleFile
import com.urmindkilla.svodkalocal.core.model.Category
import com.urmindkilla.svodkalocal.core.model.EditionItem
import com.urmindkilla.svodkalocal.core.model.FeedSource
import com.urmindkilla.svodkalocal.core.model.FeedTransport
import com.urmindkilla.svodkalocal.core.model.ParsedEntry
import com.urmindkilla.svodkalocal.core.model.SeenStory
import com.urmindkilla.svodkalocal.core.model.SourceStatus
import com.urmindkilla.svodkalocal.core.model.TransportResponse
import com.urmindkilla.svodkalocal.core.model.Vote
import com.urmindkilla.svodkalocal.core.net.IndexLocator
import com.urmindkilla.svodkalocal.core.net.UrlCanonicalizer
import com.urmindkilla.svodkalocal.core.net.UrlPolicy
import com.urmindkilla.svodkalocal.core.rss.FeedParseException
import com.urmindkilla.svodkalocal.core.rss.RssDates
import com.urmindkilla.svodkalocal.core.rss.SafeFeedParser
import com.urmindkilla.svodkalocal.core.store.DeviceArchive
import com.urmindkilla.svodkalocal.core.store.FileDigestStore
import com.urmindkilla.svodkalocal.core.text.HtmlText
import com.urmindkilla.svodkalocal.core.text.Tokens
import com.urmindkilla.svodkalocal.core.time.EditionCalendar
import com.urmindkilla.svodkalocal.core.time.RunDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

class BehaviorTest {
    private val now: Instant = Instant.parse("2026-11-08T03:15:00Z")
    private val rules = Codec.json.decodeFromString<RuleFile>(repoRoot().resolve("sources/rules.json").readText())
    private val classifier = Classifier(rules)
    private val builder = EditionBuilder(classifier)

    @Test
    fun parsesRssAtomAndRejectsDoctype() {
        val parser = SafeFeedParser()
        val rss = parser.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Лента</title>
              <item>
                <title><![CDATA[Новый телескоп увидел <b>комету</b>]]></title>
                <link>https://example.com/a?utm_source=rss</link>
                <pubDate>Sun, 08 Nov 2026 01:00:00 GMT</pubDate>
                <description><![CDATA[<script>alert(1)</script><p>Короткий фрагмент про телескоп и комету.</p>]]></description>
              </item>
            </channel></rss>
            """.trimIndent().toByteArray(),
            480,
        )
        assertEquals(1, rss.size)
        assertFalse(rss[0].excerpt.contains("script"))
        assertFalse(rss[0].excerpt.contains("<p>"))
        assertTrue(rss[0].excerpt.contains("телескоп"))
        val atom = parser.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Издательство выпустило книгу фантастики</title>
                <link rel="alternate" href="https://example.com/book"/>
                <updated>2026-11-08T02:00:00Z</updated>
                <summary>Короткое описание фантастики в ленте.</summary>
              </entry>
            </feed>
            """.trimIndent().toByteArray(),
            200,
        )
        assertEquals("https://example.com/book", atom.single().url)
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <rss><channel><item><title>&xxe;</title><link>https://example.com/x</link></item></channel></rss>
        """.trimIndent().toByteArray()
        org.junit.jupiter.api.assertThrows<FeedParseException> { parser.parse(xxe, 100) }
    }

    @Test
    fun parsesWindows1251WithoutAssumingUtf8() {
        val xml = """
            <?xml version="1.0" encoding="windows-1251"?>
            <rss version="2.0"><channel><item>
              <title>Телескоп нашёл комету</title>
              <link>https://example.com/w</link>
              <pubDate>Sun, 08 Nov 2026 01:00:00 GMT</pubDate>
              <description>Фрагмент про телескоп.</description>
            </item></channel></rss>
        """.trimIndent()
        val bytes = xml.toByteArray(charset("windows-1251"))
        val item = SafeFeedParser().parse(bytes, 200).single()
        assertEquals("Телескоп нашёл комету", item.title)
    }

    @Test
    fun datesWindowAndEditionBoundary() {
        assertNotNull(RssDates.parse("Sun, 08 Nov 2026 08:59:11 GMT"))
        assertNotNull(RssDates.parse("Sun, 08 Nov 2026 11:58:59 +0300"))
        assertNotNull(RssDates.parse("2026-11-08T10:00:33+03:00"))
        assertNotNull(RssDates.parse("05 Nov 2026 04:26:00 +0300"))
        assertNull(RssDates.parse("не дата"))
        assertNull(RssDates.parse("2026-11-08 10:00:00"))
        assertTrue(EditionCalendar.inWindow(now.minus(2, ChronoUnit.HOURS), now))
        assertFalse(EditionCalendar.inWindow(now.minus(40, ChronoUnit.HOURS), now))
        assertFalse(EditionCalendar.inWindow(now.plus(10, ChronoUnit.HOURS), now))
        assertEquals(LocalDate.parse("2026-11-08"), EditionCalendar.editionDate(Instant.parse("2026-11-08T18:59:00Z")))
        assertEquals(LocalDate.parse("2026-11-09"), EditionCalendar.editionDate(Instant.parse("2026-11-08T19:00:00Z")))
        assertEquals(RunDecision.SKIP_COMPLETE, EditionCalendar.decide(sampleEdition(partial = false), force = false))
        assertEquals(RunDecision.RETRY_PARTIAL, EditionCalendar.decide(sampleEdition(partial = true), force = false))
        assertEquals(RunDecision.BUILD, EditionCalendar.decide(sampleEdition(partial = false), force = true))
        assertEquals(RunDecision.BUILD, EditionCalendar.decide(null, force = false))
    }

    @Test
    fun selectionExclusionsDedupAndNoPadding() {
        val kept = build(
            entry("Новый телескоп нашёл далёкую экзопланету у звезды", "https://example.com/space?utm_source=a&b=1"),
            entry("Новый телескоп нашёл далёкую экзопланету у звезды вчера", "https://example.com/space-copy"),
            entry("Компания добавила оплату биткоином в кошелёк приложения", "https://example.com/btc"),
            entry("Компания SpaceX показала телескоп для экзопланет", "https://example.com/sx"),
            entry("Издательство выпустило книгу в твердом переплёте", "https://example.com/book", excerpt = "Это роман в жанре фантастики."),
            entry("Издательство выпустило обычную книгу в переплёте", "https://example.com/plain"),
            entry("Страна объявила войну и ввела чрезвычайное положение", "https://example.com/war"),
            entry("Депутат обсудил бюджет региона на заседании", "https://example.com/local"),
            entry("Вышел новый фильм по мотивам игры студии", "https://example.com/film"),
            entry("Студия показала новый аниме сериал на сезон", "https://example.com/anime"),
            entry("Певица выпустила альбом Тишина в цифровых магазинах", "https://example.com/album"),
            entry("Певица выпустила альбом и большой концерт", "https://example.com/tour"),
            entry("OpenAI releases a new model for developers", "https://example.com/en"),
        )
        val titles = kept.edition.items.map { it.title }
        assertTrue(titles.any { it.contains("экзопланету у звезды") && !it.contains("вчера") })
        assertFalse(titles.any { it.contains("вчера") })
        assertFalse(titles.any { it.contains("биткоин") })
        assertFalse(titles.any { it.contains("SpaceX") })
        assertTrue(titles.any { it.contains("фантастики") || it.contains("твердом") })
        assertFalse(titles.any { it.contains("обычную книгу") })
        assertTrue(titles.any { it.contains("чрезвычайное") })
        assertFalse(titles.any { it.contains("бюджет") })
        assertFalse(titles.any { it.contains("фильм") })
        assertTrue(titles.any { it.contains("аниме") })
        assertTrue(titles.any { it.contains("Тишина") })
        assertFalse(titles.any { it.contains("концерт") })
        assertFalse(titles.any { it.contains("OpenAI") })
        assertTrue(kept.edition.items.size < 8)
        assertFalse(Codec.write(kept.edition).contains("viewpoints"))
        assertTrue(kept.edition.counts.excluded >= 1)
        assertTrue(kept.edition.counts.duplicates >= 1)
        assertEquals("https://example.com/space?b=1", kept.edition.items.first { it.url.contains("space") }.url)
    }

    @Test
    fun capsAtTwentyAndDoesNotRememberRejected() {
        val entries = (1..25).map { n ->
            entry("Нейросеть случай$n открыла архив района$n", "https://example.com/ai/$n")
        }
        val built = build(*entries.toTypedArray(), source = source("ai", Category.ai))
        assertEquals(20, built.edition.items.size)
        val rejected = entry("Компания добавила оплату биткоином в кошелёк приложения", "https://example.com/btc")
        val withReject = build(rejected, source = source("ai", Category.ai))
        assertTrue(withReject.edition.items.none { it.url.contains("btc") })
        assertTrue(withReject.seen.none { it.url.contains("btc") })
    }

    @Test
    fun seenWindowAndCatchUp(@TempDir dir: Path) {
        val old = SeenStory("https://example.com/old", Tokens.titleTokens("старый телескоп нашёл комету давно"), "2026-10-31")
        val recent = SeenStory("https://example.com/recent", listOf("aaaa"), "2026-11-02")
        val built = builder.build(
            now,
            listOf(fetched(entry("Новый телескоп нашёл далёкую экзопланету у звезды", "https://example.com/recent"))),
            listOf(old, recent),
        )
        assertTrue(built.edition.items.isEmpty())
        val revived = builder.build(
            now,
            listOf(fetched(entry("Старый телескоп нашёл комету снова", "https://example.com/old"))),
            listOf(old),
        )
        assertEquals(1, revived.edition.items.size)

        val store = FileDigestStore(dir)
        val service = service(dir, clock = { Instant.parse("2026-11-08T03:15:00Z") }, bodies = mapOf("https://example.com/one" to rss("Новый телескоп нашёл комету у звезды", "https://example.com/one")))
        service.run(store, listOf(source("space", Category.space, url = "https://example.com/one")), force = false)
        assertTrue(Files.exists(dir.resolve("public/2026-11-08.json")))
        assertFalse(Files.exists(dir.resolve("public/2026-11-07.json")))
    }

    @Test
    fun idempotentSkipPartialRetryAndOffline(@TempDir dir: Path) {
        val calls = mutableListOf<String>()
        val bodies = mutableMapOf(
            "https://example.com/ok" to rss("Новый телескоп нашёл комету у звезды", "https://example.com/ok"),
        )
        val transport = FeedTransport { url ->
            calls += url
            val body = bodies[url]
            if (body == null) TransportResponse(503, null, "HTTP 503", 1, url)
            else TransportResponse(200, body, null, 1, url)
        }
        val service = CollectService(transport, classifier, clock = { now }, sleep = {})
        val store = FileDigestStore(dir)
        val sources = listOf(
            source("space", Category.space, url = "https://example.com/ok"),
            source("down", Category.space, url = "https://example.com/down"),
            source("off", Category.games, url = "https://example.com/off").copy(enabled = false),
        )
        val first = service.run(store, sources, force = false)
        assertEquals(2, first.exitCode)
        assertTrue(first.partial)
        assertEquals(1, store.readEdition("2026-11-08")!!.items.size)
        assertFalse(calls.contains("https://example.com/off"))
        val generated = store.readEdition("2026-11-08")!!.generatedAt
        calls.clear()
        bodies["https://example.com/down"] = rss("Радиотелескоп нашёл новую галактику в обзоре", "https://example.com/galaxy")
        val second = service.run(store, sources, force = false)
        assertEquals(0, second.exitCode)
        assertFalse(calls.contains("https://example.com/ok"))
        assertEquals(2, store.readEdition("2026-11-08")!!.items.size)
        assertFalse(store.readEdition("2026-11-08")!!.partial)
        val third = service.run(store, sources, force = false)
        assertTrue(third.skipped)
        assertEquals(generated, store.readEdition("2026-11-08")!!.generatedAt)
        val offlineDir = Files.createTempDirectory(dir, "off")
        val failed = CollectService(
            FeedTransport { TransportResponse(null, null, "нет сети", 1, it) },
            classifier,
            clock = { now },
            sleep = {},
        ).run(FileDigestStore(offlineDir), listOf(source("space", Category.space, url = "https://example.com/ok")), false)
        assertEquals(2, failed.exitCode)
        assertEquals(0, FileDigestStore(offlineDir).readEdition("2026-11-08")!!.items.size)
    }

    @Test
    fun feedbackDoesNotEraseCategoryAndBookmarksSurvivePrune(@TempDir dir: Path) {
        val items = listOf(
            item("a", Category.ai, "Нейросеть альфа открыла архив"),
            item("b", Category.ai, "Нейросеть бета открыла архив"),
            item("c", Category.space, "Телескоп нашёл комету"),
        )
        val votes = com.urmindkilla.svodkalocal.core.model.FeedbackDb(
            listOf(
                Vote("a", Category.ai, Tokens.titleTokens(items[0].title), "dislike"),
                Vote("b", Category.ai, Tokens.titleTokens(items[1].title), "dislike"),
            ),
        )
        val ranked = LocalRanker.rank(items, votes)
        assertTrue(LocalRanker.hidesNothing(items, ranked))
        assertEquals(items.map { it.id }.toSet(), ranked.map { it.id }.toSet())
        assertEquals("c", ranked.first().id)

        val archive = DeviceArchive(dir)
        val edition = sampleEdition(partial = false).copy(items = items, editionDate = "2026-10-01")
        archive.saveEdition(edition)
        archive.saveBookmark(items[0], "2026-10-01T00:00:00Z")
        archive.prune(LocalDate.parse("2026-11-08"), keepDays = 30)
        assertNull(archive.loadEdition("2026-10-01"))
        assertEquals("a", archive.readBookmarks().items.single().item.id)
    }

    @Test
    fun urlPolicyHtmlAndWorkflowFiles() {
        assertNotNull(UrlPolicy.rejection("http://example.com/a"))
        assertNotNull(UrlPolicy.rejection("https://user:pass@example.com/a"))
        assertNotNull(UrlPolicy.rejection("https://127.0.0.1/a"))
        assertNotNull(UrlPolicy.rejection("https://10.1.2.3/a"))
        assertNotNull(UrlPolicy.rejection("https://169.254.169.254/a"))
        assertNotNull(UrlPolicy.rejection("https://localhost/a"))
        assertNotNull(UrlPolicy.rejection("file:///etc/passwd"))
        assertNull(UrlPolicy.rejection("http://127.0.0.1/a", allowLoopback = true))
        assertEquals(
            "https://example.com/a?b=2",
            UrlCanonicalizer.canonicalize("https://Example.com/a/?utm_source=x&b=2&fbclid=z#frag"),
        )
        val plain = HtmlText.toPlain("<style>x</style><p>Телескоп &amp; комета</p>", 10)
        assertEquals("Телескоп &…", plain.text)
        assertTrue(plain.truncated)
        assertNull(IndexLocator.editionUrl("https://example.com/data/public/index.json", "../secret.json"))
        assertNull(IndexLocator.editionUrl("https://example.com/data/public/index.json", "https://evil.example/a.json"))
        assertEquals(
            "https://example.com/data/public/2026-11-08.json",
            IndexLocator.editionUrl("https://example.com/data/public/index.json", "2026-11-08.json"),
        )
        val root = repoRoot()
        val collect = root.resolve(".github/workflows/collect-digest.yml").readText()
        val apk = root.resolve(".github/workflows/build-apk.yml").readText()
        assertFalse(collect.contains("upload-artifact"))
        assertFalse(collect.contains("actions/cache"))
        assertFalse(collect.contains("upload-pages"))
        assertFalse(collect.contains("gh release"))
        assertTrue(collect.contains("ubuntu-24.04"))
        assertTrue(collect.contains("Asia/Dushanbe"))
        assertFalse(collect.contains("macos-"))
        assertFalse(collect.contains("windows-"))
        assertFalse(collect.contains("SVODKA_LOCAL_COLLECT_ENABLED"))
        assertFalse(collect.contains("\n  push:"))
        assertTrue(apk.contains("retention-days: 1"))
        assertTrue(apk.contains("workflow_dispatch"))
        assertFalse(apk.contains("tags:"))
        assertFalse(apk.contains("schedule:"))
        assertFalse(apk.contains("softprops"))
        assertEquals(
            "https://raw.githubusercontent.com/neverdie666bruh/svodka-local/data/public/index.json",
            com.urmindkilla.svodkalocal.core.net.PublicDigest.INDEX_URL,
        )
        val sources = Codec.sources(root.resolve("sources/sources.json").readText())
        assertTrue(sources.all { it.url.startsWith("https://") && it.licenseNote.isNotBlank() && it.groups.isNotEmpty() })
        assertEquals(sources.size, sources.map { it.id }.distinct().size)
        assertTrue(sources.none { UrlPolicy.rejection(it.url.substringBefore('?')) != null })
    }

    @Test
    fun treeHasNoLegacyDigestDependency() {
        val needles = listOf(
            "gist." + "github.com",
            "ntfy." + "sh",
            "GITHUB_" + "GIST_TOKEN",
            "GITHUB_" + "REPO_TOKEN",
            "api.github.com/" + "gists",
            "X8" + " Pro",
        )
        val root = repoRoot()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val rel = root.relativize(path).toString()
                if (rel.startsWith(".git") || rel.startsWith("uploads") || rel.startsWith("agent-tools") ||
                    rel.contains("/build/") || rel.startsWith("build/") || rel.startsWith(".gradle")
                ) {
                    return@forEach
                }
                if (rel.endsWith(".png") || rel.endsWith(".jar") || rel.endsWith(".jks")) return@forEach
                val text = runCatching { path.readText() }.getOrNull() ?: return@forEach
                for (needle in needles) {
                    assertFalse(text.contains(needle), "$rel содержит запрещённую связь: $needle")
                }
                assertFalse(
                    Regex("""github\.com/neverdie666bruh/svodka(?!-local)""").containsMatchIn(text),
                    "$rel ссылается на прежний репозиторий",
                )
            }
        }
        assertTrue(repoRoot().resolve(".gitignore").readText().contains("uploads/"))
        val gradle = repoRoot().resolve("app/build.gradle.kts").readText()
        assertTrue(gradle.contains("applicationId = \"com.urmindkilla.svodkalocal\""))
        assertFalse(gradle.contains("work-runtime"))
        val manifest = repoRoot().resolve("app/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("POST_NOTIFICATIONS"))
        assertFalse(manifest.contains("FOREGROUND_SERVICE"))
        assertFalse(manifest.contains("RECEIVE_BOOT"))
    }

    private fun build(vararg entries: ParsedEntry, source: FeedSource = source("mix", Category.space, Category.books, Category.world, Category.games, Category.music, Category.anime, Category.ai)): com.urmindkilla.svodkalocal.core.edition.BuiltEdition {
        return builder.build(now, listOf(fetched(*entries, source = source)), emptyList())
    }

    private fun fetched(vararg entries: ParsedEntry, source: FeedSource = source("mix", Category.space)) = FetchedSource(
        source,
        entries.toList(),
        SourceStatus(source.id, source.name, source.url, source.enabled, true, 200, itemsSeen = entries.size),
    )

    private fun entry(title: String, url: String, excerpt: String = "Короткий фрагмент ленты без лишних тем."): ParsedEntry =
        ParsedEntry(title, url, null, "Sun, 08 Nov 2026 01:00:00 GMT", now.minus(2, ChronoUnit.HOURS), excerpt, false, "feed_description")

    private fun source(id: String, vararg groups: Category, url: String = "https://example.com/$id.xml") =
        FeedSource(id, id, url, groups.toList(), licenseNote = "тест")

    private fun item(id: String, category: Category, title: String) = EditionItem(
        id = id,
        title = title,
        url = "https://example.com/$id",
        category = category,
        sourceId = "s",
        sourceName = "s",
        sourceFeed = "https://example.com/s.xml",
        publishedAt = now.toString(),
        excerpt = "фрагмент",
        excerptTruncated = false,
        excerptOrigin = "feed_description",
        match = com.urmindkilla.svodkalocal.core.model.MatchInfo(0.8, listOf("детерминированное совпадение"), listOf("x")),
    )

    private fun sampleEdition(partial: Boolean) = com.urmindkilla.svodkalocal.core.model.Edition(
        editionDate = "2026-11-08",
        timezone = "Asia/Dushanbe",
        generatedAt = now.toString(),
        partial = partial,
        summary = "тест",
    )

    private fun service(dir: Path, clock: () -> Instant, bodies: Map<String, ByteArray>) = CollectService(
        transport = FeedTransport { url ->
            val body = bodies[url]
            if (body == null) TransportResponse(404, null, "нет", 1, url) else TransportResponse(200, body, null, 1, url)
        },
        classifier = classifier,
        clock = clock,
        sleep = {},
    )

    private fun rss(title: String, link: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><item>
          <title>$title</title>
          <link>$link</link>
          <pubDate>Sun, 08 Nov 2026 01:00:00 GMT</pubDate>
          <description>Короткий фрагмент ленты без лишних тем и длиннее сорока знаков.</description>
        </item></channel></rss>
    """.trimIndent().toByteArray()
}

fun repoRoot(): Path {
    var path = Path.of("").toAbsolutePath()
    repeat(8) {
        if (Files.exists(path.resolve("settings.gradle.kts"))) return path
        path = path.parent ?: error("корень не найден")
    }
    error("корень не найден")
}
