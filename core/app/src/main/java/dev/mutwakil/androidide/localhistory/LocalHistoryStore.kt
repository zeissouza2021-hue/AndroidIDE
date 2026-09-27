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
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * Persists Local History snapshots outside the project, under the IDE home directory
 * (`~/.androidide/local-history`).
 *
 * Layout:
 * ```
 * local-history/
 *   <projectId>/                 // sha256 of the project root absolute path
 *     project.info               // human-readable project path (debugging aid)
 *     snapshots/
 *       <fileId>/                // sha256 of the project-relative file path
 *         relpath.txt            // the project-relative path (debugging aid)
 *         <epochMillis>.snapshot // raw file content
 * ```
 *
 * Nothing is ever written inside the project itself and `.git/` is never watched, so the
 * project's git state is left completely untouched.
 */
class LocalHistoryStore(private val context: Context) {

  private val log = LoggerFactory.getLogger(LocalHistoryStore::class.java)

  /** Last time a global retention sweep ran (in-memory throttle). */
  @Volatile
  private var lastGlobalSweepMillis: Long = 0L

  /** Root of the local-history storage: `~/.androidide/local-history`. */
  fun baseDir(): File {
    val home = runCatching {
      System.getenv("HOME")?.let(::File)?.takeIf { it.isDirectory }
    }.getOrNull() ?: context.filesDir
    return File(home, ".androidide/local-history")
  }

  fun projectIdFor(projectRoot: File): String =
    sha256Hex(runCatching { projectRoot.canonicalPath }.getOrDefault(projectRoot.absolutePath))

  fun snapshotDirFor(projectRoot: File, relativePath: String): File =
    File(
      File(baseDir(), projectIdFor(projectRoot)),
      "snapshots/${sha256Hex(relativePath)}"
    )

  /**
   * Saves a snapshot of [content] for the file at [relativePath] inside [projectRoot].
   *
   * @return The created [LocalHistorySnapshot], or null if it could not be written.
   */
  fun saveSnapshot(
    projectRoot: File,
    relativePath: String,
    content: ByteArray
  ): LocalHistorySnapshot? {
    return try {
      val dir = snapshotDirFor(projectRoot, relativePath)
      if (!dir.isDirectory && !dir.mkdirs()) {
        log.warn("LocalHistory: cannot create snapshot dir {}", dir)
        return null
      }
      val relPathFile = File(dir, "relpath.txt")
      if (!relPathFile.exists()) {
        runCatching { relPathFile.writeText(relativePath) }
      }
      writeProjectInfo(projectRoot)

      val timestamp = System.currentTimeMillis()
      val target = File(dir, "$timestamp.snapshot")
      // Write to a temp file first and rename, so readers never see a partial snapshot.
      val tmp = File(dir, "$timestamp.snapshot.tmp")
      tmp.writeBytes(content)
      if (!tmp.renameTo(target)) {
        tmp.delete()
        log.warn("LocalHistory: cannot move snapshot into place for {}", relativePath)
        return null
      }
      LocalHistorySnapshot(projectRoot, relativePath, timestamp, content.size.toLong(), target)
    } catch (e: Exception) {
      log.warn("LocalHistory: failed to save snapshot for {}", relativePath, e)
      null
    }
  }

  /** Lists snapshots for a file, newest first. */
  fun listSnapshots(projectRoot: File, relativePath: String): List<LocalHistorySnapshot> {
    val dir = snapshotDirFor(projectRoot, relativePath)
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles { f -> f.isFile && f.name.endsWith(".snapshot") }
      ?.mapNotNull { file ->
        val timestamp = file.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
        LocalHistorySnapshot(projectRoot, relativePath, timestamp, file.length(), file)
      }
      ?.sortedByDescending { it.timestamp }
      ?: emptyList()
  }

  /** Reads the raw content of a snapshot. */
  fun readSnapshot(snapshot: LocalHistorySnapshot): ByteArray? =
    runCatching { snapshot.file.readBytes() }.getOrNull()

  /**
   * Returns true when the newest stored snapshot for the file already holds exactly [content],
   * so we can skip writing a duplicate.
   */
  fun isDuplicate(projectRoot: File, relativePath: String, content: ByteArray): Boolean {
    val latest = listSnapshots(projectRoot, relativePath).firstOrNull() ?: return false
    if (latest.size != content.size.toLong()) return false
    val stored = readSnapshot(latest) ?: return false
    return stored.contentEquals(content)
  }

  /** Deletes snapshots beyond [LocalHistoryConfig.MAX_SNAPSHOTS_PER_FILE] for a single file. */
  fun enforceFileLimit(projectRoot: File, relativePath: String) {
    try {
      val snapshots = listSnapshots(projectRoot, relativePath)
      if (snapshots.size <= LocalHistoryConfig.MAX_SNAPSHOTS_PER_FILE) return
      snapshots.drop(LocalHistoryConfig.MAX_SNAPSHOTS_PER_FILE).forEach { snapshot ->
        runCatching { snapshot.file.delete() }
      }
    } catch (e: Exception) {
      log.warn("LocalHistory: per-file retention failed for {}", relativePath, e)
    }
  }

  /**
   * Global retention sweep: deletes snapshots older than
   * [LocalHistoryConfig.MAX_SNAPSHOT_AGE_DAYS] and, if the storage is still over
   * [LocalHistoryConfig.MAX_TOTAL_BYTES], deletes the oldest snapshots until it fits.
   *
   * Throttled to [LocalHistoryConfig.RETENTION_SWEEP_INTERVAL_MILLIS], unless [force] is true.
   */
  fun enforceRetention(force: Boolean = false) {
    val now = System.currentTimeMillis()
    if (!force && now - lastGlobalSweepMillis < LocalHistoryConfig.RETENTION_SWEEP_INTERVAL_MILLIS) {
      return
    }
    lastGlobalSweepMillis = now
    try {
      val base = baseDir()
      if (!base.isDirectory) return

      val maxAgeMillis = LocalHistoryConfig.MAX_SNAPSHOT_AGE_DAYS * 24L * 60L * 60L * 1000L
      val all = collectSnapshots(base).sortedBy { it.lastModified() }

      // 1. Age-based expiry.
      all.forEach { file ->
        if (now - file.lastModified() > maxAgeMillis) {
          runCatching { file.delete() }
        }
      }

      // 2. Total size ceiling, oldest first.
      var remaining = collectSnapshots(base).sortedBy { it.lastModified() }
      var total = remaining.sumOf { it.length() }
      val iterator = remaining.iterator()
      while (total > LocalHistoryConfig.MAX_TOTAL_BYTES && iterator.hasNext()) {
        val file = iterator.next()
        total -= file.length()
        runCatching { file.delete() }
      }

      // 3. Drop now-empty snapshot directories (keeps the tree tidy).
      pruneEmptyDirs(base)
    } catch (e: Exception) {
      log.warn("LocalHistory: retention sweep failed", e)
    }
  }

  private fun collectSnapshots(base: File): List<File> {
    val result = mutableListOf<File>()
    base.walkTopDown()
      .onEnter { dir -> !dir.name.startsWith(".") || dir == base }
      .filter { it.isFile && it.name.endsWith(".snapshot") }
      .forEach { result.add(it) }
    return result
  }

  private fun pruneEmptyDirs(dir: File) {
    dir.listFiles()?.forEach { child ->
      if (child.isDirectory) {
        pruneEmptyDirs(child)
        // Never delete the storage root itself.
        if (child != baseDir() && child.listFiles()?.isEmpty() == true) {
          runCatching { child.delete() }
        }
      }
    }
  }

  private fun writeProjectInfo(projectRoot: File) {
    try {
      val info = File(File(baseDir(), projectIdFor(projectRoot)), "project.info")
      if (info.exists()) return
      info.parentFile?.mkdirs()
      info.writeText("path=${runCatching { projectRoot.canonicalPath }.getOrDefault(projectRoot.absolutePath)}\n")
    } catch (e: Exception) {
      log.debug("LocalHistory: cannot write project.info", e)
    }
  }

  private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray(Charsets.UTF_8))
      .joinToString("") { b -> "%02x".format(b) }
  }
}
