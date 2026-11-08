package com.urmindkilla.svodkalocal.core.store

import com.urmindkilla.svodkalocal.core.json.Codec
import com.urmindkilla.svodkalocal.core.model.Bookmark
import com.urmindkilla.svodkalocal.core.model.BookmarkDb
import com.urmindkilla.svodkalocal.core.model.DigestIndex
import com.urmindkilla.svodkalocal.core.model.Edition
import com.urmindkilla.svodkalocal.core.model.EditionItem
import com.urmindkilla.svodkalocal.core.model.FeedbackDb
import com.urmindkilla.svodkalocal.core.model.IndexRow
import com.urmindkilla.svodkalocal.core.model.SeenState
import com.urmindkilla.svodkalocal.core.model.Vote
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

interface DigestStore {
    fun readIndex(): DigestIndex?
    fun readEdition(date: String): Edition?
    fun readSeen(): SeenState
    fun writeEdition(edition: Edition)
    fun writeSeen(state: SeenState)
    fun writeIndex(index: DigestIndex)
    fun writeReadme(text: String)
    fun writeLastRun(text: String)
}

class FileDigestStore(private val root: Path) : DigestStore {
    override fun readIndex(): DigestIndex? = read(root.resolve("public/index.json"))?.let(Codec::index)
    override fun readEdition(date: String): Edition? {
        require(date.matches(DATE))
        return read(root.resolve("public/$date.json"))?.let(Codec::edition)
    }

    override fun readSeen(): SeenState =
        read(root.resolve("state/seen.json"))?.let(Codec::seen) ?: SeenState()

    override fun writeEdition(edition: Edition) {
        val file = root.resolve("public/${edition.editionDate}.json")
        Files.createDirectories(file.parent)
        file.writeText(Codec.write(edition))
    }

    override fun writeSeen(state: SeenState) {
        val file = root.resolve("state/seen.json")
        Files.createDirectories(file.parent)
        file.writeText(Codec.write(state))
    }

    override fun writeIndex(index: DigestIndex) {
        val file = root.resolve("public/index.json")
        Files.createDirectories(file.parent)
        file.writeText(Codec.write(index))
    }

    override fun writeReadme(text: String) {
        root.resolve("README.md").writeText(text)
    }

    override fun writeLastRun(text: String) {
        val file = root.resolve("state/last-run.txt")
        Files.createDirectories(file.parent)
        file.writeText(text)
    }

    private fun read(path: Path): String? = if (path.exists()) path.readText() else null

    companion object {
        private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}

class DeviceArchive(private val root: Path) {
    fun saveEdition(edition: Edition) {
        val file = root.resolve("editions/${edition.editionDate}.json")
        Files.createDirectories(file.parent)
        file.writeText(Codec.write(edition))
        val rows = listEditions().filter { it.date != edition.editionDate } + IndexRow(
            date = edition.editionDate,
            file = "${edition.editionDate}.json",
            itemCount = edition.items.size,
            partial = edition.partial,
        )
        root.resolve("index.json").writeText(
            Codec.write(
                com.urmindkilla.svodkalocal.core.model.DigestIndex(
                    latest = rows.maxByOrNull { it.date }?.date,
                    editions = rows.sortedByDescending { it.date },
                ),
            ),
        )
    }

    fun loadEdition(date: String): Edition? {
        val file = root.resolve("editions/$date.json")
        if (!file.exists()) return null
        return Codec.edition(file.readText())
    }

    fun listEditions(): List<IndexRow> {
        val file = root.resolve("index.json")
        if (!file.exists()) return emptyList()
        return Codec.index(file.readText()).editions
    }

    fun prune(today: LocalDate, keepDays: Long = 30) {
        val cutoff = today.minusDays(keepDays).toString()
        val remain = listEditions().filter { it.date >= cutoff }
        listEditions().filter { it.date < cutoff }.forEach { row ->
            Files.deleteIfExists(root.resolve("editions/${row.date}.json"))
        }
        root.resolve("index.json").writeText(
            Codec.write(
                com.urmindkilla.svodkalocal.core.model.DigestIndex(
                    latest = remain.maxByOrNull { it.date }?.date,
                    editions = remain.sortedByDescending { it.date },
                ),
            ),
        )
    }

    fun readBookmarks(): BookmarkDb {
        val file = root.resolve("bookmarks.json")
        if (!file.exists()) return BookmarkDb()
        return Codec.bookmarks(file.readText())
    }

    fun writeBookmarks(db: BookmarkDb) {
        root.resolve("bookmarks.json").writeText(Codec.write(db))
    }

    fun saveBookmark(item: EditionItem, savedAt: String) {
        val current = readBookmarks().items.filter { it.item.id != item.id }
        writeBookmarks(BookmarkDb(current + Bookmark(item, savedAt)))
    }

    fun removeBookmark(itemId: String) {
        writeBookmarks(BookmarkDb(readBookmarks().items.filter { it.item.id != itemId }))
    }

    fun readFeedback(): FeedbackDb {
        val file = root.resolve("feedback.json")
        if (!file.exists()) return FeedbackDb()
        return Codec.feedback(file.readText())
    }

    fun writeFeedback(db: FeedbackDb) {
        root.resolve("feedback.json").writeText(Codec.write(db))
    }

    fun vote(vote: Vote) {
        val rest = readFeedback().votes.filter { it.itemId != vote.itemId }
        writeFeedback(FeedbackDb(rest + vote))
    }

    fun clearVote(itemId: String) {
        writeFeedback(FeedbackDb(readFeedback().votes.filter { it.itemId != itemId }))
    }
}

object DataBranchReadme {
    const val TEXT: String = """
# Данные «Сводки Локал»

Эта ветка содержит только публичный JSON ежедневного выпуска.

Здесь нет оценок читателя, закладок и личных данных. Телефон хранит их у себя и никуда их не отправляет.

Фрагменты взяты из RSS/Atom и усечены. Это не перепечатка статей и не редакционный обзор.
"""
}
