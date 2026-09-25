package com.jev.probe.capture

/**
 * In-process bridge from NotificationListenerService to the accessibility
 * capture service. It carries trigger metadata only; notification message text
 * is deliberately not retained here.
 */
object WeChatNotificationBridge {
    data class Signal(
        val conversationTitle: String?,
        val at: Long = System.currentTimeMillis()
    )

    @Volatile
    private var listener: ((Signal) -> Unit)? = null

    fun setListener(value: ((Signal) -> Unit)?) {
        listener = value
    }

    fun emit(conversationTitle: String?) {
        listener?.invoke(Signal(conversationTitle?.trim()?.takeIf { it.isNotEmpty() }))
    }
}
