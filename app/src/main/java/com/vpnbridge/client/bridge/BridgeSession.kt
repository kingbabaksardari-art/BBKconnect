package com.vpnbridge.client.bridge

import com.vpnbridge.client.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One TCP tunnel through the bridge. Mirrors the Python BridgeSession.
 *
 * - Upstream: each [sendUpstream] call POSTs to /up?s=<sid>.
 * - Downstream: data arrives either via [pushFromMultiPoller] (when the
 *   MultiPoller is active) or via [legacyPollOnce] in a loop.
 *
 * Use [downstream] to read bytes; channel is closed (returns null on receiveCatching)
 * when the session ends.
 */
class BridgeSession(
    val target: String,
    private val bridge: BridgeClient,
    private val multiPoller: MultiPoller? = null
) {
    var sessionId: String? = null
        private set

    private val closedFlag = AtomicBoolean(false)
    val isClosed: Boolean get() = closedFlag.get()

    val bytesUp = AtomicLong(0)
    val bytesDown = AtomicLong(0)
    val startedAt: Long = System.currentTimeMillis()

    // Buffer up to 64 chunks downstream. Backpressure suspends the producer
    // (MultiPoller or legacy poller) when the local socket is slow to drain.
    private val recvChannel = Channel<ByteArray>(capacity = 64)
    private val sendLock = Mutex()

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val sid = bridge.connectSession(target) ?: return@withContext false
        sessionId = sid
        multiPoller?.register(this@BridgeSession)
        true
    }

    /**
     * Send [data] to the remote target via POST /up. Marked closed if server
     * reports 404/410. Returns false on error.
     */
    suspend fun sendUpstream(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val sid = sessionId ?: return@withContext false
        if (closedFlag.get()) return@withContext false
        // Serialize uploads on a single session — preserves byte ordering
        // (multiple concurrent POSTs to /up?s= could be reordered by LiteSpeed).
        sendLock.withLock {
            val res = bridge.uploadBytes(sid, data)
            when (res) {
                UploadResult.OK -> {
                    bytesUp.addAndGet(data.size.toLong())
                    true
                }
                UploadResult.CLOSED -> {
                    closedFlag.set(true)
                    false
                }
                UploadResult.ERROR -> false
            }
        }
    }

    /**
     * Suspend until a chunk arrives or the session closes. Returns null on close,
     * empty ByteArray on idle tick (caller should loop).
     */
    suspend fun receiveDownstream(): ByteArray? {
        val r: ChannelResult<ByteArray> = recvChannel.receiveCatching()
        return when {
            r.isClosed -> null
            r.isFailure -> null
            else -> r.getOrThrow()
        }
    }

    /** Called by MultiPoller to deliver data into the queue. */
    suspend fun pushFromMultiPoller(data: ByteArray) {
        if (data.isNotEmpty()) {
            bytesDown.addAndGet(data.size.toLong())
            try {
                recvChannel.send(data)
            } catch (_: Exception) { /* closed */ }
        }
    }

    /** Called by MultiPoller when the server signals this session is closed. */
    fun markClosedFromUpstream() {
        closedFlag.set(true)
        recvChannel.close()
    }

    /**
     * Run a legacy /dn polling loop in [scope]. Used only when the bridge
     * does not support multi_dn.
     */
    fun startLegacyPolling(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var errors = 0
            while (!closedFlag.get()) {
                val sid = sessionId ?: break
                val r = bridge.downloadOnce(sid)
                if (r.error) {
                    errors++
                    if (errors >= 5) {
                        closedFlag.set(true)
                        break
                    }
                    delay((errors * 500L).coerceAtMost(3000L))
                    continue
                }
                errors = 0
                if (r.data.isNotEmpty()) {
                    bytesDown.addAndGet(r.data.size.toLong())
                    try { recvChannel.send(r.data) } catch (_: Exception) { break }
                }
                if (r.closed) {
                    closedFlag.set(true)
                    break
                }
            }
            recvChannel.close()
        }
    }

    suspend fun closeSession() = withContext(Dispatchers.IO) {
        if (closedFlag.getAndSet(true)) {
            recvChannel.close()
            return@withContext
        }
        recvChannel.close()
        val sid = sessionId
        if (sid != null) {
            multiPoller?.unregister(sid)
            bridge.closeSession(sid)
        }
        if (bridge.config.verboseLogging) {
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
            LogBuffer.d("CLOSE $target up=${bytesUp.get()}B dn=${bytesDown.get()}B t=${"%.1f".format(elapsed)}s")
        }
    }
}
