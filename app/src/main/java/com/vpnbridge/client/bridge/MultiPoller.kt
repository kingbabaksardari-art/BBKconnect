package com.vpnbridge.client.bridge

import com.vpnbridge.client.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background coroutine that batches /multi_dn polls for all live sessions.
 *
 * Mirrors the Python MultiPoller:
 *   - rotates through sessions when count > batch size (round-robin)
 *   - falls back to legacy /dn if the bridge does not advertise multi_dn
 *
 * Lifecycle:
 *   poller.start(scope)
 *   poller.register(session)  // when session connects
 *   poller.unregister(sid)    // when session closes
 *   poller.stop()
 */
class MultiPoller(private val bridge: BridgeClient) {

    private val batchSize = bridge.config.multiDnBatch.coerceIn(1, 64)
    private val waitMs = bridge.config.multiDnWaitMs.coerceIn(1000, 30_000)

    private val sessions = ConcurrentHashMap<String, BridgeSession>()
    private var job: Job? = null
    private val shutdown = AtomicBoolean(false)
    private var cycle = 0L

    /** When the bridge responds 404 on /multi_dn, we disable batched mode for
     *  the remainder of this client lifetime and let sessions use legacy. */
    private val fallback = AtomicBoolean(false)
    fun isInFallback(): Boolean = fallback.get()

    fun register(session: BridgeSession) {
        val sid = session.sessionId ?: return
        sessions[sid] = session
    }

    fun unregister(sid: String) { sessions.remove(sid) }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        LogBuffer.i("MultiPoller starting (batch<=$batchSize, wait=${waitMs}ms)")
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !shutdown.get()) {
                try {
                    cycleOnce()
                } catch (e: Exception) {
                    if (bridge.config.verboseLogging) {
                        LogBuffer.d("multi-poll cycle err: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    delay(500)
                }
            }
        }
    }

    fun stop() {
        shutdown.set(true)
        job?.cancel()
        job = null
        sessions.clear()
    }

    private suspend fun cycleOnce() = withContext(Dispatchers.IO) {
        if (fallback.get()) {
            delay(500)
            return@withContext
        }

        // Snapshot live sessions
        val live = sessions.entries
            .filter { !it.value.isClosed }
            .map { it.key }

        if (live.isEmpty()) {
            delay(200)
            return@withContext
        }

        // Round-robin so no session starves when count > batch
        cycle++
        val batch: List<String> = if (live.size > batchSize) {
            val offset = ((cycle * batchSize) % live.size).toInt()
            val rotated = live.drop(offset) + live.take(offset)
            rotated.take(batchSize)
        } else live

        val result = bridge.multiDownload(batch, waitMs)

        if (!result.supported) {
            LogBuffer.w("Bridge does not support /multi_dn — falling back to legacy /dn polling")
            fallback.set(true)
            // Sessions will be picked up by the legacy poller; we just stop touching them.
            return@withContext
        }

        for (entry in result.entries) {
            val s = sessions[entry.sid] ?: continue
            if (entry.data.isNotEmpty()) {
                s.pushFromMultiPoller(entry.data)
            }
            if (entry.closed) {
                s.markClosedFromUpstream()
                sessions.remove(entry.sid)
            }
        }
    }
}
