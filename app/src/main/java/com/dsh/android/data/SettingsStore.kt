package com.dsh.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "dsh_settings")

/**
 * Persists the user's DeepSeek API connection settings.
 *
 * The API key is injected into the dsh server process as `DEEPSEEK_API_KEY`,
 * which is exactly the credential the harness's llm-deepseek adapter reads.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val API_BASE_URL = stringPreferencesKey("api_base_url")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val AUTO_START = booleanPreferencesKey("auto_start")
    }

    val apiKey: Flow<String> = context.dataStore.data.map { it[Keys.API_KEY] ?: "" }
    val apiBaseUrl: Flow<String> = context.dataStore.data.map { it[Keys.API_BASE_URL] ?: "" }
    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDED] ?: false }
    val autoStart: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_START] ?: true }

    suspend fun setApiKey(value: String) {
        context.dataStore.edit { it[Keys.API_KEY] = value.trim() }
    }

    suspend fun setApiBaseUrl(value: String) {
        context.dataStore.edit { it[Keys.API_BASE_URL] = value.trim() }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDED] = value }
    }

    suspend fun setAutoStart(value: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_START] = value }
    }

    suspend fun snapshot(): SettingsSnapshot = SettingsSnapshot(
        apiKey = apiKey.first(),
        apiBaseUrl = apiBaseUrl.first(),
        onboarded = onboarded.first(),
        autoStart = autoStart.first(),
    )

    data class SettingsSnapshot(
        val apiKey: String,
        val apiBaseUrl: String,
        val onboarded: Boolean,
        val autoStart: Boolean,
    ) {
        val hasApiKey: Boolean get() = apiKey.isNotBlank()
    }
}
