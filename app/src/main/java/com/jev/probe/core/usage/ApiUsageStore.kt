package com.jev.probe.core.usage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.ceil

data class ApiUsageRecord(
    val ts: Long,
    val route: String,
    val baseUrl: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int,
    val costCny: Double,
    val exactTokens: Boolean,
    val pricingKnown: Boolean
)

data class ApiUsageSummary(
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val costCny: Double,
    val estimatedRequests: Int,
    val unknownPriceRequests: Int
)

data class ApiUsageBreakdown(
    val route: String,
    val model: String,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val costCny: Double,
    val estimatedRequests: Int,
    val unknownPriceRequests: Int
)

/**
 * Local-only API metering. Stores token counts and estimated cost, never prompt
 * text, chat text or API keys.
 */
object ApiUsageStore {
    private val lock = Any()
    // REVIEW(MAJOR-02): bounded detail records can undercount a busy "current month".
    // See docs/CODE_REVIEW_2026-09-25.md before changing storage semantics.
    private const val MAX_RECORDS = 5000
    private const val USD_CNY = 6.71

    fun record(
        context: Context,
        route: String,
        baseUrl: String,
        model: String,
        requestBody: String,
        response: JSONObject,
        outputText: String = ""
    ) = synchronized(lock) {
        val usage = findUsage(response)
        val exactPrompt = usage?.optInt("prompt_tokens", -1) ?: -1
        val exactCompletion = usage?.optInt("completion_tokens", -1) ?: -1
        val exact = exactPrompt >= 0 && exactCompletion >= 0

        // REVIEW(MAJOR-03): for vision requests, requestBody may contain Base64 image
        // data, so this fallback is not a trustworthy billed-token estimate.
        val prompt = if (exactPrompt >= 0) exactPrompt else estimateTokens(requestBody)
        val completion = if (exactCompletion >= 0) exactCompletion else estimateTokens(outputText)

        val cached = when {
            usage == null -> 0
            usage.has("prompt_cache_hit_tokens") -> usage.optInt("prompt_cache_hit_tokens", 0)
            usage.optJSONObject("prompt_tokens_details")?.has("cached_tokens") == true ->
                usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0) ?: 0
            else -> 0
        }.coerceAtMost(prompt).coerceAtLeast(0)

        val directCostUsd = usage?.optDouble("cost", Double.NaN)
            ?.takeIf { !it.isNaN() && it >= 0.0 }
        val priced = price(
            baseUrl = baseUrl,
            model = model,
            inputTokens = prompt,
            outputTokens = completion,
            cachedInputTokens = cached,
            at = System.currentTimeMillis(),
            directCostUsd = directCostUsd
        )

        val list = load(context).toMutableList()
        list.add(ApiUsageRecord(
            ts = System.currentTimeMillis(),
            route = route,
            baseUrl = baseUrl,
            model = model,
            inputTokens = prompt,
            outputTokens = completion,
            cachedInputTokens = cached,
            costCny = priced.first,
            exactTokens = exact,
            pricingKnown = priced.second
        ))
        while (list.size > MAX_RECORDS) list.removeAt(0)
        write(context, list)
    }

    fun currentMonth(context: Context): ApiUsageSummary {
        val zone = ZoneId.systemDefault()
        val ym = YearMonth.now(zone)
        return summarize(load(context).filter {
            YearMonth.from(Instant.ofEpochMilli(it.ts).atZone(zone)) == ym
        })
    }

    fun today(context: Context): ApiUsageSummary {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return summarize(load(context).filter {
            Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() == today
        })
    }

    fun currentMonthBreakdown(context: Context): List<ApiUsageBreakdown> {
        val zone = ZoneId.systemDefault()
        val ym = YearMonth.now(zone)
        val records = load(context).filter {
            YearMonth.from(Instant.ofEpochMilli(it.ts).atZone(zone)) == ym
        }
        return records.groupBy { it.route to it.model }.map { (k, v) ->
            ApiUsageBreakdown(
                route = k.first,
                model = k.second,
                requests = v.size,
                inputTokens = v.sumOf { it.inputTokens.toLong() },
                outputTokens = v.sumOf { it.outputTokens.toLong() },
                costCny = v.sumOf { it.costCny },
                estimatedRequests = v.count { !it.exactTokens },
                unknownPriceRequests = v.count { !it.pricingKnown }
            )
        }.sortedByDescending { it.costCny }
    }

    fun clear(context: Context) = synchronized(lock) {
        usageFile(context).delete()
    }

    private fun summarize(records: List<ApiUsageRecord>): ApiUsageSummary =
        ApiUsageSummary(
            requests = records.size,
            inputTokens = records.sumOf { it.inputTokens.toLong() },
            outputTokens = records.sumOf { it.outputTokens.toLong() },
            cachedInputTokens = records.sumOf { it.cachedInputTokens.toLong() },
            costCny = records.sumOf { it.costCny },
            estimatedRequests = records.count { !it.exactTokens },
            unknownPriceRequests = records.count { !it.pricingKnown }
        )

    private fun findUsage(root: JSONObject): JSONObject? {
        root.optJSONObject("usage")?.let { return it }
        root.optJSONObject("meta")?.optJSONObject("usage")?.let { return it }
        root.optJSONObject("data")?.optJSONObject("usage")?.let { return it }
        return null
    }

    /** Conservative fallback; Chinese is closer to one token/char than English. */
    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return ceil(text.length / 1.8).toInt().coerceAtLeast(1)
    }

    /** Returns cost CNY + whether the app knows the current pricing rule. */
    private fun price(
        baseUrl: String,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        cachedInputTokens: Int,
        at: Long,
        directCostUsd: Double?
    ): Pair<Double, Boolean> {
        if (directCostUsd != null && baseUrl.contains("openrouter.ai", true)) {
            return directCostUsd * USD_CNY to true
        }

        val input = inputTokens.toDouble()
        val output = outputTokens.toDouble()
        val cached = cachedInputTokens.toDouble().coerceAtMost(input)
        val uncached = (input - cached).coerceAtLeast(0.0)

        if (baseUrl.contains("api.deepseek.com", true) && model == "deepseek-flash") {
            val peak = isDeepSeekPeak(at)
            val cacheRate = if (peak) 0.04 else 0.02
            val inputRate = if (peak) 2.0 else 1.0
            val outputRate = if (peak) 8.0 else 4.0
            val cost = cached / 1_000_000.0 * cacheRate +
                uncached / 1_000_000.0 * inputRate +
                output / 1_000_000.0 * outputRate
            return cost to true
        }

        if (baseUrl.contains("openrouter.ai", true) && model == "typesafe/jev-1.13") {
            return input / 1_000_000.0 * 0.042 * USD_CNY to true
        }

        if (baseUrl.contains("openrouter.ai", true) && model == "deepseek/deepseek-v4.1-flash") {
            val costUsd = uncached / 1_000_000.0 * 0.13 +
                cached / 1_000_000.0 * 0.0026 +
                output / 1_000_000.0 * 0.52
            return costUsd * USD_CNY to true
        }

        return 0.0 to false
    }

    private fun isDeepSeekPeak(at: Long): Boolean {
        val z = Instant.ofEpochMilli(at).atZone(ZoneId.of("Asia/Shanghai"))
        if (z.dayOfWeek == DayOfWeek.SATURDAY || z.dayOfWeek == DayOfWeek.SUNDAY) return false
        val m = z.hour * 60 + z.minute
        return m in (9 * 60) until (12 * 60) || m in (14 * 60) until (18 * 60)
    }

    private fun usageFile(context: Context): File =
        File(File(context.filesDir, "usage").apply { mkdirs() }, "api_usage.json")

    private fun load(context: Context): List<ApiUsageRecord> {
        val file = usageFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ApiUsageRecord(
                    ts = o.optLong("ts"),
                    route = o.optString("route"),
                    baseUrl = o.optString("baseUrl"),
                    model = o.optString("model"),
                    inputTokens = o.optInt("inputTokens"),
                    outputTokens = o.optInt("outputTokens"),
                    cachedInputTokens = o.optInt("cachedInputTokens"),
                    costCny = o.optDouble("costCny", 0.0),
                    exactTokens = o.optBoolean("exactTokens", false),
                    pricingKnown = o.optBoolean("pricingKnown", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, records: List<ApiUsageRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject()
                .put("ts", r.ts)
                .put("route", r.route)
                .put("baseUrl", r.baseUrl)
                .put("model", r.model)
                .put("inputTokens", r.inputTokens)
                .put("outputTokens", r.outputTokens)
                .put("cachedInputTokens", r.cachedInputTokens)
                .put("costCny", r.costCny)
                .put("exactTokens", r.exactTokens)
                .put("pricingKnown", r.pricingKnown))
        }
        val file = usageFile(context)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }
}
