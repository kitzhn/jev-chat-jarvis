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
            autoAnalyze = false
            ocrFallback = true
            ocrAutoAnalyze = false
            wechatAutoOcr = true
            wechatNotificationTrigger = true
        }
        finish()
    }
}
