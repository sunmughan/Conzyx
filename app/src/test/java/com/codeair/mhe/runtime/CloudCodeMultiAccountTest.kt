package com.codeair.mhe.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCodeMultiAccountTest {

    @Test
    fun `parses and serializes accounts json correctly`() {
        val original = listOf(
            CloudCodeAccount("alpha@example.com", isActive = true, isQuotaExhausted = false, lastUsedAt = 1000L),
            CloudCodeAccount(
                "beta@example.com", isActive = false,
                isQuotaExhausted = true, quotaExhaustedAt = 5000L,
                lastUsedAt = 2000L, isAuthExpired = true, authExpiredAt = 6000L,
            ),
        )

        val array = JSONArray()
        original.forEach { acc ->
            array.put(
                JSONObject()
                    .put("email", acc.email)
                    .put("isActive", acc.isActive)
                    .put("isQuotaExhausted", acc.isQuotaExhausted)
                    .put("quotaExhaustedAt", acc.quotaExhaustedAt)
                    .put("lastUsedAt", acc.lastUsedAt)
                    .put("isAuthExpired", acc.isAuthExpired)
                    .put("authExpiredAt", acc.authExpiredAt),
            )
        }
        val serialized = array.toString()

        val parsedArray = JSONArray(serialized)
        val deserialized = (0 until parsedArray.length()).map { i ->
            val obj = parsedArray.getJSONObject(i)
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

        assertEquals(2, deserialized.size)
        assertEquals("alpha@example.com", deserialized[0].email)
        assertTrue(deserialized[0].isActive)
        assertFalse(deserialized[0].isQuotaExhausted)
        assertFalse(deserialized[0].isAuthExpired)
        assertEquals("beta@example.com", deserialized[1].email)
        assertTrue(deserialized[1].isQuotaExhausted)
        assertEquals(5000L, deserialized[1].quotaExhaustedAt)
        assertTrue(deserialized[1].isAuthExpired)
        assertEquals(6000L, deserialized[1].authExpiredAt)
    }

    @Test
    fun `detects quota and rate limit failures`() {
        val quotaReasons = listOf(
            "quota exceeded for this account",
            "HTTP 429: too many requests",
            "rate limit reached, retry later",
            "You have reached your daily limit",
            "RESOURCE_EXHAUSTED: quota spent",
            "out of credits for this billing period",
            "usage limit exceeded on current plan",
            "Hourly limit hit, please wait",
        )
        quotaReasons.forEach { reason ->
            assertTrue("Expected quota failure for: $reason", isCloudCodeQuotaFailure(reason))
        }
    }

    @Test
    fun `ignores non-quota errors`() {
        val otherReasons = listOf(
            "network unreachable",
            "connection timed out",
            "invalid request payload",
            "model not found",
        )
        otherReasons.forEach { reason ->
            assertFalse("Expected non-quota for: $reason", isCloudCodeQuotaFailure(reason))
        }
    }

    @Test
    fun `quota cooldown constant is one hour`() {
        assertEquals(60 * 60 * 1000L, CloudCodeAuthController.HOURLY_LIMIT_COOLDOWN_MS)
    }

    @Test
    fun `failover candidate ordering prefers healthy accounts`() {
        // Simulates the priority logic in switchToNextAccount: healthy first,
        // then cooled-down, then oldest-exhausted.
        val now = System.currentTimeMillis()
        val accounts = listOf(
            CloudCodeAccount("active@example.com", isActive = true),
            CloudCodeAccount("exhausted@example.com", isQuotaExhausted = true, quotaExhaustedAt = now - 10_000L),
            CloudCodeAccount("healthy@example.com"),
            CloudCodeAccount("cooled@example.com", isQuotaExhausted = true, quotaExhaustedAt = now - 2 * 60 * 60 * 1000L),
        )
        val candidates = accounts.filter { !it.isActive }

        val healthyFirst = candidates.filter { !it.isQuotaExhausted && !it.isAuthExpired }
        assertEquals(listOf("healthy@example.com"), healthyFirst.map { it.email })

        val cooledDown = candidates.filter {
            !it.isAuthExpired && it.isQuotaExhausted &&
                now - it.quotaExhaustedAt >= CloudCodeAuthController.HOURLY_LIMIT_COOLDOWN_MS
        }
        assertEquals(listOf("cooled@example.com"), cooledDown.map { it.email })
    }
}
