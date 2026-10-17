package com.codeair.mhe.operator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class OperatorAccessibilityService : AccessibilityService(), OperatorDeviceControl {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun isAvailable(): Boolean = instance != null

    override fun tapAt(x: Float, y: Float): Boolean = runCatching {
        gestureAt(x, y, TAP_DURATION_MS)
    }.getOrDefault(false)

    override fun longPressAt(x: Float, y: Float): Boolean = runCatching {
        gestureAt(x, y, LONG_PRESS_DURATION_MS)
    }.getOrDefault(false)

    private fun gestureAt(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val description = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(description, null, null)
    }

    override fun setText(node: com.codeair.mhe.operator.observation.UiNode, text: String): Boolean = runCatching {
        val target = findNodeById(node.nodeId) ?: return@runCatching false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }.getOrDefault(false)

    override fun scrollForward(node: com.codeair.mhe.operator.observation.UiNode): Boolean = runCatching {
        val target = findNodeById(node.nodeId) ?: return@runCatching false
        target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }.getOrDefault(false)

    override fun scrollBackward(node: com.codeair.mhe.operator.observation.UiNode): Boolean = runCatching {
        val target = findNodeById(node.nodeId) ?: return@runCatching false
        target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }.getOrDefault(false)

    override fun pressBack(): Boolean = runCatching {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }.getOrDefault(false)

    override fun launchApp(packageName: String): Boolean = runCatching {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    }.getOrDefault(false)

    internal fun findNodeById(nodeId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val path = parseNodeIdPath(nodeId) ?: return null
        var current = root
        for (index in path) {
            if (index < 0 || index >= current.childCount) return null
            val next = current.getChild(index) ?: return null
            current = next
        }
        return current
    }

    private fun parseNodeIdPath(nodeId: String): List<Int>? {
        val segments = nodeId.split(":")
        if (segments.size != 2) return null
        val pathSegment = segments[1]
        if (!pathSegment.startsWith("p")) return null
        val body = pathSegment.removePrefix("p")
        if (body.isBlank()) return null
        return body.split("/").map { it.toIntOrNull() ?: return null }
    }

    companion object {
        @Volatile
        var instance: OperatorAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val flattened = ComponentName(context, OperatorAccessibilityService::class.java).flattenToShortString()
            return enabled.split(":").any { it == flattened }
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        private const val TAP_DURATION_MS = 100L
        private const val LONG_PRESS_DURATION_MS = 1000L
    }
}
