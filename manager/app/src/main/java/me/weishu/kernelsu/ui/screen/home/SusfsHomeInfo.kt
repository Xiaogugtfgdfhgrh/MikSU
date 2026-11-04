package me.weishu.kernelsu.ui.screen.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.weishu.kernelsu.data.system.CommandExecutor
import me.weishu.kernelsu.data.system.SusfsProtocol
import me.weishu.kernelsu.ui.screen.kpm.KpmUiState
import me.weishu.kernelsu.ui.screen.kpm.SusfsState

fun susfsHomeSnapshots(
    integrationState: StateFlow<KpmUiState>,
    refreshRequests: StateFlow<Int>,
): Flow<SusfsHomeSnapshot> = combine(
    integrationState.map { SusfsHomeSnapshot.from(it) }.distinctUntilChanged(),
    refreshRequests,
) { snapshot, _ -> snapshot }

data class SusfsHomeSnapshot(
    val state: SusfsState,
    val integrationEnabled: Boolean,
    val unavailable: String? = null,
) {
    val confirmed: Boolean
        get() = unavailable == null && state.queryError == null &&
            state.supported != null && state.loaded != null &&
            state.builtin != null && state.autoload != null

    val operational: Boolean
        get() = confirmed && integrationEnabled && state.supported == true &&
            (state.loaded == true || state.builtin == true)

    companion object {
        fun from(state: KpmUiState) = SusfsHomeSnapshot(
            state = state.susfs,
            integrationEnabled = state.susfsIntegrationEnabled,
            unavailable = state.unavailable,
        )
    }
}

data class SusfsHomeInfo(
    val integrationEnabled: Boolean = false,
    val supported: Boolean = false,
    val version: String? = null,
    val variant: String? = null,
) {
    fun merge(snapshot: SusfsHomeSnapshot): SusfsHomeInfo {
        val enabled = if (snapshot.confirmed) snapshot.integrationEnabled else integrationEnabled
        if (!snapshot.operational) return SusfsHomeInfo(integrationEnabled = enabled)

        val nextVersion = runtimeValue(snapshot.state.version)
        return SusfsHomeInfo(
            integrationEnabled = enabled,
            supported = true,
            version = nextVersion,
            variant = variant.takeIf { supported && version == nextVersion },
        )
    }

    companion object {
        fun runtimeValue(value: String): String? = value.trim().takeIf {
            it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl) &&
                it.lowercase() !in setOf("unknown", "unavailable", "unsupported", "n/a", "null")
        }
    }
}

class SusfsHomeInfoQuery(private val executor: CommandExecutor) {
    suspend fun variant(): String? = try {
        val output = SusfsProtocol.checkCommand(executor.execute(listOf("susfs", "variant")))
        SusfsHomeInfo.runtimeValue(output.stdout)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
