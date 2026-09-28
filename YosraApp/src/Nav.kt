package ir.yosra.app

import android.app.Activity
import android.content.Intent
import android.graphics.PorterDuff
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object Nav {

    fun bind(a: Activity, index: Int) {
        U.loadFont(a)
        U.rtl(a)
        U.applyFont(a.findViewById(android.R.id.content))

        val purple = 0xFF6C5CE7.toInt()
        val gray = 0xFF9AA0BC.toInt()

        val boxes = intArrayOf(R.id.nav_home, R.id.nav_trans, R.id.nav_reports, R.id.nav_settings)
        val icons = intArrayOf(R.id.nav_home_ic, R.id.nav_trans_ic, R.id.nav_reports_ic, R.id.nav_settings_ic)
        val labels = intArrayOf(R.id.nav_home_lbl, R.id.nav_trans_lbl, R.id.nav_reports_lbl, R.id.nav_settings_lbl)
        val targets = arrayOf(
            HomeActivity::class.java, TransactionsActivity::class.java,
            ReportsActivity::class.java, SettingsActivity::class.java
        )

        for (i in boxes.indices) {
            val box = a.findViewById<LinearLayout>(boxes[i])
            val ic = a.findViewById<ImageView>(icons[i])
            val lb = a.findViewById<TextView>(labels[i])
            if (i == index) {
                ic.setColorFilter(purple, PorterDuff.Mode.SRC_IN)
                lb.setTextColor(purple)
                val f = U.fBold ?: U.fReg
                if (f != null) lb.typeface = f
            } else {
                ic.setColorFilter(gray, PorterDuff.Mode.SRC_IN)
                lb.setTextColor(gray)
                box.setOnClickListener { v ->
                    val it = Intent(v.context, targets[i])
                    it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    v.context.startActivity(it)
                    (v.context as? Activity)?.overridePendingTransition(0, 0)
                }
            }
        }
    }
}
