package com.demonlab.lune.tools

import android.graphics.Bitmap

object ImageAnalyzer {
    /**
     * Returns an ARGB colour that best represents the artwork: the most populated hue bucket,
     * weighted towards saturated, mid-brightness pixels so greys/near-black/near-white don't win.
     * Falls back to the average colour for monochrome art. Works on a downscaled copy for speed.
     */
    fun dominantColor(source: Bitmap): Int {
        val bmp = Bitmap.createScaledBitmap(source, 48, 48, true)
        val hsv = FloatArray(3)
        val weights = FloatArray(36)
        val sumR = FloatArray(36); val sumG = FloatArray(36); val sumB = FloatArray(36)
        var avgR = 0L; var avgG = 0L; var avgB = 0L
        for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
            val px = bmp.getPixel(x, y)
            val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
            avgR += r; avgG += g; avgB += b
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val sat = hsv[1]; val v = hsv[2]
            if (v < 0.15f || (sat < 0.12f && v > 0.9f)) continue
            val w = sat * sat * (1f - kotlin.math.abs(v - 0.6f)) + 0.001f
            val bucket = ((hsv[0] / 10f).toInt()).coerceIn(0, 35)
            weights[bucket] += w
            sumR[bucket] += r * w; sumG[bucket] += g * w; sumB[bucket] += b * w
        }
        val n = (bmp.width * bmp.height).coerceAtLeast(1)
        var best = -1; var bestW = 0f
        for (i in 0 until 36) if (weights[i] > bestW) { bestW = weights[i]; best = i }
        return if (best >= 0 && bestW > 0.5f) {
            android.graphics.Color.rgb(
                (sumR[best] / weights[best]).toInt(),
                (sumG[best] / weights[best]).toInt(),
                (sumB[best] / weights[best]).toInt()
            )
        } else {
            android.graphics.Color.rgb((avgR / n).toInt(), (avgG / n).toInt(), (avgB / n).toInt())
        }
    }

}