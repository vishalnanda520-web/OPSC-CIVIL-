package com.opsc.tracker

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("focus_restore", Context.MODE_PRIVATE) }
    private var waitingForPolicyAccess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreAbandonedFocusIfNeeded()
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        if (waitingForPolicyAccess) {
            waitingForPolicyAccess = false
            if (getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
                applyFocusDnd()
                Toast.makeText(this, "Focus mode: alarms and starred callers are allowed", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "DND access was not granted; focus timer will still work", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startFocus() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            waitingForPolicyAccess = true
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }
        applyFocusDnd()
    }

    @Suppress("DEPRECATION")
    private fun applyFocusDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!prefs.getBoolean("saved", false)) {
            val oldPolicy = nm.notificationPolicy
            prefs.edit()
                .putInt("old_filter", nm.currentInterruptionFilter)
                .putInt("old_categories", oldPolicy?.priorityCategories ?: 0)
                .putInt("old_calls", oldPolicy?.priorityCallSenders ?: NotificationManager.Policy.PRIORITY_SENDERS_ANY)
                .putInt("old_messages", oldPolicy?.priorityMessageSenders ?: NotificationManager.Policy.PRIORITY_SENDERS_ANY)
                .putInt("old_visual", oldPolicy?.suppressedVisualEffects ?: 0)
                .putBoolean("saved", true)
                .apply()
        }
        val policy = NotificationManager.Policy(
            NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or NotificationManager.Policy.PRIORITY_CATEGORY_CALLS,
            NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
            NotificationManager.Policy.PRIORITY_SENDERS_NONE,
            0
        )
        nm.notificationPolicy = policy
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    @Suppress("DEPRECATION")
    private fun restoreFocusDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!prefs.getBoolean("saved", false)) return
        if (nm.isNotificationPolicyAccessGranted) {
            nm.notificationPolicy = NotificationManager.Policy(
                prefs.getInt("old_categories", 0),
                prefs.getInt("old_calls", NotificationManager.Policy.PRIORITY_SENDERS_ANY),
                prefs.getInt("old_messages", NotificationManager.Policy.PRIORITY_SENDERS_ANY),
                prefs.getInt("old_visual", 0)
            )
            nm.setInterruptionFilter(prefs.getInt("old_filter", NotificationManager.INTERRUPTION_FILTER_ALL))
        }
        prefs.edit().clear().apply()
    }

    private fun restoreAbandonedFocusIfNeeded() {
        if (prefs.getBoolean("saved", false)) restoreFocusDnd()
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    inner class Bridge {
        @JavascriptInterface fun minimalOn() { runOnUiThread { startFocus() } }
        @JavascriptInterface fun minimalOff() { runOnUiThread { restoreFocusDnd() } }
    }
}
