package com.reopen.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object Store {
    private fun p(c: Context) = c.getSharedPreferences("p", 0)
    fun apps(c: Context): Set<String> = p(c).getStringSet("apps", emptySet())!!.toSet()
    fun setApp(c: Context, pkg: String, on: Boolean) {
        val s = apps(c).toMutableSet(); if (on) s.add(pkg) else s.remove(pkg)
        p(c).edit().putStringSet("apps", s).apply()
    }
    fun macro(c: Context, pkg: String) = p(c).getString("m_$pkg", "")!!
    fun setMacro(c: Context, pkg: String, v: String) = p(c).edit().putString("m_$pkg", v).apply()
    fun wait(c: Context) = p(c).getInt("wait", 25)
    fun setWait(c: Context, v: Int) = p(c).edit().putInt("wait", v).apply()
    fun log(c: Context, msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val l = logs(c).lines().filter { it.isNotBlank() }.toMutableList()
        l.add(0, "$t  $msg")
        p(c).edit().putString("log", l.take(150).joinToString("\n")).apply()
    }
    fun logs(c: Context) = p(c).getString("log", "")!!
}
