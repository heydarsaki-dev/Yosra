package ir.yosra.app

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Pull-to-refresh دستی برای همهٔ صفحات — بدون نیاز به کتابخانهٔ خارجی.
 * با اولین ScrollView/ListViewِ صفحه کار می‌کند.
 *
 * رفتار (استاندارد همهٔ اپ‌ها):
 *  - ژست باید از بالای صفحه شروع شود؛ وسط لیست، کشیدن یعنی اسکرول و هرگز
 *    دزدیده نمی‌شود (وگرنه اسکرول عادی می‌شکند)؛
 *  - بالای صفحه، کشیدنِ سبک به پایین رفرش را مسلح می‌کند؛
 *  - درگ افقی (جابه‌جایی ردیف‌ها) هرگز دزدیده نمی‌شود.
 *
 * استفاده در انتهای onCreate هر صفحه (بعد از setContentView):
 * ```
 * private var ptr: Ptr? = null
 * ...
 * ptr = Ptr.attach(this) {
 *     Sync.refreshNow(this) { changed, msg ->
 *         ptr?.finish()
 *         val err = msg.startsWith("⚠️") || msg.startsWith("⏳") || msg.startsWith("ابتدا")
 *         U.toast(this, msg, false, if (err) U.TOAST_ERR else U.TOAST_OK)
 *         if (changed && !isFinishing && !isDestroyed) recreate()
 *     }
 * }
 * ```
 */
class Ptr private constructor(c: Context) : FrameLayout(c) {

    private val header: LinearLayout
    private val spin: ProgressBar
    private val label: TextView
    private var content: View? = null
    private var onRefresh: (() -> Unit)? = null
    private var refreshing = false
    private var startY = 0f
    private var startX = 0f
    private var lastY = 0f
    private var over = 0f // مقدار کششِ روبه‌پایینِ ژست جاری
    private var pulling = false
    private var atTopAtStart = false // ژست از بالا شروع شده؟
    private var scroller: View? = null // اسکرولرِ واقعی زیر انگشت (ریشهٔ صفحه اسکرولر نیست!)

    private val headH = U.dp(c, 64f)
    private val threshold = U.dp(c, 56f).toFloat()
    private val slop = ViewConfiguration.get(c).scaledTouchSlop.toFloat()

    init {
        header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, headH)
            setPadding(0, U.dp(c, 8f), 0, U.dp(c, 8f))
            visibility = View.GONE
        }
        spin = ProgressBar(c).apply {
            layoutParams = LinearLayout.LayoutParams(U.dp(c, 28f), U.dp(c, 28f))
            visibility = View.GONE
        }
        label = TextView(c).apply {
            text = "بروزرسانی — بکش پایین"
            setTextColor(0xFF9AA0B4.toInt())
            textSize = 13f
            setPadding(U.dp(c, 10f), 0, 0, 0)
        }
        header.addView(spin)
        header.addView(label)
        addView(header)
    }

    private fun setContent(v: View) {
        content = v
        addView(v)
    }

    private fun setOnRefresh(cb: () -> Unit) {
        onRefresh = cb
    }

    /** شروع حالت بارگذاری بعد از رها شدن کشش */
    private fun begin() {
        refreshing = true
        header.visibility = View.VISIBLE
        spin.visibility = View.VISIBLE
        label.text = "در حال بروزرسانی…"
        content?.animate()?.translationY(headH.toFloat())?.setDuration(180)?.start()
        onRefresh?.invoke()
    }

    /** پایان — جمع کردن هدر (حتی اگر صفحه recreate شده باشد بی‌خطر است) */
    fun finish() {
        refreshing = false
        spin.visibility = View.GONE
        content?.animate()?.translationY(0f)?.setDuration(200)?.withEndAction {
            header.visibility = View.GONE
        }?.start()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (refreshing) return false
        val v = content ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y
                startX = ev.x
                lastY = ev.y
                over = 0f
                pulling = false
                // اسکرولرِ واقعی زیر انگشت را پیدا کن (خودِ ریشهٔ صفحه اسکرولر
                // نیست و همیشه «بالای صفحه» گزارش می‌دهد)
                scroller = findScroller(v, ev.x, ev.y) ?: firstScroller(v)
                // فقط ژستی که از بالای اسکرولر شروع شده حق رفرش دارد
                atTopAtStart = scroller?.canScrollVertically(-1) != true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - lastY
                lastY = ev.y
                val sc = scroller
                if (!pulling && atTopAtStart && sc?.canScrollVertically(-1) != true) {
                    // بالای اسکرولر هستیم: فقط کششِ روبه‌پایین حساب است
                    if (dy > 0) over += dy else over = 0f
                    val totalDy = ev.y - startY
                    val totalDx = kotlin.math.abs(ev.x - startX)
                    // عمودیِ غالب + بیشتر از slop → شروع pull (از همین نقطه، بدون پرش)
                    if (over > slop && totalDy > totalDx) {
                        pulling = true
                        startY = ev.y
                        startX = ev.x
                        over = 0f
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pulling = false
                over = 0f
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (refreshing) return false
        val v = content ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (pulling) {
                    val pull = (ev.y - startY).coerceAtLeast(0f)
                    val off = (pull * 0.55f).coerceAtMost(headH * 1.6f)
                    v.translationY = off
                    header.visibility = if (off > 2f) View.VISIBLE else View.GONE
                    label.text =
                        if (off >= threshold) "برای بروزرسانی رها کن ✓"
                        else "بروزرسانی — بکش پایین"
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    pulling = false
                    if (v.translationY >= threshold) begin()
                    else {
                        v.animate().translationY(0f).setDuration(180).start()
                        header.visibility = View.GONE
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    /** آیا این ویو مالکِ اسکرول عمودی است؟ */
    private fun isVerticalScroller(v: View): Boolean =
        v is android.widget.AbsListView ||
            v is android.widget.ScrollView ||
            v.javaClass.simpleName == "RecyclerView" ||
            v.javaClass.simpleName == "NestedScrollView"

    /**
     * عمیق‌ترین اسکرولرِ عمودی زیر نقطهٔ لمس.
     * بدون این، `content` (ریشهٔ صفحه) چک می‌شد که همیشه «بالای صفحه» است
     * و هر کششی از وسط/پایینِ لیست هم رفرش می‌زد.
     */
    private fun findScroller(v: View, x: Float, y: Float): View? {
        if (!isVerticalScroller(v)) {
            if (v !is ViewGroup) return null
            for (i in v.childCount - 1 downTo 0) {
                val ch = v.getChildAt(i)
                if (ch.visibility != View.VISIBLE) continue
                if (x < ch.left || x >= ch.right || y < ch.top || y >= ch.bottom) continue
                val found = findScroller(ch, x - ch.left + ch.scrollX, y - ch.top + ch.scrollY)
                if (found != null) return found
            }
            return null
        }
        // خودِ اسکرولر: اگر زیرِ نقطه زیرمجموعهٔ اسکرولرِ دیگری باشد همان را برمی‌گرداند
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val ch = v.getChildAt(i)
                if (ch.visibility != View.VISIBLE) continue
                if (x < ch.left || x >= ch.right || y < ch.top || y >= ch.bottom) continue
                val found = findScroller(ch, x - ch.left + ch.scrollX, y - ch.top + ch.scrollY)
                if (found != null) return found
            }
        }
        return v
    }

    /** اولین اسکرولرِ عمودی درخت — برای ژست روی بخش‌های خارج از لیست (مثلاً هدر) */
    private fun firstScroller(v: View): View? {
        if (isVerticalScroller(v)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val found = firstScroller(v.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    companion object {
        /** نصب روی اکتیویتی — ویوی محتوای فعلی را زیر هدر می‌برد */
        fun attach(a: Activity, onRefresh: () -> Unit): Ptr {
            val root = a.findViewById<ViewGroup>(android.R.id.content)
            val content = root.getChildAt(0)
            root.removeView(content)
            val ptr = Ptr(a)
            ptr.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            ptr.setContent(content)
            ptr.setOnRefresh(onRefresh)
            root.addView(ptr)
            return ptr
        }
    }
}
