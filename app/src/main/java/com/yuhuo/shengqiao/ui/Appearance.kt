package com.yuhuo.shengqiao.ui

import android.app.UiModeManager
import android.content.Context

object Appearance {
    val labels = arrayOf("跟随系统", "深色", "浅色")
    private val modes = intArrayOf(UiModeManager.MODE_NIGHT_AUTO, UiModeManager.MODE_NIGHT_YES, UiModeManager.MODE_NIGHT_NO)
    fun selected(context: Context): Int = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        .getInt("mode", 0).coerceIn(0, 2)
    fun apply(context: Context) {
        // Application night mode persists at system level and updates native controls/dialogs too.
        // MODE_NIGHT_AUTO here clears the application's override and follows the system.
        context.getSystemService(UiModeManager::class.java).setApplicationNightMode(modes[selected(context)])
    }
    fun select(context: Context, index: Int) {
        require(index in 0..2)
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putInt("mode", index).apply()
        apply(context)
    }
}
