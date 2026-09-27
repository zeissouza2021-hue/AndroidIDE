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

package dev.mutwakil.androidide.aiagent.context

/**
 * A compact, bounded summary of a project used as LLM context.
 *
 * This is intentionally lossy: it carries a truncated file tree, detected
 * languages and Gradle dependency info — never the whole project.
 */
data class ProjectSnapshot(
    /** Absolute path of the project root this snapshot was taken from. */
    val rootPath: String,
    /** Indented file tree, truncated to ~8k chars. */
    val tree: String,
    /** Number of files visited (may exceed the listed ones when truncated). */
    val fileCount: Int,
    /** True when the file list was cut short by the file cap. */
    val truncated: Boolean,
    /** Programming/markup languages detected by file extension. */
    val languages: Set<String>,
    /** Concatenated settings.gradle[.kts] / build.gradle[.kts] contents, truncated to ~4k chars. */
    val dependencies: String,
)
