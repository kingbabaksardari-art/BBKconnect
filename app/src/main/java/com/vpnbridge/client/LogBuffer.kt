package com.vpnbridge.client

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight log buffer for the UI. Keeps the last N entries in memory and
 * also forwards everything to logcat so `adb logcat` still shows it.
 */
object LogBuffer {
    private const val MAX = 300
    private const val TAG = "VpnBridge"
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(val time: String, val level: Level, val message: String)

    private val buffer = ArrayDeque<Entry>(MAX)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun init() { /* warm */ }

    private fun add(level: Level, message: String) {
        val e = Entry(fmt.format(Date()), level, message)
        synchronized(buffer) {
            buffer.addLast(e)
            while (buffer.size > MAX) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
        when (level) {
            Level.DEBUG -> Log.d(TAG, message)
            Level.INFO -> Log.i(TAG, message)
            Level.WARN -> Log.w(TAG, message)
            Level.ERROR -> Log.e(TAG, message)
        }
    }

    fun d(msg: String) = add(Level.DEBUG, msg)
    fun i(msg: String) = add(Level.INFO, msg)
    fun w(msg: String) = add(Level.WARN, msg)
    fun e(msg: String) = add(Level.ERROR, msg)

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }
}
