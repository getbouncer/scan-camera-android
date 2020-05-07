package com.getbouncer.scan.camera.camera2

class CameraDeviceCallbackOpenException(val cameraId: String, val errorCode: Int) : Exception()

class CameraConfigurationFailedException(val cameraId: String) : Exception()
