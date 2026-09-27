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

package dev.mutwakil.androidide.apkanalyzer.axml

/**
 * Minimal parser for Android binary XML (AXML), the format used for
 * `AndroidManifest.xml` inside APKs.
 *
 * Reads the string pool, namespace declarations and element/attribute nodes
 * and rebuilds a readable, pretty-printed XML document. It is intentionally
 * self-contained (no AAPT dependency) so the APK Analyzer works fully
 * offline on-device.
 *
 * Supported value types: string, reference, attribute, float, dimension,
 * fraction, int (dec/hex/boolean) and colors. Anything else is rendered as
 * its raw hex value.
 */
object AXmlParser {

  // Chunk types
  private const val RES_XML_TYPE = 0x0003
  private const val RES_STRING_POOL_TYPE = 0x0001
  private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
  private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
  private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
  private const val RES_XML_START_ELEMENT_TYPE = 0x0102
  private const val RES_XML_END_ELEMENT_TYPE = 0x0103
  private const val RES_XML_CDATA_TYPE = 0x0104

  private const val UTF8_FLAG = 1 shl 8
  private const val NO_INDEX = -1

  // Res_value data types
  private const val TYPE_NULL = 0x00
  private const val TYPE_REFERENCE = 0x01
  private const val TYPE_ATTRIBUTE = 0x02
  private const val TYPE_STRING = 0x03
  private const val TYPE_FLOAT = 0x04
  private const val TYPE_DIMENSION = 0x05
  private const val TYPE_FRACTION = 0x06
  private const val TYPE_INT_DEC = 0x10
  private const val TYPE_INT_HEX = 0x11
  private const val TYPE_INT_BOOLEAN = 0x12
  private const val TYPE_INT_COLOR_ARGB8 = 0x1c
  private const val TYPE_INT_COLOR_RGB8 = 0x1d
  private const val TYPE_INT_COLOR_ARGB4 = 0x1e
  private const val TYPE_INT_COLOR_RGB4 = 0x1f

  private val DIMENSION_UNITS = arrayOf("px", "dp", "sp", "pt", "in", "mm")
  private val FRACTION_UNITS = arrayOf("%", "%p")
  // 1/256 factor included: value = (complex & 0xFFFFFF00) * MULTS[radix].
  private val RADIX_MULTS = floatArrayOf(1f / 256f, 1f / 32768f, 1f / 8388608f, 1f / 2147483648f)

  @Throws(IllegalArgumentException::class)
  fun toXml(bytes: ByteArray): String {
    val reader = Reader(bytes)
    // File header
    val type = reader.u16()
    reader.u16() // headerSize
    reader.u32() // size
    if (type != RES_XML_TYPE) {
      throw IllegalArgumentException("Not a binary XML file (type=0x${type.toString(16)})")
    }

    var stringPool: List<String> = emptyList()
    val root = ElementNode("#root")
    val stack = ArrayDeque<ElementNode>()
    stack.addLast(root)
    val uriToPrefix = mutableMapOf<String, String>()

    while (reader.remaining() >= 8) {
      val chunkStart = reader.pos
      val chunkType = reader.u16()
      reader.u16() // headerSize
      val chunkSize = reader.u32().toInt()
      if (chunkSize < 8 || chunkStart + chunkSize > bytes.size) break

      when (chunkType) {
        RES_STRING_POOL_TYPE -> {
          stringPool = readStringPool(reader, chunkStart)
        }
        RES_XML_RESOURCE_MAP_TYPE -> {
          // Array of u32 resource ids; not needed for display.
        }
        RES_XML_START_NAMESPACE_TYPE -> {
          reader.u32() // lineNumber
          reader.u32() // comment
          val prefix = stringPool.getOrNull(reader.u32().toInt()) ?: ""
          val uri = stringPool.getOrNull(reader.u32().toInt()) ?: ""
          if (uri.isNotEmpty() && prefix.isNotEmpty()) {
            uriToPrefix[uri] = prefix
          }
        }
        RES_XML_END_NAMESPACE_TYPE -> {
          // Nothing to do for display purposes.
        }
        RES_XML_START_ELEMENT_TYPE -> {
          reader.u32() // lineNumber
          reader.u32() // comment
          val nsIdx = reader.u32().toInt()
          val nameIdx = reader.u32().toInt()
          reader.u16() // attributeStart
          reader.u16() // attributeSize
          val attributeCount = reader.u16()
          reader.u16() // idIndex
          reader.u16() // classIndex
          reader.u16() // styleIndex

          val name = stringPool.getOrNull(nameIdx) ?: "?"
          val node = ElementNode(name)
          repeat(attributeCount) {
            val attrNsIdx = reader.u32().toInt()
            val attrNameIdx = reader.u32().toInt()
            reader.u32() // rawValue
            reader.u16() // typedValue size
            reader.u8() // res0
            val dataType = reader.u8()
            val data = reader.u32()
            val attrName = stringPool.getOrNull(attrNameIdx) ?: "?"
            val qualified = qualifyName(attrNsIdx, attrName, stringPool, uriToPrefix)
            node.attributes += qualified to formatValue(dataType, data, stringPool)
          }
          stack.last().children += node
          stack.addLast(node)
        }
        RES_XML_END_ELEMENT_TYPE -> {
          if (stack.size > 1) stack.removeLast()
        }
        RES_XML_CDATA_TYPE -> {
          reader.u32() // lineNumber
          reader.u32() // comment
          val dataIdx = reader.u32().toInt()
          reader.u16() // typedValue size
          reader.u8() // res0
          val dataType = reader.u8()
          val data = reader.u32()
          val text =
            if (dataType == TYPE_STRING) {
              stringPool.getOrNull(dataIdx) ?: stringPool.getOrNull(data.toInt()) ?: ""
            } else {
              stringPool.getOrNull(dataIdx) ?: ""
            }
          stack.last().text += text
        }
        else -> {
          // Unknown chunk: skip.
        }
      }
      // Always advance to the end of the chunk.
      reader.pos = chunkStart + chunkSize
    }

    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
    for (child in root.children) {
      serialize(child, sb, 0)
    }
    return sb.toString()
  }

  private fun qualifyName(
    nsIdx: Int,
    name: String,
    pool: List<String>,
    uriToPrefix: Map<String, String>,
  ): String {
    if (nsIdx == NO_INDEX) return name
    val uri = pool.getOrNull(nsIdx) ?: return name
    val prefix = uriToPrefix[uri] ?: return name
    return "$prefix:$name"
  }

  private fun formatValue(dataType: Int, data: Long, pool: List<String>): String {
    return when (dataType) {
      TYPE_NULL -> "null"
      TYPE_REFERENCE -> "@0x%08X".format(data)
      TYPE_ATTRIBUTE -> "?0x%08X".format(data)
      TYPE_STRING -> escapeXml(pool.getOrNull(data.toInt()) ?: "")
      TYPE_FLOAT -> formatFloat(java.lang.Float.intBitsToFloat(data.toInt()))
      TYPE_DIMENSION -> formatComplex(data.toInt(), false)
      TYPE_FRACTION -> formatComplex(data.toInt(), true)
      TYPE_INT_DEC -> data.toInt().toString()
      TYPE_INT_HEX -> "0x" + data.toInt().toUInt().toString(16).uppercase()
      TYPE_INT_BOOLEAN -> if (data != 0L) "true" else "false"
      TYPE_INT_COLOR_ARGB8 -> "#%08X".format(data)
      TYPE_INT_COLOR_RGB8 -> "#FF%06X".format(data and 0xFFFFFF)
      TYPE_INT_COLOR_ARGB4 -> expandColor4(data.toInt(), true)
      TYPE_INT_COLOR_RGB4 -> expandColor4(data.toInt(), false)
      else -> "0x" + data.toUInt().toString(16).uppercase()
    }
  }

  private fun formatFloat(value: Float): String {
    return if (value % 1f == 0f) value.toInt().toString() else value.toString()
  }

  private fun formatComplex(data: Int, isFraction: Boolean): String {
    val mantissa = data and -0x100 // top 24 bits
    val radix = (data shr 4) and 0x3
    val unit = data and 0xF
    val value = mantissa.toFloat() * RADIX_MULTS[radix]
    val unitStr =
      if (isFraction) FRACTION_UNITS.getOrElse(unit) { "?" }
      else DIMENSION_UNITS.getOrElse(unit) { "?" }
    return formatFloat(value) + unitStr
  }

  private fun expandColor4(data: Int, hasAlpha: Boolean): String {
    val a = if (hasAlpha) (data shr 12) and 0xF else 0xF
    val r = (data shr 8) and 0xF
    val g = (data shr 4) and 0xF
    val b = data and 0xF
    fun e(v: Int) = (v * 17).coerceIn(0, 255)
    return "#%02X%02X%02X%02X".format(e(a), e(r), e(g), e(b))
  }

  private fun serialize(node: ElementNode, sb: StringBuilder, depth: Int) {
    val indent = "  ".repeat(depth)
    sb.append(indent).append('<').append(node.name)
    for ((name, value) in node.attributes) {
      sb.append(' ').append(name).append("=\"").append(value).append('"')
    }
    val hasChildren = node.children.isNotEmpty()
    val hasText = node.text.isNotBlank()
    if (!hasChildren && !hasText) {
      sb.append(" />\n")
      return
    }
    sb.append('>')
    if (hasChildren) {
      sb.append('\n')
      for (child in node.children) {
        serialize(child, sb, depth + 1)
      }
      sb.append(indent)
    } else {
      sb.append(escapeXml(node.text.trim()))
    }
    sb.append("</").append(node.name).append(">\n")
  }

  private fun escapeXml(text: String): String {
    return text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
  }

  private fun readStringPool(reader: Reader, chunkStart: Int): List<String> {
    val stringCount = reader.u32().toInt()
    val styleCount = reader.u32().toInt()
    val flags = reader.u32()
    val stringsStart = reader.u32().toInt()
    reader.u32() // stylesStart
    if (stringCount < 0 || stringCount > 100000) {
      throw IllegalArgumentException("Suspicious string pool size: $stringCount")
    }
    val offsets = IntArray(stringCount) { reader.u32().toInt() }
    // Skip style offsets.
    repeat(styleCount) { reader.u32() }

    val utf8 = (flags and UTF8_FLAG.toLong()) != 0L
    val result = ArrayList<String>(stringCount)
    for (i in 0 until stringCount) {
      reader.pos = chunkStart + stringsStart + offsets[i]
      result +=
        if (utf8) reader.utf8String() else reader.utf16String()
    }
    return result
  }

  private class ElementNode(val name: String) {
    val attributes = mutableListOf<Pair<String, String>>()
    val children = mutableListOf<ElementNode>()
    var text: String = ""
  }

  /** Little-endian byte reader with bounds checking. */
  private class Reader(private val bytes: ByteArray) {
    var pos: Int = 0

    fun remaining(): Int = bytes.size - pos

    private fun need(n: Int) {
      if (pos + n > bytes.size) throw IllegalArgumentException("Truncated binary XML")
    }

    fun u8(): Int {
      need(1)
      return bytes[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
      need(2)
      val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
      pos += 2
      return v
    }

    fun u32(): Long {
      need(4)
      val v =
        (bytes[pos].toLong() and 0xFF) or
          ((bytes[pos + 1].toLong() and 0xFF) shl 8) or
          ((bytes[pos + 2].toLong() and 0xFF) shl 16) or
          ((bytes[pos + 3].toLong() and 0xFF) shl 24)
      pos += 4
      return v
    }

    /** Reads a length field encoded as u16, or u32 when the high bit is set. */
    private fun lengthField(): Int {
      val first = u16()
      return if ((first and 0x8000) != 0) {
        ((first and 0x7FFF) shl 16) or u16()
      } else {
        first
      }
    }

    fun utf16String(): String {
      val charCount = lengthField()
      if (charCount < 0 || charCount > 100000) throw IllegalArgumentException("Bad UTF-16 length")
      need(charCount * 2 + 2)
      val chars = CharArray(charCount)
      for (i in 0 until charCount) {
        chars[i] = u16().toChar()
      }
      u16() // null terminator
      return String(chars)
    }

    fun utf8String(): String {
      lengthField() // utf16 length, not needed
      val byteCount = lengthField()
      if (byteCount < 0 || byteCount > 1000000) throw IllegalArgumentException("Bad UTF-8 length")
      need(byteCount + 1)
      val bytes = ByteArray(byteCount)
      for (i in 0 until byteCount) {
        bytes[i] = this.bytes[pos++]
      }
      u8() // null terminator
      return String(bytes, Charsets.UTF_8)
    }
  }
}
