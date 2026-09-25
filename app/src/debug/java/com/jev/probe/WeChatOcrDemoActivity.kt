package com.jev.probe

import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.capture.ocr.OcrChatGrouper
import com.jev.probe.capture.ocr.OcrLine

/**
 * Debug-only deterministic check for the WeChat OCR side heuristic.
 * It does not pretend to be WeChat; it validates the geometry stage that turns
 * OCR boxes into other/me chat messages before automatic analysis.
 */
class WeChatOcrDemoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screenWidth = 1080
        val sample = listOf(
            OcrLine("12:30", Rect(500, 240, 580, 280)),
            OcrLine("周末有空吗？", Rect(90, 360, 390, 420)),
            OcrLine("想一起吃个饭", Rect(90, 425, 430, 485)),
            OcrLine("有空呀", Rect(790, 570, 990, 630)),
            OcrLine("那周六晚上？", Rect(95, 720, 430, 780))
        )
        val grouped = OcrChatGrouper.groupBySide(sample, screenWidth)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
            setBackgroundColor(Color.parseColor("#071226"))
        }
        root.addView(TextView(this).apply {
            text = "WECHAT OCR · GEOMETRY CHECK"
            textSize = 18f
            setTextColor(Color.parseColor("#7EE7FF"))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "模拟 OCR 框 → 只按屏幕左右位置推断发送方"
            textSize = 12f
            setTextColor(Color.parseColor("#A8C5DF"))
            setPadding(0, 10, 0, 24)
        })

        grouped.forEachIndexed { index, msg ->
            root.addView(TextView(this).apply {
                text = "${index + 1}. ${msg.side.uppercase()}  ·  ${msg.text}"
                textSize = 15f
                setTextColor(if (msg.side == "other") Color.parseColor("#7EE7FF") else Color.WHITE)
                gravity = if (msg.side == "other") Gravity.START else Gravity.END
                setPadding(18, 18, 18, 18)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 12 }
            })
        }

        val expected = grouped.map { it.side } == listOf("other", "me", "other")
        root.addView(TextView(this).apply {
            text = if (expected) "PASS · other → me → other" else "FAIL · ${grouped.map { it.side }}"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (expected) Color.parseColor("#34D399") else Color.parseColor("#F87171"))
            setPadding(0, 30, 0, 0)
        })

        setContentView(root)
    }
}
