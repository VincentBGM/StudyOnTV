package com.studyontv.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 免费内置微软 Edge 在线朗读（Read-Aloud WebSocket 协议，零第三方依赖）。
 *
 * 使用标准 TLS Socket + RFC 6455 WebSocket 握手与分帧，并按当前微软要求生成
 * `Sec-MS-GEC` 时间窗签名。合成结果返回 MP3 字节流，由 [SpeechReader] 缓存并播放。
 */
object EdgeTts {
    private const val HOST = "speech.platform.bing.com"
    private const val PORT = 443
    private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_VERSION = "130.0.2849.68"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    private const val MAX_AUDIO_BYTES = 4 * 1024 * 1024

    val VOICES = listOf(
        "zh-CN-XiaoxiaoNeural",
        "zh-CN-YunxiNeural",
        "zh-CN-YunjianNeural",
        "zh-CN-XiaoyiNeural",
        "zh-HK-HiuGaaiNeural",
        "zh-TW-HsiaoChenNeural",
        "en-US-JennyNeural",
        "en-GB-SoniaNeural",
    )

    fun defaultVoice(language: String): String {
        val tag = language.lowercase(Locale.US)
        return when {
            tag.startsWith("zh-hk") || tag.startsWith("yue") -> "zh-HK-HiuGaaiNeural"
            tag.startsWith("zh-tw") -> "zh-TW-HsiaoChenNeural"
            tag.startsWith("en-gb") -> "en-GB-SoniaNeural"
            tag.startsWith("en") -> "en-US-JennyNeural"
            else -> "zh-CN-XiaoxiaoNeural"
        }
    }

    fun synthesize(text: String, voice: String, language: String = ""): ByteArray {
        val chosenVoice = voice.trim().ifEmpty { defaultVoice(language) }
        val connId = UUID.randomUUID().toString().replace("-", "")
        val muid = randomHex(16)
        val path = "/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_TOKEN" +
            "&Sec-MS-GEC=${secMsGec()}" +
            "&Sec-MS-GEC-Version=1-$CHROMIUM_VERSION" +
            "&ConnectionId=$connId"

        val socket = (SSLSocketFactory.getDefault().createSocket(HOST, PORT) as SSLSocket).apply {
            soTimeout = 15_000
            startHandshake()
        }
        try {
            val out = socket.outputStream
            val input = BufferedInputStream(socket.inputStream, 16 * 1024)
            handshake(input, out, path, muid)

            val ts = isoTimestamp()
            val configMsg = "X-Timestamp:$ts\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}"
            sendTextFrame(out, configMsg)

            val reqId = UUID.randomUUID().toString().replace("-", "")
            val lang = chosenVoice.split("-").take(2).joinToString("-").ifEmpty { "zh-CN" }
            val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
                "<voice name='$chosenVoice'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>${escapeXml(text)}</prosody></voice></speak>"
            val ssmlMsg = "X-RequestId:$reqId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:$ts\r\n" +
                "Path:ssml\r\n\r\n$ssml"
            sendTextFrame(out, ssmlMsg)

            val audio = ByteArrayOutputStream()
            while (true) {
                val frame = readFrame(input) ?: break
                when (frame.opcode) {
                    0x1 -> { // text
                        val msg = String(frame.payload, Charsets.UTF_8)
                        if (msg.contains("Path:turn.end")) break
                    }
                    0x2 -> { // binary
                        val data = frame.payload
                        if (data.size >= 2) {
                            val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                            val bodyStart = 2 + headerLen
                            if (bodyStart < data.size) {
                                require(audio.size() + (data.size - bodyStart) <= MAX_AUDIO_BYTES) { "Edge 语音返回数据过大" }
                                audio.write(data, bodyStart, data.size - bodyStart)
                            }
                        }
                    }
                    0x8 -> break // close
                    0x9 -> sendPongFrame(out, frame.payload)
                }
            }
            sendCloseFrame(out)
            val result = audio.toByteArray()
            require(result.isNotEmpty()) { "Edge 语音返回空音频" }
            return result
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    /** Sec-MS-GEC = SHA256(rounded_windows_ticks + TrustedClientToken) 大写十六进制 */
    internal fun secMsGec(nowMillis: Long = System.currentTimeMillis()): String {
        var ticks = (nowMillis / 1000L) + 11_644_473_600L
        ticks -= ticks % 300L
        ticks *= 10_000_000L
        val raw = "$ticks$TRUSTED_TOKEN".toByteArray(Charsets.US_ASCII)
        return MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02X".format(Locale.US, it) }
    }

    private fun handshake(input: BufferedInputStream, out: OutputStream, path: String, muid: String) {
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wsKey = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(HOST).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Origin: ").append(ORIGIN).append("\r\n")
            append("User-Agent: ").append(USER_AGENT).append("\r\n")
            append("Pragma: no-cache\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Cookie: muid=").append(muid).append(";\r\n\r\n")
        }
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()

        val statusLine = readHttpLine(input)
        require(statusLine.contains("101")) { "Edge 语音握手失败：$statusLine" }
        while (true) {
            val line = readHttpLine(input)
            if (line.isEmpty()) break
        }
    }

    private fun readHttpLine(input: BufferedInputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw EOFException("Edge 语音连接意外关闭")
            if (b == '\n'.code) {
                val arr = buf.toByteArray()
                val len = if (arr.isNotEmpty() && arr[arr.size - 1] == '\r'.code.toByte()) arr.size - 1 else arr.size
                return String(arr, 0, len, Charsets.UTF_8)
            }
            buf.write(b)
            require(buf.size() <= 8192) { "Edge 响应头过长" }
        }
    }

    private class Frame(val opcode: Int, val payload: ByteArray)

    private fun readFrame(input: InputStream): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        var len = (second and 0x7F).toLong()
        if (len == 126L) {
            val b1 = input.read(); val b2 = input.read()
            if (b1 < 0 || b2 < 0) throw EOFException()
            len = ((b1 shl 8) or b2).toLong()
        } else if (len == 127L) {
            var v = 0L
            for (i in 0 until 8) {
                val b = input.read(); if (b < 0) throw EOFException()
                v = (v shl 8) or b.toLong()
            }
            len = v
        }
        require(len in 0..MAX_AUDIO_BYTES) { "Edge 帧过大" }
        val mask = if (masked) readExact(input, 4) else null
        val payload = readExact(input, len.toInt())
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(opcode, payload)
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = input.read(out, off, size - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return out
    }

    private fun sendTextFrame(out: OutputStream, text: String) = sendFrame(out, 0x1, text.toByteArray(Charsets.UTF_8))
    private fun sendPongFrame(out: OutputStream, payload: ByteArray) = sendFrame(out, 0xA, payload)
    private fun sendCloseFrame(out: OutputStream) {
        try { sendFrame(out, 0x8, ByteArray(0)) } catch (_: Exception) { }
    }

    private fun sendFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream()
        header.write(0x80 or (opcode and 0x0F))
        val len = payload.size
        when {
            len <= 125 -> header.write(0x80 or len)
            len <= 65535 -> {
                header.write(0x80 or 126)
                header.write((len ushr 8) and 0xFF)
                header.write(len and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write(((len.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(len) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        synchronized(out) {
            out.write(header.toByteArray())
            out.write(masked)
            out.flush()
        }
    }

    private fun isoTimestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(Locale.US, it) }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
