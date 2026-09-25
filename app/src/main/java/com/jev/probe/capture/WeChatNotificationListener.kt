package com.jev.probe.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jev.probe.core.Prefs

/**
 * Optional WeChat notification trigger.
 *
 * It does not parse or persist the notification body. The title is used only as
 * a hint for the currently open conversation; the actual chat text is still read
 * from a screenshot + on-device OCR when the accessibility service confirms
 * WeChat is foreground.
 */
class WeChatNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != PKG_WECHAT) return

        val prefs = Prefs(this)
        if (!prefs.enabled || !prefs.wechatAutoOcr || !prefs.wechatNotificationTrigger) return

        val extras = sbn.notification?.extras
        val conversationTitle =
            extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        WeChatNotificationBridge.emit(conversationTitle)
    }

    companion object {
        private const val PKG_WECHAT = "com.tencent.mm"
    }
}
