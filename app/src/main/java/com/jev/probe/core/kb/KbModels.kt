package com.jev.probe.core.kb

/**
 * Local knowledge base data model (v1.3, D stage).
 *
 * Everything here lives only in the app-private `filesDir/kb` directory as plain
 * JSON — no database, no network, no export. The user can wipe all of it from
 * settings ("清空知识库与历史"), which deletes that directory and nothing else.
 */

/** One free-text knowledge note the user maintains by hand. */
data class Note(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    /** Always injected regardless of what the conversation is about. */
    val alwaysOn: Boolean = false,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

/** One app-specific identity that belongs to a real-world contact. */
data class PlatformIdentity(
    val app: String,
    val title: String,
    val label: String = "",
    /** Optional conversation scope for group-member identities. Blank = global/direct identity. */
    val scope: String = ""
)

/** One manual affinity change event. */
data class RelationshipEvent(
    val id: String,
    val ts: Long,
    val delta: Int,
    val fromScore: Int,
    val toScore: Int,
    val reason: String = "",
    val source: String = "manual"
)

/** One user-maintained edge between two contacts in the local relationship graph. */
data class ContactRelation(
    val id: String,
    val fromId: String,
    val toId: String,
    val type: String,
    val strength: Int = 50,
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * One person (or group) the user chats with. [identities] is the preferred
 * cross-app mapping: each entry binds an app package + conversation title to
 * this one real-world contact. [aliases]/[apps] remain for backward compatibility.
 */
data class Contact(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    /** Package names this contact has been seen in, retained for old data. */
    val apps: List<String> = emptyList(),
    /** Exact app-scoped identities for safe cross-app contact linking. */
    val identities: List<PlatformIdentity> = emptyList(),

    /** User-defined relationship type, e.g. friend / colleague / family. */
    val relationship: String = "",
    /** Optional current relationship state, e.g. newly acquainted / close / cooling down. */
    val relationshipStage: String = "",

    /**
     * User-maintained affinity score. This is deliberately NOT inferred from chat:
     * the owner decides what the number means and when it changes.
     */
    val affection: Int = 50,
    /** User-maintained trust and closeness dimensions for a richer relationship model. */
    val trust: Int = 50,
    val closeness: Int = 50,

    /** Structured profile fields. */
    val profileTags: List<String> = emptyList(),
    val traits: String = "",
    val communicationStyle: String = "",
    val boundaries: String = "",

    val notes: String = "",
    /** Counts of the user's actual GalGame taps, keyed by ReplyStrategy.name lowercase. */
    val strategySelections: Map<String, Int> = emptyMap(),
    /** Reserved for the (deferred) auto-summary; never written automatically here. */
    val autoSummary: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/** A neutral, game-like affinity scale that works for friends, family and colleagues too. */
data class AffectionTier(val min: Int, val max: Int, val label: String)

object AffectionScale {
    val tiers: List<AffectionTier> = listOf(
        AffectionTier(0, 19, "冷淡"),
        AffectionTier(20, 39, "疏远"),
        AffectionTier(40, 59, "普通"),
        AffectionTier(60, 79, "亲近"),
        AffectionTier(80, 94, "信赖"),
        AffectionTier(95, 100, "特别亲密")
    )

    fun clamp(value: Int): Int = value.coerceIn(0, 100)

    fun label(value: Int): String {
        val v = clamp(value)
        return tiers.firstOrNull { v in it.min..it.max }?.label ?: "普通"
    }
}

/** One remembered chat line. side is "me" / "other", matching [com.jev.probe.core.Msg]. */
data class LogEntry(
    val side: String,
    val text: String,
    val ts: Long,
    val app: String,
    val speaker: String? = null
)

/**
 * What one analysis gets to see beyond the on-screen messages: who the other
 * party is, older history for them, and the knowledge notes that matched.
 */
data class ChatContext(
    /** Primary person for reply adaptation; in a group this prefers the current speaker. */
    val contact: Contact?,
    val history: List<LogEntry>,
    val notes: List<Note>,
    /** Human-readable direct edges involving this contact, already name-resolved. */
    val relationshipGraph: List<String> = emptyList(),
    /** The group conversation contact, if the current chat is a group. */
    val groupContact: Contact? = null,
    /** The currently speaking person matched inside that group, when known. */
    val speakerContact: Contact? = null,
    val speakerName: String? = null
) {

    /** True when there is nothing extra to inject (then no field is sent at all). */
    fun isEmpty(): Boolean =
        history.isEmpty() && notes.isEmpty() && contact == null && relationshipGraph.isEmpty() &&
            groupContact == null && speakerContact == null

    /**
     * The `background` string injected into Jev's state and the reply prompt:
     * relationship + contact notes + auto-summary + each matched note as
     * "title: content". Blank when there is nothing to say — callers must then
     * omit the field entirely rather than send an empty one.
     *
     * @param defaultRelationship unused when the contact carries no relationship
     *        of its own — that global default already goes out separately as
     *        `chat.relationship`, so repeating it here would just duplicate it.
     *        A contact with no relationship set simply omits the "关系：" line.
     */
    fun background(defaultRelationship: String): String {
        val sb = StringBuilder()

        fun appendLine(raw: String) {
            val line = raw.trim()
            if (line.isEmpty() || sb.length >= MAX_BACKGROUND_CHARS) return
            val separator = if (sb.isEmpty()) 0 else 1
            val room = MAX_BACKGROUND_CHARS - sb.length - separator
            if (room <= 0) return
            if (separator == 1) sb.append('\n')
            sb.append(line.take(room))
        }

        if (groupContact != null) {
            appendLine("当前会话类型：群聊")
            appendLine("群聊：${groupContact.name}")
            speakerName?.takeIf { it.isNotBlank() }?.let {
                appendLine("当前发言人：$it")
            }
            speakerContact?.let { sp ->
                appendLine("当前发言人已关联人物：${sp.name}")
            }
        }
        contact?.let { c ->
            appendLine("关系模型说明：以下分数和画像由用户手动维护，仅作回复背景，不代表对方真实心理。")
            val rel = c.relationship.trim()
            if (rel.isNotEmpty()) appendLine("关系：$rel")
            if (c.relationshipStage.isNotBlank()) {
                appendLine("关系阶段：${c.relationshipStage.trim()}")
            }
            appendLine("好感度：${AffectionScale.clamp(c.affection)}/100（${AffectionScale.label(c.affection)}）")
            appendLine("信任度：${AffectionScale.clamp(c.trust)}/100")
            appendLine("亲密度：${AffectionScale.clamp(c.closeness)}/100")
            if (c.identities.isNotEmpty()) {
                val identities = c.identities.joinToString("；") { identity ->
                    val appName = when (identity.app) {
                        "com.tencent.mobileqq" -> "QQ"
                        "com.ss.android.lark" -> "飞书"
                        "com.twitter.android" -> "X"
                        else -> identity.label.ifBlank { identity.app }
                    }
                    val scope = identity.scope.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
                    "${appName}:${identity.title}${scope}"
                }
                appendLine("已关联平台身份：$identities")
            }
            if (c.profileTags.isNotEmpty()) appendLine("画像标签：${c.profileTags.joinToString("、")}")
            if (c.traits.isNotBlank()) appendLine("性格/特征：${c.traits.trim()}")
            if (c.communicationStyle.isNotBlank()) appendLine("沟通偏好：${c.communicationStyle.trim()}")
            if (c.boundaries.isNotBlank()) appendLine("边界/忌讳：${c.boundaries.trim()}")
            if (c.notes.isNotBlank()) appendLine("关于${c.name}：${c.notes.trim()}")
            if (c.autoSummary.isNotBlank()) appendLine("过往摘要：${c.autoSummary.trim()}")
        }
        if (relationshipGraph.isNotEmpty()) {
            appendLine("联系人关系网络（用户手动维护）：")
            relationshipGraph.forEach { appendLine("- $it") }
        }
        notes.forEach { n ->
            appendLine("${n.title.trim()}: ${n.content.trim()}")
        }
        return sb.toString()
    }

    companion object {
        /** Hard cap for profile/graph/note background sent to model providers. */
        const val MAX_BACKGROUND_CHARS = 2500
    }
}
}
