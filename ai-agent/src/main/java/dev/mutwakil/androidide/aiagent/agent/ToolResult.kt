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
 * Resultado da execução de uma [AgentTool].
 *
 * @param success `true` se a tool executou com êxito.
 * @param output saída textual (truncada em [MAX_OUTPUT_LENGTH] caracteres).
 *        Em caso de falha, contém a mensagem de erro legível.
 */
data class ToolResult(
    val success: Boolean,
    val output: String
) {
    companion object {

        /** Limite de caracteres da saída de qualquer tool (~8k). */
        const val MAX_OUTPUT_LENGTH: Int = 8192

        /** Resultado de sucesso. */
        fun ok(output: String): ToolResult = ToolResult(true, output)

        /** Resultado de erro esperado (ex.: arquivo não encontrado, permissão negada). */
        fun error(message: String): ToolResult = ToolResult(false, "Erro: $message")
    }
}
