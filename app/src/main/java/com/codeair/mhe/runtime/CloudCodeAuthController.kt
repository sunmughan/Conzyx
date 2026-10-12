package com.codeair.mhe.runtime

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Multi-account manager for Cloud Code.
 *
 * Follows the same pattern as [AntigravityAuthController]: multiple accounts can be
 * added, each account's credential is stored in its own folder, and
 * [switchToNextAccount] automatically fails over to the next healthy account when
 * the current one hits a quota limit or its auth expires.
 */
class CloudCodeAuthController(
    private val context: Context,
    initiallySignedIn: Boolean,
    initialAccountEmail: String,
    private val onSignedInChanged: (Boolean, String?) -> Unit,
) {
    private val mutableState = MutableStateFlow(
        CloudCodeAuthState(
            status = if (initiallySignedIn) CloudCodeAuthStatus.SIGNED_IN else CloudCodeAuthStatus.SIGNED_OUT,
            message = initialAccountEmail.takeIf(String::isNotBlank)?.let { "Connected as $it" },
            accountEmail = initialAccountEmail.takeIf(String::isNotBlank),
        ),
    )
    val state: StateFlow<CloudCodeAuthState> = mutableState.asStateFlow()

    private val prefs = context.getSharedPreferences("cloudcode_auth_store", Context.MODE_PRIVATE)

    private fun accountsDir() = File(
        context.filesDir,
        "runtime/cloudcode/accounts",
    ).apply { mkdirs() }

    private fun accountCredentialFile(email: String) = File(
        accountsDir(),
        "${sanitizeEmail(email)}/cloudcode-oauth-token",
    )

    private fun officialCredentialFile() = File(
        context.filesDir,
        "runtime/cloudcode/cloudcode-oauth-token",
    )

    private fun sanitizeEmail(email: String): String =
        email.lowercase().replace(Regex("[^a-z0-9._-]"), "_")

    fun getSavedAccounts(): List<CloudCodeAccount> {
        val raw = prefs.getString("accounts_json", "[]") ?: "[]"
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                CloudCodeAccount(
                    email = obj.getString("email"),
                    isActive = obj.optBoolean("isActive", false),
                    isQuotaExhausted = obj.optBoolean("isQuotaExhausted", false),
                    quotaExhaustedAt = obj.optLong("quotaExhaustedAt", 0L),
                    lastUsedAt = obj.optLong("lastUsedAt", 0L),
                    isAuthExpired = obj.optBoolean("isAuthExpired", false),
                    authExpiredAt = obj.optLong("authExpiredAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAccounts(accounts: List<CloudCodeAccount>) {
        val array = org.json.JSONArray()
        accounts.forEach { acc ->
            val obj = org.json.JSONObject()
                .put("email", acc.email)
                .put("isActive", acc.isActive)
                .put("isQuotaExhausted", acc.isQuotaExhausted)
                .put("quotaExhaustedAt", acc.quotaExhaustedAt)
                .put("lastUsedAt", acc.lastUsedAt)
                .put("isAuthExpired", acc.isAuthExpired)
                .put("authExpiredAt", acc.authExpiredAt)
            array.put(obj)
        }
        prefs.edit().putString("accounts_json", array.toString()).apply()
        mutableState.value = mutableState.value.copy(accounts = accounts)
    }

    fun isAutoRoundRobin(): Boolean = prefs.getBoolean("auto_round_robin", true)

    fun setAutoRoundRobin(enabled: Boolean) {
        prefs.edit().putBoolean("auto_round_robin", enabled).apply()
        mutableState.value = mutableState.value.copy(autoRoundRobin = enabled)
    }

    fun recordSignedInAccount(email: String) {
        val cred = officialCredentialFile()
        if (cred.isFile) {
            val target = accountCredentialFile(email).apply { parentFile?.mkdirs() }
            runCatching { cred.copyTo(target, overwrite = true) }
        }
        val currentAccounts = getSavedAccounts().toMutableList()
        val index = currentAccounts.indexOfFirst { it.email.equals(email, ignoreCase = true) }
        val updated = CloudCodeAccount(
            email = email,
            isActive = true,
            isQuotaExhausted = false,
            lastUsedAt = System.currentTimeMillis(),
        )
        val newAccounts = currentAccounts.map { it.copy(isActive = false) }.toMutableList()
        if (index >= 0) {
            newAccounts[index] = updated
        } else {
            newAccounts.add(updated)
        }
        saveAccounts(newAccounts)
    }

    fun isCredentialValid(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching {
            val text = file.readText().trim()
            text.isNotBlank() && (text.startsWith("{") || text.length >= 10)
        }.getOrDefault(false)
    }

    fun activateAccount(email: String): Boolean {
        val cred = accountCredentialFile(email)
        if (!isCredentialValid(cred)) return false
        val active = officialCredentialFile().apply { parentFile?.mkdirs() }
        try {
            cred.copyTo(active, overwrite = true)
            if (!active.isFile || active.length() <= 0L) return false
        } catch (_: Throwable) {
            return false
        }
        val currentAccounts = getSavedAccounts()
        val updated = currentAccounts.map {
            it.copy(
                isActive = it.email.equals(email, ignoreCase = true),
                lastUsedAt = if (it.email.equals(email, ignoreCase = true)) System.currentTimeMillis() else it.lastUsedAt,
            )
        }
        saveAccounts(updated)
        onSignedInChanged(true, email)
        mutableState.value = mutableState.value.copy(
            status = CloudCodeAuthStatus.SIGNED_IN,
            message = "Connected as $email",
            accountEmail = email,
            accounts = updated,
        )
        return true
    }

    /**
     * Automatically switches to the next healthy account.
     * Priority: (1) non-exhausted accounts with valid credentials,
     * (2) accounts whose quota cooldown (60 min) has elapsed,
     * (3) oldest-exhausted account as last resort.
     * Returns the newly activated email, or null if no account could be activated.
     */
    fun switchToNextAccount(): String? {
        val accounts = getSavedAccounts()
        if (accounts.size <= 1) return null
        val activeIndex = accounts.indexOfFirst { it.isActive }
        val now = System.currentTimeMillis()

        val candidatesInOrder = (1 until accounts.size).map { offset ->
            val index = (if (activeIndex >= 0) activeIndex + offset else offset) % accounts.size
            accounts[index]
        }

        // 1. Prioritize account not marked exhausted or auth-expired with valid credentials
        for (candidate in candidatesInOrder) {
            val credFile = accountCredentialFile(candidate.email)
            if (!candidate.isQuotaExhausted && !candidate.isAuthExpired && isCredentialValid(credFile)) {
                if (activateAccount(candidate.email)) return candidate.email
            }
        }

        // 2. Prioritize account whose limit was hit >= 60 minutes ago (and not auth expired)
        for (candidate in candidatesInOrder) {
            val credFile = accountCredentialFile(candidate.email)
            if (!candidate.isAuthExpired && candidate.isQuotaExhausted && candidate.quotaExhaustedAt > 0 &&
                (now - candidate.quotaExhaustedAt >= HOURLY_LIMIT_COOLDOWN_MS) &&
                isCredentialValid(credFile)
            ) {
                if (activateAccount(candidate.email)) return candidate.email
            }
        }

        // 3. If all non-expired accounts hit quota, try candidate with oldest exhaustion time
        val candidatesByCooldown = candidatesInOrder
            .filter { !it.isAuthExpired && isCredentialValid(accountCredentialFile(it.email)) }
            .sortedBy { it.quotaExhaustedAt }

        for (candidate in candidatesByCooldown) {
            if (activateAccount(candidate.email)) return candidate.email
        }

        return null
    }

    fun markCurrentAccountQuotaExhausted() {
        val current = mutableState.value.accountEmail ?: return
        val accounts = getSavedAccounts()
        val updated = accounts.map {
            if (it.email.equals(current, ignoreCase = true)) {
                it.copy(isQuotaExhausted = true, quotaExhaustedAt = System.currentTimeMillis())
            } else it
        }
        saveAccounts(updated)
    }

    fun markCurrentAccountAuthExpired() {
        val current = mutableState.value.accountEmail ?: return
        val accounts = getSavedAccounts()
        val updated = accounts.map {
            if (it.email.equals(current, ignoreCase = true)) {
                it.copy(isAuthExpired = true, authExpiredAt = System.currentTimeMillis())
            } else it
        }
        saveAccounts(updated)
    }

    /** Prepares to add a new account: backs up the current credential, then clears the active slot. */
    suspend fun beginAddAccount() = withContext(Dispatchers.IO) {
        val currentEmail = mutableState.value.accountEmail
        if (!currentEmail.isNullOrBlank() && officialCredentialFile().isFile) {
            val target = accountCredentialFile(currentEmail).apply { parentFile?.mkdirs() }
            runCatching { officialCredentialFile().copyTo(target, overwrite = true) }
        }
        officialCredentialFile().delete()
        mutableState.value = mutableState.value.copy(
            status = CloudCodeAuthStatus.AWAITING_CODE,
            message = "Sign in with the next Cloud Code account.",
        )
    }

    suspend fun removeAccount(email: String) = withContext(Dispatchers.IO) {
        val folder = File(accountsDir(), sanitizeEmail(email))
        if (folder.isDirectory) folder.deleteRecursively()
        val remaining = getSavedAccounts().filter { !it.email.equals(email, ignoreCase = true) }
        saveAccounts(remaining)
        if (mutableState.value.accountEmail.equals(email, ignoreCase = true)) {
            val next = remaining.firstOrNull()
            if (next != null) {
                activateAccount(next.email)
            } else {
                logout()
            }
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val previousEmail = mutableState.value.accountEmail
        mutableState.value = mutableState.value.copy(
            status = CloudCodeAuthStatus.STARTING,
            message = "Signing out of Cloud Code…",
            accountEmail = previousEmail,
        )
        try {
            val credential = officialCredentialFile()
            if (credential.exists()) {
                check(credential.delete()) {
                    "Could not remove the Cloud Code credential. Your account remains connected."
                }
            }
            onSignedInChanged(false, null)
            mutableState.value = mutableState.value.copy(
                status = CloudCodeAuthStatus.SIGNED_OUT,
                message = "Signed out",
                accountEmail = null,
            )
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                status = CloudCodeAuthStatus.SIGNED_IN,
                message = error.message?.take(240) ?: "Could not log out of Cloud Code",
                accountEmail = previousEmail,
            )
            throw error
        }
    }

    fun invalidateSession(message: String) {
        onSignedInChanged(false, null)
        mutableState.value = mutableState.value.copy(status = CloudCodeAuthStatus.ERROR, message = message)
    }

    init {
        val existing = getSavedAccounts()
        val accounts = if (existing.isEmpty() && initialAccountEmail.isNotBlank() && officialCredentialFile().isFile) {
            val seeded = CloudCodeAccount(
                email = initialAccountEmail,
                isActive = true,
                lastUsedAt = System.currentTimeMillis(),
            )
            runCatching {
                val target = accountCredentialFile(initialAccountEmail).apply { parentFile?.mkdirs() }
                officialCredentialFile().copyTo(target, overwrite = true)
            }
            listOf(seeded).also { saveAccounts(it) }
        } else existing
        mutableState.value = mutableState.value.copy(
            accounts = accounts,
            autoRoundRobin = isAutoRoundRobin(),
        )
    }

    companion object {
        const val HOURLY_LIMIT_COOLDOWN_MS = 60 * 60 * 1000L // 60-minute limit cooldown window
    }
}

/**
 * Detects quota / rate-limit / capacity failures in Cloud Code error output so the
 * caller can mark the account exhausted and trigger [CloudCodeAuthController.switchToNextAccount].
 */
fun isCloudCodeQuotaFailure(reason: String): Boolean {
    val value = reason.lowercase()
    return "quota" in value ||
        "rate limit" in value ||
        "ratelimit" in value ||
        "resource_exhausted" in value ||
        "resource exhausted" in value ||
        "429" in value ||
        "out of credits" in value ||
        "limit exceeded" in value ||
        "exhausted" in value ||
        "too many requests" in value ||
        "hourly limit" in value ||
        "hour limit" in value ||
        "daily limit" in value ||
        "day limit" in value ||
        "usage limit" in value ||
        "plan limit" in value ||
        "capacity" in value ||
        "overloaded" in value ||
        "quota_exceeded" in value ||
        "rate_limit_exceeded" in value ||
        "reached your limit" in value ||
        "hit your limit" in value ||
        "exceeded your limit" in value ||
        "exceeded your current quota" in value
}
