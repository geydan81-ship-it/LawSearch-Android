package com.lawsearch.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lawsearch.app.ui.AppViewModel
import com.lawsearch.app.ui.home.HomeScreen
import com.lawsearch.app.ui.laws.LawListScreen
import com.lawsearch.app.ui.reader.LawReaderScreen
import com.lawsearch.app.ui.search.SearchScreen
import com.lawsearch.app.ui.settings.SettingsScreen
import com.lawsearch.app.ui.theme.LawSearchTheme

class MainActivity : ComponentActivity() {

    /** 从 Intent 带来的查询词（支持 adb / 其他 App 分享文本进来） */
    private val pendingQuery = androidx.compose.runtime.mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingQuery.value = intent?.getStringExtra(EXTRA_QUERY).orEmpty()
        setContent {
            LawSearchTheme {
                AppRoot(initialQuery = pendingQuery.value)
            }
        }
    }

    companion object {
        /** adb shell am start -n com.lawsearch.app/.MainActivity -e query 船舶优先权 */
        const val EXTRA_QUERY = "query"
    }
}

/** 路由常量（与规格书 5.13 的导航结构一致） */
object Routes {
    const val HOME = "home"
    const val LAWS = "laws"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val LIST = "list/{group}"
    const val LATEST = "latest"
    const val BOOKMARKS = "bookmarks"
    const val READER = "reader/{bbbs}?anchor={anchor}"

    val TABS = listOf(HOME, LAWS, SEARCH, SETTINGS)

    fun list(group: String) = "list/" + android.net.Uri.encode(group)

    fun reader(bbbs: String, anchor: String = "") =
        "reader/" + android.net.Uri.encode(bbbs) + "?anchor=" + android.net.Uri.encode(anchor)
}

/**
 * 应用根：单页导航 + 悬浮胶囊 TabBar（阅读器是全屏 push 页，进去后 TabBar 消失）。
 */
@Composable
fun AppRoot(initialQuery: String = "") {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as LawApp
    val vm = remember { AppViewModel(app.container) }
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.HOME

    val showTabBar = !route.startsWith("reader")

    // 带查询词启动：直接落到搜索 Tab
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            navController.navigate(Routes.SEARCH) { launchSingleTop = true }
        }
    }

    // 液态玻璃开关必须用可观察 State。
    // 之前直接 provide prefs.glassEffects（普通 getter），切换后不会触发重组，
    // 所以开关看起来「无效、只能是开启状态」。
    val glassState = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(app.container.prefs.glassEffects)
    }
    // 设置页写 Prefs 后，这里回读一次（vm.setGlassEffects 会同步写）
    val glassEnabled = glassState.value

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // 轮询感知外部修改：设置页写完后立刻反映到全局
        while (true) {
            val saved = app.container.prefs.glassEffects
            if (saved != glassState.value) glassState.value = saved
            kotlinx.coroutines.delay(120)
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.lawsearch.app.ui.components.LocalGlassEffectsEnabled provides glassEnabled,
    ) {
    // 全局背景：壁纸 + 暗化层。所有页面（除阅读器）都叠在它上面用玻璃。
    // 这是让液态玻璃「有东西可折射」的前提 —— 纯白背景上玻璃等于不存在。
    com.lawsearch.app.ui.wallpaper.WallpaperBackground(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        // 照片壁纸的最终暗化 = scrimAlpha + 0.10（上限 0.42，见 WallpaperBackground）。
        // 原来是 0.30 → 照片壁纸实际 0.46，叠加深色令牌后整屏发黑，壁纸几乎看不见。
        scrimAlpha = 0.24f,
    ) {
        NavHost(
            navController = navController,
            startDestination = "tabs",
            enterTransition = { androidx.compose.animation.slideInHorizontally(com.lawsearch.app.ui.theme.IOSMotion.page()) { it } },
            exitTransition = { androidx.compose.animation.fadeOut(com.lawsearch.app.ui.theme.IOSMotion.page()) },
            popEnterTransition = { androidx.compose.animation.fadeIn(com.lawsearch.app.ui.theme.IOSMotion.page()) },
            popExitTransition = { androidx.compose.animation.slideOutHorizontally(com.lawsearch.app.ui.theme.IOSMotion.page()) { it } },
        ) {
            // 四个 Tab 放在 HorizontalPager 里，支持左右滑动切换（类似 iOS）。
            // 列表/阅读器等 push 页面仍是独立 route，盖在 Tab 层之上。
            composable("tabs") {
                TabPager(
                    vm = vm,
                    initialPage = TabPages.indexOfRoute(route),
                    onPageChanged = {},
                    onOpenLaw = { bbbs, anchor -> navController.navigate(Routes.reader(bbbs, anchor)) },
                    onOpenList = { group -> navController.navigate(Routes.list(group)) },
                    onOpenLatest = { navController.navigate(Routes.LATEST) },
                    showTabBar = showTabBar,
                    initialQuery = initialQuery,
                    onTabSelect = {},
                )
            }
            composable(Routes.LIST) { entry ->
                val group = entry.arguments?.getString("group").orEmpty()
                LawListScreen(
                    vm = vm,
                    group = group,
                    onBack = { navController.popBackStack() },
                    onLawClick = { bbbs -> navController.navigate(Routes.reader(bbbs)) },
                )
            }
            composable(Routes.LATEST) {
                LawListScreen(
                    vm = vm,
                    group = "",
                    onBack = { navController.popBackStack() },
                    onLawClick = { bbbs -> navController.navigate(Routes.reader(bbbs)) },
                    latestMode = true,
                )
            }
            composable(
                route = Routes.READER,
                arguments = listOf(
                    navArgument("bbbs") { type = NavType.StringType },
                    navArgument("anchor") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val bbbs = entry.arguments?.getString("bbbs").orEmpty()
                val anchor = entry.arguments?.getString("anchor").orEmpty()
                LawReaderScreen(
                    vm = vm,
                    bbbs = bbbs,
                    initialAnchor = anchor,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
    }
}


/** iOS 的 Tab 切换：保留各 Tab 自己的返回栈与滚动位置 */
fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

