/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.mutwakil.androidide.aiagent.ui

import android.content.Context
import android.graphics.Rect
import dev.mutwakil.androidide.aiagent.model.AgentMode
import dev.mutwakil.androidide.aiagent.model.Attachment
import dev.mutwakil.androidide.aiagent.model.AttachmentType
import dev.mutwakil.androidide.aiagent.model.ChatMessage
import dev.mutwakil.androidide.aiagent.model.Role
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted geometry for one floating AI Chat window.
 *
 * Geometry is keyed by a stable window id, never by the project or provider,
 * so two windows in the same project cannot overwrite each other's placement.
 * Conversation data remains owned by the window's Fragment/ViewModel.
 */
internal data class AiWindowGeometry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean,
    val minimized: Boolean,
    val savedHeight: Int,
    val restoreRect: Rect?
)

/** A model turn plus the text shown in its chat bubble. */
internal data class AiPersistedTurn(
    val message: ChatMessage,
    val displayText: String
)

/** Small persistence boundary for floating-window placement and window state. */
internal class AiWindowPersistence(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(windowId: String): AiWindowGeometry? {
        val p = prefix(windowId)
        if (!prefs.contains(p + "width") || !prefs.contains(p + "height")) return null

        val restoreRect = if (prefs.getBoolean(p + "has_restore_rect", false)) {
            Rect(
                prefs.getInt(p + "restore_left", 0),
                prefs.getInt(p + "restore_top", 0),
                prefs.getInt(p + "restore_right", 0),
                prefs.getInt(p + "restore_bottom", 0)
            )
        } else {
            null
        }

        return AiWindowGeometry(
            x = prefs.getInt(p + "x", 0),
            y = prefs.getInt(p + "y", 0),
            width = prefs.getInt(p + "width", 0),
            height = prefs.getInt(p + "height", 0),
            maximized = prefs.getBoolean(p + "maximized", false),
            minimized = prefs.getBoolean(p + "minimized", false),
            savedHeight = prefs.getInt(p + "saved_height", 0),
            restoreRect = restoreRect
        )
    }

    fun loadActiveConfigId(windowId: String): String? =
        prefs.getString(prefix(windowId) + "active_config_id", null)

    fun loadMode(windowId: String): AgentMode? =
        prefs.getString(prefix(windowId) + "mode", null)?.let { stored ->
            runCatching { AgentMode.valueOf(stored) }.getOrNull()
        }

    fun saveMode(windowId: String, mode: AgentMode) {
        prefs.edit().putString(prefix(windowId) + "mode", mode.name).apply()
    }

    fun loadConversation(windowId: String): List<AiPersistedTurn> {
        val raw = prefs.getString(prefix(windowId) + "conversation", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val role = runCatching { Role.valueOf(item.optString("role")) }.getOrNull()
                        ?: continue
                    if (role != Role.USER && role != Role.ASSISTANT) continue
                    val attachments = mutableListOf<Attachment>()
                    val attachmentArray = item.optJSONArray("attachments") ?: JSONArray()
                    val unavailable = mutableListOf<String>()
                    for (attachmentIndex in 0 until attachmentArray.length()) {
                        val stored = attachmentArray.optJSONObject(attachmentIndex) ?: continue
                        val path = stored.optString("path")
                        val file = path.takeIf { it.isNotBlank() }?.let(::File)
                        val type = runCatching {
                            AttachmentType.valueOf(stored.optString("type"))
                        }.getOrNull()
                        if (file == null || !file.isFile || type == null) {
                            stored.optString("displayName").takeIf { it.isNotBlank() }
                                ?.let(unavailable::add)
                            continue
                        }
                        attachments += Attachment(
                            type = type,
                            file = file,
                            mimeType = stored.optString("mimeType"),
                            displayName = stored.optString("displayName", file.name)
                        )
                    }
                    var text = item.optString("text")
                    if (unavailable.isNotEmpty()) {
                        text += "\n\n[Anexos indisponíveis após reiniciar: " +
                            unavailable.joinToString(", ") + "]"
                    }
                    val message = ChatMessage(role, text, attachments)
                    add(AiPersistedTurn(message, item.optString("displayText", text)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveConversation(windowId: String, turns: List<AiPersistedTurn>) {
        val selected = turns.takeLast(MAX_PERSISTED_MESSAGES)
        val array = JSONArray()
        selected.forEach { turn ->
            val message = turn.message
            val item = JSONObject()
                .put("role", message.role.name)
                .put("text", message.text)
                .put("displayText", turn.displayText)
            val attachments = JSONArray()
            message.attachments.forEach { attachment ->
                attachments.put(
                    JSONObject()
                        .put("type", attachment.type.name)
                        .put("path", attachment.file.absolutePath)
                        .put("mimeType", attachment.mimeType)
                        .put("displayName", attachment.displayName)
                )
            }
            item.put("attachments", attachments)
            array.put(item)
        }
        prefs.edit().putString(prefix(windowId) + "conversation", array.toString()).apply()
    }

    fun saveActiveConfigId(windowId: String, configId: String?) {
        val key = prefix(windowId) + "active_config_id"
        if (configId.isNullOrBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, configId).apply()
        }
    }

    fun save(windowId: String, geometry: AiWindowGeometry) {
        val p = prefix(windowId)
        prefs.edit().apply {
            putInt(p + "x", geometry.x)
            putInt(p + "y", geometry.y)
            putInt(p + "width", geometry.width)
            putInt(p + "height", geometry.height)
            putBoolean(p + "maximized", geometry.maximized)
            putBoolean(p + "minimized", geometry.minimized)
            putInt(p + "saved_height", geometry.savedHeight)
            val rect = geometry.restoreRect
            putBoolean(p + "has_restore_rect", rect != null)
            if (rect != null) {
                putInt(p + "restore_left", rect.left)
                putInt(p + "restore_top", rect.top)
                putInt(p + "restore_right", rect.right)
                putInt(p + "restore_bottom", rect.bottom)
            } else {
                remove(p + "restore_left")
                remove(p + "restore_top")
                remove(p + "restore_right")
                remove(p + "restore_bottom")
            }
        }.apply()
    }

    fun remove(windowId: String) {
        val p = prefix(windowId)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(p) }.forEach { key ->
            editor.remove(key)
        }
        editor.apply()
    }

    private fun prefix(windowId: String) = "window.$windowId."

    private companion object {
        const val PREFS_NAME = "aiagent_window_state_v1"
        const val MAX_PERSISTED_TURNS = 40
    }
}
