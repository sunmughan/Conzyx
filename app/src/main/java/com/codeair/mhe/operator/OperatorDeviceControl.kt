package com.codeair.mhe.operator

interface OperatorDeviceControl {
    fun isAvailable(): Boolean
    fun tapAt(x: Float, y: Float): Boolean
    fun longPressAt(x: Float, y: Float): Boolean
    fun setText(node: com.codeair.mhe.operator.observation.UiNode, text: String): Boolean
    fun scrollForward(node: com.codeair.mhe.operator.observation.UiNode): Boolean
    fun scrollBackward(node: com.codeair.mhe.operator.observation.UiNode): Boolean
    fun pressBack(): Boolean
    fun launchApp(packageName: String): Boolean
}
