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

package dev.mutwakil.androidide.aiagent.providers

/**
 * Thread-safe registry of [AiProviderPlugin]s.
 *
 * Plugins are registered once at app startup (see front-f wiring);
 * registering the same id twice replaces the previous plugin.
 */
class ProviderRegistry {
    private val lock = Any()
    private val plugins = LinkedHashMap<String, AiProviderPlugin>()

    /** Registers (or replaces) a provider plugin. */
    fun register(plugin: AiProviderPlugin) {
        synchronized(lock) { plugins[plugin.id] = plugin }
    }

    /** Returns the plugin for [id], or null if none is registered. */
    fun get(id: String): AiProviderPlugin? = synchronized(lock) { plugins[id] }

    /** All registered plugins, in registration order. */
    fun all(): List<AiProviderPlugin> = synchronized(lock) { plugins.values.toList() }
}
