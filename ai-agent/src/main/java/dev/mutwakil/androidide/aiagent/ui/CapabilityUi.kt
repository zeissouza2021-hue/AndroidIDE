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

import android.content.Context
import dev.mutwakil.androidide.aiagent.AiAgent
import dev.mutwakil.androidide.aiagent.R
import dev.mutwakil.androidide.aiagent.model.Capability
import dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin

/** Presentation helpers for provider [Capability] sets. */
internal object CapabilityUi {

  /**
   * Capabilities of [plugin] resolved for its stored configuration
   * (model-dependent capabilities included); falls back to the base
   * [AiProviderPlugin.capabilities] when nothing is configured yet.
   */
  fun resolvedCaps(plugin: AiProviderPlugin): Set<Capability> {
    val config = runCatching { AiAgent.configStore().getProviderConfig(plugin.id) }.getOrNull()
    return config?.let { plugin.resolvedCapabilities(it) } ?: plugin.capabilities
  }

  /** Short, comma-separated capability summary, e.g. "Text, Vision, Tools". */
  fun summary(context: Context, caps: Set<Capability>): String {
    val labels = orderedLabels(context, caps)
    return labels.joinToString(", ")
  }

  /** Whether the provider can receive images (directly or via vision). */
  fun supportsImageInput(caps: Set<Capability>): Boolean =
    Capability.IMAGE_INPUT in caps || Capability.VISION in caps

  /** Whether the provider can receive generic files (documents/PDFs). */
  fun supportsFileInput(caps: Set<Capability>): Boolean =
    Capability.FILE_INPUT in caps || Capability.PDF_INPUT in caps

  /** Whether the provider supports voice input. */
  fun supportsVoiceInput(caps: Set<Capability>): Boolean =
    Capability.VOICE_INPUT in caps

  private fun orderedLabels(context: Context, caps: Set<Capability>): List<String> {
    val labels = mutableListOf<String>()
    if (Capability.TEXT_INPUT in caps) labels += context.getString(R.string.aiagent_cap_text)
    if (Capability.VOICE_INPUT in caps) labels += context.getString(R.string.aiagent_cap_voice)
    if (Capability.VISION in caps || Capability.IMAGE_INPUT in caps) {
      labels += context.getString(R.string.aiagent_cap_vision)
    }
    if (Capability.FILE_INPUT in caps ||
      Capability.PDF_INPUT in caps ||
      Capability.VIDEO_INPUT in caps
    ) {
      labels += context.getString(R.string.aiagent_cap_files)
    }
    if (Capability.TOOL_CALLING in caps || Capability.FUNCTION_CALLING in caps) {
      labels += context.getString(R.string.aiagent_cap_tools)
    }
    if (Capability.STREAMING in caps) labels += context.getString(R.string.aiagent_cap_streaming)
    return labels
  }
}
