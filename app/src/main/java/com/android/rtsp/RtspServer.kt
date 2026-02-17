package com.android.rtsp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Minimal embedded RTSP server. Extend for auth, RTSPS, and RTCP feedback handling. */
@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
class RtspServer(
    private val port: Int = 8554,
    private val streamName: String = "live",
    private val sdpProvider: () -> String,
    private val onSetup: (clientIp: String, clientRtpPort: Int, sessionId: String) -> Unit,
    private val onTeardown: (sessionId: String) -> Unit,
) {
    private val executor = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    data class Session(val id: String, val clientIp: String, val clientRtpPort: Int)

    fun start() {
        if (running) return
        running = true
        serverSocket = ServerSocket(port)
        executor.submit {
            while (running) {
                val client = runCatching { serverSocket?.accept() }.getOrNull() ?: continue
                executor.submit { handleClient(client) }
            }
        }
    }

    fun stop() {
        running = false
        sessions.keys.forEach(onTeardown)
        sessions.clear()
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            var currentSessionId: String? = null

            while (running && !socket.isClosed) {
                val requestLine = reader.readLine() ?: break
                if (requestLine.isBlank()) continue

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    val sep = line.indexOf(':')
                    if (sep > 0) headers[line.substring(0, sep).trim()] = line.substring(sep + 1).trim()
                }

                val cSeq = headers["CSeq"] ?: "1"
                when {
                    requestLine.startsWith("OPTIONS") -> {
                        respond(writer, cSeq, 200, mapOf(
                            "Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN"
                        ))
                    }

                    requestLine.startsWith("DESCRIBE") -> {
                        val sdp = sdpProvider()
                        respond(writer, cSeq, 200, mapOf(
                            "Content-Base" to "rtsp://0.0.0.0:$port/$streamName/",
                            "Content-Type" to "application/sdp",
                            "Content-Length" to sdp.toByteArray().size.toString()
                        ), body = sdp)
                    }

                    requestLine.startsWith("SETUP") -> {
                        val transport = headers["Transport"].orEmpty()
                        val clientPort = parseClientPort(transport)
                        if (clientPort == null) {
                            respond(writer, cSeq, 461)
                            continue
                        }
                        val sessionId = UUID.randomUUID().toString()
                        currentSessionId = sessionId
                        sessions[sessionId] = Session(sessionId, socket.inetAddress.hostAddress, clientPort)
                        onSetup(socket.inetAddress.hostAddress, clientPort, sessionId)
                        respond(writer, cSeq, 200, mapOf(
                            "Session" to sessionId,
                            "Transport" to "RTP/AVP/UDP;unicast;client_port=$clientPort-${clientPort + 1};server_port=5004-5005"
                        ))
                    }

                    requestLine.startsWith("PLAY") -> {
                        val sid = headers["Session"] ?: currentSessionId
                        if (sid == null || !sessions.containsKey(sid)) {
                            respond(writer, cSeq, 454)
                            continue
                        }
                        respond(writer, cSeq, 200, mapOf("Session" to sid))
                    }

                    requestLine.startsWith("TEARDOWN") -> {
                        val sid = headers["Session"] ?: currentSessionId
                        if (sid != null) {
                            sessions.remove(sid)
                            onTeardown(sid)
                        }
                        respond(writer, cSeq, 200)
                        break
                    }

                    else -> respond(writer, cSeq, 405)
                }
            }
        }
    }

    private fun parseClientPort(transport: String): Int? {
        val token = transport.split(';').firstOrNull { it.trim().startsWith("client_port=") } ?: return null
        val range = token.substringAfter('=').split('-')
        return range.firstOrNull()?.toIntOrNull()
    }

    private fun respond(
        writer: PrintWriter,
        cSeq: String,
        code: Int,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ) {
        val msg = when (code) {
            200 -> "OK"
            405 -> "Method Not Allowed"
            454 -> "Session Not Found"
            461 -> "Unsupported Transport"
            else -> "Error"
        }

        writer.append("RTSP/1.0 $code $msg\r\n")
        writer.append("CSeq: $cSeq\r\n")
        headers.forEach { (k, v) -> writer.append("$k: $v\r\n") }
        if (body != null) writer.append("\r\n$body") else writer.append("\r\n")
        writer.flush()
    }
}