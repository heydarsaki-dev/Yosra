package ir.yosra.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class DonutChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Slice(val value: Long, val color: Int)

    private var slices: List<Slice> = emptyList()
    private var centerTop = ""
    private var centerSub = ""

    fun setData(s: List<Slice>, top: String, sub: String) {
        slices = s
        centerTop = top
        centerSub = sub
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val grayRing = 0xFFEEF0F8.toInt()
    private val dark = 0xFF1A1B2E.toInt()
    private val textGray = 0xFF6B7194.toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val stroke = U.dp(context, 26f).toFloat()
        val pad = stroke / 2f + U.dp(context, 4f).toFloat()
        rect.set(cx - size / 2f + pad, cy - size / 2f + pad, cx + size / 2f - pad, cy + size / 2f - pad)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = stroke

        val total = slices.sumOf { it.value }
        if (total <= 0) {
            paint.color = grayRing
            canvas.drawOval(rect, paint)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.color = textGray
            paint.textSize = U.dp(context, 12f).toFloat()
            U.fReg?.let { paint.typeface = it }
            canvas.drawText(centerSub.ifEmpty { "بدون داده" }, cx, cy + U.dp(context, 4f).toFloat(), paint)
            return
        }

        var start = -90f
        val gap = if (slices.size > 1) 2.5f else 0f
        for (s in slices) {
            val sweep = (s.value.toFloat() / total) * 360f
            paint.color = s.color
            canvas.drawArc(rect, start + gap / 2f, (sweep - gap).coerceAtLeast(0.5f), false, paint)
            start += sweep
        }

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = dark
        paint.textSize = U.dp(context, 15f).toFloat()
        paint.typeface = U.fBold ?: U.fReg
        canvas.drawText(centerTop, cx, cy, paint)
        paint.color = textGray
        paint.textSize = U.dp(context, 11f).toFloat()
        paint.typeface = U.fReg
        canvas.drawText(centerSub, cx, cy + U.dp(context, 16f).toFloat(), paint)
    }
}
