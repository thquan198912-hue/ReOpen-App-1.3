package com.reopen.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var waitEdit: EditText
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { logView.text = Store.logs(this@MainActivity); h.postDelayed(this, 2000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun btn(t: String, a: () -> Unit) = root.addView(Button(this).apply { text = t; setOnClickListener { a() } })
        btn("1. Bật Trợ năng cho ReOpen App 2") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("2. Cho phép hiển thị trên ứng dụng khác") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("3. Tắt tối ưu pin") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        root.addView(TextView(this).apply { text = "Thời gian chờ app tải (giây) trước khi chạy macro:" })
        waitEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(Store.wait(this@MainActivity).toString())
        }
        root.addView(waitEdit)
        root.addView(TextView(this).apply { text = "\nChọn app cần giám sát:"; textSize = 16f })

        val pm = packageManager
        val list = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        val on = Store.apps(this)
        for (ri in list) {
            val pkg = ri.activityInfo.packageName
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(CheckBox(this).apply {
                text = "${ri.loadLabel(pm)}\n$pkg"; textSize = 13f; isChecked = pkg in on
                setOnCheckedChangeListener { _, c -> Store.setApp(this@MainActivity, pkg, c) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply { text = "Macro"; setOnClickListener { macroDialog(pkg) } })
            root.addView(row)
        }
        root.addView(TextView(this).apply { text = "\nNhật ký:"; textSize = 16f })
        logView = TextView(this).apply { textSize = 12f }
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun macroDialog(pkg: String) {
        val et = EditText(this).apply {
            setText(Store.macro(this@MainActivity, pkg)); minLines = 6; gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this).setTitle("Macro: $pkg")
            .setMessage("Mỗi dòng 1 lần chạm: x,y,chờ_ms\nVí dụ: 540,1200,1500")
            .setView(et)
            .setPositiveButton("Lưu") { _, _ -> Store.setMacro(this, pkg, et.text.toString()) }
            .setNegativeButton("Huỷ", null).show()
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() {
        super.onPause(); h.removeCallbacks(tick)
        waitEdit.text.toString().toIntOrNull()?.let { Store.setWait(this, it.coerceIn(1, 300)) }
    }
}
