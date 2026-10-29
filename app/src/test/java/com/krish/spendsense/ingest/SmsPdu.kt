package com.krish.spendsense.ingest

import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime

/** Builds a 3GPP SMS-DELIVER PDU (GSM 7-bit, alphanumeric sender) so tests can drive the real receiver. */
object SmsPdu {
    private const val GSM =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001BÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    private fun septets(s: String) = s.map { ch -> GSM.indexOf(ch).also { require(it >= 0) { "'$ch' is not in the GSM 7-bit alphabet" } } }

    private fun pack(s: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        var acc = 0
        var bits = 0
        for (c in s) {
            acc = acc or (c shl bits)
            bits += 7
            while (bits >= 8) {
                out.write(acc and 0xFF)
                acc = acc ushr 8
                bits -= 8
            }
        }
        if (bits > 0) out.write(acc and 0xFF)
        return out.toByteArray()
    }

    private fun bcd(v: Int) = ((v % 10) shl 4) or (v / 10)

    fun deliver(sender: String, body: String, at: ZonedDateTime): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00) // no SMSC
        out.write(0x04) // SMS-DELIVER, no more messages
        val addr = septets(sender)
        out.write((addr.size * 7 + 3) / 4) // useful semi-octets
        out.write(0xD0) // alphanumeric address
        out.write(pack(addr))
        out.write(0x00) // PID
        out.write(0x00) // DCS: GSM 7-bit
        listOf(at.year % 100, at.monthValue, at.dayOfMonth, at.hour, at.minute, at.second).forEach { out.write(bcd(it)) }
        out.write(bcd(at.offset.totalSeconds / 900)) // time zone in quarter hours (positive)
        val text = septets(body)
        out.write(text.size)
        out.write(pack(text))
        return out.toByteArray()
    }
}
