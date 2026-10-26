package com.demonlab.lune.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.demonlab.lune.R

// Geometry of res/drawable-nodpi/jewel_case_frame.webp (cut from the reference render): the case is 1078 x 948 and the
// booklet window, where the album art shows through, sits at these fractions of it.
private const val CASE_RATIO = 1078f / 948f
private const val WINDOW_LEFT = 0.1187f
private const val WINDOW_TOP = 0.0295f
private const val WINDOW_WIDTH = 0.8367f
private const val WINDOW_HEIGHT = 0.9494f

/**
 * Album art inside a CD jewel case: the art sits in the booklet window of the case frame (black ribbed spine on the left,
 * clear plastic all around) with a soft reflection underneath. Fills the width it is given; whatever height is left below
 * the case is used for the reflection. Used only in the full player.
 */
@Composable
fun JewelCaseCover(coverUrl: Any?, modifier: Modifier = Modifier, sway: Boolean = false) {
    val tiltX = remember { mutableFloatStateOf(0f) }
    val tiltY = remember { mutableFloatStateOf(0f) }
    if (sway) PhoneTiltEffect(tiltX, tiltY)
    LaunchedEffect(sway) {
        if (!sway) {
            tiltX.floatValue = 0f
            tiltY.floatValue = 0f
        }
    }
    val density = LocalDensity.current.density
    BoxWithConstraints(
        modifier = modifier.graphicsLayer {
            // Read here (draw phase) so the sensor never recomposes the player, it only redraws the case.
            rotationX = tiltX.floatValue
            rotationY = tiltY.floatValue
            cameraDistance = 14f * density
        }
    ) {
        val caseWidth = maxWidth
        val caseHeight = caseWidth / CASE_RATIO
        val reflectionHeight = (maxHeight - caseHeight).coerceAtLeast(Dp(0f))

        JewelCase(
            coverUrl = coverUrl,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(caseWidth, caseHeight)
        )

        if (reflectionHeight.value > 6f) {
            val fade = (reflectionHeight.value / caseHeight.value).coerceIn(0.05f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = caseHeight)
                    .size(caseWidth, reflectionHeight)
                    .clipToBounds()
            ) {
                // The case again, upside down, so that its bottom edge is the top of this strip, fading out downwards.
                JewelCase(
                    coverUrl = coverUrl,
                    modifier = Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .size(caseWidth, caseHeight)
                        .graphicsLayer {
                            scaleY = -1f
                            alpha = 0.30f
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(0f to Color.Black, fade to Color.Transparent, 1f to Color.Transparent),
                                blendMode = BlendMode.DstIn
                            )
                        }
                )
            }
        }
    }
}

@Composable
private fun JewelCase(coverUrl: Any?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val w = maxWidth
        val h = maxHeight
        // The art is a hair bigger than the window so no gap can show between it and the plastic frame.
        val bleed = w * 0.004f
        SongCoverImage(
            coverUrl = coverUrl,
            contentDescription = null,
            modifier = Modifier
                .offset(x = w * WINDOW_LEFT - bleed, y = h * WINDOW_TOP - bleed)
                .size(w * WINDOW_WIDTH + bleed * 2, h * WINDOW_HEIGHT + bleed * 2),
            shape = RectangleShape,
            backgroundColor = Color(0xFF2A2628),
            iconScale = 0.5f
        )
        // A faint sheen across the booklet, as light slides over the clear lid.
        Box(
            modifier = Modifier
                .offset(x = w * WINDOW_LEFT, y = h * WINDOW_TOP)
                .size(w * WINDOW_WIDTH, h * WINDOW_HEIGHT)
                .background(
                    Brush.linearGradient(
                        0.00f to Color.Transparent,
                        0.30f to Color.White.copy(alpha = 0.10f),
                        0.42f to Color.Transparent,
                        0.78f to Color.Transparent,
                        0.86f to Color.White.copy(alpha = 0.06f),
                        1.00f to Color.Transparent
                    )
                )
        )
        Image(
            painter = painterResource(id = R.drawable.jewel_case_frame),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private const val MAX_SWAY_DEGREES = 9f

/**
 * Small 3D sway: while the screen is visible, the phone's accelerometer gently turns the case (at most about 9 degrees),
 * so the plastic seems to catch the light as you move. How the phone is usually held is learned slowly, so only changes
 * of angle move the case. No permission is needed, and the sensor is released when the screen is not in front.
 */
@Composable
private fun PhoneTiltEffect(tiltX: MutableFloatState, tiltY: MutableFloatState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var resting = Float.NaN
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val g = 9.81f
                val side = (event.values[0] / g).coerceIn(-1f, 1f)
                val back = (event.values[2] / g).coerceIn(-1f, 1f)
                resting = if (resting.isNaN()) back else resting + (back - resting) * 0.01f
                val targetY = side * MAX_SWAY_DEGREES
                val targetX = ((back - resting) * MAX_SWAY_DEGREES * 1.5f).coerceIn(-MAX_SWAY_DEGREES, MAX_SWAY_DEGREES)
                tiltY.floatValue += (targetY - tiltY.floatValue) * 0.12f
                tiltX.floatValue += (targetX - tiltX.floatValue) * 0.12f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    if (accelerometer != null) sensorManager?.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
                Lifecycle.Event.ON_PAUSE -> sensorManager?.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager?.unregisterListener(listener)
        }
    }
}
