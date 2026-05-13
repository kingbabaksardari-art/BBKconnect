package com.vpnbridge.client

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.configDataStore by preferencesDataStore(name = "vpn_bridge_config")

object ConfigStore {
    private val KEY_CONFIG_JSON = stringPreferencesKey("config_json")

    fun observe(context: Context): Flow<BridgeConfig?> {
        return context.configDataStore.data.map { prefs ->
            val raw = prefs[KEY_CONFIG_JSON] ?: return@map null
            try {
                BridgeConfig.parse(raw)
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun save(context: Context, config: BridgeConfig) {
        context.configDataStore.edit { prefs ->
            prefs[KEY_CONFIG_JSON] = config.toJsonString(pretty = false)
        }
    }

    suspend fun clear(context: Context) {
        context.configDataStore.edit { it.remove(KEY_CONFIG_JSON) }
    }
}
