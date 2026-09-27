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
package dev.mutwakil.androidide.aiintegration

import dev.mutwakil.androidide.aiagent.integration.BuildBridge
import dev.mutwakil.androidide.aiagent.integration.BuildOutcome
import dev.mutwakil.androidide.lookup.Lookup
import dev.mutwakil.androidide.projects.builder.BuildService
import dev.mutwakil.androidide.tooling.api.messages.result.TaskExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * [BuildBridge] implementation backed by the app's [BuildService].
 *
 * The service is resolved from [Lookup] on every call, exactly like the app does in
 * `BaseBuildAction` (`Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)`).
 * If no service is registered (no project is open in the editor, or the service is not
 * bound yet), [executeTasks] reports a failed [BuildOutcome] instead of throwing.
 *
 * Build output is captured by the shared [BuildLogCollector] and exposed via [recentLogs].
 */
class GradleBuildBridgeImpl(
  private val logCollector: BuildLogCollector
) : BuildBridge {

  private val building = AtomicBoolean(false)

  private val buildService: BuildService?
    get() = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)

  /**
   * `true` while a build started through this bridge is running, or while the underlying
   * service reports [BuildService.isBuildInProgress].
   */
  override val isBuilding: Boolean
    get() = building.get() || (buildService?.isBuildInProgress == true)

  override suspend fun executeTasks(tasks: List<String>): BuildOutcome =
    withContext(Dispatchers.IO) {
      val service = buildService
        ?: return@withContext BuildOutcome(
          success = false,
          summary = "BuildService indisponível — abra um projeto no editor primeiro"
        )

      building.set(true)
      try {
        val result = service.executeTasks(tasks).await()
        mapResult(tasks, result)
      } catch (e: CancellationException) {
        // The agent cancelled its coroutine: convert to a "cancelled" outcome and
        // best-effort cancel the actual build on the tooling server, which would
        // otherwise keep running in the background.
        runCatching { service.cancelCurrentBuild() }
        BuildOutcome(success = false, summary = "Build cancelado", failedTasks = tasks)
      } catch (e: Exception) {
        BuildOutcome(
          success = false,
          summary = "Erro ao executar build: ${e.message ?: e.javaClass.simpleName}"
        )
      } finally {
        building.set(false)
      }
    }

  override suspend fun cancelBuild(): Boolean =
    withContext(Dispatchers.IO) {
      val service = buildService ?: return@withContext false
      return@withContext try {
        val result = service.cancelCurrentBuild().await()
        building.set(false)
        result.wasEnqueued
      } catch (e: Exception) {
        false
      }
    }

  override fun recentLogs(maxLines: Int): List<String> = logCollector.lines(maxLines)

  private fun mapResult(tasks: List<String>, result: TaskExecutionResult): BuildOutcome {
    if (result.isSuccessful) {
      return BuildOutcome(
        success = true,
        summary = "Build concluído com sucesso: ${tasks.joinToString(" ")}"
      )
    }
    val failure = result.failure
    val summary = if (failure != null) {
      "Build falhou: $failure (tasks: ${tasks.joinToString(" ")})"
    } else {
      "Build falhou (tasks: ${tasks.joinToString(" ")})"
    }
    // Cancelled tasks did not "fail"; only report failed tasks for real failures.
    val failedTasks =
      if (failure == TaskExecutionResult.Failure.BUILD_CANCELLED) emptyList() else tasks
    return BuildOutcome(success = false, summary = summary, failedTasks = failedTasks)
  }
}
