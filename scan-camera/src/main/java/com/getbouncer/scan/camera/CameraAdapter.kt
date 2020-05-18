package com.getbouncer.scan.camera

import android.graphics.PointF
import android.util.Size
import android.view.Surface
import androidx.annotation.IntDef
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.max
import kotlin.math.min

/**
 * Valid integer rotation values.
 */
@IntDef(
    Surface.ROTATION_0,
    Surface.ROTATION_90,
    Surface.ROTATION_180,
    Surface.ROTATION_270
)
@Retention(AnnotationRetention.SOURCE)
private annotation class RotationValue

abstract class CameraAdapter : LifecycleObserver {

    companion object {

        /**
         * Determine how much to rotate the image from the camera given the orientation of the
         * display and the orientation of the camera sensor.
         *
         * @param displayOrientation: The enum value of the display rotation (e.g. Surface.ROTATION_0)
         * @param sensorRotationDegrees: The rotation of the sensor in degrees
         */
        internal fun calculateImageRotationDegrees(
            @RotationValue displayOrientation: Int,
            sensorRotationDegrees: Int
        ) = ((when (displayOrientation) {
            Surface.ROTATION_0 -> sensorRotationDegrees
            Surface.ROTATION_90 -> sensorRotationDegrees - 90
            Surface.ROTATION_180 -> sensorRotationDegrees - 180
            Surface.ROTATION_270 -> sensorRotationDegrees - 270
            else -> 0
        } % 360) + 360) % 360

        /**
         * Convert a size on the screen to a resolution.
         */
        internal fun sizeToResolution(size: Size): Size = Size(
            /* width */ max(size.width, size.height),
            /* height */ min(size.width, size.height)
        )

        /**
         * Convert a resolution to a size on the screen.
         */
        internal fun resolutionToSize(
            resolution: Size,
            @RotationValue displayRotation: Int,
            sensorRotationDegrees: Int
        ) = if (areScreenAndSensorPerpendicular(displayRotation, sensorRotationDegrees)) {
            Size(resolution.height, resolution.width)
        } else {
            resolution
        }

        /**
         * Determines if the dimensions are swapped given the phone's current rotation.
         *
         * @param displayRotation The current rotation of the display
         *
         * @return true if the dimensions are swapped, false otherwise.
         */
        private fun areScreenAndSensorPerpendicular(
            @RotationValue displayRotation: Int,
            sensorRotationDegrees: Int
        ) = when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                sensorRotationDegrees == 90 || sensorRotationDegrees == 270
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                sensorRotationDegrees == 0 || sensorRotationDegrees == 180
            }
            else -> {
                false
            }
        }
    }

    /**
     * Bind this camera manager to a lifecycle.
     */
    open fun bindToLifecycle(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    /**
     * Execute a task with flash support.
     */
    abstract fun withFlashSupport(task: (Boolean) -> Unit)

    /**
     * Turn the camera torch on or off.
     */
    abstract fun setTorchState(on: Boolean)

    /**
     * Determine if the torch is currently on.
     */
    abstract fun isTorchOn(): Boolean

    /**
     * Set the focus on a particular point on the screen.
     */
    abstract fun setFocus(point: PointF)
}

interface CameraErrorListener {

    fun onCameraOpenError(cause: Throwable?)

    fun onCameraAccessError(cause: Throwable?)

    fun onCameraUnsupportedError(cause: Throwable?)
}
