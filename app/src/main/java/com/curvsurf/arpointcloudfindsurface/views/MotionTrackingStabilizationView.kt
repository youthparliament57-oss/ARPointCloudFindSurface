package com.curvsurf.arpointcloudfindsurface.views

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.curvsurf.arpointcloudfindsurface.R
import com.curvsurf.arpointcloudfindsurface.StabilizationData
import com.curvsurf.arpointcloudfindsurface.helpers.MotionTrackingStabilizer
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MotionTrackingStabilizationView(
    stabilizationData: StabilizationData,
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (stabilizationData.status != MotionTrackingStabilizer.Status.Finished) {
            if (stabilizationData.notEnoughFeatures) {
                AskUserToScanMoreTexturedEnvironmentView(
                    onSkip = onSkip,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            } else {
                AskUserToScanEnvironmentView(
                    progress = stabilizationData.progress,
                    onSkip = onSkip,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AskUserToScanMoreTexturedEnvironmentView(
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xAAFFFFFF)),
        modifier = modifier.widthIn(max = 320.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = R.drawable.chat_info_24px),
                contentDescription = null,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = "Keep moving your device and aim at surfaces that are textured or have details on them.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            if (onSkip != null) {
                FilledTonalButton(
                    onClick = onSkip,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("Skip & Start Scanning")
                }
            }
        }
    }
}

@Composable
private fun AskUserToScanEnvironmentView(
    progress: Float,
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "tilt")
    // tilt animation: 15° ↔ -15°
    val tilt by infinite.animateFloat(
        initialValue = 30f,
        targetValue = -30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tiltAnim"
    )
    // small circular motion
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "moveAnim"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xAAFFFFFF)),
        modifier = modifier.widthIn(max = 320.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(R.drawable.mobile_landscape_24px),
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer {
                        rotationY = tilt
                        translationX = cos(Math.toRadians(angle.toDouble())).toFloat() * 32f
                        translationY = sin(Math.toRadians(angle.toDouble())).toFloat() * 16f
                    }
            )
            Text(
                text = "Scan your surroundings using the device.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .widthIn(min = 200.dp)
                    .height(6.dp)
            )
            if (onSkip != null) {
                FilledTonalButton(
                    onClick = onSkip,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("Skip & Start Scanning")
                }
            }
        }
    }
}
