package com.example.rainradar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    trackColor: Color = Color(0xFF26354A), // Default BorderColor
    activeTrackColor: Color = Color(0xFF3B82F6), // Default AccentBlue
    thumbColor: Color = Color(0xFF3B82F6), // Default AccentBlue
    thumbSize: Dp = 36.dp,
    trackWidth: Dp = 4.dp
) {
    val density = LocalDensity.current
    val thumbSizePx = with(density) { thumbSize.toPx() }
    val halfThumbSizePx = thumbSizePx / 2f

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val heightPx = constraints.maxHeight.toFloat()
        val dragRangePx = heightPx - thumbSizePx
        val rangeLength = valueRange.endInclusive - valueRange.start

        val currentValueFraction = if (rangeLength > 0f) {
            ((value - valueRange.start) / rangeLength).coerceIn(0f, 1f)
        } else {
            0f
        }

        // Berechne die Y-Koordinate des Daumens (von oben gemessen).
        // Minimalwert ist ganz unten: heightPx - halfThumbSizePx
        // Maximalwert ist ganz oben: halfThumbSizePx
        val thumbCenterY = heightPx - halfThumbSizePx - (currentValueFraction * dragRangePx)

        fun updateValueFromY(y: Float) {
            val clampedY = y.coerceIn(halfThumbSizePx, heightPx - halfThumbSizePx)
            val fraction = if (dragRangePx > 0f) {
                (heightPx - halfThumbSizePx - clampedY) / dragRangePx
            } else {
                0f
            }
            val newValue = valueRange.start + fraction * rangeLength
            onValueChange(newValue.coerceIn(valueRange.start, valueRange.endInclusive))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(valueRange, heightPx, thumbSizePx) {
                    detectTapGestures(
                        onPress = { offset ->
                            updateValueFromY(offset.y)
                            onValueChangeFinished?.invoke()
                        }
                    )
                }
                .pointerInput(valueRange, heightPx, thumbSizePx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            updateValueFromY(offset.y)
                        },
                        onDragEnd = {
                            onValueChangeFinished?.invoke()
                        },
                        onDragCancel = {
                            onValueChangeFinished?.invoke()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            updateValueFromY(change.position.y)
                        }
                    )
                }
        ) {
            // Track (Hintergrund-Leiste)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(trackWidth)
                    .fillMaxHeight()
                    .padding(vertical = thumbSize / 2)
                    .clip(RoundedCornerShape(trackWidth / 2))
                    .background(trackColor)
            ) {
                // Aktiver Track (von unten bis zum Daumen)
                val activeTrackHeightDp = with(density) {
                    val activePx = (heightPx - halfThumbSizePx - thumbCenterY).coerceAtLeast(0f)
                    activePx.toDp()
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .width(trackWidth)
                        .height(activeTrackHeightDp)
                        .background(activeTrackColor)
                )
            }

            // Daumen (Slider-Knopf)
            val thumbOffsetY = with(density) {
                (thumbCenterY - halfThumbSizePx).toDp()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = thumbOffsetY)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(thumbColor)
                    .border(2.dp, Color.White, CircleShape)
            )
        }
    }
}
