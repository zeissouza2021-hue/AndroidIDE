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

package dev.mutwakil.androidide.apkanalyzer

import android.content.Context
import android.content.Intent
import android.view.MenuItem
import androidx.core.content.ContextCompat
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.actions.ActionData
import dev.mutwakil.androidide.actions.EditorActivityAction
import dev.mutwakil.androidide.actions.markInvisible

/**
 * Editor toolbar action that opens the [ApkAnalyzerActivity].
 *
 * Unlike [dev.mutwakil.androidide.assetstudio.AssetStudioAction], this does
 * not require an open project: any APK file can be analyzed.
 *
 * Register in `EditorActivityActions.register()` (alongside the other editor
 * toolbar actions) and declare the activity in `AndroidManifest.xml`.
 */
class ApkAnalyzerAction(context: Context, override val order: Int) : EditorActivityAction() {

  override val id: String = "ide.tools.apkAnalyzer"
  override var requiresUIThread: Boolean = true

  init {
    label = context.getString(R.string.apk_analyzer_title)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_file_apk)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    if (data.getActivity() == null) {
      markInvisible()
    }
  }

  override suspend fun execAction(data: ActionData): Any {
    val activity = data.requireActivity()
    activity.startActivity(Intent(activity, ApkAnalyzerActivity::class.java))
    return true
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    return MenuItem.SHOW_AS_ACTION_NEVER
  }
}
