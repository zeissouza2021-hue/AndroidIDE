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

package dev.mutwakil.androidide.aiagent.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import dev.mutwakil.androidide.aiagent.R
import dev.mutwakil.androidide.aiagent.agent.EditReviewDecision
import dev.mutwakil.androidide.aiagent.model.Attachment

private const val VIEW_TYPE_USER = 0
private const val VIEW_TYPE_ASSISTANT = 1
private const val VIEW_TYPE_STATUS = 2
private const val VIEW_TYPE_PLAN = 3
private const val VIEW_TYPE_TOOL = 4
private const val VIEW_TYPE_ERROR = 5
private const val VIEW_TYPE_DIFF = 6

/**
 * RecyclerView adapter for the chat message list. One view type per
 * [ChatListItem] subtype; colors come from the app theme (`?attr/`) so the
 * chat follows light/dark mode automatically.
 *
 * @param onDiffDecision invoked when the user decides on a diff-review card:
 *   the card id and the decision (apply all / some / discard).
 */
class ChatMessageAdapter(
  private val onDiffDecision: (id: String, decision: EditReviewDecision) -> Unit = { _, _ -> }
) :
  ListAdapter<ChatListItem, RecyclerView.ViewHolder>(ChatDiffCallback()) {

  override fun getItemViewType(position: Int): Int =
    when (getItem(position)) {
      is ChatListItem.User -> VIEW_TYPE_USER
      is ChatListItem.Assistant -> VIEW_TYPE_ASSISTANT
      is ChatListItem.Status -> VIEW_TYPE_STATUS
      is ChatListItem.PlanItem -> VIEW_TYPE_PLAN
      is ChatListItem.ToolEvent -> VIEW_TYPE_TOOL
      is ChatListItem.Error -> VIEW_TYPE_ERROR
      is ChatListItem.DiffProposal -> VIEW_TYPE_DIFF
    }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val inflater = LayoutInflater.from(parent.context)
    return when (viewType) {
      VIEW_TYPE_USER -> UserViewHolder(
        inflater.inflate(R.layout.item_ai_chat_user, parent, false)
      )
      VIEW_TYPE_ASSISTANT -> AssistantViewHolder(
        inflater.inflate(R.layout.item_ai_chat_assistant, parent, false)
      )
      VIEW_TYPE_STATUS -> StatusViewHolder(
        inflater.inflate(R.layout.item_ai_chat_status, parent, false)
      )
      VIEW_TYPE_PLAN -> PlanViewHolder(
        inflater.inflate(R.layout.item_ai_chat_plan, parent, false)
      )
      VIEW_TYPE_TOOL -> ToolViewHolder(
        inflater.inflate(R.layout.item_ai_chat_tool, parent, false)
      )
      VIEW_TYPE_ERROR -> ErrorViewHolder(
        inflater.inflate(R.layout.item_ai_chat_error, parent, false)
      )
      VIEW_TYPE_DIFF -> DiffViewHolder(
        inflater.inflate(R.layout.item_ai_chat_v2_diff, parent, false),
        onDiffDecision
      )
      else -> throw IllegalArgumentException("Unknown view type: $viewType")
    }
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    when (val item = getItem(position)) {
      is ChatListItem.User -> (holder as UserViewHolder).bind(item)
      is ChatListItem.Assistant -> (holder as AssistantViewHolder).bind(item)
      is ChatListItem.Status -> (holder as StatusViewHolder).bind(item)
      is ChatListItem.PlanItem -> (holder as PlanViewHolder).bind(item)
      is ChatListItem.ToolEvent -> (holder as ToolViewHolder).bind(item)
      is ChatListItem.Error -> (holder as ErrorViewHolder).bind(item)
      is ChatListItem.DiffProposal -> (holder as DiffViewHolder).bind(item)
    }
  }

  private class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val messageText: TextView = view.findViewById(R.id.message_text)
    private val attachmentsText: TextView = view.findViewById(R.id.attachments_text)

    fun bind(item: ChatListItem.User) {
      messageText.text = item.text
      messageText.visibility = if (item.text.isBlank()) View.GONE else View.VISIBLE
      if (item.attachments.isEmpty()) {
        attachmentsText.visibility = View.GONE
      } else {
        attachmentsText.visibility = View.VISIBLE
        attachmentsText.text = item.attachments.joinToString("\n") { "● ${it.displayName}" }
      }
    }
  }

  private class AssistantViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val messageText: TextView = view.findViewById(R.id.message_text)

    fun bind(item: ChatListItem.Assistant) {
      messageText.text = item.text
    }
  }

  private class StatusViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val statusText: TextView = view.findViewById(R.id.status_text)

    fun bind(item: ChatListItem.Status) {
      statusText.text = item.text
    }
  }

  private class PlanViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val stepsContainer: LinearLayout = view.findViewById(R.id.plan_steps)

    fun bind(item: ChatListItem.PlanItem) {
      stepsContainer.removeAllViews()
      val context = itemView.context
      item.steps.forEachIndexed { index, step ->
        val done = index < item.doneCount
        val row = TextView(context).apply {
          text = (if (done) "✓ " else "○ ") + step
          setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
          )
          setPadding(0, 4, 0, 4)
        }
        stepsContainer.addView(row)
      }
    }
  }

  private class ToolViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val toolIcon: TextView = view.findViewById(R.id.tool_icon)
    private val toolName: TextView = view.findViewById(R.id.tool_name)
    private val toolStatus: TextView = view.findViewById(R.id.tool_status)

    fun bind(item: ChatListItem.ToolEvent) {
      val context = itemView.context
      toolName.text = item.name
      when (item.ok) {
        null -> {
          toolIcon.text = "◌"
          toolStatus.text = context.getString(R.string.aiagent_tool_running)
        }
        true -> {
          toolIcon.text = "✓"
          toolStatus.text = context.getString(R.string.aiagent_tool_ok)
        }
        false -> {
          toolIcon.text = "✕"
          toolStatus.text = context.getString(R.string.aiagent_tool_failed)
        }
      }
    }
  }

  private class ErrorViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val errorText: TextView = view.findViewById(R.id.error_text)

    fun bind(item: ChatListItem.Error) {
      errorText.text = item.text
    }
  }

  /**
   * v2 diff-review card: one checkbox per proposed hunk plus
   * apply-selected/discard buttons. After the decision the card becomes a
   * read-only result line.
   */
  private class DiffViewHolder(
    view: View,
    private val onDecision: (id: String, decision: EditReviewDecision) -> Unit
  ) : RecyclerView.ViewHolder(view) {
    private val pathText: TextView = view.findViewById(R.id.diff_path)
    private val hunksContainer: LinearLayout = view.findViewById(R.id.diff_hunks)
    private val applyButton: MaterialButton = view.findViewById(R.id.diff_apply_button)
    private val discardButton: MaterialButton = view.findViewById(R.id.diff_discard_button)
    private val resultText: TextView = view.findViewById(R.id.diff_result)
    private val checkBoxes = mutableListOf<CheckBox>()
    private var item: ChatListItem.DiffProposal? = null

    init {
      applyButton.setOnClickListener {
        val current = item ?: return@setOnClickListener
        val indices = current.hunks
          .filterIndexed { i, _ -> checkBoxes.getOrNull(i)?.isChecked == true }
          .map { it.index }
          .toSet()
        val decision = when {
          indices.isEmpty() -> EditReviewDecision.Discard
          indices.size == current.hunks.size -> EditReviewDecision.ApplyAll
          else -> EditReviewDecision.ApplySome(indices)
        }
        onDecision(current.id, decision)
      }
      discardButton.setOnClickListener {
        item?.let { onDecision(it.id, EditReviewDecision.Discard) }
      }
    }

    fun bind(item: ChatListItem.DiffProposal) {
      this.item = item
      val context = itemView.context
      val pending = item.state == DiffProposalState.PENDING
      pathText.text = item.path
      hunksContainer.removeAllViews()
      checkBoxes.clear()
      item.hunks.forEach { hunk ->
        val hunkView = LinearLayout(context).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(0, 8, 0, 8)
        }
        val checkBox = CheckBox(context).apply {
          text = if (hunk.found) {
            context.getString(R.string.ai_chat_v2_diff_hunk_title, hunk.index + 1, hunk.oldLineStart)
          } else {
            context.getString(R.string.ai_chat_v2_diff_hunk_not_found, hunk.index + 1)
          }
          isChecked = true
          isEnabled = pending
        }
        val oldText = TextView(context).apply {
          text = "- " + hunk.oldText.take(MAX_HUNK_CHARS)
          setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall
          )
        }
        val newText = TextView(context).apply {
          text = "+ " + hunk.newText.take(MAX_HUNK_CHARS)
          setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall
          )
        }
        hunkView.addView(checkBox)
        hunkView.addView(oldText)
        hunkView.addView(newText)
        hunksContainer.addView(hunkView)
        checkBoxes.add(checkBox)
      }
      applyButton.isVisible = pending
      discardButton.isVisible = pending
      resultText.isVisible = !pending
      if (!pending) {
        resultText.text = when (item.state) {
          DiffProposalState.APPLIED -> context.getString(R.string.ai_chat_v2_diff_applied)
          DiffProposalState.PARTIAL -> context.getString(R.string.ai_chat_v2_diff_partially_applied)
          DiffProposalState.DISCARDED -> context.getString(R.string.ai_chat_v2_diff_discarded)
          DiffProposalState.PENDING -> ""
        }
      }
    }
  }

  private class ChatDiffCallback : DiffUtil.ItemCallback<ChatListItem>() {
    override fun areItemsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean {
      // Items are append-only; same class at the same position is the same row,
      // except user messages which are unique by content.
      if (oldItem::class != newItem::class) return false
      if (oldItem is ChatListItem.User && newItem is ChatListItem.User) {
        return oldItem.text == newItem.text && oldItem.attachments == newItem.attachments
      }
      if (oldItem is ChatListItem.DiffProposal && newItem is ChatListItem.DiffProposal) {
        return oldItem.id == newItem.id
      }
      return true
    }

    override fun areContentsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean =
      oldItem == newItem
  }

  companion object {
    /** Caracteres por trecho exibidos no card de diff (o resto é cortado). */
    private const val MAX_HUNK_CHARS = 600
  }
}

/**
 * Horizontal chip list of pending attachments shown above the input bar,
 * each with a remove button.
 */
class PendingAttachmentAdapter(
  private val onRemove: (Attachment) -> Unit
) : ListAdapter<Attachment, PendingAttachmentAdapter.ViewHolder>(AttachmentDiffCallback()) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val view = LayoutInflater.from(parent.context)
      .inflate(R.layout.item_ai_chat_attachment, parent, false)
    return ViewHolder(view, onRemove)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  class ViewHolder(
    view: View,
    private val onRemove: (Attachment) -> Unit
  ) : RecyclerView.ViewHolder(view) {
    private val nameText: TextView = view.findViewById(R.id.attachment_name)
    private val removeButton: MaterialButton = view.findViewById(R.id.remove_button)
    private var attachment: Attachment? = null

    init {
      removeButton.setOnClickListener { attachment?.let(onRemove) }
    }

    fun bind(attachment: Attachment) {
      this.attachment = attachment
      nameText.text = attachment.displayName
    }
  }

  private class AttachmentDiffCallback : DiffUtil.ItemCallback<Attachment>() {
    override fun areItemsTheSame(oldItem: Attachment, newItem: Attachment): Boolean =
      oldItem.file.absolutePath == newItem.file.absolutePath

    override fun areContentsTheSame(oldItem: Attachment, newItem: Attachment): Boolean =
      oldItem == newItem
  }
}
