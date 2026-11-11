package com.anve.app.data.diagnostics

import android.content.Context
import android.os.PowerManager
import androidx.work.WorkManager
import com.anve.app.data.location.LocationQueueRepository
import com.anve.app.data.location.WorkManagerLocationSyncScheduler
import com.anve.app.data.sos.SosDataSource
import com.anve.app.data.sos.SosLocalState
import com.anve.app.data.sos.SosOutbox
import com.anve.app.tracking.diagnostics.DiagnosticsDetails
import com.anve.app.tracking.diagnostics.SyncWorkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

fun interface DiagnosticsSource {
    suspend fun load(owner: String): DiagnosticsDetails
    suspend fun loadLocal(owner: String): DiagnosticsDetails = load(owner)
    suspend fun loadRemote(owner: String, local: DiagnosticsDetails): DiagnosticsDetails = local
}

class AndroidDiagnosticsSource(
    context: Context,
    private val queue: LocationQueueRepository,
    private val outbox: SosOutbox,
    private val sos: SosDataSource,
    private val hasCheckpoint: (String) -> Boolean,
    private val isOnline: () -> Boolean = { true },
) : DiagnosticsSource {
    private val appContext = context.applicationContext

    override suspend fun load(owner: String): DiagnosticsDetails = loadRemote(owner, loadLocal(owner))

    override suspend fun loadLocal(owner: String): DiagnosticsDetails = coroutineScope {
        val queueRead = async { optional { queue.diagnostics(owner) } }
        val sosRead = async {
            optional {
                outbox.read(owner).count {
                    it.state in setOf(SosLocalState.QUEUED, SosLocalState.SENDING, SosLocalState.UNKNOWN, SosLocalState.CANCEL_REQUESTED)
                }
            }
        }
        val work = async {
            optional {
                withContext(Dispatchers.IO) {
                    WorkManager.getInstance(appContext)
                        .getWorkInfosForUniqueWork(WorkManagerLocationSyncScheduler.WORK_NAME).get()
                        .filter { !it.state.isFinished }
                        .map { SyncWorkStatus.valueOf(it.state.name) }.distinct()
                }
            }
        }
        DiagnosticsDetails(
            queue = queueRead.await(),
            captureCheckpoint = hasCheckpoint(owner),
            batteryOptimizationExempt = optional {
                appContext.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(appContext.packageName)
            },
            syncWork = work.await(),
            sosPending = sosRead.await(),
        )
    }

    override suspend fun loadRemote(owner: String, local: DiagnosticsDetails): DiagnosticsDetails {
        if (!isOnline()) return local
        return coroutineScope {
            val registered = async { optional { sos.pushRegistered(owner) } }
            val configured = async { optional { sos.pushConfigured(owner) } }
            local.copy(pushRegistered = registered.await(), pushConfigured = configured.await())
        }
    }
}

private suspend fun <T> optional(read: suspend () -> T): T? = try {
    read()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    // An unreadable subsystem is unknown, never a fabricated empty/healthy state.
    null
}
