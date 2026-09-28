package com.jev.probe.jev

import java.net.URI

/** Canonical host checks shared by provider-specific request and metering code. */
object ApiEndpoint {
    fun hostEquals(raw: String, expectedHost: String): Boolean = runCatching {
        val host = URI(raw.trim()).host?.trimEnd('.')?.lowercase() ?: return@runCatching false
        host == expectedHost.trimEnd('.').lowercase()
    }.getOrDefault(false)
}
