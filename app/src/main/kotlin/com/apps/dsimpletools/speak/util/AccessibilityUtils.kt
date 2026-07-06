package com.apps.dsimpletools.speak.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object AccessibilityUtils {

    /**
     * Mirrors the standard pattern for checking whether a specific
     * accessibility service is user-enabled: read the colon-separated
     * [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] list and look for our
     * component. This is the same setting `adb shell settings put secure
     * enabled_accessibility_services ...` writes to.
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expected = ComponentName(context, serviceClass)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(splitter.next())
            if (componentName != null && componentName == expected) {
                return true
            }
        }
        return false
    }
}
