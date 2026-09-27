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
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Xml
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.app.EdgeToEdgeIDEActivity
import dev.mutwakil.androidide.databinding.ActivityAssetStudioToolBinding
import dev.mutwakil.androidide.projects.IProjectManager
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * Asset Studio: import SVG/PNG artwork into the open project.
 *
 * - SVG files are converted to `VectorDrawable` XML and saved to `res/drawable/`.
 * - Pasted `<vector>` XML is validated and saved to `res/drawable/`.
 * - Bitmap images are saved as PNG to `res/drawable-nodpi/`, or used to
 *   generate an adaptive launcher icon (`mipmap-anydpi-v26/` + legacy PNGs).
 *
 * A preview is always shown before the import is confirmed.
 */
class AssetStudioActivity : EdgeToEdgeIDEActivity() {

  companion object {
    const val EXTRA_PROJECT_PATH = "asset_studio.project_path"
  }

  private var _binding: ActivityAssetStudioToolBinding? = null
  private val binding: ActivityAssetStudioToolBinding
    get() = checkNotNull(_binding) { "Activity has been destroyed" }

  private var sourceVectorXml: String? = null
  private var sourceBitmap: Bitmap? = null
  private var sourceLabel: String? = null

  private var backgroundColor: Int = Color.parseColor("#FF6200EE")
  private var foregroundScale: Float = 0.72f

  private val pickSvgLauncher =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      uri?.let { loadSvg(it) }
    }

  private val pickImageLauncher =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      uri?.let { loadImage(it) }
    }

  override fun bindLayout(): View {
    _binding = ActivityAssetStudioToolBinding.inflate(layoutInflater)
    return binding.root
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setupUi()
  }

  override fun onDestroy() {
    super.onDestroy()
    _binding = null
    sourceBitmap?.recycle()
    sourceBitmap = null
  }

  private fun setupUi() {
    binding.apply {
      assetStudioToolbar.setNavigationIcon(R.drawable.ic_arrow_back)
      assetStudioToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

      assetStudioBtnImportSvg.setOnClickListener { pickSvgLauncher.launch("*/*") }
      assetStudioBtnImportImage.setOnClickListener { pickImageLauncher.launch("image/*") }
      assetStudioBtnPasteXml.setOnClickListener { showPasteXmlDialog() }

      assetStudioOutputGroup.setOnCheckedChangeListener { _, checkedId ->
        assetStudioLauncherOptions.visibility =
          if (checkedId == R.id.asset_studio_output_launcher) View.VISIBLE else View.GONE
      }

      assetStudioBgColorHex.addTextChangedListener(
        object : TextWatcher {
          override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

          override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

          override fun afterTextChanged(s: Editable?) {
            try {
              val color = Color.parseColor(s.toString().trim())
              backgroundColor = color
              assetStudioBgColorPreview.setBackgroundColor(color)
            } catch (_: Exception) {
              // Invalid hex while typing; keep the last valid color.
            }
          }
        }
      )

      assetStudioFgScaleSlider.addOnChangeListener { _, value, _ ->
        foregroundScale = value / 100f
      }

      assetStudioBtnImport.setOnClickListener { doImport() }
    }
  }

  // ---------------------------------------------------------------------------
  // Source loading
  // ---------------------------------------------------------------------------

  private fun loadSvg(uri: Uri) {
    setStatus(getString(R.string.asset_studio_wait_loading))
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val text =
          contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("Empty file")
        if (!text.contains("<svg", ignoreCase = true)) {
          throw IllegalArgumentException("File does not look like SVG")
        }
        val result = SvgToVectorDrawableConverter.convert(text)
        withContext(Dispatchers.Main) {
          sourceVectorXml = result.vectorXml
          sourceBitmap?.recycle()
          sourceBitmap = null
          sourceLabel = uri.lastPathSegment ?: "svg"
          suggestName(sourceLabel)
          updatePreview()
          setStatus(getString(R.string.asset_studio_source_loaded, sourceLabel ?: "?"))
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          setStatus(getString(R.string.asset_studio_svg_parse_error, e.message ?: "?"))
          toast(getString(R.string.asset_studio_svg_parse_error, e.message ?: "?"))
        }
      }
    }
  }

  private fun loadImage(uri: Uri) {
    setStatus(getString(R.string.asset_studio_wait_loading))
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val bitmap = decodeSampledBitmap(uri, 2048) ?: throw IllegalArgumentException("Decode failed")
        withContext(Dispatchers.Main) {
          sourceBitmap?.recycle()
          sourceBitmap = bitmap
          sourceVectorXml = null
          sourceLabel = uri.lastPathSegment ?: "image"
          suggestName(sourceLabel)
          updatePreview()
          setStatus(getString(R.string.asset_studio_source_loaded, sourceLabel ?: "?"))
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          setStatus(getString(R.string.asset_studio_import_failed, e.message ?: "?"))
          toast(getString(R.string.asset_studio_import_failed, e.message ?: "?"))
        }
      }
    }
  }

  private fun decodeSampledBitmap(uri: Uri, maxSize: Int): Bitmap? {
    val boundsOpts =
      BitmapFactory.Options().apply {
        inJustDecodeBounds = true
      }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
    var sample = 1
    while (boundsOpts.outWidth / sample > maxSize || boundsOpts.outHeight / sample > maxSize) {
      sample *= 2
    }
    val decodeOpts =
      BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
    return contentResolver.openInputStream(uri)?.use {
      BitmapFactory.decodeStream(it, null, decodeOpts)
    }
  }

  private fun showPasteXmlDialog() {
    val input =
      EditText(this).apply {
        hint = "<vector …>"
        minLines = 8
        setPadding(48, 32, 48, 32)
      }
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.asset_studio_paste_xml_title)
      .setView(input)
      .setPositiveButton(android.R.string.ok) { dialog, _ ->
        val xml = input.text?.toString()?.trim().orEmpty()
        if (xml.isNotEmpty()) {
          try {
            SvgToVectorDrawableConverter.VectorDrawableValidator.validate(xml)
            sourceVectorXml = xml
            sourceBitmap?.recycle()
            sourceBitmap = null
            sourceLabel = "pasted XML"
            updatePreview()
            setStatus(getString(R.string.asset_studio_source_loaded, sourceLabel ?: "?"))
          } catch (e: Exception) {
            toast(getString(R.string.asset_studio_vector_invalid, e.message ?: "?"))
          }
        }
        dialog.dismiss()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  // ---------------------------------------------------------------------------
  // Preview
  // ---------------------------------------------------------------------------

  private fun updatePreview() {
    val xml = sourceVectorXml
    when {
      xml != null -> {
        val drawable = inflateVectorDrawable(xml)
        if (drawable != null) {
          binding.assetStudioPreview.setImageDrawable(drawable)
        } else {
          toast(getString(R.string.asset_studio_vector_invalid, "?"))
        }
      }
      sourceBitmap != null -> binding.assetStudioPreview.setImageBitmap(sourceBitmap)
      else -> binding.assetStudioPreview.setImageDrawable(null)
    }
  }

  private fun inflateVectorDrawable(xml: String): Drawable? {
    return try {
      val parser: XmlPullParser = Xml.newPullParser()
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
      parser.setInput(StringReader(xml))
      var eventType = parser.eventType
      while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) {
        eventType = parser.next()
      }
      val drawable = VectorDrawable()
      drawable.inflate(resources, parser, Xml.asAttributeSet(parser))
      drawable
    } catch (_: Exception) {
      null
    }
  }

  // ---------------------------------------------------------------------------
  // Import
  // ---------------------------------------------------------------------------

  private fun sanitizedName(): String? {
    val raw = binding.assetStudioNameInput.text?.toString()?.trim().orEmpty()
    if (raw.isEmpty()) return null
    var name = raw.lowercase().replace(Regex("[^a-z0-9_]"), "_")
    if (name.isEmpty() || !name[0].isLetter()) name = "ic_$name"
    if (!name.matches(Regex("[a-z][a-z0-9_]*"))) return null
    return name
  }

  private fun suggestName(label: String?) {
    val base =
      label?.substringAfterLast('/')?.substringBeforeLast('.')?.lowercase()
        ?.replace(Regex("[^a-z0-9_]"), "_")
        ?.trim('_')
    val current = binding.assetStudioNameInput.text?.toString().orEmpty()
    if (current.isBlank() && !base.isNullOrBlank()) {
      val suggested = if (base[0].isLetter()) base else "ic_$base"
      binding.assetStudioNameInput.setText(suggested)
    }
  }

  private fun resolveResDir(): File? {
    return try {
      val manager = IProjectManager.getInstance()
      val appModule = manager.getAndroidAppModules().firstOrNull()
      val fromModel = appModule?.getResourceDirectories()?.firstOrNull { it.isDirectory }
      if (fromModel != null) return fromModel
      val projectDir = manager.projectDirPath ?: return null
      val fallback = File(projectDir, "app/src/main/res")
      if (fallback.isDirectory) fallback else null
    } catch (_: Exception) {
      null
    }
  }

  private fun selectedOutput(): Output {
    return when (binding.assetStudioOutputGroup.checkedRadioButtonId) {
      R.id.asset_studio_output_png -> Output.PNG
      R.id.asset_studio_output_launcher -> Output.LAUNCHER
      else -> Output.VECTOR
    }
  }

  private fun doImport() {
    val name =
      sanitizedName()
        ?: run {
          toast(getString(R.string.asset_studio_name_invalid))
          return
        }
    val resDir =
      resolveResDir()
        ?: run {
          toast(getString(R.string.asset_studio_no_res_dir))
          return
        }

    val output = selectedOutput()
    if (output == Output.VECTOR && sourceVectorXml == null && sourceBitmap != null) {
      toast(getString(R.string.asset_studio_png_cannot_be_vector))
      return
    }
    if (sourceVectorXml == null && sourceBitmap == null) {
      toast(getString(R.string.asset_studio_no_source))
      return
    }

    setStatus(getString(R.string.asset_studio_wait_loading))
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val (written, overwritten) =
          when (output) {
            Output.VECTOR -> importVectorDrawable(name, resDir)
            Output.PNG -> importPngDrawable(name, resDir)
            Output.LAUNCHER -> importLauncherIcon(name, resDir) to false
          }
        withContext(Dispatchers.Main) {
          if (overwritten) {
            toast(getString(R.string.asset_studio_file_exists, written.substringAfterLast('/')))
          }
          val msg = getString(R.string.asset_studio_imported, written)
          setStatus(msg)
          toast(msg)
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          val msg = getString(R.string.asset_studio_import_failed, e.message ?: "?")
          setStatus(msg)
          toast(msg)
        }
      }
    }
  }

  private fun importVectorDrawable(name: String, resDir: File): Pair<String, Boolean> {
    val xml = sourceVectorXml ?: throw IllegalStateException(getString(R.string.asset_studio_no_source))
    val dir = File(resDir, "drawable").apply { mkdirs() }
    val file = File(dir, "$name.xml")
    val existed = file.exists()
    file.writeText(xml)
    return file.relativeTo(resDir).path to existed
  }

  private fun importPngDrawable(name: String, resDir: File): Pair<String, Boolean> {
    val bitmap = bitmapForRasterOutput() ?: throw IllegalStateException(getString(R.string.asset_studio_no_source))
    val dir = File(resDir, "drawable-nodpi").apply { mkdirs() }
    val file = File(dir, "$name.png")
    val existed = file.exists()
    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    return file.relativeTo(resDir).path to existed
  }

  private fun importLauncherIcon(name: String, resDir: File): String {
    val bitmap = bitmapForRasterOutput() ?: throw IllegalStateException(getString(R.string.asset_studio_no_source))
    val result = AdaptiveIconGenerator.generate(bitmap, name, backgroundColor, foregroundScale, resDir)
    return result.files.joinToString(", ") { it.relativeTo(resDir).path }
  }

  /**
   * Returns a bitmap for raster outputs: the imported bitmap, or the vector
   * source rendered at [AdaptiveIconGenerator.FOREGROUND_PX] px.
   */
  private fun bitmapForRasterOutput(): Bitmap? {
    sourceBitmap?.let {
      return if (it.isRecycled) null else it
    }
    val xml = sourceVectorXml ?: return null
    // Inflate on the calling (background) thread using application resources.
    val drawable =
      try {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var eventType = parser.eventType
        while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) {
          eventType = parser.next()
        }
        VectorDrawable().apply { inflate(resources, parser, Xml.asAttributeSet(parser)) }
      } catch (_: Exception) {
        return null
      }
    return AdaptiveIconGenerator.renderDrawable(drawable, AdaptiveIconGenerator.FOREGROUND_PX)
  }

  private enum class Output {
    VECTOR,
    PNG,
    LAUNCHER,
  }

  private fun setStatus(text: String) {
    binding.assetStudioStatus.text = text
  }

  private fun toast(text: String) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
  }
}
