package ir.yosra.app

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class ReportsActivity : Activity() {

    private var dayMode = false
    private var dayTs = System.currentTimeMillis()
    private var monY = 0
    private var monM = 0
    private var donutType = 1
    private var memberType = 1

    private val palette = intArrayOf(
        0xFF6C5CE7.toInt(), 0xFFE11D48.toInt(), 0xFF059669.toInt(),
        0xFFF2A93B.toInt(), 0xFF00B8D9.toInt(), 0xFF9C56D8.toInt(),
        0xFFF06292.toInt(), 0xFF00A76F.toInt(), 0xFF6D7C8B.toInt(),
        0xFFE4B321.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Db.init(this)
        setContentView(R.layout.activity_reports)

        val now = U.jParts(System.currentTimeMillis())
        monY = now[0]
        monM = now[1]

        findViewById<View>(R.id.r_mode_day).setOnClickListener {
            dayMode = true; paintModes(); render()
        }
        findViewById<View>(R.id.r_mode_month).setOnClickListener {
            dayMode = false; paintModes(); render()
        }
        findViewById<View>(R.id.btn_prev).setOnClickListener {
            if (dayMode) dayTs -= 86400000L
            else { monM--; if (monM < 1) { monM = 12; monY-- } }
            render()
        }
        findViewById<View>(R.id.btn_next).setOnClickListener {
            if (!canNext()) return@setOnClickListener
            if (dayMode) dayTs += 86400000L
            else { monM++; if (monM > 12) { monM = 1; monY++ } }
            render()
        }
        findViewById<View>(R.id.donut_chip_out).setOnClickListener {
            donutType = 0; paintReportChips(); render()
        }
        findViewById<View>(R.id.donut_chip_in).setOnClickListener {
            donutType = 1; paintReportChips(); render()
        }
        findViewById<View>(R.id.member_chip_out).setOnClickListener {
            memberType = 0; paintReportChips(); render()
        }
        findViewById<View>(R.id.member_chip_in).setOnClickListener {
            memberType = 1; paintReportChips(); render()
        }
    }

    private fun paintReportChips() {
        setChip(R.id.donut_chip_out, donutType == 0, 0xFFE11D48.toInt())
        setChip(R.id.donut_chip_in, donutType == 1, 0xFF059669.toInt())
        setChip(R.id.member_chip_out, memberType == 0, 0xFFE11D48.toInt())
        setChip(R.id.member_chip_in, memberType == 1, 0xFF059669.toInt())
        findViewById<TextView>(R.id.donut_title).text =
            (if (donutType == 0) "خرج" else "درآمد") + " به تفکیک دسته 🍩"
        findViewById<TextView>(R.id.member_title).text =
            if (memberType == 0) "چه کسی خرج کرد؟" else "چه کسی درآمد داشت؟"
    }

    private fun paintModes() {
        setChip(R.id.r_mode_day, dayMode, 0xFF6C5CE7.toInt())
        setChip(R.id.r_mode_month, !dayMode, 0xFF6C5CE7.toInt())
    }

    private fun setChip(id: Int, on: Boolean, color: Int) {
        val v = findViewById<TextView>(id)
        v.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip)
        v.setTextColor(if (on) color else 0xFF6B7194.toInt())
    }

    private fun canNext(): Boolean {
        val now = U.jParts(System.currentTimeMillis())
        return if (dayMode) {
            val cur = U.jParts(dayTs)
            !(cur[0] == now[0] && cur[1] == now[1] && cur[2] == now[2])
        } else {
            !(monY == now[0] && monM == now[1])
        }
    }

    override fun onResume() {
        super.onResume()
        Nav.bind(this, 2)
        paintModes()
        paintReportChips()
        render()
    }

    private fun periodLabel(): String {
        return if (dayMode) {
            when {
                U.isToday(dayTs) -> "امروز • ${U.isoJ(dayTs)}"
                U.isYesterday(dayTs) -> "دیروز • ${U.isoJ(dayTs)}"
                else -> U.isoJ(dayTs)
            }
        } else {
            "${U.monthName(monM)} ${U.fa(monY.toString())}"
        }
    }

    private fun inPeriod(ts: Long): Boolean {
        val j = U.jParts(ts)
        return if (dayMode) j[0] == U.jParts(dayTs)[0] && j[1] == U.jParts(dayTs)[1] && j[2] == U.jParts(dayTs)[2]
        else j[0] == monY && j[1] == monM
    }

    private fun render() {
        findViewById<TextView>(R.id.tv_period).text = periodLabel()
        val next = findViewById<TextView>(R.id.btn_next)
        next.alpha = if (canNext()) 1f else 0.3f
        next.isClickable = canNext()

        val all = Db.allTrans()
        val period = all.filter { inPeriod(it.ts) }.sortedByDescending { it.ts }
        val pIn = period.filter { it.type == 1 }.sumOf { it.amount }
        val pOut = period.filter { it.type == 0 }.sumOf { it.amount }

        findViewById<TextView>(R.id.r_income).text = U.money(pIn) + " تومان"
        findViewById<TextView>(R.id.r_expense).text = U.money(pOut) + " تومان"
        findViewById<TextView>(R.id.r_balance).text = U.money(pIn - pOut) + " تومان"
        findViewById<TextView>(R.id.r_count).text = U.rich(
            Triple("📊 ", 0, false),
            Triple(U.fa(period.size.toString()), 0xFF6C5CE7.toInt(), true),
            Triple(if (dayMode) " تراکنش در این روز" else " تراکنش در این ماه", 0, false)
        )

        val cats = Db.categories().associateBy { it.id }
        val mem = Db.members().associateBy { it.id }

        renderTrend(all)
        renderDonut(period, cats, if (donutType == 0) pOut else pIn)
        renderMembers(period, if (memberType == 0) pOut else pIn)
        renderDayList(period, cats, mem)

        U.applyFont(findViewById(android.R.id.content))
    }

    private fun renderTrend(all: List<Db.Trans>) {
        val card = findViewById<View>(R.id.trend_card)
        if (dayMode) {
            card.visibility = View.GONE
            return
        }
        val days = (1..U.daysInMonth(monM, monY)).map { d ->
            var inc = 0L
            var exp = 0L
            for (t in all) {
                val j = U.jParts(t.ts)
                if (j[0] == monY && j[1] == monM && j[2] == d) {
                    if (t.type == 1) inc += t.amount else exp += t.amount
                }
            }
            BarChartView.Day(U.fa(d.toString()), inc, exp)
        }
        val chart = findViewById<BarChartView>(R.id.bar_view)
        chart.setData(days)
        card.visibility = if (chart.hasData()) View.VISIBLE else View.GONE
    }

    private fun renderDonut(period: List<Db.Trans>, cats: Map<Long, Db.Category>, totalAmt: Long) {
        val byCat = period.filter { it.type == donutType }
            .groupBy { it.catId }
            .map { (id, list) -> Triple(id, list.sumOf { it.amount }, cats[id]) }
            .sortedByDescending { it.second }

        val top = byCat.take(7)
        val rest = byCat.drop(7).sumOf { it.second }

        val slices = ArrayList<DonutChartView.Slice>()
        for (i in top.indices) {
            slices.add(DonutChartView.Slice(top[i].second, palette[i % palette.size]))
        }
        if (rest > 0) slices.add(DonutChartView.Slice(rest, 0xFF9AA0BC.toInt()))

        findViewById<DonutChartView>(R.id.donut_view).setData(
            slices,
            if (totalAmt > 0) U.money(totalAmt) + " تومان" else "۰ تومان",
            (if (donutType == 0) "خرج" else "درآمد") + (if (dayMode) " این روز" else " این ماه")
        )

        val catBox = findViewById<LinearLayout>(R.id.cat_box)
        catBox.removeAllViews()
        for (i in top.indices) {
            val (id, amt, cat) = top[i]
            addBar(
                catBox,
                cat?.let { "${it.emoji} ${it.name}" } ?: "سایر",
                amt, top.firstOrNull()?.second ?: 1L,
                palette[i % palette.size]
            )
        }
        if (rest > 0) addBar(catBox, "سایر", rest, top.firstOrNull()?.second ?: 1L, 0xFF9AA0BC.toInt())
        val empty = findViewById<TextView>(R.id.cat_empty)
        empty.text = if (donutType == 0) "هنوز خرجی ثبت نشده" else "هنوز درآمدی ثبت نشده"
        empty.visibility = if (byCat.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderMembers(period: List<Db.Trans>, totalAmt: Long) {
        val memBox = findViewById<LinearLayout>(R.id.member_box)
        memBox.removeAllViews()
        val members = Db.members()
        val byMem = period.filter { it.type == memberType }.groupBy { it.memberId }
        val total = if (totalAmt > 0) totalAmt else 1L
        for (m in members) {
            val amt = byMem[m.id]?.sumOf { it.amount } ?: 0L
            val v = layoutInflater.inflate(R.layout.item_member, memBox, false)
            v.findViewById<View>(R.id.m_del).visibility = View.GONE
            v.findViewById<View>(R.id.m_edit).visibility = View.GONE
            val av = v.findViewById<TextView>(R.id.m_avatar)
            av.text = m.emoji
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(m.color)
            av.background = gd
            v.findViewById<TextView>(R.id.m_name).text = m.name
            val pct = (amt * 100) / total
            v.findViewById<TextView>(R.id.m_sub).text =
                (if (memberType == 0) "خرج" else "درآمد") + ": ${U.money(amt)} تومان • ${U.fa(pct.toString())}٪"
            memBox.addView(v)
        }
        U.applyFont(memBox)
    }

    private fun renderDayList(
        period: List<Db.Trans>,
        cats: Map<Long, Db.Category>,
        mem: Map<Long, Db.Member>
    ) {
        val card = findViewById<View>(R.id.day_card)
        if (!dayMode) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val box = findViewById<LinearLayout>(R.id.tx_box)
        box.removeAllViews()
        for (t in period) {
            val v = layoutInflater.inflate(R.layout.item_trans, box, false)
            HomeActivity.bindRow(v, t, cats, mem)
            v.findViewById<View>(R.id.drag_handle).visibility = View.GONE
            box.addView(v)
        }
        U.applyFont(box)
        findViewById<TextView>(R.id.tx_empty).visibility =
            if (period.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addBar(box: LinearLayout, label: String, amt: Long, max: Long, color: Int) {
        val v = layoutInflater.inflate(R.layout.item_bar, box, false)
        v.findViewById<TextView>(R.id.b_cat).text = label
        v.findViewById<TextView>(R.id.b_amt).text = U.money(amt) + " تومان"
        val fill = v.findViewById<View>(R.id.b_fill)
        val rest = v.findViewById<View>(R.id.b_rest)
        val pct = if (max > 0) amt.toDouble() / max.toDouble() else 0.0
        val lp = fill.layoutParams as LinearLayout.LayoutParams
        lp.weight = pct.toFloat().coerceIn(0.02f, 1f)
        fill.layoutParams = lp
        val lp2 = rest.layoutParams as LinearLayout.LayoutParams
        lp2.weight = (1.0 - pct).toFloat().coerceAtLeast(0f)
        rest.layoutParams = lp2
        val gd = GradientDrawable()
        gd.cornerRadius = U.dp(this, 5f).toFloat()
        gd.setColor(color)
        fill.background = gd
        box.addView(v)
        U.applyFont(v)
    }
}
