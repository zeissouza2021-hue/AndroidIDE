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

package dev.mutwakil.androidide.aiagent.agent

/**
 * Structured diff review for `edit_file` (chat v2).
 *
 * Instead of a blind allow/deny dialog, the UI can show the proposed change
 * as a diff card in the chat and let the user apply all hunks, only some of
 * them, or discard everything. The engine applies the decision through the
 * normal [dev.mutwakil.androidide.aiagent.agent.tools.EditFileTool] path, so
 * history/snapshots and undo keep working.
 *
 * Wired through [PermissionManager.confirmEditDiff]; when no UI hooks it,
 * the engine falls back to the classic [PermissionManager.requireConfirmation]
 * dialog.
 */

/** One proposed edit (one oldText → newText pair). */
data class EditHunk(
    /** Index of the edit in the tool call (drives per-hunk accept/reject). */
    val index: Int,
    val oldText: String,
    val newText: String,
    /**
     * 1-based line number where [oldText] starts in the original file,
     * or -1 when it was not found (applying it will fail with a clear error).
     */
    val oldLineStart: Int,
) {
    val found: Boolean get() = oldLineStart != -1
}

/** A file edit proposed by the agent, awaiting the user's review. */
data class EditReviewRequest(
    /** Path as given to the tool (relative to the project root). */
    val path: String,
    /** Current file content (for context; not sent anywhere). */
    val originalContent: String,
    /** One hunk per proposed edit, in application order. */
    val hunks: List<EditHunk>,
)

/** The user's decision on an [EditReviewRequest]. */
sealed interface EditReviewDecision {
    /** Apply every proposed edit. */
    data object ApplyAll : EditReviewDecision

    /** Apply only the hunks at [indices] (subset of [EditReviewRequest.hunks]). */
    data class ApplySome(val indices: Set<Int>) : EditReviewDecision

    /** Discard the whole change. */
    data object Discard : EditReviewDecision
}
