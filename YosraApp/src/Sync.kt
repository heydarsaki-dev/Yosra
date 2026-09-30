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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
    private const val TOL = 60_000L // تلورانس ساعت سرور
    // تلورانس کوچکتر برای onResume: کافی است جلوی دریافت نسخه‌ای را که خودمان
    // همین الان آپلود کرده‌ایم بگیرد، ولی تغییرات تازهٔ وب را سریع دریافت کنیم
    private const val RESUME_TOL = 5_000L
    private const val OUTBOX = "outbox"
    private const val OUTBOX_CAP = 300

    private val busy = AtomicBoolean(false)

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
        val msg = try { JSONObject(text).optString("message", "") } catch (_: Exception) { "" }
        return when {
            code == 401 -> "توکن نامعتبر است ❌"
            code == 403 -> "دسترسی توکن کافی نیست ❌"
            code == 404 -> "ریپو پیدا نشد ❌"
            msg.isNotEmpty() -> "$msg (کد $code)"
            else -> "خطای گیت‌هاب (کد $code)"
        }
    }

    /** کاربر + ریپو (ساخت در صورت نبود) + شاخه */
    private fun ensureRepo(tk: String): Repo {
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
        return Repo(login, branch)
    }

    // ───────────────────────── آپلود ─────────────────────────

    private fun push(tk: String, repo: Repo) {
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
    }

    // ───────────────────────── دانلود ─────────────────────────

    /** زمان آخرین کامیتِ فایل دیتابیس (میلی‌ثانیه) — 0 اگر فایلی نیست */
    private fun remoteTime(tk: String, repo: Repo): Long {
        val (c, t) = req("GET", "/repos/${repo.login}/$REPO/commits?path=$FILE&per_page=1", null, tk)
        if (c != 200) return 0L
        val arr = JSONArray(t)
        if (arr.length() == 0) return 0L
        val date = arr.getJSONObject(0).getJSONObject("commit")
            .getJSONObject("committer").getString("date")
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(date)!!.time
        } catch (_: Exception) {
            0L
        }
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
            val rt = remoteTime(tk, repo)

            // دادهٔ تمیز: فقط اگر آنلاین جدیدتر است دریافت می‌کنیم
            if (!dirty) {
                if (rt > lastSync(c) + TOL) {
                    synchronized(lock) {
                        if (splashDone) return "✓"
                        val bytes = download(tk, repo) ?: return "روی گیت‌هاب نسخه‌ای نیست"
                        if (!Db.restoreFrom(bytes)) return "⚠️ فایل بکاپ نامعتبر است"
                    }
                    clearOutbox(c)
                    setLastSync(c, System.currentTimeMillis())
                    return "📥 آخرین نسخه دریافت شد"
                }
                return "✓ همگام‌شده"
            }

            // تغییرات محلی موجود
            if (rt > lastSync(c) + TOL) {
                // آنلاین هم جدیدتر است → ادغام
                synchronized(lock) {
                    if (splashDone) return "✓"
                    val bytes = download(tk, repo)
                    if (bytes != null && Db.restoreFrom(bytes)) {
                        if (Db.replayOutbox(outbox(c))) {
                            push(tk, repo)
                            clearOutbox(c)
                            setLastSync(c, System.currentTimeMillis())
                            return "🔀 همگام‌سازی دوطرفه انجام شد"
                        }
                        // ادغام نشد: نسخهٔ آنلاین می‌ماند، تغییرات محلی برای دفعهٔ بعد حفظ می‌شود
                        return "⚠️ ادغام ناموفق بود؛ بعداً دوباره تلاش کن"
                    }
                }
                // فایل آنلاین نیست یا نامعتبر است → آپلود نسخهٔ محلی
                push(tk, repo)
                clearOutbox(c)
                setLastSync(c, System.currentTimeMillis())
                return "📤 نسخه آپلود شد"
            }

            // آنلاین قدیمی‌تر است → آپلود ساده
            push(tk, repo)
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
                val dirty = isDirty(c)
                val rt = remoteTime(tk, repo)
                if (rt > lastSync(c) + RESUME_TOL) {
                    val bytes = download(tk, repo)
                    if (bytes != null && Db.restoreFrom(bytes)) {
                        if (dirty) {
                            // تغییرات محلی روی نسخهٔ آنلاین اعمال می‌شود تا گم نشوند
                            Db.replayOutbox(outbox(c))
                            push(tk, repo)
                        }
                        clearOutbox(c)
                        setLastSync(c, System.currentTimeMillis())
                        refresh = true
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
        if (!busy.compareAndSet(false, true)) return "همگام‌سازی در جریان است..."
        try {
            val repo = ensureRepo(tk)
            push(tk, repo)
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
            val bytes = download(tk, repo) ?: return "روی گیت‌هاب نسخه‌ای نیست"
            if (!Db.restoreFrom(bytes)) return "⚠️ فایل بکاپ نامعتبر است"
            clearOutbox(c)
            setLastSync(c, System.currentTimeMillis())
            return "📥 بازیابی شد ✓"
        } finally {
            busy.set(false)
        }
    }

    /** آپلود خودکار هنگام رفتن اپ به پس‌زمینه — اگر تغییری مانده باشد */
    fun autoPush(c: Context) {
        if (token(c).isEmpty()) return
        Thread {
            try {
                pushNow(c)
            } catch (_: Exception) {
            }
        }.start()
    }
}
