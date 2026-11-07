package com.gamewatchdog

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Nguồn tín hiệu thứ hai để biết app nào đang hiển thị, không phụ thuộc việc đọc nội dung cửa sổ.
 * Cần người dùng cấp quyền "Truy cập sử dụng" (Cài đặt → Quyền truy cập đặc biệt).
 * Hữu ích với game có cửa sổ không đọc được và với app nhân bản (clone).
 */
class UsageTracker(private val ctx: Context) {

    private val active = HashMap<String, Boolean>() // "package/activity" → đang hiển thị
    private var lastQuery = 0L

    @Suppress("DEPRECATION")
    fun hasPermission(): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            } else {
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /** Đọc các sự kiện mới kể từ lần trước và cập nhật trạng thái từng activity. */
    fun update() {
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            if (now < lastQuery) lastQuery = 0L // đồng hồ máy bị chỉnh lùi
            val from = if (lastQuery == 0L) now - 6 * 3600_000L else lastQuery - 2000L
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName ?: continue
                val key = pkg + "/" + (e.className ?: "")
                when (e.eventType) {
                    1 -> active[key] = true            // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
                    23, 24 -> active[key] = false      // ACTIVITY_STOPPED / ACTIVITY_DESTROYED
                    2 -> if (Build.VERSION.SDK_INT < 29) active[key] = false // MOVE_TO_BACKGROUND (máy cũ)
                }
            }
            lastQuery = now
        } catch (ex: Exception) {
            // bỏ qua, lần sau đọc lại
        }
    }

    fun foregroundPackages(): Set<String> {
        val out = HashSet<String>()
        for ((k, v) in active) {
            if (v) out.add(k.substringBefore('/'))
        }
        return out
    }
}
