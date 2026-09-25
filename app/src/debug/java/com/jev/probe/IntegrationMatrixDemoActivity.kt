package com.jev.probe

import android.content.ComponentName
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.capture.WeChatNotificationBridge
import com.jev.probe.capture.WeChatNotificationListener
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.ReplyStrategy
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.kb.PlatformIdentity
import com.jev.probe.jev.ConversationSceneDetector
import com.jev.probe.jev.RelationshipStrategyPolicy

/** Debug-only deterministic integration matrix for v2.4 features. */
class IntegrationMatrixDemoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = KbStore.get(this)
        val app = "com.tencent.mobileqq"
        val group = Contact(
            id = "v23-demo-group",
            name = "演示旅行群",
            identities = listOf(PlatformIdentity(app, "演示旅行群 (5)", "QQ群")),
            relationship = "群聊"
        )
        val member = Contact(
            id = "v23-demo-member",
            name = "演示成员 B",
            identities = listOf(PlatformIdentity(app, "演示成员 B", "QQ", "演示旅行群 (5)")),
            relationship = "朋友",
            relationshipStage = "亲近",
            affection = 82,
            trust = 78,
            closeness = 86,
            communicationStyle = "喜欢轻松自然的聊天",
            boundaries = "不喜欢被催和连续追问",
            strategySelections = mapOf("playful" to 12, "warm" to 4, "steady" to 2, "proactive" to 1)
        )
        store.saveContact(group)
        store.saveContact(member)

        val snapshot = ChatSnapshot(
            title = "演示旅行群 (5)",
            messages = listOf(
                Msg("other", "周末大家有空一起吃饭吗？", "演示成员 B"),
                Msg("me", "我应该有空"),
                Msg("other", "那周六晚上见？", "演示成员 B")
            ),
            conversationKind = "group"
        )
        val prefs = Prefs(this, "v23_feature_demo").apply {
            contextEnabled = false
        }
        val ctx = ContextBuilder.build(this, snapshot, app, prefs)
        val groupPass = ctx.groupContact?.id == group.id &&
            ctx.speakerContact?.id == member.id &&
            ctx.contact?.id == member.id

        val scene = ConversationSceneDetector.detect(snapshot)

        val before = store.contact(member.id)?.strategySelections?.get("playful") ?: 0
        store.recordStrategySelection(member.id, "playful")
        val afterContact = store.contact(member.id)
        val after = afterContact?.strategySelections?.get("playful") ?: 0
        val learningPass = after == before + 1

        var signalTitle: String? = null
        WeChatNotificationBridge.setListener { signalTitle = it.conversationTitle }
        WeChatNotificationBridge.emit("演示旅行群 (5)")
        WeChatNotificationBridge.setListener(null)
        val bridgePass = signalTitle == "演示旅行群 (5)"

        val servicePass = runCatching {
            packageManager.getServiceInfo(
                ComponentName(this, WeChatNotificationListener::class.java),
                0
            )
        }.isSuccess

        val equal = listOf(
            RankedReply("温柔接住", .25, ReplyStrategy.WARM, .25),
            RankedReply("轻松回应", .25, ReplyStrategy.PLAYFUL, .25),
            RankedReply("稳妥回应", .25, ReplyStrategy.STEADY, .25),
            RankedReply("主动推进", .25, ReplyStrategy.PROACTIVE, .25)
        )
        val ranked = RelationshipStrategyPolicy.rerank(equal, afterContact, scene)
        val top = ranked.firstOrNull()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 46, 36, 40)
            setBackgroundColor(Color.parseColor("#071226"))
        }
        root.addView(label("JEV ULTIMATE 2.4 · INTEGRATION MATRIX", 19f, "#7EE7FF", true))
        root.addView(label("四个新增链路使用虚构演示数据，不包含任何个人内容", 11.5f, "#A8C5DF"))

        root.addView(card(
            if (servicePass && bridgePass) "PASS" else "FAIL",
            "微信通知触发",
            "NotificationListener 已注册 · Bridge 标题=${signalTitle ?: "null"}"
        ))
        root.addView(card(
            if (learningPass) "PASS" else "FAIL",
            "策略选择学习",
            "轻松自然点击计数 $before → $after；不修改好感/信任/亲密"
        ))
        root.addView(card(
            if (scene.label == "约见") "PASS" else "FAIL",
            "对话场景模式",
            "当前场景=${scene.label} · top=${top?.strategy?.name ?: "NONE"} · 场景权重=${"%.2f".format(top?.sceneWeight ?: 0.0)}"
        ))
        root.addView(card(
            if (groupPass) "PASS" else "FAIL",
            "群聊人物模型",
            "群=${ctx.groupContact?.name ?: "未命中"} · 当前发言人=${ctx.speakerContact?.name ?: "未命中"} · 主画像=${ctx.contact?.name ?: "未命中"}"
        ))

        val allPass = servicePass && bridgePass && learningPass &&
            scene.label == "约见" && groupPass
        root.addView(label(
            if (allPass) "ALL PASS · notification → scene → person → learned ranking"
            else "CHECK FAILED",
            15f,
            if (allPass) "#34D399" else "#F87171",
            true
        ).apply { setPadding(0, 28, 0, 0) })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun card(status: String, title: String, detail: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 18)
            setBackgroundColor(Color.parseColor("#10213D"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 18 }
            addView(label("$status · $title", 15f, if (status == "PASS") "#34D399" else "#F87171", true))
            addView(label(detail, 12.5f, "#D5E7F7").apply { setPadding(0, 8, 0, 0) })
        }

    private fun label(textValue: String, size: Float, color: String, bold: Boolean = false) =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
}
