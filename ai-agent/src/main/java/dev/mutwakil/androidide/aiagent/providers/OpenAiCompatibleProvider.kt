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
 * [AiProviderPlugin] for OpenAI's `chat/completions` API and any compatible
 * endpoint (Ollama, LM Studio, OpenRouter, vLLM, ...).
 *
 * The endpoint is taken from [ProviderConfig.endpoint] (default
 * [DEFAULT_ENDPOINT]) and the model from [ProviderConfig.model] (default
 * [DEFAULT_MODEL]). Authentication uses `Authorization: Bearer <apiKey>`.
 *
 * Capabilities are declared honestly per model: vision ([Capability.IMAGE_INPUT]
 * / [Capability.VISION]) is only advertised when [modelSupportsVision] matches
 * the model name. Voice input is intentionally *not* declared: spoken input is
 * transcribed to text by the app before reaching the provider.
 */
class OpenAiCompatibleProvider : AiProviderPlugin {

    override val id: String = "openai-compatible"
    override val displayName: String = "OpenAI / Compatible"

    override val capabilities: Set<Capability> = setOf(
        Capability.TEXT_INPUT,
        Capability.FILE_INPUT,
        Capability.CODE_GENERATION,
        Capability.TOOL_CALLING,
        Capability.FUNCTION_CALLING,
        Capability.STREAMING,
    )

    override val defaultModels: List<String> = listOf(
        "gpt-4o-mini",
        "gpt-4o",
        "gpt-4.1-mini",
        "gpt-4.1",
    )

    override val docsUrl: String? = "https://platform.openai.com/docs/api-reference"

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        /**
         * Lowercase substrings of model names treated as vision-capable.
         * Note: "o1" is kept in this list per the plugin specification, even
         * though o1-series models historically did not accept image input.
         */
        private val VISION_MODEL_MARKERS = listOf("gpt-4o", "gpt-4.1", "o1", "vision")

        /** Attachments larger than this are rejected with a clear error. */
        private const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024

        /** Images larger than this are downscaled before base64 encoding. */
        private const val IMAGE_DOWNSCALE_THRESHOLD_BYTES = 2L * 1024 * 1024

        /** Text attachments (code/documents) are truncated past this many chars. */
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
     * plus [Capability.IMAGE_INPUT] and [Capability.VISION] when the model name
     * indicates vision support. Used internally for the [CapabilityGate] checks
     * and available to UI layers that need model-aware capabilities.
     */
    override fun resolvedCapabilities(config: ProviderConfig): Set<Capability> {
        val model = config.model.ifBlank { DEFAULT_MODEL }
        return if (modelSupportsVision(model)) {
            capabilities + setOf(Capability.IMAGE_INPUT, Capability.VISION)
        } else {
            capabilities
        }
    }

    /**
     * Returns true when the model name (lowercased) contains one of
     * [VISION_MODEL_MARKERS]. Defaults to *no* vision: never assumed.
     */
    private fun modelSupportsVision(model: String): Boolean {
        val name = model.lowercase()
        return VISION_MODEL_MARKERS.any { marker -> name.contains(marker) }
    }

    override suspend fun chat(request: ChatRequest, config: ProviderConfig): ChatResponse =
        withContext(Dispatchers.IO) {
            val caps = resolvedCapabilities(config)
            enforceAttachmentCapabilities(request, caps)
            val body = buildRequestBody(request, caps, modelOf(config), stream = false)
            http.newCall(postRequest("${endpointOf(config)}/chat/completions", body, config))
                .execute().use { response ->
                    val raw = response.body?.string()
                    if (!response.isSuccessful) throw httpException("OpenAI", response.code, raw)
                    parseChatResponse(raw ?: throw IOException("Empty response body"))
                }
        }

    override fun streamChat(request: ChatRequest, config: ProviderConfig): Flow<ChatChunk> = flow {
        val caps = resolvedCapabilities(config)
        enforceAttachmentCapabilities(request, caps)
        val body = buildRequestBody(request, caps, modelOf(config), stream = true)
        val httpRequest = postRequest("${endpointOf(config)}/chat/completions", body, config)
            .newBuilder()
            .header("Accept", "text/event-stream")
            .build()
        http.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw httpException("OpenAI", response.code, response.body?.string())
            }
            val source = response.body?.source() ?: throw IOException("Empty stream body")
            val toolCalls = LinkedHashMap<Int, ToolCallAccumulator>()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val root = try {
                    JsonParser.parseString(data).asJsonObject
                } catch (e: Exception) {
                    continue
                }
                val delta = root.getAsJsonArray("choices")
                    ?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("delta") ?: continue
                delta.optString("content")?.let { text ->
                    if (text.isNotEmpty()) emit(ChatChunk(deltaText = text))
                }
                delta.getAsJsonArray("tool_calls")?.forEach { element ->
                    val toolCall = element.asJsonObject
                    val index = toolCall.get("index")?.asInt ?: 0
                    val acc = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                    toolCall.optString("id")?.let { if (acc.id == null) acc.id = it }
                    val function = toolCall.getAsJsonObject("function")
                    function?.optString("name")?.let { if (acc.name == null) acc.name = it }
                    function?.optString("arguments")?.let { acc.arguments.append(it) }
                }
            }
            for ((index, acc) in toolCalls) {
                emit(
                    ChatChunk(
                        deltaText = "",
                        toolCallDelta = ToolCall(
                            id = acc.id ?: "call_$index",
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
            val url = "${endpointOf(config)}/models"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer ${config.apiKey?.trim().orEmpty()}")
                .applyHeaders(config.extraHeaders)
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (response.code == 200) {
                        ConnectionResult(true, "Connected to $url")
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

    private fun endpointOf(config: ProviderConfig): String =
        config.endpoint?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: DEFAULT_ENDPOINT

    private fun modelOf(config: ProviderConfig): String =
        config.model.ifBlank { DEFAULT_MODEL }

    private fun requireApiKey(config: ProviderConfig): String {
        val key = config.apiKey?.trim()
        if (key.isNullOrEmpty()) {
            throw IllegalStateException("No API key configured for provider \"$displayName\".")
        }
        return key
    }

    private fun postRequest(url: String, body: String, config: ProviderConfig): Request =
        Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer ${requireApiKey(config)}")
            .applyHeaders(config.extraHeaders)
            .build()

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
        stream: Boolean,
    ): String {
        val messages = mutableListOf<Map<String, Any?>>()
        request.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
            messages += mapOf("role" to "system", "content" to prompt)
        }
        for (message in request.messages) {
            messages += openAiMessage(message)
        }
        val body = LinkedHashMap<String, Any?>()
        body["model"] = model
        body["messages"] = messages
        body["max_tokens"] = request.maxTokens
        val tools = CapabilityGate.filterTools(caps, request.tools)
        if (tools.isNotEmpty()) {
            body["tools"] = tools.map { tool ->
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "parameters" to parseSchema(tool.parametersSchemaJson),
                    ),
                )
            }
            body["tool_choice"] = "auto"
        }
        if (stream) body["stream"] = true
        return gson.toJson(body)
    }

    private fun openAiMessage(message: ChatMessage): Map<String, Any?> {
        val role = when (message.role) {
            Role.SYSTEM -> "system"
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.TOOL -> "tool"
        }
        val map = LinkedHashMap<String, Any?>()
        map["role"] = role
        if (message.role == Role.TOOL && message.toolCallId != null) {
            map["tool_call_id"] = message.toolCallId
        }
        val imageParts = message.attachments.filter { it.type == AttachmentType.IMAGE }
        val text = buildString {
            append(message.text)
            for (attachment in message.attachments) {
                when (attachment.type) {
                    AttachmentType.CODE ->
                        append("\n\n```\n").append(readTextAttachment(attachment)).append("\n```")
                    AttachmentType.DOCUMENT ->
                        append("\n\n[Attached file: ${attachment.displayName}]\n")
                            .append(readTextAttachment(attachment))
                    AttachmentType.IMAGE -> { /* sent as image_url parts below */ }
                    else -> throw UnsupportedCapabilityException(
                        "Attachment type ${attachment.type} is not supported by \"$displayName\"."
                    )
                }
            }
        }
        if (imageParts.isEmpty()) {
            map["content"] = text
        } else {
            val parts = mutableListOf<Map<String, Any?>>()
            if (text.isNotBlank()) parts += mapOf("type" to "text", "text" to text)
            for (image in imageParts) {
                parts += mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf(
                        "url" to "data:${image.mimeType};base64,${encodeAttachment(image)}"
                    ),
                )
            }
            map["content"] = parts
        }
        return map
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    private fun parseChatResponse(raw: String): ChatResponse {
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: JsonParseException) {
            throw IOException("Malformed OpenAI-compatible response: invalid JSON (${e.message})")
        } catch (e: IllegalStateException) {
            // CancellationException herda IllegalStateException: nunca engolir cancelamento.
            if (e is CancellationException) throw e
            throw IOException("Malformed OpenAI-compatible response: root is not a JSON object")
        }
        val message = root.getAsJsonArray("choices")
            ?.firstOrNull()?.asJsonObject
            ?.getAsJsonObject("message")
            ?: throw IOException("Malformed response: missing choices[0].message")
        val content = message.optString("content") ?: ""
        val toolCalls = message.getAsJsonArray("tool_calls")?.mapNotNull { element ->
            val toolCall = element.asJsonObject
            // Some OpenAI-compatible endpoints (Ollama, LM Studio, proxies) may
            // emit tool_calls without the "function" object; skip those instead
            // of crashing with an NPE on the platform type.
            val function = toolCall.getAsJsonObject("function") ?: return@mapNotNull null
            ToolCall(
                id = toolCall.optString("id") ?: "call_${function.optString("name")}",
                name = function.optString("name") ?: "unknown",
                argumentsJson = function.optString("arguments") ?: "{}",
            )
        }.orEmpty()
        return ChatResponse(content, toolCalls)
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

    /** Accumulates streamed `tool_calls` deltas keyed by their `index`. */
    private class ToolCallAccumulator {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
