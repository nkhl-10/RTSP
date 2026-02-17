package com.android.rtsp

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.rtsp.databinding.ActivityMainBinding


/**
 * Example activity that starts local RTSP streaming and displays URL:
 * rtsp://<device-ip>:8554/live
 */
class MainActivity : AppCompatActivity() {

    private var sessionManager: StreamingSessionManager? = null
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.statusView.text = "Initializing RTSP server..."
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        bindPreviewAndStart()
    }

    private fun bindPreviewAndStart() {
        binding.previewSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startStreaming(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopStreaming()
            }
        })
    }

    private fun startStreaming(holder: SurfaceHolder) {
        if (sessionManager != null) return

        val source = Camera2SurfaceSource(
            context = this,
            cameraId = "0",
            previewSurface = holder.surface,
        )

        val encoder = MediaCodecVideoEncoder(
            mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
            width = 1280,
            height = 720,
            fps = 30,
            initialBitrate = 3_000_000,
            iFrameIntervalSec = 1f,
        )

        sessionManager = StreamingSessionManager(source, encoder).also { it.start(surfaceSource = true) }

        val url = "rtsp://${getDeviceIpAddress()}:8554/live"
        binding.statusView.text = "Streaming started\n$url"
    }

    private fun stopStreaming() {
        sessionManager?.stop()
        sessionManager = null
        binding.statusView.text = "Streaming stopped"
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            bindPreviewAndStart()
        } else {
            binding.statusView.text = "Camera permission required to stream"
        }
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun getDeviceIpAddress(): String {
        return runCatching {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        }.getOrDefault("127.0.0.1")
    }

    companion object {
        private const val REQ_CAMERA = 1001
    }
}