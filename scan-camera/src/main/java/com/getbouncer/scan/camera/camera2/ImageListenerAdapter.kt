package com.getbouncer.scan.camera.camera2

import android.graphics.Bitmap
import android.media.ImageReader
import android.util.Size
import androidx.annotation.RestrictTo
import com.getbouncer.scan.camera.FrameConverter
import com.getbouncer.scan.framework.ProcessBoundAnalyzerLoop
import com.getbouncer.scan.framework.image.isSupportedFormat
import com.getbouncer.scan.framework.image.scale
import com.getbouncer.scan.framework.image.toBitmap
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

abstract class OnImageAvailableListener(
    private val analysisResolution: Size
): ImageReader.OnImageAvailableListener {
    var rotationDegrees = 0

    private val processing = AtomicBoolean(false)

    override fun onImageAvailable(reader: ImageReader?) {
        if (processing.getAndSet(true)) {
            return
        }

        val image = reader?.acquireLatestImage()
        if (image?.isSupportedFormat() == true) {
            val scale = max(
                analysisResolution.width.toFloat() / image.width,
                analysisResolution.height.toFloat() / image.height
            )
            val bitmap = image.toBitmap().scale(scale)
            image.close()
            onBitmapAvailable(bitmap)
        } else {
            image?.close()
        }

        processing.set(false)
    }

    abstract fun onBitmapAvailable(bitmap: Bitmap)
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class ImageListenerAdapter<ImageFormat, State, Output>(
    private val loop: ProcessBoundAnalyzerLoop<ImageFormat, State, Output>,
    private val frameConverter: FrameConverter<Bitmap, ImageFormat>,
    analysisResolution: Size
) : OnImageAvailableListener(analysisResolution) {
    override fun onBitmapAvailable(bitmap: Bitmap) {
        runBlocking {
            loop.processFrame(frameConverter.convertFrameFormat(bitmap, rotationDegrees))
        }
    }
}
