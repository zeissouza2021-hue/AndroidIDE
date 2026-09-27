package dev.mutwakil.androidide.layouteditor.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import dev.mutwakil.androidide.layouteditor.BaseActivity
import dev.mutwakil.androidide.layouteditor.LayoutFile
import dev.mutwakil.androidide.layouteditor.R
import dev.mutwakil.androidide.resources.R.string
import dev.mutwakil.androidide.layouteditor.databinding.ActivityPreviewLayoutBinding
import dev.mutwakil.androidide.layouteditor.deviceframe.DeviceFrameDialogs
import dev.mutwakil.androidide.layouteditor.deviceframe.DeviceFrameView
import dev.mutwakil.androidide.layouteditor.deviceframe.DeviceProfile
import dev.mutwakil.androidide.layouteditor.deviceframe.DeviceProfiles
import dev.mutwakil.androidide.layouteditor.tools.ValidationResult
import dev.mutwakil.androidide.layouteditor.tools.XmlLayoutParser
import dev.mutwakil.androidide.layouteditor.utils.Constants
import dev.mutwakil.androidide.ui.themes.IThemeManager

class PreviewLayoutActivity : BaseActivity() {

  private lateinit var binding: ActivityPreviewLayoutBinding

  private var layoutFile: LayoutFile? = null
  private var basePath: String? = null
  private var designXml: String? = null

  private var profiles: List<DeviceProfile> = emptyList()
  private var currentProfile: DeviceProfile? = null
  private var multiMode = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityPreviewLayoutBinding.inflate(layoutInflater)
    setContentView(binding.root)
    IThemeManager.getInstance().applyTheme(this)

    setSupportActionBar(binding.toolbar)
    binding.toolbar.setNavigationOnClickListener { finish() }

    @Suppress("DEPRECATION")
    layoutFile = intent.extras?.getParcelable(Constants.EXTRA_KEY_LAYOUT)
    basePath = layoutFile?.path?.let { java.io.File(it).parent }
    designXml = layoutFile?.readDesignFile()

    profiles = DeviceProfiles.defaults(this)
    currentProfile =
      savedInstanceState?.getSerializable(KEY_PROFILE) as? DeviceProfile
        ?: profiles.firstOrNull()
    multiMode = savedInstanceState?.getBoolean(KEY_MULTI) ?: false

    render()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putSerializable(KEY_PROFILE, currentProfile)
    outState.putBoolean(KEY_MULTI, multiMode)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_device_frame, menu)
    return true
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    val foldable = !multiMode && currentProfile?.isFoldable == true
    menu.findItem(R.id.device_frame_fold)?.isVisible = foldable
    menu.findItem(R.id.device_frame_fold)?.isChecked = currentProfile?.folded == true
    menu.findItem(R.id.device_frame_multi)?.isChecked = multiMode
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean =
    when (item.itemId) {
      R.id.device_frame_select -> {
        val current = currentProfile ?: return false
        DeviceFrameDialogs.showSelector(this, profiles, current) { selected ->
          // null = perfil personalizado cancelado; mantém o atual.
          if (selected != null) {
            currentProfile = selected
            multiMode = false
            render()
          }
        }
        true
      }
      R.id.device_frame_fold -> {
        val profile = currentProfile?.takeIf { it.isFoldable } ?: return false
        currentProfile = profile.copy(folded = !profile.folded)
        multiMode = false
        render()
        true
      }
      R.id.device_frame_multi -> {
        multiMode = !multiMode
        render()
        true
      }
      else -> super.onOptionsItemSelected(item)
    }

  private fun render() {
    if (multiMode) {
      renderMulti()
    } else {
      renderSingle()
    }
    invalidateOptionsMenu()
  }

  /**
   * Infla o layout com o [Configuration]/DisplayMetrics do perfil, para que
   * dp, densidade, orientação e uiMode se comportem como no aparelho real.
   */
  private fun inflateFor(profile: DeviceProfile): View? {
    val xml = designXml ?: return null
    val context = profile.wrap(this)
    val parser = XmlLayoutParser(context, basePath)
    return when (parser.processXml(xml, context)) {
      is ValidationResult.Success ->
        parser.root?.also { (it.parent as? ViewGroup)?.removeView(it) }
      is ValidationResult.Error -> null
    }
  }

  private fun renderSingle() {
    binding.multiScroll.isVisible = false
    binding.deviceFrameView.isVisible = true
    val profile = currentProfile ?: return showErrorDialog()
    val root = inflateFor(profile) ?: return showErrorDialog()
    binding.deviceFrameView.setProfile(profile, root)
    binding.deviceFrameView.onResizeFinished = { resized ->
      // O perfil redimensionado vira um "personalizado" da sessão.
      currentProfile =
        resized.copy(
          id = DeviceProfiles.ID_CUSTOM,
          label =
            getString(
              R.string.device_frame_custom_label,
              resized.widthDp,
              resized.heightDp,
              resized.densityDpi,
            ),
        )
      renderSingle()
      invalidateOptionsMenu()
    }
    binding.toolbar.title = getString(R.string.device_frame_preview_title)
    binding.toolbar.subtitle = subtitleFor(profile)
  }

  private fun renderMulti() {
    binding.deviceFrameView.isVisible = false
    binding.multiScroll.isVisible = true
    binding.multiContainer.removeAllViews()
    binding.toolbar.title = getString(R.string.device_frame_preview_title)
    binding.toolbar.subtitle = getString(R.string.device_frame_multi)

    val density = resources.displayMetrics.density
    val frameWidth = (380 * density).toInt()
    val margin = (12 * density).toInt()
    for (profile in DeviceProfiles.multiPreviewDefaults(this)) {
      val root = inflateFor(profile) ?: continue
      val frame = DeviceFrameView(this)
      frame.layoutParams =
        LinearLayout.LayoutParams(frameWidth, ViewGroup.LayoutParams.MATCH_PARENT)
          .apply { setMargins(margin, margin, margin, margin) }
      frame.contentDescription = profile.label
      frame.setProfile(profile, root)
      binding.multiContainer.addView(frame)
    }
    if (binding.multiContainer.childCount == 0) {
      showErrorDialog()
    }
  }

  private fun subtitleFor(profile: DeviceProfile): String {
    val dims =
      "${profile.label} · ${profile.effectiveWidthDp}×${profile.effectiveHeightDp} dp" +
        " @ ${profile.densityDpi}dpi"
    return if (profile.folded) {
      "$dims · ${getString(R.string.device_frame_folded)}"
    } else {
      dims
    }
  }

  private fun showErrorDialog() {
    AlertDialog.Builder(this)
      .setTitle(getString(string.preview_render_error_title))
      .setMessage(getString(string.preview_render_error_message))
      .setPositiveButton(getString(string.msg_ok)) { dialog, _ ->
        dialog.dismiss()
        finish()
      }
      .setCancelable(false)
      .show()
  }

  companion object {
    private const val KEY_PROFILE = "device_frame_profile"
    private const val KEY_MULTI = "device_frame_multi"
  }
}
