package com.qring.printer.data

import com.qring.printer.model.HistoryRecord

/**
 * 历史记录重打的中转站。
 * 从历史页点击重打时，把记录存到这里；打印页在 init 时读取并清空。
 */
object HistoryPayloadHolder {
    private var pendingRecord: HistoryRecord? = null

    fun setRecord(record: HistoryRecord) {
        pendingRecord = record
    }

    /** 兼容旧调用 */
    fun setPayload(type: String, payload: String) {
        pendingRecord = HistoryRecord(
            id = "", typeName = type, payload = payload,
            thumbnailPath = "", createdAt = 0
        )
    }

    fun consumeRecord(): HistoryRecord? {
        val r = pendingRecord
        pendingRecord = null
        return r
    }

    fun consumePayload(): Pair<String, String>? {
        val r = pendingRecord
        pendingRecord = null
        return r?.let { Pair(it.typeName, it.payload) }
    }

    fun peekPayload(): Pair<String, String>? {
        val r = pendingRecord
        return r?.let { Pair(it.typeName, it.payload) }
    }

    fun clear() {
        pendingRecord = null
    }
}
