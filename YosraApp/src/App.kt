package ir.yosra.app

import android.app.Application

class YosraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Db.init(this)
        U.loadFont(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // اپ رفت پس‌زمینه → اگر تغییری روی دیتابیس مانده، آپلود شود
        if (level == TRIM_MEMORY_UI_HIDDEN) Sync.autoPush(this)
    }
}
