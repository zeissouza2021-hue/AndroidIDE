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

package dev.mutwakil.androidide.dbinspector

import android.content.Context
import android.content.Intent
import android.view.MenuItem
import androidx.core.content.ContextCompat
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.actions.ActionData
import dev.mutwakil.androidide.actions.EditorActivityAction
import dev.mutwakil.androidide.actions.markInvisible
import dev.mutwakil.androidide.actions.openApplicationModuleChooser
import dev.mutwakil.androidide.projects.IProjectManager
import dev.mutwakil.androidide.utils.flashError
import org.slf4j.LoggerFactory

/**
 * Editor toolbar action that opens the [DbInspectorActivity] for the selected
 * application module's package.
 *
 * Register in `EditorActivityActions` (shown in the toolbar overflow menu) and
 * declare [DbInspectorActivity] in the manifest. The inspector needs the app
 * to be installed as a debuggable build; that is checked when the activity
 * loads, not here.
 */
class DbInspectorAction(context: Context, override val order: Int) : EditorActivityAction() {

  override val id: String = "ide.editor.dbInspector"
  override var requiresUIThread: Boolean = true

  init {
    label = context.getString(R.string.db_inspector_action_title)
    icon = ContextCompat.getDrawable(context, R.drawable.db_inspector_ic_database)
  }

  companion object {
    private val log = LoggerFactory.getLogger(DbInspectorAction::class.java)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    data.getActivity() ?: run {
      markInvisible()
      return
    }

    visible = true
    val projectManager = IProjectManager.getInstance()
    enabled = projectManager.getAndroidAppModules().isNotEmpty()
  }

  override suspend fun execAction(data: ActionData) {
    openApplicationModuleChooser(data) { app ->
      val variant = app.getSelectedVariant()

      log.debug("Selected variant: {}", variant?.name)

      if (variant == null) {
        flashError(R.string.err_selected_variant_not_found)
        return@openApplicationModuleChooser
      }

      val applicationId = variant.mainArtifact.applicationId
      if (applicationId == null) {
        log.error("Unable to inspect database. variant.mainArtifact.applicationId is null")
        flashError(R.string.err_cannot_determine_package)
        return@openApplicationModuleChooser
      }

      log.info("Inspecting databases of application: {}", applicationId)

      val activity = data.requireActivity()
      val intent = Intent(activity, DbInspectorActivity::class.java)
        .putExtra(DbInspectorActivity.EXTRA_PACKAGE_NAME, applicationId)
        .putExtra(DbInspectorActivity.EXTRA_MODULE_PATH, app.path)
      activity.startActivity(intent)
    }
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    // prefer showing this in the overflow menu
    return MenuItem.SHOW_AS_ACTION_NEVER
  }
}
