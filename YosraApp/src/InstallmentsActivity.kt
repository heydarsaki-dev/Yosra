package ir.yosra.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class InstallmentsActivity : BaseActivity() {

    private var debts: List<Db.Debt> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Db.init(this)
        setContentView(R.layout.activity_installments)
        enablePullToRefresh()

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_add_inst).setOnClickListener { debtDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        U.loadFont(this)
        U.rtl(this)
        U.applyFont(findViewById(android.R.id.content))
        render()
    }

    private fun render() {
        debts = Db.debts()
        val paidMap = Db.debtPaidAll()

        val total = debts.sumOf { it.total }
        var paidAmt = 0L
        var paidCount = 0
        for (d in debts) {
            val paid = paidMap[d.id] ?: emptyMap()
            if (paid.isNotEmpty()) paidCount++
            for (idx in paid.keys) if (idx <= d.months) paidAmt += d.amountAt(idx)
        }

        findViewById<TextView>(R.id.ins_total).text = U.money(total) + " تومان"
        findViewById<TextView>(R.id.ins_paid).text = U.money(paidAmt) + " تومان"
        findViewById<TextView>(R.id.ins_left).text = U.money(total - paidAmt) + " تومان"
        findViewById<TextView>(R.id.ins_count).text = U.rich(
            Triple("💸 ", 0, false),
            Triple(U.fa(debts.size.toString()), T.primary, true),
            Triple(" طلبکار   ·   ", 0, false),
            Triple(U.fa(paidCount.toString()), T.green, true),
            Triple(" پرداخت‌شده", 0, false)
        )

        val box = findViewById<LinearLayout>(R.id.inst_box)
        box.removeAllViews()
        for (d in debts) {
            val v = layoutInflater.inflate(R.layout.item_inst, box, false)
            val paid = paidMap[d.id] ?: emptyMap()

            var paidSoFar = 0L
            for (idx in paid.keys) if (idx <= d.months) paidSoFar += d.amountAt(idx)
            val remaining = d.total - paidSoFar

            v.findViewById<TextView>(R.id.i_name).text = d.name
            v.findViewById<TextView>(R.id.i_sub).text =
                "ماهی ${U.money(d.monthly)} تومان • ${U.fa(d.months.toString())} قسط"

            v.findViewById<TextView>(R.id.i_amount).text =
                if (remaining > 0) U.money(remaining) + " تومان" else "✓"

            val st = v.findViewById<TextView>(R.id.i_status)
            if (remaining > 0) {
                st.text = "جزئیات ‹"
                st.setBackgroundResource(R.drawable.chip_pay)
                st.setTextColor(T.onBrand)
            } else {
                st.text = "✓ تسویه شد"
                st.setBackgroundResource(R.drawable.chip_paid)
                st.setTextColor(T.onBrand)
            }

            val open = View.OnClickListener {
                val i = Intent(this, DebtDetailActivity::class.java)
                i.putExtra("debt_id", d.id)
                startActivity(i)
            }
            v.setOnClickListener(open)
            st.setOnClickListener(open)

            v.setOnLongClickListener {
                val items = arrayOf("✏️ ویرایش", "🗑 حذف بدهی")
                val dlg = AlertDialog.Builder(this)
                    .setTitle(d.name)
                    .setItems(items) { _, which ->
                        if (which == 0) debtDialog(d)
                        else confirmDelete(d)
                    }
                    .setNegativeButton("بستن", null)
                    .create()
                dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
                dlg.show()
                true
            }
            box.addView(v)
        }
        U.applyFont(box)
        findViewById<TextView>(R.id.inst_empty).visibility =
            if (debts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(d: Db.Debt) {
        val dlg = AlertDialog.Builder(this)
            .setMessage("بدهی «${d.name}» حذف بشه؟\nسابقه پرداخت‌ها و تراکنش‌های مرتبط هم پاک می‌شود.")
            .setPositiveButton("حذف") { _, _ ->
                Db.deleteDebt(d.id)
                render()
                U.toast(this, "حذف شد ✓")
            }
            .setNegativeButton("بی‌خیال", null)
            .create()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dlg.show()
    }

    /** دیالوگ افزودن/ویرایش طلبکار */
    private fun debtDialog(edit: Db.Debt?) {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val d = U.dp(this, 20f)
        root.setPadding(d, d, d, 0)

        val tvStart = TextView(this)
        tvStart.text = if (edit == null) "از ماه ${periodNow()} شروع می‌شود"
        else "شروع: ${U.monthName(edit.startM)} ${U.fa(edit.startY.toString())}"
        tvStart.textSize = 12f
        tvStart.setTextColor(T.primary)
        tvStart.setPadding(0, 0, 0, U.dp(this, 10f))
        root.addView(tvStart)

        root.addView(fieldLabel("اسم طرف"))
        val etName = EditText(this)
        etName.setBackgroundResource(R.drawable.input_bg)
        etName.setPadding(U.dp(this, 14f), U.dp(this, 12f), U.dp(this, 14f), U.dp(this, 12f))
        if (edit != null) etName.setText(edit.name)
        root.addView(etName)

        root.addView(fieldLabel("کل مبلغ بدهی (تومان)"))
        val etTotal = EditText(this)
        etTotal.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        etTotal.setBackgroundResource(R.drawable.input_bg)
        etTotal.setPadding(U.dp(this, 14f), U.dp(this, 12f), U.dp(this, 14f), U.dp(this, 12f))
        val lp1 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp1.topMargin = U.dp(this, 4f)
        etTotal.layoutParams = lp1
        if (edit != null) etTotal.setText(U.money(edit.total))
        root.addView(etTotal)

        root.addView(fieldLabel("مبلغ پرداخت ماهانه (تومان)"))
        val etMonthly = EditText(this)
        etMonthly.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        etMonthly.setBackgroundResource(R.drawable.input_bg)
        etMonthly.setPadding(U.dp(this, 14f), U.dp(this, 12f), U.dp(this, 14f), U.dp(this, 12f))
        val lp2 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp2.topMargin = U.dp(this, 4f)
        etMonthly.layoutParams = lp2
        if (edit != null) etMonthly.setText(U.money(edit.monthly))
        root.addView(etMonthly)

        amountWatcher(etTotal)
        amountWatcher(etMonthly)

        val tvPreview = TextView(this)
        tvPreview.textSize = 12f
        tvPreview.setTextColor(T.gray)
        val lp3 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp3.topMargin = U.dp(this, 8f)
        tvPreview.layoutParams = lp3
        root.addView(tvPreview)

        fun updatePreview() {
            val total = U.parse(etTotal.text.toString())
            val monthly = U.parse(etMonthly.text.toString())
            tvPreview.text = if (total > 0 && monthly > 0) {
                val months = (total + monthly - 1) / monthly
                "→ ${U.fa(months.toString())} قسط ماهانه"
            } else ""
        }
        etTotal.addTextChangedListener(SimpleWatcher { updatePreview() })
        etMonthly.addTextChangedListener(SimpleWatcher { updatePreview() })

        U.applyFont(root)

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (edit == null) "＋ طلبکار جدید" else "✏️ ویرایش طلبکار")
            .setView(root)
            .setPositiveButton(if (edit == null) "افزودن" else "ذخیره", null)
            .setNegativeButton("بی‌خیال", null)
            .create()
        dlg.setButton(AlertDialog.BUTTON_POSITIVE, if (edit == null) "افزودن" else "ذخیره") { _, _ ->
            val name = etName.text.toString().trim()
            val total = U.parse(etTotal.text.toString())
            val monthly = U.parse(etMonthly.text.toString())
            if (name.isEmpty()) { U.toast(this, "اسم طرف رو بنویس 🙏"); return@setButton }
            if (total <= 0) { U.toast(this, "کل بدهی رو وارد کن 🙏"); return@setButton }
            if (monthly <= 0) { U.toast(this, "مبلغ ماهانه رو وارد کن 🙏"); return@setButton }
            if (monthly > total) { U.toast(this, "مبلغ ماهانه از کل بدهی بیشتره! 🙂"); return@setButton }
            if (edit == null) {
                val now = U.jParts(System.currentTimeMillis())
                Db.addDebt(name, total, monthly, now[0], now[1])
                render()
                U.toast(this, "«$name» اضافه شد ✓")
            } else {
                Db.updateDebt(edit.id, name, total, monthly)
                render()
                U.toast(this, "ذخیره شد ✓")
            }
            dlg.dismiss()
        }
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dlg.show()
    }

    private fun periodNow(): String {
        val p = U.jParts(System.currentTimeMillis())
        return "${U.monthName(p[1])} ${U.fa(p[0].toString())}"
    }

    private fun fieldLabel(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        t.setTextColor(T.text2)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = U.dp(this, 10f)
        t.layoutParams = lp
        return t
    }

    /** جداکننده هزارگان هنگام تایپ: ۱۵۰۰۰۰۰ → ۱,۵۰۰,۰۰۰ */
    private fun amountWatcher(et: EditText) {
        et.addTextChangedListener(object : TextWatcher {
            private var self = false
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (self) return
                val raw = e?.toString() ?: return
                val f = U.money(U.parse(raw))
                if (f != raw) {
                    self = true
                    et.setText(f)
                    et.setSelection(f.length)
                    self = false
                }
            }
        })
    }
}

class SimpleWatcher(private val onChange: () -> Unit) : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(e: Editable?) { onChange() }
}
