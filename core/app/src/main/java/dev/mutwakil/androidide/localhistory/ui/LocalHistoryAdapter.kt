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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.localhistory.LocalHistorySnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter showing the snapshots of a single file, newest first.
 */
class LocalHistoryAdapter(
  private val onSelect: (LocalHistorySnapshot) -> Unit
) : RecyclerView.Adapter<LocalHistoryAdapter.ViewHolder>() {

  private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

  private var snapshots: List<LocalHistorySnapshot> = emptyList()
  private var selectedPosition: Int = RecyclerView.NO_POSITION

  val selectedSnapshot: LocalHistorySnapshot?
    get() = snapshots.getOrNull(selectedPosition)

  fun submitList(newSnapshots: List<LocalHistorySnapshot>) {
    snapshots = newSnapshots
    selectedPosition = RecyclerView.NO_POSITION
    notifyDataSetChanged()
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val view = LayoutInflater.from(parent.context)
      .inflate(R.layout.local_history_item_snapshot, parent, false)
    return ViewHolder(view).also { it.defaultBackground = view.background }
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val snapshot = snapshots[position]
    holder.time.text = dateFormat.format(Date(snapshot.timestamp))
    holder.size.text = formatSize(snapshot.size)

    val selected = position == selectedPosition
    holder.itemView.isSelected = selected
    if (selected) {
      holder.itemView.setBackgroundColor(
        MaterialColors.getColor(
          holder.itemView,
          com.google.android.material.R.attr.colorPrimaryContainer,
          android.graphics.Color.TRANSPARENT
        )
      )
    } else {
      holder.itemView.background = holder.defaultBackground
    }
    holder.itemView.setOnClickListener {
      val previous = selectedPosition
      selectedPosition = holder.bindingAdapterPosition
      if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
      if (selectedPosition != RecyclerView.NO_POSITION) notifyItemChanged(selectedPosition)
      selectedSnapshot?.let(onSelect)
    }
  }

  override fun getItemCount(): Int = snapshots.size

  private fun formatSize(bytes: Long): String {
    return when {
      bytes < 1024 -> "$bytes B"
      bytes < 1024 * 1024 -> "${bytes / 1024} KB"
      else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
    }
  }

  class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val time: TextView = view.findViewById(R.id.local_history_item_time)
    val size: TextView = view.findViewById(R.id.local_history_item_size)
    var defaultBackground: android.graphics.drawable.Drawable? = null
  }
}
