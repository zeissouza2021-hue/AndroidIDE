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

package dev.mutwakil.androidide.localhistory.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.activities.editor.EditorHandlerActivity
import dev.mutwakil.androidide.localhistory.LocalHistoryDiff
import dev.mutwakil.androidide.localhistory.LocalHistoryDiffLine
import dev.mutwakil.androidide.localhistory.LocalHistoryService
import dev.mutwakil.androidide.localhistory.LocalHistorySnapshot
import dev.mutwakil.androidide.localhistory.LocalHistoryStore
import dev.mutwakil.androidide.utils.DialogUtils
import dev.mutwakil.androidide.utils.flashError
import dev.mutwakil.androidide.utils.flashSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows the Local History of a single file: the list of automatic snapshots (newest first),
 * a diff of the selected snapshot against the current file content, and a restore action.
 *
 * Restoring rewrites the file on disk; the current content is snapshotted first so the
 * restore itself can be undone. Open editors showing the file are refreshed through
 * [EditorHandlerActivity.checkForExternalFileChanges].
 */
class LocalHistoryDialogFragment : DialogFragment() {

  companion object {
    private val log = LoggerFactory.getLogger(LocalHistoryDialogFragment::class.java)
    private const val ARG_FILE_PATH = "local_history.file_path"

    fun newInstance(fileAbsolutePath: String): LocalHistoryDialogFragment {
      return LocalHistoryDialogFragment().apply {
        arguments = Bundle().apply { putString(ARG_FILE_PATH, fileAbsolutePath) }
      }
    }
  }

  private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

  private lateinit var file: File
  private lateinit var adapter: LocalHistoryAdapter

  private lateinit var subtitle: TextView
  private lateinit var list: RecyclerView
  private lateinit var emptyView: TextView
  private lateinit var diffView: TextView
  private lateinit var restoreButton: MaterialButton

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val path = requireArguments().getString(ARG_FILE_PATH)
      ?: throw IllegalArgumentException("File path argument is required")
    file = File(path)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    return inflater.inflate(R.layout.local_history_dialog, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    // Make sure tracking is running even if the app-start wiring was skipped.
    runCatching { LocalHistoryService.ensureWatching() }

    subtitle = view.findViewById(R.id.local_history_subtitle)
    list = view.findViewById(R.id.local_history_list)
    emptyView = view.findViewById(R.id.local_history_empty)
    diffView = view.findViewById(R.id.local_history_diff)
    restoreButton = view.findViewById(R.id.local_history_btn_restore)
    val closeButton: MaterialButton = view.findViewById(R.id.local_history_btn_close)

    subtitle.text = file.name
    diffView.typeface = Typeface.MONOSPACE

    adapter = LocalHistoryAdapter { snapshot -> showDiff(snapshot) }
    list.layoutManager = LinearLayoutManager(requireContext())
    list.adapter = adapter

    restoreButton.setOnClickListener {
      val snapshot = adapter.selectedSnapshot
      if (snapshot == null) {
        flashError(R.string.local_history_select_snapshot)
      } else {
        confirmRestore(snapshot)
      }
    }
    closeButton.setOnClickListener { dismiss() }

    refresh()
  }

  override fun onStart() {
    super.onStart()
    // Use most of the screen: this dialog shows two scrollable panes.
    dialog?.window?.let { window ->
      val metrics = resources.displayMetrics
      window.setLayout(
        (metrics.widthPixels * 0.94).toInt(),
        (metrics.heightPixels * 0.84).toInt()
      )
    }
  }

  private fun refresh() {
    lifecycleScope.launch(Dispatchers.IO) {
      val snapshots = runCatching { LocalHistoryService.listSnapshotsFor(file) }
        .getOrDefault(emptyList())
      withContext(Dispatchers.Main) {
        if (!isAdded) return@withContext
        adapter.submitList(snapshots)
        val hasSnapshots = snapshots.isNotEmpty()
        list.visibility = if (hasSnapshots) View.VISIBLE else View.GONE
        emptyView.visibility = if (hasSnapshots) View.GONE else View.VISIBLE
        restoreButton.isEnabled = false
        diffView.text = ""
        snapshots.firstOrNull()?.let { subtitle.text = it.relativePath }
      }
    }
  }

  private fun showDiff(snapshot: LocalHistorySnapshot) {
    restoreButton.isEnabled = true
    lifecycleScope.launch(Dispatchers.IO) {
      val rendered = renderDiff(snapshot)
      withContext(Dispatchers.Main) {
        if (!isAdded) return@withContext
        diffView.text = rendered
      }
    }
  }

  private fun renderDiff(snapshot: LocalHistorySnapshot): CharSequence {
    val context = context ?: return ""
    return try {
      val store = LocalHistoryStore(context)
      val snapshotBytes = store.readSnapshot(snapshot)
      val currentBytes = runCatching { file.readBytes() }.getOrNull()
      if (snapshotBytes == null || currentBytes == null ||
        snapshotBytes.contains(0) || currentBytes.contains(0)
      ) {
        return context.getString(R.string.local_history_diff_unavailable)
      }
      val oldText = snapshotBytes.toString(Charsets.UTF_8)
      val newText = currentBytes.toString(Charsets.UTF_8)
      val lines = LocalHistoryDiff.diff(oldText, newText)
      if (lines.isEmpty()) {
        return context.getString(R.string.local_history_diff_identical)
      }
      val builder = SpannableStringBuilder()
      for (line in lines) {
        val prefix = when (line.kind) {
          LocalHistoryDiffLine.Kind.ADDED -> "+ "
          LocalHistoryDiffLine.Kind.REMOVED -> "- "
          LocalHistoryDiffLine.Kind.CONTEXT -> "  "
        }
        val start = builder.length
        builder.append(prefix).append(line.text).append('\n')
        val bg = when (line.kind) {
          LocalHistoryDiffLine.Kind.ADDED -> 0x334CAF50
          LocalHistoryDiffLine.Kind.REMOVED -> 0x33F44336
          LocalHistoryDiffLine.Kind.CONTEXT -> null
        }
        if (bg != null) {
          builder.setSpan(
            BackgroundColorSpan(bg),
            start,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
          )
        }
      }
      builder
    } catch (e: Exception) {
      log.warn("LocalHistory: diff render failed", e)
      context.getString(R.string.local_history_diff_unavailable)
    }
  }

  private fun confirmRestore(snapshot: LocalHistorySnapshot) {
    val whenTaken = dateFormat.format(Date(snapshot.timestamp))
    DialogUtils.newMaterialDialogBuilder(requireContext())
      .setTitle(R.string.local_history_restore_confirm_title)
      .setMessage(getString(R.string.local_history_restore_confirm, whenTaken))
      .setPositiveButton(R.string.local_history_restore) { _, _ -> doRestore(snapshot) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun doRestore(snapshot: LocalHistorySnapshot) {
    val activity = activity as? EditorHandlerActivity
    lifecycleScope.launch(Dispatchers.IO) {
      val ok = runCatching { LocalHistoryService.restoreSnapshot(snapshot) }.getOrDefault(false)
      withContext(Dispatchers.Main) {
        if (!isAdded) return@withContext
        if (ok) {
          flashSuccess(R.string.local_history_restored)
          // Refresh open editors showing this file (only clean buffers are replaced).
          activity?.checkForExternalFileChanges()
          refresh()
        } else {
          flashError(R.string.local_history_restore_failed)
        }
      }
    }
  }
}
