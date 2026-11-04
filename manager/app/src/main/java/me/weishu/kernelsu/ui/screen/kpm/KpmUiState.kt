package me.weishu.kernelsu.ui.screen.kpm

import me.weishu.kernelsu.data.system.BackendException
import me.weishu.kernelsu.data.system.KpmModule
import me.weishu.kernelsu.data.system.KpmReport
import me.weishu.kernelsu.data.system.KpmStatus
import me.weishu.kernelsu.data.system.SusfsStatus
import me.weishu.kernelsu.data.system.integrationState

data class SusfsState(
    val supported: Boolean? = null,
    val loaded: Boolean? = null,
    val builtin: Boolean? = null,
    val autoload: Boolean? = null,
    val version: String = "",
    val kmi: String = "",
    val reason: String? = null,
    val error: BackendException? = null,
    val queryError: BackendException? = null,
) {
    fun merge(status: SusfsStatus) = copy(
        supported = status.supported,
        loaded = status.loaded,
        builtin = status.builtin,
        autoload = status.autoload,
        version = status.version.orEmpty(),
        kmi = status.kmi.orEmpty(),
        reason = status.reason,
        error = null,
        queryError = null,
    )

    fun queryFailed(failure: BackendException) = copy(error = failure, queryError = failure)
}

data class KpmUiState(
    val status: KpmStatus? = null,
    val modules: List<KpmModule> = emptyList(),
    val detail: KpmModule? = null,
    val hasRead: Boolean = false,
    val busy: Boolean = false,
    val unavailable: String? = null,
    val error: BackendException? = null,
    val susfs: SusfsState = SusfsState(),
    val canPatchBoot: Boolean = false,
    val output: String? = null,
    val safeMode: Boolean = false,
    val hasFreshStatus: Boolean = false,
    val hasFreshIntegrationStatus: Boolean = false,
    val kpmTabEnabled: Boolean = false,
    val navigationReady: Boolean = false,
    val pendingImport: KpmModule? = null,
    val pendingImportIsNew: Boolean = false,
) {
    val integration get() = integrationState(status, unavailable, error)
    val canMutate get() = !busy && unavailable == null && hasRead && hasFreshStatus && status != null && error == null
    val canImport get() = canMutate
    val showImport get() = unavailable == null && hasRead && status != null
    val canLoad get() = canImport && !safeMode && status?.let {
        it.active && it.configuredEnabled && it.supported && !it.rebootRequired && !it.transitionFailed
    } == true
    private val hasIntegrationAccess get() = !busy && unavailable == null && hasFreshIntegrationStatus &&
        canPatchBoot && status?.stateKnown == true
    val canChangeIntegration get() = status?.let { canSetIntegration(!it.configuredEnabled) } == true
    val requiresReboot get() = status?.let {
        !it.transitionFailed && it.rebootRequired
    } == true
    val canReboot get() = canMutate && requiresReboot
    private val hasKnownSusfsState get() = !busy && unavailable == null && susfs.queryError == null &&
        susfs.supported != null && susfs.loaded != null && susfs.builtin != null && susfs.autoload != null
    val canMutateSusfs get() = hasKnownSusfsState && !(susfs.loaded == true && susfs.supported == false)
    val canChangeSusfsAutoload get() = hasKnownSusfsState &&
        (susfs.autoload == true || (canMutateSusfs && !safeMode && susfs.builtin != true))
    val susfsIntegrationEnabled get() = susfs.autoload == true ||
        (susfs.loaded == true && susfs.builtin != true)
    val canChangeSusfsIntegration get() = canChangeSusfsAutoload ||
        (hasKnownSusfsState && susfs.loaded == true && susfs.builtin != true)
    val canUpgradeIntegration get() = status?.configuredEnabled == true && canSetIntegration(true)

    fun canSetIntegration(enabled: Boolean) = hasIntegrationAccess && status?.let {
        ((if (enabled) it.canEnable else it.canDisable) ?: it.canConfigure ?: it.supported) &&
            (it.configuredEnabled != enabled || (enabled && it.upgradeAvailable))
    } == true &&
        (!safeMode || !enabled)

    fun canChangeAutoload(module: KpmModule?) = canMutate && module?.let {
        it.sha256.isNotBlank() && (!safeMode || it.autoload) && (!it.pendingUnload || it.autoload)
    } == true

    fun canUnload(module: KpmModule?) = canMutate && module?.let { it.loaded && !it.pendingUnload } == true

    fun canRemove(module: KpmModule?) = canMutate && module?.let { !it.loaded && !it.pendingUnload } == true

    fun withAccess(unavailable: String?, canPatchBoot: Boolean, safeMode: Boolean) = copy(
        unavailable = unavailable,
        canPatchBoot = canPatchBoot,
        safeMode = safeMode,
        hasFreshStatus = hasFreshStatus && unavailable == null,
        hasFreshIntegrationStatus = hasFreshIntegrationStatus && unavailable == null,
        susfs = if (unavailable == null) susfs else susfs.queryFailed(
            BackendException("root_unavailable", unavailable)
        ),
    )

    fun merge(report: KpmReport, detailId: String? = null): KpmUiState {
        val freshSnapshot = report.status != null && report.modules != null && report.error == null
        return copy(
            status = report.status ?: status,
            kpmTabEnabled = report.status?.takeIf { it.stateKnown }?.configuredEnabled ?: kpmTabEnabled,
            navigationReady = report.status?.stateKnown == true || navigationReady,
            modules = report.modules ?: modules,
            detail = report.detail ?: if (report.modules != null) report.modules.find { it.id == detailId } else detail,
            hasRead = true,
            // Detail-only success must not clear a failed snapshot or make cached status fresh.
            hasFreshStatus = when {
                report.error != null -> false
                freshSnapshot -> true
                else -> hasFreshStatus
            },
            hasFreshIntegrationStatus = when {
                report.status != null -> true
                report.statusQueried -> false
                else -> hasFreshIntegrationStatus
            },
            error = report.error ?: if (freshSnapshot) null else error,
            output = report.output ?: output,
            pendingImport = pendingImport?.let { pending ->
                report.modules?.find { it.id == pending.id } ?: pending
            },
        )
    }
}
