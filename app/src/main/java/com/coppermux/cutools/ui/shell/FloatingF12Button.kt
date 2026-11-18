package com.coppermux.cutools.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.coppermux.cutools.ui.theme.concentricInner
import com.coppermux.cutools.ui.theme.concentricMiddle
import com.coppermux.cutools.ui.theme.concentricOuter
import com.coppermux.cutools.ui.theme.onBrowserNavContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Floating F12 navigation button.
 *
 * Provides single-tap navigation between Browser and DevTools Dashboard,
 * free draggable repositioning within safe viewport bounds, and a long-press
 * context menu to hide the button.
 *
 * Redesigned to match CuTools' visual identity: three concentric circles
 * (outer 64dp, middle 56dp, inner 48dp) resembling a miniature version of
 * the DevTools empty-state graphic.
 *
 * Theme colors:
 * - Outer circle: [concentricOuter] (lightest copper tint in light mode).
 * - Middle circle: [concentricMiddle] (stronger peach tint in light mode).
 * - Inner circle: [concentricInner] (soft peach in light mode).
 * - Text: [onBrowserNavContainer] (bold and dark in light mode, bright peach in dark mode).
 */
@Composable
fun FloatingF12Button(
    onTap: () -> Unit,
    onHide: () -> Unit,
    onPositionChanged: (xRatio: Float, yRatio: Float) -> Unit,
    initialXRatio: Float,
    initialYRatio: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val coroutineScope = rememberCoroutineScope()

        val buttonSize = 64.dp
        val horizontalMargin = 16.dp
        val topMargin = 16.dp

        // Bottom clearance: persistent JS input pill (~72dp) + IME keyboard insets + 8dp margin
        val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val bottomClearance = 72.dp + imeBottomPadding + 8.dp

        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val buttonSizePx = with(density) { buttonSize.toPx() }
        val minXPx = with(density) { horizontalMargin.toPx() }
        val maxXPx = (containerWidthPx - buttonSizePx - minXPx).coerceAtLeast(minXPx)
        val minYPx = with(density) { topMargin.toPx() }
        val bottomClearancePx = with(density) { bottomClearance.toPx() }
        val maxYPx = (containerHeightPx - buttonSizePx - bottomClearancePx).coerceAtLeast(minYPx)

        // Track position ratios
        var currentXRatio by remember(initialXRatio) { mutableFloatStateOf(initialXRatio.coerceIn(0f, 1f)) }
        var currentYRatio by remember(initialYRatio) { mutableFloatStateOf(initialYRatio.coerceIn(0f, 1f)) }

        // Pixel offsets
        var offsetX by remember { mutableFloatStateOf(minXPx + (maxXPx - minXPx) * currentXRatio) }
        var offsetY by remember { mutableFloatStateOf(minYPx + (maxYPx - minYPx) * currentYRatio) }

        // Re-clamp and update pixel coordinates when viewport or IME changes
        LaunchedEffect(minXPx, maxXPx, minYPx, maxYPx, currentXRatio, currentYRatio) {
            offsetX = minXPx + (maxXPx - minXPx) * currentXRatio
            offsetY = (minYPx + (maxYPx - minYPx) * currentYRatio).coerceIn(minYPx, maxYPx)
        }

        var isMenuExpanded by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(buttonSize),
            contentAlignment = Alignment.Center,
        ) {
            // Three concentric circles: 64dp outer -> 56dp middle -> 48dp inner
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.concentricOuter)
                    .pointerInput(Unit) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalDrag = Offset.Zero
                            var hasPassedSlop = false
                            var longPressFired = false

                            val longPressJob = coroutineScope.launch {
                                delay(viewConfiguration.longPressTimeoutMillis)
                                if (!hasPassedSlop) {
                                    longPressFired = true
                                    isMenuExpanded = true
                                }
                            }

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                    if (!change.pressed) {
                                        // Finger released
                                        longPressJob.cancel()
                                        if (!hasPassedSlop && !longPressFired) {
                                            change.consume()
                                            onTap()
                                        }
                                        if (hasPassedSlop) {
                                            val finalXRatio = if (maxXPx > minXPx) {
                                                (offsetX - minXPx) / (maxXPx - minXPx)
                                            } else 1.0f
                                            val finalYRatio = if (maxYPx > minYPx) {
                                                (offsetY - minYPx) / (maxYPx - minYPx)
                                            } else 0.75f
                                            currentXRatio = finalXRatio
                                            currentYRatio = finalYRatio
                                            onPositionChanged(finalXRatio, finalYRatio)
                                        }
                                        break
                                    }

                                    val positionChange = change.positionChange()
                                    totalDrag += positionChange

                                    if (!hasPassedSlop) {
                                        if (totalDrag.getDistance() > touchSlop) {
                                            hasPassedSlop = true
                                            longPressJob.cancel()
                                            change.consume()
                                            offsetX = (offsetX + totalDrag.x).coerceIn(minXPx, maxXPx)
                                            offsetY = (offsetY + totalDrag.y).coerceIn(minYPx, maxYPx)
                                        }
                                    } else {
                                        change.consume()
                                        offsetX = (offsetX + positionChange.x).coerceIn(minXPx, maxXPx)
                                        offsetY = (offsetY + positionChange.y).coerceIn(minYPx, maxYPx)
                                    }
                                }
                            } finally {
                                longPressJob.cancel()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.concentricMiddle),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.concentricInner),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "F12",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onBrowserNavContainer,
                        )
                    }
                }
            }

            // Long-press popup menu: Hide Button
            DropdownMenu(
                expanded = isMenuExpanded,
                onDismissRequest = { isMenuExpanded = false },
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 8.dp,
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Hide Button",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.VisibilityOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        isMenuExpanded = false
                        onHide()
                    },
                )
            }
        }
    }
}
