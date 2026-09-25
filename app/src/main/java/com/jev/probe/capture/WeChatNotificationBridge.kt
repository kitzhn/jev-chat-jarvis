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

    @Volatile
    private var pending: Signal? = null

    fun setListener(value: ((Signal) -> Unit)?) {
        listener = value
        if (value != null) {
            val p = pending
            if (p != null && System.currentTimeMillis() - p.at <= 10_000L) {
                pending = null
                value(p)
            }
        }
    }

    fun emit(conversationTitle: String?) {
        val signal = Signal(conversationTitle?.trim()?.takeIf { it.isNotEmpty() })
        val l = listener
        if (l != null) l(signal) else pending = signal
    }
}
