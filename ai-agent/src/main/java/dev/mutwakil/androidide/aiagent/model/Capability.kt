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
 * Capabilities honestly declared by an [dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin].
 *
 * Providers must only declare what they actually support for the configured model;
 * [dev.mutwakil.androidide.aiagent.gate.CapabilityGate] uses this set to guard
 * attachments, tools and streaming before any request is built.
 */
enum class Capability {
    /** Plain text input. */
    TEXT_INPUT,
    /** Microphone / spoken input. */
    VOICE_INPUT,
    /** Image file attachments accepted by the API. */
    IMAGE_INPUT,
    /** Generic file attachments accepted by the API. */
    FILE_INPUT,
    /** PDF file attachments accepted by the API. */
    PDF_INPUT,
    /** Video file attachments accepted by the API. */
    VIDEO_INPUT,
    /** The model can visually understand image content. */
    VISION,
    /** The model can generate source code. */
    CODE_GENERATION,
    /** Provider-native tool calling (e.g. OpenAI tools, Anthropic tool_use). */
    TOOL_CALLING,
    /** Provider-native function calling (e.g. Gemini function calling). */
    FUNCTION_CALLING,
    /** Incremental (streaming) responses. */
    STREAMING,
}
