package ir.yosra.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class TransactionsActivity : Activity() {

    private var filter = 0
    private var rows = mutableListOf<Db.Trans>()
    private lateinit var listDrag: RowDrag
    private var cats: Map<Long, Db.Category> = emptyMap()
    private var mem: Map<Long, Db.Member> = emptyMap()
    private lateinit var list: ListView

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(p: Int): Any = rows[p]
        override fun getItemId(p: Int): Long = rows[p].id
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = cv ?: layoutInflater.inflate(R.layout.item_trans, parent, false)
            HomeActivity.bindRow(v, rows[p], cats, mem)
            listDrag.attach(v, v.findViewById(R.id.drag_handle))
            U.applyFont(v)
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Db.init(this)
        setContentView(R.layout.activity_transactions)

        list = findViewById(R.id.list)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            U.toast(this, U.transInfo(rows[pos], cats, mem), true)
        }
        list.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            val t = rows[pos]
            val isDebt = cats[t.catId]?.name == "بدهی"
            val items = if (isDebt) arrayOf("🗑 حذف") else arrayOf("✏️ ویرایش", "🗑 حذف")
            val dlg = AlertDialog.Builder(this)
                .setTitle("این تراکنش...")
                .setItems(items) { _, which ->
                    if (!isDebt && which == 0) edit(t)
                    else confirmDelete(t)
                }
                .setNegativeButton("بستن", null)
                .create()
            dlg.window?.decorView?.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            dlg.show()
            true
        }

        listDrag = RowDrag(list) { from, to ->
            if (from in rows.indices && to != from) {
                val moved = rows.removeAt(from)
                rows.add(to.coerceIn(0, rows.size), moved)
                Db.reorderTrans(rows.map { it.id })
                adapter.notifyDataSetChanged()
            }
        }

        findViewById<View>(R.id.btn_new).setOnClickListener {
            startActivity(Intent(this, AddTransactionActivity::class.java))
            overridePendingTransition(0, 0)
        }
        findViewById<View>(R.id.f_all).setOnClickListener { setFilter(0) }
        findViewById<View>(R.id.f_in).setOnClickListener { setFilter(1) }
        findViewById<View>(R.id.f_out).setOnClickListener { setFilter(2) }
    }

    private fun edit(t: Db.Trans) {
        if (cats[t.catId]?.name == "بدهی") {
            U.toast(this, "پرداخت بدهی از صفحه بدهی مدیریت می‌شود 💳")
            return
        }
        startActivity(
            Intent(this, AddTransactionActivity::class.java)
                .putExtra("edit_id", t.id)
                .putExtra("type", t.type)
        )
        overridePendingTransition(0, 0)
    }

    private fun confirmDelete(t: Db.Trans) {
        val dlg = AlertDialog.Builder(this)
            .setMessage("این تراکنش حذف بشه؟")
            .setPositiveButton("حذف") { _, _ ->
                Db.deleteTrans(t.id)
                load()
                U.toast(this, "حذف شد ✓")
            }
            .setNegativeButton("بی‌خیال", null)
            .create()
        dlg.window?.decorView?.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        dlg.show()
    }

    private fun setFilter(f: Int) {
        filter = f
        paintChips()
        load()
    }

    private fun paintChips() {
        paint(R.id.f_all, filter == 0, 0xFF6C5CE7.toInt())
        paint(R.id.f_in, filter == 1, 0xFF059669.toInt())
        paint(R.id.f_out, filter == 2, 0xFFE11D48.toInt())
    }

    private fun paint(id: Int, on: Boolean, color: Int) {
        val v = findViewById<TextView>(id)
        v.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip)
        v.setTextColor(if (on) color else 0xFF6B7194.toInt())
    }

    override fun onResume() {
        super.onResume()
        Nav.bind(this, 1)
        paintChips()
        load()
    }

    private fun load() {
        cats = Db.categories().associateBy { it.id }
        mem = Db.members().associateBy { it.id }
        rows = Db.allTrans().filter {
            filter == 0 || (filter == 1 && it.type == 1) || (filter == 2 && it.type == 0)
        }.toMutableList()
        adapter.notifyDataSetChanged()
    }
}
