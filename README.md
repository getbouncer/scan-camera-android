# CardScan Camera

This repository contains the camera framework to allow CardScan to scan payment cards. [CardScan](https://cardscan.io/) is a relatively small library (1.9 MB) that provides fast and accurate payment card scanning.

Note this library does not contain any user interfaces. Another library, [CardScan UI](https://github.com/getbouncer/cardscan-ui-android) builds upon this one any adds simple user interfaces. 

![CardScan](docs/images/cardscan.png)

## Contents

* [Requirements](#requirements)
* [Demo](#demo)
* [Installation](#installation)
* [Using CardScan](#using-cardscan-base)
* [Developing CardScan](#developing-cardscan)
* [Authors](#authors)
* [License](#license)

## Requirements

* Android API level 21 or higher
* Kotlin coroutine compatibility

Note: Your app does not have to be written in kotlin to integrate cardscan, but must be able to depend on kotlin functionality.

## Demo

An app demonstrating the basic capabilities of CardScan is available in [github](https://github.com/getbouncer/cardscan-demo-android).

## Installation

The CardScan libraries are published in the [jcenter](https://jcenter.bintray.com/com/getbouncer/) repository, so for most gradle configurations you only need to add the dependencies to your app's `build.gradle` file:

```gradle
dependencies {
    implementation 'com.getbouncer:cardscan-base:2.0.0004'
    implementation 'com.getbouncer:cardscan-camera:2.0.0004'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.3'
}
```

## Using cardscan-camera

CardScan Camera is designed to be used with [CardScan UI](https://github.com/getbouncer/cardscan-ui-android), which will provide user interfaces for scanning payment cards. However, it can be used independently.

For an overview of the architecture and design of the cardscan framework, see the [architecture documentation](https://github.com/getbouncer/cardscan-base-android/blob/master/docs/architecture.md).

### Getting images from the camera

Let's use an example where we stream images from the camera.

```kotlin
class MyCameraAnalyzer : AppCompatActivity(), OnImageAvailableListener, CameraErrorListener {
    
    /**
     * Call this method to start streaming images from the camera.
     */
    fun startStreamingCamera() {
        Camera2Adapter(
            activity = this,
            onImageAvailableListener = this,  // An image listener. If null, images will not be captured.
            minimumResolution = MINIMUM_RESOLUTION,
            cameraErrorListener = this,
            cameraTexture = cameraTexture  // A TextureView where the previews should be shown. If this is null, no preview will be shown.
        ).bindToLifecycle(this)
    }

    override fun onBitmapAvailable(bitmap: Bitmap) {
        // A camera image is available
    }

    override fun onCameraOpenError(cause: Throwable?) {
        // The camera could not be opened
    }

    override fun onCameraAccessError(cause: Throwable?) {
        // The camera could not be accessed
    }

    override fun onCameraUnsupportedError(cause: Throwable?) {
        // the camera is not supported on this device.
    }
}
```

## Developing CardScan

See the [development documentation](docs/develop.md) for details on developing for CardScan.

## Authors

Adam Wushensky, Sam King, and Zain ul Abi Din

## License

CardScan is available under paid and free licenses. See the [LICENSE](LICENSE) file for the full license text.

### Quick summary
In short, CardScan will remain free forever for non-commercial applications, but use by commercial applications is limited to 90 days, after which time a licensing agreement is required. We're also adding some legal liability protections.

After this period commercial applications need to convert to a licensing agreement to continue to use CardScan.
* Details of licensing (pricing, etc) are available at [https://cardscan.io/pricing](https://cardscan.io/pricing), or you can contact us at [license@getbouncer.com](mailto:license@getbouncer.com).

### More detailed summary
What's allowed under the license:
* Free use for any app for 90 days (for demos, evaluations, hackathons, etc).
* Contributions (contributors must agree to the [Contributor License Agreement](Contributor%20License%20Agreement))
* Any modifications as needed to work in your app

What's not allowed under the license:
* Commercial applications using the license for longer than 90 days without a license agreement. 
* Using us now in a commercial app today? No worries! Just email [license@getbouncer.com](mailto:license@getbouncer.com) and we’ll get you set up.
* Redistribution under a different license
* Removing attribution
* Modifying logos
* Indemnification: using this free software is ‘at your own risk’, so you can’t sue Bouncer Technologies, Inc. for problems caused by this library

Questions? Concerns? Please email us at [license@getbouncer.com](mailto:license@getbouncer.com) or ask us on [slack](https://getbouncer.slack.com).
