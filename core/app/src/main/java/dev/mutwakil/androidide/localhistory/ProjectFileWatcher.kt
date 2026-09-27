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

import android.os.FileObserver
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Recursively watches a project directory for file changes using [FileObserver] (inotify).
 *
 * Excluded directories ([LocalHistoryConfig.EXCLUDED_DIR_NAMES], notably `.git/`) are never
 * watched and never descended into. Newly created subdirectories are picked up automatically;
 * deleted ones are unwatched.
 *
 * Callbacks fire on an internal binder thread; callers must not do heavy work in [onFileChanged].
 */
class ProjectFileWatcher(
  private val root: File,
  private val onFileChanged: (File) -> Unit
) {

  private val log = LoggerFactory.getLogger(ProjectFileWatcher::class.java)

  private val lock = Any()
  private val observers = mutableMapOf<String, FileObserver>()
  @Volatile
  private var started = false

  /** Starts watching [root] and every non-excluded subdirectory. */
  fun start() {
    synchronized(lock) {
      if (started) return
      started = true
      var count = 0
      root.walkTopDown()
        .onEnter { dir -> dir == root || !isExcludedDir(dir) }
        .filter { it.isDirectory && (it == root || !isExcludedDir(it)) }
        .forEach { dir ->
          if (addObserverLocked(dir)) count++
        }
      log.debug("LocalHistory: watching {} directories under {}", count, root)
    }
  }

  /** Stops all watchers. */
  fun stop() {
    synchronized(lock) {
      if (!started) return
      started = false
      observers.values.forEach { runCatching { it.stopWatching() } }
      observers.clear()
    }
  }

  @Suppress("DEPRECATION")
  private fun addObserverLocked(dir: File): Boolean {
    val key = dir.absolutePath
    if (observers.containsKey(key)) return false
    return try {
      val observer = object : FileObserver(
        key,
        FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or
          FileObserver.DELETE or FileObserver.MOVED_FROM
      ) {
        override fun onEvent(event: Int, path: String?) {
          if (path == null) return
          handleEvent(event, File(dir, path))
        }
      }
      observer.startWatching()
      observers[key] = observer
      true
    } catch (e: Exception) {
      log.warn("LocalHistory: cannot watch {}", key, e)
      false
    }
  }

  private fun removeObserverLocked(dir: File) {
    // Also drop observers of any subdirectory: deleting a tree fires a single DELETE
    // for its root, and the child observers would otherwise leak.
    val rootPath = dir.absolutePath
    val prefix = "$rootPath/"
    val keys = observers.keys.filter { it == rootPath || it.startsWith(prefix) }
    keys.forEach { key ->
      observers.remove(key)?.let { observer -> runCatching { observer.stopWatching() } }
    }
  }

  private fun handleEvent(event: Int, file: File) {
    try {
      val created = event and FileObserver.CREATE != 0 || event and FileObserver.MOVED_TO != 0
      val deleted = event and FileObserver.DELETE != 0 || event and FileObserver.MOVED_FROM != 0
      val written = event and FileObserver.CLOSE_WRITE != 0

      if (deleted) {
        // NOTE: File.isDirectory is false after deletion, so match by watched path instead.
        // This also covers whole subtrees moved/renamed away.
        synchronized(lock) {
          if (started && observers.containsKey(file.absolutePath)) {
            removeObserverLocked(file)
          }
        }
        return
      }

      if (created && file.isDirectory) {
        // A new subdirectory: start watching it (unless excluded).
        if (!isExcludedDir(file)) {
          synchronized(lock) {
            if (started) addObserverLocked(file)
          }
        }
        return
      }

      // Regular file created, moved in, or finished writing.
      if ((created || written) && file.isFile) {
        onFileChanged(file)
      }
    } catch (e: Exception) {
      log.debug("LocalHistory: watcher event failed for {}", file, e)
    }
  }

  private fun isExcludedDir(dir: File): Boolean =
    LocalHistoryConfig.EXCLUDED_DIR_NAMES.contains(dir.name)
}
