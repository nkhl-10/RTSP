package com.android.rtsp

import android.media.Image
import android.view.Surface
import java.nio.ByteBuffer

/** Generic frame source abstraction for camera, UVC, and custom producers. */
interface FrameSource {
    fun start()
    fun stop()
}

/** Zero-copy source path: producer renders directly to codec input [Surface]. */
interface SurfaceFrameSource : FrameSource {
    fun attachEncoderSurface(surface: Surface)
}

/** CPU-frame source path for custom producers pushing raw frames. */
interface RawFrameSource : FrameSource {
    fun setRawFrameConsumer(consumer: RawFrameConsumer)
}

interface RawFrameConsumer {
    fun onNv12Frame(buffer: ByteBuffer, ptsUs: Long, width: Int, height: Int)
    fun onYuv420Image(image: Image, ptsUs: Long)
}