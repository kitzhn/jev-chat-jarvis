package com.jev.probe.core.usage

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.jev.probe.jev.Route
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
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
    val tokenKnown: Boolean,
    val pricingKnown: Boolean
)

data class ApiUsageSummary(
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val costCny: Double,
    val estimatedRequests: Int,
    val unknownTokenRequests: Int,
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
    val unknownTokenRequests: Int,
    val unknownPriceRequests: Int
)

private data class ApiUsageRollup(
    val day: String,
    val route: String,
    val model: String,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val costCny: Double,
    val estimatedRequests: Int,
    val unknownTokenRequests: Int,
    val unknownPriceRequests: Int
)

private data class UsageState(
    val records: MutableList<ApiUsageRecord>,
    val rollups: MutableList<ApiUsageRollup>
)

/**
 * Local-only API metering. Stores token counts and estimated cost, never prompt
 * text, chat text or API keys.
 *
 * Detail rows are capped, but evicted rows are folded into daily rollups. This
 * keeps "today" / "current month" totals complete even during very high-volume
 * use without allowing the detail file to grow without bound.
 */
object ApiUsageStore {
    private val lock = Any()
    private const val MAX_RECORDS = 5000
    private const val ROLLUP_RETENTION_DAYS = 400L
    private const val STATE_VERSION = 2
    private const val USD_CNY = 6.71

    /** Metering must never turn an already successful, billable API call into an error. */
    fun recordSafely(
        context: Context, route: String, baseUrl: String, model: String,
        requestBody: String, response: JSONObject, outputText: String = ""
    ) {
        try {
            record(context, route, baseUrl, model, requestBody, response, outputText)
        } catch (e: Exception) {
            Log.e("ApiUsageStore", "Local metering failed; API response remains valid", e)
        }
    }

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

        // Vision input contains Base64 image bytes. If the provider does not
        // report prompt tokens, character-count estimation is not meaningful.
        val visionInputUnknown = route == Route.VISION && exactPrompt < 0
        val prompt = when {
            exactPrompt >= 0 -> exactPrompt
            visionInputUnknown -> 0
            else -> estimateTokens(requestBody)
        }
        val completion = if (exactCompletion >= 0) exactCompletion else estimateTokens(outputText)
        val tokenKnown = !visionInputUnknown
        val exact = tokenKnown && exactPrompt >= 0 && exactCompletion >= 0

        val cached = when {
            usage == null || prompt <= 0 -> 0
            usage.has("prompt_cache_hit_tokens") -> usage.optInt("prompt_cache_hit_tokens", 0)
            usage.optJSONObject("prompt_tokens_details")?.has("cached_tokens") == true ->
                usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0) ?: 0
            else -> 0
        }.coerceAtMost(prompt).coerceAtLeast(0)

        val directCostUsd = usage?.optDouble("cost", Double.NaN)
            ?.takeIf { !it.isNaN() && it >= 0.0 }
        val priced = if (!tokenKnown && directCostUsd == null) {
            0.0 to false
        } else {
            price(
                baseUrl = baseUrl,
                model = model,
                inputTokens = prompt,
                outputTokens = completion,
                cachedInputTokens = cached,
                at = System.currentTimeMillis(),
                directCostUsd = directCostUsd
            )
        }

        val state = loadState(context)
        state.records.add(ApiUsageRecord(
            ts = System.currentTimeMillis(),
            route = route,
            baseUrl = sanitizeBaseUrl(baseUrl),
            model = model,
            inputTokens = prompt,
            outputTokens = completion,
            cachedInputTokens = cached,
            costCny = priced.first,
            exactTokens = exact,
            tokenKnown = tokenKnown,
            pricingKnown = priced.second
        ))

        while (state.records.size > MAX_RECORDS) {
            mergeIntoRollup(state.rollups, state.records.removeAt(0))
        }
        pruneRollups(state.rollups)
        writeState(context, state)
    }

    fun currentMonth(context: Context): ApiUsageSummary = synchronized(lock) {
        val zone = ZoneId.systemDefault()
        val ym = YearMonth.now(zone)
        val state = loadState(context)
        val detail = summarizeRecords(state.records.filter {
            YearMonth.from(Instant.ofEpochMilli(it.ts).atZone(zone)) == ym
        })
        val archived = summarizeRollups(state.rollups.filter { rollupMonth(it) == ym })
        combine(detail, archived)
    }

    fun today(context: Context): ApiUsageSummary = synchronized(lock) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val state = loadState(context)
        val detail = summarizeRecords(state.records.filter {
            Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() == today
        })
        val archived = summarizeRollups(state.rollups.filter { it.day == today.toString() })
        combine(detail, archived)
    }

    fun currentMonthBreakdown(context: Context): List<ApiUsageBreakdown> = synchronized(lock) {
        val zone = ZoneId.systemDefault()
        val ym = YearMonth.now(zone)
        val state = loadState(context)
        val records = state.records.filter {
            YearMonth.from(Instant.ofEpochMilli(it.ts).atZone(zone)) == ym
        }
        val rollups = state.rollups.filter { rollupMonth(it) == ym }

        val recordMap = records.groupBy { it.route to it.model }
            .mapValues { (_, v) -> breakdownFromRecords(v) }
        val rollupMap = rollups.groupBy { it.route to it.model }
            .mapValues { (_, v) -> breakdownFromRollups(v) }

        (recordMap.keys + rollupMap.keys).map { key ->
            combineBreakdown(
                route = key.first,
                model = key.second,
                a = recordMap[key],
                b = rollupMap[key]
            )
        }.sortedByDescending { it.costCny }
    }

    fun clear(context: Context) = synchronized(lock) {
        AtomicFile(usageFile(context)).delete()
    }

    private fun summarizeRecords(records: List<ApiUsageRecord>): ApiUsageSummary =
        ApiUsageSummary(
            requests = records.size,
            inputTokens = records.sumOf { it.inputTokens.toLong() },
            outputTokens = records.sumOf { it.outputTokens.toLong() },
            cachedInputTokens = records.sumOf { it.cachedInputTokens.toLong() },
            costCny = records.sumOf { it.costCny },
            estimatedRequests = records.count { it.tokenKnown && !it.exactTokens },
            unknownTokenRequests = records.count { !it.tokenKnown },
            unknownPriceRequests = records.count { !it.pricingKnown }
        )

    private fun summarizeRollups(rollups: List<ApiUsageRollup>): ApiUsageSummary =
        ApiUsageSummary(
            requests = rollups.sumOf { it.requests },
            inputTokens = rollups.sumOf { it.inputTokens },
            outputTokens = rollups.sumOf { it.outputTokens },
            cachedInputTokens = rollups.sumOf { it.cachedInputTokens },
            costCny = rollups.sumOf { it.costCny },
            estimatedRequests = rollups.sumOf { it.estimatedRequests },
            unknownTokenRequests = rollups.sumOf { it.unknownTokenRequests },
            unknownPriceRequests = rollups.sumOf { it.unknownPriceRequests }
        )

    private fun combine(a: ApiUsageSummary, b: ApiUsageSummary): ApiUsageSummary =
        ApiUsageSummary(
            requests = a.requests + b.requests,
            inputTokens = a.inputTokens + b.inputTokens,
            outputTokens = a.outputTokens + b.outputTokens,
            cachedInputTokens = a.cachedInputTokens + b.cachedInputTokens,
            costCny = a.costCny + b.costCny,
            estimatedRequests = a.estimatedRequests + b.estimatedRequests,
            unknownTokenRequests = a.unknownTokenRequests + b.unknownTokenRequests,
            unknownPriceRequests = a.unknownPriceRequests + b.unknownPriceRequests
        )

    private fun breakdownFromRecords(records: List<ApiUsageRecord>): ApiUsageBreakdown =
        ApiUsageBreakdown(
            route = records.firstOrNull()?.route.orEmpty(),
            model = records.firstOrNull()?.model.orEmpty(),
            requests = records.size,
            inputTokens = records.sumOf { it.inputTokens.toLong() },
            outputTokens = records.sumOf { it.outputTokens.toLong() },
            costCny = records.sumOf { it.costCny },
            estimatedRequests = records.count { it.tokenKnown && !it.exactTokens },
            unknownTokenRequests = records.count { !it.tokenKnown },
            unknownPriceRequests = records.count { !it.pricingKnown }
        )

    private fun breakdownFromRollups(rollups: List<ApiUsageRollup>): ApiUsageBreakdown =
        ApiUsageBreakdown(
            route = rollups.firstOrNull()?.route.orEmpty(),
            model = rollups.firstOrNull()?.model.orEmpty(),
            requests = rollups.sumOf { it.requests },
            inputTokens = rollups.sumOf { it.inputTokens },
            outputTokens = rollups.sumOf { it.outputTokens },
            costCny = rollups.sumOf { it.costCny },
            estimatedRequests = rollups.sumOf { it.estimatedRequests },
            unknownTokenRequests = rollups.sumOf { it.unknownTokenRequests },
            unknownPriceRequests = rollups.sumOf { it.unknownPriceRequests }
        )

    private fun combineBreakdown(
        route: String,
        model: String,
        a: ApiUsageBreakdown?,
        b: ApiUsageBreakdown?
    ): ApiUsageBreakdown = ApiUsageBreakdown(
        route = route,
        model = model,
        requests = (a?.requests ?: 0) + (b?.requests ?: 0),
        inputTokens = (a?.inputTokens ?: 0L) + (b?.inputTokens ?: 0L),
        outputTokens = (a?.outputTokens ?: 0L) + (b?.outputTokens ?: 0L),
        costCny = (a?.costCny ?: 0.0) + (b?.costCny ?: 0.0),
        estimatedRequests = (a?.estimatedRequests ?: 0) + (b?.estimatedRequests ?: 0),
        unknownTokenRequests = (a?.unknownTokenRequests ?: 0) + (b?.unknownTokenRequests ?: 0),
        unknownPriceRequests = (a?.unknownPriceRequests ?: 0) + (b?.unknownPriceRequests ?: 0)
    )

    private fun findUsage(root: JSONObject): JSONObject? {
        root.optJSONObject("usage")?.let { return it }
        root.optJSONObject("meta")?.optJSONObject("usage")?.let { return it }
        root.optJSONObject("data")?.optJSONObject("usage")?.let { return it }
        return null
    }

    /** Conservative fallback for text-only requests. */
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

    private fun sanitizeBaseUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return runCatching {
            val uri = URI(value)
            val scheme = uri.scheme ?: return@runCatching fallbackSanitizeUrl(value)
            val host = uri.host ?: return@runCatching fallbackSanitizeUrl(value)
            val safeHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            buildString {
                append(scheme)
                append("://")
                append(safeHost)
                if (uri.port >= 0) append(":").append(uri.port)
                // Paths may contain API keys (even when query/userinfo do not).
            }
        }.getOrElse { fallbackSanitizeUrl(value) }
    }

    private fun fallbackSanitizeUrl(raw: String): String {
        val noQuery = raw.substringBefore('#').substringBefore('?')
        val marker = noQuery.indexOf("://")
        if (marker < 0) return ""
        val prefix = noQuery.substring(0, marker + 3)
        val rest = noQuery.substring(marker + 3)
        val authority = rest.substringBefore('/')
        return prefix + authority.substringAfterLast('@')
    }

    private fun usageFile(context: Context): File =
        File(File(context.filesDir, "usage").apply { mkdirs() }, "api_usage.json")

    private fun loadState(context: Context): UsageState {
        val file = usageFile(context)
        if (!file.exists() && !File(file.path + ".bak").exists())
            return UsageState(mutableListOf(), mutableListOf())
        return try {
            val raw = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val trimmed = raw.trimStart()
            var needsMigration = false
            val state = if (trimmed.startsWith("[")) {
                needsMigration = true
                UsageState(parseRecords(JSONArray(raw), migrationFlag = { needsMigration = true }),
                    mutableListOf())
            } else {
                val root = JSONObject(raw)
                if (root.optInt("version", 1) < STATE_VERSION) needsMigration = true
                UsageState(
                    parseRecords(root.optJSONArray("records") ?: JSONArray(),
                        migrationFlag = { needsMigration = true }),
                    parseRollups(root.optJSONArray("rollups") ?: JSONArray())
                )
            }
            while (state.records.size > MAX_RECORDS) {
                mergeIntoRollup(state.rollups, state.records.removeAt(0))
                needsMigration = true
            }
            val rollupCount = state.rollups.size
            pruneRollups(state.rollups)
            if (state.rollups.size != rollupCount) needsMigration = true
            if (needsMigration) writeState(context, state)
            state
        } catch (e: Exception) {
            // Never replace an unreadable ledger with an apparently empty one.
            throw IllegalStateException("API 用量记录读取失败，原文件已保留", e)
        }
    }

    private fun parseRecords(
        arr: JSONArray,
        migrationFlag: () -> Unit
    ): MutableList<ApiUsageRecord> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val route = o.optString("route")
            val exact = o.optBoolean("exactTokens", false)
            val legacyVisionUnknown = !o.has("tokenKnown") && route == Route.VISION && !exact
            val tokenKnown = if (o.has("tokenKnown")) o.optBoolean("tokenKnown", true) else !legacyVisionUnknown
            val rawBase = o.optString("baseUrl")
            val safeBase = sanitizeBaseUrl(rawBase)
            if (safeBase != rawBase.trim() || !o.has("tokenKnown")) migrationFlag()

            ApiUsageRecord(
                ts = o.optLong("ts"),
                route = route,
                baseUrl = safeBase,
                model = o.optString("model"),
                inputTokens = if (legacyVisionUnknown) 0 else o.optInt("inputTokens"),
                outputTokens = o.optInt("outputTokens"),
                cachedInputTokens = if (legacyVisionUnknown) 0 else o.optInt("cachedInputTokens"),
                costCny = if (legacyVisionUnknown) 0.0 else o.optDouble("costCny", 0.0),
                exactTokens = exact && tokenKnown,
                tokenKnown = tokenKnown,
                pricingKnown = if (legacyVisionUnknown) false else o.optBoolean("pricingKnown", false)
            )
        }.toMutableList()

    private fun parseRollups(arr: JSONArray): MutableList<ApiUsageRollup> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ApiUsageRollup(
                day = o.optString("day"),
                route = o.optString("route"),
                model = o.optString("model"),
                requests = o.optInt("requests"),
                inputTokens = o.optLong("inputTokens"),
                outputTokens = o.optLong("outputTokens"),
                cachedInputTokens = o.optLong("cachedInputTokens"),
                costCny = o.optDouble("costCny", 0.0),
                estimatedRequests = o.optInt("estimatedRequests"),
                unknownTokenRequests = o.optInt("unknownTokenRequests"),
                unknownPriceRequests = o.optInt("unknownPriceRequests")
            )
        }.toMutableList()

    private fun mergeIntoRollup(rollups: MutableList<ApiUsageRollup>, record: ApiUsageRecord) {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(record.ts).atZone(zone).toLocalDate().toString()
        val index = rollups.indexOfFirst {
            it.day == day && it.route == record.route && it.model == record.model
        }
        val addEstimated = if (record.tokenKnown && !record.exactTokens) 1 else 0
        val addUnknownTokens = if (record.tokenKnown) 0 else 1
        val addUnknownPrice = if (record.pricingKnown) 0 else 1
        if (index < 0) {
            rollups.add(ApiUsageRollup(
                day = day,
                route = record.route,
                model = record.model,
                requests = 1,
                inputTokens = record.inputTokens.toLong(),
                outputTokens = record.outputTokens.toLong(),
                cachedInputTokens = record.cachedInputTokens.toLong(),
                costCny = record.costCny,
                estimatedRequests = addEstimated,
                unknownTokenRequests = addUnknownTokens,
                unknownPriceRequests = addUnknownPrice
            ))
        } else {
            val old = rollups[index]
            rollups[index] = old.copy(
                requests = old.requests + 1,
                inputTokens = old.inputTokens + record.inputTokens,
                outputTokens = old.outputTokens + record.outputTokens,
                cachedInputTokens = old.cachedInputTokens + record.cachedInputTokens,
                costCny = old.costCny + record.costCny,
                estimatedRequests = old.estimatedRequests + addEstimated,
                unknownTokenRequests = old.unknownTokenRequests + addUnknownTokens,
                unknownPriceRequests = old.unknownPriceRequests + addUnknownPrice
            )
        }
    }

    private fun pruneRollups(rollups: MutableList<ApiUsageRollup>) {
        val cutoff = LocalDate.now(ZoneId.systemDefault()).minusDays(ROLLUP_RETENTION_DAYS)
        rollups.removeAll {
            runCatching { LocalDate.parse(it.day).isBefore(cutoff) }.getOrDefault(true)
        }
    }

    private fun rollupMonth(rollup: ApiUsageRollup): YearMonth? =
        runCatching { YearMonth.from(LocalDate.parse(rollup.day)) }.getOrNull()

    private fun writeState(context: Context, state: UsageState) {
        val records = JSONArray()
        state.records.forEach { r ->
            records.put(JSONObject()
                .put("ts", r.ts)
                .put("route", r.route)
                .put("baseUrl", r.baseUrl)
                .put("model", r.model)
                .put("inputTokens", r.inputTokens)
                .put("outputTokens", r.outputTokens)
                .put("cachedInputTokens", r.cachedInputTokens)
                .put("costCny", r.costCny)
                .put("exactTokens", r.exactTokens)
                .put("tokenKnown", r.tokenKnown)
                .put("pricingKnown", r.pricingKnown))
        }
        val rollups = JSONArray()
        state.rollups.forEach { r ->
            rollups.put(JSONObject()
                .put("day", r.day)
                .put("route", r.route)
                .put("model", r.model)
                .put("requests", r.requests)
                .put("inputTokens", r.inputTokens)
                .put("outputTokens", r.outputTokens)
                .put("cachedInputTokens", r.cachedInputTokens)
                .put("costCny", r.costCny)
                .put("estimatedRequests", r.estimatedRequests)
                .put("unknownTokenRequests", r.unknownTokenRequests)
                .put("unknownPriceRequests", r.unknownPriceRequests))
        }
        val root = JSONObject()
            .put("version", STATE_VERSION)
            .put("records", records)
            .put("rollups", rollups)
        val file = usageFile(context)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
    }
}
