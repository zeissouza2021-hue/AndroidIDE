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

/**
 * Automatic context chips (chat v2, item 7): the open file and the current
 * selection arrive as removable chips, plus an "attach error" chip with
 * recent build/logcat output. Chips are consumed by the next sent message
 * (prepended to what the model sees) and then cleared.
 */
data class ChatContextChip(
    /** Stable id ("file", "selection", "error"); one chip per id. */
    val id: String,
    val kind: Kind,
    /** User-facing label, already resolved (may contain the file name). */
    val label: String,
    /** Raw content prepended to the message. */
    val content: String,
) {
    enum class Kind { FILE, SELECTION, ERROR }
}

object AiChatV2Context {

    const val CHIP_FILE = "file"
    const val CHIP_SELECTION = "selection"
    const val CHIP_ERROR = "error"

    /**
     * Builds the context block prepended to the outgoing message.
     * Empty when there are no chips.
     */
    fun formatContextBlock(chips: List<ChatContextChip>): String {
        if (chips.isEmpty()) return ""
        return buildString {
            append("[Contexto anexado automaticamente]\n")
            chips.forEach { chip ->
                append("\n### ").append(chip.label).append('\n')
                append("```\n")
                append(chip.content.trim())
                append("\n```\n")
            }
            append("\n")
        }
    }
}
