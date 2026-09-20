package com.curvsurf.arpointcloudfindsurface.helpers.arcore

import android.app.Activity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

sealed class ARFrameProviderException(message: String, cause: Exception): Exception(message, cause) {

    class ARCoreNotInstalledException(cause: Exception) :
        ARFrameProviderException("Please install ARCore", cause)

    class ARCoreNeedsUpdateException(cause: Exception) :
        ARFrameProviderException("Please update ARCore", cause)

    class AppNeedsUpdateException(cause: Exception) :
        ARFrameProviderException("Please update the app or contact to the developer", cause)

    class DeviceNotCompatibleException(cause: Exception) :
        ARFrameProviderException("This device does not support AR", cause)

    class CameraPermissionNotGrantedException(cause: Exception) :
        ARFrameProviderException("Camera permission not granted", cause)

    class FailedToCreateARSessionException(cause: Exception) :
        ARFrameProviderException("Failed to create AR session", cause)
}

class ARFrameProvider(activity: Activity) {

    private var session: Session? = null
    private var installRequested: Boolean = false

    private val displayRotationHelper = DisplayRotationHelper(activity)
    private val trackingStateHelper = TrackingStateHelper(activity)

    private fun create(applicationActivity: Activity) {
        try {
            val instance = ArCoreApk.getInstance()
            val availability = instance.checkAvailability(applicationActivity)

            if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
                val installStatus = instance.requestInstall(applicationActivity, !installRequested)

                if (installStatus == ArCoreApk.InstallStatus.INSTALLED) installRequested = true
            }

            if (!CameraPermissionHelper.hasCameraPermission(applicationActivity)) {
                CameraPermissionHelper.requestCameraPermission(applicationActivity)
                return
            }

            session = Session(applicationActivity)
        } catch (cause: Exception) {
            when (cause) {
                is UnavailableArcoreNotInstalledException,
                is UnavailableUserDeclinedInstallationException-> throw ARFrameProviderException.ARCoreNotInstalledException(cause)
                is UnavailableApkTooOldException -> throw ARFrameProviderException.ARCoreNeedsUpdateException(cause)
                is UnavailableSdkTooOldException -> throw ARFrameProviderException.AppNeedsUpdateException(cause)
                is UnavailableDeviceNotCompatibleException -> throw ARFrameProviderException.DeviceNotCompatibleException(cause)
                is SecurityException -> throw ARFrameProviderException.CameraPermissionNotGrantedException(cause)
                else -> throw ARFrameProviderException.FailedToCreateARSessionException(cause)
            }
        }
    }

    private fun configure() {
        val session = session ?: return

        val config = session.config
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        config.focusMode = Config.FocusMode.AUTO
        val imageStabilizationMode = Config.ImageStabilizationMode.EIS
        if (session.isImageStabilizationModeSupported(imageStabilizationMode)) {
            config.imageStabilizationMode = imageStabilizationMode
        }
        config.depthMode = when {
            session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC
            session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY
            else -> Config.DepthMode.DISABLED
        }
        session.configure(config)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
    }

    private var textureID: Int = -1
    fun setCameraTexture(textureID: Int) {
        require(textureID > 0)
        val session = session ?: return
        if (this.textureID != textureID) {
            session.setCameraTextureName(textureID)
            this.textureID = textureID
        }
    }

    fun onPause(beforePause: () -> Unit) {
        val session = session ?: return
        displayRotationHelper.onPause()
        beforePause()
        session.pause()
    }

    fun onResume(activity: Activity, afterResume: () -> Unit) {
        if (session == null) create(activity)
        val session = session ?: return
        configure()
        session.resume()
        afterResume()
        displayRotationHelper.onResume()
    }

    fun onDestroy() {
        session?.close()
        session = null
    }

    val frame: Frame?
        get() {
            val session = session ?: return null

            displayRotationHelper.updateSessionIfNeeded(session)

            val frame = session.update()
            trackingStateHelper.updateKeepScreenOnFlag(frame.camera.trackingState)
            return frame
        }
}