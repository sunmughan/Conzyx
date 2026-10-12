package com.jarves.mh.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceListingValidatorTest {

    private fun validDraft() = ListingDraft(
        name = "Postgres MCP Pro",
        category = MarketplaceCategory.MCP_SERVERS,
        description = "A production-ready Postgres MCP server with pooling.",
        version = "2.1.0",
        priceCents = 999,
        currency = "USD",
        artifactUrl = "https://example.com/postgres-pro.zip",
        sha256 = "b".repeat(64),
        tags = listOf("sql", "postgres", "mcp"),
        sellerId = "seller-9"
    )

    @Test
    fun validDraftPasses() {
        val result = validateListing(validDraft())
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun freeListingWithZeroPricePasses() {
        val result = validateListing(validDraft().copy(priceCents = 0))
        assertTrue(result.isValid)
    }

    @Test
    fun shortNameFails() {
        val result = validateListing(validDraft().copy(name = "AB"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("name") })
    }

    @Test
    fun missingCategoryFails() {
        val result = validateListing(validDraft().copy(category = null))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("category") })
    }

    @Test
    fun shortDescriptionFails() {
        val result = validateListing(validDraft().copy(description = "Too short"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("description") })
    }

    @Test
    fun badVersionFails() {
        listOf("v1", "1.0.0.0.0x", "latest", "").forEach { v ->
            val result = validateListing(validDraft().copy(version = v))
            assertFalse("version '$v' should fail", result.isValid)
        }
    }

    @Test
    fun prereleaseVersionPasses() {
        val result = validateListing(validDraft().copy(version = "2.0.0-beta.1"))
        assertTrue(result.isValid)
    }

    @Test
    fun negativePriceFails() {
        val result = validateListing(validDraft().copy(priceCents = -5))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("priceCents") })
    }

    @Test
    fun badCurrencyFails() {
        listOf("usd", "USDD", "US", "12A").forEach { c ->
            val result = validateListing(validDraft().copy(currency = c))
            assertFalse("currency '$c' should fail", result.isValid)
        }
    }

    @Test
    fun nonHttpArtifactUrlFails() {
        val result = validateListing(validDraft().copy(artifactUrl = "ftp://example.com/x.zip"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("artifactUrl") })
    }

    @Test
    fun blankArtifactUrlFails() {
        val result = validateListing(validDraft().copy(artifactUrl = "  "))
        assertFalse(result.isValid)
    }

    @Test
    fun badSha256Fails() {
        val result = validateListing(validDraft().copy(sha256 = "not-hex"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("sha256") })
    }

    @Test
    fun tooManyTagsFail() {
        val result = validateListing(validDraft().copy(tags = (1..11).map { "tag$it" }))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("tags") })
    }

    @Test
    fun badTagFails() {
        val result = validateListing(validDraft().copy(tags = listOf("ok-tag", "bad tag!")))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("tag") })
    }

    @Test
    fun multipleErrorsAreAllReported() {
        val result = validateListing(
            validDraft().copy(name = "x", description = "y", priceCents = -1, currency = "zz")
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.size >= 4)
    }

    @Test
    fun draftConvertsToListingJson() {
        val json = validDraft().toListingJson()
        assertEquals("Postgres MCP Pro", json.getString("name"))
        assertEquals("MCP_SERVERS", json.getString("category"))
        assertEquals(999L, json.getJSONObject("price").getLong("cents"))
        assertEquals("seller-9", json.getString("sellerId"))
    }
}
