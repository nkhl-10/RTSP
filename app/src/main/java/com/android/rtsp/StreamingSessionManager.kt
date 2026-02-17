package com.android.rtsp

import android.media.MediaFormat
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Coordinates source -> encoder -> RTP -> RTSP control and supports live camera switching.
 */
class StreamingSessionManager(
    private val source: FrameSource,
    private val encoder: MediaCodecVideoEncoder,
) {
    private val packetizers = ConcurrentHashMap<String, H264RtpPacketizer>()
    private var spsPpsBase64: String = ""

    private val rtspServer = RtspServer(
        sdpProvider = { buildSdp() },
        onSetup = { ip, clientPort, sessionId ->
            val packetizer = H264RtpPacketizer(
                destination = InetAddress.getByName(ip),
                port = clientPort,
                ssrc = Random.nextInt(),
            )
            packetizers[sessionId] = packetizer
        },
        onTeardown = { sessionId -> packetizers.remove(sessionId) }
    )

    fun start(surfaceSource: Boolean = true) {
        encoder.setListener(object : MediaCodecVideoEncoder.Listener {
            override fun onOutputFormatChanged(format: MediaFormat) {
                // Populate profile-level-id/sprop-parameter-sets for full SDP completeness.
                spsPpsBase64 = format.getByteBuffer("csd-0")?.let { "<base64-sps>" } ?: ""
            }

            override fun onEncodedSample(sample: MediaCodecVideoEncoder.EncodedSample) {
                packetizers.values.forEach { it.packetizeAccessUnit(sample.data, sample.ptsUs) }
            }
        })

        val inputSurface = encoder.start(surfaceInput = surfaceSource)
        if (surfaceSource && source is SurfaceFrameSource && inputSurface != null) {
            source.attachEncoderSurface(inputSurface)
        }
        if (!surfaceSource && source is RawFrameSource) {
            source.setRawFrameConsumer(encoder)
        }

        source.start()
        rtspServer.start()
    }

    fun stop() {
        rtspServer.stop()
        source.stop()
        encoder.stop()
        packetizers.clear()
    }

    fun switchSource(newSource: FrameSource) {
        // Keep RTSP server alive; swap only producer, then request IDR for clean decoder sync.
        source.stop()
        when {
            newSource is SurfaceFrameSource -> {
                val inputSurface = encoder.start(surfaceInput = true)
                if (inputSurface != null) newSource.attachEncoderSurface(inputSurface)
            }
            newSource is RawFrameSource -> newSource.setRawFrameConsumer(encoder)
        }
        newSource.start()
        encoder.requestSyncFrame()
    }

    fun updateBitrate(bitrate: Int) {
        encoder.setBitrate(bitrate)
    }

    private fun buildSdp(): String {
        return """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=Android RTSP Stream
            t=0 0
            a=control:*
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;sprop-parameter-sets=$spsPpsBase64
            a=control:trackID=0
        """.trimIndent()
    }
}