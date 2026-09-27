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

/**
 * Tuning knobs for the Local History service.
 *
 * Local History keeps automatic snapshots of the files edited in the IDE, without any need to
 * commit. Snapshots are stored outside the project, under the IDE home directory
 * (`~/.androidide/local-history`), and are pruned automatically so they never eat the device disk.
 */
object LocalHistoryConfig {

  /** How long to wait after the last observed change before a snapshot is taken. */
  const val DEBOUNCE_MILLIS: Long = 3_000L

  /** Maximum number of snapshots kept per file. Older ones are deleted first. */
  const val MAX_SNAPSHOTS_PER_FILE: Int = 30

  /** Snapshots older than this are deleted during retention sweeps. */
  const val MAX_SNAPSHOT_AGE_DAYS: Int = 7

  /** Hard ceiling for the whole local-history storage. Oldest snapshots go first. */
  const val MAX_TOTAL_BYTES: Long = 150L * 1024L * 1024L // 150 MB

  /** Files larger than this are never snapshotted. */
  const val MAX_FILE_BYTES: Long = 1L * 1024L * 1024L // 1 MB

  /** Minimum interval between two global retention sweeps. */
  const val RETENTION_SWEEP_INTERVAL_MILLIS: Long = 30L * 60L * 1000L // 30 min

  /** Directory names that are never watched (build outputs, VCS metadata, caches, ...). */
  val EXCLUDED_DIR_NAMES: Set<String> = setOf(
    ".git",
    ".gradle",
    ".idea",
    ".cxx",
    ".vscode",
    ".dart_tool",
    "build",
    "out",
    "dist",
    "node_modules"
  )

  /** File extensions that are never snapshotted (binaries, archives, media, ...). */
  val EXCLUDED_EXTENSIONS: Set<String> = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico",
    "mp3", "wav", "ogg", "flac", "mid", "midi",
    "mp4", "mkv", "webm", "3gp",
    "apk", "aab", "apks",
    "dex", "class", "jar", "aar",
    "zip", "gz", "tgz", "tar", "rar", "7z",
    "so", "dll", "dylib",
    "ttf", "otf", "woff", "woff2", "eot",
    "pdf", "bin", "dat", "db", "sqlite", "realm",
    "hprof", "jks", "keystore"
  )
}
