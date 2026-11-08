package com.urmindkilla.svodkalocal.core.feedback

import com.urmindkilla.svodkalocal.core.model.EditionItem
import com.urmindkilla.svodkalocal.core.model.FeedbackDb
import com.urmindkilla.svodkalocal.core.text.Tokens
import kotlin.math.round

object LocalRanker {
    fun rank(items: List<EditionItem>, feedback: FeedbackDb): List<EditionItem> {
        if (items.isEmpty() || feedback.votes.isEmpty()) return items
        return items.sortedWith(
            compareByDescending<EditionItem> { adjusted(it, feedback) }
                .thenByDescending { it.match.confidence }
                .thenByDescending { it.publishedAt }
                .thenBy { it.url },
        )
    }

    fun adjusted(item: EditionItem, feedback: FeedbackDb): Double {
        val categoryVotes = feedback.votes.filter { it.category == item.category }
        val likes = categoryVotes.count { it.value == "like" }
        val dislikes = categoryVotes.count { it.value == "dislike" }
        val categoryDelta = ((likes - dislikes) * 0.03).coerceIn(-0.12, 0.15)
        val tokens = Tokens.titleTokens(item.title).toSet()
        var tokenDelta = 0.0
        for (vote in feedback.votes) {
            val overlap = vote.tokens.count { it in tokens }
            if (overlap == 0) continue
            tokenDelta += if (vote.value == "like") 0.04 else -0.04
        }
        tokenDelta = tokenDelta.coerceIn(-0.12, 0.12)
        return round((item.match.confidence + categoryDelta + tokenDelta) * 100.0) / 100.0
    }

    fun hidesNothing(before: List<EditionItem>, after: List<EditionItem>): Boolean =
        before.map { it.id }.toSet() == after.map { it.id }.toSet()
}
