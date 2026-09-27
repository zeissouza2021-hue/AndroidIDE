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

import dev.mutwakil.androidide.apkanalyzer.dex.DexParser

/** Size categories used by the breakdown. */
enum class ApkCategory {
  DEX,
  RES,
  ASSETS,
  LIB,
  OTHER,
}

/** A single entry of the APK (zip) listing. */
data class ApkEntryInfo(
  val name: String,
  val size: Long,
  val compressedSize: Long,
  val crc: Long,
  val category: ApkCategory,
  val stored: Boolean,
)

/** Full analysis result for one APK file. */
data class ApkReport(
  val fileName: String,
  val entries: List<ApkEntryInfo>,
  val totalSize: Long,
  /** Sum of compressed sizes: the best offline download-size estimate. */
  val downloadSizeEstimate: Long,
  val manifestXml: String?,
  val manifestError: String?,
  val dexInfos: List<DexParser.DexInfo>,
) {
  val fileCount: Int get() = entries.size
  val totalClasses: Int get() = dexInfos.sumOf { it.classCount }

  fun sizeByCategory(category: ApkCategory): Long =
    entries.filter { it.category == category }.sumOf { it.size }
}

/** Human-readable byte count, e.g. `1.2 MB`. */
fun formatBytes(bytes: Long): String {
  if (bytes < 0) return "?"
  if (bytes < 1024) return "$bytes B"
  val units = arrayOf("KB", "MB", "GB")
  var value = bytes.toDouble() / 1024.0
  var unit = 0
  while (value >= 1024 && unit < units.size - 1) {
    value /= 1024.0
    unit++
  }
  return if (value >= 100) "%d %s".format(value.toInt(), units[unit])
  else "%.1f %s".format(value, units[unit])
}

/** Signed variant used by the diff view, e.g. `+1.2 MB` / `-300 KB`. */
fun formatBytesSigned(bytes: Long): String {
  if (bytes == 0L) return "±0 B"
  val sign = if (bytes > 0) "+" else "-"
  return sign + formatBytes(kotlin.math.abs(bytes))
}
