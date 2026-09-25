package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.ReplyDraft
import com.jev.probe.core.ReplyStrategy
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.usage.ApiUsageStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * The generative route: any OpenAI-compatible `/chat/completions` endpoint.
 * Drafts 4 candidate replies for the GalGame selector, and (D stage) summarizes text. Reads
 * replyBaseUrl / replyKey / replyModel from [Prefs].
 */
class ReplyClient(private val prefs: Prefs) {

    /**
     * Exactly 4 varied candidate replies in Chinese.
     *
     * @param ctx D-stage knowledge context. When present its background and
     *        history are prepended to the prompt with an instruction to stay
     *        consistent with them and invent nothing beyond them.
     */
    fun draft(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): List<ReplyDraft> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            val who = if (it.side == "me") "我" else (it.speaker?.takeIf { s -> s.isNotBlank() } ?: "对方")
            who + "：" + it.text
        }
        val sys = "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 4 条候选回复文本，" +
            "四条要明显对应不同策略：1) 温柔承接；2) 轻松自然；3) 稳妥克制；4) 主动推进。"+
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。四条不要只是同义改写。不要解释，直接输出 JSON 数组。"
        val user = knowledgeBlock(relationship, ctx) +
            "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 4 条不同策略的候选回复。"
        return parseFour(chat(sys, user, temperature = 0.85)).mapIndexed { index, text ->
            ReplyDraft(
                text = text,
                strategy = listOf(
                    ReplyStrategy.WARM,
                    ReplyStrategy.PLAYFUL,
                    ReplyStrategy.STEADY,
                    ReplyStrategy.PROACTIVE
                ).getOrElse(index) { ReplyStrategy.UNKNOWN }
            )
        }
    }

    /** The background + history preamble; empty string when there is no context. */
    private fun knowledgeBlock(relationship: String, ctx: ChatContext?): String {
        ctx ?: return ""
        val background = ctx.background(relationship)
        val history = ctx.history
        if (background.isBlank() && history.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("以下是关于我和对方的背景与知识库，回复必须与之一致，")
            .append("可以直接引用其中事实，不要编造知识库里没有的事实。\n")
        if (background.isNotBlank()) sb.append(background).append('\n')
        if (history.isNotEmpty()) {
            sb.append("\n更早的聊天记录（越靠下越新）：\n")
            history.takeLast(prefs.contextHistoryCount.coerceIn(0, 100)).forEach {
                val who = if (it.side == "me") "我" else (it.speaker?.takeIf { s -> s.isNotBlank() } ?: "对方")
                sb.append(who).append("：").append(it.text).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * One plain chat round trip for the settings connectivity test. Deliberately
     * NOT [summarize]: the test should exercise the ordinary path, not whatever
     * the summary prompt happens to be.
     */
    fun ping(): String =
        chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到", temperature = 0.0).trim()

    /** Condense a block of text (used by the D-stage contact auto-summary). */
    fun summarize(text: String): String {
        if (text.isBlank()) return ""
        val sys = "你是中文摘要助手。把给到的聊天记录压缩成不超过 120 字的第三人称要点摘要，" +
            "只保留事实、偏好、承诺和待办，不要评论，不要编造。直接输出摘要正文。"
        return chat(sys, text, temperature = 0.2).trim()
    }

    /** One chat-completions round trip; returns the assistant message content. */
    private fun chat(system: String, user: String, temperature: Double): String {
        val url = prefs.replyEndpoint()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.replyModel)
            .put("messages", messages)
            .put("temperature", temperature)
        if (url.contains("openrouter.ai", ignoreCase = true)) {
            body.put("usage", JSONObject().put("include", true))
        }
        val resp = HttpJson.post(url, prefs.effectiveReplyKey(), body, Route.REPLY, HttpJson.headersFor(url))
        val content = resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
        ApiUsageStore.record(
            prefs.appContext, Route.REPLY, prefs.replyBaseUrl, prefs.replyModel,
            body.toString(), resp, content
        )
        return content
    }

    private fun parseFour(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 4) return out.take(4)
                while (out.size < 4) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) { }
        }
        // Fallback: split lines.
        val lines = content.split("\n").map {
            it.trim().trimStart('-', '*', '1', '2', '3', '4', '.', ' ', '"')
        }.filter { it.isNotBlank() }
        val out = lines.take(4).toMutableList()
        while (out.size < 4) out.add("（稍等，我看下）")
        return out
    }
}
