package com.qring.print.data

import android.content.Context

/**
 * 设备别名存储。给打印机起个名字方便区分多台设备。
 */
object DeviceAliasStore {

    private const val PREFS = "qringprint_device_aliases"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 获取别名，没设置过返回 null */
    fun get(context: Context, address: String): String? {
        return try {
            prefs(context).getString(address, null)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** 设置别名 */
    fun set(context: Context, address: String, alias: String) {
        try {
            prefs(context).edit().putString(address, alias.trim()).apply()
        } catch (e: Exception) { }
    }

    /** 清除别名 */
    fun remove(context: Context, address: String) {
        try {
            prefs(context).edit().remove(address).apply()
        } catch (e: Exception) { }
    }
}
