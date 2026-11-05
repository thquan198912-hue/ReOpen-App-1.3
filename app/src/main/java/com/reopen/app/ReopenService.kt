package com.reopen.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

class ReopenService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private val missingSince = HashMap<String, Long>()
    private val lastLaunch = HashMap<String, Long>()
    private val nextAllowed = HashMap<String, Long>()
    private val attempts = HashMap<String, Int>()

    // Cửa sổ hệ thống tạm thời: KHÔNG được coi là "app đã bị đóng"
    private val ignored = hashSetOf(
        "com.android.systemui", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "android", "com.android.settings",
        "com.google.android.gms"
    )

    private val loop = object : Runnable {
        override fun run() {
            try { check() } catch (e: Exception) { Store.log(this@ReopenService, "lỗi: ${e.message}") }
            h.postDelayed(this, 3000)
        }
    }

    override fun onServiceConnected() {
        ignored.add(packageName)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList
            .forEach { ignored.add(it.packageName) }
        Store.log(this, "dịch vụ giám sát đã bật")
        h.post(loop)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun visiblePackages(): Set<String> {
        val s = HashSet<String>()
        windows.forEach { w -> w.root?.packageName?.let { s.add(it.toString()) } }
        rootInActiveWindow?.packageName?.let { s.add(it.toString()) }
        return s
    }

    private fun check() {
        if (!(getSystemService(POWER_SERVICE) as PowerManager).isInteractive) return
        val apps = Store.apps(this)
        if (apps.isEmpty()) return
        val vis = visiblePackages()
        // Đang bị cửa sổ hệ thống/IME che hoàn toàn -> chưa kết luận gì
        if (vis.isEmpty() || vis.all { it in ignored }) return
        val now = System.currentTimeMillis()
        for (pkg in apps) {
            if (pkg in vis) {
                missingSince.remove(pkg)
                // chạy ổn định > 2 phút thì xoá bộ đếm lỗi
                if (now - (lastLaunch[pkg] ?: 0L) > 120_000) attempts[pkg] = 0
                continue
            }
            val first = missingSince.getOrPut(pkg) { now }
            if (now - first < 8000) continue                         // chờ 8s tránh báo nhầm
            if (now < (nextAllowed[pkg] ?: 0L)) continue             // đang backoff / đang chờ tải
            reopen(pkg, now)
        }
    }

    private fun reopen(pkg: String, now: Long) {
        val n = (attempts[pkg] ?: 0) + 1
        attempts[pkg] = n
        val waitMs = Store.wait(this) * 1000L
        // KHÔNG tự tắt giám sát: mỗi lần lỗi liên tiếp thì chờ lâu hơn (tối đa 5 phút)
        nextAllowed[pkg] = now + waitMs + minOf(15_000L * (n - 1), 300_000L) + 10_000
        lastLaunch[pkg] = now
        missingSince.remove(pkg)
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) { Store.log(this, "$pkg: không tìm thấy activity để mở"); return }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
        Store.log(this, "$pkg: mở lại (lần liên tiếp $n), chờ tải ${waitMs / 1000}s rồi chạy macro")
        h.postDelayed({ runMacro(pkg) }, waitMs)
    }

    private fun runMacro(pkg: String) {
        val steps = Store.macro(this, pkg).lines().mapNotNull {
            val p = it.trim().split(",").map { s -> s.trim() }
            if (p.size >= 2 && p[0].toFloatOrNull() != null && p[1].toFloatOrNull() != null)
                Triple(p[0].toFloat(), p[1].toFloat(), p.getOrNull(2)?.toLongOrNull() ?: 800L)
            else null
        }
        if (steps.isEmpty()) return
        if (pkg !in visiblePackages()) { Store.log(this, "$pkg: bỏ qua macro (app chưa hiện)"); return }
        Store.log(this, "$pkg: bắt đầu macro ${steps.size} bước")
        tap(steps, 0, pkg)
    }

    private fun tap(steps: List<Triple<Float, Float, Long>>, i: Int, pkg: String) {
        if (i >= steps.size) { Store.log(this, "$pkg: xong macro"); return }
        val (x, y, d) = steps[i]
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        dispatchGesture(g, null, null)
        h.postDelayed({ tap(steps, i + 1, pkg) }, d)
    }
}
