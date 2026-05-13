package com.vpnbridge.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vpnbridge.client.bridge.BridgeClient
import com.vpnbridge.client.bridge.MultiPoller
import com.vpnbridge.client.proxy.ProxyServer
import com.vpnbridge.client.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VpnBridgeService : Service() {

    companion object {
        const val ACTION_START = "com.vpnbridge.client.START"
        const val ACTION_STOP  = "com.vpnbridge.client.STOP"

        private const val CHANNEL_ID = "vpn_bridge_running"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, VpnBridgeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, VpnBridgeService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridge: BridgeClient? = null
    private var multiPoller: MultiPoller? = null
    private var proxy: ProxyServer? = null
    private var statsJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBridge(reason = null)
                return START_NOT_STICKY
            }
            else -> { /* default = start */ }
        }

        // Already running? Bring foreground notification up to date and return.
        if (proxy != null) {
            startForeground(NOTIF_ID, buildNotification())
            return START_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        scope.launch { startBridge() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopBridge(reason = null)
        super.onDestroy()
    }

    // ─── Lifecycle ────────────────────────────────────────────

    private suspend fun startBridge() = withContext(Dispatchers.IO) {
        ServiceState.update { it.copy(status = ServiceState.Status.CONNECTING, errorMessage = null) }

        val config = try {
            ConfigStore.observe(this@VpnBridgeService).first()
        } catch (_: Exception) { null } ?: run {
            failWith("کانفیگ ذخیره نشده — ابتدا config.json را import کنید")
            return@withContext
        }

        val client = BridgeClient(config)
        bridge = client

        // 1. Health check
        val health = try {
            client.health()
        } catch (e: Exception) {
            LogBuffer.e("Health check failed: ${e.javaClass.simpleName}: ${e.message}")
            failWith("ارتباط با bridge برقرار نشد: ${e.message ?: "نامشخص"}")
            return@withContext
        }
        LogBuffer.i("Bridge OK — version=${health.version}, features=${health.features.joinToString(",")}")

        // 2. MultiPoller (if supported)
        val poller = if (config.useMultiDn && health.supportsMultiDn()) {
            MultiPoller(client).also { it.start(scope) }
        } else {
            if (config.useMultiDn) LogBuffer.w("Bridge does not advertise multi_dn — using legacy /dn")
            null
        }
        multiPoller = poller

        // 3. Local proxy
        val srv = ProxyServer(
            bridge = client,
            multiPoller = poller,
            listenHost = config.listenHost,
            listenPort = config.listenPort
        )
        try {
            srv.start()
        } catch (e: Exception) {
            LogBuffer.e("Could not bind ${config.listenHost}:${config.listenPort}: ${e.message}")
            failWith("نمی‌توان روی ${config.listenHost}:${config.listenPort} bind کرد — پورت اشغال است")
            poller?.stop()
            return@withContext
        }
        proxy = srv

        // Acquire a partial wake lock to reduce chance of being killed mid-poll
        acquireWakeLock()

        ServiceState.update {
            it.copy(
                status = ServiceState.Status.CONNECTED,
                errorMessage = null,
                bridgeVersion = health.version,
                multiDnSupported = health.supportsMultiDn(),
                listenHost = config.listenHost,
                listenPort = config.listenPort,
                startedAtMs = System.currentTimeMillis()
            )
        }
        updateNotification()
        startStatsLoop()
    }

    private fun stopBridge(reason: String?) {
        statsJob?.cancel(); statsJob = null
        try { proxy?.stop() } catch (_: Exception) {}
        proxy = null
        try { multiPoller?.stop() } catch (_: Exception) {}
        multiPoller = null
        try { bridge?.shutdown() } catch (_: Exception) {}
        bridge = null
        releaseWakeLock()
        ServiceState.update {
            it.copy(
                status = if (reason == null) ServiceState.Status.DISCONNECTED else ServiceState.Status.FAILED,
                errorMessage = reason
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failWith(message: String) {
        LogBuffer.e(message)
        stopBridge(reason = message)
    }

    // ─── Stats loop — updates notification every few seconds ──

    private fun startStatsLoop() {
        statsJob = scope.launch {
            while (isActive) {
                val p = proxy ?: break
                ServiceState.update {
                    it.copy(
                        openSessions = p.openSessions.get(),
                        totalSessions = p.totalSessions.get(),
                        failedSessions = p.failedSessions.get()
                    )
                }
                updateNotification()
                delay(3000)
            }
        }
    }

    // ─── Wake lock ────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "vpnbridge:proxy"
            ).apply {
                setReferenceCounted(false)
                acquire(/* max */ 0)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    // ─── Notification ────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "BBK CONNECT",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "اعلان زنده‌بودن سرویس"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val state = ServiceState.state.value
        val title = if (state.isRunning) "اتصال برقرار است" else "در حال آماده‌سازی…"
        val body = if (state.isRunning) {
            "پروکسی روی ${state.listenHost}:${state.listenPort} • " +
                "${state.openSessions} session باز"
        } else {
            "VPN Bridge Client"
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VpnBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try { nm.notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
    }
}
