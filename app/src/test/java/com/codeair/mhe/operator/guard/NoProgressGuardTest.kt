package com.codeair.mhe.operator.guard

import com.codeair.mhe.security.SafetyOperation
import com.codeair.mhe.visual.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoProgressGuardTest {

    private fun blackBuffer(): PixelBuffer =
        PixelBuffer(4, 4, IntArray(16) { 0xFF000000.toInt() })

    private fun whiteBuffer(): PixelBuffer =
        PixelBuffer(4, 4, IntArray(16) { 0xFFFFFFFF.toInt() })

    private fun tap(id: String) = SafetyOperation.UiTap(id, "")

    @Test
    fun nullBuffers_returnsAllowAndResetsStreak() {
        val guard = NoProgressGuard()
        val d1 = guard.check(tap("g1:p0"), blackBuffer(), blackBuffer())
        assertTrue(d1.allow)
        val d2 = guard.check(tap("g1:p0"), null, null)
        assertTrue(d2.allow)
        assertEquals(0, guard.currentStreak())
    }

    @Test
    fun threeIdenticalNoChangeActions_blocksThird() {
        val guard = NoProgressGuard(maxEquivalentStreak = 3)
        val before = blackBuffer()
        val after = blackBuffer()
        assertTrue(guard.check(tap("g1:p0"), before, after).allow)
        assertTrue(guard.check(tap("g1:p0"), before, after).allow)
        val third = guard.check(tap("g1:p0"), before, after)
        assertFalse(third.allow)
        assertTrue(third.reason.contains("no observable progress"))
    }

    @Test
    fun differentAction_resetsStreak() {
        val guard = NoProgressGuard(maxEquivalentStreak = 3)
        val buf = blackBuffer()
        guard.check(tap("g1:p0"), buf, buf)
        guard.check(tap("g1:p0"), buf, buf)
        val other = guard.check(tap("g1:p9"), buf, buf)
        assertTrue(other.allow)
        assertEquals(1, guard.currentStreak())
    }

    @Test
    fun observedProgress_resetsStreak() {
        val guard = NoProgressGuard(maxEquivalentStreak = 3)
        guard.check(tap("g1:p0"), blackBuffer(), blackBuffer())
        guard.check(tap("g1:p0"), blackBuffer(), blackBuffer())
        val progressed = guard.check(tap("g1:p0"), blackBuffer(), whiteBuffer())
        assertTrue(progressed.allow)
        assertEquals(0, guard.currentStreak())
    }

    @Test
    fun reset_clearsStreak() {
        val guard = NoProgressGuard(maxEquivalentStreak = 3)
        val buf = blackBuffer()
        guard.check(tap("g1:p0"), buf, buf)
        guard.check(tap("g1:p0"), buf, buf)
        guard.reset()
        assertEquals(0, guard.currentStreak())
        assertTrue(guard.check(tap("g1:p0"), buf, buf).allow)
    }

    @Test
    fun diffUnavailable_returnsAllow() {
        val guard = NoProgressGuard()
        val small = PixelBuffer(2, 2, IntArray(4) { 0 })
        val large = PixelBuffer(4, 4, IntArray(16) { 0 })
        val decision = guard.check(tap("g1:p0"), small, large)
        assertTrue(decision.allow)
    }
}
