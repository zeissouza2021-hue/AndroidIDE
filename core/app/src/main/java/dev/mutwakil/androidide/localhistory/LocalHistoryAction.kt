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

package dev.mutwakil.androidide.localhistory

import android.content.Context
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.actions.ActionData
import dev.mutwakil.androidide.actions.filetree.BaseFileTreeAction
import dev.mutwakil.androidide.actions.get
import dev.mutwakil.androidide.actions.markInvisible
import dev.mutwakil.androidide.actions.requireFile
import dev.mutwakil.androidide.localhistory.ui.LocalHistoryDialogFragment
import java.io.File

/**
 * File-tree action that opens the Local History dialog for the selected file.
 *
 * NOTE: this action must be registered in [dev.mutwakil.androidide.utils.EditorActivityActions]
 * (file tree actions section) to appear in the IDE:
 * ```
 * registry.registerAction(LocalHistoryAction(context, order++))
 * ```
 */
class LocalHistoryAction(context: Context, override val order: Int) :
  BaseFileTreeAction(
    context,
    labelRes = R.string.local_history_action,
    iconRes = R.drawable.local_history_ic_history
  ) {

  override val id: String = "ide.editor.fileTree.localHistory"

  private var dialog: LocalHistoryDialogFragment? = null

  override fun prepare(data: ActionData) {
    super.prepare(data)
    // Only regular, tracked files have a history. Directories and binaries are hidden.
    val context = data.get<Context>()
    if (context != null) {
      runCatching {
        LocalHistoryService.attach(context)
        LocalHistoryService.ensureWatching()
      }
    }
    val file: File? = data.get()
    if (file == null || !file.isFile || !LocalHistoryService.isTracking(file)) {
      markInvisible()
      return
    }
    visible = true
    enabled = true
  }

  override suspend fun execAction(data: ActionData): Any {
    val activity = data.requireActivity()
    runCatching {
      LocalHistoryService.attach(activity)
      LocalHistoryService.ensureWatching()
    }
    val file = data.requireFile()
    try {
      dialog?.dismiss()
    } catch (ignored: Exception) {
      // ignored
    }
    dialog = LocalHistoryDialogFragment.newInstance(file.absolutePath)
    return dialog!!
  }

  override fun postExec(data: ActionData, result: Any) {
    val fragment = result as? LocalHistoryDialogFragment ?: return
    fragment.show(data.requireActivity().supportFragmentManager, id)
  }

  override fun destroy() {
    super.destroy()
    try {
      dialog?.dismiss()
    } catch (ignored: Exception) {
      // ignored
    }
    dialog = null
  }
}
