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
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.mutwakil.androidide.aiagent.model.Capability
import dev.mutwakil.androidide.aiagent.model.ProviderConfig

/**
 * Persists [ProviderConfig]s.
 *
 * Storage layout:
 * - API keys and extra headers go to an [EncryptedSharedPreferences] file (`aiagent_secrets`),
 *   encrypted with a [MasterKey] (AES256-GCM). Extra headers frequently carry
 *   credentials (`Authorization`, `x-api-key`, ...), so they are never stored in plain text.
 * - Everything else (config id, provider id, display name, endpoint, model,
 *   fallback flag, capability overrides, the set of configured ids and the
 *   active config id) lives in a regular [SharedPreferences] file (`aiagent_prefs`).
 *
 * Legacy note: versions before this change stored `extraHeaders` in the
 * plain prefs file; reads fall back to that key so existing configs are
 * not lost, and the plaintext copy is removed on the next save.
 *
 * Per-entry keys are namespaced as `provider.<configId>.<field>`.
 * Entries stored before v2 used the provider id as the key; since default
 * entries keep `configId == providerId`, they resolve unchanged.
 */
class ProviderConfigStore(context: Context) {

    private val gson = Gson()
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secrets: SharedPreferences =
        createEncryptedPrefs(context.applicationContext)

    /**
     * Cria o prefs criptografado. Se o keystore falhar (ex.: corrompido ou
     * bloqueado no aparelho), cai para um prefs comum em vez de estourar o
     * construtor — degradado, mas o app continua abrindo. No caminho normal
     * (keystore íntegro) o comportamento é idêntico ao anterior.
     */
    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Keystore indisponível; usando prefs sem criptografia", e)
            context.getSharedPreferences(ENCRYPTED_PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun prefix(id: String) = "provider.$id."

    /** Inserts or replaces the configuration entry [config.configId]. */
    fun saveProviderConfig(config: ProviderConfig) {
        val id = config.configId.ifBlank { config.providerId }
        val p = prefix(id)
        prefs.edit()
            .putString(p + "configId", id)
            .putString(p + "providerId", config.providerId)
            .putString(p + "displayName", config.displayName)
            .putString(p + "endpoint", config.endpoint)
            .putString(p + "model", config.model)
            // Remove a cópia legada em texto puro (agora vai para o prefs criptografado).
            .remove(p + "extraHeaders")
            .putBoolean(p + "useAsFallback", config.useAsFallback)
            .putBoolean(p + "isDefault", config.isDefault)
            .apply {
                val overrides = config.capabilityOverrides
                if (overrides == null) {
                    remove(p + "capabilityOverrides")
                } else {
                    putStringSet(p + "capabilityOverrides", overrides.map { it.name }.toMutableSet())
                }
            }.apply()
        prefs.edit()
            .putStringSet(KEY_PROVIDER_IDS, (listConfiguredIds() + id).toMutableSet())
            .apply()
        secrets.edit().let { editor ->
            if (config.apiKey.isNullOrEmpty()) {
                editor.remove(p + "apiKey")
            } else {
                editor.putString(p + "apiKey", config.apiKey)
            }
            // Headers extras podem carregar credenciais: nunca em texto puro.
            if (config.extraHeaders.isEmpty()) {
                editor.remove(p + "extraHeaders")
            } else {
                editor.putString(p + "extraHeaders", gson.toJson(config.extraHeaders))
            }
            editor.apply()
        }
    }

    /** Returns the stored configuration entry for [id] (a config id), or null if never saved. */
    fun getProviderConfig(id: String): ProviderConfig? {
        if (id !in listConfiguredIds()) return null
        val p = prefix(id)
        return ProviderConfig(
            providerId = prefs.getString(p + "providerId", id) ?: id,
            displayName = prefs.getString(p + "displayName", id) ?: id,
            apiKey = secrets.getString(p + "apiKey", null),
            endpoint = prefs.getString(p + "endpoint", null),
            model = prefs.getString(p + "model", "") ?: "",
            // Fallback para a chave legada em texto puro (versões antigas).
            extraHeaders = readExtraHeaders(
                secrets.getString(p + "extraHeaders", null)
                    ?: prefs.getString(p + "extraHeaders", null)
            ),
            configId = prefs.getString(p + "configId", id) ?: id,
            useAsFallback = prefs.getBoolean(p + "useAsFallback", true),
            capabilityOverrides = readCapabilityOverrides(
                prefs.getStringSet(p + "capabilityOverrides", null)
            ),
            isDefault = prefs.getBoolean(p + "isDefault", false),
        )
    }

    /** Ids of all saved provider configuration entries. */
    fun listConfiguredIds(): Set<String> =
        prefs.getStringSet(KEY_PROVIDER_IDS, mutableSetOf())?.toSet().orEmpty()

    /** Deletes the configuration entry for [id], clearing the active entry if it matches. */
    fun deleteProviderConfig(id: String) {
        val p = prefix(id)
        prefs.edit()
            .remove(p + "configId")
            .remove(p + "providerId")
            .remove(p + "displayName")
            .remove(p + "endpoint")
            .remove(p + "model")
            .remove(p + "extraHeaders") // cópia legada em texto puro
            .remove(p + "useAsFallback")
            .remove(p + "capabilityOverrides")
            .remove(p + "isDefault")
            .putStringSet(KEY_PROVIDER_IDS, (listConfiguredIds() - id).toMutableSet())
            .apply()
        secrets.edit().remove(p + "apiKey").remove(p + "extraHeaders").apply()
        if (getActiveConfigId() == id) setActiveConfigId(null)
    }

    /** Id of the currently active configuration entry, or null if none was selected. */
    fun getActiveConfigId(): String? = prefs.getString(KEY_ACTIVE_PROVIDER_ID, null)

    /** Selects the active configuration entry; null clears the selection. */
    fun setActiveConfigId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE_PROVIDER_ID) else putString(KEY_ACTIVE_PROVIDER_ID, id)
        }.apply()
    }

    /**
     * Id of the currently active provider entry, or null if none was selected.
     * Same as [getActiveConfigId]; kept for callers written before v2.
     */
    fun getActiveProviderId(): String? = getActiveConfigId()

    /**
     * Selects the active provider entry; null clears the selection.
     * Same as [setActiveConfigId]; kept for callers written before v2.
     */
    fun setActiveProviderId(id: String?) = setActiveConfigId(id)

    /** The stored configuration of the active entry, or null. */
    fun getActiveConfig(): ProviderConfig? =
        getActiveConfigId()?.let { getProviderConfig(it) }

    private fun readExtraHeaders(json: String?): Map<String, String> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type).orEmpty()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Reads the stored capability overrides: absent key means "no override"
     * (null); a present (possibly empty) set is the explicit override.
     */
    private fun readCapabilityOverrides(names: Set<String>?): Set<Capability>? {
        if (names == null) return null
        return names.mapNotNull { runCatching { Capability.valueOf(it) }.getOrNull() }.toSet()
    }

    companion object {
        private const val TAG = "ProviderConfigStore"
        private const val PREFS_NAME = "aiagent_prefs"
        private const val ENCRYPTED_PREFS_NAME = "aiagent_secrets"
        private const val KEY_PROVIDER_IDS = "provider_ids"
        private const val KEY_ACTIVE_PROVIDER_ID = "active_provider_id"
    }
}
