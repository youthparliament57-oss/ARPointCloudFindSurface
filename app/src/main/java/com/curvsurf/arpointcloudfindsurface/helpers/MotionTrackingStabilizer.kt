package com.curvsurf.arpointcloudfindsurface.helpers

import androidx.xr.runtime.math.Vector3
import com.curvsurf.arpointcloudfindsurface.helpers.math.distance
import com.curvsurf.arpointcloudfindsurface.helpers.math.invoke
import com.google.ar.core.Pose
import kotlin.math.max

class MotionTrackingStabilizer(
    val movingDistance: Float = 0.5f,
    val sensorDistance: Float = 0.10f
) {
    private var position: Vector3 = Vector3()
    private var distanceRemaining: Float = movingDistance
    private var isInitialized: Boolean = false
    private var stableTrackingFrames: Int = 0

    val progress: Float
        get() {
            val distProg = ((movingDistance - distanceRemaining) / movingDistance).coerceIn(0f, 1f)
            val frameProg = (stableTrackingFrames / 45f).coerceIn(0f, 1f)
            return maxOf(distProg, frameProg)
        }

    enum class Status {
        NotStarted, Working, Finished;

        val isNotFinished: Boolean
            get() = this != Finished

        val isFinished: Boolean
            get() = this == Finished

        val isWorking: Boolean
            get() = this == Working

        val hasNotStarted: Boolean
            get() = this == NotStarted
    }
    var status: Status = Status.NotStarted
        private set
    private var frameCountNotEnoughFeaturesDetected: Int = 0
    val notEnoughFeatures: Boolean
        get() = frameCountNotEnoughFeaturesDetected > 90

    fun finish() {
        status = Status.Finished
        distanceRemaining = 0f
        stableTrackingFrames = 45
    }

    fun update(cameraPose: Pose, featureCount: Int): Status {
        if (status == Status.Finished) return status
        if (status == Status.NotStarted) status = Status.Working

        if (featureCount < 5) {
            ++frameCountNotEnoughFeaturesDetected
        } else {
            frameCountNotEnoughFeaturesDetected = 0
            stableTrackingFrames++
        }

        if (hasEnoughScans(cameraPose, featureCount) || stableTrackingFrames >= 45) {
            status = Status.Finished
        }
        return status
    }

    private fun hasEnoughScans(cameraPose: Pose, featureCount: Int): Boolean {
        if (distanceRemaining <= 0f) return true

        val p = Vector3(cameraPose.transformPoint(floatArrayOf(0f, 0f, -sensorDistance)))
        if (!isInitialized) {
            position = p
            isInitialized = true
            return false
        }
        val old = position
        position = p

        if (featureCount >= 5) {
            val d = distance(p, old)
            if (d > 0.001f) {
                distanceRemaining = max(distanceRemaining - d, 0f)
            }
        }
        return distanceRemaining <= 0f
    }
}