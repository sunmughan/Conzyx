package com.codeair.mhe.operator.guard

import com.codeair.mhe.security.SafetyOperation
import com.codeair.mhe.visual.PixelBuffer
import com.codeair.mhe.visual.VisualDiffer

data class GuardDecision(val allow: Boolean, val reason: String)

class NoProgressGuard(
    private val maxEquivalentStreak: Int = 3,
    private val changeThresholdPercent: Double = 0.5,
) {
    private var streakKey: String? = null
    private var streakCount = 0

    fun check(action: SafetyOperation, before: PixelBuffer?, after: PixelBuffer?): GuardDecision {
        val key = action.describe()
        if (before == null || after == null) {
            streakKey = null
            streakCount = 0
            return GuardDecision(true, "no visual baseline")
        }
        val diff = runCatching { VisualDiffer.diff(before, after, tolerance = 2) }.getOrNull()
            ?: return GuardDecision(true, "diff unavailable")
        if (diff.diffPercentage < changeThresholdPercent) {
            if (key == streakKey) {
                streakCount++
            } else {
                streakKey = key
                streakCount = 1
            }
            return if (streakCount >= maxEquivalentStreak) {
                GuardDecision(false, "no observable progress after $streakCount equivalent actions: $key")
            } else {
                GuardDecision(true, "no change yet (streak $streakCount)")
            }
        }
        streakKey = null
        streakCount = 0
        return GuardDecision(true, "progress observed: ${diff.diffPercentage}% changed")
    }

    fun reset() {
        streakKey = null
        streakCount = 0
    }

    fun currentStreak(): Int = streakCount
}
