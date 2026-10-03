package ir.yosra.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * سرویس پیش‌زمینهٔ نگه‌دارندهٔ سینک.
 *
 * مشکل: اگر بلافاصله بعد از ثبت تراکنش اپ به پس‌زمینه برود، سیستم ممکن است
 * پروسه را بکشد و pushِ در حال پرواز ناتمام بماند (در پیش‌زمینه مشکلی نیست).
 * این سرویس با یک نوتیفکیشن موقت جلوی کشته‌شدن پروسه را می‌گیرد تا آپلود
 * تمام شود؛ بعد خودش می‌ایستد و نوتیفکیشن جمع می‌شود.
 */
class SyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotif("📤 در حال همگام‌سازی دیتابیس…"))
        Thread({
            try {
                // حداکثر ۹۰ ثانیه صبر برای اتمام سینک‌های در حال اجرا
                val end = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < end) {
                    if (!Sync.isSyncActive()) break
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    stopForeground(true)
                } catch (_: Exception) {
                }
                // فقط اگر start جدیدتری نیامده باشد بایست
                stopSelfResult(startId)
            }
        }, "yosra-sync-guard").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun buildNotif(text: String): Notification {
        val chId = "yosra-sync"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "همگام‌سازی", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, chId) else Notification.Builder(this)
        return b.setContentTitle("یسرا")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 41

        /** روشن کردن نگهبان — از هر thread؛ خطا نادیده گرفته می‌شود */
        fun kick(c: Context) {
            try {
                val i = Intent(c.applicationContext, SyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    c.applicationContext.startForegroundService(i)
                } else {
                    c.startService(i)
                }
            } catch (_: Exception) {
                // مثلاً شروع سرویس از پس‌زمینه در اندرویدهای جدید محدود است
            }
        }
    }
}
