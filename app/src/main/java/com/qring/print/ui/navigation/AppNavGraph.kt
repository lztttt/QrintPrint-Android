package com.qring.print.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.qring.print.R
import com.qring.print.ui.home.HomeScreen
import com.qring.print.ui.theme.BRAND
import com.qring.print.ui.theme.QringPalette
import com.qring.print.ui.textprint.TextPrintScreen

// ── 路由常量 ──────────────────────────────────────────────
object Routes {
    const val MAIN = "main"
    const val TEXT_PRINT = "text_print"
    const val IMAGE_PRINT = "image_print"
    const val CODE_PRINT = "code_print"
    const val CUSTOM_PRINT = "custom_print"
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

    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
    ) {
        composable(Routes.MAIN) {
            MainScreen(navController = navController)
        }
        composable(Routes.TEXT_PRINT) {
            TextPrintScreen(navController = navController)
        }
    }
}

@Composable
fun MainScreen(navController: NavHostController) {
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
                            selectedIconColor = BRAND,
                            selectedTextColor = BRAND,
                            unselectedIconColor = QringPalette.textSecondary,
                            unselectedTextColor = QringPalette.textSecondary,
                            indicatorColor = BRAND.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentIndex) {
                0 -> HomeScreen(navController = navController)
                1 -> PlaceholderScreen("模板")
                2 -> PlaceholderScreen("历史")
                3 -> PlaceholderScreen("我的")
            }
        }
    }
}

fun PlaceholderScreen(title: String) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(
            "敬请期待",
            style = MaterialTheme.typography.bodyMedium,
            color = QringPalette.textSecondary,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
