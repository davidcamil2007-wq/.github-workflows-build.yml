package com.autodroid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/** Executa no celular os toques feitos na tela do kit. */
class AutoAccess : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoAccess? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Int) {
        val p = Path().apply {
            moveTo(x1, y1)
            if (abs(x2 - x1) > 12 || abs(y2 - y1) > 12) lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(p, 0, dur.coerceIn(1, 4000).toLong())
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun global(code: Int) {
        val a = when (code) {
            1 -> GLOBAL_ACTION_BACK
            2 -> GLOBAL_ACTION_HOME
            3 -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        performGlobalAction(a)
    }
}
