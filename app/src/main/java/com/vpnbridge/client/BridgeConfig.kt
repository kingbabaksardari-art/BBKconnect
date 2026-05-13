package com.vpnbridge.client

import org.json.JSONObject

/**
 * Mirror of the Python client config.json schema.
 * Field names are kept identical for drop-in compatibility — the same
 * config.json downloaded from the cPanel admin panel can be imported here.
 */
data class BridgeConfig(
    val listenHost: String = "127.0.0.1",
    val listenPort: Int = 8080,
    val bridgeUrl: String,
    val bridgeToken: String,
    val userToken: String = "",
    val verifySsl: Boolean = true,
    val verboseLogging: Boolean = false,
    val useMultiDn: Boolean = true,
    val multiDnWaitMs: Int = 15000,
    val multiDnBatch: Int = 32,
    val connectionLimitPerHost: Int = 30,
    val forceCloseConnections: Boolean = true,
    val keepaliveTimeoutSec: Int = 4
) {
    fun toJsonString(pretty: Boolean = true): String {
        val o = JSONObject()
        o.put("listen_host", listenHost)
        o.put("listen_port", listenPort)
        o.put("bridge_url", bridgeUrl)
        o.put("bridge_token", bridgeToken)
        o.put("user_token", userToken)
        o.put("verify_ssl", verifySsl)
        o.put("verbose_logging", verboseLogging)
        o.put("use_multi_dn", useMultiDn)
        o.put("multi_dn_wait_ms", multiDnWaitMs)
        o.put("multi_dn_batch", multiDnBatch)
        o.put("connection_limit_per_host", connectionLimitPerHost)
        o.put("force_close_connections", forceCloseConnections)
        o.put("keepalive_timeout", keepaliveTimeoutSec)
        return if (pretty) o.toString(2) else o.toString()
    }

    /** Compact display: "host (token: abc...xyz)" */
    fun shortDescription(): String {
        val host = try {
            java.net.URI(bridgeUrl).host ?: bridgeUrl
        } catch (_: Exception) { bridgeUrl }
        val t = bridgeToken
        val masked = if (t.length > 10) "${t.take(6)}…${t.takeLast(4)}" else t
        return "$host  •  $masked"
    }

    companion object {
        /**
         * Parse a config.json. Throws [ConfigException] with a Persian message
         * when something is wrong so the UI can display it directly.
         */
        @Throws(ConfigException::class)
        fun parse(jsonText: String): BridgeConfig {
            val o = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                throw ConfigException("فرمت JSON معتبر نیست: ${e.message}")
            }

            val bridgeUrl = o.optString("bridge_url", "").trim()
            if (bridgeUrl.isEmpty()) throw ConfigException("فیلد bridge_url خالی است")
            if (!bridgeUrl.startsWith("https://")) {
                throw ConfigException("bridge_url باید با https:// شروع شود")
            }

            val bridgeToken = o.optString("bridge_token", "").trim()
            if (bridgeToken.isEmpty() || bridgeToken == "CHANGE_ME") {
                throw ConfigException("bridge_token در فایل کانفیگ تنظیم نشده است")
            }

            return BridgeConfig(
                listenHost = o.optString("listen_host", "127.0.0.1"),
                listenPort = o.optInt("listen_port", 8080),
                bridgeUrl = bridgeUrl,
                bridgeToken = bridgeToken,
                userToken = o.optString("user_token", ""),
                verifySsl = o.optBoolean("verify_ssl", true),
                verboseLogging = o.optBoolean("verbose_logging", false),
                useMultiDn = o.optBoolean("use_multi_dn", true),
                multiDnWaitMs = o.optInt("multi_dn_wait_ms", 15000)
                    .coerceIn(1000, 30000),
                multiDnBatch = o.optInt("multi_dn_batch", 32).coerceIn(1, 64),
                connectionLimitPerHost = o.optInt("connection_limit_per_host", 30),
                forceCloseConnections = o.optBoolean("force_close_connections", true),
                keepaliveTimeoutSec = o.optInt("keepalive_timeout", 4)
            )
        }
    }
}

class ConfigException(message: String) : Exception(message)
