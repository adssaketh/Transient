package com.demonlab.lune.ui.utils

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Pinch to change how many columns a grid shows: spread two fingers for bigger tiles (fewer columns),
 * pinch together for smaller tiles (more columns).
 *
 * It only reacts while two or more fingers are down, so one-finger scrolling and taps are untouched.
 */
@Composable
fun Modifier.pinchToChangeColumns(
    columns: Int,
    minColumns: Int,
    maxColumns: Int,
    onColumnsChange: (Int) -> Unit
): Modifier {
    val currentColumns by rememberUpdatedState(columns)
    val currentOnChange by rememberUpdatedState(onColumnsChange)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var accumulated = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        accumulated *= zoom
                        val target = when {
                            accumulated > ZOOM_IN_STEP -> currentColumns - 1
                            accumulated < ZOOM_OUT_STEP -> currentColumns + 1
                            else -> currentColumns
                        }.coerceIn(minColumns, maxColumns)
                        if (target != currentColumns) {
                            currentOnChange(target)
                            accumulated = 1f
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

private const val ZOOM_IN_STEP = 1.22f
private const val ZOOM_OUT_STEP = 0.82f
