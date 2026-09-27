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

import android.content.Context
import dev.mutwakil.androidide.eventbus.events.project.ProjectInitializedEvent
import dev.mutwakil.androidide.projects.IProjectManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Automatic per-file snapshots ("Local History"), inspired by Android Studio.
 *
 * The service watches the currently open project's directory (never `.git/` and never build
 * outputs) and, a few seconds after edits to a file stop, stores a snapshot of its content
 * under the IDE home directory (`~/.androidide/local-history`). Snapshots are pruned
 * automatically (count per file, age, and total size), so storage stays bounded on a phone.
 *
 * Only files of the project currently open in the IDE are tracked. Nothing is ever written
 * inside the project and git metadata is never touched: the project's git state is unaffected.
 *
 * Start it once from the application: `LocalHistoryService.attach(context)`. It then follows
 * project open/update events by itself.
 */
object LocalHistoryService {

  private val log = LoggerFactory.getLogger(LocalHistoryService::class.java)

  private val lock = Any()

  @Volatile
  private var attached = false

  @Volatile
  private var appContext: Context? = null

  private var watcher: ProjectFileWatcher? = null
  private var watchedRoot: File? = null

  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "local-history").apply { isDaemon = true }
    }

  /** Pending debounced snapshot tasks, keyed by file absolute path. */
  private val pending = mutableMapOf<String, ScheduledFuture<*>>()

  /**
   * Attaches the service to the application. Safe to call more than once; subsequent calls
   * are no-ops. Must be called on the main thread.
   */
  fun attach(context: Context) {
    val firstAttach = synchronized(lock) {
      if (attached) {
        false
      } else {
        attached = true
        appContext = context.applicationContext
        true
      }
    }
    if (!firstAttach) return

    try {
      EventBus.getDefault().register(this)
    } catch (e: Exception) {
      log.warn("LocalHistory: EventBus registration failed", e)
    }
    // The project may already be open (e.g. process restart); try to start watching now.
    runCatching { ensureWatching() }
  }

  /** Starts (or restarts) watching the currently open project, if any. */
  fun ensureWatching() {
    val context = appContext ?: return
    val root = currentProjectRoot() ?: return
    val rootChanged = synchronized(lock) { watchedRoot?.absolutePath != root.absolutePath }
    if (!rootChanged && synchronized(lock) { watcher != null }) return

    synchronized(lock) {
      stopLocked()
      watcher = ProjectFileWatcher(root, ::onFileChanged)
      watchedRoot = root
    }
    try {
      watcher?.start()
    } catch (e: Exception) {
      log.warn("LocalHistory: failed to start watching {}", root, e)
      return
    }
    // Opportunistic cleanup whenever we (re)start watching a project.
    scheduler.execute {
      runCatching { LocalHistoryStore(context).enforceRetention() }
    }
    log.info("LocalHistory: watching {}", root)
  }

  /** Stops watching. Snapshots already stored are kept. */
  fun stop() {
    synchronized(lock) { stopLocked() }
  }

  /** Returns true if [file] is currently tracked (inside the watched project and eligible). */
  fun isTracking(file: File): Boolean {
    val root = watchedRoot ?: return false
    return isTrackableFile(root, file)
  }

  /** Lists snapshots for [file], newest first. Empty when the file is not tracked. */
  fun listSnapshotsFor(file: File): List<LocalHistorySnapshot> {
    val context = appContext ?: return emptyList()
    val root = watchedRoot ?: return emptyList()
    if (!isTrackableFile(root, file)) return emptyList()
    return LocalHistoryStore(context)
      .listSnapshots(root, relativePathOf(root, file))
  }

  /**
   * Restores [snapshot] over its file on disk. The current content is snapshotted first, so
   * the restore itself can be undone from the history UI.
   *
   * @return true if the file was rewritten.
   */
  fun restoreSnapshot(snapshot: LocalHistorySnapshot): Boolean {
    val context = appContext ?: return false
    return try {
      val store = LocalHistoryStore(context)
      val target = File(snapshot.projectRoot, snapshot.relativePath)
      if (!isTrackableFile(snapshot.projectRoot, target)) {
        log.warn("LocalHistory: refusing to restore outside tracked project: {}", target)
        return false
      }
      val snapshotBytes = store.readSnapshot(snapshot) ?: return false

      // Keep the current content as a snapshot so the restore is undoable.
      if (target.isFile) {
        runCatching { target.readBytes() }.getOrNull()?.let { current ->
          if (!current.contentEquals(snapshotBytes) &&
            !store.isDuplicate(snapshot.projectRoot, snapshot.relativePath, current)
          ) {
            store.saveSnapshot(snapshot.projectRoot, snapshot.relativePath, current)
          }
        }
      }

      target.parentFile?.mkdirs()
      // Write via temp file + rename so a crash never leaves a half-written file.
      val tmp = File(target.parentFile, "${target.name}.localhistory.tmp")
      tmp.writeBytes(snapshotBytes)
      val ok = tmp.renameTo(target)
      if (!ok) {
        runCatching { tmp.delete() }
      }
      // A restore counts as a change: let the normal debounce path snapshot it if it differs.
      if (ok) onFileChanged(target)
      ok
    } catch (e: Exception) {
      log.warn("LocalHistory: restore failed for {}", snapshot.relativePath, e)
      false
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onProjectInitialized(@Suppress("UNUSED_PARAMETER") event: ProjectInitializedEvent) {
    runCatching { ensureWatching() }
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private fun stopLocked() {
    pending.values.forEach { runCatching { it.cancel(false) } }
    pending.clear()
    runCatching { watcher?.stop() }
    watcher = null
    watchedRoot = null
  }

  private fun currentProjectRoot(): File? {
    return try {
      IProjectManager.getInstance().projectDirPath
        .takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf { it.isDirectory }
    } catch (e: Exception) {
      log.debug("LocalHistory: no project open", e)
      null
    }
  }

  private fun onFileChanged(file: File) {
    val root = watchedRoot ?: return
    if (!isTrackableFile(root, file)) return
    val key = file.absolutePath
    synchronized(lock) {
      pending.remove(key)?.cancel(false)
      pending[key] = scheduler.schedule(
        {
          synchronized(lock) { pending.remove(key) }
          takeSnapshot(root, file)
        },
        LocalHistoryConfig.DEBOUNCE_MILLIS,
        TimeUnit.MILLISECONDS
      )
    }
  }

  private fun takeSnapshot(root: File, file: File) {
    val context = appContext ?: return
    try {
      if (!file.isFile) return
      if (file.length() > LocalHistoryConfig.MAX_FILE_BYTES) return
      val content = runCatching { file.readBytes() }.getOrNull() ?: return
      if (content.size > LocalHistoryConfig.MAX_FILE_BYTES) return

      val relativePath = relativePathOf(root, file)
      val store = LocalHistoryStore(context)
      if (store.isDuplicate(root, relativePath, content)) return

      val snapshot = store.saveSnapshot(root, relativePath, content) ?: return
      store.enforceFileLimit(root, relativePath)
      // Global sweep is throttled internally; cheap to ask every time.
      store.enforceRetention()
      log.debug("LocalHistory: snapshot {} ({} bytes)", snapshot.relativePath, snapshot.size)
    } catch (e: Exception) {
      log.warn("LocalHistory: snapshot failed for {}", file, e)
    }
  }

  private fun relativePathOf(root: File, file: File): String {
    val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
    val filePath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    val relative = if (filePath.startsWith("$rootPath/")) {
      filePath.removePrefix("$rootPath/")
    } else {
      filePath
    }
    return relative.replace(File.separatorChar, '/')
  }

  /**
   * Decides whether a file is eligible for snapshots: inside [root], not in an excluded
   * directory, not a binary, and a regular file.
   */
  private fun isTrackableFile(root: File, file: File): Boolean {
    return try {
      val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
      val filePath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
      if (filePath != rootPath && !filePath.startsWith("$rootPath/")) return false
      if (!file.isFile) return false
      if (file.name.endsWith(".localhistory.tmp")) return false

      // No path segment may be an excluded directory (covers .git at any depth).
      val relative = if (filePath == rootPath) "" else filePath.removePrefix("$rootPath/")
      val segments = relative.split('/')
      if (segments.any { it in LocalHistoryConfig.EXCLUDED_DIR_NAMES }) return false

      val extension = file.extension.lowercase()
      if (extension in LocalHistoryConfig.EXCLUDED_EXTENSIONS) return false

      true
    } catch (e: Exception) {
      false
    }
  }
}
