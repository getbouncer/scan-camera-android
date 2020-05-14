package com.getbouncer.scan.camera.camerax

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.getbouncer.scan.camera.FrameConverter
import com.getbouncer.scan.framework.ProcessBoundAnalyzerLoop
import com.getbouncer.scan.framework.image.isSupportedFormat
import com.getbouncer.scan.framework.image.scale
import com.getbouncer.scan.framework.image.toBitmap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.runBlocking

interface RotatingImageAnalyzer : ImageAnalysis.Analyzer {
    var rotationDegrees: Int
}

class ImageAnalyzer<ImageFormat, State, Output>(
    private val loop: ProcessBoundAnalyzerLoop<ImageFormat, State, Output>,
    private val frameConverter: FrameConverter<Bitmap, ImageFormat>,
    private val analysisResolution: Size
) : RotatingImageAnalyzer {

    override var rotationDegrees: Int = 0

    private val processing = AtomicBoolean(false)

    @SuppressLint("UnsafeExperimentalUsageError")
    override fun analyze(image: ImageProxy) {
        if (processing.getAndSet(true)) {
            image.close()
            return
        }

        val scale = max(
            analysisResolution.width.toFloat() / image.width,
            analysisResolution.height.toFloat() / image.height
        )
        image.toBitmap()?.scale(scale)?.apply {
            runBlocking {
                loop.processFrame(
                    frameConverter.convertFrameFormat(
                        this@apply,
                        rotationDegrees
                    )
                )
            }
        }
        image.close()

        processing.set(false)
    }
}

@ExperimentalGetImage
private fun ImageProxy.toBitmap(
    crop: Rect = Rect(
        0,
        0,
        this.width,
        this.height
    ),
    quality: Int = 75
) = if (image?.isSupportedFormat() == true) image?.toBitmap(crop, quality) else null
