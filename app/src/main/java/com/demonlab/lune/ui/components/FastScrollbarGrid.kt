package com.demonlab.lune.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Fast-scroll handle with a letter bubble for [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]
 * (albums, artists, genres). Same look and behaviour as [FastScrollbar], which is list-only.
 *
 * [itemKeyOrLetter] returns the text whose first letter is shown in the bubble; return "" to show no letter
 * (e.g. when the grid is sorted by track count or date).
 */
@Composable
fun <T> FastScrollbarGrid(
    gridState: LazyGridState,
    items: List<T>,
    modifier: Modifier = Modifier,
    itemKeyOrLetter: (T) -> String,
    hasBlurBackground: Boolean = false,
    accentColor: Color? = null,
    thumbColor: Color = when {
        accentColor != null -> accentColor.copy(alpha = 0.85f)
        hasBlurBackground -> Color.White.copy(alpha = 0.75f)
        else -> MaterialTheme.colorScheme.primary
    },
    bubbleColor: Color = when {
        accentColor != null -> accentColor
        hasBlurBackground -> Color.White
        else -> MaterialTheme.colorScheme.primaryContainer
    },
    bubbleTextColor: Color = when {
        accentColor != null -> Color.White
        hasBlurBackground -> Color.Black
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
) {
    if (items.size <= 15) return

    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    var isDragging by remember { mutableStateOf(false) }
    var dragThumbY by remember { mutableFloatStateOf(0f) }
    var containerHeight by remember { mutableFloatStateOf(0f) }
    var currentLetter by remember { mutableStateOf("") }

    val thumbHeightDp = 48.dp
    val thumbHeightPx = with(LocalDensity.current) { thumbHeightDp.toPx() }

    val currentItems by rememberUpdatedState(items)
    val currentLetterOf by rememberUpdatedState(itemKeyOrLetter)

    // Alphabet index: letters the grid really contains, so tapping "M" lands on the first M.
    val letterIndex = remember(items) { buildLetterIndex(items) { currentLetterOf(it) } }

    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 10.dp else 4.dp,
        animationSpec = tween(durationMillis = 150),
        label = "gridThumbWidth"
    )
    val thumbAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0.4f,
        animationSpec = tween(durationMillis = 150),
        label = "gridThumbAlpha"
    )

    if (letterIndex != null) {
        val activeLetter by remember(letterIndex) {
            derivedStateOf { letterIndex.letterAt(gridState.firstVisibleItemIndex) }
        }
        Box(
            modifier = modifier.fillMaxHeight().width(30.dp),
            contentAlignment = Alignment.Center
        ) {
            AlphabetIndexBar(
                letterIndex = letterIndex,
                activeLetter = activeLetter,
                textColor = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface,
                activeColor = accentColor ?: if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.primary,
                onLetterSelected = { position ->
                    coroutineScope.launch { gridState.scrollToItem(letterIndex.firstIndex[position]) }
                },
                modifier = Modifier.fillMaxHeight(0.82f)
            )
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(24.dp)
            .onSizeChanged { size -> containerHeight = size.height.toFloat() }
            .pointerInput(gridState) {
                var scrollJob: Job? = null
                var lastTargetIndex = -1

                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    isDragging = true

                    fun updatePosition(y: Float) {
                        val total = currentItems.size
                        if (total <= 1) return

                        val availableHeight = (containerHeight - thumbHeightPx).coerceAtLeast(0f)
                        val targetThumbY = (y - thumbHeightPx / 2f).coerceIn(0f, availableHeight)
                        dragThumbY = targetThumbY
                        val progress = if (availableHeight > 0f) {
                            (targetThumbY / availableHeight).coerceIn(0f, 1f)
                        } else 0f
                        val targetIndex = (progress * (total - 1)).roundToInt().coerceIn(0, total - 1)

                        val text = currentLetterOf(currentItems[targetIndex]).trim()
                        val newLetter = if (text.isNotEmpty()) text.take(1).uppercase() else ""
                        if (newLetter != currentLetter) {
                            currentLetter = newLetter
                            if (newLetter.isNotBlank()) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }

                        if (targetIndex != lastTargetIndex) {
                            lastTargetIndex = targetIndex
                            scrollJob?.cancel()
                            scrollJob = coroutineScope.launch { gridState.scrollToItem(targetIndex) }
                        }
                    }

                    updatePosition(down.position.y)

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                change.consume()
                                updatePosition(change.position.y)
                            } else {
                                break
                            }
                        }
                    } finally {
                        isDragging = false
                        lastTargetIndex = -1
                    }
                }
            }
    ) {
        // Thumb
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val thumbY = if (isDragging) {
                        dragThumbY
                    } else {
                        val availableHeight = (containerHeight - thumbHeightPx).coerceAtLeast(0f)
                        val count = items.size
                        val progress = if (count > 1) {
                            if (!gridState.canScrollForward) {
                                1f
                            } else {
                                val visible = gridState.layoutInfo.visibleItemsInfo
                                val first = visible.firstOrNull()
                                if (first != null && first.size.height > 0) {
                                    // Position within the list = first visible row + how far that row is scrolled,
                                    // measured in items (a row holds several).
                                    val itemsPerRow = visible.count { it.offset.y == first.offset.y }.coerceAtLeast(1)
                                    val rowFraction = (-first.offset.y.toFloat() / first.size.height).coerceIn(0f, 1f)
                                    ((gridState.firstVisibleItemIndex + rowFraction * itemsPerRow) / (count - 1).toFloat())
                                        .coerceIn(0f, 1f)
                                } else 0f
                            }
                        } else 0f
                        progress * availableHeight
                    }
                    IntOffset(-4.dp.roundToPx(), thumbY.roundToInt())
                }
                .width(thumbWidth)
                .height(thumbHeightDp)
                .clip(RoundedCornerShape(percent = 50))
                .background(thumbColor.copy(alpha = thumbAlpha))
                .then(
                    if (hasBlurBackground && isDragging) {
                        Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(percent = 50))
                    } else {
                        Modifier
                    }
                )
        )

        // Letter bubble
        AnimatedVisibility(
            visible = isDragging && currentLetter.isNotEmpty(),
            enter = fadeIn() + scaleIn(transformOrigin = TransformOrigin(1f, 0.5f)),
            exit = fadeOut() + scaleOut(transformOrigin = TransformOrigin(1f, 0.5f)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    IntOffset(-56.dp.roundToPx(), (dragThumbY - 12.dp.toPx()).roundToInt())
                }
        ) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = bubbleColor,
                shadowElevation = if (hasBlurBackground) 4.dp else 8.dp,
                border = if (hasBlurBackground) BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)) else null,
                modifier = Modifier.width(48.dp).height(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = currentLetter,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = bubbleTextColor
                    )
                }
            }
        }
    }
}
