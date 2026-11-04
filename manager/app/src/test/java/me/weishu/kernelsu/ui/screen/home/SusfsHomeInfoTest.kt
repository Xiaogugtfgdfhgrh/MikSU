package me.weishu.kernelsu.ui.screen.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.weishu.kernelsu.data.system.BackendException
import me.weishu.kernelsu.data.system.CommandExecutor
import me.weishu.kernelsu.data.system.CommandOutput
import me.weishu.kernelsu.ui.screen.kpm.KpmUiState
import me.weishu.kernelsu.ui.screen.kpm.SusfsState
import org.junit.Assert.*
import org.junit.Test

class SusfsHomeInfoTest {
    private fun state(
        enabled: Boolean = true,
        supported: Boolean = true,
        loaded: Boolean = true,
        builtin: Boolean = false,
        version: String = "v2.3.0",
    ) = KpmUiState(
        susfs = SusfsState(
            autoload = enabled, supported = supported, loaded = loaded,
            builtin = builtin, version = version,
        ),
    )

    @Test fun disabledIntegrationHidesEvenAnAvailableBuiltinProvider() {
        val snapshot = SusfsHomeSnapshot.from(state(enabled = false, loaded = false, builtin = true))
        val info = SusfsHomeInfo(integrationEnabled = true).merge(snapshot)
        assertTrue(snapshot.confirmed)
        assertFalse(info.integrationEnabled)
        assertFalse(info.supported)
        assertNull(info.version)
        assertNull(info.variant)
    }

    @Test fun cachedEnableKeepsTheRowDuringInitialStatusReadWithoutClaimingSupport() {
        val info = SusfsHomeInfo(integrationEnabled = true).merge(SusfsHomeSnapshot.from(KpmUiState()))
        assertTrue(info.integrationEnabled)
        assertFalse(info.supported)
        assertNull(info.version)
    }

    @Test fun actualRuntimeVersionIsUsedAndVariantSurvivesEquivalentRefreshes() {
        val snapshot = SusfsHomeSnapshot.from(state(version = " v2.3.0 "))
        val info = SusfsHomeInfo().merge(snapshot).copy(variant = "SUSFS_LKM_MODULES")
        assertTrue(info.integrationEnabled)
        assertTrue(info.supported)
        assertEquals("v2.3.0", info.version)
        assertEquals(info, info.merge(snapshot))
        assertNull(info.merge(SusfsHomeSnapshot.from(state(version = "v2.4.0"))).variant)
    }

    @Test fun enabledButStoppedOrFailedProviderIsNotReportedSupported() {
        for (current in listOf(
            state(loaded = false),
            state(supported = false),
            state(supported = false, loaded = false),
        )) {
            val info = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(current))
            assertTrue(info.integrationEnabled)
            assertFalse(info.supported)
            assertNull(info.version)
            assertNull(info.variant)
        }
    }

    @Test fun aResidentIndependentGuardUsesTheAuthoritativeEnabledState() {
        val current = state(enabled = false)
        assertTrue(current.susfsIntegrationEnabled)
        val info = SusfsHomeInfo().merge(SusfsHomeSnapshot.from(current))
        assertTrue(info.integrationEnabled)
        assertTrue(info.supported)
    }

    @Test fun aConfirmedDisableClearsAllPreviousRuntimeDetails() {
        val info = SusfsHomeInfo(true, true, "v2.3.0", "SUSFS_LKM_MODULES")
            .merge(SusfsHomeSnapshot.from(state(enabled = false, supported = false, loaded = false)))
        assertEquals(SusfsHomeInfo(), info)
    }

    @Test fun queryFailurePreservesVisibilityButCannotPresentStaleSupport() {
        val previous = SusfsHomeInfo(true, true, "v2.3.0", "SUSFS_LKM_MODULES")
        val failed = state().let {
            it.copy(susfs = it.susfs.queryFailed(BackendException("read", "failed")))
        }
        for (snapshot in listOf(
            SusfsHomeSnapshot.from(failed),
            SusfsHomeSnapshot.from(state().copy(unavailable = "No root")),
        )) {
            assertFalse(snapshot.confirmed)
            assertEquals(SusfsHomeInfo(integrationEnabled = true), previous.merge(snapshot))
        }
    }

    @Test fun unknownFieldsAndBusyFlagsDoNotProduceOffOnVisibilityChanges() {
        val previous = SusfsHomeInfo(true, true, "v2.3.0", "SUSFS_LKM_MODULES")
        for (partial in listOf(
            state().susfs.copy(supported = null), state().susfs.copy(loaded = null),
            state().susfs.copy(builtin = null), state().susfs.copy(autoload = null),
        )) {
            val snapshot = SusfsHomeSnapshot.from(KpmUiState(susfs = partial))
            assertFalse(snapshot.confirmed)
            assertTrue(previous.merge(snapshot).integrationEnabled)
        }
        assertEquals(previous, previous.merge(SusfsHomeSnapshot.from(state().copy(busy = true))))
    }

    @Test fun invalidRuntimeMetadataIsNeverDisplayedAsRealVersionOrVariant() {
        for (value in listOf("", "  ", "Unknown", "Unsupported", "Unavailable", "null", "n/a", "v1\nv2", "x".repeat(129))) {
            assertNull(SusfsHomeInfo.runtimeValue(value))
        }
        assertEquals("SUSFS-LKM", SusfsHomeInfo.runtimeValue(" SUSFS-LKM\n"))
    }

    @Test fun variantIsReadFromTheActualBackend() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val query = SusfsHomeInfoQuery(CommandExecutor {
            calls += it
            CommandOutput(0, "SUSFS_LKM_MODULES\n", "")
        })
        assertEquals("SUSFS_LKM_MODULES", query.variant())
        assertEquals(listOf(listOf("susfs", "variant")), calls)
    }

    @Test fun failedOrInvalidVariantQueryDoesNotInventAModuleVariant() = runBlocking {
        for (output in listOf(
            CommandOutput(1, "SUSFS_LKM_MODULES", "failed"),
            CommandOutput(0, "", ""), CommandOutput(0, "Unknown", ""),
            CommandOutput(0, "SUSFS\nextra output", ""),
        )) {
            assertNull(SusfsHomeInfoQuery(CommandExecutor { output }).variant())
        }
    }

    @Test fun cancellingVariantQueryIsPropagated() = runBlocking {
        try {
            SusfsHomeInfoQuery(CommandExecutor { throw CancellationException("cancelled") }).variant()
            fail("Cancellation must not be swallowed")
        } catch (_: CancellationException) {
        }
    }

    @Test fun aHomeRefreshRetriesVariantAfterFailureWithoutChangingIntegration() = runBlocking {
        withTimeout(5_000) {
            val integration = MutableStateFlow(state())
            val refresh = MutableStateFlow(0)
            val first = CompletableDeferred<Unit>()
            val recovered = CompletableDeferred<String?>()
            var calls = 0
            val query = SusfsHomeInfoQuery(CommandExecutor {
                calls++
                if (calls == 1) CommandOutput(1, "", "temporarily unavailable")
                else CommandOutput(0, "SUSFS_LKM_MODULES", "")
            })
            val observer = launch {
                susfsHomeSnapshots(integration, refresh).collectLatest { snapshot ->
                    if (snapshot.operational) {
                        val variant = query.variant()
                        if (calls == 1) {
                            assertNull(variant)
                            first.complete(Unit)
                        } else recovered.complete(variant)
                    }
                }
            }
            try {
                first.await()
                refresh.value++
                assertEquals("SUSFS_LKM_MODULES", recovered.await())
                assertTrue(integration.value.susfsIntegrationEnabled)
                assertEquals(2, calls)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    @Test fun anAuthoritativeDisableCancelsAnInflightVariantQuery() = runBlocking {
        withTimeout(5_000) {
            val integration = MutableStateFlow(state())
            val refresh = MutableStateFlow(0)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val disabled = CompletableDeferred<Unit>()
            var result = SusfsHomeInfo()
            val query = SusfsHomeInfoQuery(CommandExecutor {
                started.complete(Unit)
                release.await()
                CommandOutput(0, "stale-variant", "")
            })
            val observer = launch {
                susfsHomeSnapshots(integration, refresh).collectLatest { snapshot ->
                    result = result.merge(snapshot)
                    if (snapshot.operational) {
                        result = result.copy(variant = query.variant())
                    } else disabled.complete(Unit)
                }
            }
            try {
                started.await()
                integration.value = state(enabled = false, supported = false, loaded = false)
                disabled.await()
                release.complete(Unit)
                yield()
                assertEquals(SusfsHomeInfo(), result)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    @Test fun unknownStatusDoesNotQueryMetadataAndObserverCancellationStopsReading() = runBlocking {
        withTimeout(5_000) {
            val integration = MutableStateFlow(KpmUiState())
            val refresh = MutableStateFlow(0)
            val unknown = CompletableDeferred<Unit>()
            val active = CompletableDeferred<Unit>()
            var calls = 0
            val query = SusfsHomeInfoQuery(CommandExecutor {
                calls++
                CommandOutput(0, "SUSFS_LKM_MODULES", "")
            })
            val observer: Job = launch {
                susfsHomeSnapshots(integration, refresh).collectLatest { snapshot ->
                    if (snapshot.operational) {
                        query.variant()
                        active.complete(Unit)
                    } else unknown.complete(Unit)
                }
            }
            try {
                unknown.await()
                assertEquals(0, calls)
                integration.value = state()
                active.await()
                assertEquals(1, calls)
                observer.cancelAndJoin()
                refresh.value++
                integration.value = state(version = "v2.4.0")
                yield()
                assertEquals(1, calls)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }
}
