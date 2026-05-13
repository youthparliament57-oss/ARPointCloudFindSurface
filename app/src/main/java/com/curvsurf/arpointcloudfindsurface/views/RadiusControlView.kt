package com.curvsurf.arpointcloudfindsurface.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.curvsurf.arpointcloudfindsurface.RadiusData
import kotlin.math.min
import kotlin.math.pow

@Composable
fun RadiusControlView(
    radiusData: RadiusData,
    onUpdateSeedRadius: (Float) -> Unit,
    onUpdateProbeRadius: (Float) -> Unit,
    onUpdateLastCanvasSize: (IntSize) -> Unit,
    onRequireToSaveSeedRadius: () -> Unit
) {

    val latestSeedRadiusRatio by rememberUpdatedState(radiusData.seedRadiusRatio)
    var baseRatio by remember { mutableFloatStateOf(radiusData.seedRadiusRatio) }
    var cumulativeScale by remember { mutableFloatStateOf(1f) }
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        cumulativeScale *= zoomChange
        val magnification = cumulativeScale.pow(1.1f)
        val newSeedRadius = (baseRatio * magnification).coerceIn(0.1f, 1.0f)
        onUpdateSeedRadius(newSeedRadius)
    }

    LaunchedEffect(transformState) {
        snapshotFlow { transformState.isTransformInProgress }.collect { inProgress ->
            if (inProgress) {
                baseRatio = latestSeedRadiusRatio
                cumulativeScale = 1f
            }
        }
    }

    LaunchedEffect(radiusData.lastCanvasSize) {
        val size = radiusData.lastCanvasSize
        if (size.width > 0 && size.height > 0 && radiusData.probeRadiusRatio == 0f) {
            val diameter = min(size.width, size.height).toFloat()
            onUpdateProbeRadius(100f / diameter)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> onRequireToSaveSeedRadius()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.00001f))
            .transformable(transformState)
    ) {
        Canvas(modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .onSizeChanged { size ->
                onUpdateLastCanvasSize(size)
            }
        ) {
            val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
            if (radiusData.lastCanvasSize != canvasSize) {
                onUpdateLastCanvasSize(canvasSize)
            }

            val diameter = min(size.width, size.height)
            val center = Offset(size.width / 2f, size.height / 2f)

            val dashPurple = PathEffect.dashPathEffect(floatArrayOf(10f, 20f), 0f)
            val dashGreen = PathEffect.dashPathEffect(floatArrayOf(6f, 12f), 0f)

            fun drawRadiusCircle(
                ratio: Float,
                color: Color,
                strokeWidth: Float,
                pathEffect: PathEffect?
            ) {
                val sizePx = (ratio * diameter - 1f).coerceAtLeast(0f)
                if (sizePx > 1f) {
                    drawCircle(
                        color = color,
                        radius = sizePx / 2f,
                        center = center,
                        style = Stroke(
                            width = strokeWidth,
                            pathEffect = pathEffect
                        )
                    )
                }
            }

            drawRadiusCircle(
                ratio = radiusData.probeRadiusRatio,
                color = Color(0xFF9C27B0),
                strokeWidth = 4f,
                pathEffect = dashPurple
            )

            drawRadiusCircle(
                ratio = radiusData.seedRadiusRatio,
                color = Color(0xFF4CAF50),
                strokeWidth = 8f,
                pathEffect = dashGreen
            )
        }
    }
}
