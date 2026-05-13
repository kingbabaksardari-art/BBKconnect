package com.vpnbridge.client.bridge

import com.vpnbridge.client.BridgeConfig
import com.vpnbridge.client.LogBuffer
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Low-level HTTP client to the cPanel relay (r.php).
 * Mirrors the endpoints used by the Python client:
 *   GET  /health           -> JSON {version, uptime, active_sessions, features:[]}
 *   POST /connect          -> JSON in: {target:"host:port"}, out: {session_id}
 *   POST /up?s=<sid>       -> raw body, 200 ok / 404|410 closed
 *   GET  /dn?s=<sid>       -> raw bytes, X-Session-Closed: 1 header on close
 *   GET  /multi_dn?s=a,b,c&t=15000 -> JSON {results:[{sid, data(b64), closed}]}
 *   POST /close?s=<sid>    -> best-effort
 *
 * All requests carry:
 *   X-Bridge-Auth:    <token>
 *   Accept-Encoding:  identity   (defeat LiteSpeed auto-gzip)
 */
class BridgeClient(val config: BridgeConfig) {

    val baseUrl: String = config.bridgeUrl.trimEnd('/')
    private val authHeader = config.bridgeToken
    private val rawMediaType = "application/octet-stream".toMediaTypeOrNull()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

    val http: OkHttpClient = buildClient(config)

    // ─── Public API ────────────────────────────────────────────

    /** GET /health. Returns parsed response or throws. */
    fun health(): HealthResult {
        val req = baseRequest("$baseUrl/health").get().build()
        http.newCall(req).execute().use { resp ->
            val body = readBodyBytes(resp)
            if (!resp.isSuccessful) {
                throw BridgeException("HTTP ${resp.code} on /health: ${body.decodeToString().take(200)}")
            }
            val text = body.decodeToString()
            if (!text.contains("active_sessions")) {
                throw BridgeException("پاسخ /health معتبر نیست: ${text.take(200)}")
            }
            val o = JSONObject(text)
            val features = mutableListOf<String>()
            o.optJSONArray("features")?.let { arr ->
                for (i in 0 until arr.length()) features.add(arr.getString(i))
            }
            return HealthResult(
                version = o.optString("version", "?"),
                uptimeSec = o.optLong("uptime", 0),
                activeSessions = o.optInt("active_sessions", 0),
                features = features
            )
        }
    }

    /** POST /connect — returns the new session id, or null on failure. */
    fun connectSession(target: String): String? {
        val body = JSONObject().put("target", target).toString()
            .toRequestBody(jsonMediaType)
        val req = baseRequest("$baseUrl/connect").post(body).build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogBuffer.w("connect $target: HTTP ${resp.code}")
                    return null
                }
                val text = readBodyBytes(resp).decodeToString()
                val sid = JSONObject(text).optString("session_id", "")
                if (sid.isEmpty()) null else sid
            }
        } catch (e: Exception) {
            LogBuffer.w("connect $target: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** POST /up?s=<sid> — returns Pair(ok, closedByServer). */
    fun uploadBytes(sessionId: String, data: ByteArray): UploadResult {
        val req = baseRequest("$baseUrl/up?s=$sessionId")
            .post(data.toRequestBody(rawMediaType, 0, data.size))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> UploadResult.OK
                    404, 410 -> UploadResult.CLOSED
                    else -> UploadResult.ERROR
                }
            }
        } catch (e: Exception) {
            UploadResult.ERROR
        }
    }

    /** GET /dn?s=<sid> — legacy long-poll, returns bytes (possibly empty) and a closed flag. */
    fun downloadOnce(sessionId: String): DownloadResult {
        val req = baseRequest("$baseUrl/dn?s=$sessionId").get().build()
        return try {
            http.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val data = readBodyBytes(resp)
                        val closed = resp.header("X-Session-Closed") == "1"
                        DownloadResult(data = data, closed = closed, error = false)
                    }
                    404, 410 -> DownloadResult(data = ByteArray(0), closed = true, error = false)
                    else -> DownloadResult(data = ByteArray(0), closed = false, error = true)
                }
            }
        } catch (e: Exception) {
            DownloadResult(data = ByteArray(0), closed = false, error = true)
        }
    }

    /** GET /multi_dn?s=sid1,sid2,...&t=<wait_ms> */
    fun multiDownload(sids: List<String>, waitMs: Int): MultiDnResult {
        if (sids.isEmpty()) return MultiDnResult.empty()
        val url = "$baseUrl/multi_dn?s=${sids.joinToString(",")}&t=$waitMs"
        val req = baseRequest(url).get().build()
        try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return MultiDnResult.notSupported()
                if (!resp.isSuccessful) return MultiDnResult.empty()
                val raw = readBodyBytes(resp)
                val payload = try {
                    JSONObject(raw.decodeToString())
                } catch (e: Exception) {
                    LogBuffer.w("multi_dn parse: ${e.message}")
                    return MultiDnResult.empty()
                }
                val results = payload.optJSONArray("results") ?: return MultiDnResult.empty()
                val out = ArrayList<MultiDnEntry>(results.length())
                for (i in 0 until results.length()) {
                    val e = results.getJSONObject(i)
                    val sid = e.optString("sid", "")
                    if (sid.isEmpty()) continue
                    val dataB64 = e.optString("data", "")
                    val data = if (dataB64.isNotEmpty()) {
                        try {
                            android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)
                        } catch (_: Exception) { ByteArray(0) }
                    } else ByteArray(0)
                    out.add(MultiDnEntry(sid, data, e.optBoolean("closed", false)))
                }
                return MultiDnResult(entries = out, supported = true)
            }
        } catch (e: Exception) {
            return MultiDnResult.empty()
        }
    }

    /** POST /close?s=<sid> — fire-and-forget. */
    fun closeSession(sessionId: String) {
        try {
            val empty = ByteArray(0).toRequestBody(rawMediaType)
            val req = baseRequest("$baseUrl/close?s=$sessionId").post(empty).build()
            http.newCall(req).execute().close()
        } catch (_: Exception) { /* best-effort */ }
    }

    fun shutdown() {
        try { http.dispatcher.executorService.shutdownNow() } catch (_: Exception) {}
        try { http.connectionPool.evictAll() } catch (_: Exception) {}
    }

    // ─── Internals ─────────────────────────────────────────────

    private fun baseRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("X-Bridge-Auth", authHeader)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "VPNBridgeClient-Android/1.0")

    /**
     * Read the response body and defensively decompress if LiteSpeed sent gzip
     * despite Accept-Encoding: identity. (See Python client comments.)
     */
    private fun readBodyBytes(resp: Response): ByteArray {
        val raw = resp.body?.bytes() ?: return ByteArray(0)
        if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            return try {
                GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            } catch (_: Exception) { raw }
        }
        return raw
    }

    private fun buildClient(cfg: BridgeConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Read timeout must be larger than multi_dn wait_ms to avoid the
            // long-poll being killed by the client side.
            .readTimeout((cfg.multiDnWaitMs + 15_000).toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // no overall cap
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = cfg.connectionLimitPerHost.coerceAtLeast(5),
                    keepAliveDuration = cfg.keepaliveTimeoutSec.toLong(),
                    timeUnit = TimeUnit.SECONDS
                )
            )
            // OkHttp does transparent gzip when no Accept-Encoding is set; we always set
            // identity, so this is moot. Keep retries off (server may not be idempotent
            // for /up which has body).
            .retryOnConnectionFailure(false)

        if (!cfg.verifySsl) {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trust), SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory as SSLSocketFactory, trust)
            builder.hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }
}

class BridgeException(msg: String) : Exception(msg)

data class HealthResult(
    val version: String,
    val uptimeSec: Long,
    val activeSessions: Int,
    val features: List<String>
) {
    fun supportsMultiDn(): Boolean = "multi_dn" in features
}

enum class UploadResult { OK, CLOSED, ERROR }

data class DownloadResult(val data: ByteArray, val closed: Boolean, val error: Boolean) {
    override fun equals(other: Any?): Boolean { return super.equals(other) }
    override fun hashCode(): Int { return super.hashCode() }
}

data class MultiDnEntry(val sid: String, val data: ByteArray, val closed: Boolean) {
    override fun equals(other: Any?): Boolean { return super.equals(other) }
    override fun hashCode(): Int { return super.hashCode() }
}

data class MultiDnResult(
    val entries: List<MultiDnEntry>,
    val supported: Boolean = true
) {
    companion object {
        fun empty() = MultiDnResult(emptyList(), supported = true)
        fun notSupported() = MultiDnResult(emptyList(), supported = false)
    }
}
