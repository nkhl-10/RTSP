package com.android.rtsp

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.annotation.RequiresPermission

/** Camera2 implementation that streams camera frames directly to encoder surface (+ optional preview). */
class Camera2SurfaceSource(
    context: Context,
    private var cameraId: String,
    private val previewSurface: Surface? = null,
) : SurfaceFrameSource {

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("camera2-source")
    private lateinit var handler: Handler

    private var encoderSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    override fun attachEncoderSurface(surface: Surface) {
        encoderSurface = surface
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override fun start() {
        if (!cameraThread.isAlive) {
            cameraThread.start()
            handler = Handler(cameraThread.looper)
        }
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createSession(camera)
            }

            override fun onDisconnected(camera: CameraDevice) = camera.close()
            override fun onError(camera: CameraDevice, error: Int) = camera.close()
        }, handler)
    }

    fun switchCamera(newCameraId: String) {
        stop()
        cameraId = newCameraId
        start()
    }

    override fun stop() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { cameraDevice?.close() }
        session = null
        cameraDevice = null
    }

    private fun createSession(camera: CameraDevice) {
        val targets = mutableListOf<Surface>()
        encoderSurface?.let(targets::add)
        previewSurface?.let(targets::add)
        if (targets.isEmpty()) return

        camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    targets.forEach { addTarget(it) }
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30, 30))
                }.build()
                s.setRepeatingRequest(request, null, handler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
        }, handler)
    }
}