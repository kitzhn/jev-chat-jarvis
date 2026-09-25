package com.jev.probe.core

/** Conversation allowlist policy shared by capture, OCR, and the reply-fill guard. */
internal object ConversationWhitelistGate {
    fun allows(title: String?, entries: Set<String>): Boolean {
        val normalizedTitle = title?.trim().orEmpty()
        if (normalizedTitle.isEmpty()) return false
        val keywords = entries.asSequence().map(String::trim).filter(String::isNotEmpty)
        return keywords.any { normalizedTitle.contains(it, ignoreCase = true) }
    }
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
