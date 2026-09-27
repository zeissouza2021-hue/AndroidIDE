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

package dev.mutwakil.androidide.aiagent.model

import java.io.File

/** Kind of media attached to a chat message. */
enum class AttachmentType {
    IMAGE,
    PDF,
    DOCUMENT,
    VIDEO,
    AUDIO,
    CODE,
}

/**
 * A file attached to a [ChatMessage].
 *
 * Providers must never receive an attachment whose required [Capability] is missing;
 * see [dev.mutwakil.androidide.aiagent.gate.CapabilityGate.missingCapability].
 */
data class Attachment(
    val type: AttachmentType,
    val file: File,
    val mimeType: String,
    val displayName: String,
)
