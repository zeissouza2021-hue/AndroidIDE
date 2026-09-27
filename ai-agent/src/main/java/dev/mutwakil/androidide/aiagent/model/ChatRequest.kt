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
 * A full chat request sent to a provider.
 *
 * @param systemPrompt optional system instruction; providers merge it as the first system message.
 */
data class ChatRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val systemPrompt: String? = null,
    val maxTokens: Int = 4096,
)

/** Non-streaming response. */
data class ChatResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
)

/**
 * One incremental unit of a streaming response.
 *
 * @param deltaText text appended by this chunk (may be empty).
 * @param toolCallDelta a tool call announced by this chunk, if any.
 * @param done true for the final chunk of the stream.
 */
data class ChatChunk(
    val deltaText: String,
    val toolCallDelta: ToolCall? = null,
    val done: Boolean = false,
)
