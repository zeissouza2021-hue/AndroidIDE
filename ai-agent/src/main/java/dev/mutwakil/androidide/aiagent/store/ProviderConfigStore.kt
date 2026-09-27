/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.mutwakil.androidide.aiagent.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.mutwakil.androidide.aiagent.model.ProviderConfig

/**
 * Persists [ProviderConfig]s.
 *
 * Storage layout:
 * - API keys go to an [EncryptedSharedPreferences] file (`aiagent_secrets`),
 *   encrypted with a [MasterKey] (AES256-GCM).
 * - Everything else (provider id, display name, endpoint, model, extra headers
 *   as JSON, the set of configured ids and the active provider id) lives in a
 *   regular [SharedPreferences] file (`aiagent_prefs`).
 *
 * Per-provider keys are namespaced as `provider.<id>.<field>`.
 */
class ProviderConfigStore(context: Context) {

    private val gson = Gson()
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secrets: SharedPreferences =
        createEncryptedPrefs(context.applicationContext)

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun prefix(id: String) = "provider.$id."

    /** Inserts or replaces the configuration for [config.providerId]. */
    fun saveProviderConfig(config: ProviderConfig) {
        val p = prefix(config.providerId)
        prefs.edit()
            .putString(p + "displayName", config.displayName)
            .putString(p + "endpoint", config.endpoint)
            .putString(p + "model", config.model)
            .putString(p + "extraHeaders", gson.toJson(config.extraHeaders))
            .putStringSet(KEY_PROVIDER_IDS, (listConfiguredIds() + config.providerId).toMutableSet())
            .apply()
        secrets.edit().let { editor ->
            if (config.apiKey.isNullOrEmpty()) {
                editor.remove(p + "apiKey")
            } else {
                editor.putString(p + "apiKey", config.apiKey)
            }
            editor.apply()
        }
    }

    /** Returns the stored configuration for [id], or null if never saved. */
    fun getProviderConfig(id: String): ProviderConfig? {
        if (id !in listConfiguredIds()) return null
        val p = prefix(id)
        return ProviderConfig(
            providerId = id,
            displayName = prefs.getString(p + "displayName", id) ?: id,
            apiKey = secrets.getString(p + "apiKey", null),
            endpoint = prefs.getString(p + "endpoint", null),
            model = prefs.getString(p + "model", "") ?: "",
            extraHeaders = readExtraHeaders(prefs.getString(p + "extraHeaders", null)),
        )
    }

    /** Ids of all saved provider configurations. */
    fun listConfiguredIds(): Set<String> =
        prefs.getStringSet(KEY_PROVIDER_IDS, mutableSetOf())?.toSet().orEmpty()

    /** Deletes the configuration for [id], clearing the active provider if it matches. */
    fun deleteProviderConfig(id: String) {
        val p = prefix(id)
        prefs.edit()
            .remove(p + "displayName")
            .remove(p + "endpoint")
            .remove(p + "model")
            .remove(p + "extraHeaders")
            .putStringSet(KEY_PROVIDER_IDS, (listConfiguredIds() - id).toMutableSet())
            .apply()
        secrets.edit().remove(p + "apiKey").apply()
        if (getActiveProviderId() == id) setActiveProviderId(null)
    }

    /** Id of the currently active provider, or null if none was selected. */
    fun getActiveProviderId(): String? = prefs.getString(KEY_ACTIVE_PROVIDER_ID, null)

    /** Selects the active provider; null clears the selection. */
    fun setActiveProviderId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE_PROVIDER_ID) else putString(KEY_ACTIVE_PROVIDER_ID, id)
        }.apply()
    }

    /** The stored configuration of the active provider, or null. */
    fun getActiveConfig(): ProviderConfig? =
        getActiveProviderId()?.let { getProviderConfig(it) }

    private fun readExtraHeaders(json: String?): Map<String, String> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type).orEmpty()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val PREFS_NAME = "aiagent_prefs"
        private const val ENCRYPTED_PREFS_NAME = "aiagent_secrets"
        private const val KEY_PROVIDER_IDS = "provider_ids"
        private const val KEY_ACTIVE_PROVIDER_ID = "active_provider_id"
    }
}
