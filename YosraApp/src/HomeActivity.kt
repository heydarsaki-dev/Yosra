package ir.yosra.app

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class HomeActivity : BaseActivity() {

    private var homeRows = mutableListOf<Db.Trans>()
    private lateinit var homeDrag: RowDrag

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Db.init(this)
        setContentView(R.layout.activity_home)

        val hbox = findViewById<LinearLayout>(R.id.latest_box)
        homeDrag = RowDrag(hbox) { from, to ->
            if (from in homeRows.indices && to != from) {
                val moved = homeRows.removeAt(from)
                homeRows.add(to.coerceIn(0, homeRows.size), moved)
                Db.reorderTrans(homeRows.map { it.id })
                render()
            }
        }

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            go(SettingsActivity::class.java)
        }
        findViewById<View>(R.id.btn_snapp).setOnClickListener {
            startActivity(android.content.Intent(this, AddTransactionActivity::class.java)
                .putExtra("type", 1))
            overridePendingTransition(0, 0)
        }
        findViewById<View>(R.id.btn_expense).setOnClickListener {
            startActivity(android.content.Intent(this, AddTransactionActivity::class.java)
                .putExtra("type", 0))
            overridePendingTransition(0, 0)
        }
        findViewById<View>(R.id.btn_all).setOnClickListener {
            go(TransactionsActivity::class.java)
        }
        findViewById<View>(R.id.btn_inst).setOnClickListener {
            startActivity(android.content.Intent(this, InstallmentsActivity::class.java))
            overridePendingTransition(0, 0)
        }
    }

    private fun go(cls: Class<*>) {
        startActivity(android.content.Intent(this, cls).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        Nav.bind(this, 0)
        render()
    }

    private fun render() {
        val members = Db.members()
        val first = members.firstOrNull()

        findViewById<TextView>(R.id.tv_greet).text = "سلام ${first?.name ?: ""} 👋"

        val trans = Db.allTrans()
        val tj = U.jParts(System.currentTimeMillis())
        var mIn = 0L
        var mOut = 0L
        var tOut = 0L
        var tIn = 0L
        for (t in trans) {
            val j = U.jParts(t.ts)
            if (j[0] == tj[0] && j[1] == tj[1]) {
                if (t.type == 1) mIn += t.amount else mOut += t.amount
            }
            if (U.isToday(t.ts)) {
                if (t.type == 0) tOut += t.amount else tIn += t.amount
            }
        }

        findViewById<TextView>(R.id.tv_m_income).text = U.money(mIn) + " تومان"
        findViewById<TextView>(R.id.tv_m_expense).text = U.money(mOut) + " تومان"
        val bal = Db.balance()
        val tvB = findViewById<TextView>(R.id.tv_m_balance)
        tvB.text = U.money(bal) + " تومان"
        tvB.setTextColor(if (bal < 0) T.red else T.primary)
        findViewById<TextView>(R.id.tv_today_snapp).text = U.money(tIn) + " تومان"
        findViewById<TextView>(R.id.tv_today_exp).text = U.money(tOut) + " تومان"

        val debts = Db.debts()
        val debtPaid = Db.debtPaidAll()
        val tvInst = findViewById<TextView>(R.id.tv_inst_sub)
        if (debts.isEmpty()) {
            tvInst.text = "بدهی ثبت نشده — برای افزودن کلیک کن"
        } else {
            var dueCount = 0
            var dueAmt = 0L
            var remaining = 0L
            for (d in debts) {
                val paid = debtPaid[d.id] ?: emptyMap()
                for (idx in 1..d.months) {
                    val amt = d.amountAt(idx)
                    if (paid.containsKey(idx)) continue
                    remaining += amt
                    val m = d.monthAt(idx)
                    if (m[0] == tj[0] && m[1] == tj[1]) { dueCount++; dueAmt += amt }
                }
            }
            tvInst.text = if (dueCount > 0)
                "${U.fa(dueCount.toString())} قسط این ماه (${U.money(dueAmt)} تومان) • بدهی باقی: ${U.money(remaining)} تومان"
            else
                "این ماه قسط نداری • بدهی باقی: ${U.money(remaining)} تومان"
        }

        val cats = Db.categories().associateBy { it.id }
        val mem = members.associateBy { it.id }
        val box = findViewById<LinearLayout>(R.id.latest_box)
        box.removeAllViews()
        val latest = trans.take(5)
        homeRows = latest.toMutableList()
        findViewById<TextView>(R.id.tv_empty).visibility =
            if (latest.isEmpty()) View.VISIBLE else View.GONE
        for (t in latest) {
            val v = layoutInflater.inflate(R.layout.item_trans, box, false)
            bindRow(v, t, cats, mem)
            homeDrag.attach(v, v.findViewById(R.id.drag_handle))
            val isDebt = cats[t.catId]?.name == "بدهی"
            v.setOnClickListener {
                U.toast(this, U.transInfo(t, cats, mem), true)
            }
            v.setOnLongClickListener {
                val items = if (isDebt) arrayOf("🗑 حذف") else arrayOf("✏️ ویرایش", "🗑 حذف")
                val dlg = android.app.AlertDialog.Builder(this)
                    .setTitle("این تراکنش...")
                    .setItems(items) { _, which ->
                        if (!isDebt && which == 0) {
                            startActivity(android.content.Intent(this, AddTransactionActivity::class.java)
                                .putExtra("edit_id", t.id)
                                .putExtra("type", t.type)
                            )
                            overridePendingTransition(0, 0)
                        } else confirmDeleteHome(t)
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
    }

    private fun confirmDeleteHome(t: Db.Trans) {
        val dlg = android.app.AlertDialog.Builder(this)
            .setMessage("این تراکنش حذف بشه؟")
            .setPositiveButton("حذف") { _, _ ->
                Db.deleteTrans(t.id)
                render()
                U.toast(this, "حذف شد ✓")
            }
            .setNegativeButton("بی‌خیال", null)
            .create()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dlg.show()
    }

    companion object {
        fun bindRow(v: View, t: Db.Trans, cats: Map<Long, Db.Category>, mem: Map<Long, Db.Member>) {            val cat = cats[t.catId]
            val m = mem[t.memberId]
            val av = v.findViewById<TextView>(R.id.it_avatar)
            av.text = m?.emoji ?: "؟"
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(m?.color ?: T.primary)
            av.background = gd
            val note = if (t.note.isNotBlank()) " — ${t.note}" else ""
            v.findViewById<TextView>(R.id.it_title).text =
                (cat?.let { "${it.name} ${it.emoji}" } ?: "سایر") + note
            v.findViewById<TextView>(R.id.it_sub).text =
                "${m?.name ?: ""} • ${U.nice(t.ts)} • ${U.clock(t.ts)}"
            val amt = v.findViewById<TextView>(R.id.it_amount)
            amt.text = (if (t.type == 1) "+ " else "− ") + U.money(t.amount) + " تومان"
            amt.setTextColor(if (t.type == 1) T.green else T.red)
        }
    }
}
