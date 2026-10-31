package com.demonlab.lune.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.graphics.ColorUtils
import com.demonlab.lune.R

/**
 * The 3D jewel case icon in any colour, put on the home screen as a pinned shortcut.
 *
 * Android does not let an app recolour its own launcher icon, but it does let an app add a shortcut with an icon it draws
 * itself, and change that icon later. So the user picks a colour, the app draws the icon in it, and the launcher shows
 * the result next to (or instead of, if the original is removed) the normal Transient icon.
 */
object LauncherIcon {
    const val SHORTCUT_ID = "transient_custom_icon"

    enum class Outcome { REQUESTED, UPDATED, UNSUPPORTED }

    /**
     * Draws the icon for an adaptive-icon mask: a square, with the picture kept inside the safe middle. It stacks the same
     * five pictures as the in-app logo; the cover and the disc are multiplied by [cover].
     */
    fun render(context: Context, cover: Int, sizePx: Int = 432): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val opaque = cover or 0xFF000000.toInt()
        c.drawColor(ColorUtils.blendARGB(opaque, 0xFF000000.toInt(), 0.84f))
        val k = sizePx / 108f
        // same place as the real launcher icon: grid point 50,50 in the middle, 0.62 dp per grid unit, pictures cover 6..94
        fun at(g: Float) = (54f + (g - 50f) * 0.62f) * k
        val dst = RectF(at(6f), at(6f), at(94f), at(94f))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        fun layer(id: Int, tint: Int?) {
            val picture = BitmapFactory.decodeResource(context.resources, id) ?: return
            paint.colorFilter = if (tint != null) PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY) else null
            c.drawBitmap(picture, null, dst, paint)
            picture.recycle()
        }
        layer(R.drawable.logo3d_base, null)
        layer(R.drawable.logo3d_cover, opaque)
        layer(R.drawable.logo3d_overlay, null)
        layer(R.drawable.logo3d_disc, opaque)
        layer(R.drawable.logo3d_wave, null)
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
                .setIcon(Icon.createWithAdaptiveBitmap(render(context, cover)))
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
