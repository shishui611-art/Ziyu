package io.github.xgl34222220.ziyu

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xgl34222220.ziyu.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.logs.LogsActions
import io.github.xgl34222220.ziyu.ui.logs.LogsRoute
import io.github.xgl34222220.ziyu.ui.logs.toLogsUiState
import io.github.xgl34222220.ziyu.ui.theme.LocalMiuixTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuTheme
import io.github.xgl34222220.ziyu.ui.setup.InitializationScreen

internal class TaskCenterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TaskCenterHost() }
    }
}

@Composable
internal fun TaskCenterHost() {
    val model: ZiyuViewModel = viewModel()
    val appearanceViewModel: AppearanceViewModel = viewModel()
    val initializationViewModel: InitializationViewModel = viewModel()
    val appearance by appearanceViewModel.settings.collectAsStateWithLifecycle()
    val setupRequired by appearanceViewModel.setupRequired.collectAsStateWithLifecycle()
    val initialization by initializationViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(setupRequired) {
        when (setupRequired) {
            true -> initializationViewModel.checkEnvironment()
            false -> model.refreshLogs()
            null -> Unit
        }
    }

    ZiyuTheme(appearance) {
        val pageBackground = if (appearance.uiStyle != UiStyle.MATERIAL) {
            LocalMiuixTokens.current.pageBackground
        } else {
            MaterialTheme.colorScheme.background
        }
        val useDarkSystemBars = pageBackground.luminance() < .5f
        val context = LocalContext.current
        val view = LocalView.current
        val contentInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        )

        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !useDarkSystemBars
                isAppearanceLightNavigationBars = !useDarkSystemBars
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBackground)
                .windowInsetsPadding(contentInsets)
                .consumeWindowInsets(contentInsets),
        ) {
            when (setupRequired) {
                null -> InitializationLoadingScreen()
                true -> InitializationScreen(
                    state = initialization,
                    onRecheck = initializationViewModel::checkEnvironment,
                    onStart = appearanceViewModel::completeSetup,
                )
                false -> LogsRoute(
                    style = appearance.uiStyle,
                    state = model.toLogsUiState(),
                    actions = LogsActions(
                        refresh = model::refreshLogs,
                        cancelTask = model::cancelTask,
                        undoApply = model::undoFontApplication,
                        markViewed = model::markLogsViewed,
                        clearLogs = model::clearLogs,
                    ),
                    onBack = { (context as? Activity)?.finish() },
                )
            }
        }
    }
}
