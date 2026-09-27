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
 * Gerencia as permissões concedidas ao agente e o fluxo de confirmação
 * de ações sensíveis.
 *
 * A UI (front-c) liga [confirmCallback] a um diálogo
 * (`MaterialAlertDialogBuilder`); o default nega tudo, ou seja,
 * sem UI ligada nenhuma ação com confirmação é executada.
 */
class PermissionManager {

    /** Conjunto de permissões (grants) do agente. Mutável para a UI ajustar. */
    data class Grants(
        var canRead: Boolean = true,
        var canModify: Boolean = true,
        var canDelete: Boolean = false,
        var canRunCommands: Boolean = false,
        var canBuild: Boolean = true,
        var canUseNetwork: Boolean = true
    )

    val grants = Grants()

    /**
     * Chamado pelo [AgentEngine] antes de executar tools com
     * [AgentTool.requiresConfirmation] `= true`.
     * Deve retornar `true` somente se o usuário confirmar explicitamente.
     */
    var confirmCallback: suspend (title: String, detail: String) -> Boolean = { _, _ -> false }

    /** Pede confirmação ao usuário; retorna `true` se confirmada. */
    suspend fun requireConfirmation(title: String, detail: String): Boolean =
        confirmCallback(title, detail)
}
