package com.android.rtsp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency hardware video encoder wrapper.
 * Supports both Surface input (preferred) and raw frame push mode.
 */
class MediaCodecVideoEncoder(
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val initialBitrate: Int,
    private val iFrameIntervalSec: Float = 1f,
) : RawFrameConsumer {

    data class EncodedSample(
        val data: ByteArray,
        val ptsUs: Long,
        val flags: Int,
        val isKeyFrame: Boolean,
    )

    interface Listener {
        fun onOutputFormatChanged(format: MediaFormat)
        fun onEncodedSample(sample: EncodedSample)
    }

    private val started = AtomicBoolean(false)
    private val callbackThread = HandlerThread("codec-callback")
    private val inputRawQueue = ArrayBlockingQueue<RawInput>(3)

    private var codec: MediaCodec? = null
    private var codecInputSurface: Surface? = null
    private var listener: Listener? = null

    private data class RawInput(
        val buffer: ByteBuffer,
        val ptsUs: Long,
        val width: Int,
        val height: Int,
    )

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun start(surfaceInput: Boolean): Surface? {
        if (!started.compareAndSet(false, true)) return codecInputSurface

        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                if (surfaceInput) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, initialBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime priority hint
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

        callbackThread.start()
        val handler = Handler(callbackThread.looper)
        codec = MediaCodec.createEncoderByType(mimeType).also { encoder ->
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (surfaceInput) return
                    val input = inputRawQueue.poll() ?: return
                    val inBuffer = codec.getInputBuffer(index) ?: return
                    inBuffer.clear()
                    inBuffer.put(input.buffer)
                    codec.queueInputBuffer(index, 0, input.buffer.limit(), input.ptsUs, 0)
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val buffer = codec.getOutputBuffer(index) ?: return
                    val out = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(out)
                    listener?.onEncodedSample(
                        EncodedSample(
                            data = out,
                            ptsUs = info.presentationTimeUs,
                            flags = info.flags,
                            isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                        )
                    )
                    codec.releaseOutputBuffer(index, false)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    listener?.onOutputFormatChanged(format)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    stop()
                }
            }, handler)

            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (surfaceInput) codecInputSurface = encoder.createInputSurface()
            encoder.start()
        }

        return codecInputSurface
    }

    fun requestSyncFrame() {
        codec?.setParameters(Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        })
    }

    fun setBitrate(bitrate: Int) {
        codec?.setParameters(Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate)
        })
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        codecInputSurface = null
        callbackThread.quitSafely()
    }

    override fun onNv12Frame(buffer: ByteBuffer, ptsUs: Long, width: Int, height: Int) {
        if (!started.get()) return
        inputRawQueue.poll()
        inputRawQueue.offer(RawInput(buffer, ptsUs, width, height))
    }

    override fun onYuv420Image(image: android.media.Image, ptsUs: Long) {
        // For production, convert plane layout carefully or use ImageReader + GPU path.
        image.close()
    }
}