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
 * Eventos emitidos pelo [AgentEngine.run] durante a execução.
 * A UI (front-c) consome este fluxo para atualizar status, plano,
 * progresso de tools e o resultado final.
 */
sealed interface AgentEvent {

    /** Atualização de status textual (ex.: "Analyzing project..."). */
    data class Status(val text: String) : AgentEvent

    /** Fragmento da resposta do assistente (streaming, modo CHAT). */
    data class AssistantDelta(val text: String) : AgentEvent

    /** Plano de ação gerado na fase PLAN (exibido como checklist na UI). */
    data class Plan(val steps: List<String>) : AgentEvent

    /** Uma tool começou a executar. */
    data class ToolStarted(val name: String, val summary: String) : AgentEvent

    /** Uma tool terminou. */
    data class ToolFinished(val name: String, val ok: Boolean) : AgentEvent

    /** Execução concluída com resumo final. */
    data class Completed(val summary: String) : AgentEvent

    /** Execução falhou com mensagem de erro legível. */
    data class Failed(val error: String) : AgentEvent

    /** Execução cancelada pelo usuário (cancelamento cooperativo). */
    data object Cancelled : AgentEvent
}
