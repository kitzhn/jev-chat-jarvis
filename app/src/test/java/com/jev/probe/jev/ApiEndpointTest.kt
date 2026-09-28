package com.jev.probe.jev

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEndpointTest {
    @Test
    fun matchesExactProviderHostOnly() {
        assertTrue(ApiEndpoint.hostEquals("https://openrouter.ai/api/v1", "openrouter.ai"))
        assertTrue(ApiEndpoint.hostEquals("https://OPENROUTER.AI/api", "openrouter.ai"))
        assertFalse(ApiEndpoint.hostEquals("https://openrouter.ai.evil.example/v1", "openrouter.ai"))
        assertFalse(ApiEndpoint.hostEquals("https://evil.example/openrouter.ai/v1", "openrouter.ai"))
    }

    @Test
    fun invalidUrlsFailClosed() {
        assertFalse(ApiEndpoint.hostEquals("not a url", "openrouter.ai"))
        assertFalse(ApiEndpoint.hostEquals("", "api.deepseek.com"))
    }
}
