package com.urmindkilla.svodkalocal.core.text

data class PlainText(val text: String, val truncated: Boolean)

object HtmlText {
    fun toPlain(input: String, maxChars: Int): PlainText {
        var s = input.replace("\u0000", "")
        s = SCRIPT.replace(s, " ")
        s = STYLE.replace(s, " ")
        s = TAG.replace(s, " ")
        s = decodeEntities(s)
        s = TAG.replace(s, " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.length <= maxChars) return PlainText(s, false)
        return PlainText(s.take(maxChars).trimEnd() + "…", true)
    }

    private fun decodeEntities(value: String): String {
        val named = value
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
        return ENTITY.replace(named) { match ->
            val body = match.groupValues[1]
            val code = if (body.startsWith("#x", ignoreCase = true)) {
                body.drop(2).toIntOrNull(16)
            } else if (body.startsWith("#")) {
                body.drop(1).toIntOrNull()
            } else {
                null
            }
            if (code == null || code == 0 || code > 0x10FFFF) "" else code.toChar().toString()
        }
    }

    private val SCRIPT = Regex("(?is)<script[^>]*>.*?</script>")
    private val STYLE = Regex("(?is)<style[^>]*>.*?</style>")
    private val TAG = Regex("(?is)<[^>]+>")
    private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
}

object Tokens {
    private val stop = setOf(
        "и", "в", "во", "не", "на", "с", "со", "о", "об", "от", "до", "по", "за", "из", "у", "к", "ко",
        "а", "но", "или", "как", "что", "это", "для", "при", "над", "под", "без", "же", "ли", "бы",
        "то", "все", "уже", "еще", "его", "ее", "их", "он", "она", "они", "мы", "вы", "я", "быть",
        "был", "была", "были", "будет", "после", "между", "также", "только", "вот", "там", "тут",
        "когда", "где", "кто", "чем", "либо", "ни", "да", "нет", "the", "and", "for", "with",
    )

    fun titleTokens(title: String): List<String> {
        return title.lowercase()
            .replace('ё', 'е')
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in stop }
            .distinct()
            .take(24)
    }

    fun similar(left: List<String>, right: List<String>): Boolean {
        if (left.isEmpty() || right.isEmpty()) return false
        val a = left.toSet()
        val b = right.toSet()
        val inter = a.intersect(b).size
        val union = a.union(b).size
        if (union == 0) return false
        if (a == b && a.size >= 3) return true
        val jaccard = inter.toDouble() / union.toDouble()
        return inter >= 4 && jaccard >= 0.72
    }
}

object RussianText {
    fun enoughCyrillic(title: String): Boolean {
        val letters = title.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val cyr = letters.count { ch ->
            ch in 'а'..'я' || ch in 'А'..'Я' || ch == 'ё' || ch == 'Ё'
        }
        return cyr >= 8 && cyr.toDouble() / letters.length >= 0.35
    }
}
