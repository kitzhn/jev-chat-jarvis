package com.jev.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jev.probe.capture.ChatCaptureService

/** Debug-only bridge used by emulator integration tests to verify cross-app fill. */
class IntegrationCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FILL) return
        val text = intent.getStringExtra("text").orEmpty()
        val delayMs = intent.getLongExtra("delay_ms", 0L)
        if (text.isNotBlank()) ChatCaptureService.debugFillForTest(text, delayMs)
    }

    companion object {
        const val ACTION_FILL = "io.github.kitzhn.jevultimate.debug.FILL_FOR_TEST"
    }
}
