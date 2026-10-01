package ir.yosra.app

import android.app.Activity
import android.app.Application

class YosraApp : Application() {
    companion object {
        @Volatile private var cur: Activity? = null
        fun currentActivity(): Activity? = cur
        fun foreground(a: Activity) {
            cur = a
        }
        fun background(a: Activity) {
            if (cur === a) cur = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        Db.init(this)
        U.loadFont(this)
        registerActivityLifecycleCallbacks(Rt.Lifecycle())
        Rt.ensure(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // اپ رفت پس‌زمینه → اگر تغییری روی دیتابیس مانده، آپلود شود
        if (level == TRIM_MEMORY_UI_HIDDEN) Sync.autoPush(this)
    }
}
