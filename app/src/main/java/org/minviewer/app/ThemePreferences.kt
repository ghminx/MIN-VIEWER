package org.minviewer.app

import android.app.Activity
import android.content.res.Configuration

object ThemePreferences {
    private fun mode(activity: Activity) = activity.getSharedPreferences("appearance", 0).getString("mode", "system")

    fun isDark(activity: Activity): Boolean = when (mode(activity)) {
        "dark" -> true
        "light" -> false
        else -> activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    fun apply(activity: Activity) {
        activity.setTheme(if (isDark(activity)) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
    }

    fun selected(activity: Activity) = when (mode(activity)) { "light" -> 1; "dark" -> 2; else -> 0 }

    fun select(activity: Activity, index: Int) {
        activity.getSharedPreferences("appearance", 0).edit().putString("mode", listOf("system", "light", "dark")[index]).commit()
        activity.recreate()
    }
}
