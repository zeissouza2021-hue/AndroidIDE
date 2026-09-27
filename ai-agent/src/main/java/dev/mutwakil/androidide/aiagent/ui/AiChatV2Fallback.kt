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
import dev.mutwakil.androidide.aiagent.store.ProviderConfigStore

/**
 * Automatic model fallback (v2).
 *
 * When a request fails with a *transient* provider-side error — rate limit
 * (HTTP 429), provider overload (HTTP 503/529) or context overflow — the chat
 * retries with the next entry flagged [ProviderConfig.useAsFallback], and
 * tells the user which model took over and why. No "smart" routing by task
 * type: the order is simply the stable entry order from
 * [AiChatV2Providers.listConfigs].
 */
object AiChatV2Fallback {

    /** One fallback candidate: protocol plugin + entry config. */
    data class Target(val plugin: AiProviderPlugin, val config: ProviderConfig)

    /** Why the previous model was abandoned (drives the user-facing message). */
    enum class Reason { RATE_LIMITED, OVERLOADED, CONTEXT_OVERFLOW }

    /**
     * Fallback-eligible entries, excluding [excludeConfigId], in stable order.
     * Entries without a model set or whose protocol plugin is not registered
     * are skipped.
     */
    fun candidates(
        store: ProviderConfigStore,
        excludeConfigId: String
    ): List<Target> {
        val registry = AiAgent.registry()
        return AiChatV2Providers.listConfigs()
            .filter { it.configId != excludeConfigId }
            .filter { it.useAsFallback && it.model.isNotBlank() }
            .mapNotNull { config ->
                registry.get(config.providerId)?.let { Target(it, config) }
            }
    }

    /**
     * Classifies a provider/engine error message. Returns null when the error
     * is not a transient provider-side failure (auth errors, capability
     * errors, network-down etc. never trigger fallback).
     */
    fun classify(error: String): Reason? {
        val lower = error.lowercase()
        // Auth / config errors must surface, never silently switch models.
        if (lower.contains("http 401") || lower.contains("http 403") ||
            lower.contains("unauthorized") || lower.contains("invalid api key") ||
            lower.contains("authentication")
        ) {
            return null
        }
        if (lower.contains("http 429") || lower.contains("rate limit") ||
            lower.contains("rate_limit") || lower.contains("too many requests") ||
            (lower.contains("429") && lower.contains("quota"))
        ) {
            return Reason.RATE_LIMITED
        }
        if (lower.contains("http 503") || lower.contains("http 529") ||
            lower.contains("overloaded") || lower.contains("overload") ||
            lower.contains("service unavailable")
        ) {
            return Reason.OVERLOADED
        }
        if (lower.contains("context length") || lower.contains("maximum context") ||
            lower.contains("context window") || lower.contains("too many tokens") ||
            lower.contains("token limit") || lower.contains("input too long") ||
            lower.contains("context_length_exceeded")
        ) {
            return Reason.CONTEXT_OVERFLOW
        }
        return null
    }
}
