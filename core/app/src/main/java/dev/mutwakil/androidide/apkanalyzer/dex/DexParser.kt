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

package dev.mutwakil.androidide.apkanalyzer.dex

/**
 * Minimal DEX parser: reads just enough of a `classesN.dex` file to list
 * the class descriptors (`Lcom/example/Foo;`) defined in it.
 *
 * Only the header, `string_ids`, `type_ids` and `class_defs` sections are
 * touched; method/field data is never parsed.
 */
object DexParser {

  private const val HEADER_SIZE = 0x70
  private const val ENDIAN_CONSTANT = 0x12345678

  data class DexInfo(
    val entryName: String,
    val classCount: Int,
    val classNames: List<String>,
  )

  @Throws(IllegalArgumentException::class)
  fun parse(entryName: String, bytes: ByteArray): DexInfo {
    if (bytes.size < HEADER_SIZE) {
      throw IllegalArgumentException("File too small to be a DEX file")
    }
    val magic = bytes.sliceArray(0 until 8).toString(Charsets.US_ASCII)
    if (!magic.startsWith("dex\n")) {
      throw IllegalArgumentException("Bad DEX magic: ${magic.take(8)}")
    }
    val reader = Reader(bytes)
    reader.pos = 36 // header_size, endian_tag (file_size is at 32)
    val headerSize = reader.u32()
    val endianTag = reader.u32()
    if (headerSize != HEADER_SIZE || endianTag != ENDIAN_CONSTANT) {
      throw IllegalArgumentException("Unsupported DEX header")
    }
    reader.pos = 56
    val stringIdsSize = reader.u32()
    val stringIdsOff = reader.u32()
    val typeIdsSize = reader.u32()
    val typeIdsOff = reader.u32()
    reader.pos = 96
    val classDefsSize = reader.u32()
    val classDefsOff = reader.u32()

    if (classDefsSize > 200000 || stringIdsSize > 1000000 || typeIdsSize > 1000000) {
      throw IllegalArgumentException("Suspicious DEX section sizes")
    }

    // type_id_item = descriptor_idx (u32)
    val descriptors = IntArray(typeIdsSize) { i ->
      reader.pos = typeIdsOff + i * 4
      reader.u32()
    }
    // string_data_item = uleb128 length + MUTF-8 bytes
    fun stringAt(index: Int): String {
      reader.pos = stringIdsOff + index * 4
      val dataOff = reader.u32()
      reader.pos = dataOff
      return reader.mutf8String()
    }

    val names = ArrayList<String>(classDefsSize.coerceAtMost(100000))
    repeat(classDefsSize) { i ->
      reader.pos = classDefsOff + i * 32
      val classIdx = reader.u32()
      if (classIdx < typeIdsSize) {
        val descriptorIdx = descriptors[classIdx]
        if (descriptorIdx < stringIdsSize) {
          names += prettifyDescriptor(stringAt(descriptorIdx))
        }
      }
    }
    names.sort()
    return DexInfo(entryName, names.size, names)
  }

  /** `Lcom/example/Foo;` -> `com.example.Foo`, `[I` -> `int[]`. */
  private fun prettifyDescriptor(descriptor: String): String {
    var d = descriptor
    var arrayDepth = 0
    while (d.startsWith("[")) {
      arrayDepth++
      d = d.substring(1)
    }
    val base =
      when {
        d.startsWith("L") && d.endsWith(";") -> d.substring(1, d.length - 1).replace('/', '.')
        d == "Z" -> "boolean"
        d == "B" -> "byte"
        d == "S" -> "short"
        d == "C" -> "char"
        d == "I" -> "int"
        d == "J" -> "long"
        d == "F" -> "float"
        d == "D" -> "double"
        d == "V" -> "void"
        else -> d
      }
    return base + "[]".repeat(arrayDepth)
  }

  private class Reader(private val bytes: ByteArray) {
    var pos: Int = 0

    private fun need(n: Int) {
      if (pos < 0 || pos + n > bytes.size) {
        throw IllegalArgumentException("Truncated DEX file")
      }
    }

    fun u32(): Int {
      need(4)
      val v =
        (bytes[pos].toInt() and 0xFF) or
          ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
          ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
          ((bytes[pos + 3].toInt() and 0xFF) shl 24)
      pos += 4
      return v
    }

    fun uleb128(): Int {
      var result = 0
      var shift = 0
      while (shift < 32) {
        need(1)
        val b = bytes[pos++].toInt() and 0xFF
        result = result or ((b and 0x7F) shl shift)
        if ((b and 0x80) == 0) return result
        shift += 7
      }
      throw IllegalArgumentException("Malformed uleb128")
    }

    fun mutf8String(): String {
      val length = uleb128()
      if (length < 0 || length > 10_000_000) throw IllegalArgumentException("Bad string length")
      need(length)
      val slice = bytes.sliceArray(pos until pos + length)
      pos += length
      // Class descriptors are ASCII; MUTF-8 differences don't matter here.
      return slice.toString(Charsets.UTF_8)
    }
  }
}
