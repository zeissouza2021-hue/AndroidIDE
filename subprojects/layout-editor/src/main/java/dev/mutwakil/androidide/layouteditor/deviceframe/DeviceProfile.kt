package dev.mutwakil.androidide.layouteditor.deviceframe

import android.content.Context
import android.content.res.Configuration
import java.io.Serializable

/** Formato físico da tela do aparelho. */
enum class ScreenShape {
  RECT,
  ROUND,
}

/**
 * Perfil de aparelho para o preview com moldura ("emulador visual").
 *
 * Não é emulação: o layout é inflado com um [Configuration]/DisplayMetrics
 * derivados deste perfil (via [wrap]), então medidas em dp, densidade,
 * orientação e uiMode se comportam como no aparelho real — incluindo a
 * resolução de qualifiers que o Android deriva dessas medidas.
 */
data class DeviceProfile(
  val id: String,
  val label: String,
  val widthDp: Int,
  val heightDp: Int,
  val densityDpi: Int,
  val orientation: Int = Configuration.ORIENTATION_PORTRAIT,
  val uiModeType: Int = Configuration.UI_MODE_TYPE_NORMAL,
  val screenShape: ScreenShape = ScreenShape.RECT,
  /** Se true, a moldura pode ser redimensionada arrastando a alça (Chromebook). */
  val resizable: Boolean = false,
  /** Dobrável: dimensões com a tela dobrada (null = não é dobrável). */
  val foldedWidthDp: Int? = null,
  val foldedHeightDp: Int? = null,
  val folded: Boolean = false,
) : Serializable {

  val isFoldable: Boolean
    get() = foldedWidthDp != null && foldedHeightDp != null

  val effectiveWidthDp: Int
    get() = if (folded) foldedWidthDp ?: widthDp else widthDp

  val effectiveHeightDp: Int
    get() = if (folded) foldedHeightDp ?: heightDp else heightDp

  val isRound: Boolean
    get() = screenShape == ScreenShape.ROUND

  fun toConfiguration(): Configuration =
    Configuration().apply {
      screenWidthDp = effectiveWidthDp
      screenHeightDp = effectiveHeightDp
      smallestScreenWidthDp = minOf(effectiveWidthDp, effectiveHeightDp)
      densityDpi = this@DeviceProfile.densityDpi
      orientation = this@DeviceProfile.orientation
      uiMode = this@DeviceProfile.uiModeType
      if (this@DeviceProfile.isRound) {
        screenLayout =
          (screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK.inv()) or
            Configuration.SCREENLAYOUT_ROUND_YES
      }
    }

  /** Context com Configuration/DisplayMetrics deste perfil. */
  fun wrap(base: Context): Context = base.createConfigurationContext(toConfiguration())
}
