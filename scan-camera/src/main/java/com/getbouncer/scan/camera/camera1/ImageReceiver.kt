@file:Suppress("Deprecation")
package com.getbouncer.scan.camera.camera1

import android.graphics.Bitmap
import android.hardware.Camera
import android.util.Size
import com.getbouncer.scan.camera.FrameConverter
import com.getbouncer.scan.framework.ProcessBoundAnalyzerLoop
import com.getbouncer.scan.framework.nv21ToYuv
import com.getbouncer.scan.framework.scale
import com.getbouncer.scan.framework.toBitmap
import kotlinx.coroutines.runBlocking
import kotlin.math.max

interface ImageReceiver {
    fun receiveImage(image: ByteArray, imageSize: Size, rotationDegrees: Int, camera: Camera)
}

class ImageReceiverAnalyzer<ImageFormat, State, Output>(
    private val loop: ProcessBoundAnalyzerLoop<ImageFormat, State, Output>,
    private val frameConverter: FrameConverter<Bitmap, ImageFormat>,
    private val analysisResolution: Size
) : ImageReceiver {
    override fun receiveImage(image: ByteArray, imageSize: Size, rotationDegrees: Int, camera: Camera) {
        val scale = max(
            analysisResolution.width.toFloat() / imageSize.width,
            analysisResolution.height.toFloat() / imageSize.height
        )
        val bitmap = image.nv21ToYuv(imageSize.width, imageSize.height).toBitmap().scale(scale)
        camera.addCallbackBuffer(image)

        runBlocking {
            loop.processFrame(frameConverter.convertFrameFormat(bitmap, rotationDegrees))
        }
    }
}
