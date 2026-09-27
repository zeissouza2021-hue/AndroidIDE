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

import dev.mutwakil.androidide.services.builder.GradleBuildService
import dev.mutwakil.androidide.tooling.api.messages.result.BuildInfo
import dev.mutwakil.androidide.tooling.events.ProgressEvent

/**
 * A [GradleBuildService.EventListener] that records build output in a thread-safe ring buffer
 * while forwarding every event to an optional inner listener.
 *
 * All callbacks received on [onOutput] (plus short `[BUILD]` markers for build start/finish
 * and Gradle daemon events) are appended to the buffer, which keeps at most [MAX_LINES] lines;
 * older lines are discarded first. The buffer can be read via [lines] and reset via [clear].
 *
 * The optional [delegate] receives every callback, so this collector can be installed in place
 * of the app's current listener (see [AiIntegration.install]) without breaking the existing
 * build-output UI flow (e.g. [dev.mutwakil.androidide.handlers.EditorBuildEventListener]).
 *
 * Thread-safety: [GradleBuildService] wraps listeners so callbacks arrive on the UI thread, but
 * the buffer is guarded by an internal lock anyway, so reads from background coroutines
 * (e.g. the AI agent via [GradleBuildBridgeImpl.recentLogs]) are safe.
 */
class BuildLogCollector(
  private var delegate: GradleBuildService.EventListener? = null
) : GradleBuildService.EventListener {

  companion object {
    /** Maximum number of lines kept in the ring buffer. */
    const val MAX_LINES = 2000
  }

  private val lock = Any()
  private val buffer = ArrayDeque<String>(MAX_LINES)

  /**
   * Sets (or clears) the inner listener that receives every event in addition to the collector.
   */
  fun setDelegate(delegate: GradleBuildService.EventListener?) {
    this.delegate = delegate
  }

  /**
   * Returns up to the last [maxLines] captured lines, oldest first.
   */
  fun lines(maxLines: Int = 200): List<String> = synchronized(lock) {
    if (maxLines <= 0 || buffer.isEmpty()) return emptyList()
    buffer.takeLast(maxLines.coerceAtMost(buffer.size))
  }

  /**
   * Discards all captured lines.
   */
  fun clear() = synchronized(lock) { buffer.clear() }

  private fun append(line: String) = synchronized(lock) {
    if (buffer.size >= MAX_LINES) buffer.removeFirst()
    buffer.addLast(line)
  }

  override fun prepareBuild(buildInfo: BuildInfo) {
    append("[BUILD] tasks: ${buildInfo.tasks}")
    delegate?.prepareBuild(buildInfo)
  }

  override fun onBuildSuccessful(tasks: List<String?>) {
    append("[BUILD] BUILD SUCCESSFUL")
    delegate?.onBuildSuccessful(tasks)
  }

  override fun onGradleDaemonStarted(pid: Int) {
    append("[BUILD] gradle daemon started: pid=$pid")
    delegate?.onGradleDaemonStarted(pid)
  }

  override fun onGradleDaemonExited(pid: Int) {
    append("[BUILD] gradle daemon exited: pid=$pid")
    delegate?.onGradleDaemonExited(pid)
  }

  override fun onProgressEvent(event: ProgressEvent) {
    delegate?.onProgressEvent(event)
  }

  override fun onBuildFailed(tasks: List<String?>) {
    append("[BUILD] BUILD FAILED")
    delegate?.onBuildFailed(tasks)
  }

  override fun onOutput(line: String?) {
    if (line != null) append(line)
    delegate?.onOutput(line)
  }
}
