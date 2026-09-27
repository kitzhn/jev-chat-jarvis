package com.jev.probe.core

/** Identifies our reply clip before cleanup so a newer user clip is untouched. */
internal object ReplyClipboardPolicy {
    const val LABEL = "jev_reply"

    fun owns(label: CharSequence?, clipText: String?, expectedText: String): Boolean =
        label?.toString() == LABEL && clipText == expectedText
}
