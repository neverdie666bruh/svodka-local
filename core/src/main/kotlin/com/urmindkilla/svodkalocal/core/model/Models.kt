package com.urmindkilla.svodkalocal.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Category {
    ai,
    gadgets,
    programming,
    hardware,
    security,
    space,
    brain,
    games,
    anime,
    music,
    books,
    oddities,
    world,
}

fun Category.titleRu(): String = when (this) {
    Category.ai -> "ИИ"
    Category.gadgets -> "Гаджеты"
    Category.programming -> "Программирование"
    Category.hardware -> "Железо"
    Category.security -> "Безопасность"
    Category.space -> "Космос"
    Category.brain -> "Мозг"
    Category.games -> "Игры"
    Category.anime -> "Аниме"
    Category.music -> "Музыка"
    Category.books -> "Книги"
    Category.oddities -> "Странности"
    Category.world -> "Мир"
}

@Serializable
data class FeedSource(
    val id: String,
    val name: String,
    val url: String,
    val groups: List<Category>,
    val enabled: Boolean = true,
    val excerptMaxChars: Int = 480,
    val licenseNote: String,
    val verifiedAt: String? = null,
    val verifiedStatus: Int? = null,
    val verifiedLatest: String? = null,
)

@Serializable
data class ScheduleDecl(
    val timezone: String = "Asia/Dushanbe",
    val localTime: String = "08:15",
    val cron: String = "15 8 * * *",
    val utcEquivalent: String = "03:15",
    val guarantee: String = "Точная минута не обещается: GitHub может задержать или пропустить запуск.",
)

@Serializable
data class RelatedLink(
    val sourceName: String,
    val url: String,
    val title: String,
)

@Serializable
data class TopicCoverage(
    val category: Category,
    val enabledSources: Int,
    val sourceDomains: Int,
    val eligible: Int,
    val published: Int,
    val note: String,
)

@Serializable
data class MatchInfo(
    val confidence: Double,
    val labels: List<String>,
    val positiveTerms: List<String>,
)

@Serializable
data class EditionItem(
    val id: String,
    val title: String,
    val url: String,
    val category: Category,
    val sourceId: String,
    val sourceName: String,
    val sourceFeed: String,
    val publishedAt: String,
    val excerpt: String,
    val excerptTruncated: Boolean,
    val excerptOrigin: String,
    val match: MatchInfo,
    val related: List<RelatedLink> = emptyList(),
)

@Serializable
data class SourceStatus(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val ok: Boolean,
    val httpStatus: Int? = null,
    val error: String? = null,
    val fetchedAt: String? = null,
    val attempts: Int = 0,
    val itemsSeen: Int = 0,
    val itemsUsed: Int = 0,
    val note: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
)

@Serializable
data class EditionCounts(
    val seen: Int = 0,
    val published: Int = 0,
    val duplicates: Int = 0,
    val outOfWindow: Int = 0,
    val excluded: Int = 0,
    val lowConfidence: Int = 0,
    val badDate: Int = 0,
    val notRussian: Int = 0,
)

@Serializable
data class Edition(
    val schema: Int = SCHEMA,
    val editionDate: String,
    val timezone: String,
    val generatedAt: String,
    val windowHours: Int = 36,
    val collector: String = COLLECTOR_NAME,
    val partial: Boolean,
    val limitations: List<String> = LIMITATIONS,
    val summary: String,
    val schedule: ScheduleDecl = ScheduleDecl(),
    val items: List<EditionItem> = emptyList(),
    val sources: List<SourceStatus> = emptyList(),
    val counts: EditionCounts = EditionCounts(),
    val collectionFailed: Boolean = false,
    val coverage: List<TopicCoverage> = emptyList(),
)

@Serializable
data class IndexRow(
    val date: String,
    val file: String,
    val itemCount: Int,
    val partial: Boolean,
)

@Serializable
data class DigestIndex(
    val schema: Int = SCHEMA,
    val timezone: String = "Asia/Dushanbe",
    val latest: String? = null,
    val checkedAt: String? = null,
    val schedule: ScheduleDecl = ScheduleDecl(),
    val editions: List<IndexRow> = emptyList(),
    val note: String = PUBLIC_NOTE,
)

@Serializable
data class SeenStory(
    val url: String,
    val tokens: List<String>,
    val editionDate: String,
)

@Serializable
data class SeenState(
    val stories: List<SeenStory> = emptyList(),
)

@Serializable
data class SourceHealth(
    val id: String,
    val ok: Boolean,
    val httpStatus: Int? = null,
    val error: String? = null,
    val checkedAt: String,
    val consecutiveFailures: Int = 0,
    val etag: String? = null,
    val lastModified: String? = null,
)

@Serializable
data class HealthState(
    val sources: List<SourceHealth> = emptyList(),
)

@Serializable
data class Vote(
    val itemId: String,
    val category: Category,
    val tokens: List<String>,
    val value: String,
)

@Serializable
data class FeedbackDb(
    val votes: List<Vote> = emptyList(),
)

@Serializable
data class Bookmark(
    val item: EditionItem,
    val savedAt: String,
)

@Serializable
data class BookmarkDb(
    val items: List<Bookmark> = emptyList(),
)

const val SCHEMA = 1
const val COLLECTOR_NAME = "svodka-local-deterministic"
const val PUBLIC_NOTE =
    "Публичные фрагменты лент и правила отбора. Оценок читателя и личных данных здесь нет."

val LIMITATIONS = listOf(
    "Отбор детерминированный: группа источника, ключевые слова и порог совпадения. Это не смысловой разбор.",
    "Фрагмент — текст, который отдала лента RSS или Atom. Он очищен от разметки и усечён. Полная статья не скачивается.",
    "Совпадение с другим источником не проверялось. Пометка «не проверено независимо» относится к каждому пункту.",
    "Мнений, чужих точек зрения и вердикта нет: сборщик их не сочиняет.",
    "Мировые новости проходят только при нескольких сильных маркерах крупного события. Если уверенности нет, пункт опускается.",
    "Места в выпуске сначала отдаются разным темам, у которых есть прошедший порог материал. Пустая тема не заполняется чужой новостью.",
    "Повтор того же заголовка из другой ленты склеивается в один пункт со ссылками. Это не проверка факта вторым источником.",
    "Лайк и дизлайк меняют только порядок на этом телефоне и не перенастраивают серверный сбор.",
)

data class ParsedEntry(
    val title: String,
    val url: String,
    val guid: String?,
    val publishedRaw: String?,
    val publishedAt: java.time.Instant?,
    val excerpt: String,
    val excerptTruncated: Boolean,
    val excerptOrigin: String,
)

data class TransportResponse(
    val status: Int?,
    val body: ByteArray?,
    val error: String?,
    val attempts: Int,
    val finalUrl: String?,
    val etag: String? = null,
    val lastModified: String? = null,
)

fun interface FeedTransport {
    fun get(url: String): TransportResponse
}

interface ConditionalFeedTransport : FeedTransport {
    fun get(url: String, etag: String?, modifiedSince: String?): TransportResponse
}
