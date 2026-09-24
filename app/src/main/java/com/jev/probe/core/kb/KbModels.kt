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
    val label: String = ""
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
data class LogEntry(val side: String, val text: String, val ts: Long, val app: String)

/**
 * What one analysis gets to see beyond the on-screen messages: who the other
 * party is, older history for them, and the knowledge notes that matched.
 */
data class ChatContext(
    val contact: Contact?,
    val history: List<LogEntry>,
    val notes: List<Note>,
    /** Human-readable direct edges involving this contact, already name-resolved. */
    val relationshipGraph: List<String> = emptyList()
) {

    /** True when there is nothing extra to inject (then no field is sent at all). */
    fun isEmpty(): Boolean =
        history.isEmpty() && notes.isEmpty() && contact == null && relationshipGraph.isEmpty()

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
        contact?.let { c ->
            sb.append("关系模型说明：以下分数和画像由用户手动维护，仅作回复背景，不代表对方真实心理。").append('\n')
            val rel = c.relationship.trim()
            if (rel.isNotEmpty()) sb.append("关系：").append(rel).append('\n')
            if (c.relationshipStage.isNotBlank()) sb.append("关系阶段：")
                .append(c.relationshipStage.trim()).append('\n')
            sb.append("好感度：").append(AffectionScale.clamp(c.affection))
                .append("/100（").append(AffectionScale.label(c.affection)).append("）").append('\n')
            sb.append("信任度：").append(AffectionScale.clamp(c.trust)).append("/100").append('\n')
            sb.append("亲密度：").append(AffectionScale.clamp(c.closeness)).append("/100").append('\n')
            if (c.identities.isNotEmpty()) sb.append("已关联平台身份：")
                .append(c.identities.joinToString("；") { identity ->
                    val appName = when (identity.app) {
                        "com.tencent.mobileqq" -> "QQ"
                        "com.ss.android.lark" -> "飞书"
                        "com.twitter.android" -> "X"
                        else -> identity.label.ifBlank { identity.app }
                    }
                    "${appName}:${identity.title}"
                }).append('\n')
            if (c.profileTags.isNotEmpty()) sb.append("画像标签：")
                .append(c.profileTags.joinToString("、")).append('\n')
            if (c.traits.isNotBlank()) sb.append("性格/特征：")
                .append(c.traits.trim()).append('\n')
            if (c.communicationStyle.isNotBlank()) sb.append("沟通偏好：")
                .append(c.communicationStyle.trim()).append('\n')
            if (c.boundaries.isNotBlank()) sb.append("边界/忌讳：")
                .append(c.boundaries.trim()).append('\n')
            if (c.notes.isNotBlank()) sb.append("关于").append(c.name).append("：")
                .append(c.notes.trim()).append('\n')
            if (c.autoSummary.isNotBlank()) sb.append("过往摘要：")
                .append(c.autoSummary.trim()).append('\n')
        }
        if (relationshipGraph.isNotEmpty()) {
            sb.append("联系人关系网络（用户手动维护）：").append('\n')
            relationshipGraph.forEach { sb.append("- ").append(it).append('\n') }
        }
        notes.forEach { n ->
            sb.append(n.title.trim()).append(": ").append(n.content.trim()).append('\n')
        }
        return sb.toString().trim()
    }
}
