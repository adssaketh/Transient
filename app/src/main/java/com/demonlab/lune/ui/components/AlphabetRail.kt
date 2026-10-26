package com.demonlab.lune.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer

/**
 * The letters a list actually contains, in list order, and the index of the first item of each letter.
 * The alphabet index on the right edge jumps straight to "M", "S", ... with it.
 */
internal class LetterIndex(
    val letters: List<String>,
    val firstIndex: List<Int>,
    private val itemLetters: List<String>
) {
    private val latin = letters.filter { it.length == 1 && it[0] in 'A'..'Z' }

    /** What the index shows: digits / "#" that exist, then the whole A-Z (missing letters are dimmed), then "…". */
    val displayLetters: List<String> = buildList {
        letters.filter { it.length == 1 && (it[0] in '0'..'9' || it == "#") }.sorted().forEach { add(it) }
        addAll(('A'..'Z').map { it.toString() })
        if (letters.contains(OTHER_LETTER)) add(OTHER_LETTER)
    }

    fun hasLetter(display: String): Boolean = letters.contains(display)

    /** The letter of the item at [itemIndex] (works for any sort order). */
    fun letterAt(itemIndex: Int): String = itemLetters.getOrNull(itemIndex) ?: letters.first()

    /** Item index to scroll to for a tapped [display] letter; a letter with no items goes to the next one that has. */
    fun jumpIndexFor(display: String): Int {
        val exact = letters.indexOf(display)
        if (exact >= 0) return firstIndex[exact]
        if (display.length == 1 && display[0] in 'A'..'Z' && latin.isNotEmpty()) {
            val target = latin.filter { it > display }.minOrNull() ?: latin.maxOrNull() ?: latin.first()
            return firstIndex[letters.indexOf(target)]
        }
        return firstIndex.first()
    }
}

internal const val OTHER_LETTER = "\u2026"

/**
 * Builds the [LetterIndex] for [items], using [key] (usually the title / name) for the first letter. It works for any
 * sort order: a letter jumps to the first item that starts with it. Returns null when the list has fewer than two
 * different letters.
 */
internal fun <T> buildLetterIndex(items: List<T>, key: (T) -> String): LetterIndex? {
    val letters = ArrayList<String>()
    val firsts = ArrayList<Int>()
    val perItem = ArrayList<String>(items.size)
    for (i in items.indices) {
        val text = key(items[i]).trim()
        val letter = if (text.isEmpty()) "#" else letterOf(text)
        perItem.add(letter)
        if (!letters.contains(letter)) {
            letters.add(letter)
            firsts.add(i)
        }
    }
    if (letters.size < 2) return null
    return LetterIndex(letters, firsts, perItem)
}

/** First letter without accents ("É" -> "E"), digits as themselves, other scripts "…", symbols "#". */
private fun letterOf(text: String): String {
    val c = text[0]
    if (c in 'A'..'Z') return c.toString()
    if (c in 'a'..'z') return c.uppercaseChar().toString()
    if (c in '0'..'9') return c.toString()
    val first = Normalizer.normalize(text.take(1), Normalizer.Form.NFD).firstOrNull() ?: return "#"
    if (!first.isLetter()) return "#"
    val upper = first.uppercaseChar()
    return if (upper in 'A'..'Z') upper.toString() else OTHER_LETTER
}

/**
 * Always-visible alphabet index along the right edge (1, 3, A, B, C ... Z). Tap a letter or slide a finger along it to
 * jump to the first item of that letter (a letter with no items goes to the next one that has). [activeLetter] (the
 * letter at the top of the list) is highlighted. Every letter gets an equal slice of the height, so all of them
 * always fit on screen.
 */
@Composable
internal fun AlphabetIndexBar(
    letterIndex: LetterIndex,
    activeLetter: String,
    onJumpToItem: (itemIndex: Int) -> Unit,
    textColor: Color,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val display = letterIndex.displayLetters
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentOnJump by rememberUpdatedState(onJumpToItem)
    var heightPx by remember { mutableFloatStateOf(0f) }
    var pressedPosition by remember { mutableIntStateOf(-1) }

    val perLetterDp = with(density) { (heightPx / display.size).toDp().value }
    val fontSize = (perLetterDp * 0.64f).coerceIn(7f, 15f)

    Column(
        modifier = modifier
            .width(30.dp)
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(display) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()

                    fun select(y: Float) {
                        val position = ((y / heightPx.coerceAtLeast(1f)) * display.size).toInt()
                            .coerceIn(0, display.size - 1)
                        if (position != pressedPosition) {
                            pressedPosition = position
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentOnJump(letterIndex.jumpIndexFor(display[position]))
                        }
                    }

                    select(down.position.y)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                change.consume()
                                select(change.position.y)
                            } else {
                                break
                            }
                        }
                    } finally {
                        pressedPosition = -1
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        display.forEachIndexed { position, letter ->
            val selected = if (pressedPosition >= 0) position == pressedPosition else letter == activeLetter
            val present = letterIndex.hasLetter(letter)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter,
                    fontSize = (if (selected) fontSize + 2f else fontSize).sp,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                    color = when {
                        selected -> activeColor
                        present -> textColor.copy(alpha = 0.9f)
                        else -> textColor.copy(alpha = 0.35f)
                    },
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
