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

import dev.mutwakil.androidide.aiagent.model.Capability
import dev.mutwakil.androidide.aiagent.model.ChatChunk
import dev.mutwakil.androidide.aiagent.model.ChatRequest
import dev.mutwakil.androidide.aiagent.model.ChatResponse
import dev.mutwakil.androidide.aiagent.model.ConnectionResult
import dev.mutwakil.androidide.aiagent.model.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Plugin contract for an AI provider (front-b implements the adapters).
 *
 * Implementations must declare [capabilities] honestly for the configured model:
 * never advertise a capability (vision, tool calling, streaming…) the model or
 * endpoint does not actually support.
 */
interface AiProviderPlugin {
    /** Stable unique id, e.g. "openai-compatible", "anthropic", "gemini". */
    val id: String

    /** Human-readable name shown in settings. */
    val displayName: String

    /** Capabilities honestly supported for the configured model. */
    val capabilities: Set<Capability>

    /**
     * Capabilities resolved for a specific [ProviderConfig] (e.g. depending on
     * the configured model name). Defaults to [capabilities]; providers whose
     * capabilities vary per model override this.
     */
    fun resolvedCapabilities(config: ProviderConfig): Set<Capability> = capabilities

    /** Suggested model names offered in the settings UI. */
    val defaultModels: List<String>

    /** Whether this provider needs an API key to work. */
    val requiresApiKey: Boolean
        get() = true

    /** Link to the provider docs, if any. */
    val docsUrl: String?

    /** Single non-streaming chat completion. */
    suspend fun chat(request: ChatRequest, config: ProviderConfig): ChatResponse

    /**
     * Streaming chat completion.
     *
     * Default implementation calls [chat] once and replays the whole response as
     * a single chunk, forwarding any tool calls first so they are not lost.
     * Providers with real streaming (SSE etc.) should override this.
     */
    fun streamChat(request: ChatRequest, config: ProviderConfig): Flow<ChatChunk> = flow {
        val response = chat(request, config)
        for (toolCall in response.toolCalls) {
            emit(ChatChunk(deltaText = "", toolCallDelta = toolCall))
        }
        emit(ChatChunk(deltaText = response.text, done = true))
    }

    /** Lightweight connectivity check (e.g. list-models or a tiny completion). */
    suspend fun testConnection(config: ProviderConfig): ConnectionResult
}
