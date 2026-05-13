package com.vpnbridge.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Global, observable state of the VPN bridge service. */
object ServiceState {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    data class Snapshot(
        val status: Status = Status.DISCONNECTED,
        val errorMessage: String? = null,
        val bridgeVersion: String? = null,
        val multiDnSupported: Boolean = false,
        val openSessions: Long = 0,
        val totalSessions: Long = 0,
        val failedSessions: Long = 0,
        val bytesUp: Long = 0,
        val bytesDown: Long = 0,
        val listenHost: String = "127.0.0.1",
        val listenPort: Int = 8080,
        val startedAtMs: Long = 0
    ) {
        val isRunning: Boolean get() = status == Status.CONNECTED
        val isBusy: Boolean get() = status == Status.CONNECTING
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun update(transform: (Snapshot) -> Snapshot) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = Snapshot()
    }
}
