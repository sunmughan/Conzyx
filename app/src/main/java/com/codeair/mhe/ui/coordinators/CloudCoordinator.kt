package com.codeair.mhe.ui.coordinators

import com.codeair.mhe.cloud.CloudHybridCoordinator
import com.codeair.mhe.cloud.model.HybridExecutionJob
import com.codeair.mhe.cloud.model.WorkloadRoutingPolicy
import com.codeair.mhe.cloud.model.WorkloadType

/**
 * Cloud/hybrid-execution domain coordinator (Milestone 1).
 *
 * Explicit owner of the hybrid job state machine, delegating to the real
 * [CloudHybridCoordinator]. Milestone 0 integrity applies: with no real cloud
 * worker configured, jobs honestly report `NOT_CONFIGURED` — never fake
 * completion. Real cloud dispatch is Milestone 11 work.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class CloudCoordinator(
    private val coordinator: CloudHybridCoordinator,
) {
    fun setCloudRoutingPolicy(policy: WorkloadRoutingPolicy) {
        coordinator.setPolicy(policy)
    }

    fun submitHybridWorkload(title: String, type: WorkloadType): HybridExecutionJob {
        return coordinator.submitExecutionJob(title, type)
    }
}
