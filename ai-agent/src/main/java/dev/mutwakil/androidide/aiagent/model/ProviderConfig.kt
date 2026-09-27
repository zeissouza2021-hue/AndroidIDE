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
 * @param providerId id of the [dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin] this config belongs to.
 * @param extraHeaders additional HTTP headers sent with every request.
 */
data class ProviderConfig(
    val providerId: String,
    val displayName: String = providerId,
    val apiKey: String? = null,
    val endpoint: String? = null,
    val model: String = "",
    val extraHeaders: Map<String, String> = emptyMap(),
)

/** Result of [dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin.testConnection]. */
data class ConnectionResult(
    val ok: Boolean,
    val message: String,
)
