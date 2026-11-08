package com.urmindkilla.svodkalocal.core.json

import com.urmindkilla.svodkalocal.core.model.BookmarkDb
import com.urmindkilla.svodkalocal.core.model.DigestIndex
import com.urmindkilla.svodkalocal.core.model.Edition
import com.urmindkilla.svodkalocal.core.model.FeedbackDb
import com.urmindkilla.svodkalocal.core.model.FeedSource
import com.urmindkilla.svodkalocal.core.model.SeenState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object Codec {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun edition(text: String): Edition = json.decodeFromString(text)
    fun index(text: String): DigestIndex = json.decodeFromString(text)
    fun seen(text: String): SeenState = json.decodeFromString(text)
    fun sources(text: String): List<FeedSource> = json.decodeFromString(text)
    fun feedback(text: String): FeedbackDb = json.decodeFromString(text)
    fun bookmarks(text: String): BookmarkDb = json.decodeFromString(text)

    fun write(value: Edition): String = json.encodeToString(Edition.serializer(), value) + "\n"
    fun write(value: DigestIndex): String = json.encodeToString(DigestIndex.serializer(), value) + "\n"
    fun write(value: SeenState): String = json.encodeToString(SeenState.serializer(), value) + "\n"
    fun write(value: FeedbackDb): String = json.encodeToString(FeedbackDb.serializer(), value) + "\n"
    fun write(value: BookmarkDb): String = json.encodeToString(BookmarkDb.serializer(), value) + "\n"
}

@Serializable
data class RuleFile(
    val hardExclude: List<String> = emptyList(),
    val filmTerms: List<String> = emptyList(),
    val filmExempt: List<String> = listOf("anime"),
    val categories: Map<String, CategoryRule> = emptyMap(),
)

@Serializable
data class CategoryRule(
    val positive: List<String> = emptyList(),
    val negative: List<String> = emptyList(),
    val minScore: Double = 0.5,
    val minTitleHits: Int = 0,
    val minPositiveHits: Int = 0,
    val firstExcerptAsTitle: Boolean = false,
)
