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

package dev.mutwakil.androidide.aiagent.ui

import dev.mutwakil.androidide.aiagent.AiAgent
import dev.mutwakil.androidide.aiagent.model.ProviderConfig
import dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin
import java.util.UUID

/**
 * Helpers around the v2 provider-entry model (default + user-created entries).
 *
 * A custom entry is a [ProviderConfig] whose [ProviderConfig.providerId] points
 * at the protocol plugin it speaks ("openai-compatible", "anthropic",
 * "gemini") and whose [ProviderConfig.configId] is a unique `custom-*` id.
 * The protocol plugin itself executes the requests, so no dynamic plugin
 * registration is needed.
 */
object AiChatV2Providers {

    /** Prefix for user-created entry ids. */
    const val CUSTOM_ID_PREFIX = "custom-"

    /** Builds a fresh id for a user-created entry. */
    fun newCustomId(): String = CUSTOM_ID_PREFIX + UUID.randomUUID().toString()

    /**
     * Seeds one editable default entry per registered protocol plugin, and
     * picks an active entry when none is selected. Idempotent; safe to call
     * on every UI entry point.
     *
     * Requires [AiAgent.init] to have run and the protocol plugins to be
     * registered already.
     */
    fun ensureDefaults() {
        val store = runCatching { AiAgent.configStore() }.getOrNull() ?: return
        val registry = AiAgent.registry()
        registry.all().forEach { plugin ->
            if (store.getProviderConfig(plugin.id) == null) {
                store.saveProviderConfig(
                    ProviderConfig(
                        providerId = plugin.id,
                        configId = plugin.id,
                        displayName = plugin.displayName,
                        isDefault = true,
                    )
                )
            }
        }
        if (store.getActiveConfigId() == null) {
            registry.all().firstOrNull()?.let { store.setActiveConfigId(it.id) }
        }
    }

    /**
     * Resolves a config id to its (protocol plugin, config) pair,
     * or null when either side is missing.
     */
    fun resolveConfig(configId: String): Pair<AiProviderPlugin, ProviderConfig>? {
        val store = runCatching { AiAgent.configStore() }.getOrNull() ?: return null
        val config = store.getProviderConfig(configId) ?: return null
        val plugin = AiAgent.registry().get(config.providerId) ?: return null
        return plugin to config
    }

    /**
     * All saved entries (defaults + customs) in a stable order:
     * defaults first, then customs, alphabetical by display name.
     */
    fun listConfigs(): List<ProviderConfig> {
        val store = runCatching { AiAgent.configStore() }.getOrNull() ?: return emptyList()
        return store.listConfiguredIds()
            .mapNotNull { store.getProviderConfig(it) }
            .sortedWith(compareBy({ !it.isDefault }, { it.displayName.lowercase() }))
    }

    /** The default entry for a protocol plugin id, if one exists. */
    fun defaultConfigFor(providerId: String): ProviderConfig? {
        val store = runCatching { AiAgent.configStore() }.getOrNull() ?: return null
        return store.getProviderConfig(providerId)?.takeIf { it.isDefault }
    }
}
