package ir.yosra.app

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast

object U {

    fun rich(vararg parts: Triple<String, Int, Boolean>): CharSequence {
        val sb = SpannableStringBuilder()
        for ((text, color, bold) in parts) {
            val start = sb.length
            sb.append(text)
            if (color != 0) sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    fun rtl(a: Activity) {
        a.findViewById<View>(android.R.id.content).layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    fun fa(s: String): String {
        val d = arrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val b = StringBuilder()
        for (ch in s) b.append(if (ch in '0'..'9') d[ch - '0'] else ch)
        return b.toString()
    }

    fun money(l: Long): String {
        val s = StringBuilder(Math.abs(l).toString())
        var i = s.length - 3
        while (i > 0) {
            s.insert(i, ',')
            i -= 3
        }
        val out = fa(s.toString())
        return if (l < 0) "−$out" else out
    }

    fun parse(s: String): Long {
        var b = StringBuilder()
        for (ch in s) {
            val d = when {
                ch in '0'..'9' -> ch - '0'
                ch in '۰'..'۹' -> ch - '۰'
                ch == ',' || ch == '٬' || ch == ' ' || ch == '.' -> -2   // separator characters
                else -> -1
            }
            if (d >= 0) b.append(d)
            else if (d == -2) continue   // skip separators: comma, thousands, space
        }
        return if (b.isEmpty()) 0L else b.toString().toLong()
    }

    private val MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    fun monthName(jm: Int): String = MONTHS[jm - 1]

    fun g2j(gy: Int, gm: Int, gd: Int): IntArray {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 - 1) / 100) + ((gy2 - 1) / 400) + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return intArrayOf(jy, jm, jd)
    }

    fun jParts(ts: Long): IntArray {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ts
        return g2j(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    fun isoJ(ts: Long): String {
        val p = jParts(ts)
        return fa(String.format(java.util.Locale.US, "%04d/%02d/%02d", p[0], p[1], p[2]))
    }

    fun isToday(ts: Long): Boolean {
        val c1 = java.util.Calendar.getInstance()
        val c2 = java.util.Calendar.getInstance()
        c2.timeInMillis = ts
        return c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR) &&
            c1.get(java.util.Calendar.DAY_OF_YEAR) == c2.get(java.util.Calendar.DAY_OF_YEAR)
    }

    fun isYesterday(ts: Long): Boolean = isToday(ts + 86400000L)

    fun nice(ts: Long): String {
        val p = jParts(ts)
        return "${fa(p[2].toString())} ${monthName(p[1])} ${fa(p[0].toString())}"
    }

    fun inMonth(ts: Long): Boolean {
        val j = jParts(ts)
        val t = jParts(System.currentTimeMillis())
        return j[0] == t[0] && j[1] == t[1]
    }

    fun daysInMonth(m: Int, y: Int): Int = when {
        m <= 6 -> 31
        m <= 11 -> 30
        else -> if ((y % 33) in intArrayOf(1, 5, 9, 13, 17, 22, 26, 30)) 30 else 29
    }

    fun findTs(y: Int, m: Int, d: Int, from: Long = System.currentTimeMillis()): Long? {
        val cur = jParts(from)
        val dir = when {
            cur[0] != y -> if (cur[0] > y) -1 else 1
            cur[1] != m -> if (cur[1] > m) -1 else 1
            else -> if (cur[2] > d) -1 else 1
        }
        var ts = from
        var steps = 0
        while (steps < 4000) {
            val j = jParts(ts)
            if (j[0] == y && j[1] == m && j[2] == d) return ts
            ts += dir * 86400000L
            steps++
        }
        return null
    }

    fun dp(c: Context, v: Float): Int = Math.round(v * c.resources.displayMetrics.density)

    /** توست با استایل اپ — متن سفید کامل روی کارت تیره */
    fun toast(c: Context, msg: String, long: Boolean = false) {
        loadFont(c)
        val tv = TextView(c)
        tv.text = msg
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 14f
        tv.typeface = fReg ?: tv.typeface
        val d = dp(c, 18f)
        tv.setPadding(d, dp(c, 12f), d, dp(c, 12f))
        val gd = android.graphics.drawable.GradientDrawable()
        gd.cornerRadius = dp(c, 16f).toFloat()
        gd.setColor(0xF01A1B2E.toInt())
        tv.background = gd
        val t = Toast(c)
        t.duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        t.setGravity(
            android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM,
            0, dp(c, 90f)
        )
        t.view = tv
        t.show()
    }

    var fReg: Typeface? = null
    var fBold: Typeface? = null

    fun loadFont(c: Context) {
        if (fReg == null) fReg = try { Typeface.createFromAsset(c.assets, "Vazirmatn-Regular.ttf") } catch (e: Exception) { null }
        if (fBold == null) fBold = try { Typeface.createFromAsset(c.assets, "Vazirmatn-Bold.ttf") } catch (e: Exception) { null }
    }

    fun applyFont(v: View) {
        if (v is TextView) {
            val bold = v.typeface != null && v.typeface.isBold
            val t = if (bold) (fBold ?: fReg) else (fReg ?: fBold)
            if (t != null) v.typeface = t
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) applyFont(v.getChildAt(i))
        }
    }
}
