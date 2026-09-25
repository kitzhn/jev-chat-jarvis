package com.jev.probe.jev

import com.jev.probe.core.RankedReply
import com.jev.probe.core.ReplyStrategy
import com.jev.probe.core.kb.Contact
import kotlin.math.max

/**
 * Deterministic local prior over dialogue strategies.
 *
 * It never tries to infer the other person's real feelings. It only turns the
 * user's manually maintained contact profile into a small multiplier that is
 * combined with Jev's per-conversation ranking.
 */
object RelationshipStrategyPolicy {

    fun rerank(ranked: List<RankedReply>, contact: Contact?): List<RankedReply> {
        if (ranked.isEmpty()) return ranked
        val base = if (ranked.any { it.judgeProb > 0.0 }) ranked else
            ranked.map { it.copy(judgeProb = 1.0 / ranked.size, prob = 1.0 / ranked.size) }

        val weighted = base.map { r ->
            val w = weight(r.strategy, contact)
            val raw = max(r.judgeProb, 0.001) * w
            Triple(r, w, raw)
        }
        val sum = weighted.sumOf { it.third }.takeIf { it > 0.0 } ?: 1.0
        return weighted.map { (r, w, raw) ->
            r.copy(prob = raw / sum, relationWeight = w)
        }.sortedByDescending { it.prob }
    }

    fun weight(strategy: ReplyStrategy, contact: Contact?): Double {
        contact ?: return 1.0

        fun centered(v: Int): Double =
            ((v.coerceIn(0, 100) - 50) / 50.0).coerceIn(-1.0, 1.0)

        val affection = centered(contact.affection)
        val trust = centered(contact.trust)
        val closeness = centered(contact.closeness)
        val profile = (
            contact.communicationStyle + " " +
                contact.boundaries + " " +
                contact.relationshipStage + " " +
                contact.profileTags.joinToString(" ")
            ).lowercase()

        fun hasAny(vararg terms: String) = terms.any { profile.contains(it.lowercase()) }

        val result = when (strategy) {
            ReplyStrategy.WARM -> {
                val preference = if (hasAny("温柔", "体贴", "耐心", "倾听")) 0.10 else 0.0
                1.0 + 0.12 * affection + 0.10 * trust + 0.06 * closeness + preference
            }

            ReplyStrategy.PLAYFUL -> {
                val preference = if (hasAny("轻松", "开玩笑", "幽默", "调侃", "自然")) 0.12 else 0.0
                val boundaryPenalty =
                    if (hasAny("严肃", "不喜欢开玩笑", "别开玩笑", "不喜欢调侃", "正式")) 0.28 else 0.0
                1.0 + 0.20 * closeness + 0.10 * affection + preference - boundaryPenalty
            }

            ReplyStrategy.STEADY -> {
                val boundaryBonus = if (contact.boundaries.isNotBlank()) 0.10 else 0.0
                val preference =
                    if (hasAny("克制", "简洁", "明确", "先说结论", "冷静", "疏远", "冷却")) 0.14 else 0.0
                1.0 - 0.08 * closeness - 0.04 * affection + boundaryBonus + preference
            }

            ReplyStrategy.PROACTIVE -> {
                val preference = if (hasAny("主动", "直接", "推进", "明确安排")) 0.10 else 0.0
                val boundaryPenalty =
                    if (hasAny("不要催", "不喜欢被催", "连续追问", "需要空间", "压力", "别追问", "疏远", "冷却")) 0.32 else 0.0
                1.0 + 0.18 * trust + 0.18 * closeness + 0.06 * affection + preference - boundaryPenalty
            }

            ReplyStrategy.UNKNOWN -> 1.0
        }
        return result.coerceIn(0.55, 1.45)
    }
}
