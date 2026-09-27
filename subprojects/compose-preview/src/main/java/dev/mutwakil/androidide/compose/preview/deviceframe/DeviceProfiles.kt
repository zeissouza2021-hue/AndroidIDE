package dev.mutwakil.androidide.compose.preview.deviceframe

import android.content.Context
import android.content.res.Configuration
import dev.mutwakil.androidide.compose.preview.R

/** Catálogo de perfis de aparelho usados pelo preview com moldura. */
object DeviceProfiles {

  const val ID_PIXEL = "device_frame_pixel9"
  const val ID_GALAXY = "device_frame_galaxy_s24"
  const val ID_FOLD = "device_frame_fold6"
  const val ID_TABLET = "device_frame_tablet"
  const val ID_WATCH = "device_frame_watch"
  const val ID_TV = "device_frame_tv"
  const val ID_CHROMEBOOK = "device_frame_chromebook"
  const val ID_CUSTOM = "device_frame_custom"

  /**
   * Perfis padrão, na ordem do seletor: celulares, dobrável (com chave
   * dobrado/aberto), tablet, relógio (tela redonda), TV e Chromebook
   * (moldura redimensionável), além do perfil personalizado.
   */
  fun defaults(context: Context): List<DeviceProfile> =
    listOf(
      DeviceProfile(
        id = ID_PIXEL,
        label = context.getString(R.string.device_frame_pixel9),
        widthDp = 411,
        heightDp = 923,
        densityDpi = 420,
      ),
      DeviceProfile(
        id = ID_GALAXY,
        label = context.getString(R.string.device_frame_galaxy_s24),
        widthDp = 411,
        heightDp = 891,
        densityDpi = 420,
      ),
      DeviceProfile(
        id = ID_FOLD,
        label = context.getString(R.string.device_frame_fold6),
        widthDp = 690,
        heightDp = 829,
        densityDpi = 420,
        foldedWidthDp = 344,
        foldedHeightDp = 882,
        folded = false,
      ),
      DeviceProfile(
        id = ID_TABLET,
        label = context.getString(R.string.device_frame_tablet),
        widthDp = 800,
        heightDp = 1333,
        densityDpi = 240,
        orientation = Configuration.ORIENTATION_LANDSCAPE,
      ),
      DeviceProfile(
        id = ID_WATCH,
        label = context.getString(R.string.device_frame_watch),
        widthDp = 227,
        heightDp = 227,
        densityDpi = 320,
        uiModeType = Configuration.UI_MODE_TYPE_WATCH,
        screenShape = ScreenShape.ROUND,
      ),
      DeviceProfile(
        id = ID_TV,
        label = context.getString(R.string.device_frame_tv),
        widthDp = 960,
        heightDp = 540,
        densityDpi = 320,
        orientation = Configuration.ORIENTATION_LANDSCAPE,
        uiModeType = Configuration.UI_MODE_TYPE_TELEVISION,
      ),
      DeviceProfile(
        id = ID_CHROMEBOOK,
        label = context.getString(R.string.device_frame_chromebook),
        widthDp = 1280,
        heightDp = 800,
        densityDpi = 160,
        orientation = Configuration.ORIENTATION_LANDSCAPE,
        resizable = true,
      ),
      DeviceProfile(
        id = ID_CUSTOM,
        label = context.getString(R.string.device_frame_custom),
        widthDp = 411,
        heightDp = 891,
        densityDpi = 420,
      ),
    )

  /** Perfis usados pelo multi-preview lado a lado. */
  fun multiPreviewDefaults(context: Context): List<DeviceProfile> =
    defaults(context).filter { it.id == ID_PIXEL || it.id == ID_TABLET || it.id == ID_FOLD }

  /** Perfil personalizado com largura/altura/densidade digitadas pelo usuário. */
  fun custom(
    label: String,
    widthDp: Int,
    heightDp: Int,
    densityDpi: Int,
    landscape: Boolean,
  ): DeviceProfile =
    DeviceProfile(
      id = ID_CUSTOM,
      label = label,
      widthDp = widthDp,
      heightDp = heightDp,
      densityDpi = densityDpi,
      orientation =
        if (landscape) Configuration.ORIENTATION_LANDSCAPE
        else Configuration.ORIENTATION_PORTRAIT,
    )
}
