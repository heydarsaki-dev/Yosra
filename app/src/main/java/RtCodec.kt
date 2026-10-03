package ir.yosra.app

/**
 * کدک پروتکل realtime (Apinator / Pusher-compatible) — عمداً بدون هیچ
 * import اندرویدی تا روی JVM هم کامپایل و تست شود.
 *
 * قرارداد (AsyncAPI رسمی):
 *  - آدرس: wss://ws-{region}.apinator.io/app/{app_key}
 *  - subscribe: {"event":"realtime:subscribe","data":"{\"channel\":\"...\",\"auth\":\"key:sig\"}"}
 *  - ایونت کلاینت: {"event":"client-...","channel":"...","data":"{...}"}
 *  - امضا: hex(hmac_sha256(secret, "socketId:channel"))
 *  - data همیشه یک JSON-string است (دابل‌انکد).
 */
object RtCodec {

    const val OP_TEXT = 0x1
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    data class Frame(val opcode: Int, val payload: ByteArray)

    /** HMAC-SHA256 به‌صورت hex */
    fun hmacSha256Hex(secret: String, msg: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(
            javax.crypto.spec.SecretKeySpec(
                secret.toByteArray(Charsets.UTF_8),
                "HmacSHA256"
            )
        )
        val out = StringBuilder()
        for (b in mac.doFinal(msg.toByteArray(Charsets.UTF_8))) {
            val v = b.toInt() and 0xFF
            if (v < 16) out.append('0')
            out.append(Integer.toHexString(v))
        }
        return out.toString()
    }

    /** امضای auth کانال خصوصی: "appkey:hex(hmac(secret, socketId:channel))" */
    fun channelAuth(appKey: String, secret: String, socketId: String, channel: String): String =
        "$appKey:${hmacSha256Hex(secret, "$socketId:$channel")}"

    fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') {
                    sb.append("\\u")
                    val h = Integer.toHexString(ch.code)
                    for (i in 0 until 4 - h.length) sb.append('0')
                    sb.append(h)
                } else sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun buildSubscribe(channel: String, auth: String?): String {
        val inner = if (auth != null)
            "{\"channel\":\"${jsonEscape(channel)}\",\"auth\":\"${jsonEscape(auth)}\"}"
        else
            "{\"channel\":\"${jsonEscape(channel)}\"}"
        return "{\"event\":\"realtime:subscribe\",\"data\":\"${jsonEscape(inner)}\"}"
    }

    fun buildClientEvent(channel: String, name: String, dataJson: String): String =
        "{\"event\":\"${jsonEscape(name)}\"," +
            "\"channel\":\"${jsonEscape(channel)}\"," +
            "\"data\":\"${jsonEscape(dataJson)}\"}"

    fun buildPong(): String = "{\"event\":\"realtime:pong\",\"data\":\"{}\"}"

    /** payload خبر «دیتابیس عوض شد» — فقط شناسه و زمان، بدون دادهٔ حساس */
    fun eventPayload(from: String, at: Long): String =
        "{\"from\":\"${jsonEscape(from)}\",\"at\":$at}"

    /** فریم ماسک‌دار کلاینت→سرور (ماسک اجباری است) */
    fun encodeFrame(opcode: Int, payload: ByteArray): ByteArray {
        val mask = ByteArray(4)
        java.security.SecureRandom().nextBytes(mask)
        val n = payload.size
        val hlen = 2 + (if (n > 65535) 8 else if (n > 125) 2 else 0) + 4
        val out = ByteArray(hlen + n)
        out[0] = (0x80 or opcode).toByte()
        var p = 1
        if (n > 65535) {
            out[1] = (0x80 or 127).toByte()
            val v = n.toLong()
            for (i in 7 downTo 0) out[2 + (7 - i)] = ((v ushr (i * 8)) and 0xFF).toByte()
            p = 10
        } else if (n > 125) {
            out[1] = (0x80 or 126).toByte()
            out[2] = ((n ushr 8) and 0xFF).toByte()
            out[3] = (n and 0xFF).toByte()
            p = 4
        } else {
            out[1] = (0x80 or n).toByte()
            p = 2
        }
        for (i in 0 until 4) out[p + i] = mask[i]
        p += 4
        for (i in payload.indices) {
            out[p + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return out
    }

    fun encodeText(text: String): ByteArray =
        encodeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    /**
     * پارسر افزایشی فریم‌های سمت سرور — بایت‌به‌بایت هم قابل تغذیه است.
     * fragmentation (continuation)، طول‌های ۱۶/۶۴ بیتی و ping/pong/close
     * وسط پیام را هم مدیریت می‌کند.
     */
    class Decoder {
        private var acc = ByteArray(0)
        private var fragOp = -1
        private var fragBuf = ByteArray(0)

        fun feed(data: ByteArray, off: Int = 0, len: Int = data.size - off): List<Frame> {
            val joined = ByteArray(acc.size + len)
            System.arraycopy(acc, 0, joined, 0, acc.size)
            System.arraycopy(data, off, joined, acc.size, len)
            val out = ArrayList<Frame>()
            var pos = 0
            while (true) {
                if (joined.size - pos < 2) break
                val b0 = joined[pos].toInt() and 0xFF
                val b1 = joined[pos + 1].toInt() and 0xFF
                val fin = b0 and 0x80 != 0
                val op = b0 and 0x0F
                val masked = b1 and 0x80 != 0
                var n: Long = (b1 and 0x7F).toLong()
                var hlen = 2
                if (n == 126L) {
                    if (joined.size - pos < 4) break
                    n = ((joined[pos + 2].toLong() and 0xFF) shl 8) or
                        (joined[pos + 3].toLong() and 0xFF)
                    hlen = 4
                } else if (n == 127L) {
                    if (joined.size - pos < 10) break
                    var v = 0L
                    for (i in 0 until 8) v = (v shl 8) or (joined[pos + 2 + i].toLong() and 0xFF)
                    if (v > 16 * 1024 * 1024) break // سقف عقلانی ۱۶MB
                    n = v
                    hlen = 10
                }
                var keyOff = -1
                if (masked) {
                    if (joined.size - pos < hlen + 4) break
                    keyOff = pos + hlen
                    hlen += 4
                }
                if (joined.size - pos < hlen + n) break
                var payload = joined.copyOfRange(pos + hlen, (pos + hlen + n).toInt())
                if (masked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor
                            (joined[keyOff + i % 4].toInt() and 0xFF)).toByte()
                    }
                }
                pos += (hlen + n).toInt()
                when (op) {
                    0x0 -> { // continuation
                        if (fragOp >= 0) {
                            fragBuf += payload
                            if (fin) {
                                out.add(Frame(fragOp, fragBuf))
                                fragBuf = ByteArray(0)
                                fragOp = -1
                            }
                        }
                    }
                    OP_TEXT, 0x2 -> {
                        if (fin) out.add(Frame(op, payload))
                        else {
                            fragOp = op
                            fragBuf = payload
                        }
                    }
                    OP_PING, OP_PONG, OP_CLOSE -> out.add(Frame(op, payload))
                    else -> { /* نادیده */ }
                }
            }
            acc = joined.copyOfRange(pos, joined.size)
            return out
        }
    }
}
