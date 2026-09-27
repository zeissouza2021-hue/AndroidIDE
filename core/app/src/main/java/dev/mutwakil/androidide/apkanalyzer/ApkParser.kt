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

package dev.mutwakil.androidide.apkanalyzer

import dev.mutwakil.androidide.apkanalyzer.axml.AXmlParser
import dev.mutwakil.androidide.apkanalyzer.dex.DexParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Parses an APK file (a zip archive) into an [ApkReport].
 *
 * - Lists every entry with uncompressed/compressed sizes and CRC.
 * - Decodes the binary `AndroidManifest.xml` with [AXmlParser].
 * - Lists classes of every `classesN.dex` with [DexParser].
 */
object ApkParser {

  private val DEX_NAME = Regex("classes\\d*\\.dex")

  @Throws(Exception::class)
  fun parse(apkFile: File): ApkReport {
    val entries = mutableListOf<ApkEntryInfo>()
    var manifestXml: String? = null
    var manifestError: String? = null
    val dexInfos = mutableListOf<DexParser.DexInfo>()

    ZipFile(apkFile).use { zip ->
      val zipEntries = zip.entries().toList()

      for (entry in zipEntries) {
        if (entry.isDirectory) continue
        entries +=
          ApkEntryInfo(
            name = entry.name,
            size = entry.size.coerceAtLeast(0),
            compressedSize = entry.compressedSize.coerceAtLeast(0),
            crc = entry.crc,
            category = categorize(entry.name),
            stored = entry.method == ZipEntry.STORED,
          )
      }

      // Binary AndroidManifest.xml
      val manifestEntry = zip.getEntry("AndroidManifest.xml")
      if (manifestEntry != null) {
        try {
          val bytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
          manifestXml = AXmlParser.toXml(bytes)
        } catch (e: Exception) {
          manifestError = e.message ?: e.javaClass.simpleName
        }
      } else {
        manifestError = "entry not found"
      }

      // DEX files
      for (entry in zipEntries) {
        if (!entry.isDirectory && DEX_NAME.matches(entry.name)) {
          try {
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            dexInfos += DexParser.parse(entry.name, bytes)
          } catch (_: Exception) {
            // Skip unreadable dex files; the overview still works.
          }
        }
      }
    }

    entries.sortByDescending { it.size }
    val totalSize = entries.sumOf { it.size }
    val downloadEstimate = entries.sumOf { it.compressedSize }

    return ApkReport(
      fileName = apkFile.name,
      entries = entries,
      totalSize = totalSize,
      downloadSizeEstimate = downloadEstimate,
      manifestXml = manifestXml,
      manifestError = manifestError,
      dexInfos = dexInfos.sortedBy { it.entryName },
    )
  }

  private fun categorize(name: String): ApkCategory {
    return when {
      DEX_NAME.matches(name) -> ApkCategory.DEX
      name == "AndroidManifest.xml" -> ApkCategory.RES
      name == "resources.arsc" -> ApkCategory.RES
      name.startsWith("res/") -> ApkCategory.RES
      name.startsWith("assets/") -> ApkCategory.ASSETS
      name.startsWith("lib/") -> ApkCategory.LIB
      else -> ApkCategory.OTHER
    }
  }
}
