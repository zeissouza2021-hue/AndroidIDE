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

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textview.MaterialTextView
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.app.EdgeToEdgeIDEActivity
import dev.mutwakil.androidide.databinding.ActivityApkAnalyzerBinding
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * APK Analyzer: opens an APK (zip), shows a size breakdown by category,
 * the decoded `AndroidManifest.xml`, the list of DEX classes, a diff
 * against a second APK and simple size-reduction tips.
 *
 * Everything is parsed on-device with the self-contained parsers in this
 * package ([ApkParser], `axml.AXmlParser`, `dex.DexParser`); no AAPT needed.
 */
class ApkAnalyzerActivity : EdgeToEdgeIDEActivity() {

  private var _binding: ActivityApkAnalyzerBinding? = null
  private val binding: ActivityApkAnalyzerBinding
    get() = checkNotNull(_binding) { "Activity has been destroyed" }

  private var currentReport: ApkReport? = null
  private var compareReport: ApkReport? = null
  private var classDisplayNames: List<String> = emptyList()

  private val pickApkLauncher =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      uri?.let { loadApk(it, forCompare = false) }
    }

  private val pickCompareLauncher =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      uri?.let { loadApk(it, forCompare = true) }
    }

  override fun bindLayout(): View {
    _binding = ActivityApkAnalyzerBinding.inflate(layoutInflater)
    return binding.root
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setupUi()
  }

  override fun onDestroy() {
    super.onDestroy()
    _binding = null
  }

  private fun setupUi() {
    binding.apply {
      apkAnalyzerToolbar.setNavigationIcon(R.drawable.ic_arrow_back)
      apkAnalyzerToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

      apkAnalyzerBtnPick.setOnClickListener { pickApkLauncher.launch("*/*") }
      apkAnalyzerBtnCompare.setOnClickListener {
        if (currentReport == null) {
          toast(getString(R.string.apk_analyzer_no_apk))
        } else {
          pickCompareLauncher.launch("*/*")
        }
      }

      apkAnalyzerTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
        if (isChecked) showSection(checkedId)
      }

      apkAnalyzerClassSearch.addTextChangedListener(
        object : TextWatcher {
          override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

          override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

          override fun afterTextChanged(s: Editable?) {
            renderClassList(s?.toString().orEmpty())
          }
        }
      )
    }
  }

  private fun showSection(checkedId: Int) {
    binding.apply {
      apkAnalyzerSectionOverview.visibility =
        if (checkedId == R.id.apk_analyzer_tab_overview) View.VISIBLE else View.GONE
      apkAnalyzerSectionManifest.visibility =
        if (checkedId == R.id.apk_analyzer_tab_manifest) View.VISIBLE else View.GONE
      apkAnalyzerSectionClasses.visibility =
        if (checkedId == R.id.apk_analyzer_tab_classes) View.VISIBLE else View.GONE
      apkAnalyzerSectionCompare.visibility =
        if (checkedId == R.id.apk_analyzer_tab_compare) View.VISIBLE else View.GONE
      apkAnalyzerSectionTips.visibility =
        if (checkedId == R.id.apk_analyzer_tab_tips) View.VISIBLE else View.GONE
    }
  }

  // ---------------------------------------------------------------------------
  // Loading
  // ---------------------------------------------------------------------------

  private fun loadApk(uri: Uri, forCompare: Boolean) {
    binding.apkAnalyzerFileInfo.text = getString(R.string.apk_analyzer_analyzing)
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val target =
          File(cacheDir, if (forCompare) "apk_analyzer_compare.apk" else "apk_analyzer_main.apk")
        contentResolver.openInputStream(uri)?.use { input ->
          target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Cannot read file")
        val report = ApkParser.parse(target)
        withContext(Dispatchers.Main) {
          if (forCompare) {
            compareReport = report
            renderCompare()
            binding.apkAnalyzerTabs.check(R.id.apk_analyzer_tab_compare)
          } else {
            currentReport = report
            compareReport = null
            renderReport(report)
            binding.apkAnalyzerTabs.check(R.id.apk_analyzer_tab_overview)
          }
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          binding.apkAnalyzerFileInfo.text = getString(R.string.apk_analyzer_error, e.message ?: "?")
          toast(getString(R.string.apk_analyzer_error, e.message ?: "?"))
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------

  private fun renderReport(report: ApkReport) {
    binding.apply {
      apkAnalyzerFileInfo.text =
        "${report.fileName} • ${formatBytes(report.totalSize)} • ${report.fileCount} ${getString(R.string.apk_analyzer_files)}"

      apkAnalyzerOverviewText.text =
        "${getString(R.string.apk_analyzer_total_size)}: ${formatBytes(report.totalSize)}\n" +
          "${getString(R.string.apk_analyzer_download_size)}: ${formatBytes(report.downloadSizeEstimate)}"

      apkAnalyzerBreakdownContainer.removeAllViews()
      for (category in ApkCategory.values()) {
        val size = report.sizeByCategory(category)
        if (size > 0) {
          apkAnalyzerBreakdownContainer.addView(
            breakdownRow(ApkDiff.categoryLabel(this@ApkAnalyzerActivity, category), size, report.totalSize))
        }
      }

      val manifest = report.manifestXml
      apkAnalyzerManifestText.text =
        manifest ?: getString(R.string.apk_analyzer_manifest_unavailable, report.manifestError ?: "?")

      val dexSummary = StringBuilder()
      dexSummary.append("${getString(R.string.apk_analyzer_dex_files)}: ${report.dexInfos.size}")
      if (report.totalClasses > 0) {
        dexSummary.append(" (${getString(R.string.apk_analyzer_classes_count, report.totalClasses)})")
      }
      apkAnalyzerDexSummary.text = dexSummary.toString()

      classDisplayNames =
        report.dexInfos.flatMap { info ->
          listOf("── ${info.entryName} (${info.classCount}) ──") + info.classNames
        }
      apkAnalyzerClassSearch.setText("")
      renderClassList("")

      apkAnalyzerCompareText.text = getString(R.string.apk_analyzer_compare_hint)
      apkAnalyzerTipsText.text = ApkTips.format(this@ApkAnalyzerActivity, ApkTips.analyze(this@ApkAnalyzerActivity, report))
    }
  }

  private fun renderClassList(query: String) {
    val q = query.trim()
    val filtered =
      if (q.isEmpty()) classDisplayNames
      else classDisplayNames.filter { it.startsWith("──") || it.contains(q, ignoreCase = true) }
    val totalClasses = classDisplayNames.count { !it.startsWith("──") }
    val shown = filtered.take(MAX_CLASSES_SHOWN)
    val header =
      if (q.isEmpty()) {
        ""
      } else {
        getString(R.string.apk_analyzer_showing_classes, shown.count { !it.startsWith("──") }, totalClasses) + "\n"
      }
    binding.apkAnalyzerClassesText.text = header + shown.joinToString("\n") +
      if (filtered.size > MAX_CLASSES_SHOWN) "\n… (${filtered.size - MAX_CLASSES_SHOWN} more)" else ""
  }

  private fun renderCompare() {
    val old = currentReport
    val new = compareReport
    if (old == null || new == null) return
    binding.apkAnalyzerCompareText.text = ApkDiff.format(this, ApkDiff.diff(old, new))
  }

  private fun breakdownRow(label: String, size: Long, total: Long): View {
    val context = this
    val row =
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams =
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
          ).apply { topMargin = dp(6) }
      }
    val labelView =
      MaterialTextView(context).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
      }
    val bar =
      ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progress = if (total > 0) ((size * 1000) / total).toInt().coerceIn(0, 1000) else 0
        layoutParams =
          LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
          }
      }
    val sizeView =
      MaterialTextView(context).apply {
        text = formatBytes(size)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
      }
    row.addView(labelView)
    row.addView(bar)
    row.addView(sizeView)
    return row
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  private fun toast(text: String) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
  }

  companion object {
    private const val MAX_CLASSES_SHOWN = 400
  }
}
