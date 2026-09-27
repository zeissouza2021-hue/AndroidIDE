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

import dev.mutwakil.androidide.aiagent.integration.BuildBridge
import java.io.File

/**
 * Contexto de execução entregue a cada [AgentTool].
 *
 * @param projectRoot raiz do projeto aberto. Todas as tools devem confinar
 *        seus acessos a arquivos dentro deste diretório.
 * @param buildBridge ponte para o sistema de build do AndroidIDE (pode ser
 *        `null` se indisponível; nesse caso as tools de build retornam erro claro).
 * @param permissions permissões concedidas + callback de confirmação (UI).
 * @param history histórico de ações e snapshots para `undo_changes`.
 * @param onStatus callback para reportar progresso (o engine emite [AgentEvent.Status]).
 * @param isCancelled retorna `true` se o usuário cancelou a execução.
 */
data class ToolContext(
    val projectRoot: File,
    val buildBridge: BuildBridge?,
    val permissions: PermissionManager,
    val history: ActionHistory,
    val onStatus: (String) -> Unit,
    val isCancelled: () -> Boolean
)
