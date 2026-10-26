package com.demonlab.lune.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
internal class LetterIndex(val letters: List<String>, val firstIndex: List<Int>) {
    /** The letter of the item at [itemIndex]. */
    fun letterAt(itemIndex: Int): String {
        var current = letters.first()
        for (k in letters.indices) {
            if (firstIndex[k] <= itemIndex) current = letters[k]
        }
        return current
    }
}

/**
 * Builds the [LetterIndex] for [items], using [key] (usually the title / name) for the first letter.
 * Returns null when the list is not ordered by that text (key returns "") or has too few / too many letters
 * for an index to make sense; the scrollbar then falls back to a plain drag handle.
 */
internal fun <T> buildLetterIndex(items: List<T>, key: (T) -> String): LetterIndex? {
    val letters = ArrayList<String>()
    val firsts = ArrayList<Int>()
    for (i in items.indices) {
        val text = key(items[i]).trim()
        if (text.isEmpty()) return null
        val letter = letterOf(text)
        if (!letters.contains(letter)) {
            letters.add(letter)
            firsts.add(i)
            if (letters.size > 40) return null
        }
    }
    if (letters.size < 2) return null
    return LetterIndex(letters, firsts)
}

/** First letter without accents ("É" -> "E"), digits as themselves, anything else "#". */
private fun letterOf(text: String): String {
    val c = text[0]
    if (c in 'A'..'Z') return c.toString()
    if (c in 'a'..'z') return c.uppercaseChar().toString()
    if (c in '0'..'9') return c.toString()
    val first = Normalizer.normalize(text.take(1), Normalizer.Form.NFD).firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

/**
 * Always-visible alphabet index along the right edge (1, 3, A, B, C ...). Tap a letter or slide a finger along it to
 * jump to the first item of that letter; [activeLetter] (the letter at the top of the list) is highlighted.
 */
@Composable
internal fun AlphabetIndexBar(
    letterIndex: LetterIndex,
    activeLetter: String,
    onLetterSelected: (position: Int) -> Unit,
    textColor: Color,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val letters = letterIndex.letters
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentOnSelected by rememberUpdatedState(onLetterSelected)
    var heightPx by remember { mutableFloatStateOf(0f) }
    var pressedPosition by remember { mutableIntStateOf(-1) }

    // Letter size follows the space there is, so 40 letters on a short screen still fit.
    val perLetterDp = with(density) { (heightPx / letters.size).toDp().value }
    val fontSize = (perLetterDp * 0.62f).coerceIn(8f, 15f)

    Column(
        modifier = modifier
            .width(30.dp)
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()

                    fun select(y: Float) {
                        val position = ((y / heightPx.coerceAtLeast(1f)) * letters.size).toInt()
                            .coerceIn(0, letters.size - 1)
                        if (position != pressedPosition) {
                            pressedPosition = position
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentOnSelected(position)
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
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        letters.forEachIndexed { position, letter ->
            val selected = if (pressedPosition >= 0) position == pressedPosition else letter == activeLetter
            Text(
                text = letter,
                fontSize = (if (selected) fontSize + 2f else fontSize).sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                color = if (selected) activeColor else textColor.copy(alpha = 0.85f),
                maxLines = 1
            )
        }
    }
}
