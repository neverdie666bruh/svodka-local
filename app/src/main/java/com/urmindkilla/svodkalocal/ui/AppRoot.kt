@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.urmindkilla.svodkalocal.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.urmindkilla.svodkalocal.core.model.Edition
import com.urmindkilla.svodkalocal.core.model.EditionItem
import com.urmindkilla.svodkalocal.core.model.LIMITATIONS
import com.urmindkilla.svodkalocal.core.model.titleRu
import com.urmindkilla.svodkalocal.core.time.EditionCalendar
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val tabs = listOf("today" to "Сегодня", "archive" to "Архив", "bookmarks" to "Закладки", "status" to "Сбор")

@Composable
fun AppRoot(model: ReaderViewModel = viewModel()) {
    val state by model.state.collectAsState()
    val nav = rememberNavController()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    val tabRoute = tabs.firstOrNull { current?.startsWith(it.first) == true }?.first
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (tabRoute != null || current == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { (route, label) ->
                        NavigationBarItem(
                            selected = current?.startsWith(route) == true,
                            onClick = {
                                nav.navigate(route) {
                                    popUpTo("today") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            label = { Text(label) },
                            icon = { Text(label.take(1), fontFamily = FontFamily.Serif) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(padding)) {
            composable("today") { TodayScreen(state, model) { nav.navigate("detail/$it") } }
            composable("archive") { ArchiveScreen(state) { model.openEdition(it); nav.navigate("today") } }
            composable("bookmarks") { BookmarkScreen(state) { nav.navigate("detail/$it") } }
            composable("status") { StatusScreen(state, model) }
            composable(
                "detail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                DetailScreen(model.findItem(id), model) { nav.popBackStack() }
            }
        }
    }
}

@Composable
private fun TodayScreen(state: ReaderUi, model: ReaderViewModel, open: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("СВОДКА ЛОКАЛ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(state.edition?.let { humanDate(it.editionDate) } ?: "Нет выпуска", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Готовый JSON. Отбор по правилам, без агента и без пересказа.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.banner?.let { text -> item { Banner(text) } }
        if (state.loading) item { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
        state.edition?.let { edition ->
            item { Text(edition.summary, style = MaterialTheme.typography.bodyLarge) }
            if (edition.items.isEmpty()) {
                item { EmptyCard("За это окно правила никого не пропустили. Пустой список честный: чужие темы сюда не добавляются.") }
            }
        } ?: item {
            EmptyCard("Когда сервер опубликует ветку data, вставьте адрес index.json на экране «Сбор» и нажмите «Загрузить».")
        }
        items(state.ranked, key = { it.id }) { item ->
            ArticleCard(item) { open(item.id) }
        }
    }
}

@Composable
private fun ArchiveScreen(state: ReaderUi, open: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Архив на телефоне", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                "Хранятся скачанные выпуски. Через 30 дней файлы выпуска удаляются, закладки остаются.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.archive.isEmpty()) item { EmptyCard("Архив пуст. Он появится после первой успешной загрузки.") }
        items(state.archive, key = { it.date }) { row ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), onClick = { open(row.date) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(humanDate(row.date), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${row.itemCount} материалов" + if (row.partial) " · неполный" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarkScreen(state: ReaderUi, open: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Закладки", style = MaterialTheme.typography.headlineMedium) }
        if (state.bookmarks.isEmpty()) {
            item { EmptyCard("Закладок нет. Они хранятся только на этом телефоне и не уходят на сервер.") }
        }
        items(state.bookmarks, key = { it.item.id }) { mark ->
            ArticleCard(mark.item) { open(mark.item.id) }
        }
    }
}

@Composable
private fun StatusScreen(state: ReaderUi, model: ReaderViewModel) {
    var draft by remember(state.indexUrl) { mutableStateOf(state.indexUrl) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Сбор", style = MaterialTheme.typography.headlineMedium) }
        item {
            InfoBlock(
                "Расписание сервера",
                "Цель — 08:15 Asia/Dushanbe (03:15 UTC), cron «15 8 * * *». " +
                    "Минута 15 выбрана потому, что начало часа у GitHub — час пик: запуск могут задержать или молча пропустить. " +
                    "У этой зоны нет летнего времени. Точную минуту приложение не обещает. " +
                    "Телефон сбор не запускает и разрешения на уведомления не просит.",
            )
        }
        item {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Адрес index.json") },
                placeholder = { Text(com.urmindkilla.svodkalocal.core.net.PublicDigest.INDEX_URL) },
                minLines = 2,
            )
            state.formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { model.saveUrl(draft) }) { Text("Сохранить адрес") }
                Button(onClick = { model.saveUrl(draft); model.refresh(manual = true) }, enabled = !state.loading) {
                    Text(if (state.loading) "Загрузка" else "Загрузить")
                }
            }
        }
        item {
            InfoBlock(
                "Последние попытки телефона",
                "Попытка: ${state.lastAttempt ?: "ещё не было"}\nУспех: ${state.lastSuccess ?: "ещё не было"}\nОшибка: ${state.lastError ?: "нет"}",
            )
        }
        state.edition?.let { edition ->
            item { Text("Источники выпуска ${edition.editionDate}", style = MaterialTheme.typography.titleLarge) }
            items(edition.sources, key = { it.id }) { source ->
                val line = when {
                    !source.enabled -> "выключен"
                    source.ok -> "ок · ${source.itemsSeen} в ленте · ${source.itemsUsed} в выпуске"
                    else -> "ошибка · ${source.error ?: "без текста"}"
                }
                Text("${source.name}: $line", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Text("Границы метода", style = MaterialTheme.typography.titleLarge) }
        items(LIMITATIONS) { line -> Text("· $line", style = MaterialTheme.typography.bodyMedium) }
        item {
            Text(
                "Лайк и дизлайк меняют только порядок на этом телефоне. Один-два дизлайка не убирают тему. Исключения из правил сильнее любой оценки. Оценки на сервер не отправляются.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DetailScreen(item: EditionItem?, model: ReaderViewModel, back: () -> Unit) {
    val context = LocalContext.current
    if (item == null) {
        Column(Modifier.padding(20.dp)) {
            Text("Материал не найден в архиве.", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = back) { Text("Назад") }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = back) { Text("Назад") }
            Text(item.category.titleRu().uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(item.title, style = MaterialTheme.typography.headlineMedium)
            Text("${item.sourceName} · ${humanTime(item.publishedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text("Материал источника") })
                FilterChip(selected = true, onClick = {}, label = { Text("Не проверено независимо") })
            }
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(item.excerpt.ifBlank { "Лента не прислала фрагмент." }, style = MaterialTheme.typography.bodyLarge)
                    if (item.excerptTruncated) {
                        Spacer(Modifier.height(8.dp))
                        Text("Фрагмент усечён. Это текст ленты, не полная статья.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Button(onClick = {
                val intent = model.openExternal(item.url)
                if (intent == null) {
                    Toast.makeText(context, "Ссылка не https", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "Нет приложения, чтобы открыть ссылку", Toast.LENGTH_SHORT).show()
                    }
                }
            }) { Text("Открыть источник в браузере") }
        }
        item {
            Text("Почему пункт в выпуске", style = MaterialTheme.typography.titleLarge)
            Text("Совпадение ${item.match.confidence}", color = MaterialTheme.colorScheme.primary)
            item.match.labels.forEach { label -> Text("· $label") }
            Text(
                "Это не мнение и не вывод о важности. Второго источника у сборщика нет.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        item {
            val vote = model.voteOf(item.id)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.vote(item, "like") }) {
                    Text(if (vote == "like") "Нравится · да" else "Нравится")
                }
                OutlinedButton(onClick = { model.vote(item, "dislike") }) {
                    Text(if (vote == "dislike") "Не нравится · да" else "Не нравится")
                }
            }
            OutlinedButton(onClick = { model.toggleBookmark(item) }, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (model.bookmarked(item.id)) "Убрать из закладок" else "В закладки")
            }
        }
    }
}

@Composable
private fun ArticleCard(item: EditionItem, open: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
        onClick = open,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${item.category.titleRu()}  ·  совпадение ${item.match.confidence}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text("${item.sourceName} · ${humanTime(item.publishedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (item.excerpt.isNotBlank()) {
                    Text(item.excerpt, maxLines = 3, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Материал источника · не проверено независимо", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
        Text(text, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyCard(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Text(text, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun InfoBlock(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun humanDate(iso: String): String {
    val date = LocalDate.parse(iso)
    val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    return "${date.dayOfMonth} ${months[date.monthValue - 1]}"
}

private fun humanTime(raw: String): String = runCatching {
    val zoned = OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.of(EditionCalendar.ZONE_ID))
    zoned.format(DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru")))
}.getOrDefault(raw)
