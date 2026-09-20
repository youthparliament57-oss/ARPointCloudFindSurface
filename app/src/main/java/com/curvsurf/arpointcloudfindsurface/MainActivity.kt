package com.curvsurf.arpointcloudfindsurface

import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.collection.MutableIntList
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.xr.runtime.math.Matrix4
import androidx.xr.runtime.math.Vector3
import androidx.xr.runtime.math.Vector4
import com.curvsurf.arpointcloudfindsurface.helpers.ARCoreAppGLRenderer
import com.curvsurf.arpointcloudfindsurface.helpers.CameraMotionDetector
import com.curvsurf.arpointcloudfindsurface.helpers.FeatureCompressor
import com.curvsurf.arpointcloudfindsurface.helpers.MotionTrackingStabilizer
import com.curvsurf.arpointcloudfindsurface.helpers.TAG
import com.curvsurf.arpointcloudfindsurface.helpers.arcore.ARFrameProvider
import com.curvsurf.arpointcloudfindsurface.helpers.arcore.ARFrameProviderException
import com.curvsurf.arpointcloudfindsurface.helpers.arcore.CameraPermissionHelper
import com.curvsurf.arpointcloudfindsurface.helpers.curvsurf.GeometryObject
import com.curvsurf.arpointcloudfindsurface.helpers.math.distance
import com.curvsurf.arpointcloudfindsurface.helpers.math.distance2
import com.curvsurf.arpointcloudfindsurface.helpers.math.dot
import com.curvsurf.arpointcloudfindsurface.helpers.math.invoke
import com.curvsurf.arpointcloudfindsurface.helpers.math.length2
import com.curvsurf.arpointcloudfindsurface.helpers.math.normalize
import com.curvsurf.arpointcloudfindsurface.helpers.math.times
import com.curvsurf.arpointcloudfindsurface.helpers.math.xyz
import com.curvsurf.arpointcloudfindsurface.helpers.putVector3
import com.curvsurf.findsurface.FeatureType
import com.curvsurf.findsurface.FindSurface
import com.curvsurf.findsurface.SearchLevel
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), ARCoreAppGLRenderer, GLSurfaceViewHolder {

    enum class Transaction {
        AddPlane, AddSphere, AddCylinder
    }

    val viewModel: AppViewModel by viewModels()

    private var glSurfaceView: GLSurfaceView? = null
    override fun setGLSurfaceView(view: GLSurfaceView) {
        this.glSurfaceView = view
    }

    private val renderer = ContentRenderer(this)

    private lateinit var frameProvider: ARFrameProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frameProvider = ARFrameProvider(this)

        FindSurface.measurementAccuracy = 0.02f
        FindSurface.meanDistance = 0.04f
        FindSurface.radialExpansion = SearchLevel.Lv5
        FindSurface.lateralExtension = SearchLevel.Lv7
        FindSurface.setDebugCallback(FindSurface.Severity.Info, null) { errorCode, severity, api, cause, string, any ->
            val message = "$errorCode, $api, $cause, $string"
            when (severity) {
                FindSurface.Severity.Info -> Log.d("FindSurface", message)
                FindSurface.Severity.Warning -> Log.w("FindSurface", message)
                FindSurface.Severity.Error -> Log.e("FindSurface", message)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        UIEvent.Undo -> undoDetectingGeometry()
                        UIEvent.ClearGeometries -> clearGeometries()
                        UIEvent.ClearPointCloud -> clearPoints()
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.findSurfaceData.map { it.featureType }.collect { type ->
                    FindSurface.targetFeature = type
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            ContentView(
                renderer = this,
                viewModel = viewModel,
                viewHolder = this
            )
        }
    }

    override fun onPause() {
        super.onPause()
        frameProvider.onPause {
            glSurfaceView?.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            frameProvider.onResume(this@MainActivity) {
                glSurfaceView?.onResume()
            }
        } catch (e: Exception) {
            when (e) {
                is ARFrameProviderException -> {
                    e.message?.let { message ->

                        viewModel.showSnackbar(message)
                    }
                    Log.e(TAG, "Exception creating AR session", e)
                }
                is CameraNotAvailableException -> {
                    viewModel.showSnackbar("Camera not available. Please restart the app.")
                    Log.e(TAG, "Camera not available. Please restart the app.")
                    frameProvider.onDestroy()
                }
            }
            return
        }
    }

    override fun onDestroy() {
        frameProvider.onDestroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String?>,
        grantResults: IntArray,
        deviceId: Int
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)

        if (!CameraPermissionHelper.hasCameraPermission(this@MainActivity)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this@MainActivity)) {
                CameraPermissionHelper.launchPermissionSettings(this@MainActivity)
            }
            finish()
        }
    }

    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0

    override fun onSurfaceCreated() {
        renderer.onInit()
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        frameProvider.onSurfaceChanged(width, height)
        renderer.onResize(width, height)
    }

    private fun fetchARFrame(): Frame? {
        return try {
            frameProvider.frame
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            viewModel.showSnackbar("Camera not available. Plaese restart the app.")
            null
        }
    }

    // Motion Tracking Stabilization
    private val motionTrackingStabilizer = MotionTrackingStabilizer()

    private fun stabilizeMotionTracking(cameraPose: Pose, featureCount: Int) {
        if (motionTrackingStabilizer.status.isFinished) {
            return
        }

        if (viewModel.stabilizationData.value.status.isFinished) {
            motionTrackingStabilizer.finish()
            recording = true
            previewEnabled = true
            return
        }

        val oldStatus = motionTrackingStabilizer.status
        val newStatus = motionTrackingStabilizer.update(cameraPose, featureCount)
        viewModel.updateStabilizationData(motionTrackingStabilizer)
        if (oldStatus.isNotFinished && newStatus.isFinished) {
            recording = true
            previewEnabled = true
        }

        // NOTE: debug purpose only
//        renderer.showRawFeaturePoints = motionTrackingStabilizer.status.isNotFinished
    }

    // Point Collecting
    private val motionDetector = CameraMotionDetector()
    private val featureCompressor = FeatureCompressor(100000, 100)
    private var recording: Boolean
        get() = viewModel.recordingData.value.recording
        set(value) { viewModel.updateRecordingData(recording = value) }

    private var pointCloud: Array<Vector3>? = null
    private var pointBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(Float.SIZE_BYTES * 3 * 100_000)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun clearPoints() {
        viewModel.updateRecordingData(pointCount = 0)
        featureCompressor.clear()
        renderer.clearPointCloud()
    }

    private fun collectPoints(features: Array<Vector4>,
                              identifiers: IntArray,
                              viewMatrix: Matrix4,
                              cameraPose: Pose) {

        if (!recording || !motionDetector.hasCameraMovedEnough(cameraPose)) return

        val validIndices = MutableIntList(features.size)
        for (i in 0 until features.size) {
            val feature = features[i]
            val cpos = viewMatrix.times(feature.xyz, 1f)
            if (length2(cpos) >= 0.0625f) {
                validIndices.add(i)
            }
        }

        // Edge case fix: If no points pass the near-plane filter, return early instead of passing all noise
        if (validIndices.isEmpty()) return

        val validCount = validIndices.size
        val filteredFeatures = if (validCount == features.size) {
            features
        } else {
            Array(validCount) { idx -> features[validIndices[idx]] }
        }

        val filteredIdentifiers = if (validCount == identifiers.size) {
            identifiers
        } else {
            IntArray(validCount) { idx -> identifiers[validIndices[idx]] }
        }

        featureCompressor.append(filteredFeatures, filteredIdentifiers)

        if (featureCompressor.updated) {
            val compressed = featureCompressor.points
            renderer.updatePointCloud(compressed)
            pointCloud = compressed
            pointBuffer.rewind()
            for (point in compressed) {
                pointBuffer.putVector3(point)
            }
            pointBuffer.rewind()
            viewModel.updateRecordingData(pointCount = compressed.size)
            featureCompressor.updated = false
        }
    }

    private val viewMatrix = Matrix4.Identity.copy()
    private val projectionMatrix = Matrix4.Identity.copy()
    private val viewProjectionMatrix = Matrix4.Identity.copy()

    private fun fetchFeatures(frame: Frame): Pair<Array<Vector4>, IntArray> {
        val pointcloud = frame.acquirePointCloud()
        val featureBuffer = pointcloud.points
        renderer.setRawFeaturePoints(featureBuffer)

        featureBuffer.rewind()
        val floatCount = featureBuffer.remaining()
        val floatArray = FloatArray(floatCount)
        featureBuffer.get(floatArray, 0, floatCount)

        val pointCount = floatCount / 4
        val features = Array(pointCount) { i ->
            Vector4(array = floatArray, offset = i * 4)
        }

        val idBuffer = pointcloud.ids
        idBuffer.rewind()
        val identifierCount = idBuffer.remaining()
        val identifiers = IntArray(identifierCount)
        idBuffer.get(identifiers, 0, identifierCount)

        pointcloud.release()
        return features to identifiers
    }

    // FindSurface
    private var previewEnabled: Boolean
        get() = viewModel.findSurfaceData.value.previewEnabled
        set(value) = viewModel.updateFindSurfaceData(previewEnabled = value)
    private var hasToSaveOne: Boolean
        get() = viewModel.findSurfaceData.value.hasToSaveOne
        set(value) = viewModel.updateFindSurfaceData(hasToSaveOne = value)
    private var lastFound: FindSurface.Result? = null
    private var transactions: MutableList<Transaction> = mutableListOf()

    private fun pickPoint(
        points: Array<Vector3>,
        camera: Camera,
        hitDistance: Float?,
        hitPosition: Vector3?
    ): Pair<Int, Float>? {
        if (points.isEmpty()) return null

        // 1. First priority: Direct AR surface hit from frame hitTest
        if (hitPosition != null && hitDistance != null && hitDistance in 0.15f..8.0f) {
            var closestIdx = -1
            var minD2 = Float.MAX_VALUE
            for (i in points.indices) {
                val d2 = distance2(points[i], hitPosition)
                if (d2 < minD2) {
                    minD2 = d2
                    closestIdx = i
                }
            }
            // If closest point cloud point is within 25cm of AR hit:
            if (closestIdx >= 0 && minD2 <= 0.0625f) {
                return closestIdx to hitDistance
            }
        }

        // 2. Optical ray-casting along camera line of sight
        val cameraPosition = Vector3(camera.pose.translation)
        val cameraDirection = -normalize(Vector3(camera.pose.zAxis))
        val tanHalfFov = camera.imageIntrinsics.let { intrinsics ->
            val heightPx = intrinsics.imageDimensions[1].toFloat()
            val fy = intrinsics.focalLength[1]
            0.5f * heightPx / fy
        }
        val probeRadiusRatio = viewModel.radiusData.value.probeRadiusRatio
        val probeRadiusSlope = if (probeRadiusRatio > 0.01f) {
            tanHalfFov * probeRadiusRatio
        } else {
            tanHalfFov * 0.12f // standard reticle probe cone (~12% screen radius)
        }

        var minIndex = -1
        var minRadialRatio = Float.POSITIVE_INFINITY
        var chosenDepth = Float.POSITIVE_INFINITY

        // Secondary fallback cone (~18 degrees) to prevent flickering if points inside reticle are sparse
        var fallbackIndex = -1
        var fallbackMinAngleRatio = Float.POSITIVE_INFINITY
        var fallbackDepth = Float.POSITIVE_INFINITY

        for (index in points.indices) {
            val point = points[index]
            val PO = point - cameraPosition
            val t = dot(PO, cameraDirection)
            if (t <= 0.1f) continue

            val PO2 = dot(PO, PO)
            val r2 = PO2 - t * t
            if (r2 < 0f) continue

            val probeRadius = t * probeRadiusSlope
            val probeRadiusSquared = probeRadius * probeRadius

            if (r2 <= probeRadiusSquared) {
                val radialRatio = r2 / probeRadiusSquared
                if (radialRatio < minRadialRatio) {
                    minIndex = index
                    minRadialRatio = radialRatio
                    chosenDepth = t
                }
            } else {
                val angleRatio = r2 / (t * t)
                if (angleRatio <= 0.10f && angleRatio < fallbackMinAngleRatio) {
                    fallbackIndex = index
                    fallbackMinAngleRatio = angleRatio
                    fallbackDepth = t
                }
            }
        }

        return when {
            minIndex >= 0 -> minIndex to chosenDepth
            fallbackIndex >= 0 -> fallbackIndex to fallbackDepth
            hitDistance != null && hitDistance in 0.15f..8.0f -> {
                // Return closest point cloud point along camera distance
                var bestIdx = -1
                var minDiff = Float.MAX_VALUE
                for (i in points.indices) {
                    val d = distance(points[i], cameraPosition)
                    val diff = abs(d - hitDistance)
                    if (diff < minDiff && diff < 0.45f) {
                        minDiff = diff
                        bestIdx = i
                    }
                }
                if (bestIdx >= 0) bestIdx to hitDistance else null
            }
            else -> null
        }
    }

    private fun calculateLocalMeanDistance(
        seedIndex: Int,
        points: Array<Vector3>,
        seedRadius: Float,
        depth: Float
    ): Float {
        if (seedIndex in points.indices) {
            val seedPoint = points[seedIndex]
            val radiusSq = seedRadius * seedRadius
            val neighborDistances = mutableListOf<Float>()

            for (i in points.indices) {
                if (i == seedIndex) continue
                val d2 = distance2(points[i], seedPoint)
                if (d2 in 0.0001f..radiusSq) {
                    neighborDistances.add(sqrt(d2))
                }
            }

            if (neighborDistances.size >= 3) {
                neighborDistances.sort()
                val sampleCount = minOf(neighborDistances.size, 8)
                var sum = 0f
                for (k in 0 until sampleCount) {
                    sum += neighborDistances[k]
                }
                val avgDist = sum / sampleCount
                return (avgDist * 2.5f).coerceIn(0.02f, 0.08f)
            }
        }
        return (0.022f + 0.012f * depth).coerceIn(0.025f, 0.07f)
    }

    private fun addTransaction(transaction: Transaction) {
        val wasEmpty = transactions.isEmpty()
        transactions.add(transaction)
        if (wasEmpty && transactions.isNotEmpty()) {
            viewModel.updateFindSurfaceData(transactionIsEmpty = false)
        }
    }

    private val mutex = Mutex()
    private fun detectGeometries(frame: Frame, camera: Camera) {
        val pointCloud = pointCloud ?: return

        // Raycast / HitTest at screen center to get physical depth ground truth
        var hitDistance: Float? = null
        var hitPosition: Vector3? = null
        if (viewportWidth > 0 && viewportHeight > 0) {
            try {
                val hits = frame.hitTest(viewportWidth * 0.5f, viewportHeight * 0.5f)
                for (hit in hits) {
                    val dist = hit.distance
                    if (dist in 0.15f..10.0f) {
                        hitDistance = dist
                        hitPosition = Vector3(hit.hitPose.translation)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "hitTest failed: ${e.message}")
            }
        }

        val pickingResult = pickPoint(pointCloud, camera, hitDistance, hitPosition)
        renderer.updatePickedIndex(pickingResult?.first ?: -1)

        val activeDepth = pickingResult?.second ?: hitDistance ?: -1f

        if (!previewEnabled && !hasToSaveOne) {
            renderer.setPreviewNone()
            viewModel.updateFindSurfaceData(
                currentDepth = activeDepth,
                isSurfaceDetected = false,
                rmsErrorCm = 0f,
                inlierCount = 0
            )
            return
        }
        val hasToSaveOne = this.hasToSaveOne
        if (this.hasToSaveOne) { this.hasToSaveOne = false }

        if (pickingResult == null) {
            renderer.setPreviewNone()
            viewModel.updateFindSurfaceData(
                currentDepth = activeDepth,
                isSurfaceDetected = false,
                rmsErrorCm = 0f,
                inlierCount = 0
            )
            return
        }

        val (pickedIndex, pickedDepth) = pickingResult
        val tanHalfFov = camera.imageIntrinsics.let { intrinsics ->
            val heightPx = intrinsics.imageDimensions[1].toFloat()
            val fy = intrinsics.focalLength[1]
            0.5f * heightPx / fy
        }
        val featureType = viewModel.findSurfaceData.value.featureType
        val rawSeedRadius = tanHalfFov * viewModel.radiusData.value.seedRadiusRatio * pickedDepth
        val seedRadius = when (featureType) {
            FeatureType.Cylinder -> rawSeedRadius.coerceIn(0.04f, 0.22f)
            FeatureType.Sphere -> rawSeedRadius.coerceIn(0.04f, 0.28f)
            else -> rawSeedRadius.coerceIn(0.08f, 0.65f)
        }

        val currentPointCloud = pointCloud
        if (!mutex.tryLock()) return
        lifecycleScope.launch {
            try {
                val (result, inliers) = withContext(Dispatchers.Default) {
                    val localMeanDist = calculateLocalMeanDistance(pickedIndex, currentPointCloud, seedRadius, pickedDepth)
                    val nominalAccuracy = (0.012f + 0.008f * pickedDepth).coerceIn(0.012f, 0.030f)

                    FindSurface.measurementAccuracy = nominalAccuracy
                    FindSurface.meanDistance = localMeanDist
                    FindSurface.seedRadius = seedRadius
                    FindSurface.radialExpansion = SearchLevel.Lv5
                    FindSurface.lateralExtension = SearchLevel.Lv7
                    FindSurface.setPointCloud(pointBuffer, 0, currentPointCloud.size)
                    pointBuffer.rewind()
                    FindSurface.seedIndex = pickedIndex

                    var computedResult = FindSurface.findSurface(featureType)

                    // Adaptive relaxation pass if initial pass had slight noise
                    if (computedResult.featureType == FeatureType.None) {
                        FindSurface.measurementAccuracy = (nominalAccuracy * 1.6f).coerceAtMost(0.045f)
                        FindSurface.meanDistance = (localMeanDist * 1.3f).coerceAtMost(0.085f)
                        computedResult = FindSurface.findSurface(featureType)
                    }

                    val lastFoundObj = this@MainActivity.lastFound
                    if (computedResult.featureType == FeatureType.None && hasToSaveOne && lastFoundObj != null) {
                        computedResult = lastFoundObj
                    }

                    val computedInliers: Array<Vector3> = if (computedResult.featureType != FeatureType.None) {
                        val flags = FindSurface.getInlierFlags()
                        val minLen = minOf(flags.size, currentPointCloud.size)
                        var inlierCount = 0
                        for (i in 0 until minLen) {
                            if (flags[i]) inlierCount++
                        }
                        val resultArr = Array(inlierCount) { Vector3() }
                        var writeIdx = 0
                        for (i in 0 until minLen) {
                            if (flags[i]) {
                                resultArr[writeIdx++] = currentPointCloud[i]
                            }
                        }
                        resultArr
                    } else emptyArray()

                    computedResult to computedInliers
                }

                if (result.featureType != FeatureType.None) {
                    viewModel.updateFindSurfaceData(
                        currentDepth = pickedDepth,
                        isSurfaceDetected = true,
                        rmsErrorCm = result.rmsError * 100f,
                        inlierCount = inliers.size
                    )
                } else {
                    viewModel.updateFindSurfaceData(
                        currentDepth = pickedDepth,
                        isSurfaceDetected = false,
                        rmsErrorCm = 0f,
                        inlierCount = 0
                    )
                }

                when (result.featureType) {
                    FeatureType.Plane -> {
                        val plane = GeometryObject.Plane.from(result)
                        if (plane != null) {
                            if (hasToSaveOne) {
                                showToast("Captured plane!\n(rms error: %.1f cm)", result.rmsError * 100f)
                                glSurfaceView?.queueEvent { renderer.appendPlane(plane, inliers) }
                                addTransaction(Transaction.AddPlane)
                                this@MainActivity.lastFound = null
                            } else {
                                glSurfaceView?.queueEvent { renderer.updatePreview(plane) }
                            }
                        }
                    }

                    FeatureType.Sphere -> {
                        val sphere = GeometryObject.Sphere.from(result)
                        if (sphere != null) {
                            if (hasToSaveOne) {
                                showToast("Captured sphere!\n(rms error: %.1f cm)", result.rmsError * 100f)
                                glSurfaceView?.queueEvent { renderer.appendSphere(sphere, inliers) }
                                addTransaction(Transaction.AddSphere)
                                this@MainActivity.lastFound = null
                            } else {
                                glSurfaceView?.queueEvent { renderer.updatePreview(sphere) }
                            }
                        }
                    }

                    FeatureType.Cylinder -> {
                        val cylinder = GeometryObject.Cylinder.from(result)
                        if (cylinder != null) {
                            if (hasToSaveOne) {
                                showToast("Captured cylinder!\n(rms error: %.1f cm)", result.rmsError * 100f)
                                glSurfaceView?.queueEvent { renderer.appendCylinder(cylinder, inliers) }
                                addTransaction(Transaction.AddCylinder)
                                this@MainActivity.lastFound = null
                            } else {
                                glSurfaceView?.queueEvent { renderer.updatePreview(cylinder) }
                            }
                        }
                    }

                    else -> {
                        if (hasToSaveOne) {
                            showToast("Nothing captured, try again.\n(rms error: %.1f cm)", result.rmsError * 100f)
                        } else {
                            glSurfaceView?.queueEvent { renderer.setPreviewNone() }
                        }
                        this@MainActivity.lastFound = null
                    }
                }
            } finally {
                mutex.unlock()
            }
        }
    }

    fun undoDetectingGeometry() {
        if (transactions.isEmpty()) return
        val t = transactions.removeAt(transactions.lastIndex)
        glSurfaceView?.queueEvent {
            when (t) {
                Transaction.AddPlane -> renderer.removeLastPlane()
                Transaction.AddSphere -> renderer.removeLastSphere()
                Transaction.AddCylinder -> renderer.removeLastCylinder()
            }
            renderer.removeLastInlierPoints()
        }
        if (transactions.isEmpty()) {
            viewModel.updateFindSurfaceData(transactionIsEmpty = true)
        }
    }

    fun clearGeometries() {
        glSurfaceView?.queueEvent {
            renderer.clearGeometries()
        }
        transactions.clear()
    }

    override fun onDrawFrame() {
        frameProvider.setCameraTexture(renderer.cameraTextureID)
        val frame = fetchARFrame() ?: return
        val camera = frame.camera
        camera.getViewMatrix(viewMatrix.data, 0)
        camera.getProjectionMatrix(projectionMatrix.data, 0, 0.01f, 65f)
        Matrix.multiplyMM(viewProjectionMatrix.data, 0, projectionMatrix.data, 0, viewMatrix.data, 0)
        renderer.setTransform(
            viewMatrixArray = viewMatrix.data,
            projectionMatrixArray = projectionMatrix.data,
            viewProjectionMatrixArray = viewProjectionMatrix.data
        )

        val (features, identifiers) = fetchFeatures(frame)

        val featureCount = features.size
        val cameraPose = camera.displayOrientedPose ?: camera.pose

        stabilizeMotionTracking(cameraPose, featureCount)

        collectPoints(features, identifiers, viewMatrix, cameraPose)
        detectGeometries(frame, camera)

        renderer.onDrawFrame(frame)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(applicationContext, message, duration).show()
    }

    private fun showToast(format: String,
                          vararg args: Any?,
                          locale: Locale = Locale.US,
                          duration: Int = Toast.LENGTH_SHORT) {
        val text = String.format(locale, format, *args)
        Toast.makeText(applicationContext, text, duration).show()
    }
}