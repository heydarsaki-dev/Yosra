package ir.yosra.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class AddTransactionActivity : Activity() {

    private var type = 1
    private var memberSel = -1L
    private var catSel = -1L
    private var cats: List<Db.Category> = emptyList()

    private var editId = -1L
    private var selY = 0
    private var selM = 0
    private var selD = 0
    private var selH = 0
    private var selM2 = 0

    private lateinit var memberBox: LinearLayout
    private lateinit var catBox: LinearLayout
    private lateinit var etAmount: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        Db.init(this)
        setContentView(R.layout.activity_add)
        U.rtl(this)

        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)

        memberBox = findViewById(R.id.member_box)
        catBox = findViewById(R.id.cat_box)
        etAmount = findViewById(R.id.et_amount)

        type = intent.getIntExtra("type", 1)
        editId = intent.getLongExtra("edit_id", -1L)

        val now = U.jParts(System.currentTimeMillis())
        selY = now[0]; selM = now[1]; selD = now[2]
        val nc = Calendar.getInstance()
        selH = nc.get(Calendar.HOUR_OF_DAY)
        selM2 = nc.get(Calendar.MINUTE)

        var note = ""
        if (editId > 0) {
            val t = Db.getTrans(editId)
            if (t != null) {
                type = t.type
                memberSel = t.memberId
                catSel = t.catId
                note = t.note
                etAmount.setText(U.money(t.amount))
                val p = U.jParts(t.ts)
                selY = p[0]; selM = p[1]; selD = p[2]
                val tc = Calendar.getInstance().apply { timeInMillis = t.ts }
                selH = tc.get(Calendar.HOUR_OF_DAY)
                selM2 = tc.get(Calendar.MINUTE)
            }
        }

        findViewById<EditText>(R.id.et_note).setText(note)

        for (id in intArrayOf(
            R.id.btn_type_out, R.id.btn_type_in,
            R.id.btn_date, R.id.btn_time, R.id.btn_day_today
        )) findViewById<TextView>(id).gravity = Gravity.CENTER

        etAmount.addTextChangedListener(object : TextWatcher {
            private var self = false
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (self) return
                val raw = e?.toString() ?: return
                val f = U.money(U.parse(raw))
                if (f != raw) {
                    self = true
                    etAmount.setText(f)
                    etAmount.setSelection(f.length)
                    self = false
                }
            }
        })

        findViewById<View>(R.id.btn_type_out).setOnClickListener {
            type = 0; titleText(); restyleType(); buildCats()
        }
        findViewById<View>(R.id.btn_type_in).setOnClickListener {
            type = 1; titleText(); restyleType(); buildCats()
        }
        findViewById<View>(R.id.btn_date).setOnClickListener { dateDialog() }
        findViewById<View>(R.id.btn_time).setOnClickListener { timeDialog() }
        findViewById<View>(R.id.btn_day_today).setOnClickListener {
            val t = U.jParts(System.currentTimeMillis())
            selY = t[0]; selM = t[1]; selD = t[2]
            val c = Calendar.getInstance()
            selH = c.get(Calendar.HOUR_OF_DAY)
            selM2 = c.get(Calendar.MINUTE)
            updateDateBtn()
        }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }

        titleText()
        restyleType()
        updateDateBtn()
        buildMembers()
        buildCats()
        U.applyFont(findViewById(android.R.id.content))
    }

    /** انتخاب‌گر ساعت و دقیقه — کاملاً فارسی، سبک خود اپ */
    private fun timeDialog() {
        var h = selH
        var m = selM2

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(U.dp(this, 18f), U.dp(this, 16f), U.dp(this, 18f), U.dp(this, 8f))

        val title = TextView(this)
        title.text = "ساعت و دقیقه 🕐"
        title.gravity = Gravity.CENTER
        title.textSize = 16f
        title.setTextColor(0xFF1A1B2E.toInt())
        root.addView(title)

        val hourChips = ArrayList<TextView>()
        val minChips = ArrayList<TextView>()
        var hourScroll: HorizontalScrollView? = null
        var minScroll: HorizontalScrollView? = null

        fun paint(c: TextView, on: Boolean) {
            c.setBackgroundResource(if (on) R.drawable.circle_on else R.drawable.circle)
            c.setTextColor(if (on) 0xFF6C5CE7.toInt() else 0xFF6B7194.toInt())
        }

        fun repaint() {
            hourChips.forEachIndexed { i, c -> paint(c, i == h) }
            minChips.forEachIndexed { i, c -> paint(c, i == m) }
        }

        fun addRow(label: String, count: Int, chips: ArrayList<TextView>, set: (Int) -> Unit): HorizontalScrollView {
            val lbl = TextView(this)
            lbl.text = label
            lbl.textSize = 13f
            lbl.setTextColor(0xFF6B7194.toInt())
            lbl.setPadding(0, U.dp(this, 14f), 0, U.dp(this, 6f))
            root.addView(lbl)

            val hs = HorizontalScrollView(this)
            hs.isHorizontalScrollBarEnabled = false
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val size = U.dp(this, 44f)
            val gap = U.dp(this, 5f)
            row.setPadding(gap, 0, gap, 0)
            for (i in 0 until count) {
                val c = TextView(this)
                c.text = U.fa(i.toString().padStart(2, '0'))
                c.textSize = 14f
                c.gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(size, size)
                lp.setMargins(gap, 0, gap, 0)
                c.layoutParams = lp
                c.setOnClickListener {
                    set(i)
                    repaint()
                }
                chips.add(c)
                row.addView(c)
            }
            hs.addView(row)
            root.addView(hs)
            return hs
        }

        hourScroll = addRow("ساعت", 24, hourChips) { h = it }
        minScroll = addRow("دقیقه", 60, minChips) { m = it }
        repaint()

        val dlg = AlertDialog.Builder(this)
            .setView(root)
            .setPositiveButton("تأیید ✓") { _, _ ->
                selH = h
                selM2 = m
                updateDateBtn()
            }
            .setNegativeButton("بی‌خیال", null)
            .create()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dlg.setOnShowListener {
            fun center(hs: HorizontalScrollView?, chip: TextView?) {
                if (hs != null && chip != null)
                    hs.post { hs.scrollTo(chip.left + chip.width / 2 - hs.width / 2, 0) }
            }
            center(hourScroll, hourChips.getOrNull(h))
            center(minScroll, minChips.getOrNull(m))
            U.applyFont(dlg.getButton(AlertDialog.BUTTON_POSITIVE))
            U.applyFont(dlg.getButton(AlertDialog.BUTTON_NEGATIVE))
            U.applyFont(root)
        }
        dlg.show()
    }

    private fun updateDateBtn() {
        findViewById<TextView>(R.id.btn_date).text =
            "🗓 ${U.fa(selD.toString())} ${U.monthName(selM)} ${U.fa(selY.toString())}"
        findViewById<TextView>(R.id.btn_time).text =
            "🕐 ${U.fa(selH.toString())}:${U.fa(selM2.toString().padStart(2, '0'))}"
    }

    private fun titleText() {
        val editing = editId > 0
        findViewById<TextView>(R.id.tv_title).text = when {
            editing && type == 0 -> "ویرایش خرج ✏️"
            editing -> "ویرایش درآمد ✏️"
            type == 0 -> "ثبت خرج"
            else -> "ثبت درآمد"
        }
        findViewById<TextView>(R.id.btn_save).text = if (editing) "ذخیره تغییرات ✓" else "ثبت کن ✓"
    }

    private fun setOn(id: Int, on: Boolean, color: Int) {
        val v = findViewById<TextView>(id)
        v.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip)
        v.setTextColor(if (on) color else 0xFF6B7194.toInt())
    }

    private fun restyleType() {
        setOn(R.id.btn_type_out, type == 0, 0xFFE11D48.toInt())
        setOn(R.id.btn_type_in, type == 1, 0xFF059669.toInt())
    }

    private fun dateDialog() {
        val today = U.jParts(System.currentTimeMillis())
        var py = selY
        var pm = selM
        var pd = selD

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(U.dp(this, 18f), U.dp(this, 18f), U.dp(this, 18f), U.dp(this, 14f))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        val btnPrev = TextView(this)
        btnPrev.text = "‹ قبلی"
        btnPrev.setBackgroundResource(R.drawable.btn_soft)
        btnPrev.setTextColor(0xFF6C5CE7.toInt())
        btnPrev.textSize = 13f
        btnPrev.setPadding(U.dp(this, 14f), U.dp(this, 7f), U.dp(this, 14f), U.dp(this, 7f))
        val tvM = TextView(this)
        tvM.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tvM.gravity = Gravity.CENTER
        tvM.textSize = 15f
        tvM.setTextColor(0xFF1A1B2E.toInt())
        val btnNext = TextView(this)
        btnNext.text = "بعدی ›"
        btnNext.setBackgroundResource(R.drawable.btn_soft)
        btnNext.setTextColor(0xFF6C5CE7.toInt())
        btnNext.textSize = 13f
        btnNext.setPadding(U.dp(this, 14f), U.dp(this, 7f), U.dp(this, 14f), U.dp(this, 7f))
        nav.addView(btnPrev)
        nav.addView(tvM)
        nav.addView(btnNext)
        root.addView(nav)

        val wh = LinearLayout(this)
        wh.orientation = LinearLayout.HORIZONTAL
        wh.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = U.dp(this@AddTransactionActivity, 12f) }
        for (d in arrayOf("ش", "ی", "د", "س", "چ", "پ", "ج")) {
            val t = TextView(this)
            t.text = d
            t.gravity = Gravity.CENTER
            t.textSize = 12f
            t.setTextColor(0xFF9AA0BC.toInt())
            t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            wh.addView(t)
        }
        root.addView(wh)

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = U.dp(this@AddTransactionActivity, 4f) }
        root.addView(grid)

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = U.dp(this@AddTransactionActivity, 14f) }
        val btnToday = TextView(this)
        btnToday.text = "امروز"
        btnToday.gravity = Gravity.CENTER
        btnToday.setBackgroundResource(R.drawable.chip)
        btnToday.setTextColor(0xFF6C5CE7.toInt())
        btnToday.textSize = 13f
        btnToday.setPadding(U.dp(this, 18f), U.dp(this, 14f), U.dp(this, 18f), U.dp(this, 14f))
        val btnOk = TextView(this)
        btnOk.text = "تأیید ✓"
        btnOk.gravity = Gravity.CENTER
        btnOk.setBackgroundResource(R.drawable.btn_primary)
        btnOk.setTextColor(0xFFFFFFFF.toInt())
        btnOk.textSize = 14f
        btnOk.setPadding(U.dp(this, 24f), U.dp(this, 14f), U.dp(this, 24f), U.dp(this, 14f))
        btnOk.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = U.dp(this@AddTransactionActivity, 8f)
        }
        bottom.addView(btnToday)
        bottom.addView(btnOk)
        root.addView(bottom)

        fun rebuild() {
            tvM.text = "${U.monthName(pm)} ${U.fa(py.toString())}"
            grid.removeAllViews()
            val firstTs = U.findTs(py, pm, 1) ?: System.currentTimeMillis()
            val cal = Calendar.getInstance()
            cal.timeInMillis = firstTs
            val startIdx = if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) 0
            else cal.get(Calendar.DAY_OF_WEEK)
            val dim = U.daysInMonth(pm, py)
            val total = startIdx + dim
            val rows = (total + 6) / 7
            var cell = 0
            for (r in 0 until rows) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                for (c in 0 until 7) {
                    val frame = FrameLayout(this)
                    frame.layoutParams = LinearLayout.LayoutParams(0, U.dp(this, 48f), 1f)
                    val tv = TextView(this)
                    tv.gravity = Gravity.CENTER
                    tv.textSize = 14f
                    if (cell in startIdx until (startIdx + dim)) {
                        val day = cell - startIdx + 1
                        tv.text = U.fa(day.toString())
                        val isSel = day == pd
                        val isToday = py == today[0] && pm == today[1] && day == today[2]
                        when {
                            isSel -> {
                                tv.setBackgroundResource(R.drawable.circle_on)
                                tv.setTextColor(0xFF6C5CE7.toInt())
                            }
                            isToday -> {
                                tv.setBackgroundResource(R.drawable.circle)
                                tv.setTextColor(0xFF6C5CE7.toInt())
                            }
                            else -> tv.setTextColor(0xFF1A1B2E.toInt())
                        }
                        tv.setOnClickListener {
                            pd = day
                            rebuild()
                        }
                    }
                    frame.addView(
                        tv,
                        FrameLayout.LayoutParams(U.dp(this, 40f), U.dp(this, 40f), Gravity.CENTER)
                    )
                    row.addView(frame)
                    cell++
                }
                grid.addView(row)
            }
        }

        btnPrev.setOnClickListener {
            pm--; if (pm < 1) { pm = 12; py-- }
            pd = if (py == selY && pm == selM) selD else 0
            rebuild()
        }
        btnNext.setOnClickListener {
            pm++; if (pm > 12) { pm = 1; py++ }
            pd = if (py == selY && pm == selM) selD else 0
            rebuild()
        }
        var dialog: AlertDialog? = null

        btnToday.setOnClickListener {
            selY = today[0]; selM = today[1]; selD = today[2]
            updateDateBtn()
            dialog?.dismiss()
        }
        btnOk.setOnClickListener {
            if (pd == 0) {
                U.toast(this, "یک روز انتخاب کن 🙏")
                return@setOnClickListener
            }
            selY = py; selM = pm; selD = pd
            updateDateBtn()
            dialog?.dismiss()
        }

        rebuild()
        U.applyFont(root)

        dialog = AlertDialog.Builder(this).setView(root).create()
        dialog!!.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dialog!!.show()
    }

    private fun buildMembers() {
        memberBox.removeAllViews()
        val members = Db.members()
        if (memberSel == -1L) memberSel = members.firstOrNull()?.id ?: -1L
        for (m in members) {
            val t = chip("${m.emoji} ${m.name}", m.id == memberSel)
            t.tag = m.id
            t.setOnClickListener {
                memberSel = m.id
                for (i in 0 until memberBox.childCount) {
                    val mv = memberBox.getChildAt(i) as TextView
                    val sel = mv.tag == m.id
                    mv.setBackgroundResource(if (sel) R.drawable.chip_on else R.drawable.chip)
                    mv.setTextColor(if (sel) 0xFF6C5CE7.toInt() else 0xFF6B7194.toInt())
                }
            }
            memberBox.addView(t)
        }
        U.applyFont(memberBox)
    }

    private fun buildCats() {
        catBox.removeAllViews()
        cats = Db.categories().filter { it.type == type }
        if (cats.none { it.id == catSel }) catSel = cats.firstOrNull()?.id ?: -1L
        for (c in cats) {
            val t = chip("${c.emoji} ${c.name}", c.id == catSel)
            t.tag = c.id
            t.setOnClickListener {
                catSel = c.id
                for (i in 0 until catBox.childCount) {
                    val cv = catBox.getChildAt(i) as TextView
                    val sel = cv.tag == c.id
                    cv.setBackgroundResource(if (sel) R.drawable.chip_on else R.drawable.chip)
                    cv.setTextColor(if (sel) 0xFF6C5CE7.toInt() else 0xFF6B7194.toInt())
                }
            }
            catBox.addView(t)
        }
        if (cats.isEmpty()) {
            val t = chip("از تنظیمات دسته اضافه کن ⚙️", false)
            t.setTextColor(0xFFE11D48.toInt())
            catBox.addView(t)
        }
        U.applyFont(catBox)
    }

    private fun chip(text: String, on: Boolean): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        t.setPadding(U.dp(this, 16f), U.dp(this, 9f), U.dp(this, 16f), U.dp(this, 9f))
        t.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip)
        t.setTextColor(if (on) 0xFF6C5CE7.toInt() else 0xFF6B7194.toInt())
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = U.dp(this, 8f)
        t.layoutParams = lp
        return t
    }

    private fun save() {
        val amount = U.parse(etAmount.text.toString())
        if (amount <= 0L) {
            U.toast(this, "مبلغ را وارد کن 🙏")
            return
        }
        if (catSel == -1L) {
            U.toast(this, "اول از تنظیمات یک دسته‌بندی اضافه کن ⚙️")
            return
        }
        if (memberSel == -1L) {
            U.toast(this, "کاربر را انتخاب کن")
            return
        }
        if (type == 0) {
            var avail = Db.balance()
            if (editId > 0) {
                val old = Db.getTrans(editId)
                if (old != null && old.type == 0) avail += old.amount
            }
            if (amount > avail) {
                U.toast(
                    this,
                    "موجودی کافی نیست! موجودی فعلی: ${U.money(avail)} تومان",
                    true
                )
                return
            }
        }
        val dayTs = U.findTs(selY, selM, selD) ?: System.currentTimeMillis()
        val tcal = Calendar.getInstance()
        tcal.timeInMillis = dayTs
        tcal.set(Calendar.HOUR_OF_DAY, selH)
        tcal.set(Calendar.MINUTE, selM2)
        tcal.set(Calendar.SECOND, 0)
        tcal.set(Calendar.MILLISECOND, 0)
        val ts = tcal.timeInMillis
        val note = findViewById<EditText>(R.id.et_note).text.toString().trim()
        if (editId > 0) {
            Db.updateTrans(editId, memberSel, catSel, type, amount, note, ts)
            U.toast(this, "ذخیره شد ✅")
        } else {
            Db.insertTrans(memberSel, catSel, type, amount, note, ts, false)
            U.toast(this, "ثبت شد ✅")
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
