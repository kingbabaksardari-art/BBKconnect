package com.vpnbridge.client.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vpnbridge.client.BridgeConfig
import com.vpnbridge.client.ConfigStore
import com.vpnbridge.client.LogBuffer
import com.vpnbridge.client.VpnBridgeService
import com.vpnbridge.client.ui.theme.VpnBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importConfigFromUri(uri)
    }

    private val askNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result is non-fatal */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask for POST_NOTIFICATIONS on Android 13+ so the foreground service notification shows.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) askNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Handle an incoming config.json opened from a file manager
        intent?.data?.let { importConfigFromUri(it) }

        setContent {
            VpnBridgeTheme {
                MainScreen(
                    onPickConfigFile = {
                        pickFile.launch(arrayOf("application/json", "*/*"))
                    },
                    onImportConfigText = { text -> importConfigFromText(text) },
                    onStartService = { VpnBridgeService.start(this) },
                    onStopService = { VpnBridgeService.stop(this) },
                    onClearConfig = {
                        lifecycleScope.launch {
                            ConfigStore.clear(this@MainActivity)
                            LogBuffer.i("Config cleared")
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { importConfigFromUri(it) }
    }

    // ─── Config import helpers ────────────────────────────────

    private fun importConfigFromUri(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    LogBuffer.e("خواندن فایل ناموفق: ${e.message}")
                    null
                }
            } ?: return@launch
            importConfigFromText(text)
        }
    }

    private fun importConfigFromText(text: String) {
        lifecycleScope.launch {
            try {
                val cfg = BridgeConfig.parse(text)
                ConfigStore.save(this@MainActivity, cfg)
                LogBuffer.i("کانفیگ import شد: ${cfg.shortDescription()}")
            } catch (e: Exception) {
                LogBuffer.e("کانفیگ نامعتبر: ${e.message}")
            }
        }
    }
}
