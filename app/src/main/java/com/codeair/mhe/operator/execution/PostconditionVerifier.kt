package com.codeair.mhe.operator.execution

import com.codeair.mhe.visual.PixelBuffer
import com.codeair.mhe.visual.VisualDiffer

data class VerificationOutcome(
    val passed: Boolean,
    val changePercent: Double,
    val changedRegions: Int,
)

object PostconditionVerifier {

    fun verify(
        before: PixelBuffer,
        after: PixelBuffer,
        minChangePercent: Double = 0.5,
        tolerance: Int = 2,
    ): VerificationOutcome {
        val result = runCatching {
            VisualDiffer.diff(before, after, tolerance = tolerance)
        }.getOrElse {
            return VerificationOutcome(passed = false, changePercent = 0.0, changedRegions = 0)
        }
        return VerificationOutcome(
            passed = result.diffPercentage >= minChangePercent,
            changePercent = result.diffPercentage,
            changedRegions = result.changedRegions.size,
        )
    }
}
