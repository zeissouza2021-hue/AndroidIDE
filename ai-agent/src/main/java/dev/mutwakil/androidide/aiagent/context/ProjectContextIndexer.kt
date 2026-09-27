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

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds small, bounded views of a project for LLM context.
 *
 * Everything here is deliberately cheap and lossy: directory walks skip build
 * outputs and VCS metadata, cap the number of visited files, and truncate all
 * produced text. The whole project is never loaded into memory.
 */
class ProjectContextIndexer {

    /**
     * Summarizes [root]: truncated file tree, detected languages and Gradle
     * dependency info. Runs on [Dispatchers.IO].
     */
    suspend fun snapshot(root: File): ProjectSnapshot = withContext(Dispatchers.IO) {
        if (!root.isDirectory) {
            return@withContext ProjectSnapshot(
                rootPath = root.absolutePath,
                tree = "",
                fileCount = 0,
                truncated = false,
                languages = emptySet(),
                dependencies = "",
            )
        }
        val (files, truncated) = collectFiles(root, MAX_FILES)
        val languages = files
            .mapNotNull { LANGUAGE_BY_EXTENSION[it.extension.lowercase()] }
            .toSet()
        ProjectSnapshot(
            rootPath = root.absolutePath,
            tree = buildTree(root, files).take(MAX_TREE_CHARS),
            fileCount = files.size,
            truncated = truncated,
            languages = languages,
            dependencies = extractDependencies(root, files).take(MAX_DEPENDENCIES_CHARS),
        )
    }

    /**
     * Simple keyword search over file names and text contents.
     *
     * Returns `path:line: snippet` hits (plus a short head for filename matches),
     * truncated to [maxChars]. Binary files and files larger than 512 KiB are skipped.
     * Runs on [Dispatchers.IO].
     */
    suspend fun relevantSnippets(root: File, query: String, maxChars: Int = 4000): String =
        withContext(Dispatchers.IO) {
            val terms = query.lowercase()
                .split(Regex("[^a-z0-9_]+"))
                .filter { it.length >= 2 }
                .distinct()
            if (terms.isEmpty() || !root.isDirectory || maxChars <= 0) return@withContext ""

            val sb = StringBuilder()
            val stack = ArrayDeque<File>()
            stack.add(root)
            var filesScanned = 0
            outer@ while (stack.isNotEmpty() && sb.length < maxChars && filesScanned < MAX_SCAN_FILES) {
                val dir = stack.removeLast()
                val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
                for (child in children) {
                    if (sb.length >= maxChars) break@outer
                    if (child.isDirectory) {
                        if (child.name !in SKIPPED_DIRS) stack.add(child)
                        continue
                    }
                    filesScanned++
                    if (child.length() > MAX_SNIPPET_FILE_BYTES) continue
                    if (child.extension.lowercase() in BINARY_EXTENSIONS) continue
                    val rel = runCatching { child.relativeTo(root).path }.getOrNull() ?: child.name

                    if (terms.any { it in child.name.lowercase() }) {
                        sb.append("### ").append(rel).append(" (filename match)\n")
                        appendHead(child, sb, maxChars, lines = 15)
                        continue
                    }
                    var matches = 0
                    runCatching {
                        child.bufferedReader().useLines { lines ->
                            var lineNo = 0
                            for (line in lines) {
                                lineNo++
                                if (matches >= MAX_MATCHES_PER_FILE || sb.length >= maxChars) break
                                if (terms.any { it in line.lowercase() }) {
                                    sb.append(rel).append(':').append(lineNo).append(": ")
                                        .append(line.trim().take(MAX_SNIPPET_LINE_CHARS))
                                        .append('\n')
                                    matches++
                                }
                            }
                        }
                    }
                    if (matches > 0) sb.append('\n')
                }
            }
            sb.toString().take(maxChars)
        }

    /** Iterative walk that prunes [SKIPPED_DIRS]; returns files plus a truncation flag. */
    private fun collectFiles(root: File, maxFiles: Int): Pair<List<File>, Boolean> {
        val result = ArrayList<File>(maxFiles)
        var truncated = false
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            if (result.size >= maxFiles) {
                truncated = true
                break
            }
            val dir = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrNull()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name !in SKIPPED_DIRS) stack.add(child)
                } else {
                    result.add(child)
                    if (result.size >= maxFiles) {
                        truncated = true
                        break
                    }
                }
            }
        }
        return result to truncated
    }

    private fun buildTree(root: File, files: List<File>): String {
        val sb = StringBuilder()
        val sorted = files
            .map { runCatching { it.relativeTo(root).path }.getOrNull() ?: it.name }
            .sorted()
        for (rel in sorted) {
            val depth = rel.count { it == '/' }
            repeat(depth) { sb.append("  ") }
            sb.append(rel.substringAfterLast('/')).append('\n')
        }
        return sb.toString()
    }

    /** Concatenates settings.gradle*/build.gradle* contents found among [files]. */
    private fun extractDependencies(root: File, files: List<File>): String {
        val sb = StringBuilder()
        val gradleFiles = files.filter { it.name in GRADLE_FILES }.take(MAX_GRADLE_FILES)
        for (file in gradleFiles) {
            val rel = runCatching { file.relativeTo(root).path }.getOrNull() ?: file.name
            val content = runCatching { file.readText() }.getOrNull()?.take(MAX_GRADLE_FILE_CHARS)
                ?: continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("===== ").append(rel).append(" =====\n").append(content)
            if (sb.length >= MAX_DEPENDENCIES_CHARS) break
        }
        return sb.toString()
    }

    private fun appendHead(file: File, sb: StringBuilder, maxChars: Int, lines: Int) {
        runCatching {
            file.bufferedReader().useLines { seq ->
                var count = 0
                for (line in seq) {
                    if (count >= lines || sb.length >= maxChars) break
                    sb.append(line.take(MAX_SNIPPET_LINE_CHARS)).append('\n')
                    count++
                }
            }
        }
        sb.append('\n')
    }

    companion object {
        private const val MAX_FILES = 200
        private const val MAX_TREE_CHARS = 8_000
        private const val MAX_DEPENDENCIES_CHARS = 4_000
        private const val MAX_GRADLE_FILES = 10
        private const val MAX_GRADLE_FILE_CHARS = 8_000
        private const val MAX_SCAN_FILES = 2_000
        private const val MAX_SNIPPET_FILE_BYTES = 512L * 1024L
        private const val MAX_MATCHES_PER_FILE = 3
        private const val MAX_SNIPPET_LINE_CHARS = 220

        private val SKIPPED_DIRS = setOf(
            "build", ".git", ".gradle", ".idea", "node_modules",
            ".cxx", ".externalNativeBuild", "captures",
        )

        private val GRADLE_FILES = setOf(
            "settings.gradle", "settings.gradle.kts",
            "build.gradle", "build.gradle.kts",
        )

        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg",
            "jar", "aar", "class", "dex", "so", "o",
            "zip", "gz", "tar", "7z", "rar",
            "mp3", "mp4", "wav", "ogg", "webm", "pdf",
            "ttf", "otf", "woff", "woff2", "eot",
            "db", "sqlite", "bin", "dat",
        )

        private val LANGUAGE_BY_EXTENSION = mapOf(
            "kt" to "Kotlin",
            "kts" to "Kotlin Script",
            "java" to "Java",
            "xml" to "XML",
            "gradle" to "Gradle",
            "c" to "C",
            "h" to "C/C++ Header",
            "cpp" to "C++",
            "cc" to "C++",
            "cxx" to "C++",
            "hpp" to "C++ Header",
            "py" to "Python",
            "js" to "JavaScript",
            "ts" to "TypeScript",
            "md" to "Markdown",
            "json" to "JSON",
            "properties" to "Properties",
            "aidl" to "AIDL",
            "rs" to "Rust",
            "go" to "Go",
            "sh" to "Shell",
            "css" to "CSS",
            "html" to "HTML",
        )
    }
}
