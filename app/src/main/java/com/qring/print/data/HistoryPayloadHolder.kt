package com.qring.print.data

/**
 * 历史记录重打的 payload 中转站。
 * 从历史页点击重打时，把 payload 存到这里；打印页在 aboutToAppear/init 时读取并清空。
 */
object HistoryPayloadHolder {
    private var pendingPayload: String? = null
    private var pendingType: String? = null

    fun setPayload(type: String, payload: String) {
        pendingType = type
        pendingPayload = payload
    }

    fun consumePayload(): Pair<String, String>? {
        val type = pendingType
        val payload = pendingPayload
        pendingType = null
        pendingPayload = null
        return if (type != null && payload != null) Pair(type, payload) else null
    }

    fun peekPayload(): Pair<String, String>? {
        val type = pendingType
        val payload = pendingPayload
        return if (type != null && payload != null) Pair(type, payload) else null
    }

    fun clear() {
        pendingType = null
        pendingPayload = null
    }
}
