package com.urmindkilla.svodkalocal.core.rss

import com.urmindkilla.svodkalocal.core.model.ParsedEntry
import com.urmindkilla.svodkalocal.core.text.HtmlText
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory

class FeedParseException(message: String) : Exception(message)

private class EnoughItems : org.xml.sax.SAXException("enough")

private fun containsEnough(error: Throwable?): Boolean {
    var current = error
    while (current != null) {
        if (current is EnoughItems) return true
        current = current.cause
    }
    return false
}

class SafeFeedParser(
    private val maxItems: Int = 80,
) {
    fun parse(bytes: ByteArray, excerptMaxChars: Int): List<ParsedEntry> {
        if (bytes.size > 4_000_000) throw FeedParseException("лента больше лимита")
        rejectDangerousProlog(bytes)
        val reader = try {
            secureReader()
        } catch (e: Exception) {
            throw FeedParseException("небезопасный разбор XML отключён: ${e.javaClass.simpleName}")
        }
        val handler = FeedHandler(maxItems, excerptMaxChars)
        reader.contentHandler = handler
        reader.errorHandler = handler
        try {
            reader.parse(InputSource(ByteArrayInputStream(bytes)))
        } catch (e: FeedParseException) {
            throw e
        } catch (e: Exception) {
            if (!containsEnough(e) || handler.items.isEmpty()) {
                throw FeedParseException("лента не разобрана: ${e.javaClass.simpleName}")
            }
        }
        if (!handler.sawFeed) throw FeedParseException("это не RSS и не Atom")
        return handler.items
    }

    private fun rejectDangerousProlog(bytes: ByteArray) {
        val sample = String(bytes, Charsets.ISO_8859_1).take(8000).lowercase()
        if (sample.contains("<!doctype") || sample.contains("<!entity")) {
            throw FeedParseException("DOCTYPE и внешние сущности запрещены")
        }
    }

    private fun secureReader() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newSAXParser().xmlReader.apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
}

private class FeedHandler(
    private val maxItems: Int,
    private val excerptMaxChars: Int,
) : DefaultHandler() {
    val items = mutableListOf<ParsedEntry>()
    var sawFeed = false
    private var inEntry = false
    private var entryName = ""
    private val stack = ArrayDeque<String>()
    private val buf = StringBuilder()
    private var title = ""
    private var link = ""
    private var guid = ""
    private var date = ""
    private var summary = ""
    private var content = ""
    private var atomHref = ""
    private var captureKey = ""
    private var captureUntil = -1

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        val name = localName?.ifEmpty { null } ?: qName?.substringAfter(':').orEmpty()
        val key = name.lowercase()
        if (key == "rss" || key == "feed" || key == "channel") sawFeed = true
        if ((key == "item" || key == "entry") && !inEntry) {
            inEntry = true
            entryName = key
            title = ""
            link = ""
            guid = ""
            date = ""
            summary = ""
            content = ""
            atomHref = ""
            captureKey = ""
            captureUntil = -1
        }
        if (inEntry && key == "link") {
            val href = attributes?.getValue("href").orEmpty()
            val rel = attributes?.getValue("rel").orEmpty()
            if (href.isNotBlank() && (rel.isEmpty() || rel == "alternate") && atomHref.isEmpty()) {
                atomHref = href
            }
        }
        if (inEntry && captureUntil < 0 && key in CAPTURE) {
            captureKey = key
            captureUntil = stack.size
            buf.clear()
        }
        stack.addLast(key)
    }

    override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (!inEntry || ch == null || captureUntil < 0) return
        if (buf.length < 200_000) buf.append(ch, start, length.coerceAtMost(200_000 - buf.length))
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = stack.removeLastOrNull() ?: return
        if (captureUntil >= 0 && stack.size == captureUntil) {
            val text = buf.toString().trim()
            buf.clear()
            when (captureKey) {
                "title" -> if (title.isEmpty()) title = text
                "link" -> if (link.isEmpty() && text.startsWith("http")) link = text
                "guid", "id" -> if (guid.isEmpty()) guid = text
                "pubdate", "published", "updated", "date" -> if (date.isEmpty()) date = text
                "description", "summary" -> if (summary.isEmpty()) summary = text
                "encoded", "content" -> if (content.isEmpty()) content = text
            }
            captureKey = ""
            captureUntil = -1
        }
        if (inEntry && name == entryName) {
            emit()
            inEntry = false
            if (items.size >= maxItems) throw EnoughItems()
        }
    }

    private fun emit() {
        if (items.size >= maxItems) return
        val url = when {
            link.startsWith("http") -> link
            atomHref.startsWith("http") -> atomHref
            guid.startsWith("http") -> guid
            else -> ""
        }
        if (title.isBlank() || url.isBlank()) return
        val summaryPlain = HtmlText.toPlain(summary, excerptMaxChars)
        val contentPlain = HtmlText.toPlain(content, excerptMaxChars)
        val chosen = when {
            summaryPlain.text.length >= 40 -> summaryPlain to if (entryName == "entry") "feed_summary" else "feed_description"
            contentPlain.text.isNotBlank() -> contentPlain to "feed_content_truncated"
            summaryPlain.text.isNotBlank() -> summaryPlain to if (entryName == "entry") "feed_summary" else "feed_description"
            else -> PlainEmpty to "feed_description"
        }
        items += ParsedEntry(
            title = HtmlText.toPlain(title, 300).text,
            url = url,
            guid = guid.ifBlank { null },
            publishedRaw = date.ifBlank { null },
            publishedAt = RssDates.parse(date),
            excerpt = chosen.first.text,
            excerptTruncated = chosen.first.truncated,
            excerptOrigin = chosen.second,
        )
    }

    override fun warning(e: org.xml.sax.SAXParseException?) = Unit
    override fun error(e: org.xml.sax.SAXParseException?) {
        throw FeedParseException("ошибка XML")
    }

    override fun fatalError(e: org.xml.sax.SAXParseException?) {
        throw FeedParseException("ошибка XML")
    }
}

private val PlainEmpty = com.urmindkilla.svodkalocal.core.text.PlainText("", false)
private val CAPTURE = setOf(
    "title", "link", "guid", "id", "pubdate", "published", "updated", "date",
    "description", "summary", "encoded", "content",
)
