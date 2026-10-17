package com.codeair.mhe.operator.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleTargetPolicyTest {

    @Test
    fun generationOf_parsesGenerationFromValidNodeId() {
        assertEquals(5L, StaleTargetPolicy.generationOf("g5:p0/1/2"))
    }

    @Test
    fun generationOf_parsesGenerationZero() {
        assertEquals(0L, StaleTargetPolicy.generationOf("g0:p"))
    }

    @Test
    fun generationOf_returnsNullForMalformedId() {
        assertNull(StaleTargetPolicy.generationOf("nope"))
        assertNull(StaleTargetPolicy.generationOf(""))
        assertNull(StaleTargetPolicy.generationOf("p0/1"))
    }

    @Test
    fun isStale_matchingGeneration_returnsFalse() {
        assertFalse(StaleTargetPolicy.isStale("g5:p0/1", 5L))
    }

    @Test
    fun isStale_differentGeneration_returnsTrue() {
        assertTrue(StaleTargetPolicy.isStale("g5:p0/1", 6L))
        assertTrue(StaleTargetPolicy.isStale("g6:p0/1", 5L))
    }

    @Test
    fun isStale_malformedId_returnsTrue_failClosed() {
        assertTrue(StaleTargetPolicy.isStale("garbage", 5L))
        assertTrue(StaleTargetPolicy.isStale("", 0L))
    }
}
