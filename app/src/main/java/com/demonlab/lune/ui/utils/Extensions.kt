package com.demonlab.lune.ui.utils

import kotlinx.coroutines.CoroutineStart
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

fun Vibrator.triggerLightVibration() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        this.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    } else {
        @Suppress("DEPRECATION")
        this.vibrate(20)
    }
}

fun formatDuration(duration: Long): String {
    val minutes = (duration / 1000) / 60
    val seconds = (duration / 1000) % 60
    return "%d:%02d".format(Locale.getDefault(), minutes, seconds)
}

fun formatDurationCompact(durationInMillis: Long): String {
    val totalSeconds = durationInMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) {
        if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
    } else {
        "${minutes}m"
    }
}

fun formatLongDuration(durationInMillis: Long): String {
    val totalSeconds = durationInMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.getDefault(), hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.getDefault(), minutes, seconds)
    }
}

fun Modifier.bounceClick(
    scaleDown: Float = 0.94f,
    dampingRatio: Float = Spring.DampingRatioMediumBouncy,
    stiffness: Float = Spring.StiffnessMedium
): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()

    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(scaleDown, dampingRatio, stiffness) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val animJob = coroutineScope.launch {
                    scale.animateTo(
                        targetValue = scaleDown,
                        animationSpec = spring(dampingRatio = dampingRatio, stiffness = stiffness)
                    )
                }
                waitForUpOrCancellation()
                animJob.cancel()
                coroutineScope.launch {
                    scale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(dampingRatio = dampingRatio, stiffness = stiffness)
                    )
                }
            }
        }
}

class MaterialExpressiveScallopShape(
    private val lobes: Int = 8,
    private val amplitude: Float = 0.07f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path()
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val baseRadius = minOf(size.width, size.height) / 2f / (1f + amplitude)
        val steps = lobes * 16
        val angleStep = (2.0 * Math.PI / steps).toFloat()

        for (stepIndex in 0..steps) {
            val theta = stepIndex * angleStep - (Math.PI / 2.0).toFloat()
            val r = baseRadius * (1f + amplitude * cos(lobes * theta))
            val x = centerX + r * cos(theta)
            val y = centerY + r * sin(theta)

            if (stepIndex == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
        return Outline.Generic(path)
    }
}

fun Modifier.songSwipeGestures(
    enabled: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit
): Modifier = composed {
    if (!enabled) return@composed this

    val coroutineScope = rememberCoroutineScope()
    // One animated value drives everything, so letting go never flips between two sources
    // (the old two-state version jumped for a frame at the end of every drag).
    val offsetX = remember { Animatable(0f) }
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)

    this
        .graphicsLayer {
            val x = offsetX.value
            translationX = x
            val s = (1f - kotlin.math.abs(x) / 1600f).coerceIn(0.96f, 1f)
            scaleX = s
            scaleY = s
        }
        .pointerInput(Unit) {
            var triggered = false
            val settle = spring<Float>(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
            // Horizontal-only: vertical drags are left alone for the player's own swipe up/down gestures.
            detectHorizontalDragGestures(
                onDragStart = {
                    triggered = false
                    coroutineScope.launch { offsetX.stop() }
                },
                onDragEnd = { coroutineScope.launch { offsetX.animateTo(0f, settle) } },
                onDragCancel = { coroutineScope.launch { offsetX.animateTo(0f, settle) } },
                onHorizontalDrag = { _, dragAmount ->
                    val target = (offsetX.value + dragAmount * 0.35f).coerceIn(-75f, 75f)
                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { offsetX.snapTo(target) }
                    if (!triggered && kotlin.math.abs(target) > 45f) {
                        triggered = true
                        if (target < 0) currentOnNext() else currentOnPrevious()
                    }
                }
            )
        }
}

/**
 * Smoothly scrolls to [targetIndex] with optimized performance for long lists.
 * If the target is further away than [threshold], it first snaps close to the target
 * before smoothly animating the remaining items, avoiding frame drops caused by
 * composing hundreds of intermediate items.
 */
suspend fun LazyListState.fastSmoothScrollToItem(
    targetIndex: Int = 0,
    threshold: Int = 12
) {
    val currentIndex = firstVisibleItemIndex
    if (currentIndex == targetIndex && firstVisibleItemScrollOffset == 0) return
    val distance = kotlin.math.abs(currentIndex - targetIndex)
    if (distance > threshold) {
        val total = layoutInfo.totalItemsCount
        val intermediateIndex = if (currentIndex > targetIndex) {
            if (total > 0) (targetIndex + threshold).coerceAtMost(total - 1) else targetIndex + threshold
        } else {
            (targetIndex - threshold).coerceAtLeast(0)
        }
        scrollToItem(intermediateIndex)
    }
    animateScrollToItem(targetIndex)
}

/**
 * Smoothly scrolls to [targetIndex] with optimized performance for long grids.
 * If the target is further away than [threshold], it first snaps close to the target
 * before smoothly animating the remaining items, avoiding frame drops.
 */
suspend fun LazyGridState.fastSmoothScrollToItem(
    targetIndex: Int = 0,
    threshold: Int = 16
) {
    val currentIndex = firstVisibleItemIndex
    if (currentIndex == targetIndex && firstVisibleItemScrollOffset == 0) return
    val distance = kotlin.math.abs(currentIndex - targetIndex)
    if (distance > threshold) {
        val total = layoutInfo.totalItemsCount
        val intermediateIndex = if (currentIndex > targetIndex) {
            if (total > 0) (targetIndex + threshold).coerceAtMost(total - 1) else targetIndex + threshold
        } else {
            (targetIndex - threshold).coerceAtLeast(0)
        }
        scrollToItem(intermediateIndex)
    }
    animateScrollToItem(targetIndex)
}

