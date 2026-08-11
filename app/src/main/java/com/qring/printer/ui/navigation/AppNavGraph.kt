﻿package com.qring.printer.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.qring.printer.BuildConfig
import com.qring.printer.R
import com.qring.printer.model.HistoryRecord
import com.qring.printer.ui.codeprint.CodePrintScreen
import com.qring.printer.ui.customprint.CustomPrintScreen
import com.qring.printer.ui.history.HistoryScreen
import com.qring.printer.ui.home.HomeScreen
import com.qring.printer.ui.imageprint.ImagePrintScreen
import com.qring.printer.ui.template.TemplateScreen
import com.qring.printer.ui.theme.QringPalette
import com.qring.printer.ui.theme.ThemeManager
import com.qring.printer.ui.textprint.TextPrintScreen
import kotlinx.coroutines.launch

// ── 路由常量 ──────────────────────────────────────────────
object Routes {
    const val MAIN = "main"
    const val TEXT_PRINT = "text_print"
    const val IMAGE_PRINT = "image_print"
    const val CODE_PRINT = "code_print"
    const val CUSTOM_PRINT = "custom_print"
    const val SCHEDULE = "schedule"
    const val LABEL = "label"
    const val CALENDAR = "calendar"
    const val TODO = "todo"
    const val WORDBOOK = "wordbook"
    const val MATH = "math"
    const val ABOUT = "about"
}

data class TabItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val key: String
)

val TAB_ITEMS = listOf(
    TabItem("tab_home", R.string.tab_home, Icons.Default.Home, "home"),
    TabItem("tab_template", R.string.tab_template, Icons.Default.GridView, "template"),
    TabItem("tab_history", R.string.tab_history, Icons.Default.History, "history"),
    TabItem("tab_mine", R.string.tab_mine, Icons.Default.Person, "mine"),
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    // 首次使用免责声明
    com.qring.printer.ui.common.DisclaimerDialog()

    // App 启动时后台检测更新
    StartupUpdateCheck()

    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
    ) {
        composable(Routes.MAIN) {
            MainScreen(
                navController = navController,
                onOpenTemplate = { templateId ->
                    // 从模板页打开模板 → 跳到自定义打印页并加载
                    navController.navigate(Routes.CUSTOM_PRINT)
                },
                onReopenHistory = { record ->
                    // 从历史记录重打 → 根据类型跳对应页面
                    when (record.typeName) {
                        "text" -> navController.navigate(Routes.TEXT_PRINT)
                        "image" -> navController.navigate(Routes.IMAGE_PRINT)
                        "code" -> navController.navigate(Routes.CODE_PRINT)
                        "custom" -> navController.navigate(Routes.CUSTOM_PRINT)
                        "schedule" -> navController.navigate(Routes.SCHEDULE)
                        "todo" -> navController.navigate(Routes.TODO)
                    }
                }
            )
        }
        composable(Routes.TEXT_PRINT) {
            TextPrintScreen(navController = navController)
        }
        composable(Routes.IMAGE_PRINT) {
            ImagePrintScreen(navController = navController)
        }
        composable(Routes.CODE_PRINT) {
            CodePrintScreen(navController = navController)
        }
        composable(Routes.CUSTOM_PRINT) {
            CustomPrintScreen(navController = navController)
        }
        composable(Routes.SCHEDULE) {
            com.qring.printer.ui.schedule.ScheduleScreen(navController = navController)
        }
        composable(Routes.LABEL) {
            com.qring.printer.ui.label.LabelPrintScreen(navController = navController)
        }
        composable(Routes.CALENDAR) {
            com.qring.printer.ui.calendar.CalendarPrintScreen(navController = navController)
        }
        composable(Routes.TODO) {
            com.qring.printer.ui.todo.TodoPrintScreen(navController = navController)
        }
        composable(Routes.WORDBOOK) {
            com.qring.printer.ui.wordbook.WordbookScreen(navController = navController)
        }
        composable(Routes.MATH) {
            com.qring.printer.ui.math.MathPrintScreen(navController = navController)
        }
        composable(Routes.ABOUT) {
            AboutScreen(navController = navController)
        }
    }
}

/**
 * App 启动时后台检查版本更新，发现新版本时弹窗提醒。
 */
@Composable
private fun StartupUpdateCheck() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = remember {
        com.qring.printer.data.UpdateManager(context.applicationContext as android.app.Application)
    }
    val updateState = updateManager.state.collectAsState().value
    var dismissed by rememberSaveable { mutableStateOf(false) }

    // 启动时自动检查一次
    LaunchedEffect(Unit) {
        scope.launch { updateManager.checkForUpdate() }
    }

    // 发现新版本且未被关闭 → 弹窗
    val info = (updateState as? com.qring.printer.data.UpdateManager.UpdateState.Available)?.info
    if (info != null && !dismissed) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dismissed = true },
            title = {
                Text(
                    text = "发现新版本 v${info.version}",
                    fontWeight = FontWeight.Bold,
                    color = QringPalette.textPrimary
                )
            },
            text = {
                Column {
                    if (info.releaseNotes.isNotBlank()) {
                        Text(
                            text = info.releaseNotes.take(300),
                            fontSize = 13.sp,
                            color = QringPalette.textSecondary
                        )
                    }
                    val sizeMB = info.downloadSize / (1024.0 * 1024.0)
                    if (sizeMB > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "大小: ${String.format("%.1f", sizeMB)} MB",
                            fontSize = 12.sp,
                            color = QringPalette.textSecondary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        dismissed = true
                        scope.launch { updateManager.downloadAndInstall(info) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = QringPalette.brand)
                ) {
                    Text("立即更新")
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissed = true }) {
                    Text("稍后再说", color = QringPalette.textSecondary)
                }
            }
        )
    }

    // 下载进度弹窗
    val downloading = updateState as? com.qring.printer.data.UpdateManager.UpdateState.Downloading
    if (downloading != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = { Text("正在下载更新...", color = QringPalette.textPrimary) },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { downloading.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = QringPalette.brand
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${downloading.progress}%", fontSize = 13.sp, color = QringPalette.textSecondary)
                }
            },
            confirmButton = { },
            dismissButton = { }
        )
    }
}

@Composable
fun MainScreen(
    navController: NavHostController,
    onOpenTemplate: (String) -> Unit,
    onReopenHistory: (HistoryRecord) -> Unit
) {
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = QringPalette.surface,
                tonalElevation = 8.dp,
            ) {
                TAB_ITEMS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                        selected = currentIndex == index,
                        onClick = { currentIndex = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = QringPalette.brand,
                            selectedTextColor = QringPalette.brand,
                            unselectedIconColor = QringPalette.textSecondary,
                            unselectedTextColor = QringPalette.textSecondary,
                            indicatorColor = QringPalette.brand.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentIndex) {
                0 -> HomeScreen(navController = navController)
                1 -> TemplateScreen(onOpenTemplate = onOpenTemplate)
                2 -> HistoryScreen(onReopen = onReopenHistory)
                3 -> MineScreen(navController = navController)
            }
        }
    }
}

@Composable
fun MineScreen(navController: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentColor by ThemeManager.brandColor
    val openUrl: (String) -> Unit = { url ->
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            )
        } catch (e: Exception) { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("我的", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // 主题色选择
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "主题色",
                        color = QringPalette.textPrimary,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(currentColor)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "选择主题色 立即生效",
                    fontSize = 12.sp,
                    color = QringPalette.textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                // 色块网格
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ThemeManager.PRESET_COLORS.take(5).forEach { color ->
                        ColorSwatch(
                            color = color,
                            selected = color == currentColor,
                            onClick = { ThemeManager.setBrandColor(context, color) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ThemeManager.PRESET_COLORS.drop(5).forEach { color ->
                        ColorSwatch(
                            color = color,
                            selected = color == currentColor,
                            onClick = { ThemeManager.setBrandColor(context, color) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 检查更新
        UpdateCard()

        Spacer(modifier = Modifier.height(12.dp))

        // 关于（内联展开）
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "QringPrint",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = QringPalette.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME} · 58mm 蓝牙热敏打印机客户端",
                    fontSize = 12.sp,
                    color = QringPalette.textSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                InfoRow("开源地址", "github.com/lztttt/QrintPrint-Android")
                InfoRow("原项目", "github.com/Thisko/QrintPrint")
                InfoRow("开源协议", "MIT License")
                InfoRow("开发语言", "Kotlin · Jetpack Compose")
                InfoRow("设备支持", "错题小印系列 58mm 蓝牙热敏打印机")

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "本应用基于 Thisko 的 QrintPrint (HarmonyOS) 移植，感谢原作者的开源项目。本应用为个人开发的第三方客户端，仅供学习参考，严禁商用。",
                    fontSize = 11.sp,
                    color = QringPalette.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 更新日志
        ChangelogCard()
    }
}

@Composable
private fun UpdateCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val updateManager = androidx.compose.runtime.remember {
        com.qring.printer.data.UpdateManager(context.applicationContext as android.app.Application)
    }
    val updateState by updateManager.state.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "版本更新",
                    color = QringPalette.textPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "当前 v${BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp,
                    color = QringPalette.textSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (val st = updateState) {
                is com.qring.printer.data.UpdateManager.UpdateState.Idle -> {
                    TextButton(onClick = {
                        scope.launch { updateManager.checkForUpdate() }
                    }) {
                        Text("检查更新", fontSize = 13.sp)
                    }
                }
                is com.qring.printer.data.UpdateManager.UpdateState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = QringPalette.brand
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在检查...", fontSize = 13.sp, color = QringPalette.textSecondary)
                    }
                }
                is com.qring.printer.data.UpdateManager.UpdateState.UpToDate -> {
                    Text("已是最新版本 ✓", fontSize = 13.sp, color = QringPalette.textSecondary)
                }
                is com.qring.printer.data.UpdateManager.UpdateState.Available -> {
                    Column {
                        Text(
                            text = "发现新版本 v${st.info.version}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = QringPalette.brand
                        )
                        if (st.info.releaseNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = st.info.releaseNotes.take(200),
                                fontSize = 12.sp,
                                color = QringPalette.textSecondary
                            )
                        }
                        val sizeMB = st.info.downloadSize / (1024.0 * 1024.0)
                        if (sizeMB > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "大小: ${String.format("%.1f", sizeMB)} MB",
                                fontSize = 11.sp,
                                color = QringPalette.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch { updateManager.downloadAndInstall(st.info) }
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = QringPalette.brand
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("下载并安装", fontSize = 13.sp)
                        }
                    }
                }
                is com.qring.printer.data.UpdateManager.UpdateState.Downloading -> {
                    Column {
                        Text("下载中 ${st.progress}%", fontSize = 13.sp, color = QringPalette.textPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { st.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = QringPalette.brand
                        )
                    }
                }
                is com.qring.printer.data.UpdateManager.UpdateState.ReadyToInstall -> {
                    Text("下载完成，请按系统提示安装", fontSize = 13.sp, color = QringPalette.brand)
                }
                is com.qring.printer.data.UpdateManager.UpdateState.Error -> {
                    Text(
                        text = st.message,
                        fontSize = 12.sp,
                        color = androidx.compose.ui.graphics.Color.Red
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = {
                        scope.launch { updateManager.checkForUpdate() }
                    }) {
                        Text("重试", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) Modifier.border(3.dp, QringPalette.textPrimary, CircleShape)
                else Modifier.border(1.dp, QringPalette.offline, CircleShape)
            )
            .clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navController: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val openUrl: (String) -> Unit = { url ->
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            )
        } catch (e: Exception) {
            // 无浏览器时忽略
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QringPalette.pageBg)
    ) {
        TopAppBar(
            title = { Text("关于") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = QringPalette.surface,
                titleContentColor = QringPalette.textPrimary
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "QringPrint",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = QringPalette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "版本 ${BuildConfig.VERSION_NAME}",
                        fontSize = 13.sp,
                        color = QringPalette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "58mm 蓝牙热敏打印机客户端",
                        fontSize = 13.sp,
                        color = QringPalette.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "开源地址",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = QringPalette.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "https://github.com/lztttt/QrintPrint-Android",
                        fontSize = 13.sp,
                        color = QringPalette.brand,
                        modifier = Modifier.clickable { openUrl("https://github.com/lztttt/QrintPrint-Android") }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "点击在浏览器中打开",
                        fontSize = 11.sp,
                        color = QringPalette.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("开源协议", "MIT License")
                    InfoRow("原项目", "github.com/Thisko/QrintPrint")
                    InfoRow("开发语言", "Kotlin · Jetpack Compose")
                    InfoRow("设备支持", "错题小印系列 58mm 蓝牙热敏打印机")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "本应用为个人开发的第三方客户端，仅供学习参考，严禁商用。",
                fontSize = 11.sp,
                color = QringPalette.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = QringPalette.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = QringPalette.textPrimary
        )
    }
}

@Composable
private fun ChangelogCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var releases by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.qring.printer.data.UpdateManager.ReleaseNote>>(emptyList()) }
    var loading by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var loaded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!loaded) {
            loading = true
            val updateManager = com.qring.printer.data.UpdateManager(context.applicationContext as android.app.Application)
            releases = updateManager.fetchAllReleases()
            loading = false
            loaded = true
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = QringPalette.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "更新日志",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = QringPalette.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = QringPalette.brand
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("加载中...", fontSize = 13.sp, color = QringPalette.textSecondary)
                }
            } else if (releases.isEmpty()) {
                Text("暂无发布记录", fontSize = 13.sp, color = QringPalette.textSecondary)
            } else {
                releases.forEachIndexed { index, release ->
                    if (index > 0) {
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = QringPalette.surfaceSunken
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "v${release.version}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (index == 0) QringPalette.brand else QringPalette.textPrimary
                        )
                        if (index == 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "最新",
                                fontSize = 10.sp,
                                color = QringPalette.brand,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(QringPalette.brand.copy(alpha = 0.12f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (release.publishedAt.isNotEmpty()) {
                            val dateStr = release.publishedAt.take(10)
                            Text(dateStr, fontSize = 11.sp, color = QringPalette.textSecondary)
                        }
                    }
                    if (release.notes.isNotBlank() && release.notes != "暂无说明") {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = release.notes.take(300),
                            fontSize = 12.sp,
                            color = QringPalette.textSecondary,
                            maxLines = 8
                        )
                    }
                }
            }
        }
    }
}
