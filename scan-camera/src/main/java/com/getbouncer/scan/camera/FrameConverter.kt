package com.getbouncer.scan.camera

import com.getbouncer.scan.framework.Config
import com.getbouncer.scan.framework.Stats
import com.getbouncer.scan.framework.time.Timer

abstract class FrameConverter<SourceFormat, DestinationFormat> {
    private val loggingTimer = Timer.newInstance(Config.logTag, "frame_conversion")

    suspend fun convertFrameFormat(source: SourceFormat, rotationDegrees: Int): DestinationFormat =
        Stats.trackTask("frame_conversion") {
            loggingTimer.measure {
                convert(source, rotationDegrees)
            }
        }

    protected abstract fun convert(
        source: SourceFormat,
        rotationDegrees: Int
    ): DestinationFormat
}
