package com.codeair.mhe.marketplace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceProtocolTest {

    @Test
    fun priceFormatsFreeAndPaid() {
        assertTrue(MarketplacePrice(0).isFree)
        assertEquals("Free", MarketplacePrice(0).format())
        assertEquals("USD 4.99", MarketplacePrice(499).format())
        assertEquals("INR 1250.00", MarketplacePrice(125000, "INR").format())
    }

    @Test
    fun priceJsonRoundTrip() {
        val price = MarketplacePrice(1999, "EUR")
        val back = MarketplacePrice.fromJson(price.toJson())
        assertEquals(price, back)
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
    fun orderStatusTerminalStates() {
        assertFalse(OrderStatus.PENDING.isTerminal)
        assertTrue(OrderStatus.PAID.isTerminal)
        assertTrue(OrderStatus.FAILED.isTerminal)
        assertTrue(OrderStatus.REFUNDED.isTerminal)
        assertEquals(OrderStatus.PAID, OrderStatus.parse("completed"))
        assertEquals(OrderStatus.FAILED, OrderStatus.parse("canceled"))
        assertEquals(OrderStatus.PENDING, OrderStatus.parse("processing"))
    }

    @Test
    fun remoteListingJsonRoundTrip() {
        val listing = RemoteListing(
            id = "lst-1",
            name = "Postgres MCP Pro",
            category = MarketplaceCategory.MCP_SERVERS,
            sellerId = "seller-9",
            sellerName = "DataWorks",
            description = "A production Postgres MCP server.",
            version = "2.1.0",
            price = MarketplacePrice(999, "USD"),
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
            sellerId = "s", sellerName = "S", description = "d",
            version = "1.0.0", price = MarketplacePrice(0)
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
            sellerId = "s", sellerName = "Acme", description = "Pro skill pack.",
            version = "1.0.0", price = MarketplacePrice(499),
            artifactUrl = "https://example.com/skill.zip"
        )
        val item = listing.toMarketplaceItem()
        assertEquals("lst-3", item.id)
        assertEquals("Pro Skill", item.name)
        assertEquals("Acme", item.author)
        assertEquals(499L, item.priceCents)
        assertEquals("https://example.com/skill.zip", item.installUrl)
    }

    @Test
    fun sellerProfileJsonRoundTrip() {
        val seller = SellerProfile("s-1", "Acme", "acme@example.com", true, 42L)
        val back = SellerProfile.fromJson(seller.toJson())
        assertEquals(seller, back)
    }

    @Test
    fun purchaseOrderParses() {
        val json = JSONObject()
            .put("orderId", "ord-1")
            .put("listingId", "lst-1")
            .put("buyerId", "buyer-7")
            .put("status", "PENDING")
            .put("priceCents", 499)
            .put("currency", "USD")
            .put("checkoutUrl", "https://pay.example.com/c/1")
        val order = PurchaseOrder.fromJson(json)
        assertEquals("ord-1", order.orderId)
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals("https://pay.example.com/c/1", order.checkoutUrl)
    }

    @Test
    fun buildSearchPathEncodesParams() {
        val path = buildSearchPath(
            MarketplaceSearchQuery(
                query = "postgres mcp",
                category = MarketplaceCategory.MCP_SERVERS,
                maxPriceCents = 1000,
                sortBy = SearchSort.RATING
            )
        )
        assertTrue(path.startsWith("/api/v1/listings/search?"))
        assertTrue(path.contains("q=postgres+mcp") || path.contains("q=postgres%20mcp"))
        assertTrue(path.contains("category=MCP_SERVERS"))
        assertTrue(path.contains("maxPriceCents=1000"))
        assertTrue(path.contains("sort=RATING"))
    }

    @Test
    fun buildSearchPathWithEmptyQueryOmitsQ() {
        val path = buildSearchPath(MarketplaceSearchQuery())
        assertFalse(path.contains("q="))
        assertTrue(path.contains("sort=RELEVANCE"))
    }
}
