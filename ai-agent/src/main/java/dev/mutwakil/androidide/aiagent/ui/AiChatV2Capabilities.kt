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

import dev.mutwakil.androidide.aiagent.model.Capability
import dev.mutwakil.androidide.aiagent.model.ProviderConfig
import dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin

/**
 * v2 capability resolution: a config entry may carry [ProviderConfig.capabilityOverrides];
 * when present they replace the capabilities resolved from the protocol plugin,
 * otherwise the plugin's own (possibly model-dependent) resolution applies.
 */
object AiChatV2Capabilities {

    /** Effective capabilities of [plugin] for [config] (null config = plugin defaults). */
    fun resolve(plugin: AiProviderPlugin, config: ProviderConfig?): Set<Capability> {
        val base = config?.let { plugin.resolvedCapabilities(it) } ?: plugin.capabilities
        return config?.capabilityOverrides ?: base
    }

    /** Whether the provider can receive video attachments. */
    fun supportsVideoInput(caps: Set<Capability>): Boolean =
        Capability.VIDEO_INPUT in caps
}
