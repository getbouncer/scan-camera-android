package com.getbouncer.scan.camera.camera2

import android.graphics.Bitmap
import android.media.ImageReader
import androidx.annotation.RestrictTo
import com.getbouncer.scan.framework.ProcessBoundAnalyzerLoop
import com.getbouncer.scan.framework.image.isSupportedFormat
import com.getbouncer.scan.framework.image.toBitmap
import com.getbouncer.scan.camera.FrameConverter
import kotlinx.coroutines.runBlocking

abstract class OnImageAvailableListener : ImageReader.OnImageAvailableListener {
    var rotationDegrees = 0

    override fun onImageAvailable(reader: ImageReader?) {
        val image = reader?.acquireLatestImage()
        if (image?.isSupportedFormat() == true) {
            val bitmap = image.toBitmap()
            image.close()
            onBitmapAvailable(bitmap)
        } else {
            image?.close()
        }
    }

    abstract fun onBitmapAvailable(bitmap: Bitmap)
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class ImageListenerAdapter<ImageFormat, State, Output>(
    private val loop: ProcessBoundAnalyzerLoop<ImageFormat, State, Output>,
    private val frameConverter: FrameConverter<Bitmap, ImageFormat>
) : OnImageAvailableListener() {
    override fun onBitmapAvailable(bitmap: Bitmap) {
        runBlocking { loop.processFrame(frameConverter.convertFrameFormat(bitmap, rotationDegrees)) }
    }
}
