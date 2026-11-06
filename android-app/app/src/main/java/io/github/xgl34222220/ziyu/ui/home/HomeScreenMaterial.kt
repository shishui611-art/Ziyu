package io.github.xgl34222220.ziyu.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.theme.LocalDockContentPadding
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuThemeTokens

@Composable
fun HomeScreenMaterial(state: HomeUiState, actions: HomeActions, trustContent: @Composable () -> Unit = {}) {
    val layout = LocalZiyuThemeTokens.current.layout
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = maxOf(LocalDockContentPadding.current, layout.dockSafeBottom)),
        verticalArrangement = Arrangement.spacedBy(layout.itemGap),
    ) {
        item(key = "header") { HomePageHeader(state.loading, actions) }
        item(key = "font") { HomeCurrentFont(state, actions) }
        item(key = "stats") { HomeStatsRow(state, actions) }
        if (state.hasTaskNotice) item(key = "task") { HomeTaskNotice(state, actions) }
        item(key = "device") { HomeDevicePanel(state, trustContent) }
        if (state.typographyVisible) item(key = "typography") { HomeTypographyCard(state, actions) }
        item(key = "mount") { HomeMountButton(state, actions) }
        item(key = "restore") { HomeRecovery(state.moduleInstalled && state.rootGranted && !state.taskRunning, actions.restoreDefault) }
        item(key = "community") { HomeCommunityLinks() }
    }
}
