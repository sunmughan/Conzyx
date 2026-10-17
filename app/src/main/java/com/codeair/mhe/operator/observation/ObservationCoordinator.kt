package com.codeair.mhe.operator.observation

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.codeair.mhe.operator.OperatorAccessibilityService
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class ObservationCoordinator private constructor(private val appContext: Context) {

    companion object {
        @Volatile
        private var INSTANCE: ObservationCoordinator? = null

        fun get(appContext: Context): ObservationCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ObservationCoordinator(appContext.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val generationCounter = AtomicLong(0)

    @Volatile
    private var cached: UiObservation? = null

    @Volatile
    private var lastPackageName: String = ""

    fun capture(): UiObservation {
        val generation = generationCounter.incrementAndGet()
        return runCatching {
            val service = OperatorAccessibilityService.instance
            val rootInfo = service?.rootInActiveWindow
            if (rootInfo == null) {
                val observation = UiObservation(
                    observationId = UUID.randomUUID().toString(),
                    generation = generation,
                    capturedAtMillis = System.currentTimeMillis(),
                    packageName = lastPackageName,
                    root = null,
                    fresh = false
                )
                cached = observation
                return@runCatching observation
            }
            val rootPackage = runCatching { rootInfo.packageName?.toString() ?: "" }.getOrDefault("")
            if (rootPackage.isNotEmpty()) {
                lastPackageName = rootPackage
            }
            val rootNode = walk(rootInfo, generation, "")
            val observation = UiObservation(
                observationId = UUID.randomUUID().toString(),
                generation = generation,
                capturedAtMillis = System.currentTimeMillis(),
                packageName = rootPackage,
                root = rootNode,
                fresh = true
            )
            cached = observation
            observation
        }.getOrElse {
            UiObservation(
                observationId = UUID.randomUUID().toString(),
                generation = generation,
                capturedAtMillis = System.currentTimeMillis(),
                packageName = lastPackageName,
                root = null,
                fresh = false
            )
        }
    }

    fun findNode(observation: UiObservation, nodeId: String): UiNode? {
        return runCatching {
            val pathSegment = nodeId.substringAfter(":p", missingDelimiterValue = "")
            if (nodeId.substringBefore(":p", missingDelimiterValue = "") == nodeId) {
                return@runCatching null
            }
            var current = observation.root ?: return@runCatching null
            if (pathSegment.isEmpty()) {
                return@runCatching current
            }
            val indices = pathSegment.split("/").map { it.toIntOrNull() ?: return@runCatching null }
            for (index in indices) {
                if (index < 0 || index >= current.children.size) {
                    return@runCatching null
                }
                current = current.children[index]
            }
            current
        }.getOrNull()
    }

    fun lastObservation(): UiObservation? {
        return runCatching { cached }.getOrNull()
    }

    private fun walk(node: AccessibilityNodeInfo, generation: Long, path: String): UiNode {
        val nodeId = "g$generation:p$path"
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val children = (0 until node.childCount).mapNotNull { index ->
            runCatching {
                val child = node.getChild(index) ?: return@runCatching null
                val childPath = if (path.isEmpty()) index.toString() else "$path/$index"
                walk(child, generation, childPath)
            }.getOrNull()
        }
        return UiNode(
            nodeId = nodeId,
            className = runCatching { node.className?.toString() ?: "" }.getOrDefault(""),
            text = runCatching { node.text?.toString() }.getOrNull(),
            contentDescription = runCatching { node.contentDescription?.toString() }.getOrNull(),
            resourceId = runCatching { node.viewIdResourceName }.getOrNull(),
            bounds = Rect(bounds),
            clickable = runCatching { node.isClickable }.getOrDefault(false),
            scrollable = runCatching { node.isScrollable }.getOrDefault(false),
            editable = runCatching {
                node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
            }.getOrDefault(false),
            children = children
        )
    }
}
