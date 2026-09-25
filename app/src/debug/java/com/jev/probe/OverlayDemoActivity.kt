package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.Msg
import com.jev.probe.core.RankedReply
import com.jev.probe.core.ReplyStrategy
import com.jev.probe.core.Score
import com.jev.probe.core.kb.Contact
import com.jev.probe.jev.ConversationSceneDetector
import com.jev.probe.jev.RelationshipStrategyPolicy
import com.jev.probe.overlay.OverlayController

/**
 * Debug-only screen used by CI to exercise the real OverlayController.
 * It is never included in release builds.
 */
class OverlayDemoActivity : AppCompatActivity() {

    private lateinit var overlay: OverlayController
    private lateinit var input: EditText

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(18))
            setBackgroundColor(Color.parseColor("#F3F5F8"))
        }

        root.addView(TextView(this).apply {
            text = "模拟聊天 · 演示联系人 A"
            textSize = 19f
            setTextColor(Color.parseColor("#111827"))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "用于验证 GalGame 选项 → 一键写入输入框"
            textSize = 11f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(4), 0, dp(14))
        })

        root.addView(bubble("周末有空一起吃饭吗？", mine = false))
        root.addView(bubble("应该有空，怎么啦？", mine = true))
        root.addView(bubble("想找你聊聊天，顺便吃个饭。", mine = false))

        root.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        input = EditText(this).apply {
            hint = "消息输入框"
            textSize = 15f
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#D1D5DB"))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        root.postDelayed({
            overlay = OverlayController(this)
            overlay.showJudgment(
                Analysis(
                    trueIntent = Choice("casual_chat", .91, emptyMap()),
                    dangerLevel = Score(1.0, .95, 9),
                    sheNeeds = Choice("care", .76, emptyMap()),
                    shouldReplyNow = .88,
                    bestAction = Choice("make_plan", .82, emptyMap()),
                    tensionResolved = .78,
                    literalQuestion = .90,
                    rankedReplies = emptyList(),
                    latencyMs = 420
                )
            )
            val demoContact = Contact(
                id = "policy-demo",
                name = "演示联系人 A",
                relationship = "朋友",
                relationshipStage = "亲近",
                affection = 85,
                trust = 80,
                closeness = 90,
                communicationStyle = "喜欢轻松自然、带一点玩笑的聊天",
                boundaries = "不喜欢被催，也不喜欢连续追问",
                strategySelections = mapOf(
                    "playful" to 20,
                    "warm" to 6,
                    "steady" to 3,
                    "proactive" to 1
                )
            )
            val equalJudgeScores = listOf(
                RankedReply(
                    "可以呀，你定个时间，我们找个舒服的地方慢慢聊。",
                    .25, ReplyStrategy.WARM, .25, 1.0),
                RankedReply(
                    "好啊，那周末见～你想吃什么？",
                    .25, ReplyStrategy.PLAYFUL, .25, 1.0),
                RankedReply(
                    "行，我周末有空，到时候你把时间地点发我就好。",
                    .25, ReplyStrategy.STEADY, .25, 1.0),
                RankedReply(
                    "那就周六？我来定地方，你看可以吗？",
                    .25, ReplyStrategy.PROACTIVE, .25, 1.0)
            )
            val demoSnapshot = ChatSnapshot(
                title = "演示联系人 A",
                messages = listOf(
                    Msg("other", "周末有空一起吃饭吗？"),
                    Msg("me", "应该有空，怎么啦？"),
                    Msg("other", "想找你聊聊天，顺便吃个饭。")
                )
            )
            val scene = ConversationSceneDetector.detect(demoSnapshot)
            overlay.showReplies(
                RelationshipStrategyPolicy.rerank(equalJudgeScores, demoContact, scene),
                null
            ) { chosen ->
                input.setText(chosen.text)
                input.setSelection(input.text.length)
            }
        }, 700)
    }

    private fun bubble(textValue: String, mine: Boolean): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(Color.parseColor("#111827"))
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (mine) Color.parseColor("#DDEBFF") else Color.WHITE)
            }
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (mine) Gravity.END else Gravity.START
                topMargin = dp(8)
                if (mine) leftMargin = dp(70) else rightMargin = dp(70)
            }
        }
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }
}
