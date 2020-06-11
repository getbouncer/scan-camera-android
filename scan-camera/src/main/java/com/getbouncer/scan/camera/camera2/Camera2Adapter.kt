/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.getbouncer.scan.camera.camera2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.getbouncer.scan.camera.CameraAdapter
import com.getbouncer.scan.camera.CameraErrorListener
import com.getbouncer.scan.camera.isSupportedFormat
import com.getbouncer.scan.camera.rotate
import com.getbouncer.scan.camera.scale
import com.getbouncer.scan.camera.toBitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The maximum resolution width for a preview.
 */
private const val MAX_RESOLUTION_WIDTH = 1920

/**
 * The maximum resolution height for a preview.
 */
private const val MAX_RESOLUTION_HEIGHT = 1080

/**
 * For tap to focus.
 */
private const val FOCUS_TOUCH_SIZE = 150

/**
 * The default image format. This is not necessarily the fastest to process, but most supported.
 */
const val DEFAULT_IMAGE_FORMAT = ImageFormat.YUV_420_888

/**
 * Unable to open the camera.
 */
class CameraDeviceCallbackOpenException(val cameraId: String, val errorCode: Int) : Exception()

/**
 * Unable to configure the camera.
 */
class CameraConfigurationFailedException(val cameraId: String) : Exception()

/**
 * A [CameraAdapter] that uses android's Camera 2 APIs to show previews and process images.
 */
class Camera2Adapter(
    private val activity: Activity,
    private val previewView: TextureView?,
    private val minimumResolution: Size,
    private val cameraErrorListener: CameraErrorListener
) : CameraAdapter<Bitmap>(), LifecycleObserver {

    companion object {
        /**
         * Get the optimal preview resolution from a list of available formats and resolutions.
         */
        private fun getOptimalPreviewResolution(
            cameraSizes: Iterable<Pair<Int, Size>>,
            minimumResolution: Size
        ): Pair<Int, Size> {
            // Only consider camera resolutions larger than the minimum resolution, but smaller than
            // the maximum resolution.
            val allowedCameraSizes = cameraSizes.filter {
                it.second.width <= MAX_RESOLUTION_WIDTH &&
                        it.second.height <= MAX_RESOLUTION_HEIGHT &&
                        it.second.width >= minimumResolution.width &&
                        it.second.height >= minimumResolution.height
            }

            return allowedCameraSizes.minBy {
                it.second.width * it.second.height
            } ?: DEFAULT_IMAGE_FORMAT to Size(MAX_RESOLUTION_WIDTH, MAX_RESOLUTION_HEIGHT)
        }

        /**
         * Scale a [previewResolution] to enclose [fit]
         */
        private fun scalePreviewToFit(previewResolution: Size, fit: Size): Size {
            val previewAspectRatio = previewResolution.width.toFloat() / previewResolution.height
            return minAspectRatioOutSize(fit, previewAspectRatio)
        }
    }

    private val processingImage = AtomicBoolean(false)

    private val displayRotation = activity.windowManager.defaultDisplay.rotation

    private lateinit var cameraId: String

    private var previewCaptureSession: CameraCaptureSession? = null

    private var cameraDevice: CameraDevice? = null

    private lateinit var previewSize: Size

    private lateinit var previewResolution: Size

    private var cameraThread: HandlerThread? = null

    private var cameraHandler: Handler? = null

    private var imageReader: ImageReader? = null

    private var sensorRotation = 0

    private val cameraOpenCloseLock = Semaphore(1)

    private var flashSupported = false

    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    private var onInitializedFlashTask: ((Boolean) -> Unit)? = null

    private var autoFocusMode: Int = CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE

    private val mainThreadHandler = Handler(activity.mainLooper)

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
            configureTransform(Size(width, height), previewSize)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?) = true

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) { }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {

        @Synchronized
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreviewSession(previewResolution)

            onInitializedFlashTask?.apply {
                mainThreadHandler.post { this(flashSupported) }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            onDisconnected(camera)
            cameraErrorListener.onCameraOpenError(CameraDeviceCallbackOpenException(camera.id, error))
        }
    }

    @Synchronized
    override fun withFlashSupport(task: (Boolean) -> Unit) {
        if (::previewRequestBuilder.isInitialized) {
            mainThreadHandler.post { task(flashSupported) }
        } else {
            onInitializedFlashTask = task
        }
    }

    override fun setTorchState(on: Boolean) {
        if (!::previewRequestBuilder.isInitialized) {
            return
        }

        if (on) {
            previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        } else {
            previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
        previewCaptureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler)
    }

    override fun isTorchOn() =
        if (::previewRequestBuilder.isInitialized) {
            previewRequestBuilder.get(CaptureRequest.FLASH_MODE) == CaptureRequest.FLASH_MODE_TORCH
        } else {
            false
        }

    @ExperimentalCoroutinesApi
    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onResume() {
        startCameraThread()

        if (previewView?.isAvailable != false) {
            openCamera()
        } else {
            previewView.surfaceTextureListener = surfaceTextureListener
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onPause() {
        closeCamera()
        stopCameraThread()
    }

    /**
     * Sets up member variables related to camera.
     */
    @ExperimentalCoroutinesApi
    private fun setUpCameraOutputs() {
        try {
            getAvailableCameras().firstOrNull()?.also { cameraDetails ->
                sensorRotation = cameraDetails.sensorRotation
                autoFocusMode = selectAutoFocusMode(cameraDetails.supportedAutoFocusModes)

                // Given a desired resolution, get a resolution and format that the camera supports.
                val previewFormatAndResolution = getOptimalPreviewResolution(
                    getCameraResolutions(cameraDetails.config),
                    minimumResolution
                )

                // rotate the preview resolution to match the orientation
                val previewFormat = previewFormatAndResolution.first
                previewResolution = previewFormatAndResolution.second
                previewSize = resolutionToSize(
                    previewResolution,
                    displayRotation,
                    cameraDetails.sensorRotation
                )

                imageReader = ImageReader.newInstance(
                    previewFormatAndResolution.second.width,
                    previewFormatAndResolution.second.height,
                    previewFormat,
                    2
                ).apply {
                    setOnImageAvailableListener(
                        object : ImageReader.OnImageAvailableListener {
                            override fun onImageAvailable(reader: ImageReader?) {
                                if (processingImage.getAndSet(true)) {
                                    return
                                }

                                val bitmap = reader?.acquireLatestImage()?.use {
                                    if (it.isSupportedFormat()) {
                                        it
                                            .toBitmap()
                                            .scale(
                                                max(
                                                minimumResolution.width.toFloat() / it.width,
                                                minimumResolution.height.toFloat() / it.height
                                                )
                                            )
                                            .rotate(
                                                calculateImageRotationDegrees(
                                                    displayRotation,
                                                    sensorRotation
                                                ).toFloat()
                                            )
                                    } else {
                                        null
                                    }
                                }

                                bitmap?.let { addImageToChannel(it) }
                                processingImage.set(false)
                            }
                        },
                        cameraHandler
                    )
                }

                // Check if the flash is supported.
                flashSupported = cameraDetails.flashAvailable

                cameraId = cameraDetails.cameraId
            }
        } catch (e: CameraAccessException) {
            cameraErrorListener.onCameraAccessError(e)
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            cameraErrorListener.onCameraUnsupportedError(e)
        }
    }

    private fun selectAutoFocusMode(supportedAutoFocusModes: List<Int>): Int =
        when {
            CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE in supportedAutoFocusModes ->
                CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO in supportedAutoFocusModes ->
                CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else -> CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }

    private fun getCameraManager() = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * Get a list of cameraIds and their configuration maps.
     */
    private fun getAvailableCameras(): List<CameraDetails> {
        val manager = getCameraManager()
        return manager.cameraIdList
            .map { it to manager.getCameraCharacteristics(it) }
            .filter { it.second.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT }
            .mapNotNull {
                it.second.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.run {
                    CameraDetails(
                        it.first,
                        it.second.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                        this,
                        it.second.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                        it.second.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList<Int>()
                    )
                }
            }
    }

    /**
     * Get a list of all the supported camera resolutions for each format. Output is in the format:
     *
     * ```
     * [
     *   (CameraFormat, Resolution),
     *   (CameraFormat, Resolution),
     *   ...
     * ]
     * ```
     *
     * Note that each format will likely have multiple resolutions. Available formats will be sorted
     * by preference (fastest first).
     */
    private fun getCameraResolutions(map: StreamConfigurationMap): List<Pair<Int, Size>> {
        val formats = map.outputFormats.filter { isSupportedFormat(it) }.sortedBy {
            when (it) {
                ImageFormat.NV21 -> 0
                ImageFormat.YUV_420_888 -> 1
                ImageFormat.JPEG -> 2
                ImageFormat.YUY2 -> 3
                else -> it
            }
        }
        return formats.flatMap { format ->
            (map.getOutputSizes(format) ?: emptyArray())
                .asIterable()
                .map { format to it }
        }
    }

    /**
     * Opens the camera specified by [cameraId].
     */
    @ExperimentalCoroutinesApi
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        setUpCameraOutputs()

        previewView?.apply {
            configureTransform(Size(width, height), previewSize)
        }

        val manager = getCameraManager()
        try {
            // Wait for camera to open - 2.5 seconds is sufficient
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                cameraErrorListener.onCameraOpenError(null)
                return
            }
            manager.openCamera(cameraId, stateCallback, cameraHandler)
        } catch (e: CameraAccessException) {
            cameraErrorListener.onCameraAccessError(e)
        } catch (e: InterruptedException) {
            cameraErrorListener.onCameraOpenError(e)
        }
    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            previewCaptureSession?.close()
            previewCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: InterruptedException) {
            cameraErrorListener.onCameraOpenError(e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startCameraThread() {
        val thread = HandlerThread("CameraBackground").also { it.start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            cameraErrorListener.onCameraOpenError(e)
        }
    }

    /**
     * Creates a new [CameraCaptureSession] for camera preview.
     */
    private fun createCameraPreviewSession(previewResolution: Size) {
        try {
            val texture = previewView?.surfaceTexture

            // We configure the size of default buffer to be the size of camera preview we want.
            texture?.setDefaultBufferSize(previewResolution.width, previewResolution.height)

            // This is the output Surface we need to start preview.
            val surface = texture?.let { Surface(it) }

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )

            surface?.apply { previewRequestBuilder.addTarget(this) }
            imageReader?.apply { previewRequestBuilder.addTarget(this.surface) }

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice?.createCaptureSession(
                listOfNotNull(surface, imageReader?.surface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (cameraDevice == null) return

                        // When the session is ready, we start displaying the preview.
                        previewCaptureSession = cameraCaptureSession
                        try {
                            // Auto focus should be continuous for camera preview. This does not
                            // work on samsung devices.
                            if (!Build.MANUFACTURER.toUpperCase(Locale.ROOT).contains("SAMSUNG")) {
                                previewRequestBuilder.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    autoFocusMode
                                )
                            }

                            // Finally, we start displaying the camera preview.
                            val previewRequest = previewRequestBuilder.build()
                            previewCaptureSession?.setRepeatingRequest(previewRequest, null, cameraHandler)
                        } catch (e: CameraAccessException) {
                            cameraErrorListener.onCameraAccessError(e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cameraErrorListener.onCameraOpenError(CameraConfigurationFailedException(session.device.id))
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            cameraErrorListener.onCameraAccessError(e)
        }
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `textureView` is fixed.
     *
     * @param viewSize The size of `textureView`
     */
    private fun configureTransform(viewSize: Size, previewSize: Size) {
        val matrix = Matrix()
        val viewRect = RectF(0F, 0F, viewSize.width.toFloat(), viewSize.height.toFloat())
        val bufferRect = RectF(0F, 0F, previewSize.width.toFloat(), previewSize.height.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        val rotation = -(displayRotation * 90).toFloat()
        val scale = max(
            viewSize.width.toFloat() / previewSize.width,
            viewSize.height.toFloat() / previewSize.height
        )

        // TODO(awushensky): this breaks on rotation. See https://stackoverflow.com/questions/34536798/android-camera2-preview-is-rotated-90deg-while-in-landscape?rq=1
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        matrix.postScale(scale, scale, viewRect.centerX(), viewRect.centerY())
        matrix.postRotate(rotation, viewRect.centerX(), viewRect.centerY())

        previewView?.setTransform(matrix)
    }

    override fun setFocus(point: PointF) {
        if (!::previewRequestBuilder.isInitialized) {
            return
        }

        previewCaptureSession?.stopRepeating()

        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        previewCaptureSession?.capture(previewRequestBuilder.build(), null, cameraHandler)

        if (isMeteringAreaAFSupported()) {
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_REGIONS,
                arrayOf(
                    MeteringRectangle(
                        max(point.x.toInt() - FOCUS_TOUCH_SIZE, 0),
                        max(point.y.toInt() - FOCUS_TOUCH_SIZE, 0),
                        FOCUS_TOUCH_SIZE * 2,
                        FOCUS_TOUCH_SIZE * 2,
                        MeteringRectangle.METERING_WEIGHT_MAX - 1
                    )
                )
            )
        }

        previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        previewRequestBuilder.setTag("FOCUS_TAG") // we'll capture this later for resuming the preview

        previewCaptureSession?.capture(previewRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)

                if (request.tag == "FOCUS_TAG") {
                    // the focus trigger is complete -
                    // resume repeating (preview surface will get frames), clear AF trigger
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
                    previewCaptureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler)
                }
            }
        }, cameraHandler)
    }

    private fun isMeteringAreaAFSupported(): Boolean {
        val manager = getCameraManager()
        val cameraCharacteristics = manager.getCameraCharacteristics(cameraId)
        return cameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0 >= 1
    }
}

/**
 * Details about a camera.
 */
private data class CameraDetails(
    val cameraId: String,
    val flashAvailable: Boolean,
    val config: StreamConfigurationMap,
    val sensorRotation: Int,
    val supportedAutoFocusModes: List<Int>
)

/**
 * Determine the maximum size of rectangle with a given aspect ratio (X/Y) that can enclose the
 * specified area.
 *
 * For example, if the aspect ratio is 9/16 and the area is 2x2, the resulting rectangle would be
 * size 2x4 and look like this:
 * ```
 *  ____
 * |____|
 * |    |
 * |____|
 * |____|
 * ```
 */
private fun minAspectRatioOutSize(area: Size, aspectRatio: Float): Size {
    var height = area.height
    var width = (height * aspectRatio).roundToInt()

    return if (width >= area.width) {
        Size(width, height)
    } else {
        width = area.width
        height = (width / aspectRatio).roundToInt()
        Size(width, max(height, area.height))
    }
}
