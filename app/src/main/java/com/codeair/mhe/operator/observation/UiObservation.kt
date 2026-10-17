package com.codeair.mhe.operator.observation

data class UiNode(
    val nodeId: String,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val bounds: android.graphics.Rect,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val children: List<UiNode> = emptyList()
)

data class UiObservation(
    val observationId: String,
    val generation: Long,
    val capturedAtMillis: Long,
    val packageName: String,
    val root: UiNode?,
    val fresh: Boolean
)
