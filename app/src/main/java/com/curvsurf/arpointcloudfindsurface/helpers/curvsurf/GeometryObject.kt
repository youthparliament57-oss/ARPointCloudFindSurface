package com.curvsurf.arpointcloudfindsurface.helpers.curvsurf

import androidx.xr.runtime.math.Matrix4
import androidx.xr.runtime.math.Vector3
import com.curvsurf.arpointcloudfindsurface.helpers.math.cross
import com.curvsurf.arpointcloudfindsurface.helpers.math.distance
import com.curvsurf.arpointcloudfindsurface.helpers.math.dot
import com.curvsurf.arpointcloudfindsurface.helpers.math.fromY
import com.curvsurf.arpointcloudfindsurface.helpers.math.invoke
import com.curvsurf.arpointcloudfindsurface.helpers.math.length
import com.curvsurf.arpointcloudfindsurface.helpers.math.length2
import com.curvsurf.arpointcloudfindsurface.helpers.math.normalize
import com.curvsurf.arpointcloudfindsurface.helpers.math.position
import com.curvsurf.arpointcloudfindsurface.helpers.math.times
import com.curvsurf.arpointcloudfindsurface.helpers.math.transform
import com.curvsurf.arpointcloudfindsurface.helpers.math.xAxis
import com.curvsurf.arpointcloudfindsurface.helpers.math.yAxis
import com.curvsurf.arpointcloudfindsurface.helpers.math.zAxis
import com.curvsurf.findsurface.FeatureType
import com.curvsurf.findsurface.FindSurface
import kotlin.math.abs

sealed class GeometryObject(
    var extrinsics: Matrix4
) {
    var xAxis: Vector3
        get() = extrinsics.xAxis
        set(value) { extrinsics.xAxis = value }

    var yAxis: Vector3
        get() = extrinsics.yAxis
        set(value) { extrinsics.yAxis = value }

    var zAxis: Vector3
        get() = extrinsics.zAxis
        set(value) { extrinsics.zAxis = value }

    var position: Vector3
        get() = extrinsics.position
        set(value) { extrinsics.position = value }

    val transform: Matrix4
        get() = Matrix4.transform(xAxis, yAxis, zAxis, position)

    class Plane(
        var width: Float,
        var height: Float,
        extrinsics: Matrix4
    ): GeometryObject(extrinsics) {

        var normal: Vector3
            get() = zAxis
            set(value) { zAxis = value }

        var center: Vector3
            get() = position
            set(value) { position = value }

        val right: Vector3
            get() = center + 0.5f * width * xAxis

        val left: Vector3
            get() = center - 0.5f * width * xAxis

        val top: Vector3
            get() = center + 0.5f * height * yAxis

        val bottom: Vector3
            get() = center - 0.5f * height * yAxis

        val bottomLeft: Vector3
            get() = center - 0.5f * (width * xAxis + height * yAxis)

        val topLeft: Vector3
            get() = center - 0.5f * (width * xAxis - height * yAxis)

        val topRight: Vector3
            get() = center + 0.5f * (width * xAxis + height * yAxis)

        val bottomRight: Vector3
            get() = center + 0.5f * (width * xAxis - height * yAxis)

        /// Rotates its model space, so that the plane's `normal` points to the camera
        /// and `yAxis` is as close as possible to the `upwardDirection`.
        fun align(cameraPosition: Vector3, upwardDirection: Vector3) {

            val len2 = length2(upwardDirection)
            if (len2 == 0f) return align(cameraPosition)
            val up = normalize(upwardDirection)

            var x = this.xAxis
            val y = this.yAxis
            var z = this.zAxis
            val center = this.position

            val toCam = cameraPosition - center
            val planeDist = dot(z, toCam)
            if (planeDist < 0) {
                x = -x
                z = -z
            }

            val dotY = dot(y, up)
            val dotX = dot(x, up)

            var bestDot = dotY
            var bestX = x
            var bestY = y
            var bestW = this.width
            var bestH = this.height

            if (-dotY > bestDot) {
                bestDot = -dotY
                bestX = -x
                bestY = -y
            }

            if (dotX > bestDot) {
                bestDot = dotX
                bestX = -y
                bestY = x
                bestW = this.height
                bestH = this.width
            }

            if (-dotX > bestDot) {
                bestDot = -dotX
                bestX = y
                bestY = -x
                bestW = this.height
                bestH = this.width
            }

            this.xAxis = bestX
            this.yAxis = bestY
            this.zAxis = z
            this.width = bestW
            this.height = bestH
        }

        fun align(cameraPosition: Vector3) {

            var x = this.xAxis
            val y = this.yAxis
            var z = this.zAxis
            val center = this.position

            val toCam = cameraPosition - center
            val planeDist = dot(z, toCam)
            if (planeDist < 0) {
                x = -x
                z = -z
            }

            val dotY = y.y
            val dotX = x.y

            var bestDot = dotY
            var bestX = x
            var bestY = y
            var bestW = this.width
            var bestH = this.height

            if (-dotY > bestDot) {
                bestDot = -dotY
                bestX = -x
                bestY = -y
            }

            if (dotX > bestDot) {
                bestDot = dotX
                bestX = -y
                bestY = x
                bestW = this.height
                bestH = this.width
            }

            if (-dotX > bestDot) {
                bestDot = -dotX
                bestX = y
                bestY = -x
                bestW = this.height
                bestH = this.width
            }

            this.xAxis = bestX
            this.yAxis = bestY
            this.zAxis = z
            this.width = bestW
            this.height = bestH
        }

        companion object {
            fun from(result: FindSurface.Result): Plane? {
                if (result.featureType != FeatureType.Plane) return null
                val dst = FloatArray(3)

                val bottomLeft = Vector3(result.planeLowerLeftCorner(dst))
                val bottomRight = Vector3(result.planeLowerRightCorner(dst))
                val topRight = Vector3(result.planeUpperRightCorner(dst))
                val topLeft = Vector3(result.planeUpperLeftCorner(dst))

                val right = (bottomRight + topRight) * 0.5f
                val left = (bottomLeft + topLeft) * 0.5f
                val top = (topLeft + topRight) * 0.5f
                val bottom = (bottomLeft + bottomRight) * 0.5f

                val horizontal = right - left
                val vertical = top - bottom

                val width = length(horizontal)
                val height = length(vertical)

                val xAxis = normalize(horizontal)
                val yAxis = normalize(vertical)
                val zAxis = normalize(cross(xAxis, yAxis))

                val position = (bottomLeft + bottomRight + topRight + topLeft) * 0.25f
                val extrinsics = Matrix4(xAxis, yAxis, zAxis, position)

                return Plane(width, height, extrinsics)
            }
        }
    }

    class Sphere(
        var radius: Float,
        extrinsics: Matrix4
    ): GeometryObject(extrinsics) {

        var center: Vector3
            get() = position
            set(value) { position = value }

        companion object {
            fun from(result: FindSurface.Result): Sphere? {
                if (result.featureType != FeatureType.Sphere) return null
                val dst = FloatArray(3)

                val center = Vector3(result.sphereCenter(dst))
                val radius = result.sphereRadius
                val extrinsics = Matrix4.fromTranslation(center)

                return Sphere(radius, extrinsics)
            }
        }
    }

    class Cylinder(
        var height: Float,
        var radius: Float,
        extrinsics: Matrix4
    ): GeometryObject(extrinsics) {

        var axis: Vector3
            get() = yAxis
            set(value) { yAxis = value }

        var center: Vector3
            get() = position
            set(value) { position = value }

        val top: Vector3
            get() = center + 0.5f * height * axis

        val bottom: Vector3
            get() = center - 0.5f * height * axis

        fun align(upwardDirection: Vector3) {
            if (dot(axis, upwardDirection) < 0) {
                xAxis *= -1f
                axis *= -1f
            }
        }

        fun align() {
            if (axis.y < 0) {
                xAxis *= -1f
                axis *= -1f
            }
        }

        companion object {
            fun from(result: FindSurface.Result): Cylinder? {
                if (result.featureType != FeatureType.Cylinder) return null
                val dst = FloatArray(3)

                val top = Vector3(result.cylinderBottomCenter(dst))
                val bottom = Vector3(result.cylinderTopCenter(dst))
                val radius = result.cylinderRadius

                val yAxis = normalize(top - bottom)
                val height = distance(top, bottom)
                val position = (top + bottom) * 0.5f
                val extrinsics = Matrix4.fromY(yAxis, position)

                return Cylinder(height, radius, extrinsics)
            }
        }
    }

    class Cone(
        var height: Float,
        var topRadius: Float,
        var bottomRadius: Float,
        extrinsics: Matrix4
    ): GeometryObject(extrinsics) {

        var axis: Vector3
            get() = yAxis
            set(value) { yAxis = value }

        var center: Vector3
            get() = position
            set(value) { position = value }

        val top: Vector3
            get() = center + 0.5f * height * axis

        val bottom: Vector3
            get() = center - 0.5f * height * axis

        val vertex: Vector3
            get() = top + ((topRadius * height) / abs(bottomRadius - topRadius)) * axis

        companion object {
            fun from(result: FindSurface.Result): Cone? {
                if (result.featureType != FeatureType.Cone) return null
                val dst = FloatArray(3)

                val bottom = Vector3(result.coneBottomCenter(dst))
                val top = Vector3(result.coneTopCenter(dst))
                val bottomRadius = result.coneBottomRadius
                val topRadius = result.coneTopRadius

                val yAxis = normalize(top - bottom)
                val height = distance(top, bottom)
                val position = (top + bottom) * 0.5f
                val extrinsics = Matrix4.fromY(yAxis, position)

                return Cone(height, topRadius, bottomRadius, extrinsics)
            }
        }
    }

    class Torus(
        var meanRadius: Float,
        var tubeRadius: Float,
        extrinsics: Matrix4
    ): GeometryObject(extrinsics) {

        var axis: Vector3
            get() = yAxis
            set(value) { yAxis = value }

        var center: Vector3
            get() = position
            set(value) { position = value }

        fun align(upwardDirection: Vector3) {
            if (dot(axis, upwardDirection) < 0) {
                xAxis *= -1f
                axis *= -1f
            }
        }

        fun align() {
            if (axis.y < 0) {
                xAxis *= -1f
                axis *= -1f
            }
        }

        companion object {
            fun from(result: FindSurface.Result): Torus? {
                if (result.featureType != FeatureType.Torus) return null
                val dst = FloatArray(3)

                val position = Vector3(result.torusCenter(dst))
                val yAxis = Vector3(result.torusAxis(dst))
                val meanRadius = result.torusMeanRadius
                val tubeRadius = result.torusTubeRadius
                val extrinsics = Matrix4.fromY(yAxis, position)

                return Torus(meanRadius, tubeRadius, extrinsics)
            }
        }
    }
}