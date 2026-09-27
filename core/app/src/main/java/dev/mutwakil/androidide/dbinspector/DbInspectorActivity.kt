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

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.app.EdgeToEdgeIDEActivity
import dev.mutwakil.androidide.databinding.DbInspectorActivityBinding
import dev.mutwakil.androidide.utils.DialogUtils
import dev.mutwakil.androidide.utils.flashSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Inspects the SQLite databases of the app being developed, inspired by the
 * Database Inspector in Android Studio.
 *
 * The target app's database files are pulled with `run-as` (which requires a
 * debuggable build) into the IDE's cache directory and opened read-only.
 * Nothing is ever written to the app's files unless the user explicitly
 * enables write mode (with confirmation) and confirms pushing changes back.
 *
 * Started by [DbInspectorAction] with [EXTRA_PACKAGE_NAME].
 */
class DbInspectorActivity : EdgeToEdgeIDEActivity() {

  companion object {
    const val EXTRA_PACKAGE_NAME = "dev.mutwakil.androidide.dbinspector.EXTRA_PACKAGE_NAME"
    const val EXTRA_MODULE_PATH = "dev.mutwakil.androidide.dbinspector.EXTRA_MODULE_PATH"

    private val log = LoggerFactory.getLogger(DbInspectorActivity::class.java)
  }

  private var _binding: DbInspectorActivityBinding? = null
  private val binding: DbInspectorActivityBinding
    get() = checkNotNull(_binding) { "Activity has been destroyed" }

  private var packageName: String = ""
  private var dbNames: List<String> = emptyList()

  private var snapshotFile: File? = null
  private var workingFile: File? = null
  private var readOnlyDb: SQLiteDatabase? = null
  private var workingDb: SQLiteDatabase? = null
  private var currentDbName: String? = null
  private var currentTable: String? = null

  private var writeMode = false
  private var workingCopyModified = false

  /** Generation counter: stale loads stop at the next checkpoint. */
  private var loadSeq = 0

  override fun bindLayout(): View {
    _binding = DbInspectorActivityBinding.inflate(layoutInflater)
    return binding.root
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()

    binding.apply {
      setSupportActionBar(dbInspectorToolbar)
      supportActionBar?.apply {
        setTitle(R.string.db_inspector_title)
        setDisplayHomeAsUpEnabled(true)
      }
      dbInspectorToolbar.setNavigationOnClickListener {
        onBackPressedDispatcher.onBackPressed()
      }
      dbInspectorPackageLabel.text = packageName

      dbInspectorTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) = selectTab(tab.position)
        override fun onTabUnselected(tab: TabLayout.Tab) = Unit
        override fun onTabReselected(tab: TabLayout.Tab) = Unit
      })

      dbInspectorRunButton.setOnClickListener { runCustomQuery() }
      dbInspectorPushButton.setOnClickListener { confirmPush() }
      dbInspectorWriteSwitch.setOnCheckedChangeListener { _, checked ->
        onWriteModeChanged(checked)
      }
    }

    if (packageName.isEmpty()) {
      showStatus(getString(R.string.db_inspector_err_no_package))
      return
    }
    reload()
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.db_inspector_menu, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      R.id.db_inspector_menu_refresh -> {
        reload()
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  override fun onDestroy() {
    loadSeq++ // invalidate in-flight loads
    closeDatabases()
    _binding = null
    super.onDestroy()
  }

  // -------------------------------------------------------------------------
  // Loading
  // -------------------------------------------------------------------------

  private fun reload() {
    closeDatabases()
    resetWriteModeUi()
    launchInspector { seq ->
      val checkError = withContext(Dispatchers.IO) {
        DbInspectorBackend.checkTargetApp(this@DbInspectorActivity, packageName)
          .exceptionOrNull()
      }
      checkSeq(seq)
      if (checkError != null) {
        showStatus(mapError(checkError))
        return@launchInspector
      }

      val listResult = withContext(Dispatchers.IO) {
        DbInspectorBackend.listDatabases(packageName)
      }
      checkSeq(seq)
      val listError = listResult.exceptionOrNull()
      if (listError != null) {
        showStatus(mapError(listError))
        return@launchInspector
      }
      dbNames = listResult.getOrThrow()

      populateDbSelector(dbNames)
      showContent(true)
      openDatabaseInternal(seq, dbNames.first())
    }
  }

  private suspend fun openDatabaseInternal(seq: Int, dbName: String) {
    resetWriteModeUi()
    binding.dbInspectorTable.removeAllViews()
    binding.dbInspectorRowsInfo.text = ""
    binding.dbInspectorQueryTable.removeAllViews()
    binding.dbInspectorQueryInfo.text = ""

    val pullResult = withContext(Dispatchers.IO) {
      DbInspectorBackend.pullDatabase(this@DbInspectorActivity, packageName, dbName)
    }
    checkSeq(seq)
    val pullError = pullResult.exceptionOrNull()
    if (pullError != null) {
      showStatus(mapError(pullError))
      return
    }
    val snapshot = pullResult.getOrThrow()

    val db = try {
      withContext(Dispatchers.IO) { DbInspectorBackend.openReadOnly(snapshot) }
    } catch (e: SQLiteException) {
      log.error("Could not open database {}", dbName, e)
      if (seq == loadSeq) {
        showStatus(getString(R.string.db_inspector_err_invalid_database, dbName))
      }
      return
    }
    checkSeq(seq)

    closeDatabases()
    snapshotFile = snapshot
    readOnlyDb = db
    currentDbName = dbName

    val tables = try {
      withContext(Dispatchers.IO) { DbInspectorBackend.listTables(db) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.error("Could not list tables in {}", dbName, e)
      emptyList()
    }
    checkSeq(seq)
    if (tables.isEmpty()) {
      showStatus(getString(R.string.db_inspector_err_no_tables, dbName))
      return
    }
    populateTableSelector(tables)
    showTableInternal(seq, tables.first())
  }

  private suspend fun showTableInternal(seq: Int, table: String) {
    val db = readOnlyDb ?: return
    currentTable = table
    val data = try {
      withContext(Dispatchers.IO) {
        DbInspectorBackend.readTable(db, table) to DbInspectorBackend.tableRowCount(db, table)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.error("Could not read table {}", table, e)
      if (seq == loadSeq) {
        showStatus(getString(R.string.db_inspector_query_error, e.message?.take(300)))
      }
      return
    }
    checkSeq(seq)
    val (result, total) = data
    binding.dbInspectorRowsInfo.text =
      getString(R.string.db_inspector_rows_info, result.rows.size, total)
    DbTableRenderer.render(
      binding.dbInspectorTable,
      result.columns,
      result.rows,
      getString(R.string.db_inspector_null)
    )
  }

  private fun launchInspector(block: suspend (seq: Int) -> Unit) {
    val seq = ++loadSeq
    lifecycleScope.launch {
      setLoading(true)
      try {
        block(seq)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.error("Database inspector failed", e)
        if (seq == loadSeq) {
          showStatus(getString(R.string.db_inspector_err_access_failed, e.message?.take(300)))
        }
      } finally {
        if (seq == loadSeq) {
          setLoading(false)
        }
      }
    }
  }

  private fun checkSeq(seq: Int) {
    if (seq != loadSeq) {
      throw CancellationException("stale inspector load")
    }
  }

  // -------------------------------------------------------------------------
  // Custom queries
  // -------------------------------------------------------------------------

  private fun runCustomQuery() {
    val sql = binding.dbInspectorQueryInput.text?.toString().orEmpty()
    if (sql.isBlank()) {
      binding.dbInspectorQueryInfo.setText(R.string.db_inspector_query_empty)
      return
    }
    val allowWrite = writeMode
    val db = if (allowWrite) workingDb else readOnlyDb
    if (db == null) {
      // Transient: the database is (re)loading.
      binding.dbInspectorQueryInfo.setText(R.string.db_inspector_status_loading)
      return
    }
    launchInspector { seq ->
      val result = try {
        withContext(Dispatchers.IO) {
          DbInspectorBackend.runQuery(db, sql, allowWrite) { size ->
            getString(R.string.db_inspector_blob, size)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: DbInspectorError.WriteNotAllowed) {
        if (seq == loadSeq) {
          binding.dbInspectorQueryInfo.setText(R.string.db_inspector_query_write_disabled)
        }
        return@launchInspector
      } catch (e: Exception) {
        log.error("Custom query failed", e)
        if (seq == loadSeq) {
          binding.dbInspectorQueryInfo.text =
            getString(R.string.db_inspector_query_error, e.message?.take(300))
        }
        return@launchInspector
      }
      checkSeq(seq)
      when {
        result.columns.isEmpty() -> {
          // A modifying statement was executed.
          binding.dbInspectorQueryInfo.text =
            getString(R.string.db_inspector_query_exec_ok, result.rowsAffected)
          binding.dbInspectorQueryTable.removeAllViews()
          if (allowWrite && result.rowsAffected != 0L) {
            workingCopyModified = true
            binding.dbInspectorPushButton.isVisible = true
          }
        }
        result.rows.isEmpty() -> {
          binding.dbInspectorQueryInfo.setText(R.string.db_inspector_query_empty_result)
          binding.dbInspectorQueryTable.removeAllViews()
        }
        else -> {
          binding.dbInspectorQueryInfo.text =
            getString(R.string.db_inspector_query_rows_returned, result.rows.size)
          DbTableRenderer.render(
            binding.dbInspectorQueryTable,
            result.columns,
            result.rows,
            getString(R.string.db_inspector_null)
          )
        }
      }
    }
  }

  // -------------------------------------------------------------------------
  // Write mode (explicit opt-in)
  // -------------------------------------------------------------------------

  private fun onWriteModeChanged(checked: Boolean) {
    if (checked == writeMode) {
      return
    }
    if (!checked) {
      setWriteMode(false)
      return
    }
    // Enabling write mode requires explicit confirmation.
    DialogUtils.newYesNoDialog(
      this,
      getString(R.string.db_inspector_confirm_write_title),
      getString(R.string.db_inspector_confirm_write_message),
      { _, _ -> setWriteMode(true) },
      { _, _ -> binding.dbInspectorWriteSwitch.isChecked = false }
    ).show()
  }

  private fun setWriteMode(enabled: Boolean) {
    if (!enabled) {
      writeMode = false
      workingCopyModified = false
      val db = workingDb
      workingDb = null
      workingFile = null
      lifecycleScope.launch(Dispatchers.IO) {
        try {
          db?.close()
        } catch (e: Exception) {
          log.warn("Error closing working db", e)
        }
      }
      binding.dbInspectorPushButton.isVisible = false
      return
    }
    val snapshot = snapshotFile
    if (snapshot == null || readOnlyDb == null) {
      binding.dbInspectorWriteSwitch.isChecked = false
      return
    }
    lifecycleScope.launch {
      val opened = withContext(Dispatchers.IO) {
        try {
          val work = DbInspectorBackend.makeWorkingCopy(snapshot)
          work to DbInspectorBackend.openReadWrite(work)
        } catch (e: Exception) {
          log.error("Could not enable write mode", e)
          null
        }
      }
      if (opened == null || snapshotFile !== snapshot) {
        if (opened != null) {
          withContext(Dispatchers.IO) {
            try {
              opened.second.close()
            } catch (e: Exception) {
              log.warn("Error closing stale working db", e)
            }
          }
        }
        binding.dbInspectorWriteSwitch.isChecked = false
        return@launch
      }
      workingFile = opened.first
      workingDb = opened.second
      writeMode = true
      workingCopyModified = false
      binding.dbInspectorPushButton.isVisible = false
    }
  }

  private fun confirmPush() {
    val dbName = currentDbName ?: return
    if (workingDb == null || !workingCopyModified) {
      return
    }
    DialogUtils.newYesNoDialog(
      this,
      getString(R.string.db_inspector_confirm_push_title),
      getString(R.string.db_inspector_confirm_push_message, dbName, packageName),
      { _, _ -> pushChanges(dbName) },
      null
    ).show()
  }

  private fun pushChanges(dbName: String) {
    val workFile = workingFile ?: return
    launchInspector { seq ->
      val pushError = withContext(Dispatchers.IO) {
        try {
          workingDb?.close()
        } catch (e: Exception) {
          log.warn("Error closing working db before push", e)
        }
        DbInspectorBackend.pushDatabase(packageName, dbName, workFile).exceptionOrNull()
      }
      workingDb = null
      checkSeq(seq)
      if (pushError != null) {
        log.error("Could not push database {}", dbName, pushError)
        // Reopen the working copy so the user's edits are not lost.
        withContext(Dispatchers.IO) {
          try {
            workingDb = DbInspectorBackend.openReadWrite(workFile)
          } catch (e: Exception) {
            log.error("Could not reopen working copy", e)
          }
        }
        checkSeq(seq)
        binding.dbInspectorQueryInfo.text =
          getString(R.string.db_inspector_push_failed, pushError.message?.take(300))
        return@launchInspector
      }
      workingCopyModified = false
      binding.dbInspectorPushButton.isVisible = false
      flashSuccess(R.string.db_inspector_push_ok)
      // Reload a fresh snapshot from the app.
      openDatabaseInternal(seq, dbName)
    }
  }

  private fun resetWriteModeUi() {
    writeMode = false
    workingCopyModified = false
    if (_binding != null) {
      binding.dbInspectorWriteSwitch.isChecked = false
      binding.dbInspectorPushButton.isVisible = false
    }
  }

  // -------------------------------------------------------------------------
  // UI helpers
  // -------------------------------------------------------------------------

  private fun populateDbSelector(names: List<String>) {
    val selector = binding.dbInspectorDbSelector
    selector.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names))
    selector.setText(names.first(), false)
    selector.setOnItemClickListener { parent, _, position, _ ->
      val name = parent.getItemAtPosition(position) as String
      if (name != currentDbName) {
        launchInspector { seq -> openDatabaseInternal(seq, name) }
      }
    }
  }

  private fun populateTableSelector(tables: List<String>) {
    val selector = binding.dbInspectorTableSelector
    selector.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, tables))
    selector.setText(tables.first(), false)
    selector.setOnItemClickListener { parent, _, position, _ ->
      val table = parent.getItemAtPosition(position) as String
      if (table != currentTable) {
        launchInspector { seq -> showTableInternal(seq, table) }
      }
    }
  }

  private fun selectTab(position: Int) {
    binding.dbInspectorTablesTab.isVisible = position == 0
    binding.dbInspectorQueryTab.isVisible = position == 1
  }

  private fun setLoading(loading: Boolean) {
    binding.dbInspectorProgress.isVisible = loading
  }

  private fun showStatus(message: CharSequence) {
    binding.dbInspectorTabs.isVisible = false
    binding.dbInspectorTabContainer.isVisible = false
    binding.dbInspectorDbSelectorLayout.isVisible = false
    binding.dbInspectorStatus.isVisible = true
    binding.dbInspectorStatus.text = message
  }

  private fun showContent(show: Boolean) {
    binding.dbInspectorTabs.isVisible = show
    binding.dbInspectorTabContainer.isVisible = show
    binding.dbInspectorDbSelectorLayout.isVisible = show
    binding.dbInspectorStatus.isVisible = !show
  }

  private fun closeDatabases() {
    try {
      readOnlyDb?.close()
    } catch (e: Exception) {
      log.warn("Error closing snapshot db", e)
    }
    try {
      workingDb?.close()
    } catch (e: Exception) {
      log.warn("Error closing working db", e)
    }
    readOnlyDb = null
    workingDb = null
    snapshotFile = null
    workingFile = null
    currentDbName = null
    currentTable = null
  }

  private fun mapError(e: Throwable): String {
    return when (e) {
      is DbInspectorError.AppNotInstalled ->
        getString(R.string.db_inspector_err_not_installed, e.packageName)
      is DbInspectorError.AppNotDebuggable ->
        getString(R.string.db_inspector_err_not_debuggable, e.packageName)
      is DbInspectorError.NoDatabases ->
        getString(R.string.db_inspector_err_no_databases, e.packageName)
      is DbInspectorError.AccessFailed ->
        getString(R.string.db_inspector_err_access_failed, e.details)
      is DbInspectorError.InvalidDatabase ->
        getString(R.string.db_inspector_err_invalid_database, e.dbName)
      is DbInspectorError.InvalidName ->
        getString(R.string.db_inspector_err_access_failed, e.what)
      else ->
        getString(R.string.db_inspector_err_access_failed, e.message?.take(300) ?: e.toString())
    }
  }
}
