package com.gamewatchdog

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Giám sát nhiều app cùng lúc (kể cả app nhân bản / clone).
 * App nào không còn hiện trên màn hình quá N giây (thoát hoặc văng) thì được mở lại,
 * chờ tải, rồi chạy macro riêng của app đó (lặp theo số lần đã cài).
 *
 * Nhận biết app đang hiển thị bằng 3 tín hiệu (chỉ cần một trong ba):
 *  1. app đó có cửa sổ đọc được trong danh sách cửa sổ,
 *  2. app đó là cửa sổ vừa hiện lên gần nhất (bỏ qua hộp thoại cấp quyền, thanh hệ thống, bàn phím),
 *  3. (tuỳ chọn, cần quyền "Truy cập sử dụng") hệ thống báo activity của app đang hiển thị.
 *
 * Mỗi lúc chỉ xử lý một app: mở lại → xác nhận đã thấy → chờ tải → chạy macro, rồi mới tới app khác.
 */
class WatchdogService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WatchdogService? = null

        // Cửa sổ thoáng qua của hệ thống: không được coi là "app trên cùng"
        private val IGNORED = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller"
        )
        private const val LOOP_WINDOW_MS = 5 * 60_000L // khoảng thời gian đếm số lần mở lại
        private const val LOOP_MAX = 6                  // app văng liên tục quá số lần này trong khoảng trên thì dừng
        private const val FAIL_MAX = 3                  // mở lại liên tiếp mà không nhận ra app → dừng
        private const val RETRY_AFTER_MS = 10_000L      // chưa thấy app sau chừng này thì thử mở lại
        private const val MAX_TRIES = 3                 // số lần mở tối đa cho mỗi lượt
    }

    private class Launch(val pkg: String, val token: Int, val startedAt: Long) {
        var tries = 1
        var lastAttemptAt = startedAt
    }

    private val handler = Handler(Looper.getMainLooper())

    private var topPkg = ""                              // app có cửa sổ mới nhất (theo sự kiện)
    private var visNow: Set<String> = emptySet()         // package có cửa sổ đọc được (mỗi nhịp)
    private var usageFgNow: Set<String> = emptySet()     // package hệ thống báo đang hiển thị (mỗi nhịp)
    private var homePkgs: Set<String> = emptySet()       // các launcher (màn hình chính)
    private val awaySince = HashMap<String, Long>()      // app → thời điểm bắt đầu vắng mặt
    private val cooldownUntil = HashMap<String, Long>()  // app → không mở lại trước thời điểm này
    private val recoverTimes = HashMap<String, ArrayList<Long>>() // app → các lần tự mở lại gần đây
    private val busyApps = HashSet<String>()             // app đang mở lại / chờ tải / chạy macro
    private val tokens = HashMap<String, Int>()          // app → mã lượt chạy (để huỷ)
    private val sawPresent = HashSet<String>()           // app đã được nhận ra sau lần mở lại gần nhất
    private val autoRuns = HashSet<String>()             // lượt chạy do tự động mở lại (để tính lỗi)
    private val failCount = HashMap<String, Int>()       // app → số lần mở lại liên tiếp không nhận ra
    private var launching: Launch? = null
    private var tokenCounter = 0
    private val macroQueue = ArrayDeque<Pair<String, Int>>()
    private var macroRunning = false

    private var imePkgs: Set<String> = emptySet()
    private var floating: FloatingUi? = null
    private var usage: UsageTracker? = null

    private val ticker = object : Runnable {
        override fun run() {
            try {
                watch()
                floating?.refresh()
            } catch (e: Exception) {
                Prefs.log(this@WatchdogService, "Lỗi: ${e.message}")
            }
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshIme()
        refreshHome()
        usage = UsageTracker(this)
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        Prefs.log(this, "Dịch vụ trợ năng đã kết nối (bản $ver)")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(ticker, 2000L)

        val ui = FloatingUi(this)
        floating = ui
        if (Prefs.isOverlay(this)) ui.show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Bỏ qua thanh thông báo, hộp thoại cấp quyền và bàn phím để không nhầm là app đã thoát
        if (pkg in IGNORED || pkg in imePkgs) return
        // Bỏ qua nút nổi của chính app này (chỉ tính màn hình MainActivity)
        if (pkg == packageName && event.className?.toString()?.endsWith("MainActivity") != true) return
        topPkg = pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        floating?.hide()
        floating = null
        usage = null
        awaySince.clear()
        cooldownUntil.clear()
        recoverTimes.clear()
        busyApps.clear()
        tokens.clear()
        sawPresent.clear()
        autoRuns.clear()
        failCount.clear()
        launching = null
        macroQueue.clear()
        macroRunning = false
        if (instance === this) instance = null
    }

    private fun refreshIme() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imePkgs = imm.enabledInputMethodList.map { it.packageName }.toSet()
        } catch (e: Exception) {
            // bỏ qua
        }
    }

    private fun refreshHome() {
        try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            homePkgs = packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
        } catch (e: Exception) {
            // bỏ qua
        }
    }

    // ---------------- API cho giao diện ----------------

    fun setOverlayVisible(visible: Boolean) {
        if (visible) floating?.show() else floating?.hide()
    }

    fun setWatchdogOn(on: Boolean) {
        Prefs.setEnabled(this, on)
        floating?.refresh()
    }

    fun isBusy(pkg: String): Boolean = pkg in busyApps

    fun hasUsageAccess(): Boolean = usage?.hasPermission() == true

    /** "Thử ngay": mở lại app rồi chạy macro của app đó. Trả về false nếu app đang bận. */
    fun runNow(pkg: String): Boolean {
        if (pkg in busyApps) return false
        val app = Prefs.getApp(this, pkg) ?: return false
        failCount.remove(pkg)
        recover(app, false, "thủ công")
        return true
    }

    /** Chỉ chạy macro của app trên màn hình hiện tại (không mở lại app). */
    fun playMacro(pkg: String): Boolean {
        if (pkg in busyApps) return false
        if (Prefs.getApp(this, pkg) == null) return false
        val token = ++tokenCounter
        tokens[pkg] = token
        busyApps.add(pkg)
        enqueueMacro(pkg, token)
        return true
    }

    /** Dừng macro đang chạy / đang chờ của app. */
    fun stopMacro(pkg: String) {
        tokens.remove(pkg)
        autoRuns.remove(pkg)
        if (launching?.pkg == pkg) launching = null
        if (busyApps.remove(pkg)) Prefs.log(this, "${appLabel(pkg)}: đã dừng macro")
    }

    /** Ghi vào nhật ký danh sách cửa sổ và tín hiệu hiện tại để biết vì sao app được/không được nhận ra. */
    @Suppress("DEPRECATION")
    fun dumpDiagnostics() {
        refreshSignals()
        val u = usage
        val on = u != null && u.hasPermission()
        Prefs.log(
            this,
            "== Chẩn đoán == trên cùng: $topPkg | quyền \"Truy cập sử dụng\": ${if (on) "BẬT" else "TẮT"} | launcher: $homePkgs"
        )
        if (on) Prefs.log(this, "  hệ thống báo đang hiển thị: ${u?.foregroundPackages()}")
        try {
            val list = windows
            Prefs.log(this, "  số cửa sổ đọc được: ${list.size}")
            for (w in list) {
                val r = w.root
                val b = Rect()
                w.getBoundsInScreen(b)
                Prefs.log(
                    this,
                    "  type=${w.type} layer=${w.layer} active=${w.isActive} focused=${w.isFocused} " +
                        "pkg=${r?.packageName ?: "(root=null)"} title=${w.title ?: ""} bounds=${b.flattenToString()}"
                )
                r?.recycle()
            }
        } catch (e: Exception) {
            Prefs.log(this, "  lỗi đọc cửa sổ: ${e.message}")
        }
        for (a in Prefs.getApps(this)) {
            Prefs.log(this, "  ${a.label} [${a.pkg}]: ${if (isPresent(a.pkg)) "THẤY" else "không thấy"}")
        }
    }

    // ---------------- Giám sát ----------------

    /** Các package đang có cửa sổ hiển thị (đọc từ danh sách cửa sổ, hỗ trợ nhiều cửa sổ cùng lúc). */
    @Suppress("DEPRECATION")
    private fun visiblePackages(): Set<String> {
        val out = HashSet<String>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                val p = r.packageName?.toString()
                if (p != null) out.add(p)
                r.recycle()
            }
        } catch (e: Exception) {
            // bỏ qua
        }
        return out
    }

    private fun refreshSignals() {
        visNow = visiblePackages()
        val u = usage
        if (u != null && u.hasPermission()) {
            u.update()
            // Đang ở màn hình chính (launcher) thì không tin "đang hiển thị" của hệ thống vì có thể đã cũ
            val homeShown = homePkgs.any { it == topPkg || it in visNow }
            usageFgNow = if (homeShown) emptySet() else u.foregroundPackages()
        } else {
            usageFgNow = emptySet()
        }
    }

    private fun isPresent(pkg: String): Boolean =
        pkg == topPkg || pkg in visNow || pkg in usageFgNow

    private fun watch() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = pm.isInteractive
        refreshSignals()
        verifyLaunch()

        if (!Prefs.isEnabled(this) || floating?.recording == true || !screenOn) {
            awaySince.clear()
            return
        }
        if (topPkg == packageName) { // đang mở màn hình cài đặt của chính app này
            awaySince.clear()
            return
        }

        val apps = Prefs.getApps(this).filter { it.enabled }
        if (apps.isEmpty()) return

        val now = System.currentTimeMillis()
        val grace = Prefs.getGraceSec(this) * 1000L
        val seen = visNow.filter { it !in IGNORED && it != packageName }.joinToString()
        val usageNote = if (usage?.hasPermission() == true) ", hệ thống báo: $usageFgNow" else ""

        for (a in apps) {
            val present = isPresent(a.pkg)
            if (a.pkg in busyApps) {
                if (present) sawPresent.add(a.pkg)
                awaySince.remove(a.pkg)
                continue
            }
            if (present) {
                awaySince.remove(a.pkg)
                continue
            }
            // Mỗi lúc chỉ xử lý một app, tránh hai app mở chồng lên nhau
            if (busyApps.isNotEmpty() || launching != null) continue

            val since = awaySince.getOrPut(a.pkg) { now }
            if (now - since >= grace && now >= (cooldownUntil[a.pkg] ?: 0L)) {
                awaySince.remove(a.pkg)
                recover(a, true, "cửa sổ thấy: [$seen], trên cùng: $topPkg$usageNote")
            }
        }
    }

    /** Sau khi mở: xác nhận đã thấy app; chưa thấy thì thử mở lại (tối đa MAX_TRIES lần). */
    private fun verifyLaunch() {
        val l = launching ?: return
        if (tokens[l.pkg] != l.token) {
            launching = null
            return
        }
        val now = System.currentTimeMillis()
        if (isPresent(l.pkg)) {
            sawPresent.add(l.pkg)
            launching = null
            Prefs.log(this, "${appLabel(l.pkg)}: đã thấy app trên màn hình sau ${(now - l.startedAt) / 1000}s")
        } else if (now - l.lastAttemptAt >= RETRY_AFTER_MS && l.tries < MAX_TRIES) {
            val err = launchApp(l.pkg, l.tries + 1)
            l.tries++
            l.lastAttemptAt = now
            Prefs.log(
                this,
                "${appLabel(l.pkg)}: chưa thấy app sau ${(now - l.startedAt) / 1000}s → thử mở lại lần ${l.tries}" +
                    (if (err != null) " (lỗi: $err)" else "")
            )
        }
    }

    private fun disableApp(a: AppConfig, msg: String) {
        Prefs.log(this, "${a.label}: $msg")
        Prefs.updateApp(this, a.pkg) { it.copy(enabled = false) }
    }

    private fun recover(a: AppConfig, auto: Boolean, reason: String) {
        val now = System.currentTimeMillis()
        if (auto) {
            if ((failCount[a.pkg] ?: 0) >= FAIL_MAX) {
                failCount.remove(a.pkg)
                disableApp(
                    a,
                    "đã mở lại $FAIL_MAX lần liên tiếp mà không nhận ra app trên màn hình → tắt giám sát app này. " +
                        "Hãy cấp quyền \"Truy cập sử dụng\" và bấm \"Chẩn đoán\" để xem. $reason"
                )
                return
            }
            // App văng liên tục (đã nhận ra nhưng lại thoát ngay) thì cũng dừng để khỏi mở mãi
            val list = recoverTimes.getOrPut(a.pkg) { ArrayList() }
            list.removeAll { now - it > LOOP_WINDOW_MS }
            if (list.size >= LOOP_MAX) {
                list.clear()
                disableApp(a, "đã mở lại $LOOP_MAX lần trong 5 phút (app văng liên tục?) → tắt giám sát app này")
                return
            }
            list.add(now)
        }

        val token = ++tokenCounter
        tokens[a.pkg] = token
        busyApps.add(a.pkg)
        sawPresent.remove(a.pkg)
        if (auto) autoRuns.add(a.pkg) else autoRuns.remove(a.pkg)
        Prefs.log(this, "${a.label}: mở lại ($reason)")

        var err = launchApp(a.pkg, 1)
        if (err != null) err = launchApp(a.pkg, 2) // thử cách mở dự phòng ngay
        if (err != null) {
            busyApps.remove(a.pkg)
            tokens.remove(a.pkg)
            autoRuns.remove(a.pkg)
            if (err.startsWith("không tìm thấy")) {
                disableApp(a, "$err. Tắt giám sát app này.")
            } else {
                Prefs.log(this, "${a.label}: không mở được ($err). Sẽ thử lại sau 60 giây.")
                cooldownUntil[a.pkg] = now + 60_000L
            }
            return
        }

        launching = Launch(a.pkg, token, now)
        val wait = a.startupSec * 1000L
        cooldownUntil[a.pkg] = now + wait + 30000L
        Prefs.log(this, "${a.label}: chờ tải ${a.startupSec}s rồi chạy macro")
        handler.postDelayed({
            if (tokens[a.pkg] == token) {
                val l = launching
                if (l != null && l.token == token) {
                    launching = null
                    Prefs.log(this, "${a.label}: chưa nhận ra app trên màn hình sau ${a.startupSec}s, vẫn chạy macro")
                }
                enqueueMacro(a.pkg, token)
            }
        }, wait)
    }

    /** Tìm activity khởi động của chính package (bản nhân bản đôi khi không có intent mặc định). */
    private fun explicitLaunchIntent(pkg: String): Intent? {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val ai = packageManager.queryIntentActivities(q, 0).firstOrNull()?.activityInfo ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(ai.packageName, ai.name))
    }

    private fun buildLaunchIntent(pkg: String, attempt: Int): Intent? {
        return if (attempt <= 1) {
            packageManager.getLaunchIntentForPackage(pkg) ?: explicitLaunchIntent(pkg)
        } else {
            explicitLaunchIntent(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        }
    }

    /** Mở app. Trả về null nếu thành công, hoặc mô tả lỗi. Không bao giờ ném lỗi ra ngoài. */
    private fun launchApp(pkg: String, attempt: Int): String? {
        return try {
            val intent = buildLaunchIntent(pkg, attempt)
            if (intent == null) {
                "không tìm thấy màn hình khởi động của $pkg"
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(intent)
                null
            }
        } catch (e: Exception) {
            (e.javaClass.simpleName + ": " + (e.message ?: "")).take(120)
        }
    }

    // ---------------- Hàng đợi và chạy macro ----------------

    private fun enqueueMacro(pkg: String, token: Int) {
        macroQueue.addLast(Pair(pkg, token))
        pump()
    }

    /** Mỗi lúc chỉ chạy một macro để các cử chỉ không chồng lên nhau. */
    private fun pump() {
        if (macroRunning) return
        val job = macroQueue.removeFirstOrNull() ?: return
        if (tokens[job.first] != job.second) { // đã bị huỷ
            pump()
            return
        }
        macroRunning = true
        runMacro(job.first, job.second)
    }

    private fun appLabel(pkg: String): String = Prefs.getApp(this, pkg)?.label ?: pkg

    private fun runMacro(pkg: String, token: Int) {
        val app = Prefs.getApp(this, pkg)
        val steps = if (app == null) emptyList() else Prefs.parseMacro(app.macro)
        if (steps.isEmpty()) {
            finishRun(pkg, "chưa có macro (hãy ghi bằng nút ⏺)")
            return
        }
        val rounds = (app?.repeat ?: 1).coerceIn(1, 9999)
        val gapMs = (app?.repeatGapSec ?: 0).coerceAtLeast(0) * 1000L
        Prefs.log(this, "${appLabel(pkg)}: bắt đầu macro ${steps.size} bước × $rounds lần")
        doStep(pkg, steps, 0, token, 1, rounds, gapMs)
    }

    private fun doStep(
        pkg: String, steps: List<MacroStep>, index: Int, token: Int,
        round: Int, rounds: Int, gapMs: Long
    ) {
        if (tokens[pkg] != token) { // đã bị dừng
            macroRunning = false
            pump()
            return
        }
        if (index >= steps.size) {
            if (round < rounds) {
                handler.postDelayed({
                    doStep(pkg, steps, 0, token, round + 1, rounds, gapMs)
                }, gapMs)
            } else {
                finishRun(pkg, "xong macro ($rounds lần)")
            }
            return
        }
        val s = steps[index]
        handler.postDelayed({
            if (tokens[pkg] == token) {
                dispatchStep(s) { doStep(pkg, steps, index + 1, token, round, rounds, gapMs) }
            } else {
                macroRunning = false
                pump()
            }
        }, s.delayMs)
    }

    private fun finishRun(pkg: String, msg: String) {
        Prefs.log(this, "${appLabel(pkg)}: $msg")
        if (autoRuns.remove(pkg)) {
            if (pkg in sawPresent) {
                failCount.remove(pkg)
            } else {
                val n = (failCount[pkg] ?: 0) + 1
                failCount[pkg] = n
                Prefs.log(this, "${appLabel(pkg)}: lần này không nhận ra app trên màn hình ($n/$FAIL_MAX)")
            }
        }
        busyApps.remove(pkg)
        tokens.remove(pkg)
        if (launching?.pkg == pkg) launching = null
        // Sau khi macro xong, cho app thêm thời gian ổn định và tính lại thời gian vắng mặt của mọi app
        cooldownUntil[pkg] = maxOf(cooldownUntil[pkg] ?: 0L, System.currentTimeMillis() + 20000L)
        awaySince.clear()
        macroRunning = false
        pump()
    }

    private fun dispatchStep(s: MacroStep, done: () -> Unit) {
        val size = screenSize()
        val w = size.first
        val h = size.second
        // Phòng khi hệ thống không báo kết quả cử chỉ: không để macro kẹt mãi
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                done()
            }
        }
        handler.postDelayed({
            if (!finished) {
                Prefs.log(this, "Cử chỉ không phản hồi sau ${(s.durMs + 4000L) / 1000}s → bỏ qua bước này")
                finish()
            }
        }, s.durMs + 4000L)
        dispatchPx(
            w * s.x1 / 100f, h * s.y1 / 100f,
            w * s.x2 / 100f, h * s.y2 / 100f,
            s.durMs, s.kind == KIND_SWIPE, finish
        )
    }

    /** Chạm hoặc vuốt theo toạ độ pixel. done() được gọi khi cử chỉ xong hoặc bị huỷ. */
    fun dispatchPx(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durMs: Long, swipe: Boolean, done: () -> Unit
    ) {
        val path = Path()
        path.moveTo(x1, y1)
        if (swipe) path.lineTo(x2, y2)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durMs.coerceAtLeast(1L)))
            .build()
        val dispatched = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    done()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done()
                }
            },
            null
        )
        if (!dispatched) done()
    }

    @Suppress("DEPRECATION")
    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }
}
