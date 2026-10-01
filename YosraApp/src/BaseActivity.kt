package ir.yosra.app

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle

/**
 * پایه همه اکتیویتی‌ها: حالت روشن/تاریک انتخابی کاربر را روی
 * configuration اعمال می‌کند تا ریسورس‌های night و تم سیستم درست انتخاب شوند.
 */
open class BaseActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        T.load(newBase)
        if (T.mode == T.LIGHT || T.mode == T.DARK) {
            val cfg = Configuration(newBase.resources.configuration)
            val night =
                if (T.mode == T.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            super.attachBaseContext(newBase.createConfigurationContext(cfg))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.sync(this)
    }

    /**
     * آیا هنگام برگشتن به پیش‌زمینه باید از گیت‌هاب دریافت شود؟
     * صفحاتی که ورودی کاربر را در حافظه دارند (فرم ثبت تراکنش) یا خودشان
     * همگام‌سازی را انجام می‌دهند (اسپلش/ورود) باید آن را false کنند.
     */
    open fun allowBgSync(): Boolean = true

    /**
     * نصب pull-to-refresh روی صفحه — کشیدن از بالای صفحه، سینک دیتابیس
     * را اجرا می‌کند و نتیجه را با توست رنگی اعلام می‌کند.
     * در onCreate بعد از setContentView صدا بزن.
     */
    protected fun enablePullToRefresh() {
        var ptr: Ptr? = null
        ptr = Ptr.attach(this) {
            Sync.refreshNow(this) { changed, msg ->
                ptr?.finish()
                val err = msg.startsWith("⚠️") || msg.startsWith("⏳") || msg.startsWith("ابتدا")
                U.toast(this, msg, false, if (err) U.TOAST_ERR else U.TOAST_OK)
                if (changed && !isFinishing && !isDestroyed) recreate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (allowBgSync()) {
            Sync.syncOnResume(this) { refresh ->
                if (refresh && !isFinishing && !isDestroyed) recreate()
            }
        }
    }
}
