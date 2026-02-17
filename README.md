# Android Low-Latency RTSP Server (On-Device)

This branch contains a production-oriented Kotlin design and reference implementation for an **embedded RTSP server running directly on Android** with a near-zero-latency target (<150ms on LAN with proper tuning).

## Implemented Components

- `MediaCodecVideoEncoder`
  - Hardware H.264/H.265 encode using `MediaCodec` async callback.
  - Supports **Surface input** (preferred zero-copy) and **raw frame push** (`ByteBuffer`/YUV path).
  - Runtime bitrate + sync-frame requests.
- `H264RtpPacketizer`
  - RTP packetization for Annex-B H264 access units.
  - FU-A fragmentation and marker-bit handling.
- `RtspServer`
  - Minimal embedded RTSP server (OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN).
- `StreamingSessionManager`
  - Orchestrates source + encoder + RTP + RTSP.
  - Multi-client fanout, source switching, bitrate updates.
- `Camera2SurfaceSource`
  - Camera2 internal camera source rendering into encoder surface (+ optional preview).
- `UvcSurfaceSource`
  - UVC integration hook for external USB cameras.
- `FrameSource` contracts
  - Extendable API for custom sources (`Surface`, raw YUV, OpenGL texture paths).

## Architecture

```text
Camera / Frame Source
       ↓
Encoder Input Surface OR Raw Frame Queue
       ↓
MediaCodec (Hardware Encoder, async)
       ↓
RTP Packetizer (H264/H265)
       ↓
Embedded RTSP Server
       ↓
VLC / FFplay / GStreamer client
```

Detailed architecture, threading, buffering, tuning, dynamic bitrate, camera switch, and RTSPS readiness:

- `docs/LOW_LATENCY_RTSP_ARCHITECTURE.md`

## Low-Latency Strategy Summary

1. Use **surface-to-encoder** path whenever possible.
2. Keep queue depth minimal (1–2 encoded AUs max).
3. Avoid bitmap conversion in the live path.
4. Use async `MediaCodec` callback mode.
5. Force IDR on camera/source switch.
6. Tune GOP/bitrate/fps per device thermal/network envelope.

## Example Wiring

```kotlin
val source = Camera2SurfaceSource(context, cameraId = "0", previewSurface = previewSurface)
val encoder = MediaCodecVideoEncoder(
    mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
    width = 1280,
    height = 720,
    fps = 30,
    initialBitrate = 3_000_000,
    iFrameIntervalSec = 1f,
)

val manager = StreamingSessionManager(source, encoder)
manager.start(surfaceSource = true)

// Later:
manager.updateBitrate(2_500_000)
// manager.switchSource(anotherSource)
```

Then open in VLC/ffplay:

```text
rtsp://<android-device-ip>:8554/live
```


## Android Activity Integration

A ready-to-wire `MainActivity` example is included at:

- `rtspserver/src/main/kotlin/com/example/rtsp/MainActivity.kt`

It attaches a preview `SurfaceView`, starts the encoder+RTSP pipeline automatically, and displays the stream URL:

```text
rtsp://<device-ip>:8554/live
```

## Production Notes

- Add auth (`Basic`/`Digest`) to `RtspServer`.
- Add RTCP receiver reports and NACK-driven adaptation.
- Populate real SPS/PPS base64 in SDP.
- Harden socket lifecycle and timeouts.
- Add TLS (`SSLServerSocket`) for RTSPS control channel.
