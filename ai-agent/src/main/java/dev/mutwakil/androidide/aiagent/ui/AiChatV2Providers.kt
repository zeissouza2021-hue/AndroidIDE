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
 * Helpers around the v2 provider-entry model.
 *
 * v2.1: o sistema é 100% genérico. Nenhuma entrada é criada pelo código —
 * o usuário adiciona "uma API" (nome livre + protocolo + endpoint + chave +
 * modelo) e tudo é editável, renomeável e apagável. Os protocolos
 * ("openai-compatible", "anthropic", "gemini") continuam existindo no código
 * porque são o formato da conversa HTTP com cada API, não modelos fixos.
 */
object AiChatV2Providers {

    /** Prefix for user-created entry ids. */
    const val CUSTOM_ID_PREFIX = "custom-"

    /** Builds a fresh id for a user-created entry. */
    fun newCustomId(): String = CUSTOM_ID_PREFIX + UUID.randomUUID().toString()

    /**
     * Garante um estado válido sem criar nada: migra entradas legadas
     * semeadas como `isDefault` para entradas normais (editáveis/apagáveis)
     * e limpa o id ativo quando ele aponta para uma entrada que não existe
     * mais. Idempotente; seguro chamar em todo ponto de entrada da UI.
     *
     * Requires [AiAgent.init] to have run already.
     */
    fun ensureValidState() {
        val store = runCatching { AiAgent.configStore() }.getOrNull() ?: return
        // Migração v2.1: entradas "padrão" das versões antigas viram
        // entradas comuns — nada é fixo ou indeletável.
        store.listConfiguredIds()
            .mapNotNull { store.getProviderConfig(it) }
            .filter { it.isDefault }
            .forEach { store.saveProviderConfig(it.copy(isDefault = false)) }
        val activeId = store.getActiveConfigId()
        if (activeId != null && store.getProviderConfig(activeId) == null) {
            val first = store.listConfiguredIds().firstOrNull()
            if (first != null) store.setActiveConfigId(first)
            else store.setActiveConfigId(null)
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
     * All saved entries in a stable order: alphabetical by display name.
     */
    fun listConfigs(): List<ProviderConfig> {
        val store = runCatching { AiAgent.configStore() }.getOrNull() ?: return emptyList()
        return store.listConfiguredIds()
            .mapNotNull { store.getProviderConfig(it) }
            .sortedWith(compareBy({ it.displayName.lowercase() }))
    }

    /**
     * Legado: antes da v2.1 existiam entradas "padrão" semeadas pelo código.
     * Hoje nada é padrão; retorna null.
     */
    fun defaultConfigFor(providerId: String): ProviderConfig? = null
}
