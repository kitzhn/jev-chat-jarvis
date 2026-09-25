package com.jev.probe.core

import android.graphics.Rect

/** One captured chat bubble. side is "me" (right) or "other" (left). */
data class Msg(
    val side: String,
    val text: String,
    /** Optional real speaker label for group chats; null when the app cannot expose it safely. */
    val speaker: String? = null
)

/**
 * A bubble the node tree can locate but not read (Feishu draws its message text
 * itself). [rect] is in screen coordinates; [side] is what the tree could infer
 * around the bubble. The service OCRs each rect to get the words.
 */
data class BubbleRect(val rect: Rect, val side: String)

/**
 * A snapshot of the currently-open conversation in whichever chat app is
 * foreground (see ChatAppAdapter).
 *
 * Adapter contract: `extract` returning null means "not in a chat window".
 * Returning a snapshot whose [messages] is empty means "in a chat window, but
 * the tree holds no text" — that is the OCR fallback's cue, and the one case
 * where [bubbleRects] may be populated.
 *
 * [note] is a caveat about how this snapshot was produced, shown verbatim in
 * the analysis panel (OCR captures cannot tell who said what).
 */
data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>,
    val bubbleRects: List<BubbleRect> = emptyList(),
    val note: String? = null,
    /** "direct" | "group"; adapters may leave it direct when group state is unknown. */
    val conversationKind: String = "direct"
) {
    val latestFrom: String? get() = messages.lastOrNull()?.side

    /** A stable signature of the last few messages, to detect real changes. */
    fun signature(): String =
        messages.takeLast(6).joinToString("|") { "${it.side}:${it.text}" }
}

/** Jev's judgment result for one snapshot, plus the ranked candidate replies. */
data class Analysis(
    val trueIntent: Choice?,
    val dangerLevel: Score?,
    val sheNeeds: Choice?,
    val shouldReplyNow: Double?,
    val bestAction: Choice?,
    val tensionResolved: Double?,
    val literalQuestion: Double?,
    val rankedReplies: List<RankedReply>,
    val latencyMs: Long,
    val error: String? = null
)

data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>)
data class Score(val score: Double, val confidence: Double, val maxLevel: Int)

enum class ReplyStrategy {
    WARM,
    PLAYFUL,
    STEADY,
    PROACTIVE,
    UNKNOWN
}

enum class ConversationScene(val label: String) {
    CASUAL("闲聊"),
    COMFORT("安慰"),
    CONFLICT("冲突"),
    WORK("工作"),
    MEETUP("约见"),
    APOLOGY("道歉"),
    UNKNOWN("未识别")
}

data class ReplyDraft(
    val text: String,
    val strategy: ReplyStrategy
)

data class RankedReply(
    val text: String,
    /** Final normalized score after Jev ranking + relationship prior. */
    val prob: Double,
    val strategy: ReplyStrategy = ReplyStrategy.UNKNOWN,
    /** Raw probability returned by the Jev ranking route. */
    val judgeProb: Double = prob,
    /** Relationship/profile multiplier applied before final normalization. */
    val relationWeight: Double = 1.0,
    /** Learned multiplier from this user's actual past taps for this contact. */
    val habitWeight: Double = 1.0,
    /** Local scene multiplier, e.g. conflict / comfort / meetup. */
    val sceneWeight: Double = 1.0,
    val scene: ConversationScene = ConversationScene.UNKNOWN
)
