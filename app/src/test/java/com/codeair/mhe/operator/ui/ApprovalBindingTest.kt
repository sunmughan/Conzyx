package com.codeair.mhe.operator.ui

import com.codeair.mhe.security.SafetyOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalBindingTest {

    private fun tapOp(id: String = "g5:p0") = SafetyOperation.UiTap(id, "Test button")

    @Test
    fun sameInputs_produceSameBindingId() {
        val op = tapOp()
        val first = approvalBindingId("run-1", "tap", op)
        val second = approvalBindingId("run-1", "tap", op)
        assertEquals(first, second)
    }

    @Test
    fun differentRunId_producesDifferentId() {
        val op = tapOp()
        assertNotEquals(
            approvalBindingId("run-1", "tap", op),
            approvalBindingId("run-2", "tap", op)
        )
    }

    @Test
    fun differentStepId_producesDifferentId() {
        val op = tapOp()
        assertNotEquals(
            approvalBindingId("run-1", "tap", op),
            approvalBindingId("run-1", "launch", op)
        )
    }

    @Test
    fun differentOperationTarget_producesDifferentId() {
        assertNotEquals(
            approvalBindingId("run-1", "tap", tapOp("g5:p0")),
            approvalBindingId("run-1", "tap", tapOp("g5:p1"))
        )
    }

    @Test
    fun bindingId_isSha256Hex() {
        val id = approvalBindingId("run-1", "tap", tapOp())
        assertEquals(64, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
