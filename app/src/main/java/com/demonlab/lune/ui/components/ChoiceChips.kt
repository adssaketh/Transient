package com.demonlab.lune.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Row of equal-width round choices (like Ping's choice chips); [selected] is the index of the chosen one. */
@Composable
fun ChoiceChips(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onBlur: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Surface(
                onClick = { onSelect(index) },
                shape = CircleShape,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else if (onBlur) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                else if (onBlur) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            ) {
                Box(modifier = Modifier.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
