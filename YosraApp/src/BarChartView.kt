package ir.yosra.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Day(val label: String, val inc: Long, val exp: Long)

    private var days: List<Day> = emptyList()

    fun setData(d: List<Day>) {
        days = d
        invalidate()
    }

    fun hasData(): Boolean = days.any { it.inc > 0 || it.exp > 0 }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val green = T.green
    private val red = T.red
    private val gray = T.gray
    private val light = T.track

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty() || !hasData()) return

        val padS = U.dp(context, 8f).toFloat()
        val labelH = U.dp(context, 18f).toFloat()
        val maxTop = U.dp(context, 14f).toFloat()
        val chartW = width - padS * 2f
        val chartH = height - labelH - maxTop

        var max = 1L
        for (d in days) max = maxOf(max, d.inc, d.exp)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = U.dp(context, 1f).toFloat()
        paint.color = light
        canvas.drawLine(padS, maxTop + chartH, width - padS, maxTop + chartH, paint)

        paint.textAlign = if (layoutDirection == LAYOUT_DIRECTION_RTL) Paint.Align.RIGHT else Paint.Align.LEFT
        paint.style = Paint.Style.FILL
        paint.color = gray
        paint.textSize = U.dp(context, 10f).toFloat()
        U.fReg?.let { paint.typeface = it }
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        canvas.drawText(
            U.money(max) + " تومان",
            if (rtl) (width - padS) else padS,
            maxTop - U.dp(context, 2f).toFloat(),
            paint
        )

        val n = days.size
        val groupW = chartW / n
        val barW = (groupW * 0.3f).coerceAtMost(U.dp(context, 14f).toFloat())
        val gap = (groupW * 0.08f).coerceAtLeast(1f)
        val radius = (barW / 2f).coerceAtMost(U.dp(context, 4f).toFloat())
        val step = ((n + 7) / 8).coerceAtLeast(1)
        paint.textAlign = Paint.Align.CENTER

        for (i in 0 until n) {
            val d = days[i]
            val cx = padS + groupW * i + groupW / 2f
            val hInc = (d.inc.toFloat() / max.toFloat()) * chartH
            val hExp = (d.exp.toFloat() / max.toFloat()) * chartH
            val x1 = cx - barW - gap / 2f
            val x2 = cx + gap / 2f

            if (d.inc > 0) {
                paint.color = green
                rect.set(x1, maxTop + chartH - hInc, x1 + barW, maxTop + chartH)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            if (d.exp > 0) {
                paint.color = red
                rect.set(x2, maxTop + chartH - hExp, x2 + barW, maxTop + chartH)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }

            if (i % step == 0 || i == n - 1) {
                paint.color = gray
                paint.textSize = U.dp(context, 9f).toFloat()
                canvas.drawText(d.label, cx, maxTop + chartH + U.dp(context, 12f).toFloat(), paint)
            }
        }
    }
}
