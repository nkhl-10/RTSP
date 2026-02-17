package com.android.rtsp

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.min

/** Basic H264 RTP packetizer supporting single-NAL and FU-A fragmentation. */
class H264RtpPacketizer(
    private val destination: InetAddress,
    private val port: Int,
    private val ssrc: Int,
    private val mtu: Int = 1200,
    private val payloadType: Int = 96,
) {
    private val socket = DatagramSocket()
    private var sequenceNumber = 0
    private val rtpClock = 90_000L

    fun packetizeAccessUnit(annexB: ByteArray, ptsUs: Long, marker: Boolean = true) {
        val timestamp = (ptsUs * rtpClock / 1_000_000L).toInt()
        val nals = splitAnnexB(annexB)
        nals.forEachIndexed { index, nal ->
            val isAuEnd = marker && index == nals.lastIndex
            if (nal.size + 12 <= mtu) {
                sendRtp(buildHeader(timestamp, isAuEnd) + nal)
            } else {
                fragmentFuA(nal, timestamp, isAuEnd)
            }
        }
    }

    private fun fragmentFuA(nal: ByteArray, timestamp: Int, marker: Boolean) {
        val nalHeader = nal[0].toInt() and 0xFF
        val nri = nalHeader and 0x60
        val type = nalHeader and 0x1F
        val fuIndicator = (nalHeader and 0x80) or nri or 28
        var offset = 1
        val payloadMax = mtu - 12 - 2
        var first = true

        while (offset < nal.size) {
            val size = min(payloadMax, nal.size - offset)
            val end = offset + size >= nal.size
            val fuHeader = (if (first) 0x80 else 0) or (if (end) 0x40 else 0) or type
            val header = buildHeader(timestamp, marker && end)
            val packet = ByteArray(12 + 2 + size)
            System.arraycopy(header, 0, packet, 0, 12)
            packet[12] = fuIndicator.toByte()
            packet[13] = fuHeader.toByte()
            System.arraycopy(nal, offset, packet, 14, size)
            sendRtp(packet)
            first = false
            offset += size
        }
    }

    private fun buildHeader(timestamp: Int, marker: Boolean): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = ((if (marker) 0x80 else 0) or payloadType).toByte()
        header[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        header[3] = (sequenceNumber and 0xFF).toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        header[4] = ((timestamp shr 24) and 0xFF).toByte()
        header[5] = ((timestamp shr 16) and 0xFF).toByte()
        header[6] = ((timestamp shr 8) and 0xFF).toByte()
        header[7] = (timestamp and 0xFF).toByte()
        header[8] = ((ssrc shr 24) and 0xFF).toByte()
        header[9] = ((ssrc shr 16) and 0xFF).toByte()
        header[10] = ((ssrc shr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }

    private fun sendRtp(packetData: ByteArray) {
        socket.send(DatagramPacket(packetData, packetData.size, destination, port))
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                ((data[i + 2] == 1.toByte()) || (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()))
            ) {
                starts += i
                i += if (data[i + 2] == 1.toByte()) 3 else 4
            } else i++
        }
        if (starts.isEmpty()) return listOf(data)
        val units = mutableListOf<ByteArray>()
        for (idx in starts.indices) {
            val startCodeLen = if (data[starts[idx] + 2] == 1.toByte()) 3 else 4
            val start = starts[idx] + startCodeLen
            val end = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            if (end > start) units += data.copyOfRange(start, end)
        }
        return units
    }
}