package com.curvsurf.arpointcloudfindsurface.helpers

import androidx.xr.runtime.math.Vector3
import com.curvsurf.arpointcloudfindsurface.helpers.math.distance
import com.curvsurf.arpointcloudfindsurface.helpers.math.dot
import com.curvsurf.arpointcloudfindsurface.helpers.math.invoke
import com.google.ar.core.Pose
import kotlin.math.cos

class CameraMotionDetector(
    val minDistance: Float = 0.03f,
    minAngleRadian: Float = (3.0 * Math.PI / 180.0).toFloat()
) {
    private var position: Vector3 = Vector3()
    private var direction: Vector3 = Vector3()
    private var isInitialized: Boolean = false

    val minCosineAngle: Float = cos(minAngleRadian.toDouble()).toFloat()

    fun hasCameraMovedEnough(cameraPose: Pose): Boolean {
        val position = Vector3(cameraPose.translation)
        val direction = Vector3(cameraPose.zAxis)

        if (!isInitialized) {
            this.position = position
            this.direction = direction
            isInitialized = true
            return true
        }

        val positionChanged = distance(position, this.position) > minDistance
        val directionChanged = dot(direction, this.direction) < minCosineAngle

        if (positionChanged || directionChanged) {
            this.position = position
            this.direction = direction
            return true
        } else {
            return false
        }
    }
}