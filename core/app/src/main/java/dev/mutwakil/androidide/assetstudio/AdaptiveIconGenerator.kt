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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream

/**
 * Generates an adaptive launcher icon from a source [Bitmap].
 *
 * Writes, under the module `res/` directory:
 * - `mipmap-anydpi-v26/<name>.xml` — the `<adaptive-icon>` definition
 * - `mipmap-xxxhdpi/<name>_foreground.png` — the foreground artwork (432x432)
 * - `drawable/<name>_background.xml` — a solid color background drawable
 * - `mipmap-<density>/<name>.png` — legacy fallback icons (mdpi..xxxhdpi)
 *
 * The foreground artwork is centered and scaled to [foregroundScale] of the
 * layer size, following the adaptive-icon safe-zone guidance (keep artwork
 * inside the center 72dp of the 108dp layer).
 */
object AdaptiveIconGenerator {

  private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

  /** Foreground layer artwork size in px (xxxhdpi, 108dp * 4). */
  const val FOREGROUND_PX = 432

  private val LEGACY_DENSITIES = listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144)

  data class GeneratedIcon(val files: List<File>)

  @Throws(IllegalArgumentException::class)
  fun generate(
    source: Bitmap,
    name: String,
    backgroundColor: Int,
    foregroundScale: Float,
    resDir: File,
  ): GeneratedIcon {
    require(name.matches(Regex("[a-z][a-z0-9_]*"))) { "Invalid asset name: $name" }
    val scale = foregroundScale.coerceIn(0.1f, 1f)
    val written = mutableListOf<File>()

    // 1. Foreground artwork: artwork centered on a transparent 432x432 canvas.
    val foreground = renderForeground(source, scale)
    val xxxhdpiDir = File(resDir, "mipmap-xxxhdpi").apply { mkdirs() }
    val foregroundFile = File(xxxhdpiDir, "${name}_foreground.png")
    FileOutputStream(foregroundFile).use { out ->
      foreground.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    written += foregroundFile

    // 2. Background: solid color drawable (avoids touching values/ files).
    val drawableDir = File(resDir, "drawable").apply { mkdirs() }
    val backgroundFile = File(drawableDir, "${name}_background.xml")
    backgroundFile.writeText(
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<shape xmlns:android=\"$ANDROID_NS\" android:shape=\"rectangle\">\n" +
        "    <solid android:color=\"${colorToHex(backgroundColor)}\" />\n" +
        "</shape>\n"
    )
    written += backgroundFile

    // 3. Adaptive icon definition.
    val anydpiDir = File(resDir, "mipmap-anydpi-v26").apply { mkdirs() }
    val adaptiveFile = File(anydpiDir, "$name.xml")
    adaptiveFile.writeText(
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<adaptive-icon xmlns:android=\"$ANDROID_NS\">\n" +
        "    <background android:drawable=\"@drawable/${name}_background\" />\n" +
        "    <foreground android:drawable=\"@mipmap/${name}_foreground\" />\n" +
        "</adaptive-icon>\n"
    )
    written += adaptiveFile

    // 4. Legacy fallback PNGs (full-bleed artwork on the background color).
    for ((density, size) in LEGACY_DENSITIES) {
      val dir = File(resDir, "mipmap-$density").apply { mkdirs() }
      val legacy = renderLegacy(source, backgroundColor, scale, size)
      val file = File(dir, "$name.png")
      FileOutputStream(file).use { out ->
        legacy.compress(Bitmap.CompressFormat.PNG, 100, out)
      }
      written += file
      if (!legacy.isRecycled) legacy.recycle()
    }

    if (!foreground.isRecycled) foreground.recycle()
    return GeneratedIcon(written)
  }

  /** Renders [source] centered on a transparent [FOREGROUND_PX] canvas. */
  fun renderForeground(source: Bitmap, foregroundScale: Float): Bitmap {
    val size = FOREGROUND_PX
    val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val target = (size * foregroundScale.coerceIn(0.1f, 1f)).toInt()
    val scaled = Bitmap.createScaledBitmap(source, target, target, true)
    val left = (size - target) / 2f
    val top = (size - target) / 2f
    canvas.drawBitmap(scaled, left, top, Paint(Paint.ANTI_ALIAS_FLAG))
    if (scaled != source) scaled.recycle()
    return result
  }

  private fun renderLegacy(
    source: Bitmap,
    backgroundColor: Int,
    foregroundScale: Float,
    size: Int,
  ): Bitmap {
    val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    canvas.drawColor(backgroundColor)
    val target = (size * foregroundScale.coerceIn(0.1f, 1f)).toInt()
    val scaled = Bitmap.createScaledBitmap(source, target, target, true)
    val left = (size - target) / 2f
    val top = (size - target) / 2f
    canvas.drawBitmap(scaled, left, top, Paint(Paint.ANTI_ALIAS_FLAG))
    if (scaled != source) scaled.recycle()
    return result
  }

  private fun colorToHex(color: Int): String {
    return "#%08X".format(color)
  }

  /** Draws a [android.graphics.drawable.Drawable] onto a bitmap of [size] px. */
  fun renderDrawable(drawable: android.graphics.drawable.Drawable, size: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bitmap
  }
}
