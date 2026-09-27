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

package dev.mutwakil.androidide.actions.ai

import android.content.Context
import android.content.Intent
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.actions.ActionData
import dev.mutwakil.androidide.actions.EditorActivityAction
import dev.mutwakil.androidide.aiagent.AiAgent
import dev.mutwakil.androidide.aiagent.ui.AiChatActivity
import dev.mutwakil.androidide.aiagent.ui.AiChatV2PanelDialog
import dev.mutwakil.androidide.editor.ui.IDEEditor
import dev.mutwakil.androidide.projects.IProjectManager
import java.io.File

/**
 * Editor toolbar action that opens the AI chat for the currently open project.
 *
 * v2: toggles the floating panel ([AiChatV2PanelDialog]) sliding over the
 * editor, with the current file + selection passed as initial context.
 * If the panel fails to open, falls back to the classic [AiChatActivity].
 */
class AiChatAction(context: Context, override val order: Int) : EditorActivityAction() {

  override val id: String = "ide.editor.aiChat"
  override var requiresUIThread: Boolean = true

  init {
    label = context.getString(R.string.aiagent_chat_title)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_aiagent_chat)
  }

  override suspend fun execAction(data: ActionData): Any {
    val activity = data.requireActivity()
    val projectDir = IProjectManager.getInstance().projectDir

    val filePath = data.get(File::class.java)?.absolutePath
    val selection = captureSelection(data)

    val opened = runCatching {
      val compat = activity as? AppCompatActivity
        ?: error("Activity não é AppCompatActivity")
      AiChatV2PanelDialog.toggle(
        compat.supportFragmentManager,
        projectDir.absolutePath,
        filePath,
        selection
      )
    }.isSuccess

    if (!opened) {
      val intent = Intent(activity, AiChatActivity::class.java)
        .putExtra(AiAgent.EXTRA_PROJECT_PATH, projectDir.absolutePath)
      activity.startActivity(intent)
    }
    return true
  }

  /** Captures the current editor selection (capped) as initial chat context. */
  private fun captureSelection(data: ActionData): String? {
    val editor = data.get(IDEEditor::class.java) ?: return null
    return runCatching {
      val cursor = editor.text.cursor
      if (!cursor.isSelected) return null
      val selected = editor.text.subSequence(cursor.left(), cursor.right()).toString()
      selected.take(MAX_SELECTION_CHARS).takeIf { it.isNotBlank() }
    }.getOrNull()
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    // v2: chat fica sempre visível na toolbar (item 1).
    return MenuItem.SHOW_AS_ACTION_ALWAYS
  }

  companion object {
    /** Teto da seleção inicial levada como contexto (8k caracteres). */
    const val MAX_SELECTION_CHARS: Int = 8000
  }
}
