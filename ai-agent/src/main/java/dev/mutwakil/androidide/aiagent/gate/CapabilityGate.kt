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

package dev.mutwakil.androidide.aiagent.gate

import dev.mutwakil.androidide.aiagent.model.Attachment
import dev.mutwakil.androidide.aiagent.model.AttachmentType
import dev.mutwakil.androidide.aiagent.model.Capability
import dev.mutwakil.androidide.aiagent.model.ToolDefinition

/**
 * Guards features against the honestly-declared capabilities of the active provider.
 *
 * Callers must check attachments and tools through this gate *before* building
 * the provider request, so media is never sent to a provider that cannot handle it.
 */
object CapabilityGate {

    private val attachmentRequirements: Map<AttachmentType, Capability> = mapOf(
        AttachmentType.IMAGE to Capability.IMAGE_INPUT,
        AttachmentType.PDF to Capability.PDF_INPUT,
        AttachmentType.DOCUMENT to Capability.FILE_INPUT,
        AttachmentType.VIDEO to Capability.VIDEO_INPUT,
        AttachmentType.AUDIO to Capability.VOICE_INPUT,
        AttachmentType.CODE to Capability.TEXT_INPUT,
    )

    /**
     * Returns the [Capability] missing from [caps] that [attachment] requires,
     * or null when the attachment is supported.
     */
    fun missingCapability(caps: Set<Capability>, attachment: Attachment): Capability? {
        val required = attachmentRequirements[attachment.type] ?: return null
        return if (required in caps) null else required
    }

    /**
     * Clear user-facing message (pt-BR) explaining that a feature is unavailable
     * on the given provider.
     */
    fun unavailableMessage(providerName: String, capability: Capability): String {
        val what = when (capability) {
            Capability.TEXT_INPUT -> "entrada de texto"
            Capability.VOICE_INPUT -> "entrada de voz (microfone)"
            Capability.IMAGE_INPUT -> "envio de imagens"
            Capability.FILE_INPUT -> "envio de arquivos"
            Capability.PDF_INPUT -> "envio de arquivos PDF"
            Capability.VIDEO_INPUT -> "envio de vídeos"
            Capability.VISION -> "análise visual de imagens"
            Capability.CODE_GENERATION -> "geração de código"
            Capability.TOOL_CALLING -> "chamada de ferramentas (tool calling)"
            Capability.FUNCTION_CALLING -> "chamada de funções (function calling)"
            Capability.STREAMING -> "respostas em tempo real (streaming)"
        }
        return "O provedor \"$providerName\" não oferece suporte a $what. " +
            "Escolha outro provedor ou modelo nas configurações do AI Agent."
    }

    /**
     * Removes all tools when the provider declares neither [Capability.TOOL_CALLING]
     * nor [Capability.FUNCTION_CALLING]; otherwise returns [tools] unchanged.
     */
    fun filterTools(caps: Set<Capability>, tools: List<ToolDefinition>): List<ToolDefinition> {
        return if (Capability.TOOL_CALLING in caps || Capability.FUNCTION_CALLING in caps) {
            tools
        } else {
            emptyList()
        }
    }
}
