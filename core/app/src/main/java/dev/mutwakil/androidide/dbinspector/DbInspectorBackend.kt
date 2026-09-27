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

package dev.mutwakil.androidide.dbinspector

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.mutwakil.androidide.shell.executeProcessAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Typed failures for database inspection. The activity maps these to
 * user-facing messages; nothing here ever crashes the caller.
 */
sealed class DbInspectorError(message: String, cause: Throwable? = null) :
  Exception(message, cause) {

  class InvalidName(val what: String) : DbInspectorError("Invalid name: $what")
  class AppNotInstalled(val packageName: String) :
    DbInspectorError("App not installed: $packageName")
  class AppNotDebuggable(val packageName: String) :
    DbInspectorError("App not debuggable: $packageName")
  class NoDatabases(val packageName: String) :
    DbInspectorError("No databases for $packageName")
  class AccessFailed(val details: String) : DbInspectorError("Access failed: $details")
  class InvalidDatabase(val dbName: String) : DbInspectorError("Invalid database: $dbName")
  object WriteNotAllowed : DbInspectorError("Write not allowed")
}

/**
 * Low-level access to the SQLite databases of the app being developed.
 *
 * The IDE runs on the same device as the developed app, so for debuggable apps
 * the `run-as` tool can list and copy files out of the app's private data
 * directory. Everything is copied into the IDE's own cache directory first and
 * opened read-only; the app's files are never touched unless the user
 * explicitly opts into write mode and confirms pushing changes back.
 *
 * All [run-as] calls run on [Dispatchers.IO] and have a hard timeout.
 */
object DbInspectorBackend {

  /** Maximum number of rows materialized for a table or a query. */
  const val MAX_ROWS = 500

  private val log = LoggerFactory.getLogger(DbInspectorBackend::class.java)

  private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
  private val DB_NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9_.\\-]*$")
  private val READ_QUERY_REGEX = Regex("^(SELECT|WITH|PRAGMA|EXPLAIN)\\b", RegexOption.IGNORE_CASE)
  private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

  private const val RUN_AS_TIMEOUT_SECONDS = 60L

  data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    /**
     * Set for non-SELECT statements: number of rows affected, or -1 if unknown.
     */
    val rowsAffected: Long = -1
  )

  private data class CommandResult(val exitCode: Int, val output: String)

  /**
   * Verifies that the target app is installed and debuggable (required for
   * `run-as` access).
   */
  fun checkTargetApp(context: Context, packageName: String): Result<Unit> {
    if (!PACKAGE_NAME_REGEX.matches(packageName)) {
      return Result.failure(DbInspectorError.InvalidName("package name"))
    }
    val appInfo = try {
      context.packageManager.getApplicationInfo(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
      return Result.failure(DbInspectorError.AppNotInstalled(packageName))
    }
    if (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
      return Result.failure(DbInspectorError.AppNotDebuggable(packageName))
    }
    return Result.success(Unit)
  }

  /**
   * Lists database files in the app's `databases/` directory, excluding
   * `-journal`, `-wal` and `-shm` companion files.
   */
  suspend fun listDatabases(packageName: String): Result<List<String>> = runCatching {
    val result = runAsText(packageName, listOf("ls", "databases/"))
    if (result.exitCode != 0) {
      if ("No such file or directory" in result.output) {
        throw DbInspectorError.NoDatabases(packageName)
      }
      throw DbInspectorError.AccessFailed(result.output.trim().take(300).ifBlank {
        "exit=${result.exitCode}"
      })
    }
    val names = result.output.lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() && DB_NAME_REGEX.matches(it) }
      .filterNot { it.endsWith("-journal") || it.endsWith("-wal") || it.endsWith("-shm") }
    if (names.isEmpty()) {
      throw DbInspectorError.NoDatabases(packageName)
    }
    names.sorted()
  }

  /**
   * Pulls the database (plus `-wal`/`-shm` companions when present, for a
   * consistent snapshot) into the IDE's cache directory and returns the local
   * snapshot file. The snapshot is validated as a real SQLite file first.
   */
  suspend fun pullDatabase(
    context: Context,
    packageName: String,
    dbName: String
  ): Result<File> = runCatching {
    requireDbName(dbName)
    val dir = snapshotDir(context, packageName)
    // Drop any stale snapshot first so a failed pull never leaves old data behind.
    File(dir, dbName).delete()
    File(dir, "$dbName-wal").delete()
    File(dir, "$dbName-shm").delete()

    val dest = File(dir, dbName)
    pullFile(packageName, dbName, dest)
    // Pull WAL companions for a consistent snapshot; missing ones are fine.
    for (suffix in arrayOf("-wal", "-shm")) {
      val companion = File(dir, "$dbName$suffix")
      try {
        pullFile(packageName, "$dbName$suffix", companion)
      } catch (e: DbInspectorError.AccessFailed) {
        companion.delete()
        log.debug("No {} companion for {}", suffix, dbName)
      }
    }
    if (!hasSqliteHeader(dest)) {
      dest.delete()
      throw DbInspectorError.InvalidDatabase(dbName)
    }
    log.info("Pulled database {} ({} bytes)", dbName, dest.length())
    dest
  }

  /**
   * Pushes a modified working copy back into the app's `databases/` directory.
   * The main file is always pushed; `-wal`/`-shm` companions are pushed only
   * when they exist in the working copy.
   */
  suspend fun pushDatabase(
    packageName: String,
    dbName: String,
    workingCopy: File
  ): Result<Unit> = runCatching {
    requireDbName(dbName)
    if (!workingCopy.isFile) {
      throw DbInspectorError.InvalidDatabase(dbName)
    }
    val pairs = mutableListOf(workingCopy to dbName)
    for (suffix in arrayOf("-wal", "-shm")) {
      val companion = File("${workingCopy.path}$suffix")
      if (companion.isFile) {
        pairs.add(companion to "$dbName$suffix")
      }
    }
    for ((local, remote) in pairs) {
      val result = pushFile(packageName, local, remote)
      if (result.exitCode != 0) {
        throw DbInspectorError.AccessFailed(result.output.trim().take(300).ifBlank {
          "exit=${result.exitCode}"
        })
      }
    }
    log.info("Pushed {} to {}:databases/{}", workingCopy.name, packageName, dbName)
  }

  /**
   * Creates a writable working copy of a snapshot (main file plus any WAL
   * companions), so the original snapshot stays untouched.
   */
  fun makeWorkingCopy(snapshot: File): File {
    val work = File(snapshot.parentFile, "${snapshot.name}.work")
    snapshot.copyTo(work, overwrite = true)
    for (suffix in arrayOf("-wal", "-shm")) {
      val src = File("${snapshot.path}$suffix")
      val dst = File("${work.path}$suffix")
      if (src.isFile) {
        src.copyTo(dst, overwrite = true)
      } else {
        dst.delete()
      }
    }
    return work
  }

  fun openReadOnly(file: File): SQLiteDatabase =
    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

  fun openReadWrite(file: File): SQLiteDatabase =
    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

  fun listTables(db: SQLiteDatabase): List<String> {
    db.rawQuery(
      "SELECT name FROM sqlite_master WHERE type IN ('table','view') " +
        "AND name NOT LIKE 'sqlite_%' ORDER BY name",
      null
    ).use { cursor ->
      val tables = mutableListOf<String>()
      while (cursor.moveToNext()) {
        tables.add(cursor.getString(0))
      }
      return tables
    }
  }

  fun tableRowCount(db: SQLiteDatabase, table: String): Long {
    db.rawQuery("SELECT COUNT(*) FROM ${quoteIdentifier(table)}", null).use { cursor ->
      return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }
  }

  fun readTable(
    db: SQLiteDatabase,
    table: String,
    limit: Int = MAX_ROWS,
    offset: Int = 0
  ): QueryResult {
    db.rawQuery(
      "SELECT * FROM ${quoteIdentifier(table)} LIMIT $limit OFFSET $offset",
      null
    ).use { cursor ->
      return cursorToResult(cursor, limit)
    }
  }

  /**
   * Runs a custom SQL statement.
   *
   * Read-only statements (`SELECT`, `WITH`, `PRAGMA`, `EXPLAIN`) always run.
   * Anything else requires [allowWrite] (explicit user opt-in) and runs via
   * `execSQL`; the number of affected rows is reported through
   * [QueryResult.rowsAffected]. Only a single statement is executed.
   */
  fun runQuery(
    db: SQLiteDatabase,
    sql: String,
    allowWrite: Boolean,
    blobFormatter: (Int) -> String = { size -> "<blob, $size bytes>" }
  ): QueryResult {
    val statement = stripLeadingComments(sql).trim()
    if (statement.isEmpty()) {
      throw IllegalArgumentException("Empty query")
    }
    val isRead = READ_QUERY_REGEX.containsMatchIn(statement)
    if (!isRead && !allowWrite) {
      throw DbInspectorError.WriteNotAllowed
    }
    return if (isRead) {
      db.rawQuery(sql, null).use { cursor -> cursorToResult(cursor, MAX_ROWS, blobFormatter) }
    } else {
      db.execSQL(sql)
      val affected = db.compileStatement("SELECT changes()").use { it.simpleQueryForLong() }
      QueryResult(emptyList(), emptyList(), affected)
    }
  }

  // -------------------------------------------------------------------------
  // run-as helpers
  // -------------------------------------------------------------------------

  private fun snapshotDir(context: Context, packageName: String): File =
    File(context.cacheDir, "db_inspector/$packageName").apply { mkdirs() }

  private fun requireDbName(dbName: String) {
    if (!DB_NAME_REGEX.matches(dbName) || dbName.contains("..")) {
      throw DbInspectorError.InvalidName("database name")
    }
  }

  private fun newRunAsProcess(packageName: String, args: List<String>): Process {
    return try {
      executeProcessAsync {
        command = listOf("run-as", packageName) + args
        redirectErrorStream = true
      }
    } catch (e: Exception) {
      throw DbInspectorError.AccessFailed("cannot start run-as: ${e.message?.take(200)}")
    }
  }

  private fun awaitExit(process: Process) {
    val finished = process.waitFor(RUN_AS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      throw DbInspectorError.AccessFailed("run-as timed out")
    }
  }

  private suspend fun runAsText(packageName: String, args: List<String>): CommandResult =
    withContext(Dispatchers.IO) {
      val process = newRunAsProcess(packageName, args)
      process.outputStream.close()
      val outBytes = process.inputStream.use { it.readBytes() }
      awaitExit(process)
      CommandResult(process.exitValue(), outBytes.toString(Charsets.UTF_8))
    }

  /**
   * Streams `run-as <pkg> cat databases/<remoteName>` into [dest]. On failure
   * [dest] is deleted and an [DbInspectorError.AccessFailed] carrying the
   * command's error output is thrown.
   */
  private suspend fun pullFile(packageName: String, remoteName: String, dest: File) =
    withContext(Dispatchers.IO) {
      val process = newRunAsProcess(packageName, listOf("cat", "databases/$remoteName"))
      process.outputStream.close()
      try {
        dest.outputStream().use { out ->
          process.inputStream.use { inp -> inp.copyTo(out) }
        }
      } catch (e: Exception) {
        process.destroyForcibly()
        dest.delete()
        throw DbInspectorError.AccessFailed("I/O with run-as failed: ${e.message?.take(200)}")
      }
      awaitExit(process)
      if (process.exitValue() != 0) {
        // stderr was merged into dest; keep its head as the diagnostic.
        val head = try {
          dest.readBytes().toString(Charsets.UTF_8).trim().take(300)
        } catch (e: Exception) {
          ""
        }
        dest.delete()
        throw DbInspectorError.AccessFailed(head.ifBlank { "exit=${process.exitValue()}" })
      }
    }

  /**
   * Streams [local] into `run-as <pkg> sh -c 'cat > databases/<remoteName>'`.
   */
  private suspend fun pushFile(
    packageName: String,
    local: File,
    remoteName: String
  ): CommandResult =
    withContext(Dispatchers.IO) {
      val process = newRunAsProcess(packageName, listOf("sh", "-c", "cat > databases/$remoteName"))
      try {
        local.inputStream().use { inp ->
          process.outputStream.use { out -> inp.copyTo(out) }
        }
      } catch (e: Exception) {
        process.destroyForcibly()
        throw DbInspectorError.AccessFailed("I/O with run-as failed: ${e.message?.take(200)}")
      }
      val outBytes = process.inputStream.use { it.readBytes() }
      awaitExit(process)
      CommandResult(process.exitValue(), outBytes.toString(Charsets.UTF_8))
    }

  private fun hasSqliteHeader(file: File): Boolean {
    if (!file.isFile || file.length() < SQLITE_MAGIC.size) {
      return false
    }
    val header = ByteArray(SQLITE_MAGIC.size)
    var read = 0
    file.inputStream().use { inp ->
      while (read < header.size) {
        val n = inp.read(header, read, header.size - read)
        if (n < 0) break
        read += n
      }
    }
    return read == header.size && header.contentEquals(SQLITE_MAGIC)
  }

  // -------------------------------------------------------------------------
  // SQL helpers
  // -------------------------------------------------------------------------

  private fun quoteIdentifier(name: String): String =
    "\"" + name.replace("\"", "\"\"") + "\""

  private fun stripLeadingComments(sql: String): String {
    var s = sql
    while (true) {
      val t = s.trimStart()
      s = when {
        t.startsWith("--") -> t.substringAfter('\n', "")
        t.startsWith("/*") -> {
          val end = t.indexOf("*/")
          if (end < 0) "" else t.substring(end + 2)
        }
        else -> return t
      }
    }
  }

  private fun cursorToResult(
    cursor: Cursor,
    maxRows: Int,
    blobFormatter: (Int) -> String = { size -> "<blob, $size bytes>" }
  ): QueryResult {
    val columns = (0 until cursor.columnCount).map { index ->
      cursor.getColumnName(index) ?: "col$index"
    }
    val rows = ArrayList<List<String?>>(minOf(maxRows, 64))
    var count = 0
    while (cursor.moveToNext() && count < maxRows) {
      val row = ArrayList<String?>(columns.size)
      for (i in columns.indices) {
        row.add(
          when (cursor.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_BLOB -> blobFormatter(cursor.getBlob(i)?.size ?: 0)
            else -> cursor.getString(i)
          }
        )
      }
      rows.add(row)
      count++
    }
    return QueryResult(columns, rows)
  }
}
