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

import dev.mutwakil.androidide.aiagent.model.ToolDefinition

/**
 * Contrato de uma tool executável pelo [AgentEngine].
 *
 * Cada tool declara seus metadados ([definition], com nome, descrição bilíngue
 * PT-BR/EN e JSON Schema dos parâmetros), se exige confirmação do usuário
 * ([requiresConfirmation]) e a lógica de execução ([execute]).
 *
 * Regras que toda implementação deve respeitar:
 * - Nunca operar fora de [ToolContext.projectRoot] (bloquear path traversal).
 * - Checar [ToolContext.isCancelled] e lançar [kotlinx.coroutines.CancellationException]
 *   quando cancelado (ver [BaseTool][dev.mutwakil.androidide.aiagent.agent.tools.BaseTool]).
 * - Truncar a saída em ~8k caracteres ([ToolResult]).
 */
interface AgentTool {

    /** Nome, descrição e JSON Schema dos parâmetros, enviados ao provider. */
    val definition: ToolDefinition

    /**
     * `true` se o [AgentEngine] deve pedir confirmação via
     * [PermissionManager.requireConfirmation] antes de executar.
     */
    val requiresConfirmation: Boolean

    /**
     * Executa a tool.
     *
     * @param argsJson argumentos em JSON, conforme o schema de [definition].
     * @param ctx contexto de execução (raiz do projeto, permissões, histórico...).
     * @return resultado; em caso de erro esperado, retornar
     *         [ToolResult] com `success = false` em vez de lançar exceção.
     */
    suspend fun execute(argsJson: String, ctx: ToolContext): ToolResult
}

/**
 * Para tools cuja necessidade de confirmação depende dos argumentos
 * (ex.: [run_command][dev.mutwakil.androidide.aiagent.agent.tools.RunCommandTool],
 * que dispensa confirmação para comandos da allowlist segura).
 *
 * O [AgentEngine] consulta este método antes de [PermissionManager.requireConfirmation].
 */
interface ConditionalConfirmation {

    /** Retorna `true` se estes [argsJson] exigem confirmação do usuário. */
    fun requiresConfirmationFor(argsJson: String): Boolean
}
