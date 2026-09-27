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

package dev.mutwakil.androidide.aiagent.integration

/**
 * Bridge between the AI agent and the IDE build system.
 *
 * Implemented in `:core:app` (front-e) against the real Gradle build service;
 * this module only declares the contract.
 */
interface BuildBridge {
    /** True while a build triggered through this bridge is running. */
    val isBuilding: Boolean

    /** Executes the given Gradle tasks and suspends until they finish. */
    suspend fun executeTasks(tasks: List<String>): BuildOutcome

    /** Attempts to cancel the running build; true if a cancellation was requested. */
    suspend fun cancelBuild(): Boolean

    /** Most recent build log lines, newest last. */
    fun recentLogs(maxLines: Int = 200): List<String>
}
