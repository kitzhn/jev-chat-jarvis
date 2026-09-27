package com.jev.probe.core

import java.text.Normalizer
import java.util.Locale

/** Conversation allowlist policy shared by capture, OCR, and the reply-fill guard. */
internal object ConversationWhitelistGate {
    fun allows(title: String?, entries: Set<String>): Boolean {
        val normalizedTitle = normalize(title.orEmpty())
        if (normalizedTitle.isEmpty()) return false
        return entries.asSequence()
            .map(::normalize)
            .filter { it.isNotEmpty() && it.replace("*", "").isNotEmpty() }
            .any { entry ->
                if ('*' !in entry) normalizedTitle == entry
                else {
                    val pattern = buildString {
                        var segmentStart = 0
                        entry.forEachIndexed { index, char ->
                            if (char == '*') {
                                append(Regex.escape(entry.substring(segmentStart, index)))
                                append(".*")
                                segmentStart = index + 1
                            }
                        }
                        append(Regex.escape(entry.substring(segmentStart)))
                    }
                    Regex("^$pattern$", RegexOption.IGNORE_CASE).matches(normalizedTitle)
                }
            }
    }

    /** Ignore presentation-only group member counts while requiring the full title. */
    private fun normalize(value: String): String = Normalizer
        .normalize(value.trim(), Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*\\(\\s*\\d+\\s*\\)\\s*$"), "")
        .trim()
        .lowercase(Locale.ROOT)
}

/** The chat identity captured when the owner taps a reply candidate. */
internal data class CaptureTarget(
    val packageName: String,
    val title: String,
    val generation: Long
)

/** A queued fill is valid only while the exact authorized chat is still active. */
internal object CaptureTargetGate {
    fun isCurrent(
        expected: CaptureTarget,
        actual: CaptureTarget?,
        whitelist: Set<String>
    ): Boolean =
        actual != null &&
            expected.generation == actual.generation &&
            expected.packageName == actual.packageName &&
            expected.title.trim() == actual.title.trim() &&
            ConversationWhitelistGate.allows(actual.title, whitelist)
}
