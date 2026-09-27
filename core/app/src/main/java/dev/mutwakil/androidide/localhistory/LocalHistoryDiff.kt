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

/**
 * A single rendered line of a textual diff.
 */
data class LocalHistoryDiffLine(
  val kind: Kind,
  val text: String
) {
  enum class Kind { CONTEXT, ADDED, REMOVED }
}

/**
 * Minimal line-based diff used to preview what changed between a snapshot and the current
 * file content. Implemented with a classic LCS table; inputs are capped so the UI never
 * chokes on huge files.
 */
object LocalHistoryDiff {

  private const val MAX_LINES_PER_SIDE = 2_000
  private const val MAX_OUTPUT_LINES = 500
  private const val CONTEXT_LINES = 3

  /**
   * Diffs [oldText] (snapshot) against [newText] (current content) and returns the changed
   * hunks with [CONTEXT_LINES] lines of context around each change.
   */
  fun diff(oldText: String, newText: String): List<LocalHistoryDiffLine> {
    if (oldText == newText) return emptyList()
    val oldLines = oldText.lines()
    val newLines = newText.lines()
    if (oldLines.size > MAX_LINES_PER_SIDE || newLines.size > MAX_LINES_PER_SIDE) {
      // Too big for an LCS table on a phone; report a single placeholder hunk.
      return listOf(
        LocalHistoryDiffLine(LocalHistoryDiffLine.Kind.REMOVED, "... (${oldLines.size} lines)"),
        LocalHistoryDiffLine(LocalHistoryDiffLine.Kind.ADDED, "+++ (${newLines.size} lines)")
      )
    }

    val edits = lcsEdits(oldLines, newLines)
    return toHunks(edits).take(MAX_OUTPUT_LINES)
  }

  private enum class EditKind { SAME, DEL, ADD }

  private data class Edit(val kind: EditKind, val text: String)

  /** Full edit script (SAME/DEL/ADD per line) via an LCS dynamic-programming table. */
  private fun lcsEdits(oldLines: List<String>, newLines: List<String>): List<Edit> {
    val n = oldLines.size
    val m = newLines.size
    val table = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
      for (j in m - 1 downTo 0) {
        table[i][j] = if (oldLines[i] == newLines[j]) {
          table[i + 1][j + 1] + 1
        } else {
          maxOf(table[i + 1][j], table[i][j + 1])
        }
      }
    }
    val edits = ArrayList<Edit>(n + m)
    var i = 0
    var j = 0
    while (i < n && j < m) {
      when {
        oldLines[i] == newLines[j] -> {
          edits.add(Edit(EditKind.SAME, oldLines[i])); i++; j++
        }
        table[i + 1][j] >= table[i][j + 1] -> {
          edits.add(Edit(EditKind.DEL, oldLines[i])); i++
        }
        else -> {
          edits.add(Edit(EditKind.ADD, newLines[j])); j++
        }
      }
    }
    while (i < n) { edits.add(Edit(EditKind.DEL, oldLines[i])); i++ }
    while (j < m) { edits.add(Edit(EditKind.ADD, newLines[j])); j++ }
    return edits
  }

  /** Groups the edit script into hunks with context lines and separators. */
  private fun toHunks(edits: List<Edit>): List<LocalHistoryDiffLine> {
    val changed = edits.indices.filter { edits[it].kind != EditKind.SAME }.toSet()
    if (changed.isEmpty()) return emptyList()

    // Expand each changed index with context, then merge overlapping ranges.
    val ranges = changed.map { idx ->
      maxOf(0, idx - CONTEXT_LINES)..minOf(edits.size - 1, idx + CONTEXT_LINES)
    }.sortedBy { it.first }
    val merged = mutableListOf<IntRange>()
    for (range in ranges) {
      val last = merged.lastOrNull()
      if (last != null && range.first <= last.last + 1) {
        merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
      } else {
        merged.add(range)
      }
    }

    val out = ArrayList<LocalHistoryDiffLine>()
    merged.forEachIndexed { hunkIndex, range ->
      if (hunkIndex > 0) {
        out.add(LocalHistoryDiffLine(LocalHistoryDiffLine.Kind.CONTEXT, "…"))
      }
      for (idx in range) {
        val edit = edits[idx]
        val kind = when (edit.kind) {
          EditKind.SAME -> LocalHistoryDiffLine.Kind.CONTEXT
          EditKind.DEL -> LocalHistoryDiffLine.Kind.REMOVED
          EditKind.ADD -> LocalHistoryDiffLine.Kind.ADDED
        }
        out.add(LocalHistoryDiffLine(kind, edit.text))
      }
    }
    return out
  }
}
