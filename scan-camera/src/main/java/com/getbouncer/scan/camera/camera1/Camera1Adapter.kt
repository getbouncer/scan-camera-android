@file:Suppress("deprecation")
package com.getbouncer.scan.camera.camera1

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PointF
import android.hardware.Camera
import android.hardware.Camera.AutoFocusCallback
import android.hardware.Camera.PreviewCallback
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.OnLifecycleEvent
import com.getbouncer.scan.camera.CameraAdapter
import com.getbouncer.scan.camera.CameraErrorListener
import com.getbouncer.scan.framework.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val ASPECT_TOLERANCE = 0.2

private val MAXIMUM_RESOLUTION = Size(1920, 1080)

class Camera1Adapter(
    private val activity: Activity,
    private val previewView: FrameLayout,
    private val minimumResolution: Size,
    private val imageReceiver: ImageReceiver,
    private val cameraErrorListener: CameraErrorListener
) : CameraAdapter(), PreviewCallback {

    private var mCamera: Camera? = null
    private var cameraPreview: CameraPreview? = null
    private var mRotation = 0

    override fun withFlashSupport(task: (Boolean) -> Unit) {
        // TODO("Not yet implemented")
        task(false)
    }

    override fun setTorchState(on: Boolean) {
//        TODO("Not yet implemented")
    }

    override fun isTorchOn(): Boolean {
//        TODO("Not yet implemented")
        return false
    }

    override fun setFocus(point: PointF) {
//        TODO("Not yet implemented")
    }

    override fun onPreviewFrame(bytes: ByteArray, camera: Camera) {
        imageReceiver.receiveImage(bytes, Size(camera.parameters.previewSize.width, camera.parameters.previewSize.height), mRotation, camera)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onPause() {
        mCamera?.stopPreview()
        mCamera?.setPreviewCallbackWithBuffer(null)
        mCamera?.release()
        mCamera = null

        val preview = cameraPreview
        if (preview != null) {
            preview.holder.removeCallback(preview)
            cameraPreview = null
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onResume() {
        GlobalScope.launch(Dispatchers.Default) {
            try {
                var camera: Camera? = null
                try {
                    camera = Camera.open()
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) { cameraErrorListener.onCameraOpenError(t) }
                }
                withContext(Dispatchers.Main) { onCameraOpen(camera) }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { cameraErrorListener.onCameraOpenError(t) }
            }
        }
    }

    private fun setCameraParameters(
        camera: Camera?,
        parameters: Camera.Parameters
    ) {
        try {
            camera?.parameters = parameters
        } catch (t: Throwable) {
            cameraErrorListener.onCameraAccessError(t)
        }
    }

    private fun startCameraPreview() {
        startCameraPreviewInternal(0, 5, null)
    }

    private fun startCameraPreviewInternal(
        attemptNumber: Int,
        maxAttempts: Int,
        previousThrowable: Throwable?
    ) {
        if (attemptNumber >= maxAttempts) {
            Log.e(Config.logTag, "Unable to start camera preview", previousThrowable)
            return
        }
        try {
            mCamera?.startPreview()
        } catch (t: Throwable) {
            Log.w(Config.logTag, "Could not start camera preview, retrying", t)
            Handler().postDelayed(
                { startCameraPreviewInternal(attemptNumber + 1, maxAttempts, t) },
                500
            )
        }
    }

    private fun onCameraOpen(camera: Camera?) {
        if (camera == null) {
            val preview = cameraPreview
            if (preview != null) {
                preview.holder.removeCallback(preview)
            }
            cameraErrorListener.onCameraOpenError(null)
        } else {
            mCamera = camera
            setCameraDisplayOrientation(activity, Camera.CameraInfo.CAMERA_FACING_BACK)
            setCameraPreviewFrame()
            // Create our Preview view and set it as the content of our activity.
            cameraPreview = CameraPreview(activity, this)
            previewView.removeAllViews()
            previewView.addView(cameraPreview)
        }
    }

    private fun setCameraPreviewFrame() {
        val camera = mCamera ?: return
        val format = ImageFormat.NV21
        val parameters: Camera.Parameters = camera.parameters
        parameters.previewFormat = format
        val displayMetrics = DisplayMetrics()
        activity.windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val displayWidth = max(displayMetrics.heightPixels, displayMetrics.widthPixels)
        val displayHeight = min(displayMetrics.heightPixels, displayMetrics.widthPixels)
        val height: Int = minimumResolution.height
        val width = displayWidth * height / displayHeight
        val previewSize = getOptimalPreviewSize(
            parameters.supportedPreviewSizes,
            width, height
        )
        if (previewSize != null) {
            parameters.setPreviewSize(previewSize.width, previewSize.height)
        }

        setCameraParameters(camera, parameters)
    }

    private fun getOptimalPreviewSize(
        sizes: List<Camera.Size>?,
        w: Int,
        h: Int
    ): Camera.Size? {
        val targetRatio = w.toDouble() / h
        if (sizes == null) {
            return null
        }
        var optimalSize: Camera.Size? = null

        // Find the smallest size that fits our tolerance and is at least as big as our target
        // height
        for (size in sizes) {
            val ratio = size.width.toDouble() / size.height
            if (abs(ratio - targetRatio) <= ASPECT_TOLERANCE) {
                if (size.height >= h) {
                    optimalSize = size
                }
            }
        }

        // Find the closest ratio that is still taller than our target height
        if (optimalSize == null) {
            var minDiffRatio = Double.MAX_VALUE
            for (size in sizes) {
                val ratio = size.width.toDouble() / size.height
                val ratioDiff = abs(ratio - targetRatio)
                if (size.height >= h && ratioDiff <= minDiffRatio && size.height <= MAXIMUM_RESOLUTION.height && size.width <= MAXIMUM_RESOLUTION.width) {
                    optimalSize = size
                    minDiffRatio = ratioDiff
                }
            }
        }
        if (optimalSize == null) {
            // Find the smallest size that is at least as big as our target height
            for (size in sizes) {
                if (size.height >= h) {
                    optimalSize = size
                }
            }
        }

        return optimalSize
    }

    private fun setCameraDisplayOrientation(activity: Activity, cameraId: Int) {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        val rotation = activity.windowManager.defaultDisplay.rotation
        var degrees = 0
        when (rotation) {
            Surface.ROTATION_0 -> degrees = 0
            Surface.ROTATION_90 -> degrees = 90
            Surface.ROTATION_180 -> degrees = 180
            Surface.ROTATION_270 -> degrees = 270
        }
        var result: Int
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360
            result = (360 - result) % 360 // compensate for the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360
        }
        try {
            mCamera!!.stopPreview()
        } catch (e: java.lang.Exception) {
            // preview was already stopped
        }
        try {
            mCamera!!.setDisplayOrientation(result)
        } catch (e: java.lang.Exception) {
            Log.d("Bouncer", "Could not set display orientation", e)
        }
        startCameraPreview()
        mRotation = result
    }

    /** A basic Camera preview class  */
    @SuppressLint("ViewConstructor")
    private inner class CameraPreview(
        context: Context,
        private val mPreviewCallback: PreviewCallback
    ): SurfaceView(context), AutoFocusCallback, SurfaceHolder.Callback {

        override fun onAutoFocus(success: Boolean, camera: Camera) { }

        /**
         * The Surface has been created, now tell the camera where to draw the preview.
         */
        override fun surfaceCreated(holder: SurfaceHolder) {
            try {
                mCamera?.setPreviewDisplay(holder)
                mCamera?.setPreviewCallbackWithBuffer(mPreviewCallback)
                startCameraPreview()
                //                mCamera.setDisplayOrientation(90);
            } catch (e: IOException) {
                Log.d(
                    "CameraCaptureActivity",
                    "Error setting camera preview: " + e.message
                )
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            // empty. Take care of releasing the Camera preview in your activity.
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            w: Int,
            h: Int
        ) {
            // If your preview can change or rotate, take care of those events here.
            // Make sure to stop the preview before resizing or reformatting it.
            if (holder.surface == null) {
                // preview surface does not exist
                return
            }

            // stop preview before making changes
            try {
                mCamera?.stopPreview()
            } catch (t: Throwable) {
                // ignore: tried to stop a non-existent preview
            }

            // set preview size and make any resize, rotate or
            // reformatting changes here

            // start preview with new settings
            try {
                mCamera?.setPreviewDisplay(holder)
                val bufSize = w * h * ImageFormat.getBitsPerPixel(format) / 8
                for (i in 0..2) {
                    mCamera?.addCallbackBuffer(ByteArray(bufSize))
                }
                mCamera?.setPreviewCallbackWithBuffer(mPreviewCallback)
                startCameraPreview()
            } catch (t: Throwable) {
                cameraErrorListener.onCameraOpenError(t)
            }
        }

        init {
            val camera = mCamera
            if (camera != null) {
                // Install a SurfaceHolder.Callback so we get notified when the
                // underlying surface is created and destroyed.
                holder.addCallback(this)
                val params: Camera.Parameters = camera.parameters
                val focusModes = params.supportedFocusModes
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                }
                params.setRecordingHint(true)
                setCameraParameters(camera, params)
            }
        }
    }
}
