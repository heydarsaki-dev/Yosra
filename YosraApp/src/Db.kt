package ir.yosra.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object Db {

    data class Member(val id: Long, val name: String, val emoji: String, val color: Int)
    data class Category(val id: Long, val name: String, val emoji: String, val type: Int, val scope: Int)
    data class Trans(
        val id: Long, val memberId: Long, val catId: Long, val type: Int,
        val amount: Long, val note: String, val ts: Long, val isWork: Boolean
    )

    private var helper: Helper? = null
    private var appCtx: Context? = null
    private val dbLock = Any()

    fun init(c: Context) {
        appCtx = c.applicationContext
        synchronized(dbLock) {
            if (helper == null) helper = Helper(c.applicationContext)
        }
    }

    private fun db(): SQLiteDatabase = synchronized(dbLock) {
        if (helper == null) helper = Helper(appCtx ?: error("Db.init صدا زده نشده"))
        helper!!.writableDatabase
    }

    // ───────────────────────── اسکیما (نسخهٔ ۱۲) ─────────────────────────
    // همهٔ جملات IF NOT EXISTS هستند تا روی هر فایلی (حتی ناقص) بی‌خطر اجرا شوند.
    private const val DB_VERSION = 12

    private val SCHEMA_SQL = arrayOf(
        "CREATE TABLE IF NOT EXISTS members(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL, color INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS categories(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL, type INTEGER NOT NULL, scope INTEGER NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, member_id INTEGER NOT NULL, cat_id INTEGER NOT NULL, type INTEGER NOT NULL, amount INTEGER NOT NULL, note TEXT DEFAULT '', ts INTEGER NOT NULL, is_work INTEGER NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS installments(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, amount INTEGER NOT NULL, day INTEGER NOT NULL, start_y INTEGER NOT NULL DEFAULT 0, start_m INTEGER NOT NULL DEFAULT 0, months INTEGER NOT NULL DEFAULT 0, type INTEGER NOT NULL DEFAULT 0, total_amount INTEGER NOT NULL DEFAULT 0, paid_total INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS inst_paid(inst_id INTEGER NOT NULL, y INTEGER NOT NULL, m INTEGER NOT NULL, tx_id INTEGER NOT NULL DEFAULT 0, paid_amount INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(inst_id,y,m))",
        "CREATE TABLE IF NOT EXISTS debts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, total INTEGER NOT NULL, monthly INTEGER NOT NULL, start_y INTEGER NOT NULL, start_m INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS debt_paid(debt_id INTEGER NOT NULL, idx INTEGER NOT NULL, tx_id INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(debt_id,idx))"
    )

    /** همهٔ ستون‌های هر جدول (به‌جز id) — برای نرمال‌سازی فایل‌های دریافتی با ADD COLUMN امن */
    private val ALL_COLUMNS = mapOf(
        "members" to listOf(
            "name" to "TEXT NOT NULL DEFAULT ''", "emoji" to "TEXT NOT NULL DEFAULT ''",
            "color" to "INTEGER NOT NULL DEFAULT 0"
        ),
        "categories" to listOf(
            "name" to "TEXT NOT NULL DEFAULT ''", "emoji" to "TEXT NOT NULL DEFAULT ''",
            "type" to "INTEGER NOT NULL DEFAULT 0", "scope" to "INTEGER NOT NULL DEFAULT 2",
            "sort_order" to "INTEGER NOT NULL DEFAULT 0"
        ),
        "transactions" to listOf(
            "member_id" to "INTEGER NOT NULL DEFAULT 0", "cat_id" to "INTEGER NOT NULL DEFAULT 0",
            "type" to "INTEGER NOT NULL DEFAULT 0", "amount" to "INTEGER NOT NULL DEFAULT 0",
            "note" to "TEXT DEFAULT ''", "ts" to "INTEGER NOT NULL DEFAULT 0",
            "is_work" to "INTEGER NOT NULL DEFAULT 0", "sort_order" to "INTEGER NOT NULL DEFAULT 0"
        ),
        "installments" to listOf(
            "name" to "TEXT NOT NULL DEFAULT ''", "amount" to "INTEGER NOT NULL DEFAULT 0",
            "day" to "INTEGER NOT NULL DEFAULT 0", "start_y" to "INTEGER NOT NULL DEFAULT 0",
            "start_m" to "INTEGER NOT NULL DEFAULT 0", "months" to "INTEGER NOT NULL DEFAULT 0",
            "type" to "INTEGER NOT NULL DEFAULT 0", "total_amount" to "INTEGER NOT NULL DEFAULT 0",
            "paid_total" to "INTEGER NOT NULL DEFAULT 0"
        ),
        "inst_paid" to listOf(
            "inst_id" to "INTEGER NOT NULL DEFAULT 0", "y" to "INTEGER NOT NULL DEFAULT 0",
            "m" to "INTEGER NOT NULL DEFAULT 0", "tx_id" to "INTEGER NOT NULL DEFAULT 0",
            "paid_amount" to "INTEGER NOT NULL DEFAULT 0"
        ),
        "debts" to listOf(
            "name" to "TEXT NOT NULL DEFAULT ''", "total" to "INTEGER NOT NULL DEFAULT 0",
            "monthly" to "INTEGER NOT NULL DEFAULT 0", "start_y" to "INTEGER NOT NULL DEFAULT 0",
            "start_m" to "INTEGER NOT NULL DEFAULT 0"
        ),
        "debt_paid" to listOf(
            "debt_id" to "INTEGER NOT NULL DEFAULT 0", "idx" to "INTEGER NOT NULL DEFAULT 0",
            "tx_id" to "INTEGER NOT NULL DEFAULT 0"
        )
    )

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name))
            .use { it.moveToFirst() }

    private fun hasColumn(db: SQLiteDatabase, table: String, col: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) if (c.getString(1) == col) return true
            false
        }

    /** ایجاد جداول نایافته + افزودن ستون‌های جاافتاده — بی‌خطر و دیوانه‌پذیر */
    private fun ensureSchema(db: SQLiteDatabase) {
        for (sql in SCHEMA_SQL) {
            try { db.execSQL(sql) } catch (_: Exception) {}
        }
        for ((table, cols) in ALL_COLUMNS) {
            if (!hasTable(db, table)) continue
            for ((col, def) in cols) {
                if (!hasColumn(db, table, col)) {
                    try { db.execSQL("ALTER TABLE $table ADD COLUMN $col $def") } catch (_: Exception) {}
                }
            }
        }
    }

    // ───────────── کلید اصلی و کلیدهای خارجی (برای ادغام تدامنی) ─────────────
    private val PK_COLS = mapOf(
        "members" to listOf("id"),
        "categories" to listOf("id"),
        "transactions" to listOf("id"),
        "installments" to listOf("id"),
        "debts" to listOf("id"),
        "inst_paid" to listOf("inst_id", "y", "m"),
        "debt_paid" to listOf("debt_id", "idx")
    )
    private val FK_COLS = mapOf(
        "transactions" to mapOf("member_id" to "members", "cat_id" to "categories"),
        "inst_paid" to mapOf("inst_id" to "installments", "tx_id" to "transactions"),
        "debt_paid" to mapOf("debt_id" to "debts", "tx_id" to "transactions")
    )

    // ───────────── ثبت تغییرات محلی (outbox) برای همگام‌سازی دوطرفه ─────────────
    // op: 0=درج، 1=ویرایش، 2=حذف
    private fun log(table: String, key: String, op: Int, cv: ContentValues?) {
        val ctx = appCtx ?: return
        try {
            Sync.logChange(ctx, table, key, op, cv)
        } catch (_: Exception) {}
        // آپلود فوری دیتابیس آنلاین بعد از هر تغییر تراکنش/داده (در پس‌زمینه)
        try {
            Sync.pushAsync(ctx)
        } catch (_: Exception) {}
    }

    /** کلید مرکب جداول قسط‌ها */
    private fun ck(a: Long, b: Int, c: Int) = "$a,$b,$c"

    private class Helper(c: Context) : SQLiteOpenHelper(c, "yosra.db", null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            // روی فایل‌های ناقص (مثلاً فایل وب با user_version=0) هم بی‌خطر اجرا می‌شود
            ensureSchema(db)

            fun member(name: String, emoji: String, color: Long) {
                val v = ContentValues()
                v.put("name", name); v.put("emoji", emoji); v.put("color", color.toInt())
                db.insert("members", null, v)
            }
            // فقط وقتی خالی است دادهٔ پایه می‌کاریم (ممکن است فایل دریافتی پر باشد)
            if (countOf(db, "members") == 0L) {
                member("حیدر", "🚕", 0xFF6C5CE7)
                member("اسماء", "🏠", 0xFFF06292)
            }
            if (countOf(db, "categories") == 0L) seedCats(db)
        }

        private fun countOf(db: SQLiteDatabase, table: String): Long {
            if (!hasTable(db, table)) return 0L
            return try {
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                }
            } catch (_: Exception) { 0L }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // جداول/ستون‌ها به‌صورت دیوانه‌پذیر ساخته می‌شوند (ADD COLUMN فقط اگر نیست)
            ensureSchema(db)
            try {
            if (oldVersion < 2) {
                db.execSQL("DELETE FROM categories WHERE id NOT IN (SELECT DISTINCT cat_id FROM transactions)")
            }
            if (oldVersion < 3 && countOf(db, "categories") == 0L) seedCats(db)
            if (oldVersion < 4) {
                insertCatIfMissing(db, "ظروف مصنوعی", "🥣", 1)
            }
            if (oldVersion < 5) {
                db.execSQL("UPDATE categories SET emoji='🥣' WHERE name='ظروف مصنوعی'")
            }
            if (oldVersion < 8) {
                val j = U.jParts(System.currentTimeMillis())
                db.execSQL("UPDATE installments SET start_y=${j[0]}, start_m=${j[1]} WHERE start_y=0")
            }
            if (oldVersion < 12) {
                db.execSQL("UPDATE transactions SET sort_order = (SELECT COUNT(*) FROM transactions t2 WHERE t2.ts > transactions.ts OR (t2.ts = transactions.ts AND t2.id > transactions.id))")
            }
            } catch (_: Exception) {
                // یک مرحله شکست خورد → جداول نرمال‌شده‌اند و نسخه به‌روز می‌شود؛ بهتر از کرش
            }
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // فایل از نسخهٔ بالاتر: ستون‌های ما را تضمین می‌کنیم و می‌پذیریم
            ensureSchema(db)
        }

        private fun seedCats(db: SQLiteDatabase) {
            fun cat(name: String, emoji: String, type: Int) {
                val v = ContentValues()
                v.put("name", name); v.put("emoji", emoji); v.put("type", type); v.put("scope", 2)
                db.insert("categories", null, v)
            }
            cat("سوخت", "⛽", 0)
            cat("سرویس و تعمیر", "🔧", 0)
            cat("بیمه و جریمه", "📋", 0)
            cat("خوراک", "🍔", 0)
            cat("قبض‌ها", "🧾", 0)
            cat("خرید", "🛒", 0)
            cat("اجاره خانه", "🏠", 0)
            cat("سلامت", "💊", 0)
            cat("تفریح", "🎮", 0)
            cat("متفرقه", "✨", 0)
            cat("اسنپ", "🚕", 1)
            cat("پاداش و فالوور", "💵", 1)
            cat("درآمد دیگر", "💰", 1)
            cat("ظروف مصنوعی", "🥣", 1)
        }

        private fun insertCatIfMissing(db: SQLiteDatabase, name: String, emoji: String, type: Int) {
            val c = db.rawQuery("SELECT id FROM categories WHERE name=? AND type=?", arrayOf(name, type.toString()))
            val exists = c.use { it.moveToFirst() }
            if (!exists) {
                val v = ContentValues()
                v.put("name", name); v.put("emoji", emoji); v.put("type", type); v.put("scope", 2)
                db.insert("categories", null, v)
            }
        }
    }

    fun splitEmoji(text: String): Pair<String, String> {
        val emojiB = StringBuilder()
        val nameB = StringBuilder()
        for (ch in text) {
            val c = ch.code
            val isEmoji = c in 0x1F000..0x1FAFF || c in 0x2600..0x27BF ||
                c in 0x2B00..0x2BFF || c in 0xFE00..0xFE0F || c == 0x200D
            if (isEmoji) emojiB.append(ch)
            else if (ch != ' ' || nameB.isNotEmpty()) nameB.append(ch)
        }
        return Pair(emojiB.toString(), nameB.toString().trim())
    }

    fun addCategory(rawName: String, fallbackEmoji: String, type: Int): Long {
        val (e, n) = splitEmoji(rawName)
        var name = n
        if (name.isEmpty()) name = if (type == 1) "درآمد" else "متفرقه"
        val emoji = if (e.isNotEmpty()) e else fallbackEmoji

        val c = db().rawQuery("SELECT id FROM categories WHERE name=? AND type=?", arrayOf(name, type.toString()))
        val found = c.use { if (it.moveToFirst()) it.getLong(0) else -1L }
        if (found != -1L) return found

        val next = db().rawQuery("SELECT IFNULL(MAX(sort_order),-1)+1 FROM categories WHERE type=?", arrayOf(type.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

        val v = ContentValues()
        v.put("name", name); v.put("emoji", emoji); v.put("type", type); v.put("scope", 2)
        v.put("sort_order", next)
        val id = db().insert("categories", null, v)
        v.put("id", id)
        log("categories", id.toString(), 0, v)
        return id
    }

    fun setCategoryOrder(ids: List<Long>) {
        val cv = ContentValues()
        for (i in ids.indices) {
            cv.clear()
            cv.put("sort_order", i)
            db().update("categories", cv, "id=?", arrayOf(ids[i].toString()))
            log("categories", ids[i].toString(), 1, cv)
        }
    }

    data class Inst(
        val id: Long, val name: String, val amount: Long, val day: Int,
        val startY: Int, val startM: Int, val months: Int,
        val type: Int, val totalAmount: Long, val paidTotal: Long
    ) {
        val isLoan: Boolean get() = type == 1
        fun activeIn(y: Int, m: Int): Boolean {
            if (startY != 0 && (y < startY || (y == startY && m < startM))) return false
            if (months > 0 && startY != 0) {
                var em = startM + months - 1
                var ey = startY
                while (em > 12) { em -= 12; ey++ }
                if (y > ey || (y == ey && m > em)) return false
            }
            return true
        }
    }

    /** بدهی من به یک نفر (طلبکار) — کل بدهی + مبلغ ماهانه */
    data class Debt(
        val id: Long, val name: String, val total: Long, val monthly: Long,
        val startY: Int, val startM: Int
    ) {
        val months: Int
            get() = if (monthly <= 0) 1
            else ((total + monthly - 1) / monthly).toInt().coerceAtLeast(1)

        /** مبلغ قسط شماره idx (۱تاN) — آخری باقی‌مانده کل */
        fun amountAt(idx: Int): Long {
            val m = months
            return if (idx < m) monthly else total - monthly * (m - 1)
        }

        /** ماه (سال، ماه) قسط شماره idx */
        fun monthAt(idx: Int): IntArray {
            var mm = startM + (idx - 1)
            var yy = startY
            while (mm > 12) { mm -= 12; yy++ }
            while (mm < 1) { mm += 12; yy-- }
            return intArrayOf(yy, mm)
        }
    }

    fun installments(): List<Inst> {
        val out = ArrayList<Inst>()
        val c = db().rawQuery(
            "SELECT id,name,amount,day,start_y,start_m,months,type,total_amount,paid_total FROM installments ORDER BY day,id", null
        )
        c.use {
            while (it.moveToNext()) out.add(
                Inst(
                    it.getLong(0), it.getString(1), it.getLong(2), it.getInt(3),
                    it.getInt(4), it.getInt(5), it.getInt(6),
                    it.getInt(7), it.getLong(8), it.getLong(9)
                )
            )
        }
        return out
    }

    fun installmentsFor(y: Int, m: Int): List<Inst> = installments().filter { it.activeIn(y, m) }

    fun addInstallment(
        name: String, amount: Long, day: Int, startY: Int, startM: Int, months: Int,
        type: Int = 0, totalAmount: Long = 0L, paidTotal: Long = 0L
    ): Long {
        val v = ContentValues()
        v.put("name", name); v.put("amount", amount); v.put("day", day)
        v.put("start_y", startY); v.put("start_m", startM); v.put("months", months)
        v.put("type", type); v.put("total_amount", totalAmount); v.put("paid_total", paidTotal)
        val id = db().insert("installments", null, v)
        v.put("id", id)
        log("installments", id.toString(), 0, v)
        return id
    }

    fun updateInstallment(id: Long, name: String, amount: Long, day: Int, months: Int) {
        val v = ContentValues()
        v.put("name", name); v.put("amount", amount); v.put("day", day); v.put("months", months)
        db().update("installments", v, "id=?", arrayOf(id.toString()))
        log("installments", id.toString(), 1, v)
    }

    fun deleteInstallment(id: Long) {
        db().rawQuery("SELECT y,m FROM inst_paid WHERE inst_id=?", arrayOf(id.toString())).use {
            while (it.moveToNext()) log("inst_paid", ck(id, it.getInt(0), it.getInt(1)), 2, null)
        }
        db().delete("inst_paid", "inst_id=?", arrayOf(id.toString()))
        db().delete("installments", "id=?", arrayOf(id.toString()))
        log("installments", id.toString(), 2, null)
    }

    fun paidTx(y: Int, m: Int): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        val c = db().rawQuery(
            "SELECT inst_id,tx_id,paid_amount FROM inst_paid WHERE y=? AND m=?",
            arrayOf(y.toString(), m.toString())
        )
        c.use {
            while (it.moveToNext()) {
                val key = it.getLong(0)
                val tx = it.getLong(1)
                val amt = it.getLong(2)
                if (tx > 0) out[key] = tx
            }
        }
        return out
    }

    /** مجموع پرداختی قبلی یک وام (از ابتدای وام تا الان) */
    fun paidLoanTotal(instId: Long): Long {
        val c = db().rawQuery("SELECT COALESCE(SUM(paid_amount),0) FROM inst_paid WHERE inst_id=?", arrayOf(instId.toString()))
        c.use {
            it.moveToFirst()
            return it.getLong(0)
        }
    }

    // ==================== بدهی‌ها (طلبکارها) ====================

    fun debts(): List<Debt> {
        val out = ArrayList<Debt>()
        val c = db().rawQuery(
            "SELECT id,name,total,monthly,start_y,start_m FROM debts ORDER BY id DESC", null
        )
        c.use {
            while (it.moveToNext()) out.add(
                Debt(it.getLong(0), it.getString(1), it.getLong(2), it.getLong(3), it.getInt(4), it.getInt(5))
            )
        }
        return out
    }

    fun getDebt(id: Long): Debt? {
        val c = db().rawQuery(
            "SELECT id,name,total,monthly,start_y,start_m FROM debts WHERE id=?",
            arrayOf(id.toString())
        )
        c.use {
            if (it.moveToFirst()) return Debt(
                it.getLong(0), it.getString(1), it.getLong(2), it.getLong(3), it.getInt(4), it.getInt(5)
            )
        }
        return null
    }

    fun addDebt(name: String, total: Long, monthly: Long, startY: Int, startM: Int): Long {
        val v = ContentValues()
        v.put("name", name); v.put("total", total); v.put("monthly", monthly)
        v.put("start_y", startY); v.put("start_m", startM)
        val id = db().insert("debts", null, v)
        v.put("id", id)
        log("debts", id.toString(), 0, v)
        return id
    }

    fun updateDebt(id: Long, name: String, total: Long, monthly: Long) {
        val v = ContentValues()
        v.put("name", name); v.put("total", total); v.put("monthly", monthly)
        db().update("debts", v, "id=?", arrayOf(id.toString()))
        log("debts", id.toString(), 1, v)
    }

    /** حذف بدهی + سوابق پرداخت + تراکنش‌های مرتبط */
    fun deleteDebt(id: Long) {
        db().rawQuery("SELECT idx,tx_id FROM debt_paid WHERE debt_id=?", arrayOf(id.toString())).use {
            while (it.moveToNext()) log("debt_paid", "$id,${it.getInt(0)}", 2, null)
        }
        val c = db().rawQuery("SELECT tx_id FROM debt_paid WHERE debt_id=? AND tx_id>0", arrayOf(id.toString()))
        val txIds = mutableListOf<Long>()
        c.use { while (it.moveToNext()) txIds.add(it.getLong(0)) }
        for (t in txIds) {
            db().delete("transactions", "id=?", arrayOf(t.toString()))
            log("transactions", t.toString(), 2, null)
        }
        db().delete("debt_paid", "debt_id=?", arrayOf(id.toString()))
        db().delete("debts", "id=?", arrayOf(id.toString()))
        log("debts", id.toString(), 2, null)
    }

    /** نقشه‌ی کل قسط‌های پرداخت‌شده: debt_id → (شماره قسط → شناسه تراکنش) */
    fun debtPaidAll(): Map<Long, Map<Int, Long>> {
        val out = HashMap<Long, HashMap<Int, Long>>()
        val c = db().rawQuery("SELECT debt_id,idx,tx_id FROM debt_paid", null)
        c.use {
            while (it.moveToNext()) {
                val d = it.getLong(0)
                out.getOrPut(d) { HashMap() }[it.getInt(1)] = it.getLong(2)
            }
        }
        return out
    }

    fun setDebtPaid(debtId: Long, idx: Int, paid: Boolean, txId: Long = 0L) {
        if (paid) {
            val v = ContentValues()
            v.put("debt_id", debtId); v.put("idx", idx); v.put("tx_id", txId)
            db().insertWithOnConflict("debt_paid", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            log("debt_paid", "$debtId,$idx", 0, v)
        } else {
            db().delete("debt_paid", "debt_id=? AND idx=?", arrayOf(debtId.toString(), idx.toString()))
            log("debt_paid", "$debtId,$idx", 2, null)
        }
    }

    fun paidInst(y: Int, m: Int): Set<Long> = paidTx(y, m).keys

    fun setInstPaid(instId: Long, y: Int, m: Int, paid: Boolean, txId: Long = 0L, paidAmount: Long = 0L) {
        if (paid) {
            val v = ContentValues()
            v.put("inst_id", instId); v.put("y", y); v.put("m", m)
            v.put("tx_id", txId); v.put("paid_amount", paidAmount)
            db().insertWithOnConflict("inst_paid", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            log("inst_paid", ck(instId, y, m), 0, v)
        } else {
            db().delete(
                "inst_paid", "inst_id=? AND y=? AND m=?",
                arrayOf(instId.toString(), y.toString(), m.toString())
            )
            log("inst_paid", ck(instId, y, m), 2, null)
        }
    }

    /** مبلغ واقعی پرداخت‌شده‌ی هر قسط در این ماه (inst_id → مبلغ) */
    fun paidAmounts(y: Int, m: Int): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        val c = db().rawQuery(
            "SELECT inst_id,paid_amount FROM inst_paid WHERE y=? AND m=?",
            arrayOf(y.toString(), m.toString())
        )
        c.use { while (it.moveToNext()) out[it.getLong(0)] = it.getLong(1) }
        return out
    }

    /** پرداخت وام یا اقساطی با مبلغ دلخواه */
    fun payLoan(instId: Long, y: Int, m: Int, payAmount: Long, txId: Long) {
        val v = ContentValues()
        v.put("inst_id", instId); v.put("y", y); v.put("m", m)
        v.put("tx_id", txId); v.put("paid_amount", payAmount)
        db().insertWithOnConflict("inst_paid", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        log("inst_paid", ck(instId, y, m), 0, v)
    }

    /** لغو پرداخت وام یا اقساطی در این ماه */
    fun cancelLoanPayment(instId: Long, y: Int, m: Int) {
        db().delete(
            "inst_paid", "inst_id=? AND y=? AND m=? AND paid_amount>0",
            arrayOf(instId.toString(), y.toString(), m.toString())
        )
        log("inst_paid", ck(instId, y, m), 2, null)
    }

    fun deleteCategory(id: Long) {
        db().delete("categories", "id=?", arrayOf(id.toString()))
        log("categories", id.toString(), 2, null)
    }

    fun updateCategory(id: Long, name: String, emoji: String, type: Int) {
        val v = ContentValues()
        v.put("name", name); v.put("emoji", emoji); v.put("type", type)
        db().update("categories", v, "id=?", arrayOf(id.toString()))
        log("categories", id.toString(), 1, v)
    }

    fun members(): List<Member> {
        val out = ArrayList<Member>()
        val c = db().rawQuery("SELECT id,name,emoji,color FROM members ORDER BY id", null)
        c.use {
            while (it.moveToNext()) out.add(Member(it.getLong(0), it.getString(1), it.getString(2), it.getInt(3)))
        }
        return out
    }

    fun addMember(name: String, emoji: String, color: Int): Long {
        val v = ContentValues()
        v.put("name", name); v.put("emoji", emoji); v.put("color", color)
        val id = db().insert("members", null, v)
        v.put("id", id)
        log("members", id.toString(), 0, v)
        return id
    }

    fun deleteMember(id: Long) {
        db().rawQuery("SELECT id FROM transactions WHERE member_id=?", arrayOf(id.toString())).use {
            while (it.moveToNext()) log("transactions", it.getLong(0).toString(), 2, null)
        }
        db().delete("transactions", "member_id=?", arrayOf(id.toString()))
        db().delete("members", "id=?", arrayOf(id.toString()))
        log("members", id.toString(), 2, null)
    }

    fun updateMember(id: Long, name: String, emoji: String, color: Int) {
        val v = ContentValues()
        v.put("name", name); v.put("emoji", emoji); v.put("color", color)
        db().update("members", v, "id=?", arrayOf(id.toString()))
        log("members", id.toString(), 1, v)
    }

    fun categories(): List<Category> {
        val out = ArrayList<Category>()
        val c = db().rawQuery("SELECT id,name,emoji,type,scope FROM categories ORDER BY type DESC,sort_order,id", null)
        c.use {
            while (it.moveToNext()) out.add(Category(it.getLong(0), it.getString(1), it.getString(2), it.getInt(3), it.getInt(4)))
        }
        return out
    }

    fun insertTrans(memberId: Long, catId: Long, type: Int, amount: Long, note: String, ts: Long, isWork: Boolean): Long {
        val v = ContentValues()
        v.put("member_id", memberId); v.put("cat_id", catId); v.put("type", type)
        v.put("amount", amount); v.put("note", note); v.put("ts", ts)
        v.put("is_work", if (isWork) 1 else 0)
        val minS = db().rawQuery("SELECT IFNULL(MIN(sort_order),1)-1 FROM transactions", null)
            .use { it.moveToFirst(); it.getLong(0) }
        v.put("sort_order", minS)
        val id = db().insert("transactions", null, v)
        v.put("id", id)
        log("transactions", id.toString(), 0, v)
        return id
    }

    fun allTrans(): List<Trans> {
        val out = ArrayList<Trans>()
        val c = db().rawQuery("SELECT id,member_id,cat_id,type,amount,note,ts,is_work FROM transactions ORDER BY sort_order ASC,ts DESC,id DESC", null)
        c.use {
            while (it.moveToNext()) out.add(
                Trans(it.getLong(0), it.getLong(1), it.getLong(2), it.getInt(3),
                    it.getLong(4), it.getString(5) ?: "", it.getLong(6), it.getInt(7) == 1)
            )
        }
        return out
    }

    /** جابجایی دستی ترتیب — مقادیر sort_order بین همین ردیف‌ها جابجا می‌شوند تا بقیه‌ها جابه‌جا نشن */
    fun reorderTrans(ids: List<Long>) {
        if (ids.size < 2) return
        val d = db()
        val marks = ids.joinToString(",") { "?" }
        val cur = LinkedHashMap<Long, Long>()
        d.rawQuery("SELECT id, sort_order FROM transactions WHERE id IN ($marks)",
            ids.map { it.toString() }.toTypedArray()).use {
            while (it.moveToNext()) cur[it.getLong(0)] = it.getLong(1)
        }
        if (cur.size < 2) return
        val sorted = cur.values.sorted()
        val values = if (sorted.distinct().size == sorted.size) sorted
        else {
            val base = (sorted.minOrNull() ?: 0L) - sorted.size
            sorted.mapIndexed { i, _ -> base + i }
        }
        d.beginTransaction()
        try {
            var i = 0
            for (id in ids) {
                if (!cur.containsKey(id)) continue
                d.execSQL("UPDATE transactions SET sort_order=? WHERE id=?",
                    arrayOf(values[i].toString(), id.toString()))
                val cv = ContentValues()
                cv.put("sort_order", values[i])
                log("transactions", id.toString(), 1, cv)
                i++
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun deleteTrans(id: Long) {
        // اگه این تراکنش مال پرداخت یک قسط بدهی/اقساط بود، وضعیتش به «پرداخت‌نشده» برمی‌گرده
        db().rawQuery("SELECT inst_id,y,m FROM inst_paid WHERE tx_id=?", arrayOf(id.toString())).use {
            while (it.moveToNext()) log("inst_paid", ck(it.getLong(0), it.getInt(1), it.getInt(2)), 2, null)
        }
        db().rawQuery("SELECT debt_id,idx FROM debt_paid WHERE tx_id=?", arrayOf(id.toString())).use {
            while (it.moveToNext()) log("debt_paid", "${it.getLong(0)},${it.getInt(1)}", 2, null)
        }
        db().delete("debt_paid", "tx_id=?", arrayOf(id.toString()))
        db().delete("inst_paid", "tx_id=?", arrayOf(id.toString()))
        db().delete("transactions", "id=?", arrayOf(id.toString()))
        log("transactions", id.toString(), 2, null)
    }

    /** موجودی کلی = کل درآمد − کل خرج (همه زمان‌ها) */
    fun balance(): Long {
        var mIn = 0L
        var mOut = 0L
        for (t in allTrans()) {
            if (t.type == 1) mIn += t.amount else mOut += t.amount
        }
        return mIn - mOut
    }

    fun getTrans(id: Long): Trans? {
        val c = db().rawQuery(
            "SELECT id,member_id,cat_id,type,amount,note,ts,is_work FROM transactions WHERE id=?",
            arrayOf(id.toString())
        )
        c.use {
            if (it.moveToFirst()) return Trans(
                it.getLong(0), it.getLong(1), it.getLong(2), it.getInt(3),
                it.getLong(4), it.getString(5) ?: "", it.getLong(6), it.getInt(7) == 1
            )
        }
        return null
    }

    fun updateTrans(id: Long, memberId: Long, catId: Long, type: Int, amount: Long, note: String, ts: Long) {
        val v = ContentValues()
        v.put("member_id", memberId); v.put("cat_id", catId); v.put("type", type)
        v.put("amount", amount); v.put("note", note); v.put("ts", ts)
        db().update("transactions", v, "id=?", arrayOf(id.toString()))
        log("transactions", id.toString(), 1, v)
    }

    fun backupTo(out: java.io.OutputStream) {
        val db = db()
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } catch (_: Exception) {}
        // قفل انحصاری حین کپی تا write هم‌زمان فایل را پاره نکند —
        // بکاپِ پاره روی گیت‌هاب می‌رود و وب آن را «بکاپ نامعتبر» اعلام می‌کند
        var locked = false
        var tries = 0
        while (!locked && tries < 3) {
            try {
                db.execSQL("BEGIN EXCLUSIVE")
                locked = true
            } catch (_: Exception) {
                tries++
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        try {
            val f = java.io.File(db.path)
            f.inputStream().use { it.copyTo(out) }
        } finally {
            if (locked) {
                try {
                    db.execSQL("COMMIT")
                } catch (_: Exception) {
                    try {
                        db.execSQL("ROLLBACK")
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /** فایل دیتابیس — برای تشخیص تغییرات آپلودنشده */
    fun dbFile(): java.io.File = java.io.File(db().path)

    /** همگام‌سازی WAL با فایل اصلی — قبل از مقایسه زمان/آپلود */
    fun checkpointNow() {
        try {
            db().rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } catch (_: Exception) {}
    }

    /**
     * جایگزینی دیتابیس با فایل دریافتی (بکاپ یا همگام‌سازی).
     * فایل ابتدا نرمال می‌شود (اسکمای کامل + user_version) تا اپ بدون کرش بازش کند،
     * و تعویض روی thread اصلی انجام می‌شود تا هیچ کوئری هم‌زمانی به helperِ null نخورد.
     */
    fun restoreFrom(data: ByteArray): Boolean {
        if (data.size < 16) return false
        if (String(data, 0, 15, Charsets.US_ASCII) != "SQLite format 3") return false
        // تعویض حتماً روی thread اصلی (کوئری‌های UI هم‌زمان هستند)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return doRestore(data)
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            ok = try { doRestore(data) } catch (_: Throwable) { false }
            latch.countDown()
        }
        latch.await()
        return ok
    }

    private fun doRestore(data: ByteArray): Boolean {
        val ctx = appCtx ?: return false
        return try {
            val path = db().path
            val tmp = java.io.File("$path.new")
            tmp.writeBytes(data)
            if (!normalize(tmp)) {
                tmp.delete()
                return false
            }
            synchronized(dbLock) {
                helper?.close()
                helper = null
                val dest = java.io.File(path)
                java.io.File("$path-wal").delete()
                java.io.File("$path-shm").delete()
                java.io.File("$path-journal").delete()
                // روی لینوکسِ اندروید rename به‌صورت اتمیک جایگزین می‌کند — بدون پنجرهٔ خطر
                if (!tmp.renameTo(dest)) {
                    dest.delete()
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                helper = Helper(ctx)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** نرمال‌سازی فایل دریافتی: جداول ناقص کامل می‌شوند و user_version روی نسخهٔ اپ تنظیم می‌شود.
     *  دلیل: فایلی که وب با sql.js می‌سازد user_version ندارد (۰ است) و باز کردنش در اپ
     *  باعث اجرای onCreate روی جداول موجود → کرش می‌شد. */
    private fun normalize(f: java.io.File): Boolean {
        return try {
            val db = SQLiteDatabase.openDatabase(
                f.path, null,
                SQLiteDatabase.OPEN_READWRITE
            )
            db.use {
                // حداقلٍ جداول اصلی باید موجود باشند، وگرنه فایل نامعتبر است
                if (!hasTable(it, "transactions") || !hasTable(it, "members") ||
                    !hasTable(it, "categories")
                ) return false
                ensureSchema(it)
                it.execSQL("PRAGMA user_version = $DB_VERSION")
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * اعمال تغییرات ثبت‌شده (outbox) روی دیتابیس جاری — که تازه از گیت‌هاب جایگزین شده.
     * ردیف‌های جدید آیدی آزاد می‌گیرند (تا با آیدی‌های آنلاین تداخل نکنند) و
     * کلیدهای خارجی هم‌زمان مپ می‌شوند. کل عملیات اتمیک است: در صورت خطا،
     * چیزی اعمال نمی‌شود و فراخوانی باید false برگرداند.
     */
    fun replayOutbox(entries: List<Sync.OutEntry>): Boolean {
        if (entries.isEmpty()) return true
        val d = db()
        val counters = HashMap<String, Long>()
        val maps = HashMap<String, HashMap<Long, Long>>() // جدول: آیدی قدیمی → جدید

        fun nextId(table: String): Long {
            val cur = counters.getOrPut(table) {
                try {
                    d.rawQuery("SELECT IFNULL(MAX(id),0) FROM $table", null)
                        .use { if (it.moveToFirst()) it.getLong(0) else 0L }
                } catch (_: Exception) { 0L }
            }
            val n = cur + 1
            counters[table] = n
            return n
        }
        fun remap(parent: String, oldId: Long): Long = maps[parent]?.get(oldId) ?: oldId

        d.beginTransaction()
        try {
            for (e in entries) {
                val pk = PK_COLS[e.table] ?: continue
                if (e.keyParts.size != pk.size) continue

                // مپ کلیدها برای شرط WHERE
                val where = pk.joinToString(" AND ") { "$it=?" }
                val args = pk.mapIndexed { i, col ->
                    val parent = FK_COLS[e.table]?.get(col)
                    when {
                        parent != null -> remap(parent, e.keyParts[i]).toString()
                        col == "id" -> remap(e.table, e.keyParts[i]).toString()
                        else -> e.keyParts[i].toString()
                    }
                }.toTypedArray()

                when (e.op) {
                    0 -> { // درج
                        val cv = ContentValues(e.row)
                        if (pk == listOf("id")) {
                            val newId = nextId(e.table)
                            maps.getOrPut(e.table) { HashMap() }[e.keyParts[0]] = newId
                            cv.put("id", newId)
                        }
                        val fks = FK_COLS[e.table] ?: emptyMap()
                        for ((col, parent) in fks) {
                            val v = cv.get(col) ?: continue
                            val oldRef = (v as? Number)?.toLong() ?: continue
                            cv.put(col, remap(parent, oldRef))
                        }
                        d.insertWithOnConflict(e.table, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    1 -> { // ویرایش
                        val cv = ContentValues(e.row)
                        val fks = FK_COLS[e.table] ?: emptyMap()
                        for ((col, parent) in fks) {
                            val v = cv.get(col) ?: continue
                            val oldRef = (v as? Number)?.toLong() ?: continue
                            cv.put(col, remap(parent, oldRef))
                        }
                        d.update(e.table, cv, where, args)
                    }
                    2 -> { // حذف
                        d.delete(e.table, where, args)
                    }
                }
            }
            d.setTransactionSuccessful()
            return true
        } catch (_: Exception) {
            return false
        } finally {
            d.endTransaction()
        }
    }
}
