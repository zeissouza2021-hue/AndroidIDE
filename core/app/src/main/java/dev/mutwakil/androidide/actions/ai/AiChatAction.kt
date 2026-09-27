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
import androidx.core.content.ContextCompat
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.actions.ActionData
import dev.mutwakil.androidide.actions.EditorActivityAction
import dev.mutwakil.androidide.aiagent.AiAgent
import dev.mutwakil.androidide.aiagent.ui.AiChatActivity
import dev.mutwakil.androidide.projects.IProjectManager

/**
 * Editor toolbar action that opens the AI chat ([AiChatActivity]) for the
 * currently open project.
 *
 * Registered in `EditorActivityActions`; shown in the toolbar overflow menu.
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
    val intent = Intent(activity, AiChatActivity::class.java)
      .putExtra(AiAgent.EXTRA_PROJECT_PATH, projectDir.absolutePath)
    activity.startActivity(intent)
    return true
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    // prefer showing this in the overflow menu
    return MenuItem.SHOW_AS_ACTION_NEVER
  }
}
