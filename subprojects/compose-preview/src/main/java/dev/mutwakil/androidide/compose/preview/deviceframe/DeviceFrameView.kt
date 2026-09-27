package dev.mutwakil.androidide.compose.preview.deviceframe

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.color.MaterialColors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Moldura de dispositivo para o preview ("emulador visual").
 *
 * Mede o conteúdo no tamanho real do perfil (dp × densidade), centraliza e
 * reduz para caber na tela, e desenha a moldura por cima: bezel arredondado,
 * câmera frontal, máscara circular para Wear OS e alça de redimensionamento
 * arrastável para perfis [DeviceProfile.resizable] (Chromebook).
 *
 * O conteúdo não é interativo: o preview é somente leitura.
 */
class DeviceFrameView
@JvmOverloads
constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

  var profile: DeviceProfile? = null
    private set

  /** Chamado ao soltar a alça de resize, com o perfil nas novas dimensões. */
  var onResizeFinished: ((DeviceProfile) -> Unit)? = null

  private var contentView: View? = null

  // Dimensões em dp atualmente exibidas (mudam durante o arrasto do resize).
  private var curWidthDp = 0
  private var curHeightDp = 0

  private var fitScale = 1f
  private val drawMatrix = Matrix()
  private val inverseMatrix = Matrix()
  private val mapPts = FloatArray(2)

  private val bezelRect = RectF()
  private val clipPath = Path()
  private val handlePath = Path()

  private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val screenPaint = Paint().apply { style = Paint.Style.FILL }
  private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val textPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

  private var dragging = false
  private var lastContentX = 0f
  private var lastContentY = 0f

  init {
    bezelPaint.color = Color.parseColor("#1C1B1F")
    screenPaint.color =
      try {
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
      } catch (_: Exception) {
        Color.WHITE
      }
    detailPaint.color = Color.parseColor("#0B0B0D")
    handlePaint.color = Color.parseColor("#9A9AA0")
    textPaint.color = Color.WHITE
    setWillNotDraw(false)
  }

  fun setProfile(profile: DeviceProfile, view: View) {
    this.profile = profile
    curWidthDp = profile.effectiveWidthDp
    curHeightDp = profile.effectiveHeightDp
    contentView?.let { removeView(it) }
    contentView = view
    addView(view)
    requestLayout()
  }

  private fun densityScale(): Float = (profile?.densityDpi ?: 160) / 160f

  private fun screenWidthPx(): Int = (curWidthDp * densityScale()).roundToInt()

  private fun screenHeightPx(): Int = (curHeightDp * densityScale()).roundToInt()

  private fun bezelPx(): Int = (BEZEL_DP * densityScale()).roundToInt()

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val view = contentView
    if (profile == null || view == null) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec)
      return
    }
    val sw = screenWidthPx()
    val sh = screenHeightPx()
    val bz = bezelPx()
    view.measure(
      MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY),
    )
    val availW = MeasureSpec.getSize(widthMeasureSpec)
    val availH = MeasureSpec.getSize(heightMeasureSpec)
    fitScale =
      min(
          availW / (sw + 2f * bz),
          availH / (sh + 2f * bz),
        )
        .coerceIn(0.05f, 1f)
    updateMatrix(availW, availH, sw, sh, bz)
    setMeasuredDimension(availW, availH)
  }

  private fun updateMatrix(availW: Int, availH: Int, sw: Int, sh: Int, bz: Int) {
    val totalW = (sw + 2 * bz) * fitScale
    val totalH = (sh + 2 * bz) * fitScale
    val ox = (availW - totalW) / 2f + bz * fitScale
    val oy = (availH - totalH) / 2f + bz * fitScale
    drawMatrix.reset()
    drawMatrix.setScale(fitScale, fitScale)
    drawMatrix.postTranslate(ox, oy)
    drawMatrix.invert(inverseMatrix)
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    contentView?.layout(0, 0, screenWidthPx(), screenHeightPx())
  }

  override fun dispatchDraw(canvas: Canvas) {
    val p = profile
    val view = contentView
    if (p == null || view == null) {
      super.dispatchDraw(canvas)
      return
    }
    val sw = screenWidthPx().toFloat()
    val sh = screenHeightPx().toFloat()
    val bz = bezelPx().toFloat()
    val corner = (min(sw, sh) * 0.055f).coerceAtLeast(bz * 0.4f)

    canvas.save()
    canvas.concat(drawMatrix)

    // Moldura (bezel).
    if (p.isRound) {
      val r = min(sw, sh) / 2f
      canvas.drawCircle(sw / 2f, sh / 2f, r + bz, bezelPaint)
      canvas.drawCircle(sw / 2f, sh / 2f, r, screenPaint)
    } else {
      bezelRect.set(-bz, -bz, sw + bz, sh + bz)
      canvas.drawRoundRect(bezelRect, corner + bz, corner + bz, bezelPaint)
      bezelRect.set(0f, 0f, sw, sh)
      canvas.drawRoundRect(bezelRect, corner, corner, screenPaint)
    }

    // Recorta o conteúdo exatamente na área da tela.
    canvas.save()
    clipPath.rewind()
    if (p.isRound) {
      clipPath.addCircle(sw / 2f, sh / 2f, min(sw, sh) / 2f, Path.Direction.CW)
    } else {
      clipPath.addRoundRect(0f, 0f, sw, sh, corner, corner, Path.Direction.CW)
    }
    canvas.clipPath(clipPath)
    super.dispatchDraw(canvas)
    canvas.restore()

    // Detalhes por cima do conteúdo.
    if (!p.isRound && p.uiModeType != Configuration.UI_MODE_TYPE_TELEVISION) {
      // Câmera frontal (punch-hole) no bezel superior.
      canvas.drawCircle(sw / 2f, -bz * 0.52f, bz * 0.16f, detailPaint)
    }
    if (p.resizable) {
      drawResizeHandle(canvas, sw, sh, bz)
    }
    if (dragging) {
      textPaint.textSize = bz * 0.42f
      canvas.drawText("$curWidthDp × $curHeightDp dp", sw / 2f, sh + bz * 0.74f, textPaint)
    }
    canvas.restore()
  }

  private fun drawResizeHandle(canvas: Canvas, sw: Float, sh: Float, bz: Float) {
    val s = bz * 0.9f
    handlePath.rewind()
    handlePath.moveTo(sw + bz, sh + bz)
    handlePath.lineTo(sw + bz - s, sh + bz)
    handlePath.lineTo(sw + bz, sh + bz - s)
    handlePath.close()
    canvas.drawPath(handlePath, handlePaint)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val p = profile
    if (p == null || !p.resizable) return super.onTouchEvent(event)

    mapPts[0] = event.x
    mapPts[1] = event.y
    inverseMatrix.mapPoints(mapPts)
    val cx = mapPts[0]
    val cy = mapPts[1]

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        if (inResizeHandle(cx, cy)) {
          dragging = true
          lastContentX = cx
          lastContentY = cy
          parent?.requestDisallowInterceptTouchEvent(true)
          return true
        }
      }
      MotionEvent.ACTION_MOVE -> {
        if (dragging) {
          val dScale = densityScale()
          curWidthDp =
            (curWidthDp + (cx - lastContentX) / dScale).roundToInt().coerceIn(MIN_DP, MAX_DP)
          curHeightDp =
            (curHeightDp + (cy - lastContentY) / dScale).roundToInt().coerceIn(MIN_DP, MAX_DP)
          lastContentX = cx
          lastContentY = cy
          requestLayout()
          return true
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (dragging) {
          dragging = false
          parent?.requestDisallowInterceptTouchEvent(false)
          onResizeFinished?.invoke(
            p.copy(widthDp = curWidthDp, heightDp = curHeightDp, folded = false)
          )
          return true
        }
      }
    }
    return super.onTouchEvent(event)
  }

  private fun inResizeHandle(cx: Float, cy: Float): Boolean {
    val sw = screenWidthPx().toFloat()
    val sh = screenHeightPx().toFloat()
    val bz = bezelPx().toFloat()
    // Área de toque generosa ao redor do canto inferior-direito da moldura.
    val s = bz * 1.8f
    return cx >= sw + bz - s && cy >= sh + bz - s
  }

  companion object {
    private const val BEZEL_DP = 30
    private const val MIN_DP = 280
    private const val MAX_DP = 2560
  }
}
