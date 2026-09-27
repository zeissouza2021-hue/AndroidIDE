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

package dev.mutwakil.androidide.assetstudio

import android.content.Context
import android.content.Intent
import android.view.MenuItem
import androidx.core.content.ContextCompat
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.actions.ActionData
import dev.mutwakil.androidide.actions.EditorActivityAction
import dev.mutwakil.androidide.actions.markInvisible
import dev.mutwakil.androidide.projects.IProjectManager

/**
 * Editor toolbar action that opens the [AssetStudioActivity].
 *
 * Visible only when a project is open, since imported assets are written to
 * the app module's `res/` directory.
 *
 * Register in `EditorActivityActions.register()` (alongside the other editor
 * toolbar actions) and declare the activity in `AndroidManifest.xml`.
 */
class AssetStudioAction(context: Context, override val order: Int) : EditorActivityAction() {

  override val id: String = "ide.tools.assetStudio"
  override var requiresUIThread: Boolean = true

  init {
    label = context.getString(R.string.asset_studio_title)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_image)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val hasProject =
      try {
        IProjectManager.getInstance().projectDirPath != null
      } catch (_: Exception) {
        false
      }
    if (!hasProject) {
      markInvisible()
    }
  }

  override suspend fun execAction(data: ActionData): Any {
    val activity = data.requireActivity()
    activity.startActivity(Intent(activity, AssetStudioActivity::class.java))
    return true
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    return MenuItem.SHOW_AS_ACTION_NEVER
  }
}
