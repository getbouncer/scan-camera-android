package com.getbouncer.scan.camera.camerax

import android.app.Activity
import android.graphics.PointF
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.getbouncer.scan.camera.CameraAdapter
import com.getbouncer.scan.camera.CameraErrorListener
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CameraXAdapter(
    private val activity: Activity,
    private val previewView: PreviewView?,
    private val minimumResolution: Size,
    private val cameraErrorListener: CameraErrorListener,
    private val imageAnalyzer: RotatingImageAnalyzer?
) : CameraAdapter() {

    companion object {
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        /**
         *  [androidx.camera.core.ImageAnalysisConfig] requires enum value of
         *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
         *
         *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
         *  of preview ratio to one of the provided values.
         *
         *  @param width - preview width
         *  @param height - preview height
         *  @return suitable aspect ratio
         */
        private fun aspectRatio(width: Int, height: Int): Int {
            val previewRatio = max(width, height).toDouble() / min(width, height)
            if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
                return AspectRatio.RATIO_4_3
            }
            return AspectRatio.RATIO_16_9
        }
    }

    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraSelector: CameraSelector? = null

    private lateinit var cameraExecutor: ExecutorService

    override fun bindToLifecycle(lifecycleOwner: LifecycleOwner) {
        super.bindToLifecycle(lifecycleOwner)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val rotation = previewView?.display?.rotation ?: activity.windowManager.defaultDisplay.rotation

        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)

        cameraProviderFuture.addListener(Runnable {

            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            if (previewView != null) {
                preview = Preview.Builder()
                    .setTargetResolution(minimumResolution)
                    .setTargetRotation(rotation) // Set initial target rotation
                    .build()
            }

            if (imageAnalyzer != null) {
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(minimumResolution)
                    .setTargetRotation(rotation) // Set initial target rotation
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, imageAnalyzer)
                    }
            }

            // Must unbind the use-cases before rebinding them
            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                if (preview != null && imageAnalysis == null) {
                    camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                } else if (preview == null && imageAnalysis != null) {
                    camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                } else if (preview != null && imageAnalysis != null) {
                    camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                }

                imageAnalyzer?.rotationDegrees = camera?.let { calculateImageRotationDegrees(rotation, it.cameraInfo.sensorRotationDegrees) } ?: 0

                // Attach the viewfinder's surface provider to preview use case
                if (previewView != null) {
                    preview?.setSurfaceProvider(previewView.createSurfaceProvider(camera?.cameraInfo))
                }

                this.cameraSelector = cameraSelector
            } catch(exc: Exception) {
                cameraErrorListener.onCameraOpenError(exc)
            }

        }, ContextCompat.getMainExecutor(activity))
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        cameraExecutor.shutdown()
    }

    override fun withFlashSupport(task: (Boolean) -> Unit) {
        task(camera?.cameraInfo?.hasFlashUnit() ?: false)
    }

    override fun setTorchState(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    override fun isTorchOn(): Boolean = camera?.cameraInfo?.torchState?.value == TorchState.ON

    override fun setFocus(point: PointF) {
        cameraSelector?.let { selector ->
            previewView?.let { previewView ->
                val factory = previewView.createMeteringPointFactory(selector)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(factory.createPoint(point.x, point.y)).build())
            }
        }
    }
}
