package com.jev.probe

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.Prefs

/** Debug-only deterministic integration-test setup. */
class IntegrationSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs(this).apply {
            enabled = true
            whitelist = if (intent.getBooleanExtra("allowFixtureChats", false)) {
                setOf("演示旅行群", "演示微信群", "演示飞书群", "演示 X 私信")
            } else {
                emptySet()
            }
            autoAnalyze = false
            ocrFallback = true
            ocrAutoAnalyze = false
            wechatAutoOcr = true
            wechatNotificationTrigger = true
        }
        finish()
    }
}
