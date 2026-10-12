package com.codeair.mhe.runtime

/** Authentication status for a Cloud Code account session. */
enum class CloudCodeAuthStatus { SIGNED_OUT, STARTING, AWAITING_CODE, COMPLETING, SIGNED_IN, ERROR }

/**
 * A single Cloud Code account. Mirrors [AntigravityAccount] so multi-account
 * failover behavior stays consistent across OAuth-based agents.
 */
data class CloudCodeAccount(
    val email: String,
    val isActive: Boolean = false,
    val isQuotaExhausted: Boolean = false,
    val quotaExhaustedAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    val isAuthExpired: Boolean = false,
    val authExpiredAt: Long = 0L,
)

/** Observable authentication state for Cloud Code, including all saved accounts. */
data class CloudCodeAuthState(
    val status: CloudCodeAuthStatus = CloudCodeAuthStatus.SIGNED_OUT,
    val authorizationUrl: String? = null,
    val message: String? = null,
    val accountEmail: String? = null,
    val accounts: List<CloudCodeAccount> = emptyList(),
    val autoRoundRobin: Boolean = true,
)
