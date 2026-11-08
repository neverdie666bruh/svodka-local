package com.urmindkilla.svodkalocal.core.classify

import com.urmindkilla.svodkalocal.core.json.CategoryRule
import com.urmindkilla.svodkalocal.core.json.RuleFile
import com.urmindkilla.svodkalocal.core.model.Category
import com.urmindkilla.svodkalocal.core.model.FeedSource
import com.urmindkilla.svodkalocal.core.model.MatchInfo
import kotlin.math.min
import kotlin.math.round

data class Classified(
    val category: Category,
    val match: MatchInfo,
)

class Classifier(val rules: RuleFile) {
    private val categories = Category.entries.associateWith { category ->
        rules.categories[category.name] ?: error("в правилах нет категории ${category.name}")
    }

    fun hardExcluded(title: String, excerpt: String): String? {
        val found = hits("$title $excerpt", rules.hardExclude)
        return found.firstOrNull()
    }

    fun classify(title: String, excerpt: String, source: FeedSource): Classified? {
        var best: Classified? = null
        for (category in Category.entries) {
            val rule = categories.getValue(category)
            val titleHits = hits(title, rule.positive)
            val excerptHits = hits(excerpt, rule.positive).filter { it !in titleHits }
            val positiveCount = titleHits.size + excerptHits.size
            if (titleHits.size < rule.minTitleHits) continue
            if (positiveCount < rule.minPositiveHits) continue
            val prior = if (category in source.groups) 0.34 else 0.0
            var score = prior + min(0.66, titleHits.size * 0.22)
            if (rule.firstExcerptAsTitle && titleHits.isEmpty() && excerptHits.isNotEmpty()) {
                score += 0.22
                if (excerptHits.size > 1) score += min(0.16, (excerptHits.size - 1) * 0.08)
            } else {
                score += min(0.16, excerptHits.size * 0.08)
            }
            score = round(score * 100.0) / 100.0
            if (score < rule.minScore) continue
            val negatives = hits("$title $excerpt", rule.negative)
            if (negatives.isNotEmpty()) continue
            val labels = mutableListOf("детерминированное совпадение, не смысловой разбор")
            if (category in source.groups) labels += "группа источника: ${category.name}"
            if (titleHits.isNotEmpty()) labels += "ключевые слова в заголовке: ${titleHits.joinToString(", ")}"
            if (excerptHits.isNotEmpty()) labels += "ключевые слова во фрагменте: ${excerptHits.joinToString(", ")}"
            labels += "счёт ${formatScore(score)} при пороге ${formatScore(rule.minScore)}"
            val candidate = Classified(
                category = category,
                match = MatchInfo(
                    confidence = score,
                    labels = labels,
                    positiveTerms = (titleHits + excerptHits).distinct().sorted(),
                ),
            )
            val current = best
            if (current == null || candidate.match.confidence > current.match.confidence ||
                (candidate.match.confidence == current.match.confidence && candidate.category.ordinal < current.category.ordinal)
            ) {
                best = candidate
            }
        }
        val winner = best ?: return null
        if (winner.category.name !in rules.filmExempt && hits("$title $excerpt", rules.filmTerms).isNotEmpty()) {
            return null
        }
        return winner
    }

    private fun hits(text: String, terms: List<String>): List<String> {
        if (terms.isEmpty() || text.isBlank()) return emptyList()
        val norm = normalize(text)
        val found = mutableListOf<String>()
        for (term in terms) {
            val needle = normalize(term)
            if (needle.isEmpty()) continue
            val matched = if (needle.length <= 4 && !needle.contains(' ')) {
                Regex("(^| )${Regex.escape(needle)}( |$)").containsMatchIn(norm)
            } else {
                Regex("(^| )${Regex.escape(needle)}").containsMatchIn(norm)
            }
            if (matched) found += term
        }
        return found
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace('ё', 'е')
            .map { ch -> if (ch.isLetterOrDigit() || ch == '+' || ch == '#') ch else ' ' }
            .joinToString("")
            .replace(Regex(" +"), " ")
            .trim()

    private fun formatScore(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
}
