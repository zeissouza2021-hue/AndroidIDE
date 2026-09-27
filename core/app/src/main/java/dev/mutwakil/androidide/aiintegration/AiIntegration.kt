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
import dev.mutwakil.androidide.services.builder.GradleBuildService

/**
 * Entry point of the AI build/logs integration in `:core:app`.
 *
 * Holds the shared [BuildLogCollector] and the [BuildBridge] implementation that the AI agent
 * uses to run builds and read their logs.
 *
 * ## Installing the multiplexed listener (front-f)
 *
 * Today the app registers its build-output listener in exactly one place:
 *
 * `core/app/src/main/java/dev/mutwakil/androidide/activities/editor/ProjectHandlerActivity.kt`,
 * in `onGradleBuildServiceConnected` (line 529):
 * ```kotlin
 * service.setEventListener(mBuildEventListener)
 * ```
 * (the second `setEventListener` call, at line 292, only passes `null` during teardown and
 * must stay as is).
 *
 * To capture build logs for the AI agent **without** breaking the existing
 * `EditorBuildEventListener` flow (bottom-sheet output, status messages, daemon watching),
 * replace that single line with:
 * ```kotlin
 * service.setEventListener(AiIntegration.install(mBuildEventListener))
 * ```
 * [install] sets the given listener as the collector's delegate and returns the collector
 * itself, so every event is both recorded in the ring buffer and forwarded to the original
 * listener. On teardown (`setEventListener(null)`) nothing changes: the service simply
 * drops the multiplexed listener.
 *
 * Also wire the bridge into the agent (front-f):
 * ```kotlin
 * AiAgent.buildBridgeProvider = BuildBridgeProvider { AiIntegration.buildBridge }
 * ```
 */
object AiIntegration {

  /**
   * Shared log collector. Installed as the [GradleBuildService] event listener (via [install])
   * so it sees the same output the editor UI sees.
   */
  val logCollector = BuildLogCollector()

  /**
   * The [BuildBridge] the AI agent uses to execute/cancel builds and read [logCollector] logs.
   */
  val buildBridge: BuildBridge = GradleBuildBridgeImpl(logCollector)

  /**
   * Returns the multiplexed [GradleBuildService.EventListener] to pass to
   * `GradleBuildService.setEventListener(...)`: the shared [logCollector] with
   * [existingListener] set as its delegate, so events are recorded for the AI agent
   * and still forwarded to the app's original listener.
   *
   * Pass `null` when there is no existing listener; the collector alone is returned.
   */
  fun install(
    existingListener: GradleBuildService.EventListener?
  ): GradleBuildService.EventListener =
    logCollector.apply { setDelegate(existingListener) }
}
