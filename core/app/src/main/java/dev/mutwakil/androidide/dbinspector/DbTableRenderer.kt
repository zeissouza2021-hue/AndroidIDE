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
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

/**
 * Renders query results into a [TableLayout]: a bold header row, a divider
 * and one row per record. Long values are ellipsized; columns shrink so the
 * table fits inside its [android.widget.HorizontalScrollView].
 */
object DbTableRenderer {

  fun render(
    table: TableLayout,
    columns: List<String>,
    rows: List<List<String?>>,
    nullText: String
  ) {
    val context = table.context
    table.removeAllViews()
    if (columns.isEmpty()) {
      return
    }
    table.addView(headerRow(context, columns))
    table.addView(divider(context))
    for (row in rows) {
      table.addView(dataRow(context, row, nullText))
    }
    table.isShrinkAllColumns = true
    table.isStretchAllColumns = true
  }

  private fun headerRow(context: Context, columns: List<String>): TableRow {
    val row = TableRow(context)
    for (column in columns) {
      val cell = cellView(context)
      cell.text = column
      cell.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
      row.addView(cell)
    }
    return row
  }

  private fun dataRow(context: Context, values: List<String?>, nullText: String): TableRow {
    val row = TableRow(context)
    for (value in values) {
      val cell = cellView(context)
      if (value == null) {
        cell.text = nullText
        cell.setTypeface(Typeface.MONOSPACE, Typeface.ITALIC)
        cell.alpha = 0.6f
      } else {
        cell.text = value
        cell.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
      }
      row.addView(cell)
    }
    return row
  }

  private fun cellView(context: Context): TextView {
    return TextView(context).apply {
      val padding = dp(context, 8)
      setPadding(padding, dp(context, 6), padding, dp(context, 6))
      maxLines = 4
      ellipsize = TextUtils.TruncateAt.END
      textSize = 13f
    }
  }

  private fun divider(context: Context): View {
    return View(context).apply {
      layoutParams = TableLayout.LayoutParams(
        TableLayout.LayoutParams.MATCH_PARENT,
        dp(context, 1)
      ).apply {
        setMargins(0, dp(context, 4), 0, dp(context, 4))
      }
      val typed = TypedValue()
      if (context.theme.resolveAttribute(android.R.attr.listDivider, typed, true)) {
        setBackgroundResource(typed.resourceId)
      }
    }
  }

  private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()
}
