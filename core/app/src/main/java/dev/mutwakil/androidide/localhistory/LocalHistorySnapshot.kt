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

package dev.mutwakil.androidide.localhistory

import java.io.File

/**
 * A single point-in-time copy of a project file.
 *
 * @property projectRoot The project root this snapshot belongs to.
 * @property relativePath The file path relative to [projectRoot], using '/' separators.
 * @property timestamp The epoch millis at which the snapshot was taken.
 * @property size The snapshot size in bytes.
 * @property file The snapshot file on disk.
 */
data class LocalHistorySnapshot(
  val projectRoot: File,
  val relativePath: String,
  val timestamp: Long,
  val size: Long,
  val file: File
)
