package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.capture.WeChatNotificationGate
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.ReplyStrategy
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.ConversationSceneDetector
import com.jev.probe.jev.RelationshipStrategyPolicy

/** Debug-only 2.3 integration proof. Never packaged in release builds. */
class AdaptiveModelDemoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)
        val snapshot = ChatSnapshot(
            title = "演示旅行群(5)",
            messages = listOf(
                Msg("other", "周末有空一起吃饭吗？", "A同学"),
                Msg("me", "有空呀"),
                Msg("other", "那周六晚上见？", "A同学")
            ),
            conversationKind = "group"
        )
        val ctx = ContextBuilder.build(this, snapshot, "com.tencent.mobileqq", prefs)
        val scene = ConversationSceneDetector.detect(snapshot)
        val contact = ctx.contact
        val habitCount = contact?.strategySelections?.get("playful") ?: 0

        val equal = listOf(
            RankedReply("温柔", .25, ReplyStrategy.WARM, .25),
            RankedReply("轻松", .25, ReplyStrategy.PLAYFUL, .25),
            RankedReply("稳妥", .25, ReplyStrategy.STEADY, .25),
            RankedReply("主动", .25, ReplyStrategy.PROACTIVE, .25)
        )
        val reranked = RelationshipStrategyPolicy.rerank(equal, contact, scene)
        val top = reranked.firstOrNull()

        val sameAllowed = WeChatNotificationGate.matches(
            "演示旅行群（5）", "演示旅行群(5)"
        )
        val otherBlocked = !WeChatNotificationGate.matches(
            "另一个联系人", "演示旅行群(5)"
        )
        val enabledListeners = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        val listenerEnabled = enabledListeners.contains(
            "com.jev.probe.capture.WeChatNotificationListener"
        )

        val checks = listOf(
            "群会话" to (ctx.groupContact?.name == "演示旅行群"),
            "当前发言人" to (ctx.speakerContact?.name == "演示联系人 A"),
            "场景识别" to (scene.label == "约见"),
            "策略学习落盘" to (habitCount >= 21),
            "同会话通知放行" to sameAllowed,
            "异会话通知拦截" to otherBlocked,
            "通知监听权限" to listenerEnabled,
            "四策略重排" to (top?.strategy == ReplyStrategy.PLAYFUL)
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 58, 40, 48)
            setBackgroundColor(Color.parseColor("#071226"))
        }
        root.addView(label("JEV ULTIMATE 2.3 · ADAPTIVE CORE", 20f, "#7EE7FF", true))
        root.addView(label(
            "群聊人物模型 × 用户策略学习 × 对话场景 × 微信通知触发",
            12f, "#A8C5DF", false
        ))

        addSection(root, "GROUP MODEL", listOf(
            "群：" + (ctx.groupContact?.name ?: "未匹配"),
            "当前发言人：" + (ctx.speakerName ?: "未知"),
            "关联人物：" + (ctx.speakerContact?.name ?: "未关联")
        ))
        addSection(root, "ADAPTIVE STRATEGY", listOf(
            "场景：" + scene.label,
            "轻松策略历史选择：" + habitCount + " 次",
            "当前推荐：" + (top?.strategy?.name ?: "NONE"),
            "关系×习惯×场景权重：" +
                reranked.joinToString("  ") {
                    it.strategy.name.take(3) + "=" + ((it.prob * 100).toInt()) + "%"
                }
        ))
        addSection(root, "WECHAT NOTIFICATION GATE", listOf(
            "当前群 + 同群通知： " + if (sameAllowed) "ALLOW" else "BLOCK",
            "当前群 + 其他人通知： " + if (otherBlocked) "BLOCK ✓" else "ALLOW ✗",
            "NotificationListener： " + if (listenerEnabled) "ENABLED" else "NOT GRANTED"
        ))

        checks.forEach { (name, pass) ->
            root.addView(label(
                (if (pass) "✓ " else "✗ ") + name,
                14f,
                if (pass) "#34D399" else "#F87171",
                true
            ))
        }
        val allPass = checks.all { it.second }
        root.addView(label(
            if (allPass) "ALL CORE CHECKS PASS" else "CORE CHECK FAILED",
            17f,
            if (allPass) "#34D399" else "#F87171",
            true
        ).apply { setPadding(0, 28, 0, 0) })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun addSection(root: LinearLayout, title: String, lines: List<String>) {
        root.addView(label(title, 13f, "#60A5FA", true).apply { setPadding(0, 28, 0, 6) })
        lines.forEach { root.addView(label(it, 13f, "#E5EEF8", false)) }
    }

    private fun label(textValue: String, size: Float, color: String, bold: Boolean): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 4, 0, 4)
        }
}
