package com.vpnbridge.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnbridge.client.ConfigStore
import com.vpnbridge.client.LogBuffer
import com.vpnbridge.client.ServiceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onPickConfigFile: () -> Unit,
    onImportConfigText: (String) -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onClearConfig: () -> Unit,
) {
    val context = LocalContext.current
    val config by ConfigStore.observe(context).collectAsState(initial = null)
    val state by ServiceState.state.collectAsStateWithLifecycle()
    val logs by LogBuffer.entries.collectAsStateWithLifecycle()

    var showPasteDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BBK CONNECT", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(state = state, hasConfig = config != null)

            ConfigCard(
                config = config,
                onPickFile = onPickConfigFile,
                onPaste = { showPasteDialog = true },
                onClear = { showClearDialog = true }
            )

            ConnectionButtons(
                hasConfig = config != null,
                state = state,
                onStart = onStartService,
                onStop = onStopService
            )

            if (state.isRunning) ProxyHowToCard(state.listenHost, state.listenPort)

            LogsCard(logs)
            FooterTip()
        }
    }

    if (showPasteDialog) {
        PasteConfigDialog(
            onDismiss = { showPasteDialog = false },
            onConfirm = { text ->
                showPasteDialog = false
                onImportConfigText(text)
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("حذف کانفیگ") },
            text = { Text("کانفیگ ذخیره‌شده پاک شود؟") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onClearConfig()
                }) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("انصراف") }
            }
        )
    }
}

@Composable
private fun StatusCard(state: ServiceState.Snapshot, hasConfig: Boolean) {
    val color = when (state.status) {
        ServiceState.Status.CONNECTED -> MaterialTheme.colorScheme.primary
        ServiceState.Status.CONNECTING -> MaterialTheme.colorScheme.secondary
        ServiceState.Status.FAILED -> MaterialTheme.colorScheme.error
        ServiceState.Status.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    val title = when (state.status) {
        ServiceState.Status.CONNECTED -> "متصل"
        ServiceState.Status.CONNECTING -> "در حال اتصال…"
        ServiceState.Status.FAILED -> "ناموفق"
        ServiceState.Status.DISCONNECTED -> if (hasConfig) "آماده" else "بدون کانفیگ"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color)
                )
                Spacer(Modifier.size(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (state.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (state.errorMessage != null) {
                Text(state.errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            if (state.isRunning) {
                Text(
                    "بریج: ${state.bridgeVersion ?: "?"} · " +
                        (if (state.multiDnSupported) "multi_dn" else "legacy /dn"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Session باز: ${state.openSessions}  ·  مجموع: ${state.totalSessions}  ·  ناموفق: ${state.failedSessions}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ConfigCard(
    config: com.vpnbridge.client.BridgeConfig?,
    onPickFile: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("کانفیگ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (config == null) {
                Text(
                    "هنوز کانفیگی import نکرده‌اید. فایل config.json را که از پنل cPanel دانلود کردید انتخاب کنید، یا متن JSON را paste کنید.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(config.shortDescription(), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFile, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("از فایل")
                }
                OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Paste JSON")
                }
                if (config != null) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Delete, "حذف", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionButtons(
    hasConfig: Boolean,
    state: ServiceState.Snapshot,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (!state.isRunning) {
            Button(
                onClick = onStart,
                enabled = hasConfig && !state.isBusy,
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.size(6.dp))
                Text("اتصال", fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.size(6.dp))
                Text("قطع اتصال", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ProxyHowToCard(host: String, port: Int) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("پروکسی روی این آدرس بالاست:", style = MaterialTheme.typography.titleSmall)
            Text(
                "$host:$port",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "برای routing کل ترافیک سیستم، NekoBox یا Surfboard را نصب کنید و یک outbound از نوع HTTP با همین host:port بسازید — یا روی WiFi → Modify → Proxy تنظیم کنید.",
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun LogsCard(entries: List<LogBuffer.Entry>) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("لاگ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { LogBuffer.clear() }) { Text("پاک‌سازی") }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .padding(6.dp)
            ) {
                if (entries.isEmpty()) {
                    Text(
                        "هنوز چیزی نیست…",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 13.sp
                    )
                } else {
                    LazyColumn(state = listState, contentPadding = PaddingValues(2.dp)) {
                        items(entries) { e ->
                            val color = when (e.level) {
                                LogBuffer.Level.ERROR -> MaterialTheme.colorScheme.error
                                LogBuffer.Level.WARN -> Color(0xFFE65100)
                                LogBuffer.Level.INFO -> MaterialTheme.colorScheme.onSurface
                                LogBuffer.Level.DEBUG -> MaterialTheme.colorScheme.outline
                            }
                            Text(
                                "${e.time}  ${e.message}",
                                color = color,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FooterTip() {
    Text(
        "این اپ یک HTTP proxy لوکال می‌سازد. برای استفاده در همه‌ی اپ‌ها به یک ابزار TUN نیاز دارید (مثلاً NekoBox). برای فقط مرورگر، Firefox + FoxyProxy کافی است.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasteConfigDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste JSON") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                placeholder = { Text("{ \"bridge_url\": \"https://…/v/r.php\", \"bridge_token\": \"…\" }") },
                keyboardOptions = KeyboardOptions.Default,
                singleLine = false
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text) }
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}
