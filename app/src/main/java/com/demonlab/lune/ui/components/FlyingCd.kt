package com.demonlab.lune.ui.components

import android.animation.TimeInterpolator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.demonlab.lune.tools.DiagnosticLogger
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "flying CD": when an album is tapped, a CD slides out of its cover, arcs across the screen and lands on the
 * mini-player's CD.
 *
 * The flight is built from plain Android views animated with ViewPropertyAnimator. Those animations run on the
 * RenderThread, so they keep moving smoothly even while the main thread is busy opening the album page and starting
 * playback (the version drawn with Compose froze and jumped exactly then). The arc comes from nesting views: one view
 * moves in X, its child in Y, and each has its own easing curve, so together they trace a curve.
 *
 * All coordinates are WINDOW pixels (boundsInWindow), which match the activity's decor view.
 */
object FlyingCd {
    /** Mini-player CD centre and diameter in window pixels. Plain fields written by layout callbacks. */
    @Volatile var targetCenter: Offset? = null
    @Volatile var targetDiameter: Float = 0f

    /** The tapped album cover (window pixels), recorded by the album tile right before the tap is handled. */
    @Volatile var pendingBounds: Rect? = null
    @Volatile var pendingCornerPx: Float = 0f
    @Volatile var pendingModel: Any? = null

    /** 0 hides the real mini-player CD while the flying one is in the air. Read only inside graphicsLayer. */
    val miniAlpha = mutableFloatStateOf(1f)

    /** Delays (ms after the tap). The album page waits until the CD has cleared the cover. */
    const val PLAY_DELAY_MS = 150L // the flight runs on the render thread, so playback can start early
    @Volatile var detailDelayMs = 260L // set by start(): when the CD has cleared the cover
    const val DURATION_MS = 820L

    private const val HANDOFF_START = 0.86f
    private val easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    private val miniFade = Animatable(1f)
    private var job: Job? = null

    private fun Context.asActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * Starts the flight from the tile recorded in [pendingBounds]. Returns false, and does nothing, when it cannot run
     * (system animations off, no recorded tile, mini player not on screen). The caller then just carries on without it.
     * The pictures are prepared and the animation is started a moment later inside [scope].
     */
    fun start(context: Context, scope: CoroutineScope, animationsEnabled: Boolean, holeColor: Int): Boolean {
        val src = pendingBounds
        val model = pendingModel
        val corner = pendingCornerPx
        val target = targetCenter
        val endD = targetDiameter
        pendingBounds = null
        val activity = context.asActivity()
        if (!animationsEnabled) { DiagnosticLogger.note("FLYCD skipped: system animations are off"); return false }
        if (activity == null) { DiagnosticLogger.note("FLYCD skipped: no activity"); return false }
        if (src == null || src.width <= 0f) { DiagnosticLogger.note("FLYCD skipped: tile position unknown"); return false }
        if (target == null || endD <= 0f) { DiagnosticLogger.note("FLYCD skipped: mini player not on screen"); return false }
        val dist = (target - src.center).getDistance()
        if (dist < 1f) return false

        // Path geometry, computed up front: the caller needs to know when the CD has cleared the cover.
        val startD = (src.width * 0.92f).toInt().coerceAtLeast(8)
        val p0 = src.center
        val p3 = target
        val dir = (p3 - p0) / dist
        var perp = Offset(dir.y, -dir.x)
        if (perp.y > 0f) perp = Offset(-perp.x, -perp.y) // bow upward, like a thrown disc
        val bulge = (dist * 0.28f).coerceAtLeast(startD * 0.8f)
        val pc = (p0 + p3) / 2f + perp * bulge
        fun bez(a: Float, c: Float, b: Float, e: Float): Float { val u = 1f - e; return u * u * a + 2f * u * e * c + e * e * b }
        var tClear = 0.5f
        for (i in 1..100) {
            val t = i / 100f
            val e = easing.transform(t)
            val cx = bez(p0.x, pc.x, p3.x, e)
            val cy = bez(p0.y, pc.y, p3.y, e)
            if (kotlin.math.hypot(cx - p0.x, cy - p0.y) >= src.width * 0.707f + startD / 2f) { tClear = t; break }
        }
        detailDelayMs = (tClear * DURATION_MS).toLong() + 40L

        job?.cancel()
        miniAlpha.floatValue = 0f
        job = scope.launch {
            var container: View? = null
            var coverView: View? = null
            val decor = activity.window.decorView as ViewGroup
            try {
                val coverPx = src.width.toInt().coerceAtLeast(8)
                val coverBmp = loadBitmap(activity, model, coverPx)
                val cdBmp = makeCd(coverBmp, startD, holeColor)

                val dx = p3.x - p0.x
                val dy = p3.y - p0.y

                val ix = TimeInterpolator { t ->
                    val e = easing.transform(t)
                    if (kotlin.math.abs(dx) < 1f) e else (bez(p0.x, pc.x, p3.x, e) - p0.x) / dx
                }
                val iy = TimeInterpolator { t ->
                    val e = easing.transform(t)
                    if (kotlin.math.abs(dy) < 1f) e else (bez(p0.y, pc.y, p3.y, e) - p0.y) / dy
                }
                val endScale = endD / startD
                val iScale = TimeInterpolator { t ->
                    val e = easing.transform(t)
                    val bump = if (t > 0.8f) 1f + 0.05f * sin(Math.PI.toFloat() * ((t - 0.8f) / 0.2f)) else 1f
                    val s = (1f + (endScale - 1f) * e) * bump
                    if (kotlin.math.abs(endScale - 1f) < 0.001f) e else (s - 1f) / (endScale - 1f)
                }
                val iSpin = TimeInterpolator { t -> 1f - (1f - t).pow(3) }

                // Nested views: X -> Y -> scale -> spin -> picture, each animated with its own curve.
                val ctx: Context = activity
                val vX = FrameLayout(ctx)
                val vY = FrameLayout(ctx)
                val vS = FrameLayout(ctx)
                val vR = FrameLayout(ctx)
                val img = ImageView(ctx).apply { setImageBitmap(cdBmp) }
                listOf(vX, vY, vS, vR).forEach { it.clipChildren = false }
                vR.addView(img, FrameLayout.LayoutParams(startD, startD))
                vS.addView(vR, FrameLayout.LayoutParams(startD, startD))
                vY.addView(vS, FrameLayout.LayoutParams(startD, startD))
                vX.addView(vY, FrameLayout.LayoutParams(startD, startD))
                val lp = FrameLayout.LayoutParams(startD, startD).apply {
                    leftMargin = (p0.x - startD / 2f).toInt()
                    topMargin = (p0.y - startD / 2f).toInt()
                }
                decor.addView(vX, lp)
                container = vX

                // The album cover is drawn again, on top of the CD, so the CD seems to slide out from behind it.
                if (coverBmp != null) {
                    val cv = ImageView(ctx).apply {
                        setImageBitmap(scaleSquare(coverBmp, coverPx))
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: android.graphics.Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, corner)
                            }
                        }
                        clipToOutline = true
                    }
                    val clp = FrameLayout.LayoutParams(coverPx, coverPx).apply {
                        leftMargin = src.left.toInt()
                        topMargin = src.top.toInt()
                    }
                    decor.addView(cv, clp)
                    coverView = cv
                }

                val d = DURATION_MS
                vX.animate().translationX(dx).setDuration(d).setInterpolator(ix).start()
                vY.animate().translationY(dy).setDuration(d).setInterpolator(iy).start()
                vS.animate().scaleX(endScale).scaleY(endScale).setDuration(d).setInterpolator(iScale).start()
                vR.animate().rotation(540f).setDuration(d).setInterpolator(iSpin).start()
                img.animate().alpha(0f)
                    .setStartDelay((HANDOFF_START * d).toLong())
                    .setDuration(((1f - HANDOFF_START) * d).toLong())
                    .setInterpolator(LinearInterpolator()).start()
                coverView?.animate()?.alpha(0f)
                    ?.setStartDelay((tClear * d).toLong())?.setDuration(90)
                    ?.setInterpolator(LinearInterpolator())?.start()
                DiagnosticLogger.note("FLYCD started (distance=${dist.toInt()}px, render-thread views)")

                // Cross-fade into the real mini-player CD while the flying one fades out.
                delay((HANDOFF_START * d).toLong())
                miniFade.snapTo(0f)
                miniFade.animateTo(
                    1f,
                    tween(((1f - HANDOFF_START) * d).toInt(), easing = LinearEasing),
                    block = { miniAlpha.floatValue = value }
                )
                delay(60)
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                DiagnosticLogger.note("FLYCD failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                container?.let { decor.removeView(it) }
                coverView?.let { decor.removeView(it) }
                miniAlpha.floatValue = 1f
            }
        }
        return true
    }

    private suspend fun loadBitmap(context: Context, model: Any?, px: Int): Bitmap? {
        if (model == null) return null
        return try {
            val req = ImageRequest.Builder(context)
                .data(model)
                .size(px)
                .precision(Precision.INEXACT)
                .allowHardware(false)
                .build()
            val res = Coil.imageLoader(context).execute(req)
            ((res as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        } catch (_: Throwable) {
            null
        }
    }

    private fun scaleSquare(src: Bitmap, px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawCenterCrop(Canvas(out), src, px)
        return out
    }

    private fun drawCenterCrop(canvas: Canvas, src: Bitmap, d: Int) {
        val scale = max(d.toFloat() / src.width, d.toFloat() / src.height)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate((d - src.width * scale) / 2f, (d - src.height * scale) / 2f)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    /** A round cover with the CD hub on top (same look as CdCenterHole). */
    private fun makeCd(cover: Bitmap?, d: Int, holeColor: Int): Bitmap {
        val out = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val r = d / 2f
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(r, r, r, Path.Direction.CW) })
        if (cover != null) drawCenterCrop(canvas, cover, d) else canvas.drawColor(Color.DKGRAY)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(56, 255, 255, 255)
        canvas.drawCircle(r, r, d * 0.27f / 2f, p)
        p.color = holeColor
        canvas.drawCircle(r, r, d * 0.12f / 2f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, d / 70f)
        p.color = Color.argb(89, 255, 255, 255)
        canvas.drawCircle(r, r, d * 0.12f / 2f, p)
        canvas.restore()
        return out
    }
}

/** Kept so existing call sites compile; the flight is drawn by views now, so there is nothing to compose. */
@Composable
fun FlyingCdOverlay() {
}
