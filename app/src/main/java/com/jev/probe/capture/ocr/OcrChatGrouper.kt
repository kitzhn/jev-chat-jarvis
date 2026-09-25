package com.jev.probe.capture.ocr

import com.jev.probe.core.Msg

/**
 * Geometry-based OCR grouping for chat screenshots.
 *
 * This is intentionally UI-agnostic: it never knows anything about WeChat's
 * internal view tree. It only receives OCR lines with screen-space bounds and
 * infers a likely sender from which screen edge each text block hugs.
 */
object OcrChatGrouper {

    /**
     * Group OCR lines into chat bubbles and infer "me"/"other" from horizontal
     * alignment. Centered/system text (timestamps, notices) is ignored.
     *
     * The heuristic is conservative: a line must be clearly closer to one edge
     * than the other. This reduces false sender assignments for long centered text.
     */
    fun groupBySide(lines: List<OcrLine>, screenWidth: Int): List<Msg> {
        if (screenWidth <= 0) return emptyList()

        data class Tagged(val line: OcrLine, val side: String)

        val tagged = lines
            .asSequence()
            .filter { it.text.isNotBlank() }
            .filterNot { PURE_TIME.matches(it.text.trim()) }
            .mapNotNull { line ->
                val left = line.bounds.left.coerceAtLeast(0)
                val rightGap = (screenWidth - line.bounds.right).coerceAtLeast(0)
                val nearLeft = left + 1
                val nearRight = rightGap + 1
                val side = when {
                    nearLeft * 1.28 < nearRight -> "other"
                    nearRight * 1.28 < nearLeft -> "me"
                    else -> null
                } ?: return@mapNotNull null
                Tagged(line, side)
            }
            .sortedBy { it.line.bounds.top }
            .toList()

        if (tagged.isEmpty()) return emptyList()

        val out = ArrayList<Msg>()
        var side = tagged.first().side
        val buf = StringBuilder()
        var prev: OcrLine? = null

        fun flush() {
            val text = buf.toString().trim()
            if (text.isNotEmpty()) out.add(Msg(side, text))
            buf.setLength(0)
        }

        for (t in tagged) {
            val p = prev
            val newBubble = if (p == null) false else {
                val gap = t.line.bounds.top - p.bounds.bottom
                val h = maxOf(p.bounds.height(), 1)
                t.side != side || gap > h * 1.35f
            }
            if (newBubble) {
                flush()
                side = t.side
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(t.line.text.trim())
            prev = t.line
        }
        flush()
        return out
    }

    private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
}
