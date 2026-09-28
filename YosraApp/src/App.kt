package ir.yosra.app

import android.app.Application

class YosraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Db.init(this)
        U.loadFont(this)
    }
}
