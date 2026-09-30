package ir.yosra.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView

/**
 * صفحهٔ ورود — فقط در اولین اجرا (وقتی توکن هنوز باز نشده) نمایش داده می‌شود.
 * رمز درست → توکن باز و ذخیره می‌شود و دیگر این صفحه نمی‌آید.
 */
class LoginActivity : BaseActivity() {

    // خودش syncOnStart را می‌زند؛ دریافت اضافی در onResume لازم نیست
    override fun allowBgSync(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val pw = findViewById<EditText>(R.id.login_pw)
        val btn = findViewById<TextView>(R.id.btn_login)

        // هر بار تایپ، حالت خطا رو پاک می‌کنه
        pw.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btn.text = "ورود"
            }
        })

        // Enter کیبورد = دکمه ورود
        pw.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                btn.performClick()
                true
            } else false
        }

        btn.setOnClickListener {
            val text = pw.text.toString()
            if (text.isEmpty()) {
                U.toast(this, "رمز رو وارد کن")
                return@setOnClickListener
            }
            btn.text = "در حال بررسی..."
            btn.isEnabled = false
            // باز کردن توکن با PBKDF2 سنگین است → در پس‌زمینه
            Thread {
                val ok = Sync.unlockWithPassword(applicationContext, text)
                runOnUiThread {
                    btn.isEnabled = true
                    if (ok) {
                        btn.text = "در حال همگام‌سازی..."
                        Thread {
                            // اولین ورود: داده‌های آنلاین گرفته می‌شود (اگر هست)
                            try {
                                Sync.markPreHome()
                                Sync.syncOnStart(applicationContext)
                            } catch (_: Exception) {
                            }
                            runOnUiThread {
                                U.toast(applicationContext, "خوش اومدی 🎉")
                                startActivity(Intent(this, HomeActivity::class.java))
                                finish()
                                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                            }
                        }.start()
                    } else {
                        btn.text = "ورود"
                        pw.setText("")
                        U.toast(this, "رمز اشتباه است ❌", true)
                        pw.requestFocus()
                    }
                }
            }.start()
        }

        // فیلد در ابتدا فوکوس داشته باشه
        pw.requestFocus()
    }
}
