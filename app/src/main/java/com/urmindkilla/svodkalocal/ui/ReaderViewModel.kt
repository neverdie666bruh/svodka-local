package com.urmindkilla.svodkalocal.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.urmindkilla.svodkalocal.core.feedback.LocalRanker
import com.urmindkilla.svodkalocal.core.json.Codec
import com.urmindkilla.svodkalocal.core.model.Bookmark
import com.urmindkilla.svodkalocal.core.model.Edition
import com.urmindkilla.svodkalocal.core.model.EditionItem
import com.urmindkilla.svodkalocal.core.model.FeedbackDb
import com.urmindkilla.svodkalocal.core.model.IndexRow
import com.urmindkilla.svodkalocal.core.model.SCHEMA
import com.urmindkilla.svodkalocal.core.model.Vote
import com.urmindkilla.svodkalocal.core.net.IndexLocator
import com.urmindkilla.svodkalocal.core.net.PublicDigest
import com.urmindkilla.svodkalocal.core.net.UrlPolicy
import com.urmindkilla.svodkalocal.core.store.DeviceArchive
import com.urmindkilla.svodkalocal.core.text.Tokens
import com.urmindkilla.svodkalocal.core.time.EditionCalendar
import com.urmindkilla.svodkalocal.data.ReaderClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

data class ReaderUi(
    val loading: Boolean = false,
    val indexUrl: String = "",
    val edition: Edition? = null,
    val ranked: List<EditionItem> = emptyList(),
    val archive: List<IndexRow> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val feedback: FeedbackDb = FeedbackDb(),
    val lastAttempt: String? = null,
    val lastSuccess: String? = null,
    val lastError: String? = null,
    val banner: String? = null,
    val formError: String? = null,
)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("svodka_local_reader", Application.MODE_PRIVATE)
    private val archive = DeviceArchive(app.filesDir.toPath().resolve("archive"))
    private val client = ReaderClient()
    private var selectedDate: String? = null
    private val _state = MutableStateFlow(ReaderUi())
    val state: StateFlow<ReaderUi> = _state.asStateFlow()

    init {
        reloadLocal()
        refresh(manual = false)
    }

    fun reloadLocal() {
        val url = storedUrl()
        val edition = selectedDate?.let { archive.loadEdition(it) } ?: latestCached()
        val feedback = archive.readFeedback()
        _state.value = _state.value.copy(
            indexUrl = url,
            edition = edition,
            ranked = edition?.let { LocalRanker.rank(it.items, feedback) }.orEmpty(),
            archive = archive.listEditions(),
            bookmarks = archive.readBookmarks().items.sortedByDescending { it.savedAt },
            feedback = feedback,
            lastAttempt = prefs.getString("last_attempt", null),
            lastSuccess = prefs.getString("last_success", null),
            lastError = prefs.getString("last_error", null),
            banner = bannerFor(edition, prefs.getString("last_error", null)),
        )
    }

    fun saveUrl(raw: String) {
        val url = raw.trim().ifEmpty { PublicDigest.INDEX_URL }
        if (url.isNotEmpty()) {
            val rejection = UrlPolicy.rejection(url)
            if (rejection != null || !url.endsWith("index.json")) {
                _state.value = _state.value.copy(formError = rejection ?: "адрес должен заканчиваться на index.json")
                return
            }
        }
        prefs.edit().putString("index_url", url).apply()
        _state.value = _state.value.copy(indexUrl = url, formError = null)
    }

    fun refresh(manual: Boolean) {
        val url = storedUrl()
        if (url.isBlank()) {
            _state.value = _state.value.copy(
                loading = false,
                banner = "Адрес указателя не задан. Сбор идёт на сервере, телефон только скачивает готовый JSON.",
            )
            return
        }
        if (!manual && _state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val attempt = Instant.now().toString()
            prefs.edit().putString("last_attempt", attempt).apply()
            val result = withContext(Dispatchers.IO) {
                runCatching { download(url) }
            }
            result.onSuccess {
                selectedDate = null
                prefs.edit().putString("last_success", Instant.now().toString()).remove("last_error").apply()
            }.onFailure { error ->
                prefs.edit().putString("last_error", error.message ?: "не удалось скачать").apply()
            }
            reloadLocal()
            _state.value = _state.value.copy(loading = false)
        }
    }

    fun openEdition(date: String) {
        selectedDate = date
        val edition = archive.loadEdition(date) ?: return
        val feedback = archive.readFeedback()
        _state.value = _state.value.copy(
            edition = edition,
            ranked = LocalRanker.rank(edition.items, feedback),
            banner = bannerFor(edition, _state.value.lastError),
        )
    }

    fun findItem(id: String): EditionItem? {
        _state.value.edition?.items?.firstOrNull { it.id == id }?.let { return it }
        _state.value.bookmarks.firstOrNull { it.item.id == id }?.let { return it.item }
        return archive.listEditions().firstNotNullOfOrNull { row ->
            archive.loadEdition(row.date)?.items?.firstOrNull { it.id == id }
        }
    }

    fun vote(item: EditionItem, value: String) {
        val current = archive.readFeedback().votes.firstOrNull { it.itemId == item.id }
        if (current?.value == value) {
            archive.clearVote(item.id)
        } else {
            archive.vote(Vote(item.id, item.category, Tokens.titleTokens(item.title), value))
        }
        reloadLocal()
    }

    fun toggleBookmark(item: EditionItem) {
        if (archive.readBookmarks().items.any { it.item.id == item.id }) {
            archive.removeBookmark(item.id)
        } else {
            archive.saveBookmark(item, Instant.now().toString())
        }
        reloadLocal()
    }

    fun bookmarked(id: String): Boolean = _state.value.bookmarks.any { it.item.id == id }

    fun voteOf(id: String): String? = _state.value.feedback.votes.firstOrNull { it.itemId == id }?.value

    fun openExternal(url: String): Intent? {
        if (!url.startsWith("https://")) return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    private fun storedUrl(): String {
        val saved = prefs.getString("index_url", null)?.trim().orEmpty()
        return saved.ifEmpty { PublicDigest.INDEX_URL }
    }

    private fun download(indexUrl: String) {
        val indexBytes = client.get(indexUrl)
        val index = Codec.index(indexBytes.toString(Charsets.UTF_8))
        if (index.schema != SCHEMA) error("неизвестная версия указателя")
        val latest = index.latest ?: error("в указателе нет выпуска")
        val row = index.editions.firstOrNull { it.date == latest } ?: error("в указателе нет файла выпуска")
        val editionUrl = IndexLocator.editionUrl(indexUrl, row.file) ?: error("файл выпуска указан небезопасно")
        val edition = Codec.edition(client.get(editionUrl).toString(Charsets.UTF_8))
        if (edition.schema != SCHEMA) error("неизвестная версия выпуска")
        archive.saveEdition(edition)
        archive.prune(LocalDate.now(EditionCalendar.ZONE), keepDays = 30)
    }

    private fun latestCached(): Edition? {
        val rows = archive.listEditions()
        val today = LocalDate.now(EditionCalendar.ZONE).toString()
        val date = rows.firstOrNull { it.date == today }?.date ?: rows.firstOrNull()?.date
        return date?.let { archive.loadEdition(it) }
    }

    private fun bannerFor(edition: Edition?, error: String?): String? {
        val today = LocalDate.now(EditionCalendar.ZONE).toString()
        return when {
            edition == null && error != null -> "Нет сохранённого выпуска. Ошибка: $error"
            edition == null -> "Сохранённого выпуска нет."
            edition.editionDate != today && error != null ->
                "Показан выпуск за ${edition.editionDate}. Сегодня по Asia/Dushanbe $today. Обновление не удалось: $error"
            edition.editionDate != today ->
                "Это не выпуск за сегодня. Дата сводки ${edition.editionDate}, сегодня по Asia/Dushanbe $today."
            edition.partial -> "Выпуск неполный: часть источников не ответила."
            error != null -> "На экране сохранённая копия. Последняя загрузка не удалась: $error"
            else -> null
        }
    }
}
