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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * [AiProviderPlugin] for the Google Gemini API
 * (`https://generativelanguage.googleapis.com/v1beta`).
 *
 * The API key travels as a `key` query parameter. Non-streaming calls use
 * `:generateContent`; streaming uses `:streamGenerateContent?alt=sse`.
 *
 * Capabilities are declared honestly per model: multimodal input
 * ([Capability.IMAGE_INPUT], [Capability.VISION], [Capability.VIDEO_INPUT],
 * [Capability.VOICE_INPUT] for audio attachments) is only advertised when
 * [isMultimodal] matches the model name. Note that "voice input" here means
 * *audio attachments* sent to the model — live microphone transcription is
 * handled by the app before reaching the provider.
 */
class GeminiProvider : AiProviderPlugin {

    override val id: String = "gemini"
    override val displayName: String = "Google Gemini"

    override val capabilities: Set<Capability> = setOf(
        Capability.TEXT_INPUT,
        Capability.CODE_GENERATION,
        Capability.FUNCTION_CALLING,
        Capability.TOOL_CALLING,
        Capability.STREAMING,
    )

    override val defaultModels: List<String> = listOf(
        "gemini-2.0-flash",
        "gemini-2.5-flash",
        "gemini-1.5-pro",
    )

    override val docsUrl: String? = "https://ai.google.dev/gemini-api/docs"

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MODEL = "gemini-2.0-flash"

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
     * plus image/vision/video/voice input when [isMultimodal] matches.
     */
    override fun resolvedCapabilities(config: ProviderConfig): Set<Capability> {
        val model = config.model.ifBlank { DEFAULT_MODEL }
        return if (isMultimodal(model)) {
            capabilities + setOf(
                Capability.IMAGE_INPUT,
                Capability.VISION,
                Capability.VIDEO_INPUT,
                Capability.VOICE_INPUT,
            )
        } else {
            capabilities
        }
    }

    /**
     * Returns true for multimodal Gemini models: the name (lowercased) contains
     * "gemini-1.5" or "gemini-2.". Defaults to text-only for unknown models.
     */
    private fun isMultimodal(model: String): Boolean {
        val name = model.lowercase()
        return name.contains("gemini-1.5") || name.contains("gemini-2.")
    }

    override suspend fun chat(request: ChatRequest, config: ProviderConfig): ChatResponse =
        withContext(Dispatchers.IO) {
            val caps = resolvedCapabilities(config)
            enforceAttachmentCapabilities(request, caps)
            val body = buildRequestBody(request, caps)
            val url = generateContentUrl(config, streaming = false)
            http.newCall(postRequest(url, body, config)).execute().use { response ->
                val raw = response.body?.string()
                if (!response.isSuccessful) throw httpException("Gemini", response.code, raw)
                parseResponse(raw ?: throw IOException("Empty response body"))
            }
        }

    /**
     * Streaming via `:streamGenerateContent?alt=sse`.
     *
     * Text parts are emitted as they arrive; `functionCall` parts are collected
     * and emitted as complete [ToolCall]s at the end of the stream, followed by
     * the final done chunk.
     */
    override fun streamChat(request: ChatRequest, config: ProviderConfig): Flow<ChatChunk> = flow {
        val caps = resolvedCapabilities(config)
        enforceAttachmentCapabilities(request, caps)
        val body = buildRequestBody(request, caps)
        val url = generateContentUrl(config, streaming = true)
        http.newCall(postRequest(url, body, config)).execute().use { response ->
            if (!response.isSuccessful) {
                throw httpException("Gemini", response.code, response.body?.string())
            }
            val source = response.body?.source() ?: throw IOException("Empty stream body")
            val pendingCalls = mutableListOf<ToolCall>()
            var callIndex = 0
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                val root = try {
                    JsonParser.parseString(data).asJsonObject
                } catch (e: Exception) {
                    continue
                }
                val parts = root.getAsJsonArray("candidates")
                    ?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("content")
                    ?.getAsJsonArray("parts") ?: continue
                for (element in parts) {
                    val part = element.asJsonObject
                    part.optString("text")?.let { text ->
                        if (text.isNotEmpty()) emit(ChatChunk(deltaText = text))
                    }
                    val functionCall = part.getAsJsonObject("functionCall")
                    if (functionCall != null) {
                        pendingCalls += ToolCall(
                            id = "gemini_call_${callIndex++}",
                            name = functionCall.optString("name") ?: "unknown",
                            argumentsJson = gson.toJson(functionCall.get("args") ?: JsonObject()),
                        )
                    }
                }
            }
            for (call in pendingCalls) {
                emit(ChatChunk(deltaText = "", toolCallDelta = call))
            }
            emit(ChatChunk(deltaText = "", done = true))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(config: ProviderConfig): ConnectionResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE_URL/v1beta/models?key=${encodedKey(config)}")
                .get()
                .applyHeaders(config.extraHeaders)
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string()
                    if (response.code != 200) {
                        return@use ConnectionResult(
                            false,
                            "HTTP ${response.code}: ${extractErrorMessage(raw)}",
                        )
                    }
                    val count = try {
                        JsonParser.parseString(raw).asJsonObject
                            .getAsJsonArray("models")?.size() ?: 0
                    } catch (e: Exception) {
                        0
                    }
                    if (count > 0) {
                        ConnectionResult(true, "Connected to Gemini API ($count models listed)")
                    } else {
                        ConnectionResult(false, "Connected, but the models list was empty")
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

    private fun encodedKey(config: ProviderConfig): String =
        URLEncoder.encode(requireApiKey(config), "UTF-8")

    private fun generateContentUrl(config: ProviderConfig, streaming: Boolean): String {
        val action = if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
        return "$BASE_URL/v1beta/models/${modelOf(config)}:$action?key=${encodedKey(config)}"
    }

    private fun postRequest(url: String, body: String, config: ProviderConfig): Request =
        Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
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

    private fun buildRequestBody(request: ChatRequest, caps: Set<Capability>): String {
        val body = LinkedHashMap<String, Any?>()
        val systemText = buildString {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { append(it) }
            for (message in request.messages) {
                if (message.role == Role.SYSTEM && message.text.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(message.text)
                }
            }
        }
        if (systemText.isNotBlank()) {
            body["systemInstruction"] = mapOf("parts" to listOf(mapOf("text" to systemText)))
        }
        val contents = mutableListOf<Map<String, Any?>>()
        for (message in mergedRoleMessages(request)) {
            val role = if (message.role == Role.ASSISTANT) "model" else "user"
            contents += mapOf("role" to role, "parts" to geminiParts(message))
        }
        body["contents"] = contents
        val tools = CapabilityGate.filterTools(caps, request.tools)
        if (tools.isNotEmpty()) {
            body["tools"] = listOf(
                mapOf(
                    "functionDeclarations" to tools.map { tool ->
                        mapOf(
                            "name" to tool.name,
                            "description" to tool.description,
                            "parameters" to parseSchema(tool.parametersSchemaJson),
                        )
                    }
                )
            )
        }
        body["generationConfig"] = mapOf("maxOutputTokens" to request.maxTokens)
        return gson.toJson(body)
    }

    /**
     * Drops SYSTEM messages (merged into `systemInstruction`) and merges
     * consecutive USER/model messages, since the API expects alternating roles.
     * TOOL messages are never merged: each keeps its own function name.
     */
    private fun mergedRoleMessages(request: ChatRequest): List<ChatMessage> {
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

    private fun geminiParts(message: ChatMessage): List<Map<String, Any?>> {
        if (message.role == Role.TOOL) {
            return listOf(
                mapOf(
                    "functionResponse" to mapOf(
                        "name" to (message.toolName ?: "unknown"),
                        "response" to mapOf("output" to message.text),
                    )
                )
            )
        }
        val parts = mutableListOf<Map<String, Any?>>()
        val text = StringBuilder(message.text)
        for (attachment in message.attachments) {
            when (attachment.type) {
                AttachmentType.IMAGE,
                AttachmentType.VIDEO,
                AttachmentType.AUDIO,
                AttachmentType.PDF,
                -> parts += mapOf(
                    "inlineData" to mapOf(
                        "mimeType" to attachment.mimeType,
                        "data" to encodeAttachment(attachment),
                    )
                )
                AttachmentType.CODE -> text.append("\n\n```\n")
                    .append(readTextAttachment(attachment))
                    .append("\n```")
                else -> throw UnsupportedCapabilityException(
                    "Attachment type ${attachment.type} is not supported by \"$displayName\"."
                )
            }
        }
        if (text.isNotBlank()) parts.add(0, mapOf("text" to text.toString()))
        return parts.ifEmpty { listOf(mapOf("text" to "")) }
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    private fun parseResponse(raw: String): ChatResponse {
        val root = JsonParser.parseString(raw).asJsonObject
        val parts = root.getAsJsonArray("candidates")
            ?.firstOrNull()?.asJsonObject
            ?.getAsJsonObject("content")
            ?.getAsJsonArray("parts")
            ?: throw IOException("Malformed Gemini response: missing candidates[0].content.parts")
        val text = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        for ((index, element) in parts.withIndex()) {
            val part = element.asJsonObject
            part.optString("text")?.let { text.append(it) }
            val functionCall = part.getAsJsonObject("functionCall")
            if (functionCall != null) {
                toolCalls += ToolCall(
                    id = "gemini_call_$index",
                    name = functionCall.optString("name") ?: "unknown",
                    argumentsJson = gson.toJson(functionCall.get("args") ?: JsonObject()),
                )
            }
        }
        return ChatResponse(text.toString(), toolCalls)
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
}
