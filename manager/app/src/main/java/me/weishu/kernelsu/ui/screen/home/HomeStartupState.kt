package me.weishu.kernelsu.ui.screen.home

import me.weishu.kernelsu.data.model.MainTab
import me.weishu.kernelsu.ui.screen.kpm.KpmUiState

data class HomeStartupState(
    val cachedSusfsEnabled: Boolean? = null,
    val susfsReadComplete: Boolean = false,
) {
    fun completeSusfsQuery(confirmedEnabled: Boolean?) = copy(
        cachedSusfsEnabled = confirmedEnabled ?: cachedSusfsEnabled,
        susfsReadComplete = true,
    )

    fun awaitingContent(integration: KpmUiState, selectedTab: String): Boolean {
        val awaitingKpm = (!integration.navigationReady || selectedTab == MainTab.KPM.name) &&
            integration.status == null && !integration.hasRead &&
            integration.unavailable == null && integration.error == null
        return (cachedSusfsEnabled == null && !susfsReadComplete) || awaitingKpm
    }
}
