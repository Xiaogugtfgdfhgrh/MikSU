package me.weishu.kernelsu.data.system

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun interface CommandExecutor {
    suspend fun execute(arguments: List<String>): CommandOutput
}

data class KpmReport(
    val status: KpmStatus? = null,
    val modules: List<KpmModule>? = null,
    val detail: KpmModule? = null,
    val error: BackendException? = null,
    val output: String? = null,
    val statusQueried: Boolean = false,
)

class KpmRepository(private val executor: CommandExecutor) {
    private val mutex = Mutex()

    suspend fun refresh(): KpmReport = mutex.withLock { refreshUnlocked() }

    suspend fun info(id: String): KpmReport = mutex.withLock {
        try {
            KpmReport(detail = KpmProtocol.decode(executor.execute(listOf("kpm", "info", id))))
        } catch (e: Exception) {
            KpmReport(error = e.backendError())
        }
    }

    suspend fun mutate(arguments: List<String>): KpmReport = mutex.withLock {
        mutateUnlocked(arguments)
    }

    suspend fun loadImported(id: String, args: String, temporary: Boolean): KpmReport = mutex.withLock {
        var report = refreshUnlocked()
        if (report.error != null) return@withLock report
        val status = report.status
        if (status == null || !status.supported || !status.active || !status.configuredEnabled ||
            status.rebootRequired || status.transitionFailed
        ) {
            return@withLock report.copy(error = BackendException("engine_inactive", "KPM integration is not active"))
        }
        val module = report.modules?.find { it.id == id }
            ?: return@withLock report.copy(error = BackendException("not_found", "KPM is not registered"))
        if (module.pendingUnload) {
            return@withLock report.copy(error = BackendException("reboot_required", "KPM is pending reboot"))
        }
        if (!module.loaded) {
            report = mutateUnlocked(listOf("kpm", "load", id, "--args", args))
            if (report.error != null) return@withLock report
            if (report.modules?.any { it.id == id && it.loaded && !it.pendingUnload } != true) {
                return@withLock report.copy(error = BackendException("unconfirmed_load", "KPM loading was not confirmed"))
            }
        }
        report = mutateUnlocked(listOf("kpm", "autoload", id, if (temporary) "disable" else "enable"))
        if (report.error == null &&
            report.modules?.any { it.id == id && it.loaded && !it.pendingUnload && it.autoload == !temporary } != true
        ) {
            report = report.copy(error = BackendException("unconfirmed_autoload", "KPM auto-load setting was not confirmed"))
        }
        report
    }

    private suspend fun mutateUnlocked(arguments: List<String>): KpmReport {
        var failure: BackendException? = null
        var output: String? = null
        var detail: KpmModule? = null
        try {
            require(arguments.firstOrNull() == "kpm")
            val result = executor.execute(arguments)
            val data = KpmProtocol.unwrap(result)
            if (arguments.getOrNull(1) == "import") {
                detail = KpmProtocol.decode<KpmModule>(result)
                if (detail.id.isBlank()) throw BackendException("invalid_data", "Backend returned an empty KPM ID")
            }
            if (arguments.getOrNull(1) == "control") {
                output = ((data as? JsonObject)?.get("output") as? JsonPrimitive)?.contentOrNull
                    ?: data.toString()
            }
        } catch (e: Exception) {
            failure = e.backendError()
        }
        // A command may have changed the kernel before reporting a failure.
        val refreshed = refreshUnlocked()
        if (arguments.getOrNull(1) == "import" && failure == null && detail != null) {
            val imported = refreshed.modules?.find { it.id == detail.id } ?: detail
            if (imported.loaded || imported.pendingUnload) {
                failure = BackendException("already_loaded", "KPM is already resident")
            }
        }
        return refreshed.copy(detail = detail, error = failure ?: refreshed.error, output = output)
    }

    private suspend fun refreshUnlocked(): KpmReport {
        var failure: BackendException? = null
        val status = try {
            KpmProtocol.decode<KpmStatus>(executor.execute(listOf("kpm", "status")))
        } catch (e: Exception) {
            failure = e.backendError()
            null
        }
        val modules = try {
            KpmProtocol.decode<List<KpmModule>>(executor.execute(listOf("kpm", "list"))).also { modules ->
                if (modules.any { it.id.isBlank() } || modules.map { it.id }.toSet().size != modules.size) {
                    throw BackendException("invalid_data", "Backend returned empty or duplicate KPM IDs")
                }
            }
        } catch (e: Exception) {
            if (failure == null) failure = e.backendError()
            null
        }
        return KpmReport(status, modules, error = failure, statusQueried = true)
    }
}

fun Exception.backendError(): BackendException {
    if (this is CancellationException) throw this
    return this as? BackendException ?: BackendException("manager", message ?: javaClass.simpleName)
}
