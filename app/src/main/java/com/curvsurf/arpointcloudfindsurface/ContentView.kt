package com.curvsurf.arpointcloudfindsurface

import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.curvsurf.arpointcloudfindsurface.helpers.ARCoreAppGLRenderer
import com.curvsurf.arpointcloudfindsurface.helpers.MotionTrackingStabilizer
import com.curvsurf.arpointcloudfindsurface.helpers.OpenGLView
import com.curvsurf.arpointcloudfindsurface.ui.theme.ARPointCloudFindSurfaceTheme
import com.curvsurf.arpointcloudfindsurface.views.CaptureButton
import com.curvsurf.arpointcloudfindsurface.views.FeatureTypePicker
import com.curvsurf.arpointcloudfindsurface.views.MotionTrackingStabilizationView
import com.curvsurf.arpointcloudfindsurface.views.PreviewToggleButton
import com.curvsurf.arpointcloudfindsurface.views.RadiusControlView
import com.curvsurf.arpointcloudfindsurface.views.RecordingToggleButton
import com.curvsurf.arpointcloudfindsurface.views.SeedRadiusTutorialView
import com.curvsurf.arpointcloudfindsurface.views.UndoButton
import com.curvsurf.findsurface.FeatureType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale


data class StabilizationData(
    val status: MotionTrackingStabilizer.Status = MotionTrackingStabilizer.Status.NotStarted,
    val notEnoughFeatures: Boolean = false,
    val progress: Float = 0f
)

data class RecordingData(
    val recording: Boolean = false,
    val pointCount: Int = 0
)

data class FindSurfaceData(
    val featureType: FeatureType = FeatureType.Plane,
    val previewEnabled: Boolean = false,
    val hasToSaveOne: Boolean = false,
    val transactionIsEmpty: Boolean = true,
    val currentDepth: Float = -1f,
    val isSurfaceDetected: Boolean = false,
    val rmsErrorCm: Float = 0f,
    val inlierCount: Int = 0
)

data class RadiusData(
    val seedRadiusRatio: Float = 0.5f,
    val probeRadiusRatio: Float = 0f,
    val lastCanvasSize: IntSize = IntSize.Zero
)

sealed class UIEvent {
    data object Undo: UIEvent()
    data object ClearGeometries: UIEvent()
    data object ClearPointCloud: UIEvent()
}

interface ContentViewModel {
    val stabilizationData: StateFlow<StabilizationData>
    fun updateStabilizationData(
        status: MotionTrackingStabilizer.Status? = null,
        notEnoughFeatures: Boolean? = null,
        progress: Float? = null
    )

    val recordingData: StateFlow<RecordingData>
    fun updateRecordingData(
        recording: Boolean? = null,
        pointCount: Int? = null
    )

    val radiusData: StateFlow<RadiusData>
    fun updateRadiusData(
        seedRadiusRatio: Float? = null,
        probeRadiusRatio: Float? = null,
        lastCanvasSize: IntSize? = null
    )

    val findSurfaceData: StateFlow<FindSurfaceData>
    fun updateFindSurfaceData(
        featureType: FeatureType? = null,
        previewEnabled: Boolean? = null,
        hasToSaveOne: Boolean? = null,
        transactionIsEmpty: Boolean? = null,
        currentDepth: Float? = null,
        isSurfaceDetected: Boolean? = null,
        rmsErrorCm: Float? = null,
        inlierCount: Int? = null
    )

    val effects: SharedFlow<UIEffect>
    val toasts: SharedFlow<String>

    fun skipStabilization()
    fun captureGeometry()
    fun undo()
    fun clearGeometries()
    fun clearPointCloud()

    fun savePreferenceValues()
}

class PreviewViewModel(stabilized: Boolean): ContentViewModel {
    private val _stabilizationData = MutableStateFlow(
        StabilizationData(
            status = if (stabilized) MotionTrackingStabilizer.Status.Finished
                     else MotionTrackingStabilizer.Status.NotStarted
        )
    )
    override val stabilizationData = _stabilizationData.asStateFlow()

    override fun updateStabilizationData(
        status: MotionTrackingStabilizer.Status?,
        notEnoughFeatures: Boolean?,
        progress: Float?
    ) {}

    private val _recordingData = MutableStateFlow(RecordingData())
    override val recordingData = _recordingData.asStateFlow()

    override fun updateRecordingData(recording: Boolean?, pointCount: Int?) {}

    private val _radiusData = MutableStateFlow(RadiusData())
    override val radiusData = _radiusData.asStateFlow()

    override fun updateRadiusData(
        seedRadiusRatio: Float?,
        probeRadiusRatio: Float?,
        lastCanvasSize: IntSize?
    ) {}

    private val _findSurfaceData = MutableStateFlow(FindSurfaceData())
    override val findSurfaceData = _findSurfaceData.asStateFlow()

    override fun updateFindSurfaceData(
        featureType: FeatureType?,
        previewEnabled: Boolean?,
        hasToSaveOne: Boolean?,
        transactionIsEmpty: Boolean?,
        currentDepth: Float?,
        isSurfaceDetected: Boolean?,
        rmsErrorCm: Float?,
        inlierCount: Int?
    ) {}

    private val _effects = MutableSharedFlow<UIEffect>()
    override val effects = _effects.asSharedFlow()
    private val _toasts = MutableSharedFlow<String>()
    override val toasts = _toasts.asSharedFlow()

    override fun skipStabilization() {}
    override fun captureGeometry() {}
    override fun undo() {}
    override fun clearGeometries() {}
    override fun clearPointCloud() {}
    override fun savePreferenceValues() {}
}

interface GLSurfaceViewHolder {
    fun setGLSurfaceView(view: GLSurfaceView)
}

@Composable
fun ContentView(
    renderer: ARCoreAppGLRenderer,
    viewModel: ContentViewModel,
    viewHolder: GLSurfaceViewHolder
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is UIEffect.Snackbar -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        actionLabel = effect.actionLabel,
                        withDismissAction = effect.withDismissAction,
                        duration = effect.duration
                    )
                }
            }
        }
    }
    val context = LocalContext.current
    val toast = remember { Toast.makeText(context, "", Toast.LENGTH_SHORT) }
    LaunchedEffect(Unit) {
        viewModel.toasts.collectLatest { msg ->
            toast.cancel()
            toast.setText(msg)
            toast.duration = Toast.LENGTH_SHORT
            toast.show()
        }
    }

    ARPointCloudFindSurfaceTheme {
        Box(modifier = Modifier.fillMaxSize()) {

            OpenGLView(renderer) {
                viewHolder.setGLSurfaceView(it)
            }

            val stabilizationData by viewModel.stabilizationData.collectAsStateWithLifecycle()

            MotionTrackingStabilizationView(
                stabilizationData = stabilizationData,
                onSkip = { viewModel.skipStabilization() },
                modifier = Modifier.align(Alignment.Center)
            )

            if (stabilizationData.status.isFinished) {

                val recordingData by viewModel.recordingData.collectAsStateWithLifecycle()
                val radiusData by viewModel.radiusData.collectAsStateWithLifecycle()
                val findSurfaceData by viewModel.findSurfaceData.collectAsStateWithLifecycle()

                RadiusControlView(
                    radiusData = radiusData,
                    onUpdateSeedRadius = { viewModel.updateRadiusData(seedRadiusRatio = it) },
                    onUpdateProbeRadius = { viewModel.updateRadiusData(probeRadiusRatio = it) },
                    onUpdateLastCanvasSize = { viewModel.updateRadiusData(lastCanvasSize = it) },
                    onRequireToSaveSeedRadius = { viewModel.savePreferenceValues() }
                )

                StatusView(
                    modifier = Modifier.align(Alignment.TopStart),
                    pointCount = recordingData.pointCount,
                    findSurfaceData = findSurfaceData,
                    recording = recordingData.recording
                )

                val density = LocalDensity.current
                val containerWidth = with(density) {
                    LocalWindowInfo.current.containerSize.width.toDp()
                }
                val (endPadding, alignment) = if (containerWidth >= 600.dp) 80.dp to Alignment.TopEnd
                                              else 0.dp to Alignment.Center
                SeedRadiusTutorialView(
                    modifier = Modifier
                        .wrapContentSize()
                        .padding(top = 16.dp, end = endPadding)
                    .align(alignment = alignment)
                )

                ButtonPanel(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    states = ButtonPanelState(
                        recording = recordingData.recording,
                        hasPointCloud = recordingData.pointCount > 0,
                        previewEnabled = findSurfaceData.previewEnabled
                    ),
                    actions = ButtonPanelAction(
                        onRecordingToggleClick = viewModel::updateRecordingData,
                        onRecordingToggleLongClick = viewModel::clearPointCloud,
                        onPreviewToggleClick = { viewModel.updateFindSurfaceData(previewEnabled = it) },
                        onCaptureButtonClick = viewModel::captureGeometry,
                        onUndoButtonClick = viewModel::undo,
                        onUndoButtonLongClick = viewModel::clearGeometries
                    )
                )

                FeatureTypePicker(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 48.dp),
                    targetFeature = findSurfaceData.featureType,
                    onSetTargetFeature = { viewModel.updateFindSurfaceData(featureType = it) }
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }
}


@Preview(
    showBackground = true,
    device = Devices.PIXEL_TABLET,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 1194,
    heightDp = 834
)
@Composable
fun ContentViewPreview_Tablet_NotStabilized() {
    val renderer = object : ARCoreAppGLRenderer {
        override fun onSurfaceCreated() {}
        override fun onSurfaceChanged(width: Int, height: Int) {}
        override fun onDrawFrame() {}
    }
    val viewHolder = object : GLSurfaceViewHolder {
        override fun setGLSurfaceView(view: GLSurfaceView) {}
    }
    ContentView(
        renderer = renderer,
        viewModel = PreviewViewModel(stabilized = false),
        viewHolder = viewHolder
    )
}

@Preview(
    showBackground = true,
    device = Devices.PIXEL_TABLET,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 1194,
    heightDp = 834
)
@Composable
fun ContentViewPreview_Tablet_Stabilized() {
    val renderer = object : ARCoreAppGLRenderer {
        override fun onSurfaceCreated() {}
        override fun onSurfaceChanged(width: Int, height: Int) {}
        override fun onDrawFrame() {}
    }
    val viewHolder = object : GLSurfaceViewHolder {
        override fun setGLSurfaceView(view: GLSurfaceView) {}
    }
    ContentView(
        renderer = renderer,
        viewModel = PreviewViewModel(stabilized = true),
        viewHolder = viewHolder
    )
}

@Preview(
    showBackground = true,
    device = Devices.PIXEL_TABLET,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 800,
    heightDp = 360
)
@Composable
fun ContentViewPreview_Phone_NotStabilized() {
    val renderer = object : ARCoreAppGLRenderer {
        override fun onSurfaceCreated() {}
        override fun onSurfaceChanged(width: Int, height: Int) {}
        override fun onDrawFrame() {}
    }
    val viewHolder = object : GLSurfaceViewHolder {
        override fun setGLSurfaceView(view: GLSurfaceView) {}
    }
    ContentView(
        renderer = renderer,
        viewModel = PreviewViewModel(stabilized = false),
        viewHolder = viewHolder
    )
}

@Preview(
    showBackground = true,
    device = Devices.PIXEL_TABLET,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 800,
    heightDp = 360
)
@Composable
fun ContentViewPreview_Phone_Stabilized() {
    val renderer = object : ARCoreAppGLRenderer {
        override fun onSurfaceCreated() {}
        override fun onSurfaceChanged(width: Int, height: Int) {}
        override fun onDrawFrame() {}
    }
    val viewHolder = object : GLSurfaceViewHolder {
        override fun setGLSurfaceView(view: GLSurfaceView) {}
    }
    ContentView(
        renderer = renderer,
        viewModel = PreviewViewModel(stabilized = true),
        viewHolder = viewHolder
    )
}

@Composable
private fun StatusView(
    modifier: Modifier,
    pointCount: Int,
    findSurfaceData: FindSurfaceData,
    recording: Boolean
) {
    Card(
        modifier = modifier.padding(top = 16.dp, start = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.65f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Row 1: Points & Live Depth
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Points pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (recording) Color(0xFF4CAF50) else Color(0xFFFF9800))
                    )
                    Text(
                        text = "$pointCount pts",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }

                // Depth Meter
                val depthText = if (findSurfaceData.currentDepth > 0f) {
                    String.format(Locale.US, "%.2f m", findSurfaceData.currentDepth)
                } else {
                    "--"
                }
                val (depthColor, depthHint) = when {
                    findSurfaceData.currentDepth <= 0f -> Color.LightGray to ""
                    findSurfaceData.currentDepth < 0.35f -> Color(0xFFFFB74D) to " (Close)"
                    findSurfaceData.currentDepth <= 2.2f -> Color(0xFF81C784) to " (Optimal)"
                    else -> Color(0xFF90CAF9) to " (Far)"
                }
                Text(
                    text = "Depth: $depthText$depthHint",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = depthColor
                )
            }

            // Row 2: Surface Detection Status & Accuracy
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val featureName = findSurfaceData.featureType.name
                if (findSurfaceData.isSurfaceDetected) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF2E7D32).copy(alpha = 0.75f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "✓ $featureName Locked",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC8E6C9)
                        )
                    }
                    Text(
                        text = "RMS: ${String.format(Locale.US, "%.1f", findSurfaceData.rmsErrorCm)} cm",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE0E0E0)
                    )
                    Text(
                        text = "(${findSurfaceData.inlierCount} inliers)",
                        fontSize = 11.sp,
                        color = Color(0xFFB0BEC5)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFEF6C00).copy(alpha = 0.65f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Aligning $featureName...",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFFE082)
                        )
                    }
                    Text(
                        text = "Aim reticle at surface",
                        fontSize = 11.sp,
                        color = Color(0xFFB0BEC5)
                    )
                }
            }
        }
    }
}

private data class ButtonPanelState(
    val recording: Boolean,
    val hasPointCloud: Boolean,
    val previewEnabled: Boolean
)

private data class ButtonPanelAction(
    val onRecordingToggleClick: (Boolean) -> Unit,
    val onRecordingToggleLongClick: () -> Unit,
    val onPreviewToggleClick: (Boolean) -> Unit,
    val onCaptureButtonClick: () -> Unit,
    val onUndoButtonClick: () -> Unit,
    val onUndoButtonLongClick: () -> Unit
)

@Composable
private fun ButtonPanel(
    modifier: Modifier,
    states: ButtonPanelState,
    actions: ButtonPanelAction
) {
    Column(
        modifier = modifier.padding(end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RecordingToggleButton(
            recording = states.recording,
            onClick = actions.onRecordingToggleClick,
            onLongClick = actions.onRecordingToggleLongClick
        )
        PreviewToggleButton(
            previewEnabled = states.previewEnabled,
            onClick = actions.onPreviewToggleClick
        )
        CaptureButton(
            enabled = states.hasPointCloud,
            onClick = actions.onCaptureButtonClick
        )
        UndoButton(
            enabled = states.hasPointCloud,
            onClick = actions.onUndoButtonClick,
            onLongClick = actions.onUndoButtonLongClick
        )
    }
}