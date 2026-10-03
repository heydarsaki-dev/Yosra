package ir.yosra.app

import android.content.Context
import android.content.res.Configuration

/**
 * تم مرکزی اپ — دو حالت «بنفش روز» و «بنفش شب».
 * رنگ‌های کاتلین همیشه از اینجا خوانده می‌شوند؛ رنگ‌های XML از values-night انتخاب می‌شوند.
 */
object T {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    /** انتخاب کاربر: system | light | dark */
    var mode: String = SYSTEM
        private set

    /** آیا حالت تاریک فعال است (بر اساس configuration نهایی) */
    var dark: Boolean = false
        private set

    private fun prefs(c: Context) = c.getSharedPreferences("yosra_theme", Context.MODE_PRIVATE)

    fun load(c: Context) {
        mode = prefs(c).getString("mode", SYSTEM) ?: SYSTEM
    }

    fun pick(c: Context, m: String) {
        mode = m
        prefs(c).edit().putString("mode", m).apply()
    }

    /** وضعیت تاریک را از configuration همین Context می‌خواند (پس از اعمال uiMode) */
    fun sync(c: Context) {
        dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun col(light: Int, night: Int) = if (dark) night else light

    // ——— سطح‌ها ———
    val bg get() = col(0xFFF1F2FD.toInt(), 0xFF0E1024.toInt())
    val card get() = col(0xFFFFFFFF.toInt(), 0xFF191C3E.toInt())
    val track get() = col(0xFFEEF0F8.toInt(), 0xFF242748.toInt())
    val line get() = col(0xFFE6E8F2.toInt(), 0xFF2A2E55.toInt())

    // ——— متن ———
    val text get() = col(0xFF1A1B2E.toInt(), 0xFFECEDFB.toInt())
    val text2 get() = col(0xFF6B7194.toInt(), 0xFF9BA2D0.toInt())
    val gray get() = col(0xFF9AA0BC.toInt(), 0xFF8B93C4.toInt())

    // ——— برند ———
    val primary get() = col(0xFF6C5CE7.toInt(), 0xFF8B7CF6.toInt())
    val primaryDeep get() = col(0xFF5246D6.toInt(), 0xFF6D5CE8.toInt())
    val primarySoft get() = col(0xFFEFEDFF.toInt(), 0xFF2A2456.toInt())
    val green get() = col(0xFF059669.toInt(), 0xFF2FD4A5.toInt())
    val greenSoft get() = col(0xFFD5F2E5.toInt(), 0xFF12352E.toInt())
    val red get() = col(0xFFE11D48.toInt(), 0xFFFF5C85.toInt())
    val redSoft get() = col(0xFFFCDBE3.toInt(), 0xFF3A1B2C.toInt())
    val gold get() = col(0xFFF59E0B.toInt(), 0xFFFFB443.toInt())

    /** متن روی سطوح برند (سفید در روز، تیره در شب چون برندها روشن می‌شوند) */
    val onBrand get() = col(0xFFFFFFFF.toInt(), 0xFF14162F.toInt())
}
