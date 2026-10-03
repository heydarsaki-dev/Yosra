package ir.yosra.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : BaseActivity() {

    private val emojis = arrayOf("", "🚕", "🏠", "🍔", "⛽", "🧾", "🛒", "💊", "🎮", "🔧", "💰", "💵", "⭐")
    private var catType = 1
    private var catExpanded = false

    private var catBoxRef: LinearLayout? = null
    private var curCats: List<Db.Category> = emptyList()
    private var dragView: View? = null
    private var dragOrigIndex = 0
    private var dragLastIndex = 0
    private var dragStartRawY = 0f
    private var lastRawY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Db.init(this)
        setContentView(R.layout.activity_settings)
        enablePullToRefresh()
        findViewById<View>(R.id.btn_add_member).setOnClickListener { memberDialog() }
        findViewById<View>(R.id.btn_add_cat).setOnClickListener { catDialog() }
        findViewById<View>(R.id.btn_cats_in).setOnClickListener { catType = 1; render() }
        findViewById<View>(R.id.btn_cats_out).setOnClickListener { catType = 0; render() }
        findViewById<View>(R.id.btn_backup).setOnClickListener { backup() }
        findViewById<View>(R.id.btn_restore).setOnClickListener { restore() }
        findViewById<View>(R.id.btn_sync_push).setOnClickListener { syncPush() }
        findViewById<View>(R.id.btn_sync_pull).setOnClickListener { syncPull() }
        findViewById<View>(R.id.btn_theme_system).setOnClickListener { pickTheme(T.SYSTEM) }
        findViewById<View>(R.id.btn_theme_light).setOnClickListener { pickTheme(T.LIGHT) }
        findViewById<View>(R.id.btn_theme_dark).setOnClickListener { pickTheme(T.DARK) }
    }

    /** انتخاب حالت ظاهر + ری‌استارت کامل برای اعمال uiMode روی همه اکتیویتی‌ها */
    private fun pickTheme(m: String) {
        if (T.mode == m) return
        T.pick(this, m)
        U.toast(
            applicationContext,
            when (m) {
                T.DARK -> "حالت تاریک فعال شد 🌙"
                T.LIGHT -> "حالت روشن فعال شد ☀️"
                else -> "حالتش از سیستم پیروی می‌کنه 📱"
            }
        )
        val i = android.content.Intent(this, SplashActivity::class.java)
        i.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        startActivity(i)
        overridePendingTransition(0, 0)
        finish()
    }

    private fun paintTheme() {
        fun p(id: Int, sel: Boolean) {
            val v = findViewById<TextView>(id)
            v.setBackgroundResource(if (sel) R.drawable.chip_on else R.drawable.chip)
            v.setTextColor(if (sel) T.primary else T.text2)
        }
        p(R.id.btn_theme_system, T.mode == T.SYSTEM)
        p(R.id.btn_theme_light, T.mode == T.LIGHT)
        p(R.id.btn_theme_dark, T.mode == T.DARK)
    }

    private fun syncPush() {
        U.toast(this, "در حال آپلود...")
        Thread {
            val msg = try { Sync.pushNow(applicationContext) } catch (e: Exception) { Sync.friendlyMsg(e) }
            runOnUiThread {
                U.toast(this, msg, true)
                try { renderSyncStatus(); render() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun syncPull() {
        val go = { force: Boolean ->
            U.toast(this, "در حال دریافت...")
            Thread {
                val msg = try { Sync.pullNow(applicationContext, force) } catch (e: Exception) { Sync.friendlyMsg(e) }
                runOnUiThread {
                    U.toast(this, msg, true)
                    try { renderSyncStatus(); render() } catch (_: Exception) {}
                }
            }.start()
        }
        // اگر تغییرات آپلودنشده محلی هست، اول هشدار بده
        Thread {
            val msg = try { Sync.pullNow(applicationContext, false) } catch (e: Exception) { Sync.friendlyMsg(e) }
            val blocked = msg.startsWith("تغییرات")
            runOnUiThread {
                if (!blocked) {
                    U.toast(this, msg, true)
                    try { renderSyncStatus(); render() } catch (_: Exception) {}
                } else {
                    val dlg = AlertDialog.Builder(this)
                        .setMessage("تغییرات آپلودنشده محلی داری. دریافت نسخه گیت‌هاب اونا رو جایگزین می‌کنه. ادامه بدم؟")
                        .setPositiveButton("بله، دریافت کن") { _, _ -> go(true) }
                        .setNegativeButton("بی‌خیال", null)
                        .create()
                    dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
                    dlg.show()
                }
            }
        }.start()
    }

    private fun renderSyncStatus() {
        val tv = findViewById<TextView>(R.id.sync_status)
        tv.text = if (Sync.token(this).isEmpty())
            "🔒 وارد نشده‌ای — از صفحه ورود اول وارد شو"
        else
            "آخرین همگام‌سازی: ${Sync.lastSyncText(this)}"
    }

    private fun backup() {
        val j = U.jParts(System.currentTimeMillis())
        val name = "yosra-backup-${j[0]}-${String.format("%02d-%02d", j[1], j[2])}.db"
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, 101)
    }

    private fun restore() {
        val dlg = AlertDialog.Builder(this)
            .setMessage("بازیابی، همه اطلاعات فعلی را با فایل بکاپ جایگزین می‌کند. ادامه بدم؟")
            .setPositiveButton("بله، بازیابی کن") { _, _ ->
                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                }
                startActivityForResult(intent, 102)
            }
            .setNegativeButton("بی‌خیال", null)
            .create()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dlg.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        try {
            when (requestCode) {
                101 -> {
                    contentResolver.openOutputStream(data.data!!)?.use { Db.backupTo(it) }
                    U.toast(this, "بکاپ گرفته شد ✅", true)
                }
                102 -> {
                    val bytes = contentResolver.openInputStream(data.data!!)?.use { it.readBytes() }
                    if (bytes == null || !Db.restoreFrom(bytes)) {
                        U.toast(this, "فایل بکاپ معتبر نیست ❌", true)
                    } else {
                        U.toast(this, "بازیابی انجام شد ✅", true)
                        render()
                    }
                }
            }
        } catch (e: Exception) {
            U.toast(this, "خطا: ${e.message}", true)
        }
    }

    override fun onResume() {
        super.onResume()
        Nav.bind(this, 3)
        render()
        renderSyncStatus()
        paintTheme()
    }

    private fun render() {
        val trans = Db.allTrans()
        val members = Db.members()
        val box = findViewById<LinearLayout>(R.id.member_box)
        box.removeAllViews()
        for (m in members) {
            val v = layoutInflater.inflate(R.layout.item_member, box, false)
            val av = v.findViewById<TextView>(R.id.m_avatar)
            av.text = m.emoji
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(m.color)
            av.background = gd
            val spent = trans.filter { it.memberId == m.id && it.type == 0 && U.inMonth(it.ts) }
                .sumOf { it.amount }
            v.findViewById<TextView>(R.id.m_name).text = m.name
            v.findViewById<TextView>(R.id.m_sub).text = "خرج این ماه: ${U.money(spent)} تومان"
            v.findViewById<View>(R.id.m_edit).setOnClickListener { memberDialog(m) }
            v.findViewById<View>(R.id.m_del).setOnClickListener {
                if (members.size <= 1) {
                    U.toast(this, "حداقل یک کاربر لازم است!")
                    return@setOnClickListener
                }
                confirm("«${m.name}» و همه تراکنش‌هایش حذف بشن؟") {
                    Db.deleteMember(m.id)
                    render()
                    U.toast(this, "حذف شد ✓")
                }
            }
            box.addView(v)
        }
        U.applyFont(box)

        val cBox = findViewById<LinearLayout>(R.id.cat_box)
        cBox.removeAllViews()
        val allCats = Db.categories().filter { it.type == catType }
        val cats = if (catExpanded) allCats else allCats.take(4)
        curCats = cats
        catBoxRef = cBox
        paintToggle(R.id.btn_cats_in, catType == 1, T.green)
        paintToggle(R.id.btn_cats_out, catType == 0, T.red)

        val more = findViewById<TextView>(R.id.btn_cats_more)
        if (allCats.size > 4) {
            more.visibility = View.VISIBLE
            more.text = if (catExpanded) "▲ بستن لیست"
            else "▼ نمایش همهٔ ${U.fa(allCats.size.toString())} دسته"
            more.setOnClickListener { catExpanded = !catExpanded; render() }
            U.applyFont(more)
        } else {
            more.visibility = View.GONE
        }

        for (c in cats) {
            val v = layoutInflater.inflate(R.layout.item_cat_row, cBox, false)
            val av = v.findViewById<TextView>(R.id.m_avatar)
            av.text = c.emoji
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(if (c.type == 1) T.greenSoft else T.redSoft)
            av.background = gd
            v.findViewById<TextView>(R.id.m_name).text = c.name
            v.findViewById<View>(R.id.m_edit).setOnClickListener { catDialog(c) }
            v.findViewById<View>(R.id.m_del).setOnClickListener {
                confirm("دسته «${c.name}» حذف بشه؟ تراکنش‌های قبلی‌اش بی‌دسته می‌شوند.") {
                    Db.deleteCategory(c.id)
                    render()
                    U.toast(this, "حذف شد ✓")
                }
            }

            val handle = TextView(this)
            handle.text = "⠿"
            handle.textSize = 17f
            handle.setTextColor(T.gray)
            handle.setPadding(U.dp(this, 7f), U.dp(this, 6f), U.dp(this, 4f), U.dp(this, 6f))
            (v as LinearLayout).addView(handle)

            v.setOnLongClickListener {
                startDrag(v, cBox)
                true
            }
            v.setOnTouchListener { view, ev ->
                lastRawY = ev.rawY
                if (dragView !== view) return@setOnTouchListener false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> dragMove(view, ev.rawY)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag(view)
                }
                true
            }

            cBox.addView(v)
        }
        U.applyFont(cBox)
    }

    private fun startDrag(v: View, box: LinearLayout) {
        dragView = v
        dragOrigIndex = box.indexOfChild(v)
        dragLastIndex = dragOrigIndex
        dragStartRawY = lastRawY
        v.elevation = U.dp(this, 12f).toFloat()
        var p: ViewParent? = v.parent
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(true)
            p = p.parent
        }
    }

    private fun dragMove(view: View, rawY: Float) {
        val box = catBoxRef ?: return
        val delta = rawY - dragStartRawY
        view.translationY = delta
        val center = view.top + view.height / 2f + delta
        var idx = 0
        for (i in 0 until box.childCount) {
            val ch = box.getChildAt(i)
            if (ch === view) continue
            if (ch.top + ch.height / 2f < center) idx++
        }
        if (idx != dragLastIndex) {
            dragLastIndex = idx
            for (i in 0 until box.childCount) {
                val ch = box.getChildAt(i)
                if (ch === view) continue
                ch.translationY = when {
                    i > dragOrigIndex && idx >= i ->
                        (box.getChildAt(i - 1).top - ch.top).toFloat()
                    i < dragOrigIndex && idx <= i ->
                        (box.getChildAt(i + 1).top - ch.top).toFloat()
                    else -> 0f
                }
            }
        }
    }

    private fun endDrag(view: View) {
        val box = catBoxRef
        val from = dragOrigIndex
        val to = dragLastIndex
        dragView = null
        view.elevation = 0f
        var p: ViewParent? = view.parent
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(false)
            p = p.parent
        }
        if (box != null && to != from && to in 0 until curCats.size) {
            val list = ArrayList(curCats)
            val moved = list.removeAt(from)
            list.add(to.coerceIn(0, list.size), moved)
            Db.setCategoryOrder(list.map { it.id })
            render()
        } else if (box != null) {
            for (i in 0 until box.childCount) box.getChildAt(i).translationY = 0f
        }
    }

    private fun paintToggle(id: Int, on: Boolean, color: Int) {
        val v = findViewById<TextView>(id)
        v.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip)
        v.setTextColor(if (on) color else T.text2)
    }

    private fun confirm(msg: String, action: () -> Unit) {
        val dlg = AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("حذف") { _, _ -> action() }
            .setNegativeButton("بی‌خیال", null)
            .create()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dlg.show()
    }

    private fun memberDialog(edit: Db.Member? = null) {
        val memEmojis = arrayOf("🚕", "🏠", "👦🏻", "👧🏻", "👩🏻", "👨🏻", "🌸", "⭐", "🐱", "💼")
        val colors = intArrayOf(
            0xFF6C5CE7.toInt(), 0xFFF06292.toInt(), 0xFF059669.toInt(), 0xFFF7971E.toInt(),
            0xFF29B6F6.toInt(), 0xFF8D6E63.toInt(), 0xFFEC407A.toInt(), 0xFF66BB6A.toInt()
        )
        var selEmoji = edit?.emoji ?: memEmojis[0]
        var selColor = edit?.color ?: colors[0]

        val root = dialogRoot()
        root.addView(label("اسم کاربر"))
        val et = input("")
        if (edit != null) et.setText(edit.name)
        root.addView(et)

        root.addView(label("ایموجی"))
        root.addView(emojiRow(memEmojis, selEmoji) { selEmoji = it })

        root.addView(label("رنگ آواتار"))
        val cRow = LinearLayout(this)
        cRow.orientation = LinearLayout.HORIZONTAL
        val colorViews = ArrayList<View>()
        for (c in colors) {
            val t = View(this)
            val lp = LinearLayout.LayoutParams(U.dp(this, 32f), U.dp(this, 32f))
            lp.marginEnd = U.dp(this, 10f)
            t.layoutParams = lp
            t.setOnClickListener {
                selColor = c
                markColor(colorViews, colors, selColor)
            }
            colorViews.add(t)
            cRow.addView(t)
        }
        root.addView(cRow)
        markColor(colorViews, colors, selColor)
        U.applyFont(root)

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (edit == null) "کاربر جدید" else "ویرایش کاربر")
            .setView(root)
            .setPositiveButton(if (edit == null) "افزودن" else "ذخیره") { _, _ ->
                val nm = et.text.toString().trim()
                if (nm.isEmpty()) {
                    U.toast(this, "اسم را بنویس 🙏")
                } else {
                    if (edit == null) {
                        Db.addMember(nm, selEmoji, selColor)
                        U.toast(this, "«$nm» اضافه شد ✓")
                    } else {
                        Db.updateMember(edit.id, nm, selEmoji, selColor)
                        U.toast(this, "«$nm» ویرایش شد ✓")
                    }
                    render()
                }
            }
            .setNegativeButton("بی‌خیال", null)
            .create()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dlg.show()
    }

    private fun catDialog(edit: Db.Category? = null) {
        var typeSel = edit?.type ?: 1
        var selEmoji = edit?.emoji ?: emojis[0]

        val root = dialogRoot()
        root.addView(label("اسم دسته"))
        val et = input("")
        if (edit != null) et.setText(edit.name)
        root.addView(et)

        root.addView(label("نوع"))
        val tRow = LinearLayout(this)
        tRow.orientation = LinearLayout.HORIZONTAL
        val tOut = TextView(this)
        val tIn = TextView(this)
        for ((tv, tt, col) in listOf(
            Triple(tIn, "درآمد", T.green),
            Triple(tOut, "خرج", T.red)
        )) {
            tv.text = tt
            tv.textSize = 14f
            tv.gravity = Gravity.CENTER
            tv.setPadding(U.dp(this, 14f), U.dp(this, 9f), U.dp(this, 14f), U.dp(this, 9f))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (tt == "خرج") lp.marginStart = U.dp(this, 8f)
            tv.layoutParams = lp
            tRow.addView(tv)
        }
        fun paintType() {
            tOut.setBackgroundResource(if (typeSel == 0) R.drawable.chip_on else R.drawable.chip)
            tOut.setTextColor(if (typeSel == 0) T.red else T.text2)
            tIn.setBackgroundResource(if (typeSel == 1) R.drawable.chip_on else R.drawable.chip)
            tIn.setTextColor(if (typeSel == 1) T.green else T.text2)
        }
        tOut.setOnClickListener { typeSel = 0; paintType() }
        tIn.setOnClickListener { typeSel = 1; paintType() }
        paintType()
        root.addView(tRow)

        root.addView(label("ایموجی (اختیاری)"))
        root.addView(emojiRow(emojis, selEmoji) { selEmoji = it })
        U.applyFont(root)

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (edit == null) "دسته‌بندی جدید" else "ویرایش دسته")
            .setView(root)
            .setPositiveButton(if (edit == null) "افزودن" else "ذخیره") { _, _ ->
                val nm = et.text.toString().trim()
                if (nm.isEmpty()) {
                    U.toast(this, "اسم دسته را بنویس 🙏")
                } else {
                    val em = if (selEmoji.isEmpty()) (if (typeSel == 1) "💰" else "✨") else selEmoji
                    if (edit == null) {
                        Db.addCategory(nm, em, typeSel)
                        U.toast(this, "«$nm» اضافه شد ✓")
                    } else {
                        Db.updateCategory(edit.id, nm, em, typeSel)
                        U.toast(this, "«$nm» ویرایش شد ✓")
                    }
                    render()
                }
            }
            .setNegativeButton("بی‌خیال", null)
            .create()
        dlg.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        dlg.show()
    }

    private fun dialogRoot(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val d = U.dp(this, 20f)
        root.setPadding(d, d, d, 0)
        return root
    }

    private fun input(hint: String): EditText {
        val et = EditText(this)
        et.hint = hint
        et.setBackgroundResource(R.drawable.input_bg)
        et.setPadding(U.dp(this, 14f), U.dp(this, 12f), U.dp(this, 14f), U.dp(this, 12f))
        return et
    }

    private fun emojiRow(list: Array<String>, initial: String, onPick: (String) -> Unit): HorizontalScrollView {
        val hsv = HorizontalScrollView(this)
        hsv.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val views = ArrayList<TextView>()
        for (e in list) {
            val t = TextView(this)
            t.text = if (e.isEmpty()) "— هیچ" else e
            t.textSize = 15f
            t.setPadding(U.dp(this, 12f), U.dp(this, 6f), U.dp(this, 12f), U.dp(this, 6f))
            t.gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = U.dp(this, 8f)
            t.layoutParams = lp
            t.setOnClickListener {
                onPick(e)
                for (tv in views) {
                    tv.setBackgroundResource(if (tv.tag == e) R.drawable.chip_on else R.drawable.chip)
                }
            }
            t.tag = e
            views.add(t)
            row.addView(t)
        }
        hsv.addView(row)
        for (tv in views) {
            tv.setBackgroundResource(if (tv.tag == initial) R.drawable.chip_on else R.drawable.chip)
        }
        return hsv
    }

    private fun markColor(views: List<View>, colors: IntArray, sel: Int) {
        for (i in views.indices) {
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(colors[i])
            if (colors[i] == sel) g.setStroke(U.dp(this, 3f), T.text)
            views[i].background = g
        }
    }

    private fun label(txt: String): TextView {
        val t = TextView(this)
        t.text = txt
        t.textSize = 13f
        t.setTextColor(T.text2)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, U.dp(this, 16f), 0, U.dp(this, 6f))
        t.layoutParams = lp
        return t
    }
}
