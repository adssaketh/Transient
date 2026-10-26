package com.demonlab.lune.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer

/**
 * The letters a list actually contains, in list order, and the index of the first item of each letter.
 * Used by the fast-scroll handles to jump straight to "M", "S", ... instead of scrolling by position.
 */
internal class LetterIndex(val letters: List<String>, val firstIndex: List<Int>)

/**
 * Builds the [LetterIndex] for [items], using [key] (usually the title / name) for the first letter.
 * Returns null when the list is not ordered by that text (key returns "") or has too few / too many letters
 * for a rail to make sense; the scrollbar then falls back to plain position scrolling.
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

/** First letter without accents ("É" -> "E"); anything that is not a letter (digits, symbols) becomes "#". */
private fun letterOf(text: String): String {
    val c = text[0]
    if (c in 'A'..'Z') return c.toString()
    if (c in 'a'..'z') return c.uppercaseChar().toString()
    val first = Normalizer.normalize(text.take(1), Normalizer.Form.NFD).firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

/** Vertical A-Z strip shown while dragging the fast-scroll handle; [current] is highlighted. */
@Composable
internal fun AlphabetRail(
    letters: List<String>,
    current: String,
    height: Dp,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = backgroundColor.copy(alpha = 0.92f),
        shadowElevation = 6.dp,
        modifier = modifier
            .width(22.dp)
            .height(height)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { letter ->
                val selected = letter == current
                Text(
                    text = letter,
                    fontSize = if (selected) 13.sp else 10.sp,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (selected) textColor else textColor.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }
        }
    }
}
