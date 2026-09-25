package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.usage.ApiUsageBreakdown
import com.jev.probe.core.usage.ApiUsageStore
import com.jev.probe.core.usage.ApiUsageSummary
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

class ApiUsageActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private val accent = Color.parseColor("#3A7AFE")
    private val green = Color.parseColor("#16A34A")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
        }
        root.padForSystemBars()
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()
        val month = ApiUsageStore.currentMonth(this)
        val today = ApiUsageStore.today(this)
        val breakdown = ApiUsageStore.currentMonthBreakdown(this)

        root.addView(text("API 消耗仪表盘", 24f, ink, true))
        root.addView(text("只统计本机 Jev Ultimate 发出的请求；不保存聊天正文、Prompt 或 API Key。",
            12f, sub).apply { setPadding(0, dp(5), 0, dp(10)) })
        root.addView(monthHero(month))
        root.addView(summaryPair(today, month))

        root.addView(section("本月按模型 / 接口"))
        if (breakdown.isEmpty()) {
            root.addView(card().apply {
                addView(text("本月还没有 API 调用记录", 14f, ink, true))
                addView(text("产生第一轮分析后，这里会出现 Jev、回复模型和视觉模型的消耗。", 12f, sub)
                    .apply { setPadding(0, dp(5), 0, 0) })
            })
        } else {
            breakdown.forEach { root.addView(breakdownCard(it)) }
        }

        root.addView(section("计费说明"))
        root.addView(card().apply {
            addView(text("如何计算", 14f, ink, true))
            addView(text(
                "• 响应带 usage 时优先使用真实 prompt/completion token。\n" +
                "• 文本接口不返回 usage 时按文本长度估算，并计入“估算请求”。\n" +
                "• 视觉接口不返回 prompt token 时，图片 token 明确记为“未知”，不会再按 Base64 长度估算。\n" +
                "• DeepSeek 官方 deepseek-flash 按北京时间峰谷价估算。\n" +
                "• OpenRouter Jev 1.13 / DeepSeek V4.1 Flash 按当前公开价估算；若响应带 cost 则优先使用。\n" +
                "• 自定义或未知价格接口只统计 token，不把费用伪装成 0 元。",
                11.5f, sub).apply { setPadding(0, dp(5), 0, 0) })
        })
        root.addView(text("价格为本地估算，最终扣费以各 API 服务商账单为准。",
            11f, sub).apply { setPadding(dp(2), dp(10), 0, 0) })

        root.addView(button("清空本机 API 统计") {
            AlertDialog.Builder(this)
                .setTitle("清空 API 统计？")
                .setMessage("只删除 token/费用统计，不影响联系人、聊天历史或 API 配置。")
                .setPositiveButton("清空") { _, _ -> ApiUsageStore.clear(this); render() }
                .setNegativeButton("取消", null)
                .show()
        })
    }

    private fun monthHero(s: ApiUsageSummary): LinearLayout = card().apply {
        val ym = YearMonth.now(ZoneId.systemDefault())
        addView(text("${ym.year} 年 ${ym.monthValue} 月", 12f, sub, true))
        addView(text(formatMoney(s.costCny), 32f, accent, true).apply { setPadding(0, dp(5), 0, 0) })
        addView(text("本月预计 API 费用", 12f, sub))
        val inputLabel = if (s.unknownTokenRequests > 0) "已知输入" else "输入"
        addView(text("${s.requests} 次请求 · ${inputLabel} ${formatTokens(s.inputTokens)} · 输出 ${formatTokens(s.outputTokens)}",
            12.5f, ink).apply { setPadding(0, dp(9), 0, 0) })
        if (s.cachedInputTokens > 0) addView(text("其中缓存命中 ${formatTokens(s.cachedInputTokens)}", 11.5f, green))
        val notes = buildList {
            if (s.estimatedRequests > 0) add("${s.estimatedRequests} 次 token 为估算")
            if (s.unknownTokenRequests > 0) add("${s.unknownTokenRequests} 次图片 token 未知")
            if (s.unknownPriceRequests > 0) add("${s.unknownPriceRequests} 次价格未知")
        }
        if (notes.isNotEmpty()) addView(text(notes.joinToString(" · "), 11f, Color.parseColor("#D97706"))
            .apply { setPadding(0, dp(5), 0, 0) })
    }

    private fun summaryPair(today: ApiUsageSummary, month: ApiUsageSummary): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
            addView(miniSummary("今日", today).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { rightMargin = dp(5) }
            })
            addView(miniSummary("本月调用", month).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(5) }
            })
        }

    private fun miniSummary(title: String, s: ApiUsageSummary): LinearLayout = card().apply {
        addView(text(title, 12f, sub, true))
        addView(text(if (title == "今日") formatMoney(s.costCny) else "${s.requests} 次", 20f, ink, true)
            .apply { setPadding(0, dp(4), 0, 0) })
        addView(text(if (title == "今日") "${s.requests} 次请求"
            else "${formatTokens(s.inputTokens + s.outputTokens)} tokens", 11f, sub))
    }

    private fun breakdownCard(b: ApiUsageBreakdown): LinearLayout = card().apply {
        val row = LinearLayout(this@ApiUsageActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this@ApiUsageActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(b.route, 12f, accent, true))
        left.addView(text(b.model.ifBlank { "未命名模型" }, 14f, ink, true))
        row.addView(left)
        val costLabel = if (b.unknownPriceRequests == b.requests && b.costCny == 0.0)
            "价格未知" else formatMoney(b.costCny)
        row.addView(text(costLabel, 16f, ink, true))
        addView(row)
        val inputLabel = if (b.unknownTokenRequests > 0) "已知输入" else "输入"
        addView(text("${b.requests} 次 · ${inputLabel} ${formatTokens(b.inputTokens)} · 输出 ${formatTokens(b.outputTokens)}",
            11.5f, sub).apply { setPadding(0, dp(7), 0, 0) })
        val notes = buildList {
            if (b.estimatedRequests > 0) add("${b.estimatedRequests} 次 token 估算")
            if (b.unknownTokenRequests > 0) add("${b.unknownTokenRequests} 次图片 token 未知")
            if (b.unknownPriceRequests > 0) add("${b.unknownPriceRequests} 次价格未知")
        }
        if (notes.isNotEmpty()) addView(text(notes.joinToString(" · "), 10.5f, Color.parseColor("#D97706")))
    }

    private fun formatMoney(v: Double): String =
        if (v < 0.01 && v > 0.0) String.format(Locale.US, "¥%.4f", v)
        else String.format(Locale.US, "¥%.2f", v)

    private fun formatTokens(v: Long): String = when {
        v >= 1_000_000 -> String.format(Locale.US, "%.2fM", v / 1_000_000.0)
        v >= 1_000 -> String.format(Locale.US, "%.1fK", v / 1_000.0)
        else -> v.toString()
    }

    private fun section(t: String) = text(t, 12f, sub, true).apply { setPadding(dp(2), dp(18), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) }
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun button(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD); setTextColor(accent)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(Color.WHITE); setStroke(dp(1), accent)
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) }
        setOnClickListener { action() }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
