package com.vpnbridge.client.proxy

import com.vpnbridge.client.LogBuffer
import com.vpnbridge.client.bridge.BridgeClient
import com.vpnbridge.client.bridge.BridgeSession
import com.vpnbridge.client.bridge.MultiPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * Local HTTP/HTTPS proxy on listenHost:listenPort.
 *
 * Each accepted connection is handled as a single proxy request, mirroring
 * the simple per-request lifecycle of the Python reference client:
 *  - CONNECT host:port  -> open BridgeSession, reply 200, splice both ways
 *  - GET/POST <abs-url> -> open BridgeSession, rewrite request line, splice
 *
 * Connections close after one request; no proxy-side HTTP keep-alive.
 */
class ProxyServer(
    private val bridge: BridgeClient,
    private val multiPoller: MultiPoller?,
    private val listenHost: String,
    private val listenPort: Int
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val openSessions = AtomicLong(0)
    val totalSessions = AtomicLong(0)
    val failedSessions = AtomicLong(0)

    fun start() {
        if (serverSocket != null) return
        val bindAddr = InetAddress.getByName(listenHost)
        // backlog 50: each backlogged client only waits long enough to read a request line
        serverSocket = ServerSocket(listenPort, 50, bindAddr)
        LogBuffer.i("Local proxy listening on $listenHost:$listenPort")
        acceptJob = scope.launch {
            val s = serverSocket ?: return@launch
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: IOException) { break }
                scope.launch { handleClient(client) }
            }
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        scope.cancel()
        LogBuffer.i("Local proxy stopped")
    }

    // ─── Per-connection handler ────────────────────────────────

    private suspend fun handleClient(client: Socket) {
        var session: BridgeSession? = null
        // Timeout only for reading the request line + headers. Tunnels themselves
        // need no idle limit (long-lived TCP, e.g. WebSocket-ish or terminal traffic).
        client.soTimeout = 10_000
        try {
            client.tcpNoDelay = true
            val rawIn = client.getInputStream()
            val rawOut = client.getOutputStream()

            // Parse request line + headers using a small reader. We must not
            // consume more bytes than the request itself because we may need
            // to splice raw bytes after the headers (for CONNECT tunnels and
            // HTTP body forwarding).
            val (requestLine, headers, remainder) = readHttpHead(rawIn)

            // Tunneling mode — disable read timeout for the piping phase
            client.soTimeout = 0

            if (requestLine.isEmpty()) {
                writeStatus(rawOut, 400, "Bad Request")
                return
            }

            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 3) {
                writeStatus(rawOut, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            val httpVersion = parts[2]

            when {
                method == "CONNECT" -> {
                    session = handleConnect(target, rawIn, rawOut, remainder)
                }
                target.startsWith("http://") || target.startsWith("https://") -> {
                    session = handleAbsoluteUrl(
                        method = method,
                        absoluteUrl = target,
                        httpVersion = httpVersion,
                        headers = headers,
                        rawIn = rawIn,
                        rawOut = rawOut,
                        bodyPrefix = remainder
                    )
                }
                else -> {
                    writeStatus(rawOut, 400, "Bad Request")
                }
            }
        } catch (e: Exception) {
            LogBuffer.d("handler error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { session?.closeSession() } catch (_: Exception) {}
            if (session != null) openSessions.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * CONNECT host:port — open tunnel and splice both directions.
     */
    private suspend fun handleConnect(
        target: String,
        rawIn: java.io.InputStream,
        rawOut: OutputStream,
        bodyPrefix: ByteArray
    ): BridgeSession? {
        if (':' !in target) {
            writeStatus(rawOut, 400, "Bad Request")
            return null
        }
        val (host, portStr) = target.substringBeforeLast(':') to target.substringAfterLast(':')
        val port = portStr.toIntOrNull()
        if (port == null) {
            writeStatus(rawOut, 400, "Bad Request")
            return null
        }

        val session = BridgeSession("$host:$port", bridge, multiPoller)
        if (!session.connect()) {
            failedSessions.incrementAndGet()
            writeStatus(rawOut, 502, "Bad Gateway")
            return null
        }
        totalSessions.incrementAndGet()
        openSessions.incrementAndGet()

        // Reply tunnel established
        rawOut.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
        rawOut.flush()
        LogBuffer.d("TUNNEL $host:$port  sid=${session.sessionId?.take(8)}")

        // If MultiPoller is in fallback mode, run a legacy /dn loop for this session
        if (multiPoller == null || multiPoller.isInFallback()) {
            session.startLegacyPolling(scope)
        }

        // Splice: any bytes already buffered after the headers go upstream first.
        if (bodyPrefix.isNotEmpty()) {
            session.sendUpstream(bodyPrefix)
        }

        coroutineScopeJoin(
            uploaderJob = scope.launch { pumpUpstream(rawIn, session) },
            downloaderJob = scope.launch { pumpDownstream(session, rawOut) }
        )
        return session
    }

    /**
     * GET/POST http(s)://host:port/path — relative-rewrite and forward.
     */
    private suspend fun handleAbsoluteUrl(
        method: String,
        absoluteUrl: String,
        httpVersion: String,
        headers: List<String>,
        rawIn: java.io.InputStream,
        rawOut: OutputStream,
        bodyPrefix: ByteArray
    ): BridgeSession? {
        val uri = try { URI(absoluteUrl) } catch (_: Exception) {
            writeStatus(rawOut, 400, "Bad Request")
            return null
        }
        val host = uri.host ?: run {
            writeStatus(rawOut, 400, "Bad Request")
            return null
        }
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80

        val session = BridgeSession("$host:$port", bridge, multiPoller)
        if (!session.connect()) {
            failedSessions.incrementAndGet()
            writeStatus(rawOut, 502, "Bad Gateway")
            return null
        }
        totalSessions.incrementAndGet()
        openSessions.incrementAndGet()
        if (multiPoller == null || multiPoller.isInFallback()) {
            session.startLegacyPolling(scope)
        }

        // Rebuild request: path + query, strip proxy-specific headers
        val newPath = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            if (!uri.rawQuery.isNullOrEmpty()) append('?').append(uri.rawQuery)
        }
        val filteredHeaders = headers.filterNot {
            val lower = it.lowercase()
            lower.startsWith("proxy-connection:") || lower.startsWith("proxy-authorization:")
        }
        val rebuilt = buildString {
            append(method).append(' ').append(newPath).append(' ').append(httpVersion).append("\r\n")
            for (h in filteredHeaders) { append(h).append("\r\n") }
            append("\r\n")
        }
        session.sendUpstream(rebuilt.toByteArray(Charsets.ISO_8859_1))
        if (bodyPrefix.isNotEmpty()) session.sendUpstream(bodyPrefix)

        LogBuffer.d("HTTP $method $host:$port$newPath")

        coroutineScopeJoin(
            uploaderJob = scope.launch { pumpUpstream(rawIn, session) },
            downloaderJob = scope.launch { pumpDownstream(session, rawOut) }
        )
        return session
    }

    // ─── Pipes ─────────────────────────────────────────────────

    private suspend fun pumpUpstream(rawIn: java.io.InputStream, session: BridgeSession) {
        val buf = ByteArray(32 * 1024)
        try {
            while (!session.isClosed) {
                val n = withContext(Dispatchers.IO) {
                    try { rawIn.read(buf) } catch (_: Exception) { -1 }
                }
                if (n <= 0) break
                val ok = session.sendUpstream(buf.copyOf(n))
                if (!ok) break
            }
        } catch (_: Exception) {
            // socket dropped
        }
        // Half-close: tell the bridge no more upstream data is coming, but keep
        // reading downstream until server closes.
        // (Simplest: close the session entirely.)
        session.markClosedFromUpstream()
    }

    private suspend fun pumpDownstream(session: BridgeSession, rawOut: OutputStream) {
        try {
            while (!session.isClosed) {
                val chunk = session.receiveDownstream() ?: break
                if (chunk.isEmpty()) continue
                withContext(Dispatchers.IO) {
                    try {
                        rawOut.write(chunk)
                        rawOut.flush()
                    } catch (_: Exception) {
                        session.markClosedFromUpstream()
                    }
                }
            }
        } catch (_: Exception) { /* socket dropped */ }
    }

    private suspend fun coroutineScopeJoin(uploaderJob: Job, downloaderJob: Job) {
        // Wait for either side to finish; then the session's close in finally cleans up.
        try {
            // Race the two: when one ends, the other will typically end soon after
            // because both observe session.isClosed via channel close.
            uploaderJob.join()
            // Give the downloader up to 2s to drain remaining bytes after upload ends.
            withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < deadline && downloaderJob.isActive) {
                    delay(50)
                }
            }
            downloaderJob.cancel()
        } catch (_: Exception) {
            uploaderJob.cancel(); downloaderJob.cancel()
        }
    }

    // ─── HTTP head parsing ─────────────────────────────────────

    /**
     * Reads the request line and headers from [input] without consuming the body.
     * Returns Triple(requestLine, headerLines, remainder) where [remainder] holds
     * bytes that were buffered by the BufferedReader's lookahead but belong to
     * the body — those must be forwarded upstream before reading more.
     *
     * Note: BufferedReader's char-based read may grab body bytes after \r\n\r\n.
     * We avoid that by reading byte-by-byte from a raw stream until we hit the
     * header terminator. Slower, but simple and correct.
     */
    private fun readHttpHead(input: java.io.InputStream): Triple<String, List<String>, ByteArray> {
        val headerBytes = ArrayList<Byte>(1024)
        var b: Int
        var consecutiveLF = 0  // count of \n at end (\r are ignored for state)
        while (true) {
            b = input.read()
            if (b < 0) {
                if (headerBytes.isEmpty()) return Triple("", emptyList(), ByteArray(0))
                break
            }
            headerBytes.add(b.toByte())
            when (b) {
                '\n'.code -> {
                    consecutiveLF++
                    if (consecutiveLF >= 2) break
                }
                '\r'.code -> { /* ignore for state */ }
                else -> consecutiveLF = 0
            }
            if (headerBytes.size > 64 * 1024) {
                // Refuse pathologically large headers
                break
            }
        }
        val text = ByteArray(headerBytes.size) { headerBytes[it] }.toString(Charsets.ISO_8859_1)
        val lines = text.split("\r\n", "\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Triple("", emptyList(), ByteArray(0))
        val requestLine = lines[0]
        val headers = if (lines.size > 1) lines.subList(1, lines.size) else emptyList()
        // No body bytes were over-read since we go one byte at a time.
        return Triple(requestLine, headers, ByteArray(0))
    }

    private fun writeStatus(out: OutputStream, code: Int, reason: String) {
        try {
            out.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        } catch (_: Exception) {}
    }
}
