package io.github.xgl34222220.ziyu.ui.library

import androidx.compose.runtime.Composable

@Composable
internal fun FontLibraryScreenMaterial(
    state: FontLibraryUiState,
    actions: FontLibraryActions,
    topActions: @Composable () -> Unit = {},
) {
    FontLibraryPageContent(state, actions, topActions)
}
