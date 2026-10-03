package ir.yosra.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class DebtDetailActivity : BaseActivity() {

    private var debtId = -1L
    private var debt: Db.Debt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Db.init(this)
        setContentView(R.layout.activity_debt_detail)

        debtId = intent.getLongExtra("debt_id", -1L)
        debt = Db.getDebt(debtId)
        if (debt == null) { finish(); return }

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        enablePullToRefresh()
    }

    override fun onResume() {
        super.onResume()
        U.loadFont(this)
        U.rtl(this)
        U.applyFont(findViewById(android.R.id.content))
        render()
    }

    private fun render() {
        debt = Db.getDebt(debtId)
        val d = debt ?: run { finish(); return }

        findViewById<TextView>(R.id.tv_title).text = "💳 ${d.name}"

        val paidMap = Db.debtPaidAll()[d.id] ?: emptyMap()
        var paidAmt = 0L
        for (idx in paidMap.keys) if (idx <= d.months) paidAmt += d.amountAt(idx)

        findViewById<TextView>(R.id.d_total).text = U.money(d.total) + " تومان"
        findViewById<TextView>(R.id.d_paid).text = U.money(paidAmt) + " تومان"
        findViewById<TextView>(R.id.d_left).text = U.money(d.total - paidAmt) + " تومان"
        findViewById<TextView>(R.id.d_sub).text = U.rich(
            Triple("ماهی ", 0, false),
            Triple(U.money(d.monthly), T.primary, true),
            Triple(" تومان   ·   ", 0, false),
            Triple(U.fa(d.months.toString()), T.primary, true),
            Triple(" قسط   ·   از ", 0, false),
            Triple("${U.monthName(d.startM)} ${U.fa(d.startY.toString())}", T.primary, true)
        )

        val box = findViewById<LinearLayout>(R.id.inst_box)
        box.removeAllViews()
        for (idx in 1..d.months) {
            val v = layoutInflater.inflate(R.layout.item_inst, box, false)
            val m = d.monthAt(idx)
            val monthName = "${U.monthName(m[1])} ${U.fa(m[0].toString())}"
            val amount = d.amountAt(idx)
            val isPaid = paidMap.containsKey(idx)

            v.findViewById<TextView>(R.id.i_name).text = monthName
            v.findViewById<TextView>(R.id.i_sub).text = "قسط ${U.fa(idx.toString())} از ${U.fa(d.months.toString())}"
            v.findViewById<TextView>(R.id.i_amount).text = U.money(amount) + " تومان"

            val st = v.findViewById<TextView>(R.id.i_status)
            if (isPaid) {
                st.text = "✓ پرداخت شد"
                st.setBackgroundResource(R.drawable.chip_paid)
                st.setTextColor(T.onBrand)
                st.setOnClickListener { confirmCancel(idx, paidMap[idx] ?: 0L) }
            } else {
                st.text = "💵 پرداخت"
                st.setBackgroundResource(R.drawable.chip_pay)
                st.setTextColor(T.onBrand)
                st.setOnClickListener { confirmPay(idx, amount, monthName) }
            }
            box.addView(v)
        }
        U.applyFont(box)
    }

    /** تأیید پرداخت یک قسط با مبلغ ثابت */
    private fun confirmPay(idx: Int, amount: Long, monthName: String) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("💵 پرداخت قسط")
            .setMessage("قسط ${U.fa(idx.toString())} — ${monthName}\nبه مبلغ ${U.money(amount)} تومان پرداخت بشه؟\n\nیک تراکنش خرج هم به لیست اضافه می‌شود.")
            .setPositiveButton("بله، پرداخت کردم ✓") { _, _ -> doPay(idx, monthName) }
            .setNegativeButton("انصراف", null)
            .create()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dlg.show()
    }

    private fun doPay(idx: Int, monthName: String) {
        val d = debt ?: return
        val amount = d.amountAt(idx)
        val avail = Db.balance()
        if (amount > avail) {
            U.toast(this, "موجودی کافی نیست! موجودی فعلی: ${U.money(avail)} تومان", true)
            return
        }
        val m = d.monthAt(idx)
        val now = U.jParts(System.currentTimeMillis())
        val ts = if (m[0] == now[0] && m[1] == now[1]) System.currentTimeMillis()
        else U.findTs(m[0], m[1], 1) ?: System.currentTimeMillis()

        val member = Db.members().firstOrNull()
        if (member == null) { U.toast(this, "اول یک کاربر بساز ⚙️"); return }
        val catId = Db.addCategory("بدهی", "💳", 0)
        val txId = Db.insertTrans(member.id, catId, 0, amount, "${d.name} — ${monthName}", ts, false)
        Db.setDebtPaid(d.id, idx, true, txId)
        render()
            U.toast(this, "پرداخت ${U.money(amount)} تومان ثبت شد ✅", true)
    }

    private fun confirmCancel(idx: Int, txId: Long) {
        val dlg = AlertDialog.Builder(this)
            .setMessage("پرداخت این قسط لغو بشه؟\nتراکنش خرج مربوطه هم حذف می‌شود.")
            .setPositiveButton("بله، لغو کن") { _, _ -> doCancel(idx, txId) }
            .setNegativeButton("بی‌خیال", null)
            .create()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dlg.show()
    }

    private fun doCancel(idx: Int, txId: Long) {
        if (txId > 0) Db.deleteTrans(txId)
        Db.setDebtPaid(debtId, idx, false)
        render()
        U.toast(this, "پرداخت لغو شد ✓")
    }
}
