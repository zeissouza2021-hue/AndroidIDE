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

/**
 * Heuristic size-reduction tips for an [ApkReport]:
 * largest files, byte-identical duplicates, native libraries per ABI and
 * heavy PNGs that are usually worth converting to WebP.
 */
object ApkTips {

  private const val TOP_N = 10
  private const val LARGE_PNG_THRESHOLD = 100 * 1024L

  data class TipReport(
    val largestFiles: List<ApkEntryInfo>,
    val duplicates: List<DuplicateGroup>,
    val suggestions: List<String>,
  )

  data class DuplicateGroup(
    val names: List<String>,
    val size: Long,
  ) {
    /** Bytes that could be saved by keeping a single copy. */
    val wasted: Long get() = size * (names.size - 1)
  }

  fun analyze(context: Context, report: ApkReport): TipReport {
    val largest = report.entries.filter { it.size > 0 }.take(TOP_N)

    val duplicates =
      report.entries
        .filter { it.size > 0 && it.crc != 0L }
        .groupBy { it.crc to it.size }
        .values
        .filter { group -> group.map { it.name }.toSet().size > 1 }
        .map { group ->
          DuplicateGroup(group.map { it.name }.sorted(), group.first().size)
        }
        .sortedByDescending { it.wasted }

    val suggestions = mutableListOf<String>()

    // Native libraries: one copy per ABI.
    val abis = report.entries.filter { it.category == ApkCategory.LIB }.mapNotNull { entry ->
      entry.name.removePrefix("lib/").substringBefore('/').takeIf { it != entry.name }
    }.toSet()
    if (abis.size > 1) {
      val libSize = report.sizeByCategory(ApkCategory.LIB)
      suggestions +=
        context.getString(
          R.string.apk_analyzer_tip_abis,
          abis.size,
          abis.sorted().joinToString(", "),
          formatBytes(libSize),
        )
    }

    // Heavy PNGs in res/.
    val heavyPngs =
      report.entries.filter {
        it.category == ApkCategory.RES && it.name.endsWith(".png") && it.size >= LARGE_PNG_THRESHOLD
      }
    if (heavyPngs.isNotEmpty()) {
      val total = heavyPngs.sumOf { it.size }
      suggestions +=
        context.getString(R.string.apk_analyzer_tip_png, heavyPngs.size, formatBytes(total))
    }

    // Duplicate content.
    val wasted = duplicates.sumOf { it.wasted }
    if (wasted > 0) {
      suggestions +=
        context.getString(R.string.apk_analyzer_tip_duplicates, duplicates.size, formatBytes(wasted))
    }

    // DEX weight.
    val dexSize = report.sizeByCategory(ApkCategory.DEX)
    if (report.totalSize > 0 && dexSize * 2 > report.totalSize) {
      suggestions += context.getString(R.string.apk_analyzer_tip_dex, formatBytes(dexSize))
    }

    // Uncompressed entries that would benefit from compression.
    val storedLarge =
      report.entries.filter { it.stored && it.size >= 512 * 1024 && it.category != ApkCategory.LIB }
    if (storedLarge.isNotEmpty()) {
      suggestions += context.getString(R.string.apk_analyzer_tip_stored, storedLarge.size)
    }

    return TipReport(largest, duplicates, suggestions)
  }

  /** Renders the tips as plain text for the tips tab. */
  fun format(context: Context, tips: TipReport): String {
    val sb = StringBuilder()

    sb.append("── ${context.getString(R.string.apk_analyzer_largest_files)} ──\n")
    tips.largestFiles.forEachIndexed { index, entry ->
      sb.append("${index + 1}. ${entry.name} — ${formatBytes(entry.size)}\n")
    }

    sb.append("\n── ${context.getString(R.string.apk_analyzer_duplicates)} ──\n")
    if (tips.duplicates.isEmpty()) {
      sb.append(context.getString(R.string.apk_analyzer_no_duplicates) + "\n")
    } else {
      tips.duplicates.take(10).forEach { group ->
        sb.append("• ${formatBytes(group.size)} × ${group.names.size} " +
          "(wastes ${formatBytes(group.wasted)}):\n")
        group.names.take(5).forEach { sb.append("    $it\n") }
        if (group.names.size > 5) {
          sb.append("    " + context.getString(R.string.apk_analyzer_and_more, group.names.size - 5) + "\n")
        }
      }
    }

    sb.append("\n── ${context.getString(R.string.apk_analyzer_suggestions)} ──\n")
    if (tips.suggestions.isEmpty()) {
      sb.append(context.getString(R.string.apk_analyzer_nothing_obvious) + "\n")
    } else {
      tips.suggestions.forEach { sb.append("• $it\n") }
    }
    return sb.toString()
  }
}
