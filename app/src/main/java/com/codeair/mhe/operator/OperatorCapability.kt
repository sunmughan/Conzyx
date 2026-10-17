package com.codeair.mhe.operator

import android.content.Context

object OperatorCapability {
    fun accessibilityEnabled(context: Context): Boolean =
        OperatorAccessibilityService.isEnabled(context)

    fun openSettings(context: Context) =
        OperatorAccessibilityService.openAccessibilitySettings(context)
}
