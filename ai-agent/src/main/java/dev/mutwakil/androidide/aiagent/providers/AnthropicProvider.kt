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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.mutwakil.androidide.aiagent.gate.CapabilityGate
import dev.mutwakil.androidide.aiagent.gate.UnsupportedCapabilityException
import dev.mutwakil.androidide.aiagent.model.Attachment
import dev.mutwakil.androidide.aiagent.model.AttachmentType
import dev.mutwakil.androidide.aiagent.model.Capability
import dev.mutwakil.androidide.aiagent.model.ChatChunk
import dev.mutwakil.androidide.aiagent.model.ChatMessage
import dev.mutwakil.androidide.aiagent.model.ChatRequest
import dev.mutwakil.androidide.aiagent.model.ChatResponse
import dev.mutwakil.androidide.aiagent.model.ConnectionResult
import dev.mutwakil.androidide.aiagent.model.ProviderConfig
import dev.mutwakil.androidide.aiagent.model.Role
import dev.mutwakil.androidide.aiagent.model.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [AiProviderPlugin] for the Anthropic Messages API
 * (`https://api.anthropic.com/v1/messages`).
 *
 * Authentication uses the `x-api-key` header plus `anthropic-version: 2023-06-01`.
 * Images are sent as base64 `image` blocks and PDFs as base64 `document` blocks,
 * **only** when the model belongs to the Claude 3/4 family (see
 * [modelSupportsVision]); other media is rejected through [CapabilityGate]
 * before any request is built.
 */
class AnthropicProvider : AiProviderPlugin {

    override val id: String = "anthropic"
    override val displayName: String = "Anthropic Claude"

    override val capabilities: Set<Capability> = setOf(
        Capability.TEXT_INPUT,
        Capability.CODE_GENERATION,
        Capability.TOOL_CALLING,
        Capability.FUNCTION_CALLING,
        Capability.STREAMING,
    )

    override val defaultModels: List<String> = listOf(
        "claude-sonnet-4-5",
        "claude-opus-4-1",
        "claude-haiku-4-5",
    )

    override val docsUrl: String? = "https://docs.anthropic.com/en/api"

    companion object {
        const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        const val MODELS_URL = "https://api.anthropic.com/v1/models"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MODEL = "claude-sonnet-4-5"

        /** Matches Claude 3/4 family names (sonnet/opus/haiku variants included). */
        private val CLAUDE_FAMILY = Regex("claude-(3|4|sonnet|opus|haiku)")

        /** Attachments larger than this are rejected with a clear error. */
        private const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024

        /** Images larger than this are downscaled before base64 encoding. */
        private const val IMAGE_DOWNSCALE_THRESHOLD_BYTES = 2L * 1024 * 1024

        /** Text attachments (code) are truncated past this many chars. */
        private const val MAX_TEXT_ATTACHMENT_CHARS = 100_000

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Capabilities resolved for the model in [config]: the base [capabilities]
     * plus [Capability.IMAGE_INPUT], [Capability.VISION] and [Capability.PDF_INPUT]
     * for Claude 3/4 family models.
     */
    override fun resolvedCapabilities(config: ProviderConfig): Set<Capability> {
        val model = config.model.ifBlank { DEFAULT_MODEL }
        return if (modelSupportsVision(model)) {
            capabilities + setOf(
                Capability.IMAGE_INPUT,
                Capability.VISION,
                Capability.PDF_INPUT,
            )
        } else {
            capabilities
        }
    }

    /**
     * Returns true for Claude 3/4 family models: the name (lowercased) matches
     * [CLAUDE_FAMILY] or simply contains "claude". Defaults to *no*
     * vision/document support for unknown models.
     */
    private fun modelSupportsVision(model: String): Boolean {
        val name = model.lowercase()
        return CLAUDE_FAMILY.containsMatchIn(name) || name.contains("claude")
    }

    override suspend fun chat(request: ChatRequest, config: ProviderConfig): ChatResponse =
        withContext(Dispatchers.IO) {
            val caps = resolvedCapabilities(config)
            enforceAttachmentCapabilities(request, caps)
            val body = buildRequestBody(request, caps, modelOf(config))
            http.newCall(postRequest(body, config, streaming = false))
                .execute().use { response ->
                    val raw = response.body?.string()
                    if (!response.isSuccessful) throw httpException("Anthropic", response.code, raw)
                    parseResponse(raw ?: throw IOException("Empty response body"))
                }
        }

    /**
     * Streaming via SSE (`Accept: text/event-stream`).
     *
     * Simplified but correct: text deltas (`content_block_delta` /
     * `text_delta`) are emitted as they arrive; `tool_use` input JSON is
     * accumulated per block and emitted as complete [ToolCall]s when the
     * `message_stop` event arrives, followed by the final done chunk.
     */
    override fun streamChat(request: ChatRequest, config: ProviderConfig): Flow<ChatChunk> = flow {
        val caps = resolvedCapabilities(config)
        enforceAttachmentCapabilities(request, caps)
        val body = buildRequestBody(request, caps, modelOf(config))
        http.newCall(postRequest(body, config, streaming = true)).execute().use { response ->
            if (!response.isSuccessful) {
                throw httpException("Anthropic", response.code, response.body?.string())
            }
            val source = response.body?.source() ?: throw IOException("Empty stream body")
            val toolCalls = LinkedHashMap<Int, ToolCallAccumulator>()
            var eventType = ""
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> {
                        eventType = line.removePrefix("event:").trim()
                    }
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        when (eventType) {
                            "content_block_start" -> {
                                val root = parseData(data) ?: continue
                                val block = root.getAsJsonObject("content_block") ?: continue
                                if (block.optString("type") == "tool_use") {
                                    val index = root.get("index")?.asInt ?: 0
                                    val acc = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                                    block.optString("id")?.let { if (acc.id == null) acc.id = it }
                                    block.optString("name")?.let { if (acc.name == null) acc.name = it }
                                }
                            }
                            "content_block_delta" -> {
                                val root = parseData(data) ?: continue
                                val delta = root.getAsJsonObject("delta") ?: continue
                                val index = root.get("index")?.asInt ?: 0
                                when (delta.optString("type")) {
                                    "text_delta" -> delta.optString("text")?.let { text ->
                                        if (text.isNotEmpty()) emit(ChatChunk(deltaText = text))
                                    }
                                    "input_json_delta" -> delta.optString("partial_json")?.let { partial ->
                                        toolCalls.getOrPut(index) { ToolCallAccumulator() }
                                            .arguments.append(partial)
                                    }
                                }
                            }
                            "message_stop" -> {
                                for ((index, acc) in toolCalls) {
                                    emit(
                                        ChatChunk(
                                            deltaText = "",
                                            toolCallDelta = ToolCall(
                                                id = acc.id ?: "toolu_$index",
                                                name = acc.name ?: "unknown",
                                                argumentsJson = acc.arguments.toString().ifBlank { "{}" },
                                            ),
                                        )
                                    )
                                }
                                emit(ChatChunk(deltaText = "", done = true))
                                return@use
                            }
                            "error" -> throw IOException(
                                "Anthropic stream error: ${extractErrorMessage(data)}"
                            )
                        }
                    }
                }
            }
            // Stream ended without message_stop: flush anything accumulated.
            for ((index, acc) in toolCalls) {
                emit(
                    ChatChunk(
                        deltaText = "",
                        toolCallDelta = ToolCall(
                            id = acc.id ?: "toolu_$index",
                            name = acc.name ?: "unknown",
                            argumentsJson = acc.arguments.toString().ifBlank { "{}" },
                        ),
                    )
                )
            }
            emit(ChatChunk(deltaText = "", done = true))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(config: ProviderConfig): ConnectionResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(MODELS_URL)
                .get()
                .header("x-api-key", config.apiKey?.trim().orEmpty())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .applyHeaders(config.extraHeaders)
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (response.code == 200) {
                        ConnectionResult(true, "Connected to Anthropic API")
                    } else {
                        ConnectionResult(
                            false,
                            "HTTP ${response.code}: ${extractErrorMessage(response.body?.string())}",
                        )
                    }
                }
            } catch (e: IOException) {
                ConnectionResult(false, "Connection failed: ${e.message}")
            }
        }

    // ------------------------------------------------------------------
    // Request building
    // ------------------------------------------------------------------

    private fun modelOf(config: ProviderConfig): String =
        config.model.ifBlank { DEFAULT_MODEL }

    private fun requireApiKey(config: ProviderConfig): String {
        val key = config.apiKey?.trim()
        if (key.isNullOrEmpty()) {
            throw IllegalStateException("No API key configured for provider \"$displayName\".")
        }
        return key
    }

    private fun postRequest(body: String, config: ProviderConfig, streaming: Boolean): Request {
        val builder = Request.Builder()
            .url(MESSAGES_URL)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("x-api-key", requireApiKey(config))
            .header("anthropic-version", ANTHROPIC_VERSION)
            .applyHeaders(config.extraHeaders)
        if (streaming) builder.header("Accept", "text/event-stream")
        return builder.build()
    }

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
        for ((name, value) in headers) header(name, value)
        return this
    }

    /**
     * Golden rule: throws [UnsupportedCapabilityException] when any attachment
     * requires a capability the resolved [caps] do not include. Never called
     * after the request is built — always before.
     */
    private fun enforceAttachmentCapabilities(request: ChatRequest, caps: Set<Capability>) {
        for (message in request.messages) {
            for (attachment in message.attachments) {
                val missing = CapabilityGate.missingCapability(caps, attachment)
                if (missing != null) {
                    throw UnsupportedCapabilityException(
                        CapabilityGate.unavailableMessage(displayName, missing)
                    )
                }
            }
        }
    }

    private fun buildRequestBody(
        request: ChatRequest,
        caps: Set<Capability>,
        model: String,
    ): String {
        val body = LinkedHashMap<String, Any?>()
        body["model"] = model
        body["max_tokens"] = request.maxTokens
        val systemText = buildString {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { append(it) }
            for (message in request.messages) {
                if (message.role == Role.SYSTEM && message.text.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(message.text)
                }
            }
        }
        if (systemText.isNotBlank()) body["system"] = systemText
        body["messages"] = normalizedMessages(request).map { anthropicMessage(it) }
        val tools = CapabilityGate.filterTools(caps, request.tools)
        if (tools.isNotEmpty()) {
            body["tools"] = tools.map { tool ->
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "input_schema" to parseSchema(tool.parametersSchemaJson),
                )
            }
        }
        return gson.toJson(body)
    }

    /**
     * Drops SYSTEM messages (merged into the top-level `system` param) and merges
     * consecutive USER/ASSISTANT messages, since the API requires alternating roles.
     * TOOL messages are never merged: each keeps its own `tool_use_id`.
     */
    private fun normalizedMessages(request: ChatRequest): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        for (message in request.messages) {
            if (message.role == Role.SYSTEM) continue
            val last = result.lastOrNull()
            if (last != null && last.role == message.role && message.role != Role.TOOL) {
                result[result.lastIndex] = last.copy(
                    text = listOf(last.text, message.text)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n"),
                    attachments = last.attachments + message.attachments,
                )
            } else {
                result += message
            }
        }
        return result
    }

    private fun anthropicMessage(message: ChatMessage): Map<String, Any?> {
        val role = if (message.role == Role.ASSISTANT) "assistant" else "user"
        val blocks = mutableListOf<Map<String, Any?>>()
        if (message.role == Role.TOOL) {
            blocks += mapOf(
                "type" to "tool_result",
                "tool_use_id" to (message.toolCallId ?: ""),
                "content" to message.text,
            )
        } else {
            val text = StringBuilder(message.text)
            for (attachment in message.attachments) {
                when (attachment.type) {
                    AttachmentType.IMAGE -> blocks += mapOf(
                        "type" to "image",
                        "source" to mapOf(
                            "type" to "base64",
                            "media_type" to attachment.mimeType,
                            "data" to encodeAttachment(attachment),
                        ),
                    )
                    AttachmentType.PDF -> blocks += mapOf(
                        "type" to "document",
                        "source" to mapOf(
                            "type" to "base64",
                            "media_type" to attachment.mimeType.ifBlank { "application/pdf" },
                            "data" to encodeAttachment(attachment),
                        ),
                    )
                    AttachmentType.CODE -> text.append("\n\n```\n")
                        .append(readTextAttachment(attachment))
                        .append("\n```")
                    else -> throw UnsupportedCapabilityException(
                        "Attachment type ${attachment.type} is not supported by \"$displayName\"."
                    )
                }
            }
            if (text.isNotBlank() || blocks.isEmpty()) {
                blocks.add(0, mapOf("type" to "text", "text" to text.toString()))
            }
        }
        return mapOf("role" to role, "content" to blocks)
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    private fun parseResponse(raw: String): ChatResponse {
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: JsonParseException) {
            throw IOException("Malformed Anthropic response: invalid JSON (${e.message})")
        } catch (e: IllegalStateException) {
            // CancellationException herda IllegalStateException: nunca engolir cancelamento.
            if (e is CancellationException) throw e
            throw IOException("Malformed Anthropic response: root is not a JSON object")
        }
        val content = root.getAsJsonArray("content")
            ?: throw IOException("Malformed Anthropic response: missing content[]")
        val text = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        for (element in content) {
            val block = element.asJsonObject
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text").orEmpty())
                "tool_use" -> toolCalls += ToolCall(
                    id = block.optString("id") ?: "toolu_${toolCalls.size}",
                    name = block.optString("name") ?: "unknown",
                    argumentsJson = gson.toJson(block.get("input") ?: JsonObject()),
                )
            }
        }
        return ChatResponse(text.toString(), toolCalls)
    }

    private fun parseData(data: String): JsonObject? =
        try {
            JsonParser.parseString(data).asJsonObject
        } catch (e: Exception) {
            null
        }

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    private fun encodeAttachment(attachment: Attachment): String {
        val size = attachment.file.length()
        if (size > MAX_ATTACHMENT_BYTES) {
            throw UnsupportedCapabilityException(
                "Attachment \"${attachment.displayName}\" is too large " +
                    "(${size / 1024 / 1024} MB). The limit is " +
                    "${MAX_ATTACHMENT_BYTES / 1024 / 1024} MB."
            )
        }
        val bytes = if (attachment.type == AttachmentType.IMAGE &&
            size > IMAGE_DOWNSCALE_THRESHOLD_BYTES
        ) {
            downscaleImage(attachment.file)
        } else {
            attachment.file.readBytes()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Downscales an image file so its decoded pixels stay under the threshold. */
    private fun downscaleImage(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return file.readBytes()
        var sampleSize = 1
        while ((bounds.outWidth / sampleSize) * (bounds.outHeight / sampleSize) * 4L >
            IMAGE_DOWNSCALE_THRESHOLD_BYTES && sampleSize < 32
        ) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return file.readBytes()
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun readTextAttachment(attachment: Attachment): String {
        val text = attachment.file.readBytes().toString(Charsets.UTF_8)
        return if (text.length > MAX_TEXT_ATTACHMENT_CHARS) {
            text.take(MAX_TEXT_ATTACHMENT_CHARS) +
                "\n[truncated: showing first $MAX_TEXT_ATTACHMENT_CHARS characters]"
        } else {
            text
        }
    }

    // ------------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------------

    private fun parseSchema(schemaJson: String): Any =
        try {
            JsonParser.parseString(schemaJson)
        } catch (e: Exception) {
            JsonParser.parseString("""{"type":"object"}""")
        }

    private fun JsonObject.optString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun httpException(provider: String, code: Int, rawBody: String?): IOException =
        IOException("$provider request failed (HTTP $code): ${extractErrorMessage(rawBody)}")

    private fun extractErrorMessage(rawBody: String?): String {
        if (rawBody.isNullOrBlank()) return "no response body"
        return try {
            JsonParser.parseString(rawBody).asJsonObject
                .getAsJsonObject("error")
                ?.optString("message")
                ?: rawBody.take(500)
        } catch (e: Exception) {
            rawBody.take(500)
        }
    }

    /** Accumulates streamed `input_json_delta` fragments keyed by block index. */
    private class ToolCallAccumulator {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
