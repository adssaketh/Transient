package com.demonlab.lune.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.graphics.ColorUtils
import com.demonlab.lune.R

/**
 * The jewel case icon in any colour, put on the home screen as a pinned shortcut.
 *
 * Android does not let an app recolour its own launcher icon, but it does let an app add a shortcut with an icon it draws
 * itself, and change that icon later. So the user picks a colour, the app draws the icon in it, and the launcher shows
 * the result next to (or instead of, if the original is removed) the normal Transient icon.
 */
object LauncherIcon {
    const val SHORTCUT_ID = "transient_custom_icon"

    enum class Outcome { REQUESTED, UPDATED, UNSUPPORTED }

    /** Draws the icon for an adaptive-icon mask: a square, with the picture kept inside the safe middle. */
    fun render(cover: Int, sizePx: Int = 432): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(ColorUtils.blendARGB(cover, 0xFF000000.toInt(), 0.84f))
        val k = sizePx / 108f
        c.scale(k, k)
        c.translate(22f, 22f)
        c.scale(0.64f, 0.64f)   // from here on the drawing is laid out on a 100 x 100 grid

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        fun box(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
            fill.color = color
            c.drawRoundRect(RectF(l, t, r, b), radius, radius, fill)
        }

        box(14f, 16f, 86f, 84f, 8f, 0xFF2B2B2B.toInt())
        line.color = 0xFFD6D3D1.toInt(); line.strokeWidth = 3f
        c.drawRoundRect(RectF(14f, 16f, 86f, 84f), 8f, 8f, line)
        box(18f, 20f, 32f, 80f, 3f, 0xFF0B0B0B.toInt())
        line.color = 0xFF3A3A3A.toInt(); line.strokeWidth = 1.2f
        for (x in floatArrayOf(21f, 24f, 27f, 30f)) c.drawLine(x, 22f, x, 78f, line)
        box(36f, 20f, 82f, 80f, 4f, cover)
        fill.color = ColorUtils.blendARGB(cover, 0xFF000000.toInt(), 0.78f)
        c.drawCircle(59f, 46f, 15f, fill)
        fill.color = cover
        c.drawCircle(59f, 46f, 4f, fill)
        val wave = Path().apply {
            moveTo(42f, 68f)
            quadTo(47f, 60f, 52f, 68f)
            quadTo(57f, 76f, 62f, 68f)
            quadTo(67f, 60f, 72f, 68f)
        }
        line.color = 0xEBFFFFFF.toInt(); line.strokeWidth = 2.6f
        c.drawPath(wave, line)
        return bmp
    }

    fun isPinned(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return false
            sm.pinnedShortcuts.any { it.id == SHORTCUT_ID }
        } catch (_: Throwable) {
            false
        }
    }

    /** Adds the coloured icon to the home screen (the launcher asks the user), or updates it when it is already there. */
    fun apply(context: Context, cover: Int): Outcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return Outcome.UNSUPPORTED
        return try {
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return Outcome.UNSUPPORTED
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply { action = Intent.ACTION_MAIN } ?: return Outcome.UNSUPPORTED
            val info = ShortcutInfo.Builder(context, SHORTCUT_ID)
                .setShortLabel(context.getString(R.string.app_name))
                .setIcon(Icon.createWithAdaptiveBitmap(render(cover)))
                .setIntent(launch)
                .build()
            when {
                sm.pinnedShortcuts.any { it.id == SHORTCUT_ID } -> {
                    sm.updateShortcuts(listOf(info))
                    Outcome.UPDATED
                }
                sm.isRequestPinShortcutSupported -> {
                    sm.requestPinShortcut(info, null)
                    Outcome.REQUESTED
                }
                else -> Outcome.UNSUPPORTED
            }
        } catch (_: Throwable) {
            Outcome.UNSUPPORTED
        }
    }
}
