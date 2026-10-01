package ir.yosra.app

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * همگام‌سازی دیتابیس با یک ریپوی خصوصی در گیت‌هاب (yosra-backup).
 * دلیل خصوصی بودن: ریپوی اصلی عمومی است و دیتابیس مالی نباید دیده شود.
 *
 * سناریوها:
 *  - تمیز + آنلاین جدیدتر      → دریافت و جایگزینی
 *  - تغییر محلی + آنلاین قدیم‌تر → آپلود
 *  - تغییر محلی + آنلاین جدیدتر → **ادغام**: دریافت نسخهٔ آنلاین، اعمال
 *    تغییرات محلی روی آن (با ثبت تغییرات در outbox) و سپس آپلود نتیجه.
 *    این یعنی داده‌های هیچ‌یک از دو طرف (اپ اندروید / داشبورد وب) گم نمی‌شود.
 *  - بدون توکن → هیچ کاری نمی‌کند
 */
object Sync {

    private const val REPO = "yosra-backup"
    private const val FILE = "yosra.db"
    private const val OUTBOX = "outbox"
    private const val OUTBOX_CAP = 300

    private val busy = AtomicBoolean(false)
    private val pushPending = AtomicBoolean(false)
    private val pushWorker = AtomicBoolean(false)
    private const val BUSY_MSG = "⏳ همگام‌سازی در جریان است..."

    /** آیا سینکی در جریان است؟ (برای نگهبان SyncService) */
    fun isSyncActive(): Boolean = pushWorker.get() || pushPending.get() || busy.get()

    /** وقتی اسپلش رفت جلو، بازیابی دیرهنگام متوقف می‌شود تا زیر دست اپ نپرد */
    private val lock = Any()
    @Volatile private var splashDone = false
    fun markSplashDone() = synchronized(lock) { splashDone = true }

    /** در صفحهٔ ورود: همگام‌سازی اجازه دارد دیتابیس را جایگزین کند (هنوز وارد خانه نشده‌ایم) */
    fun markPreHome() = synchronized(lock) { splashDone = false }

    private class Repo(val login: String, val branch: String)

    private fun prefs(c: Context) = c.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /**
     * توکن رمزنگاری‌شده داخل سورس — ساختار: base64(salt(16) || xor(token, pbkdf2))
     * فقط با رمز کاربر باز می‌شود (PBKDF2-HMAC-SHA1، ۲۰۰۰۰ تکرار)
     */
    private const val ENC_TOKEN = "ty9APWyqSgyB8AGuIcg48Uq9EoTKT5osSTGcLov8ilJU0C4THMkxQ4UNmeJFoEN3S/EsKFqni0s="

    /**
     * سکرت realtime رمزنگاری‌شده داخل سورس — همان ساختار ENC_TOKEN
     * (base64(salt(16) || xor(secret, pbkdf2))) با همان رمز کاربر.
     */
    private const val ENC_RT_SECRET = "VQAKEvGN7Oa5Qh6IpBgr9Dmupo+7/sGMkvJY32ecdNRR7Aa3D5WeIv2TBMM7N3QHRpLyq9mSQhmc8xzCUG5Paany4jgt9vuX1lfgh3dmEmA="

    /** باز کردن سکرت realtime و ذخیره در prefs؛ خطا → login خراب نمی‌شود */
    private fun unlockRt(c: Context, password: String) {
        try {
            val raw = Base64.decode(ENC_RT_SECRET, Base64.NO_WRAP)
            if (raw.size < 17) return
            val salt = raw.copyOfRange(0, 16)
            val ct = raw.copyOfRange(16, raw.size)
            val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 20000, ct.size * 8)
            val ks = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(spec).encoded
            val out = ByteArray(ct.size) { (ct[it].toInt() xor ks[it].toInt()).toByte() }
            val sec = String(out, Charsets.UTF_8)
            if (sec.length == 64 && sec.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                prefs(c).edit().putString("rt_secret", sec).apply()
            }
        } catch (_: Exception) {
        }
    }

    /** با رمز کاربر توکن داخل سورس را باز می‌کند؛ رمز اشتباه → false */
    fun unlockWithPassword(c: Context, password: String): Boolean {
        if (password.isEmpty()) return false
        return try {
            val raw = Base64.decode(ENC_TOKEN, Base64.NO_WRAP)
            if (raw.size < 33) return false
            val salt = raw.copyOfRange(0, 16)
            val ct = raw.copyOfRange(16, raw.size)
            val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 20000, ct.size * 8)
            val ks = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(spec).encoded
            val out = ByteArray(ct.size) { (ct[it].toInt() xor ks[it].toInt()).toByte() }
            val token = String(out, Charsets.UTF_8)
            if (!token.startsWith("ghp_") && !token.startsWith("github_pat_")) return false
            saveToken(c, token)
            try {
                unlockRt(c, password)
            } catch (_: Exception) {
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun token(c: Context): String = prefs(c).getString("token", "") ?: ""
    fun saveToken(c: Context, t: String) {
        prefs(c).edit().putString("token", t.trim()).apply()
    }

    private fun lastSync(c: Context): Long = prefs(c).getLong("lastSync", 0L)
    private fun setLastSync(c: Context, t: Long) {
        prefs(c).edit().putLong("lastSync", t).apply()
    }

    /**
     * sha آخرین نسخهٔ آنلاینی که دیده‌ایم — شرط pull با همین مقایسه می‌شود،
     * نه با زمان: زمان کامیت دقت ثانیه دارد و ساعت گوشی با ساعت گیت‌هاب
     * ممکن است اختلاف داشته باشد؛ با مقایسهٔ زمانی، کامیتِ تازهٔ وب بین دو
     * سینکِ اپ برای همیشه نادیده گرفته می‌شد (درست مثل باگی که وب داشت).
     */
    private fun lastRemoteSha(c: Context): String = prefs(c).getString("remoteSha", "") ?: ""
    private fun setLastRemoteSha(c: Context, sha: String) {
        if (sha.isNotEmpty()) prefs(c).edit().putString("remoteSha", sha).apply()
    }

    /** آنلاین چیزی دارد که هنوز ندیده‌ایم؟ */
    private fun unseenSha(c: Context, sha: String) = sha.isNotEmpty() && sha != lastRemoteSha(c)

    fun lastSyncText(c: Context): String {
        val t = lastSync(c)
        if (t == 0L) return "هنوز همگام‌سازی نشده"
        val j = U.jParts(t)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val h = U.fa(cal.get(java.util.Calendar.HOUR_OF_DAY).toString())
        val m = U.fa(cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0'))
        return "${U.nice(t)} ساعت $h:$m"
    }

    /** تمیز = از آخرین همگام‌سازی تغییری نکرده */
    private fun isDirty(c: Context): Boolean {
        Db.checkpointNow()
        return Db.dbFile().lastModified() > lastSync(c)
    }

    // ───────────────────────── ثبت تغییرات محلی (outbox) ─────────────────────────
    // هر تغییری که اپ می‌دهد (درج/ویرایش/حذف) در این صف می‌افتد تا هنگام تداخل،
    // روی نسخهٔ آنلاین اعمال شود. op: 0=درج 1=ویرایش 2=حذف

    data class OutEntry(val table: String, val keyParts: List<Long>, val op: Int, val row: ContentValues)

    @Synchronized
    fun logChange(c: Context, table: String, key: String, op: Int, cv: ContentValues?) {
        try {
            val arr = loadOutbox(c)
            val o = JSONObject()
            o.put("t", table)
            o.put("k", key)
            o.put("o", op)
            if (cv != null) {
                val r = JSONObject()
                for (k in cv.keySet()) {
                    val v = cv.get(k) ?: continue
                    when (v) {
                        is Long -> r.put(k, v)
                        is Int -> r.put(k, v)
                        is String -> r.put(k, v)
                        is Boolean -> r.put(k, v)
                        else -> r.put(k, v.toString())
                    }
                }
                o.put("r", r)
            }
            arr.put(o)
            // کاپ: قدیمی‌ترین ورودی‌ها حذف می‌شوند
            val toDrop = arr.length() - OUTBOX_CAP
            val save = if (toDrop > 0) {
                val keep = JSONArray()
                for (i in toDrop until arr.length()) keep.put(arr[i])
                keep
            } else arr
            prefs(c).edit().putString(OUTBOX, save.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadOutbox(c: Context): JSONArray {
        val raw = prefs(c).getString(OUTBOX, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    fun outbox(c: Context): List<OutEntry> {
        val out = ArrayList<OutEntry>()
        val arr = loadOutbox(c)
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val parts = o.optString("k").split(",").map { it.trim().toLong() }
                val r = o.optJSONObject("r")
                val cv = ContentValues()
                if (r != null) {
                    val keys = r.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        when (val v = r.get(k)) {
                            is Number -> cv.put(k, v.toLong())
                            is String -> cv.put(k, v)
                            is Boolean -> cv.put(k, v)
                        }
                    }
                }
                out.add(OutEntry(o.optString("t"), parts, o.optInt("o"), cv))
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun clearOutbox(c: Context) {
        prefs(c).edit().remove(OUTBOX).apply()
    }

    /** آیا تغییرات محلیِ آپلودنشده وجود دارد؟ */
    fun hasPending(c: Context): Boolean = loadOutbox(c).length() > 0

    // ───────────────────────── هسته HTTP ─────────────────────────

    private fun req(method: String, path: String, body: String? = null, tk: String): Pair<Int, String> {
        var last: Pair<Int, String>? = null
        for (attempt in 0..1) {
            try {
                val r = doReq(method, path, body, tk)
                last = r
                // PUT تکرار نمی‌شود (پاسخ گم‌شده یعنی شاید اعمال شده — تکرار → 409)
                if (r.first in 200..299 || method == "PUT" || attempt == 1 || !isTransient(r.first, r.second)) return r
                Thread.sleep(800L)
            } catch (e: Exception) {
                if (attempt == 1) throw e
                Thread.sleep(800L) // قطعی لحظه‌ای وی‌پیِن → یک بار دوباره
            }
        }
        return last ?: (0 to "")
    }

    private fun doReq(method: String, path: String, body: String? = null, tk: String): Pair<Int, String> {
        val conn = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 6000
        conn.readTimeout = 12000
        conn.setRequestProperty("Authorization", "Bearer $tk")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    private fun errMessage(code: Int, text: String): String {
        val raw = text.trim()
        val msg = try {
            val m = JSONObject(text).optString("message", "")
            if (m.isNotEmpty()) m else raw.take(120)
        } catch (_: Exception) {
            // پاسخ غیر-JSON — معمولاً صفحهٔ WAF گیت‌هاب هنگام عوض شدن IP
            raw.take(120)
        }
        val lower = msg.lowercase()
        val isRate = code == 429 || lower.contains("rate limit") || lower.contains("secondary")
        val isWaf = lower.contains("malicious") || lower.contains("abuse") || raw.startsWith("<")
        return when {
            code == 401 -> "توکن نامعتبر است ❌"
            isRate -> "گیت‌هاب موقتاً درخواست‌ها را محدود کرده — چند دقیقه بعد دوباره تلاش کن ⏳"
            code == 403 -> "دسترسی توکن کافی نیست ❌"
            code == 404 -> "ریپو پیدا نشد ❌"
            isWaf -> "گیت‌هاب درخواست را رد کرد (احتمالاً به‌خاطر عوض شدن ناگهانی وی‌پیِن/IP) — چند لحظه صبر کن و دوباره تلاش کن ⚠️"
            msg.isNotEmpty() && !msg.startsWith("{") -> "$msg (کد $code)"
            else -> "خطای گیت‌هاب (کد $code)"
        }
    }

    /** پاسخ موقت و قابل‌تکرار: قطعی وی‌پیِن/شبکه، سقف درخواست، ردِ WAF */
    private fun isTransient(code: Int, text: String): Boolean {
        if (code == 429) return true
        val lower = text.lowercase()
        return lower.contains("malicious") || lower.contains("abuse") ||
            lower.contains("secondary rate") || lower.contains("rate limit")
    }

    /** خطای شبکه → پیام فارسی دوستانه (متن خام انگلیسیِ exception نباید دیده شود) */
    fun friendlyMsg(e: Throwable): String = when (e) {
        is java.io.IOException -> "⚠️ اتصال به گیت‌هاب برقرار نشد (وی‌پیِن؟) — دوباره تلاش کن ❌"
        else -> "⚠️ ${e.message ?: "خطا در همگام‌سازی"}"
    }

    /** کشِ داخل حافظه — هر بار ensureRepo دو درخواست می‌زد؛ کم کردن درخواست
     *  = کمتر شدن فرصتِ خطا و سقفِ درخواست (مخصوصاً با وی‌پیِن ناپایدار). */
    @Volatile private var repoCache: Repo? = null
    @Volatile private var repoCacheToken: String = ""
    @Volatile private var repoCacheAt: Long = 0L

    /** کاربر + ریپو (ساخت در صورت نبود) + شاخه */
    private fun ensureRepo(tk: String): Repo {
        val hit = repoCache
        if (hit != null && repoCacheToken == tk &&
            System.currentTimeMillis() - repoCacheAt < 10 * 60_000L
        ) return hit

        val (c1, t1) = req("GET", "/user", null, tk)
        if (c1 !in 200..299) throw IllegalStateException(errMessage(c1, t1))
        val login = JSONObject(t1).getString("login")

        var branch = "main"
        val (c2, t2) = req("GET", "/repos/$login/$REPO", null, tk)
        if (c2 == 404) {
            val body = JSONObject()
                .put("name", REPO)
                .put("private", true)
                .put("description", "پشتیبان دیتابیس یسرا")
                .toString()
            val (c3, t3) = req("POST", "/user/repos", body, tk)
            if (c3 !in 200..299 && c3 != 422) throw IllegalStateException(errMessage(c3, t3))
        } else if (c2 in 200..299) {
            branch = JSONObject(t2).optString("default_branch", "main").ifEmpty { "main" }
        } else {
            throw IllegalStateException(errMessage(c2, t2))
        }
        val repo = Repo(login, branch)
        repoCache = repo
        repoCacheToken = tk
        repoCacheAt = System.currentTimeMillis()
        return repo
    }

    // ───────────────────────── آپلود ─────────────────────────

    private fun push(c: Context, tk: String, repo: Repo) {
        val baos = java.io.ByteArrayOutputStream()
        Db.backupTo(baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        // sha فایل فعلی (اگر روی ریپو هست) — ریپوی خالی 404 می‌دهد
        val (cg, ct) = req("GET", "/repos/${repo.login}/$REPO/contents/$FILE", null, tk)
        if (cg !in 200..299 && cg != 404) throw IllegalStateException(errMessage(cg, ct))
        val sha = if (cg == 200) JSONObject(ct).optString("sha") else null

        // آپلود با Contents API (بدون نیاز به PATCH که در HttpURLConnection پشتیبانی نمی‌شود)
        val body = JSONObject()
            .put("message", "sync ${System.currentTimeMillis()}")
            .put("content", b64)
        if (!sha.isNullOrEmpty()) body.put("sha", sha)
        val (c2, t2) = req("PUT", "/repos/${repo.login}/$REPO/contents/$FILE", body.toString(), tk)
        if (c2 !in 200..299) throw IllegalStateException(errMessage(c2, t2))
        // sha کامیتِ تازه بماند تا pushِ خودمان دوباره «دیده‌نشده» حساب نشود
        try {
            setLastRemoteSha(c, JSONObject(t2).getJSONObject("commit").optString("sha", ""))
        } catch (_: Exception) {
        }
        // خبر لحظه‌ای به طرف دیگر — اینجا (داخل خود push) تا هیچ مسیر آپلودی
        // فراموش نشود (دستی از تنظیمات، خودکار، ادغام و…)
        Rt.notifyDbChanged()
    }

    // ───────────────────────── دانلود ─────────────────────────

    /** sha آخرین کامیتِ فایل دیتابیس — "" اگر فایلی نیست یا خطا آمد */
    private fun remoteSha(tk: String, repo: Repo): String {
        val (c, t) = req("GET", "/repos/${repo.login}/$REPO/commits?path=$FILE&per_page=1", null, tk)
        if (c != 200) return ""
        val arr = JSONArray(t)
        if (arr.length() == 0) return ""
        return arr.getJSONObject(0).optString("sha", "")
    }

    private fun download(tk: String, repo: Repo): ByteArray? {
        val (c1, t1) = req("GET", "/repos/${repo.login}/$REPO/git/trees/${repo.branch}?recursive=1", null, tk)
        if (c1 !in 200..299) throw IllegalStateException(errMessage(c1, t1))
        val tree = JSONObject(t1).getJSONArray("tree")
        var blobSha: String? = null
        for (i in 0 until tree.length()) {
            val e = tree.getJSONObject(i)
            if (e.optString("path") == FILE && e.optString("type") == "blob") {
                blobSha = e.getString("sha"); break
            }
        }
        val sha = blobSha ?: return null
        val (c2, t2) = req("GET", "/repos/${repo.login}/$REPO/git/blobs/$sha", null, tk)
        if (c2 !in 200..299) throw IllegalStateException(errMessage(c2, t2))
        val obj = JSONObject(t2)
        if (obj.optString("encoding") != "base64") return null
        return Base64.decode(obj.getString("content"), Base64.NO_WRAP)
    }

    // ───────────────────────── سناریوها ─────────────────────────

    /**
     * اجرای اسپلش: همگام‌سازی هوشمند
     *  - تمیز + آنلاین جدیدتر → دریافت
     *  - کثیف + آنلاین قدیمی‌تر → آپلود
     *  - کثیف + آنلاین جدیدتر → دریافت + اعمال تغییرات محلی + آپلود (ادغام)
     */
    fun syncOnStart(c: Context): String {
        val tk = token(c)
        if (tk.isEmpty()) return "آماده ✨"
        if (!busy.compareAndSet(false, true)) return "در حال همگام‌سازی..."
        try {
            val repo = ensureRepo(tk)
            val dirty = isDirty(c)
            val sha = remoteSha(tk, repo)

            // دادهٔ تمیز: فقط اگر آنلاین چیزی دارد که هنوز ندیده‌ایم دریافت می‌کنیم
            if (!dirty) {
                if (unseenSha(c, sha)) {
                    synchronized(lock) {
                        if (splashDone) return "✓"
                        val bytes = download(tk, repo) ?: return "روی گیت‌هاب نسخه‌ای نیست"
                        if (!Db.restoreFrom(bytes)) return "⚠️ فایل بکاپ نامعتبر است"
                        setLastRemoteSha(c, sha)
                    }
                    clearOutbox(c)
                    setLastSync(c, System.currentTimeMillis())
                    return "📥 آخرین نسخه دریافت شد"
                }
                return "✓ همگام‌شده"
            }

            // تغییرات محلی موجود
            if (unseenSha(c, sha)) {
                // آنلاین هم چیزی دارد که هنوز ندیده‌ایم → ادغام
                synchronized(lock) {
                    if (splashDone) return "✓"
                    val bytes = download(tk, repo)
                    if (bytes != null && Db.restoreFrom(bytes)) {
                        setLastRemoteSha(c, sha)
                        if (hasPending(c)) {
                            if (Db.replayOutbox(outbox(c))) {
                                push(c, tk, repo)
                                clearOutbox(c)
                                setLastSync(c, System.currentTimeMillis())
                                return "🔀 همگام‌سازی دوطرفه انجام شد"
                            }
                            // ادغام نشد: نسخهٔ آنلاین می‌ماند، تغییرات محلی برای دفعهٔ بعد حفظ می‌شود
                            return "⚠️ ادغام ناموفق بود؛ بعداً دوباره تلاش کن"
                        }
                        // چیزی برای ادغام نبود (کثیفیِ بی‌دلیل مثلاً بعد از checkpoint) —
                        // push بی‌دلیل یعنی کامیت و خبرِ اضافه برای طرف مقابل
                        clearOutbox(c)
                        setLastSync(c, System.currentTimeMillis())
                        return "📥 آخرین نسخه دریافت شد"
                    }
                }
                // فایل آنلاین نیست یا نامعتبر است → آپلود نسخهٔ محلی
                push(c, tk, repo)
                clearOutbox(c)
                setLastSync(c, System.currentTimeMillis())
                return "📤 نسخه آپلود شد"
            }

            // آنلاین از آخرین باری که دیدیم تغییری نکرده → آپلود ساده
            push(c, tk, repo)
            clearOutbox(c)
            setLastSync(c, System.currentTimeMillis())
            return "📤 نسخه آپلود شد"
        } finally {
            busy.set(false)
        }
    }

    /**
     * همگام‌سازی هنگام برگشتن اپ به پیش‌زمینه (onResume).
     *
     * برخلاف syncOnStart، پس از تمام‌شدن اسپلش هم اجرا می‌شود تا تغییراتی که
     * از طرف دیگر (مثلاً داشبورد وب) روی گیت‌هاب گذاشته شده بدون نیاز به
     * باز کردن دوبارهٔ اپ دریافت شوند:
     *  - آنلاین قدیمی‌تر یا برابر      → هیچ کاری نمی‌کند
     *  - تمیز + آنلاین جدیدتر        → دریافت و جایگزینی
     *  - تغییر محلی + آنلاین جدیدتر  → دریافت + اعمال تغییرات محلی (outbox) + آپلود
     *
     * @param onDone روی thread اصلی صدا زده می‌شود؛ آرگومان true است اگر دیتابیس
     *               تغییر کرده و صفحه باید دوباره رسم شود.
     * @return true اگر همگام‌سازی در پس‌زمینه شروع شد
     */
    fun syncOnResume(c: Context, onDone: (refresh: Boolean) -> Unit): Boolean {
        val tk = token(c)
        if (tk.isEmpty()) { onDone(false); return false }
        if (!busy.compareAndSet(false, true)) { onDone(false); return false }
        Thread {
            var refresh = false
            try {
                val repo = ensureRepo(tk)
                val sha = remoteSha(tk, repo)
                if (unseenSha(c, sha)) {
                    val bytes = download(tk, repo)
                    if (bytes != null && Db.restoreFrom(bytes)) {
                        setLastRemoteSha(c, sha)
                        // ادغام باید موفق باشد وگرنه push + clearOutbox تغییرات محلی را گم می‌کند
                        val applied = !hasPending(c) || Db.replayOutbox(outbox(c))
                        if (applied && hasPending(c)) {
                            // تغییرات محلی روی نسخهٔ آنلاین اعمال شد → آپلود ادغام
                            SyncService.kick(c)
                            push(c, tk, repo)
                        }
                        if (applied) {
                            clearOutbox(c)
                            setLastSync(c, System.currentTimeMillis())
                            refresh = true
                        }
                        // ادغام ناموفق → outbox برای دفعهٔ بعد می‌ماند
                    }
                }
            } catch (_: Exception) {
            } finally {
                busy.set(false)
                Handler(Looper.getMainLooper()).post { onDone(refresh) }
            }
        }.start()
        return true
    }

    /** آپلود دستی از تنظیمات — بلاک می‌کند، بیرون thread صدا بزن */
    fun pushNow(c: Context): String {
        val tk = token(c)
        if (tk.isEmpty()) return "ابتدا وارد شو ⚙️"
        if (!busy.compareAndSet(false, true)) return BUSY_MSG
        try {
            // نگهبان: اگر حین push اپ به پس‌زمینه رفت، پروسه کشته نشود
            SyncService.kick(c)
            val repo = ensureRepo(tk)
            push(c, tk, repo)
            clearOutbox(c)
            setLastSync(c, System.currentTimeMillis())
            return "📤 آپلود شد ✓"
        } finally {
            busy.set(false)
        }
    }

    /**
     * دانلود دستی از تنظیمات.
     * force=false: اگر تغییرات آپلودنشده محلی باشد خطا می‌دهد (تا از دست رفتن جلوگیری شود).
     * force=true: بعد از هشدار کاربر، نسخه گیت‌هاب جایگزین می‌شود.
     */
    fun pullNow(c: Context, force: Boolean): String {
        val tk = token(c)
        if (tk.isEmpty()) return "ابتدا وارد شو ⚙️"
        if (!busy.compareAndSet(false, true)) return "همگام‌سازی در جریان است..."
        try {
            if (!force && isDirty(c)) return "تغییرات آپلودنشده داری — اول آپلود کن"
            val repo = ensureRepo(tk)
            val sha = remoteSha(tk, repo)
            val bytes = download(tk, repo) ?: return "روی گیت‌هاب نسخه‌ای نیست"
            if (!Db.restoreFrom(bytes)) return "⚠️ فایل بکاپ نامعتبر است"
            setLastRemoteSha(c, sha)
            clearOutbox(c)
            setLastSync(c, System.currentTimeMillis())
            return "📥 بازیابی شد ✓"
        } finally {
            busy.set(false)
        }
    }

    /**
     * آپلود خودکار دیتابیس آنلاین بعد از هر تغییر — در پس‌زمینه.
     * اگر سینکی در جریان باشد، تا پایانش صبر می‌کند تا آپلود گم نشود؛
     * تغییراتِ هم‌زمان در یک آپلود ادغام می‌شوند.
     * بعد از هر آپلود موفق، توست سبز نشان می‌دهد.
     */
    fun pushAsync(c: Context) {
        if (token(c).isEmpty()) return
        pushPending.set(true)
        if (!pushWorker.compareAndSet(false, true)) return // worker در حال اجراست
        Thread {
            try {
                var tries = 0
                while (pushPending.getAndSet(false)) {
                    if (!hasPending(c)) continue // چیزی برای آپلود نیست
                    val msg = try {
                        pushNow(c)
                    } catch (e: Exception) {
                        friendlyMsg(e)
                    }
                    when {
                        msg == BUSY_MSG -> { // سینک دیگری در جریان است → کمی بعد دوباره
                            pushPending.set(true)
                            tries++
                            if (tries > 30) {
                                U.toastOnUi(c, "⚠️ آپلود انجام نشد — بعداً از تنظیمات آپلود کن", true, U.TOAST_ERR)
                                break
                            }
                            try {
                                Thread.sleep(700)
                            } catch (_: InterruptedException) {
                            }
                        }
                        msg.contains("✓") -> {
                            U.toastOnUi(c, "✅ دیتابیس آنلاین به‌روز شد", false, U.TOAST_OK)
                            tries = 0
                        }
                        else -> U.toastOnUi(c, msg, true, U.TOAST_ERR)
                    }
                }
            } finally {
                pushWorker.set(false)
            }
        }.start()
    }

    /**
     * سینک دستی (pull-to-refresh) — دریافت نسخهٔ آنلاین با ادغام تغییرات محلی.
     * در پس‌زمینه اجرا می‌شود و نتیجه را روی نخ اصلی اعلام می‌کند.
     */
    fun refreshNow(c: Context, onDone: (changed: Boolean, msg: String) -> Unit) {
        val tk = token(c)
        if (tk.isEmpty()) {
            Handler(Looper.getMainLooper()).post { onDone(false, "ابتدا وارد شو ⚙️") }
            return
        }
        if (!busy.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).post { onDone(false, BUSY_MSG) }
            return
        }
        Thread {
            var changed = false
            var msg = "✓ آخرین نسخه روی دستگاه است"
            try {
                val repo = ensureRepo(tk)
                val dirty = isDirty(c) || hasPending(c)
                if (dirty) {
                    // تغییر محلی هست → اول نسخهٔ آنلاین را بگیر و ادغام کن، بعد آپلود
                    val sha = remoteSha(tk, repo) // خطا → catch بیرونی؛ آپلود انجام نمی‌شود
                    if (unseenSha(c, sha)) {
                        val bytes = try {
                            download(tk, repo)
                        } catch (_: Exception) {
                            null
                        }
                        val restored = bytes != null && Db.restoreFrom(bytes)
                        if (restored) {
                            setLastRemoteSha(c, sha)
                        }
                        if (!restored && unseenSha(c, sha)) {
                            // دانلود نشد ولی آنلاین از آخرین باری که دیدیم عوض شده →
                            // آپلود کور یعنی گم شدن تغییرات طرف دیگر؛ اصلاً آپلود نکن
                            msg = "⚠️ دریافت نسخهٔ آنلاین ناموفق بود — دوباره تلاش کن"
                        } else if (!hasPending(c)) {
                            // تغییر ثبت‌شده‌ای در کار نبود (کثیفیِ بی‌دلیل مثلاً بعد از
                            // checkpoint) — push بی‌دلیل یعنی کامیت و خبرِ اضافه
                            clearOutbox(c)
                            setLastSync(c, System.currentTimeMillis())
                            changed = true
                            msg = "📥 آخرین نسخه دریافت شد"
                        } else if (Db.replayOutbox(outbox(c))) {
                            SyncService.kick(c)
                            push(c, tk, repo)
                            clearOutbox(c)
                            setLastSync(c, System.currentTimeMillis())
                            changed = true
                            msg = "✅ دیتابیس آنلاین به‌روز شد"
                        } else {
                            // ادغام ناموفق → outbox برای دفعهٔ بعد می‌ماند
                            msg = "⚠️ ادغام ناموفق بود؛ بعداً دوباره تلاش کن"
                        }
                    } else if (hasPending(c)) {
                        // ریموت عوض نشده → فقط تغییرات محلی آپلود شود (بدون دانلودِ بی‌دلیل)
                        SyncService.kick(c)
                        push(c, tk, repo)
                        clearOutbox(c)
                        setLastSync(c, System.currentTimeMillis())
                        changed = true
                        msg = "✅ دیتابیس آنلاین به‌روز شد"
                    } else {
                        // فقط کثیفیِ بی‌دلیل → همین که هست همگام است
                        clearOutbox(c)
                        setLastSync(c, System.currentTimeMillis())
                        msg = "✓ آخرین نسخه روی دستگاه است"
                    }
                } else {
                    val sha = remoteSha(tk, repo)
                    if (unseenSha(c, sha)) {
                        val bytes = download(tk, repo)
                        if (bytes != null && Db.restoreFrom(bytes)) {
                            setLastRemoteSha(c, sha)
                            clearOutbox(c)
                            setLastSync(c, System.currentTimeMillis())
                            changed = true
                            msg = "📥 آخرین نسخه دریافت شد"
                        } else {
                            msg = "⚠️ فایل بکاپ نامعتبر است"
                        }
                    }
                }
            } catch (e: Exception) {
                msg = friendlyMsg(e)
            } finally {
                busy.set(false)
                Handler(Looper.getMainLooper()).post { onDone(changed, msg) }
            }
        }.start()
    }

    /** آپلود خودکار هنگام رفتن اپ به پس‌زمینه — اگر تغییری مانده باشد */
    fun autoPush(c: Context) {
        pushAsync(c)
    }
}
