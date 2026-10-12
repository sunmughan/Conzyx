package com.codeair.mhe.cloud

import com.codeair.mhe.cloud.exec.CloudCostController
import com.codeair.mhe.cloud.exec.CloudSpendEntry
import com.codeair.mhe.cloud.model.CloudWorkerSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCostControllerTest {

    private val worker = CloudWorkerSpec(
        name = "Test Worker",
        endpointUrl = "https://worker.test",
        region = "test-1",
        authKey = "secret-key",
        costPerHourUsd = 1.0
    )

    @Test
    fun cheapJobIsAuthorized() {
        val controller = CloudCostController(monthlyBudgetUsd = 25.0, maxCostPerJobUsd = 5.0)
        val auth = controller.authorizeDispatch(worker, 120L, "build")
        assertTrue(auth.authorized)
        assertEquals(120.0 / 3600.0, auth.estimatedCostUsd, 1e-9)
    }

    @Test
    fun perJobCapRefusal() {
        val controller = CloudCostController(monthlyBudgetUsd = 1000.0, maxCostPerJobUsd = 0.01)
        val auth = controller.authorizeDispatch(worker, 3600L, "expensive build")
        assertFalse(auth.authorized)
        assertTrue(auth.reason.contains("per-job cap"))
        assertEquals(1L, controller.refusedDispatches())
    }

    @Test
    fun monthlyBudgetRefusal() {
        val controller = CloudCostController(monthlyBudgetUsd = 1.0, maxCostPerJobUsd = 100.0)
        controller.recordSpend(CloudSpendEntry("j1", "w1", "Test Worker", 3500L, 0.97))
        val auth = controller.authorizeDispatch(worker, 600L, "another build")
        assertFalse(auth.authorized)
        assertTrue(auth.reason.contains("monthly budget"))
    }

    @Test
    fun ledgerAccumulatesSpend() {
        val controller = CloudCostController()
        controller.recordSpend(CloudSpendEntry("j1", "w1", "Test Worker", 60L, 0.01))
        controller.recordSpend(CloudSpendEntry("j2", "w1", "Test Worker", 120L, 0.02))
        assertEquals(0.03, controller.totalSpend(), 1e-9)
        assertEquals(2, controller.jobCount())
        assertEquals(2, controller.ledgerSnapshot().size)
    }

    @Test
    fun spendOlderThan30DaysIsExcluded() {
        val now = System.currentTimeMillis()
        val controller = CloudCostController(clock = { now })
        val old = now - 31L * 24 * 60 * 60 * 1000
        controller.recordSpend(CloudSpendEntry("j1", "w1", "Test Worker", 3600L, 5.0, timestampMs = old))
        controller.recordSpend(CloudSpendEntry("j2", "w1", "Test Worker", 60L, 0.01, timestampMs = now))
        assertEquals(0.01, controller.spendLast30Days(), 1e-9)
    }

    @Test
    fun estimateCostScalesWithDurationAndRate() {
        val controller = CloudCostController()
        assertEquals(0.5, controller.estimateCost(worker, 1800L), 1e-9)
        assertEquals(0.0, controller.estimateCost(worker, 0L), 1e-9)
    }
}
