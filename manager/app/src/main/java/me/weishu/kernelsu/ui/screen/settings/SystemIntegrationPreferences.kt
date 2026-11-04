package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.SegmentedSwitchItem
import me.weishu.kernelsu.ui.screen.kpm.KpmUiState
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon

private val KpmUiState.integrationError: String?
    get() = (unavailable ?: error?.message ?: status?.takeIf { !it.supported || it.transitionFailed }?.reason)
        ?.lineSequence()?.firstOrNull()?.take(160)

private val KpmUiState.susfsError: String?
    get() = (unavailable ?: susfs.error?.message ?: susfs.reason?.takeIf {
        susfs.loaded == true && susfs.supported == false
    })?.lineSequence()?.firstOrNull()?.take(160)

fun systemIntegrationPreferencesMaterial(
    state: KpmUiState,
    actions: SettingsScreenActions,
): List<@Composable () -> Unit> = buildList {
    add {
        SegmentedSwitchItem(
            icon = Icons.Filled.Memory,
            title = stringResource(R.string.kpm_integration),
            summary = state.integrationError,
            checked = state.status?.configuredEnabled == true,
            enabled = state.canChangeIntegration,
            onCheckedChange = actions.onSetKpmIntegration,
        )
    }
    add {
        SegmentedSwitchItem(
            icon = Icons.Filled.RestartAlt,
            title = stringResource(R.string.susfs_integration),
            summary = state.susfsError,
            checked = state.susfsIntegrationEnabled,
            enabled = state.canChangeSusfsIntegration,
            onCheckedChange = actions.onSetSusfsAutoload,
        )
    }
}

@Composable
fun SystemIntegrationPreferencesMiuix(
    state: KpmUiState,
    actions: SettingsScreenActions,
) {
    SwitchPreference(
        title = stringResource(R.string.kpm_integration),
        summary = state.integrationError,
        startAction = {
            MiuixIcon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        },
        checked = state.status?.configuredEnabled == true,
        enabled = state.canChangeIntegration,
        onCheckedChange = actions.onSetKpmIntegration,
    )
    SwitchPreference(
        title = stringResource(R.string.susfs_integration),
        summary = state.susfsError,
        startAction = {
            MiuixIcon(
                imageVector = Icons.Rounded.RestartAlt,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        },
        checked = state.susfsIntegrationEnabled,
        enabled = state.canChangeSusfsIntegration,
        onCheckedChange = actions.onSetSusfsAutoload,
    )
}
