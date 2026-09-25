package com.jev.probe.capture

import com.jev.probe.core.kb.KbStore

/** Conservative gate for notification-triggered WeChat OCR. */
object WeChatNotificationGate {
    fun matches(notificationTitle: String?, currentTitle: String?): Boolean {
        val n = notificationTitle?.trim().orEmpty()
        val c = currentTitle?.trim().orEmpty()
        if (n.isBlank() || c.isBlank()) return false
        val nk = KbStore.normalizeName(n)
        val ck = KbStore.normalizeName(c)
        if (nk.isBlank() || ck.isBlank()) return false
        if (nk == "微信" || nk == "wechat") return false
        return nk == ck
    }
}
