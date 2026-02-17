# Android Near-Zero-Latency RTSP Server Architecture

## Goals
- Embedded RTSP server running fully on device (no cloud relay).
- Sub-150ms glass-to-glass target on LAN.
- Hardware-first encoding path with `MediaCodec` (`video/avc`, optional `video/hevc`).
- Multi-source ingest:
  - Camera2 internal cameras.
  - UVC camera surfaces.
  - Custom producers (`Surface`, YUV planes, NV12 `ByteBuffer`, OpenGL textures).

## High-Level Pipeline

```text
Camera / Frame Source
       ↓
Encoder Input Surface OR Raw Frame Queue
       ↓
MediaCodec (hardware encoder, async callback)
       ↓
RTP Packetizer (H264/H265 over UDP interleaved-ready)
       ↓
Embedded RTSP Server (OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN)
       ↓
Client (VLC / FFplay / GStreamer)
```

## Core Modules

1. **Frame Source Layer**
   - `FrameSource`: generic source contract.
   - `SurfaceFrameSource`: zero-copy source when camera/UVC can render directly.
   - `RawFrameSource`: push APIs for YUV/NV12 `ByteBuffer` or externally produced planes.
   - `GlTextureFrameSource`: GLES producer path (optional EGL bridge) for OES/2D textures.

2. **Encoder Layer (`MediaCodecVideoEncoder`)**
   - Async callback mode (`setCallback`) to avoid blocking and reduce thread wakeups.
   - Two ingest modes:
     - `createInputSurface()` for true zero-copy when source can render to `Surface`.
     - `queueInputBuffer` when raw frames are pushed.
   - Low latency tunables:
     - Small GOP (`iFrameIntervalSec` 0.5-1 sec).
     - No B-frames (device dependent via profile/level + vendor params).
     - Constant/CBR bitrate with fast update support.
     - `KEY_LATENCY`/vendor keys opportunistically applied.

3. **RTP Packetizer (`H264RtpPacketizer`)**
   - Parses Annex-B NAL units.
   - Single NAL packet for payload <= MTU budget.
   - FU-A fragmentation for large NALs.
   - Marker bit set on access-unit boundary.
   - Sequence/timestamp continuity preserved per stream.

4. **Transport + Session Layer**
   - `RtpUdpTransport`: dual sockets (RTP/RTCP baseline).
   - `RtspServer`: minimal RTSP state machine, session table, SDP generation.
   - Supports multi-client fan-out by duplicating encoded frames to per-session packetizers/transports.

5. **Orchestration Layer (`StreamingSessionManager`)**
   - Binds frame source, encoder, packetizer, RTSP session lifecycle.
   - Handles camera hot-switch by re-binding source while RTSP server remains alive.
   - Dynamic bitrate adaptation input from network stats.

## Threading Model

- **Encoder Callback Thread** (internal codec looper):
  - Receives `onOutputBufferAvailable`, extracts encoded AU + metadata.
  - Pushes into lock-free fan-out queue.

- **RTP Sender Thread(s)**:
  - One sender per active client OR one sender + per-client transport adapters.
  - Reads latest encoded samples and packetizes immediately.
  - Drops stale frames if queue depth > 2 AUs.

- **RTSP Control Thread Pool**:
  - Accept loop + per-client command handling.
  - Commands update session state atomically.

- **Frame Producer Thread**:
  - Camera2 capture session thread or external producer thread.
  - Writes to encoder surface or raw input queue.

## Buffer Strategy for Low Latency

- Prefer **single-buffer handoff** semantics:
  - Surface path: camera -> encoder surface directly, no CPU copy.
  - Raw path: fixed-size pool of direct `ByteBuffer`s to avoid GC churn.
- Keep in-flight queues short:
  - Encoded queue depth target: 1-2 access units.
  - Drop policy: if sender lags, discard oldest P-frame first.
- Use monotonic timestamp source (`System.nanoTime`) mapped to RTP clock.
- Disable deep buffering in source and transport where possible.

## Preview + Stream Simultaneously

- Camera2 output configuration with two targets:
  1. Preview `Surface` (`TextureView`/`SurfaceView`).
  2. Encoder input `Surface`.
- For GPU pipelines, use EGL to render once to two framebuffers (preview + encoder).

## Camera Switching Without Restarting RTSP

- Keep RTSP control socket/session IDs stable.
- Pause sample forwarding.
- Recreate camera capture session with new camera target surface.
- Force IDR (`MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME`) and resume.
- If resolution/profile changes, regenerate SDP and require client re-SETUP.

## Dynamic Bitrate Adaptation

- Inputs:
  - RTP send queue occupancy.
  - Packet loss (RTCP receiver reports when implemented).
  - Measured send throughput.
- Policy:
  - Rapid decrease on congestion (15-25%).
  - Slow increase on stability (5-10%).
- Runtime update via `MediaCodec.setParameters(Bundle().putInt(PARAMETER_KEY_VIDEO_BITRATE,...))`.

## RTSPS Readiness

- Wrap RTSP TCP control socket in `SSLServerSocket`.
- Keep RTP over UDP unchanged initially, or use RTP-over-TCP interleaving for firewall/TLS-friendly deployments.
- Certificate pinning and mutual TLS can be added for managed clients.

## Tuning Checklist

- GOP (`I-frame interval`): 0.5-1s for quick recovery and low join latency.
- FPS: 24-30 for mobile thermals; 60 only if pipeline budget allows.
- Bitrate:
  - 720p30: ~2-4 Mbps.
  - 1080p30: ~4-8 Mbps.
- MTU payload target: ~1200 bytes for safe Wi-Fi traversal.
- Prefer AVC baseline/main for broad decoder compatibility; HEVC where supported and clients can decode.

## RTSP vs WebRTC (Latency + Ops)

- RTSP advantages:
  - Simpler stack on LAN.
  - Easier hardware pipeline control.
  - Lower CPU than full WebRTC in many one-way streaming scenarios.
- WebRTC advantages:
  - NAT traversal, congestion control, encryption by default.
  - Better internet-scale resilience.
- In constrained LAN production, tuned RTSP can reach ~80-150ms; WebRTC often ~150-400ms depending on jitter buffer policy.
