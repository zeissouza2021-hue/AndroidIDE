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

package dev.mutwakil.androidide.aiagent.model

/**
 * User-editable configuration for one provider instance (e.g. "my OpenAI key",
 * "local Ollama"). Persisted by [dev.mutwakil.androidide.aiagent.store.ProviderConfigStore].
 *
 * v2.1: o sistema é 100% genérico — nenhuma entrada é criada pelo código.
 * O usuário adiciona "uma API": nome livre + protocolo ([providerId]) +
 * chave + modelo. Toda entrada é editável, renomeável e apagável.
 * [isDefault] é legado (migração das entradas semeadas em versões antigas).
 *
 * @param providerId id of the [dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin]
 *   (protocol) this config belongs to.
 * @param configId unique id of this entry.
 * @param useAsFallback when true, this entry is eligible for automatic
 *   fallback after rate-limit/overload/context errors.
 * @param capabilityOverrides when non-null, replaces the capabilities resolved
 *   from the protocol plugin for this entry.
 * @param isDefault legado: sempre false em entradas novas.
 * @param extraHeaders additional HTTP headers sent with every request.
 */
data class ProviderConfig(
    val providerId: String,
    val displayName: String = providerId,
    val apiKey: String? = null,
    val endpoint: String? = null,
    val model: String = "",
    val extraHeaders: Map<String, String> = emptyMap(),
    val configId: String = providerId,
    val useAsFallback: Boolean = true,
    val capabilityOverrides: Set<Capability>? = null,
    val isDefault: Boolean = false,
)

/** Result of [dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin.testConnection]. */
data class ConnectionResult(
    val ok: Boolean,
    val message: String,
)
