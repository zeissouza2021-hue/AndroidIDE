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

package dev.mutwakil.androidide.assetstudio

import android.util.Xml
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/**
 * Converts simple SVG artwork into an Android `VectorDrawable` XML document.
 *
 * Supported: `path` (any path data), `rect`, `circle`, `ellipse`, `line`,
 * `polyline` and `polygon` elements with `fill`, `stroke`, `stroke-width`,
 * `fill-rule` and `opacity` presentation attributes. Gradients, filters,
 * masks, text and transforms are not converted (elements carrying them are
 * still imported, but those attributes are ignored).
 *
 * The result is validated with [VectorDrawableValidator] before it is
 * handed to the caller.
 */
object SvgToVectorDrawableConverter {

  private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

  data class ConversionResult(val vectorXml: String, val widthDp: Int, val heightDp: Int)

  @Throws(IllegalArgumentException::class)
  fun convert(svg: String): ConversionResult {
    val parser: XmlPullParser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(StringReader(svg))

    var viewportWidth = 24f
    var viewportHeight = 24f
    var widthDp = 24
    var heightDp = 24
    val paths = mutableListOf<String>()
    var foundSvg = false
    var defaultFill = "#FF000000"

    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
      if (eventType == XmlPullParser.START_TAG) {
        when (parser.name) {
          "svg" -> {
            foundSvg = true
            val viewBox = parser.getAttributeValue(null, "viewBox")
            if (viewBox != null) {
              val parts = viewBox.trim().split(Regex("[\\s,]+"))
              if (parts.size == 4) {
                viewportWidth = parts[2].toFloatOrNull() ?: viewportWidth
                viewportHeight = parts[3].toFloatOrNull() ?: viewportHeight
              }
            } else {
              parser.getAttributeValue(null, "width")?.let {
                parseLength(it)?.let { w -> viewportWidth = w }
              }
              parser.getAttributeValue(null, "height")?.let {
                parseLength(it)?.let { h -> viewportHeight = h }
              }
            }
            parser.getAttributeValue(null, "width")?.let {
              parseLength(it)?.let { w -> widthDp = w.toInt().coerceAtLeast(1) }
            }
            parser.getAttributeValue(null, "height")?.let {
              parseLength(it)?.let { h -> heightDp = h.toInt().coerceAtLeast(1) }
            }
            parser.getAttributeValue(null, "fill")?.let { defaultFill = it }
          }
          "path" -> {
            val d = parser.getAttributeValue(null, "d")
            if (!d.isNullOrBlank()) {
              paths += buildPathXml(d, parser, defaultFill)
            }
          }
          "rect" -> rectToPathData(parser)?.let { paths += buildPathXml(it, parser, defaultFill) }
          "circle" -> circleToPathData(parser)?.let { paths += buildPathXml(it, parser, defaultFill) }
          "ellipse" ->
            ellipseToPathData(parser)?.let { paths += buildPathXml(it, parser, defaultFill) }
          "line" -> lineToPathData(parser)?.let { paths += buildPathXml(it, parser, defaultFill) }
          "polyline", "polygon" ->
            pointsToPathData(parser, parser.name == "polygon")?.let {
              paths += buildPathXml(it, parser, defaultFill)
            }
        }
      }
      eventType = parser.next()
    }

    if (!foundSvg) {
      throw IllegalArgumentException("Not an SVG document: missing <svg> root element")
    }
    if (paths.isEmpty()) {
      throw IllegalArgumentException("No convertible shapes found in the SVG")
    }

    val sb = StringBuilder()
    sb.append(
      "<vector xmlns:android=\"$ANDROID_NS\"\n" +
        "    android:width=\"${widthDp}dp\"\n" +
        "    android:height=\"${heightDp}dp\"\n" +
        "    android:viewportWidth=\"$viewportWidth\"\n" +
        "    android:viewportHeight=\"$viewportHeight\">\n"
    )
    paths.forEach { sb.append(it) }
    sb.append("</vector>\n")

    val vectorXml = sb.toString()
    VectorDrawableValidator.validate(vectorXml)
    return ConversionResult(vectorXml, widthDp, heightDp)
  }

  private fun buildPathXml(pathData: String, parser: XmlPullParser, defaultFill: String): String {
    val fill = parser.getAttributeValue(null, "fill") ?: defaultFill
    val fillOpacity = parser.getAttributeValue(null, "fill-opacity")?.toFloatOrNull()
    val stroke = parser.getAttributeValue(null, "stroke")
    val strokeWidth = parser.getAttributeValue(null, "stroke-width")?.toFloatOrNull()
    val strokeOpacity = parser.getAttributeValue(null, "stroke-opacity")?.toFloatOrNull()
    val opacity = parser.getAttributeValue(null, "opacity")?.toFloatOrNull()
    val fillRule = parser.getAttributeValue(null, "fill-rule")

    val sb = StringBuilder()
    sb.append("    <path\n")
    sb.append("        android:pathData=\"${escapeXml(pathData.trim())}\"\n")
    if (fill.equals("none", ignoreCase = true)) {
      sb.append("        android:fillColor=\"@android:color/transparent\"\n")
    } else {
      sb.append("        android:fillColor=\"${toAndroidColor(fill, fillOpacity ?: opacity)}\"\n")
    }
    if (fillRule.equals("evenodd", ignoreCase = true)) {
      sb.append("        android:fillType=\"evenOdd\"\n")
    }
    if (!stroke.isNullOrBlank() && !stroke.equals("none", ignoreCase = true)) {
      sb.append("        android:strokeColor=\"${toAndroidColor(stroke, strokeOpacity ?: opacity)}\"\n")
      if (strokeWidth != null && strokeWidth > 0f) {
        sb.append("        android:strokeWidth=\"$strokeWidth\"\n")
      }
    }
    sb.append(" />\n")
    return sb.toString()
  }

  private fun toAndroidColor(svgColor: String, opacity: Float?): String {
    val value = svgColor.trim()
    val base =
      when {
        value.startsWith("#") -> normalizeHex(value)
        value.startsWith("rgb(") -> parseRgbFunction(value)
        else -> NAMED_COLORS[value.lowercase()] ?: "#FF000000"
      }
    if (opacity == null || opacity >= 1f || !base.matches(Regex("#[0-9a-fA-F]{8}"))) {
      return base
    }
    val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
    return "#%02X%s".format(alpha, base.substring(1))
  }

  private fun normalizeHex(hex: String): String {
    val h = hex.substring(1)
    return when (h.length) {
      3 -> "#FF" + h.map { "$it$it" }.joinToString("").uppercase()
      4 -> "#" + h.map { "$it$it" }.joinToString("").uppercase()
      6 -> ("#FF" + h).uppercase()
      8 -> ("#" + h).uppercase()
      else -> "#FF000000"
    }
  }

  private fun parseRgbFunction(value: String): String {
    val inner = value.substringAfter("(").substringBefore(")").trim()
    val parts = inner.split(Regex("[\\s,]+"))
    if (parts.size < 3) return "#FF000000"
    fun component(part: String): Int {
      return if (part.endsWith("%")) {
        (part.dropLast(1).toFloatOrNull()?.div(100f)?.times(255f) ?: 0f).toInt()
      } else {
        part.toFloatOrNull()?.toInt() ?: 0
      }.coerceIn(0, 255)
    }
    return "#FF%02X%02X%02X".format(component(parts[0]), component(parts[1]), component(parts[2]))
  }

  private fun parseLength(value: String): Float? {
    val v = value.trim()
    return when {
      v.endsWith("px") -> v.dropLast(2).toFloatOrNull()
      v.endsWith("pt") -> v.dropLast(2).toFloatOrNull()?.times(1.333f)
      v.endsWith("pc") -> v.dropLast(2).toFloatOrNull()?.times(16f)
      v.endsWith("mm") -> v.dropLast(2).toFloatOrNull()?.times(3.7795f)
      v.endsWith("cm") -> v.dropLast(2).toFloatOrNull()?.times(37.795f)
      v.endsWith("in") -> v.dropLast(2).toFloatOrNull()?.times(96f)
      v.endsWith("%") -> null
      else -> v.toFloatOrNull()
    }
  }

  private fun rectToPathData(parser: XmlPullParser): String? {
    val x = parser.getAttributeValue(null, "x")?.toFloatOrNull() ?: 0f
    val y = parser.getAttributeValue(null, "y")?.toFloatOrNull() ?: 0f
    val w = parser.getAttributeValue(null, "width")?.toFloatOrNull() ?: return null
    val h = parser.getAttributeValue(null, "height")?.toFloatOrNull() ?: return null
    return "M$x,$y h$w v$h h${-w} Z"
  }

  private fun circleToPathData(parser: XmlPullParser): String? {
    val cx = parser.getAttributeValue(null, "cx")?.toFloatOrNull() ?: return null
    val cy = parser.getAttributeValue(null, "cy")?.toFloatOrNull() ?: return null
    val r = parser.getAttributeValue(null, "r")?.toFloatOrNull() ?: return null
    // Two arcs approximate a full circle.
    return "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0 Z"
  }

  private fun ellipseToPathData(parser: XmlPullParser): String? {
    val cx = parser.getAttributeValue(null, "cx")?.toFloatOrNull() ?: return null
    val cy = parser.getAttributeValue(null, "cy")?.toFloatOrNull() ?: return null
    val rx = parser.getAttributeValue(null, "rx")?.toFloatOrNull() ?: return null
    val ry = parser.getAttributeValue(null, "ry")?.toFloatOrNull() ?: return null
    return "M${cx - rx},$cy a$rx,$ry 0 1,0 ${2 * rx},0 a$rx,$ry 0 1,0 ${-2 * rx},0 Z"
  }

  private fun lineToPathData(parser: XmlPullParser): String? {
    val x1 = parser.getAttributeValue(null, "x1")?.toFloatOrNull() ?: return null
    val y1 = parser.getAttributeValue(null, "y1")?.toFloatOrNull() ?: return null
    val x2 = parser.getAttributeValue(null, "x2")?.toFloatOrNull() ?: return null
    val y2 = parser.getAttributeValue(null, "y2")?.toFloatOrNull() ?: return null
    return "M$x1,$y1 L$x2,$y2"
  }

  private fun pointsToPathData(parser: XmlPullParser, close: Boolean): String? {
    val points = parser.getAttributeValue(null, "points") ?: return null
    val coords = points.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
    if (coords.size < 4 || coords.size % 2 != 0) return null
    val sb = StringBuilder()
    sb.append("M${coords[0]},${coords[1]}")
    var i = 2
    while (i < coords.size) {
      sb.append(" L${coords[i]},${coords[i + 1]}")
      i += 2
    }
    if (close) sb.append(" Z")
    return sb.toString()
  }

  private fun escapeXml(text: String): String {
    return text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
  }

  private val NAMED_COLORS =
    mapOf(
      "black" to "#FF000000",
      "white" to "#FFFFFFFF",
      "red" to "#FFFF0000",
      "green" to "#FF008000",
      "lime" to "#FF00FF00",
      "blue" to "#FF0000FF",
      "yellow" to "#FFFFFF00",
      "cyan" to "#FF00FFFF",
      "aqua" to "#FF00FFFF",
      "magenta" to "#FFFF00FF",
      "fuchsia" to "#FFFF00FF",
      "gray" to "#FF808080",
      "grey" to "#FF808080",
      "silver" to "#FFC0C0C0",
      "maroon" to "#FF800000",
      "olive" to "#FF808000",
      "navy" to "#FF000080",
      "purple" to "#FF800080",
      "teal" to "#FF008080",
      "orange" to "#FFFFA500",
      "pink" to "#FFFFC0CB",
      "brown" to "#FFA52A2A",
      "transparent" to "#00000000",
    )

  /** Validates that [vectorXml] inflates as a framework `VectorDrawable`. */
  object VectorDrawableValidator {
    @Throws(IllegalArgumentException::class)
    fun validate(vectorXml: String) {
      try {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(vectorXml))
        var eventType = parser.eventType
        var sawVector = false
        while (eventType != XmlPullParser.END_DOCUMENT) {
          if (eventType == XmlPullParser.START_TAG && parser.name == "vector") {
            sawVector = true
            break
          }
          eventType = parser.next()
        }
        if (!sawVector) {
          throw IllegalArgumentException("Root element must be <vector>")
        }
        // Try a real inflate so malformed path data is rejected early.
        val drawable = android.graphics.drawable.VectorDrawable()
        drawable.inflate(
          android.content.res.Resources.getSystem(),
          parser,
          Xml.asAttributeSet(parser),
        )
      } catch (e: IllegalArgumentException) {
        throw e
      } catch (e: Exception) {
        throw IllegalArgumentException(e.message ?: "Invalid vector XML", e)
      }
    }
}
