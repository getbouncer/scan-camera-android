package com.getbouncer.scan.camera

abstract class FrameConverter<SourceFormat, DestinationFormat> {
    abstract fun convert(
        source: SourceFormat,
        rotationDegrees: Int
    ): DestinationFormat
}
