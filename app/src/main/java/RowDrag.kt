package ir.yosra.app

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.AdapterView

/**
 * درگ‌اند‌دراپ ردیف‌ها — همان منطق دسته‌ها در تنظیمات:
 * نگه‌داشتن انگشت روی دستگیره ⠿ → بلند شدن ردیف (ارتقا + ترجمه) →
 * بقیه ردیف‌ها کنار می‌روند → رها کردن = ثبت ترتیب جدید.
 * برای LinearLayout و ListView هر دو کار می‌کند.
 */
class RowDrag(
    private val container: ViewGroup,
    private val onDrop: (from: Int, to: Int) -> Unit
) {
    private var dragView: View? = null
    private var origIndex = 0
    private var lastIndex = 0
    private var startRawY = 0f
    private var lastRawY = 0f

    private fun offset(): Int =
        if (container is AdapterView<*>) container.firstVisiblePosition else 0

    fun attach(row: View, handle: View) {
        handle.setOnLongClickListener {
            startDrag(row)
            true
        }
        handle.setOnTouchListener { _, ev ->
            lastRawY = ev.rawY
            if (dragView !== row) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragMove(row, ev.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag(row)
            }
            true
        }
    }

    private fun startDrag(v: View) {
        dragView = v
        origIndex = container.indexOfChild(v)
        lastIndex = origIndex
        startRawY = lastRawY
        v.elevation = U.dp(v.context, 12f).toFloat()
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        var p: ViewParent? = v.parent
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(true)
            p = p.parent
        }
    }

    private fun dragMove(view: View, rawY: Float) {
        val delta = rawY - startRawY
        view.translationY = delta
        val center = view.top + view.height / 2f + delta
        var idx = 0
        for (i in 0 until container.childCount) {
            val ch = container.getChildAt(i)
            if (ch === view) continue
            if (ch.top + ch.height / 2f < center) idx++
        }
        if (idx != lastIndex) {
            lastIndex = idx
            for (i in 0 until container.childCount) {
                val ch = container.getChildAt(i)
                if (ch === view) continue
                ch.translationY = when {
                    i > origIndex && idx >= i ->
                        (container.getChildAt(i - 1).top - ch.top).toFloat()
                    i < origIndex && idx <= i ->
                        (container.getChildAt(i + 1).top - ch.top).toFloat()
                    else -> 0f
                }
            }
        }
    }

    private fun endDrag(view: View) {
        val from = offset() + origIndex
        val to = offset() + lastIndex
        dragView = null
        view.elevation = 0f
        var p: ViewParent? = view.parent
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(false)
            p = p.parent
        }
        for (i in 0 until container.childCount) container.getChildAt(i).translationY = 0f
        if (to != from) onDrop(from, to)
    }
}
