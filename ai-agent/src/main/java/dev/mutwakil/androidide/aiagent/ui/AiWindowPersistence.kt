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
    }
}
