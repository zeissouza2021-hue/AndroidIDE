package dev.mutwakil.androidide.compose.preview.deviceframe

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.mutwakil.androidide.compose.preview.R

/** Diálogos do seletor de perfis de aparelho do preview. */
object DeviceFrameDialogs {

  /**
   * Seletor de perfil. [noneLabel] != null adiciona a opção "sem moldura"
   * (retorna null em [onSelect]).
   */
  fun showSelector(
    activity: AppCompatActivity,
    profiles: List<DeviceProfile>,
    current: DeviceProfile?,
    noneLabel: String? = null,
    onSelect: (DeviceProfile?) -> Unit,
  ) {
    val labels = (if (noneLabel != null) listOf(noneLabel) else emptyList()) +
      profiles.map { it.label }
    val checked =
      if (current == null && noneLabel != null) 0
      else (if (noneLabel != null) 1 else 0) +
        profiles.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)

    MaterialAlertDialogBuilder(activity)
      .setTitle(R.string.device_frame_title)
      .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
        dialog.dismiss()
        val offset = if (noneLabel != null) 1 else 0
        if (noneLabel != null && which == 0) {
          onSelect(null)
          return@setSingleChoiceItems
        }
        val selected = profiles[which - offset]
        if (selected.id == DeviceProfiles.ID_CUSTOM) {
          showCustom(activity, onSelect)
        } else {
          onSelect(selected)
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /** Diálogo do perfil personalizado (largura/altura/densidade digitáveis). */
  fun showCustom(
    activity: AppCompatActivity,
    onCreate: (DeviceProfile?) -> Unit,
  ) {
    val density = activity.resources.displayMetrics.density
    val pad = (16 * density).toInt()
    val container =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
      }

    fun numberField(hintRes: Int, default: String): EditText =
      EditText(activity).apply {
        hint = activity.getString(hintRes)
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(default)
      }.also { container.addView(it) }

    val widthField = numberField(R.string.device_frame_width_dp, "411")
    val heightField = numberField(R.string.device_frame_height_dp, "891")
    val densityField = numberField(R.string.device_frame_density_dpi, "420")

    val orientationGroup = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
    val portraitButton =
      RadioButton(activity).apply {
        text = activity.getString(R.string.device_frame_portrait)
        isChecked = true
      }
    val landscapeButton =
      RadioButton(activity).apply { text = activity.getString(R.string.device_frame_landscape) }
    orientationGroup.addView(portraitButton)
    orientationGroup.addView(landscapeButton)
    container.addView(orientationGroup)

    val dialog =
      MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.device_frame_custom)
        .setView(container)
        .setPositiveButton(R.string.device_frame_create, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
      val w = widthField.text.toString().toIntOrNull()
      val h = heightField.text.toString().toIntOrNull()
      val d = densityField.text.toString().toIntOrNull()
      if (w == null || h == null || d == null || w <= 0 || h <= 0 || d <= 0) {
        widthField.error = activity.getString(R.string.device_frame_invalid_dimensions)
        return@setOnClickListener
      }
      dialog.dismiss()
      onCreate(
        DeviceProfiles.custom(
          label = activity.getString(R.string.device_frame_custom_label, w, h, d),
          widthDp = w,
          heightDp = h,
          densityDpi = d,
          landscape = landscapeButton.isChecked,
        )
      )
    }
  }
}
