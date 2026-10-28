package com.demonlab.lune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos

/**
 * Angle (degrees) of a round cover that spins like a CD while [active]. When playback pauses the disc stops where it
 * is and carries on from there, instead of snapping back to 0. Read it inside graphicsLayer { } so only the cover is
 * redrawn each frame, not the whole player.
 */
@Composable
fun rememberCdRotation(active: Boolean, secondsPerTurn: Float = 8f): State<Float> {
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // The flying CD hands over the angle it has reached, so this CD carries on turning from the same place.
            val handedOver = FlyingCd.takeAngleOverride()
            if (!handedOver.isNaN()) angle.floatValue = handedOver
            angle.floatValue = (angle.floatValue + (now - last) / 1_000_000_000f * (360f / secondsPerTurn)) % 360f
            last = now
        }
    }
    return angle
}

/** The hub of an audio CD: a clear ring with the centre hole, drawn over a round cover (it does not need to turn). */
@Composable
fun CdCenterHole(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize(0.27f)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f))
        )
        Box(
            modifier = Modifier
                .fillMaxSize(0.12f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
        )
    }
}
