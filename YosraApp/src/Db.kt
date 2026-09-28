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

    fun init(c: Context) {
        appCtx = c.applicationContext
        if (helper == null) helper = Helper(c.applicationContext)
    }

    private fun db(): SQLiteDatabase = helper!!.writableDatabase

    private class Helper(c: Context) : SQLiteOpenHelper(c, "yosra.db", null, 11) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE members(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL, color INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE categories(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, emoji TEXT NOT NULL, type INTEGER NOT NULL, scope INTEGER NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, member_id INTEGER NOT NULL, cat_id INTEGER NOT NULL, type INTEGER NOT NULL, amount INTEGER NOT NULL, note TEXT DEFAULT '', ts INTEGER NOT NULL, is_work INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS installments(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, amount INTEGER NOT NULL, day INTEGER NOT NULL, start_y INTEGER NOT NULL DEFAULT 0, start_m INTEGER NOT NULL DEFAULT 0, months INTEGER NOT NULL DEFAULT 0, type INTEGER NOT NULL DEFAULT 0, total_amount INTEGER NOT NULL DEFAULT 0, paid_total INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS inst_paid(inst_id INTEGER NOT NULL, y INTEGER NOT NULL, m INTEGER NOT NULL, tx_id INTEGER NOT NULL DEFAULT 0, paid_amount INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(inst_id,y,m))")
            db.execSQL("CREATE TABLE IF NOT EXISTS debts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, total INTEGER NOT NULL, monthly INTEGER NOT NULL, start_y INTEGER NOT NULL, start_m INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS debt_paid(debt_id INTEGER NOT NULL, idx INTEGER NOT NULL, tx_id INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(debt_id,idx))")

            fun member(name: String, emoji: String, color: Long) {
                val v = ContentValues()
                v.put("name", name); v.put("emoji", emoji); v.put("color", color.toInt())
                db.insert("members", null, v)
            }
            member("حیدر", "🚕", 0xFF6C5CE7)
            member("اسماء", "🏠", 0xFFF06292)
            seedCats(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("DELETE FROM categories WHERE id NOT IN (SELECT DISTINCT cat_id FROM transactions)")
            }
            if (oldVersion < 3) {
                val c = db.rawQuery("SELECT COUNT(*) FROM categories", null)
                val empty = c.use { it.moveToFirst() && it.getInt(0) == 0 }
                if (empty) seedCats(db)
            }
            if (oldVersion < 4) {
                insertCatIfMissing(db, "ظروف مصنوعی", "🥣", 1)
            }
            if (oldVersion < 5) {
                db.execSQL("UPDATE categories SET emoji='🥣' WHERE name='ظروف مصنوعی'")
            }
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE categories ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            }
            if (oldVersion < 7) {
                db.execSQL("CREATE TABLE IF NOT EXISTS installments(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, amount INTEGER NOT NULL, day INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS inst_paid(inst_id INTEGER NOT NULL, y INTEGER NOT NULL, m INTEGER NOT NULL, PRIMARY KEY(inst_id,y,m))")
            }
            if (oldVersion < 8) {
                db.execSQL("ALTER TABLE installments ADD COLUMN start_y INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE installments ADD COLUMN start_m INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE installments ADD COLUMN months INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE inst_paid ADD COLUMN tx_id INTEGER NOT NULL DEFAULT 0")
                val j = U.jParts(System.currentTimeMillis())
                db.execSQL("UPDATE installments SET start_y=${j[0]}, start_m=${j[1]} WHERE start_y=0")
            }
            if (oldVersion < 10) {
                db.execSQL("ALTER TABLE installments ADD COLUMN type INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE installments ADD COLUMN total_amount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE installments ADD COLUMN paid_total INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE inst_paid ADD COLUMN paid_amount INTEGER NOT NULL DEFAULT 0")
            }
            if (oldVersion < 11) {
                db.execSQL("CREATE TABLE IF NOT EXISTS debts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, total INTEGER NOT NULL, monthly INTEGER NOT NULL, start_y INTEGER NOT NULL, start_m INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS debt_paid(debt_id INTEGER NOT NULL, idx INTEGER NOT NULL, tx_id INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(debt_id,idx))")
            }
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
        return db().insert("categories", null, v)
    }

    fun setCategoryOrder(ids: List<Long>) {
        val cv = ContentValues()
        for (i in ids.indices) {
            cv.clear()
            cv.put("sort_order", i)
            db().update("categories", cv, "id=?", arrayOf(ids[i].toString()))
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
        return db().insert("installments", null, v)
    }

    fun updateInstallment(id: Long, name: String, amount: Long, day: Int, months: Int) {
        val v = ContentValues()
        v.put("name", name); v.put("amount", amount); v.put("day", day); v.put("months", months)
        db().update("installments", v, "id=?", arrayOf(id.toString()))
    }

    fun deleteInstallment(id: Long) {
        db().delete("inst_paid", "inst_id=?", arrayOf(id.toString()))
        db().delete("installments", "id=?", arrayOf(id.toString()))
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
        return db().insert("debts", null, v)
    }

    fun updateDebt(id: Long, name: String, total: Long, monthly: Long) {
        val v = ContentValues()
        v.put("name", name); v.put("total", total); v.put("monthly", monthly)
        db().update("debts", v, "id=?", arrayOf(id.toString()))
    }

    /** حذف بدهی + سوابق پرداخت + تراکنش‌های مرتبط */
    fun deleteDebt(id: Long) {
        val c = db().rawQuery("SELECT tx_id FROM debt_paid WHERE debt_id=? AND tx_id>0", arrayOf(id.toString()))
        val txIds = mutableListOf<Long>()
        c.use { while (it.moveToNext()) txIds.add(it.getLong(0)) }
        for (t in txIds) db().delete("transactions", "id=?", arrayOf(t.toString()))
        db().delete("debt_paid", "debt_id=?", arrayOf(id.toString()))
        db().delete("debts", "id=?", arrayOf(id.toString()))
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
        } else {
            db().delete("debt_paid", "debt_id=? AND idx=?", arrayOf(debtId.toString(), idx.toString()))
        }
    }

    fun paidInst(y: Int, m: Int): Set<Long> = paidTx(y, m).keys

    fun setInstPaid(instId: Long, y: Int, m: Int, paid: Boolean, txId: Long = 0L, paidAmount: Long = 0L) {
        if (paid) {
            val v = ContentValues()
            v.put("inst_id", instId); v.put("y", y); v.put("m", m)
            v.put("tx_id", txId); v.put("paid_amount", paidAmount)
            db().insertWithOnConflict("inst_paid", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db().delete(
                "inst_paid", "inst_id=? AND y=? AND m=?",
                arrayOf(instId.toString(), y.toString(), m.toString())
            )
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
    }

    /** لغو پرداخت وام یا اقساطی در این ماه */
    fun cancelLoanPayment(instId: Long, y: Int, m: Int) {
        db().delete(
            "inst_paid", "inst_id=? AND y=? AND m=? AND paid_amount>0",
            arrayOf(instId.toString(), y.toString(), m.toString())
        )
    }

    fun deleteCategory(id: Long) {
        db().delete("categories", "id=?", arrayOf(id.toString()))
    }

    fun updateCategory(id: Long, name: String, emoji: String, type: Int) {
        val v = ContentValues()
        v.put("name", name); v.put("emoji", emoji); v.put("type", type)
        db().update("categories", v, "id=?", arrayOf(id.toString()))
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
        return db().insert("members", null, v)
    }

    fun deleteMember(id: Long) {
        db().delete("transactions", "member_id=?", arrayOf(id.toString()))
        db().delete("members", "id=?", arrayOf(id.toString()))
    }

    fun updateMember(id: Long, name: String, emoji: String, color: Int) {
        val v = ContentValues()
        v.put("name", name); v.put("emoji", emoji); v.put("color", color)
        db().update("members", v, "id=?", arrayOf(id.toString()))
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
        return db().insert("transactions", null, v)
    }

    fun allTrans(): List<Trans> {
        val out = ArrayList<Trans>()
        val c = db().rawQuery("SELECT id,member_id,cat_id,type,amount,note,ts,is_work FROM transactions ORDER BY ts DESC,id DESC", null)
        c.use {
            while (it.moveToNext()) out.add(
                Trans(it.getLong(0), it.getLong(1), it.getLong(2), it.getInt(3),
                    it.getLong(4), it.getString(5) ?: "", it.getLong(6), it.getInt(7) == 1)
            )
        }
        return out
    }

    fun deleteTrans(id: Long) {
        // اگه این تراکنش مال پرداخت یک قسط بدهی/اقساط بود، وضعیتش به «پرداخت‌نشده» برمی‌گرده
        db().delete("debt_paid", "tx_id=?", arrayOf(id.toString()))
        db().delete("inst_paid", "tx_id=?", arrayOf(id.toString()))
        db().delete("transactions", "id=?", arrayOf(id.toString()))
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
    }

    fun backupTo(out: java.io.OutputStream) {
        val db = db()
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } catch (_: Exception) {}
        val f = java.io.File(db.path)
        f.inputStream().use { it.copyTo(out) }
    }

    fun restoreFrom(data: ByteArray): Boolean {
        if (data.size < 16) return false
        val header = String(data, 0, 15, Charsets.US_ASCII)
        if (header != "SQLite format 3") return false
        val path = db().path
        helper?.close()
        helper = null
        java.io.File(path).writeBytes(data)
        java.io.File("$path-wal").delete()
        java.io.File("$path-shm").delete()
        java.io.File("$path-journal").delete()
        val c = appCtx ?: return false
        init(c)
        return true
    }
}
