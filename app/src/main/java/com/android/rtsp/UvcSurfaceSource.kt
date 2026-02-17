package com.android.rtsp

import android.view.Surface

/**
 * Placeholder contract for UVC camera integration.
 * Production implementation should bind USB camera frames to [attachEncoderSurface].
 */
class UvcSurfaceSource : SurfaceFrameSource {
    private var encoderSurface: Surface? = null

    override fun attachEncoderSurface(surface: Surface) {
        encoderSurface = surface
    }

    override fun start() {
        // Integrate with your selected UVC stack and render directly to encoderSurface.
    }

    override fun stop() {
        // Release USB camera resources.
    }
}