package ir.yosra.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * اسپلش ابتدای اجرا: نمایش لوگو + همگام‌سازی دیتابیس با گیت‌هاب در پشت پرده
 * (حداکثر ۷ ثانیه؛ بعد از آن بدون توجه به ادامه کار، وارد اپ می‌شویم)
 */
class SplashActivity : BaseActivity() {

    // این صفحه خودش syncOnStart را انجام می‌دهد
    override fun allowBgSync(): Boolean = false

    private val done = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        val status = findViewById<TextView>(R.id.sp_status)

        if (Sync.token(this).isEmpty()) {
            status.text = "نسخه ۱.۰.۰"
        } else {
            status.text = "در حال همگام‌سازی..."
            Thread {
                val msg = try {
                    Sync.syncOnStart(applicationContext)
                } catch (e: Exception) {
                    "⚠️ ${e.message ?: "خطای شبکه"}"
                }
                runOnUiThread { if (!done.get()) status.text = msg }
            }.start()
        }

        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ go() }, 1400)
        h.postDelayed({ go() }, 7000)
    }

    private fun go() {
        if (!done.compareAndSet(false, true)) return
        Sync.markSplashDone()
        // اولین اجرا: اگر توکن هنوز باز نشده، صفحهٔ ورود می‌آید (یک بار برای همیشه)
        val next = if (Sync.token(this).isEmpty()) LoginActivity::class.java
                   else HomeActivity::class.java
        startActivity(android.content.Intent(this, next))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
