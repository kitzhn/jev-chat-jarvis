package com.jev.probe.core

import java.util.Locale

/** Accessibility properties used to choose a chat composer without guessing. */
internal data class EditableCandidate(
    val index: Int,
    val viewId: String,
    val hint: String,
    val contentDescription: String,
    val className: String,
    val editable: Boolean,
    val focused: Boolean,
    /** Candidate center as a fraction of the chat window height, or null if unknown. */
    val verticalCenter: Float?
)

/** Conservative selector for reply input fields. Ambiguous screens fail closed. */
internal object ChatInputTargetSelector {
    fun select(packageName: String, candidates: List<EditableCandidate>): Int? {
        if (candidates.isEmpty()) return null

        val known = candidates.filter { isKnownComposer(packageName, it.viewId) }
        if (known.size == 1) return known.single().index
        if (known.size > 1) {
            return known.singleOrNull { it.focused }?.index
        }

        val scored = candidates.mapNotNull { candidate ->
            val metadata = listOf(candidate.viewId, candidate.hint, candidate.contentDescription)
                .joinToString(" ")
                .lowercase(Locale.ROOT)
            if (!candidate.editable || SEARCH_MARKERS.any { metadata.contains(it) }) return@mapNotNull null

            var score = if (candidate.className == EDIT_TEXT) 1 else 0
            score += if (candidate.editable) 2 else 0
            if (candidate.focused) score += 7
            if (COMPOSER_MARKERS.any { metadata.contains(it) }) score += 6
            score += when {
                candidate.verticalCenter == null -> 0
                candidate.verticalCenter >= 0.60f -> 4
                candidate.verticalCenter >= 0.48f -> 2
                else -> -8
            }
            candidate.index to score
        }
        val bestScore = scored.maxOfOrNull { it.second } ?: return null
        if (bestScore < MIN_SCORE) return null
        return scored.filter { it.second == bestScore }.singleOrNull()?.first
    }

    private fun isKnownComposer(packageName: String, viewId: String): Boolean = when (packageName) {
        QQ_PACKAGE -> viewId == "$QQ_PACKAGE:id/input"
        FEISHU_PACKAGE -> viewId.endsWith(":id/kb_rich_text_content")
        else -> false
    }

    private const val MIN_SCORE = 5
    private const val EDIT_TEXT = "android.widget.EditText"
    private const val QQ_PACKAGE = "com.tencent.mobileqq"
    private const val FEISHU_PACKAGE = "com.ss.android.lark"
    private val SEARCH_MARKERS = listOf("search", "搜索", "查找", "filter")
    private val COMPOSER_MARKERS = listOf(
        "message", "reply", "composer", "chat_input", "输入", "消息", "回复", "发送"
    )
}
