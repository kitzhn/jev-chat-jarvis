package com.jev.probe.jev

import com.jev.probe.core.ConversationScene
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

    fun rerank(
        ranked: List<RankedReply>,
        contact: Contact?,
        scene: ConversationScene = ConversationScene.UNKNOWN
    ): List<RankedReply> {
        if (ranked.isEmpty()) return ranked
        val base = if (ranked.any { it.judgeProb > 0.0 }) ranked else
            ranked.map { it.copy(judgeProb = 1.0 / ranked.size, prob = 1.0 / ranked.size) }

        data class Weighted(
            val reply: RankedReply,
            val relation: Double,
            val habit: Double,
            val sceneWeight: Double,
            val raw: Double
        )

        val weighted = base.map { r ->
            val relation = weight(r.strategy, contact)
            val habit = habitWeight(r.strategy, contact)
            val sceneWeight = sceneWeight(r.strategy, scene)
            val raw = max(r.judgeProb, 0.001) * relation * habit * sceneWeight
            Weighted(r, relation, habit, sceneWeight, raw)
        }
        val sum = weighted.sumOf { it.raw }.takeIf { it > 0.0 } ?: 1.0
        return weighted.map { w ->
            w.reply.copy(
                prob = w.raw / sum,
                relationWeight = w.relation,
                habitWeight = w.habit,
                sceneWeight = w.sceneWeight,
                scene = scene
            )
        }.sortedByDescending { it.prob }
    }

    fun habitWeight(strategy: ReplyStrategy, contact: Contact?): Double {
        contact ?: return 1.0
        val counts = contact.strategySelections
        val total = counts.values.sum().coerceAtLeast(0)
        if (total <= 0) return 1.0

        val key = strategy.name.lowercase()
        val count = counts[key] ?: 0
        // Symmetric Dirichlet prior (2 taps each) keeps early samples gentle.
        val posterior = (count + 2.0) / (total + 8.0)
        val relative = posterior / 0.25
        val confidence = total.toDouble() / (total + 12.0)
        return (1.0 + confidence * (relative - 1.0) * 0.30).coerceIn(0.75, 1.30)
    }

    fun sceneWeight(strategy: ReplyStrategy, scene: ConversationScene): Double = when (scene) {
        ConversationScene.CASUAL -> when (strategy) {
            ReplyStrategy.PLAYFUL -> 1.15
            ReplyStrategy.WARM -> 1.05
            ReplyStrategy.PROACTIVE -> 1.02
            ReplyStrategy.STEADY -> 0.95
            else -> 1.0
        }
        ConversationScene.COMFORT -> when (strategy) {
            ReplyStrategy.WARM -> 1.25
            ReplyStrategy.STEADY -> 1.08
            ReplyStrategy.PLAYFUL -> 0.75
            ReplyStrategy.PROACTIVE -> 0.85
            else -> 1.0
        }
        ConversationScene.CONFLICT -> when (strategy) {
            ReplyStrategy.STEADY -> 1.25
            ReplyStrategy.WARM -> 1.15
            ReplyStrategy.PLAYFUL -> 0.70
            ReplyStrategy.PROACTIVE -> 0.75
            else -> 1.0
        }
        ConversationScene.WORK -> when (strategy) {
            ReplyStrategy.STEADY -> 1.20
            ReplyStrategy.PROACTIVE -> 1.10
            ReplyStrategy.WARM -> 0.90
            ReplyStrategy.PLAYFUL -> 0.85
            else -> 1.0
        }
        ConversationScene.MEETUP -> when (strategy) {
            ReplyStrategy.PROACTIVE -> 1.25
            ReplyStrategy.PLAYFUL -> 1.05
            else -> 1.0
        }
        ConversationScene.APOLOGY -> when (strategy) {
            ReplyStrategy.WARM -> 1.20
            ReplyStrategy.STEADY -> 1.15
            ReplyStrategy.PROACTIVE -> 0.90
            ReplyStrategy.PLAYFUL -> 0.65
            else -> 1.0
        }
        ConversationScene.UNKNOWN -> 1.0
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
