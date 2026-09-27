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
import dev.mutwakil.androidide.R

/** Result of comparing two APK reports (old vs new). */
data class ApkDiffResult(
  val oldName: String,
  val newName: String,
  val totalSizeDelta: Long,
  val downloadSizeDelta: Long,
  val categoryDeltas: Map<ApkCategory, Long>,
  val added: List<ApkEntryInfo>,
  val removed: List<ApkEntryInfo>,
  val changed: List<ChangedEntry>,
  val classCountDelta: Int,
)

data class ChangedEntry(
  val name: String,
  val oldSize: Long,
  val newSize: Long,
)

object ApkDiff {

  fun diff(old: ApkReport, new: ApkReport): ApkDiffResult {
    val oldByName = old.entries.associateBy { it.name }
    val newByName = new.entries.associateBy { it.name }

    val added = new.entries.filter { it.name !in oldByName }.sortedByDescending { it.size }
    val removed = old.entries.filter { it.name !in newByName }.sortedByDescending { it.size }
    val changed =
      new.entries.mapNotNull { newEntry ->
        val oldEntry = oldByName[newEntry.name] ?: return@mapNotNull null
        if (oldEntry.size != newEntry.size || oldEntry.crc != newEntry.crc) {
          ChangedEntry(newEntry.name, oldEntry.size, newEntry.size)
        } else {
          null
        }
      }.sortedByDescending { kotlin.math.abs(it.newSize - it.oldSize) }

    val categoryDeltas =
      ApkCategory.values().associateWith { category ->
        new.sizeByCategory(category) - old.sizeByCategory(category)
      }

    return ApkDiffResult(
      oldName = old.fileName,
      newName = new.fileName,
      totalSizeDelta = new.totalSize - old.totalSize,
      downloadSizeDelta = new.downloadSizeEstimate - old.downloadSizeEstimate,
      categoryDeltas = categoryDeltas,
      added = added,
      removed = removed,
      changed = changed,
      classCountDelta = new.totalClasses - old.totalClasses,
    )
  }

  /** Renders the diff as plain text for the compare tab. */
  fun format(context: Context, result: ApkDiffResult): String {
    val sb = StringBuilder()
    sb.append("${result.oldName}  →  ${result.newName}\n\n")
    sb.append("${context.getString(R.string.apk_analyzer_total_size)}: ${formatBytesSigned(result.totalSizeDelta)}\n")
    sb.append("${context.getString(R.string.apk_analyzer_download_size)}: ${formatBytesSigned(result.downloadSizeDelta)}\n")
    sb.append("${context.getString(R.string.apk_analyzer_tab_classes)}: ${signedInt(result.classCountDelta)}\n\n")

    sb.append("── ${context.getString(R.string.apk_analyzer_by_category)} ──\n")
    for (category in ApkCategory.values()) {
      val delta = result.categoryDeltas[category] ?: 0L
      if (delta != 0L) {
        sb.append("${categoryLabel(context, category)}: ${formatBytesSigned(delta)}\n")
      }
    }

    if (result.added.isNotEmpty()) {
      sb.append("\n── ${context.getString(R.string.apk_analyzer_added)} (${result.added.size}) ──\n")
      result.added.take(20).forEach { sb.append("+ ${it.name} (${formatBytes(it.size)})\n") }
      if (result.added.size > 20) {
        sb.append(context.getString(R.string.apk_analyzer_and_more, result.added.size - 20) + "\n")
      }
    }
    if (result.removed.isNotEmpty()) {
      sb.append("\n── ${context.getString(R.string.apk_analyzer_removed)} (${result.removed.size}) ──\n")
      result.removed.take(20).forEach { sb.append("- ${it.name} (${formatBytes(it.size)})\n") }
      if (result.removed.size > 20) {
        sb.append(context.getString(R.string.apk_analyzer_and_more, result.removed.size - 20) + "\n")
      }
    }
    if (result.changed.isNotEmpty()) {
      sb.append("\n── ${context.getString(R.string.apk_analyzer_changed)} (${result.changed.size}) ──\n")
      result.changed.take(20).forEach {
        sb.append("~ ${it.name} (${formatBytes(it.oldSize)} → ${formatBytes(it.newSize)})\n")
      }
      if (result.changed.size > 20) {
        sb.append(context.getString(R.string.apk_analyzer_and_more, result.changed.size - 20) + "\n")
      }
    }
    if (result.added.isEmpty() && result.removed.isEmpty() && result.changed.isEmpty()) {
      sb.append("\n${context.getString(R.string.apk_analyzer_no_differences)}")
    }
    return sb.toString()
  }

  fun categoryLabel(context: Context, category: ApkCategory): String {
    return when (category) {
      ApkCategory.DEX -> context.getString(R.string.apk_analyzer_cat_dex)
      ApkCategory.RES -> context.getString(R.string.apk_analyzer_cat_res)
      ApkCategory.ASSETS -> context.getString(R.string.apk_analyzer_cat_assets)
      ApkCategory.LIB -> context.getString(R.string.apk_analyzer_cat_lib)
      ApkCategory.OTHER -> context.getString(R.string.apk_analyzer_cat_other)
    }
  }

  private fun signedInt(value: Int): String {
    return when {
      value > 0 -> "+$value"
      value < 0 -> "$value"
      else -> "±0"
    }
  }
}
