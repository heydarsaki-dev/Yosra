package ir.yosra.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * کلاینت realtime (Apinator / Pusher-compatible) — گوش‌به‌زنگِ لحظه‌ایِ
 * «دیتابیس آنلاین عوض شد» برای هر دو طرف (اپ و وب).
 *
 *  - اتصال: wss://ws-{region}.apinator.io/app/{app_key} (سوکت خام TLS، بدون کتابخانه)
 *  - کانال خصوصی private-yosra-sync با auth محلی: "key:hmac(secret, socketId:channel)"
 *  - بعد از هر push موفق به گیت‌هاب، ایونت client-db-updated ارسال می‌شود.
 *  - با دریافت ایونت، Sync.refreshNow اجرا و صفحهٔ باز بازسازی می‌شود.
 *  - اگر سوکت قطع باشد، همان پولینگ/سینک‌های دوره‌ای پشتیبان است.
 */
object Rt {

    const val RT_KEY = "app_07f637954851fb7feb1ba8922693995e3a8e7dd2"
    private const val RT_REGION = "eu"
    private const val RT_HOST = "ws-eu.apinator.io"
    private const val RT_CHANNEL = "private-yosra-sync"
    private const val RT_EVENT = "client-db-updated"

    @Volatile private var running = false
    @Volatile private var subscribed = false
    // خبری که هنگام قطع بودن سوکت داده شده — به محض وصل شدن ارسال می‌شود
    // تا pushِ حین reconnect گم نشود
    @Volatile private var pendingNotify = false
    private var worker: Thread? = null
    private var appCtx: Context? = null

    private val sendLock = Any()
    private var out: java.io.OutputStream? = null

    /** شروع حلقهٔ اتصال (idempotent) — از YosraApp.onCreate صدا بزن */
    fun ensure(c: Context) {
        appCtx = c.applicationContext
        if (running) return
        running = true
        worker = Thread({ loop() }, "yosra-rt").apply { isDaemon = true; start() }
    }

    /** سکرت realtime (با رمز کاربر باز و در prefs ذخیره می‌شود) */
    fun secret(c: Context): String =
        try {
            c.getSharedPreferences("sync", Context.MODE_PRIVATE).getString("rt_secret", "") ?: ""
        } catch (_: Exception) {
            ""
        }

    private fun deviceId(c: Context): String {
        return try {
            val p = c.getSharedPreferences("sync", Context.MODE_PRIVATE)
            var id = p.getString("rt_device", null)
            if (id.isNullOrEmpty()) {
                id = "a-" + java.util.UUID.randomUUID().toString().take(8)
                p.edit().putString("rt_device", id).apply()
            }
            id
        } catch (_: Exception) {
            "a-unknown"
        }
    }

    private fun loop() {
        var retry = 0
        while (running) {
            try {
                val ctx = appCtx ?: break
                if (secret(ctx).isEmpty()) {
                    // هنوز لاگین نشده — بعداً دوباره
                    try {
                        Thread.sleep(5000)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                if (connectOnce(ctx)) retry = 0 else retry++
            } catch (_: Exception) {
                retry++
            }
            try {
                val wait = minOf(1000L shl retry.coerceAtMost(5), 30000L)
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** یک اتصال کامل تا قطع شدن؛ true یعنی حداقل یک‌بار وصل و سابسکرایب شد */
    private fun connectOnce(ctx: Context): Boolean {
        var sock: SSLSocket? = null
        var ok = false
        try {
            val sf = SSLSocketFactory.getDefault()
            sock = sf.createSocket(RT_HOST, 443) as SSLSocket
            sock.soTimeout = 130_000
            sock.startHandshake()
            val input = sock.inputStream
            val output = sock.outputStream
            if (!handshake(input, output)) return false
            synchronized(sendLock) { out = output }
            ok = readLoop(ctx, input)
        } catch (_: Exception) {
        } finally {
            synchronized(sendLock) { out = null }
            subscribed = false
            try {
                sock?.close()
            } catch (_: Exception) {
            }
        }
        return ok
    }

    private fun handshake(input: InputStream, output: java.io.OutputStream): Boolean {
        val nonce = ByteArray(16)
        java.security.SecureRandom().nextBytes(nonce)
        val key = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val req = "GET /app/$RT_KEY HTTP/1.1\r\n" +
            "Host: $RT_HOST\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Origin: https://yosra.app\r\n\r\n"
        output.write(req.toByteArray(Charsets.US_ASCII))
        output.flush()
        // هدر پاسخ بایت‌به‌بایت (بدون بافر اضافی تا فریم‌ها خورده نشوند)
        val head = ByteArrayOutputStream()
        val one = ByteArray(1)
        var matched = 0
        val tail = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (head.size() < 8192) {
            val n = input.read(one)
            if (n <= 0) return false
            head.write(one[0].toInt())
            matched = if (one[0] == tail[matched]) matched + 1 else if (one[0] == tail[0]) 1 else 0
            if (matched == 4) break
        }
        val status = String(head.toByteArray(), Charsets.US_ASCII).lineSequence().firstOrNull() ?: ""
        return status.contains("101")
    }

    private fun sendRaw(bytes: ByteArray): Boolean {
        synchronized(sendLock) {
            val o = out ?: return false
            return try {
                o.write(bytes)
                o.flush()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun sendText(s: String): Boolean =
        sendRaw(RtCodec.encodeText(s))

    private fun readLoop(ctx: Context, input: InputStream): Boolean {
        val dec = RtCodec.Decoder()
        val buf = ByteArray(8192)
        var everSubscribed = false
        while (running) {
            val n = try {
                input.read(buf)
            } catch (_: Exception) {
                break
            }
            if (n == null || n <= 0) break
            val frames = try {
                dec.feed(buf, 0, n)
            } catch (_: Exception) {
                break
            }
            for (f in frames) {
                when (f.opcode) {
                    RtCodec.OP_PING -> sendRaw(RtCodec.encodeFrame(RtCodec.OP_PONG, f.payload))
                    RtCodec.OP_CLOSE -> return everSubscribed
                    RtCodec.OP_TEXT -> {
                        if (onText(ctx, String(f.payload, Charsets.UTF_8))) everSubscribed = true
                    }
                    else -> { /* ignore */ }
                }
            }
        }
        return everSubscribed
    }

    /** پردازش پیام متنی؛ true یعنی سابسکرایب تأیید شد */
    private fun onText(ctx: Context, raw: String): Boolean {
        val ev: String
        val channel: String?
        val data: String
        try {
            val o = org.json.JSONObject(raw)
            ev = o.optString("event")
            channel = if (o.has("channel")) o.optString("channel") else null
            data = o.optString("data")
        } catch (_: Exception) {
            return false
        }
        when {
            ev == "realtime:connection_established" -> {
                try {
                    val d = org.json.JSONObject(data)
                    val sid = d.optString("socket_id")
                    if (sid.isEmpty()) return false
                    val sec = secret(ctx)
                    if (sec.isEmpty()) return false
                    val auth = RtCodec.channelAuth(RT_KEY, sec, sid, RT_CHANNEL)
                    sendText(RtCodec.buildSubscribe(RT_CHANNEL, auth))
                } catch (_: Exception) {
                }
            }
            ev == "realtime:subscription_succeeded" && channel == RT_CHANNEL -> {
                subscribed = true
                // خبرِ جامانده در دوران قطعی همین حالا ارسال شود
                if (pendingNotify) {
                    pendingNotify = false
                    notifyDbChanged()
                }
                return true
            }
            ev == "realtime:subscription_error" -> {
                // auth رد شد → اتصال مجدد با backoff (شاید سکرت تازه شده باشد)
                throw java.io.IOException("realtime:subscription_error")
            }
            ev == "realtime:ping" -> {
                sendText(RtCodec.buildPong())
            }
            ev == RT_EVENT && channel == RT_CHANNEL -> {
                try {
                    val p = org.json.JSONObject(data)
                    val from = p.optString("from")
                    if (from.isNotEmpty() && from != deviceId(ctx)) onRemoteEvent(ctx)
                } catch (_: Exception) {
                }
            }
            else -> { /* ignore */ }
        }
        return false
    }

    /** خبر به طرف دیگر که دیتابیس آنلاین را عوض کردیم (best-effort) */
    fun notifyDbChanged() {
        try {
            val ctx = appCtx ?: return
            if (!subscribed) {
                pendingNotify = true // به محض سابسکرایب ارسال می‌شود
                return
            }
            val payload = RtCodec.eventPayload(deviceId(ctx), System.currentTimeMillis())
            if (!sendText(RtCodec.buildClientEvent(RT_CHANNEL, RT_EVENT, payload))) {
                pendingNotify = true // سوکت در حال قطع است → بعداً دوباره
            }
        } catch (_: Exception) {
            pendingNotify = true
        }
    }

    private fun onRemoteEvent(ctx: Context, tries: Int = 0) {
        try {
            Sync.refreshNow(ctx) { changed, msg ->
                when {
                    changed -> {
                        U.toast(ctx, "📥 نسخهٔ تازه رسید", false, U.TOAST_OK)
                        val act = YosraApp.currentActivity() as? BaseActivity
                        if (act != null && act.allowBgSync() && !act.isFinishing && !act.isDestroyed) {
                            act.recreate()
                        }
                    }
                    // سینک دیگری در جریان است → خبر رها نشود، کمی بعد دوباره
                    msg.startsWith("⏳") && tries < 4 -> {
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({ onRemoteEvent(ctx, tries + 1) }, 1500)
                    }
                    msg.startsWith("⚠️") || msg.startsWith("ابتدا") ->
                        U.toast(ctx, msg, true, U.TOAST_ERR)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** اتصال برای YosraApp + ردیابی اکتیویتی جاری */
    class Lifecycle : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {
            YosraApp.foreground(a)
        }
        override fun onActivityPaused(a: Activity) {
            YosraApp.background(a)
        }
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {
            YosraApp.background(a)
        }
    }
}
