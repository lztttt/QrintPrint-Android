package com.qring.print

import android.app.Application
import com.qring.print.ui.common.FontList
import com.qring.print.ui.theme.ThemeManager
import timber.log.Timber

class QringApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化主题色
        ThemeManager.init(this)
        // 预加载内置字体（楷体/宋体），供渲染线程读取
        FontList.initBundled(this)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
