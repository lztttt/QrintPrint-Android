package com.qring.print

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.qring.print.bt.PrinterConnection
import com.qring.print.bt.PrinterPollingService
import com.qring.print.ui.navigation.AppNavHost
import com.qring.print.ui.theme.QringPrintTheme

class MainActivity : ComponentActivity() {

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                PrinterConnection.getInstance().onForeground()
                PrinterPollingService.start(this)
            }
            Lifecycle.Event.ON_PAUSE -> {
                PrinterConnection.getInstance().onBackground()
                PrinterPollingService.stop(this)
            }
            else -> { }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化蓝牙连接
        PrinterConnection.getInstance().init(this)
        PrinterConnection.getInstance().autoReconnect()

        // 监听生命周期
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)

        setContent {
            QringPrintTheme {
                AppNavHost()
            }
        }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        super.onDestroy()
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    QringPrintTheme {
        AppNavHost()
    }
}
