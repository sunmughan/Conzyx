package com.codeair.mhe.operator.execution

import com.codeair.mhe.operator.observation.UiNode
import com.codeair.mhe.operator.observation.UiObservation

fun verifyTapPostcondition(before: UiObservation, after: UiObservation, nodeId: String): Boolean {
    if (!after.fresh) return false
    if (after.generation == before.generation) return false
    val path = nodeId.substringAfter(":p", missingDelimiterValue = "")
    if (nodeId.substringBefore(":p", missingDelimiterValue = "") == nodeId) return false
    val nodeBefore = findNodeByPath(before.root, path) ?: return false
    if (after.packageName != before.packageName) return true
    val nodeAfter = findNodeByPath(after.root, path) ?: return true
    if (nodeAfter.text != nodeBefore.text) return true
    if (nodeAfter.contentDescription != nodeBefore.contentDescription) return true
    if (nodeAfter.bounds != nodeBefore.bounds) return true
    return nodeAfter.clickable != nodeBefore.clickable
}

internal fun findNodeByPath(root: UiNode?, path: String): UiNode? {    var current = root ?: return null
    if (path.isEmpty()) return current
    for (segment in path.split("/")) {
        val index = segment.toIntOrNull() ?: return null
        if (index < 0 || index >= current.children.size) return null
        current = current.children[index]
    }
    return current
}
