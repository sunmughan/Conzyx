package com.codeair.mhe.marketplace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceProtocolTest {

    @Test
    fun transportRetriesOn429ThenSucceeds() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
        try {
            server.createContext("/api/v1/listings") { exchange ->
                val n = calls.incrementAndGet()
                if (n == 1) {
                    val body = "rate limited".toByteArray()
                    exchange.sendResponseHeaders(429, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } else {
                    val body = "{\"listings\":[]}".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                exchange.close()
            }
            server.start()
            val baseUrl = "http://localhost:${server.address.port}"
            val res = UrlConnectionMarketplaceTransport().send(
                baseUrl,
                MarketplaceTransportRequest("GET", "/api/v1/listings")
            )
            assertEquals(200, res.statusCode)
            assertTrue(res.body.contains("listings"))
            assertEquals(2, calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun transportStopsRetryingAfterMaxAttempts() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
        try {
            server.createContext("/api/v1/listings") { exchange ->
                calls.incrementAndGet()
                val body = "server error".toByteArray()
                exchange.sendResponseHeaders(503, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            server.start()
            val baseUrl = "http://localhost:${server.address.port}"
            val res = UrlConnectionMarketplaceTransport().send(
                baseUrl,
                MarketplaceTransportRequest("GET", "/api/v1/listings")
            )
            assertEquals(503, res.statusCode)
            assertEquals(3, calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun listingStatusParsesLeniently() {
        assertEquals(ListingStatus.PUBLISHED, ListingStatus.parse("published"))
        assertEquals(ListingStatus.UNPUBLISHED, ListingStatus.parse("UNPUBLISHED"))
        assertEquals(ListingStatus.REJECTED, ListingStatus.parse("Rejected"))
        assertEquals(ListingStatus.DRAFT, ListingStatus.parse(null))
        assertEquals(ListingStatus.DRAFT, ListingStatus.parse("bogus"))
    }

    @Test
    fun remoteListingJsonRoundTrip() {
        val listing = RemoteListing(
            id = "lst-1",
            name = "Postgres MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "DataWorks",
            description = "A production Postgres MCP server.",
            version = "2.1.0",
            rating = 4.9,
            downloads = 120,
            artifactUrl = "https://example.com/pkg.zip",
            sha256 = "a".repeat(64),
            tags = listOf("sql", "mcp"),
            publishedAt = 123456789L,
            status = ListingStatus.PUBLISHED
        )
        val back = RemoteListing.fromJson(listing.toJson())
        assertEquals(listing, back)
    }

    @Test
    fun remoteListingListParses() {
        val listing = RemoteListing(
            id = "lst-2", name = "X", category = MarketplaceCategory.SKILLS,
            author = "S", description = "d",
            version = "1.0.0"
        )
        val json = JSONObject().put(
            "listings",
            org.json.JSONArray().put(listing.toJson())
        ).toString()
        val parsed = RemoteListing.listFromJsonArray(JSONObject(json).getJSONArray("listings"))
        assertEquals(1, parsed.size)
        assertEquals("lst-2", parsed[0].id)
    }

    @Test
    fun remoteListingConvertsToMarketplaceItem() {
        val listing = RemoteListing(
            id = "lst-3", name = "Pro Skill", category = MarketplaceCategory.SKILLS,
            author = "Acme", description = "Pro skill pack.",
            version = "1.0.0",
            artifactUrl = "https://example.com/skill.zip"
        )
        val item = listing.toMarketplaceItem()
        assertEquals("lst-3", item.id)
        assertEquals("Pro Skill", item.name)
        assertEquals("Acme", item.author)
        assertEquals("https://example.com/skill.zip", item.installUrl)
    }

    @Test
    fun remoteListingReadsLegacySellerNameAsAuthor() {
        val json = JSONObject()
            .put("id", "lst-9")
            .put("name", "Legacy")
            .put("category", "SKILLS")
            .put("sellerName", "Old Registry Author")
            .put("description", "d")
            .put("version", "1.0.0")
        assertEquals("Old Registry Author", RemoteListing.fromJson(json).author)
    }

    @Test
    fun buildSearchPathEncodesParams() {
        val path = buildSearchPath(
            MarketplaceSearchQuery(
                query = "postgres mcp",
                category = MarketplaceCategory.MCP_SERVERS,
                sortBy = SearchSort.RATING
            )
        )
        assertTrue(path.startsWith("/api/v1/listings/search?"))
        assertTrue(path.contains("q=postgres+mcp") || path.contains("q=postgres%20mcp"))
        assertTrue(path.contains("category=MCP_SERVERS"))
        assertTrue(path.contains("sort=RATING"))
    }

    @Test
    fun buildSearchPathWithEmptyQueryOmitsQ() {
        val path = buildSearchPath(MarketplaceSearchQuery())
        assertFalse(path.contains("q="))
        assertTrue(path.contains("sort=RELEVANCE"))
    }
}
