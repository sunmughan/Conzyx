package com.jarves.mh.marketplace

import com.jarves.mh.core.result.OperationStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MarketplaceSellerCenterTest {

    private lateinit var transport: FakeMarketplaceTransport
    private lateinit var center: MarketplaceSellerCenter
    private val config = MarketplaceRegistryConfig("https://market.example.com")
    private val sellerToken = "seller-token-abc"

    private fun validDraft() = ListingDraft(
        name = "Postgres MCP Pro",
        category = MarketplaceCategory.MCP_SERVERS,
        description = "A production-ready Postgres MCP server with pooling.",
        version = "2.1.0",
        priceCents = 999,
        currency = "USD",
        artifactUrl = "https://example.com/postgres-pro.zip",
        sha256 = "b".repeat(64),
        tags = listOf("sql", "mcp")
    )

    private fun listingBody(id: String, status: String = "PUBLISHED"): String =
        JSONObject().put(
            "listing", RemoteListing(
                id = id, name = "Postgres MCP Pro", category = MarketplaceCategory.MCP_SERVERS,
                sellerId = "seller-1", sellerName = "DataWorks",
                description = "A production-ready Postgres MCP server with pooling.",
                version = "2.1.0", price = MarketplacePrice(999),
                artifactUrl = "https://example.com/postgres-pro.zip",
                sha256 = "b".repeat(64), status = ListingStatus.parse(status)
            ).toJson()
        ).toString()

    @Before
    fun setUp() {
        transport = FakeMarketplaceTransport()
        center = MarketplaceSellerCenter(config, transport)
    }

    @Test
    fun blankRegistryUrlIsNotConfiguredWithoutNetworkCall() {
        val unconfigured = MarketplaceSellerCenter(MarketplaceRegistryConfig(""), transport)
        val result = unconfigured.registerSeller("Acme", "acme@example.com")
        assertEquals(OperationStatus.NOT_CONFIGURED, result.result.status)
        assertEquals(0, transport.callCount())
    }

    @Test
    fun blankSellerTokenIsNotConfiguredWithoutNetworkCall() {
        val result = center.publishListing("  ", validDraft())
        assertEquals(OperationStatus.NOT_CONFIGURED, result.result.status)
        assertEquals(0, transport.callCount())
    }

    @Test
    fun registerSellerSuccess() {
        transport.respond(
            "POST", "/api/v1/sellers", 201,
            JSONObject()
                .put("seller", SellerProfile("seller-1", "Acme", "acme@example.com").toJson())
                .put("sellerToken", "tok-seller-1")
                .toString()
        )
        val result = center.registerSeller("Acme", "acme@example.com")
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals("seller-1", result.seller!!.sellerId)
        assertEquals("tok-seller-1", result.sellerToken)
    }

    @Test
    fun registerSellerRejectsBadEmailLocallyWithoutNetworkCall() {
        val result = center.registerSeller("Acme", "not-an-email")
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error!!.contains("email"))
        assertEquals(0, transport.callCount())
    }

    @Test
    fun registerSellerRejectsBlankNameLocally() {
        val result = center.registerSeller(" ", "acme@example.com")
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertEquals(0, transport.callCount())
    }

    @Test
    fun registerSellerMissingTokenIsFailed() {
        transport.respond(
            "POST", "/api/v1/sellers", 201,
            JSONObject()
                .put("seller", SellerProfile("seller-1", "Acme", "acme@example.com").toJson())
                .toString() // no sellerToken
        )
        val result = center.registerSeller("Acme", "acme@example.com")
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error!!.contains("token"))
    }

    @Test
    fun publishListingSuccess() {
        transport.respond("POST", "/api/v1/listings", 201, listingBody("lst-new"))
        val result = center.publishListing(sellerToken, validDraft())
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals("lst-new", result.listing!!.id)
        assertEquals("Bearer $sellerToken", transport.lastRequest()!!.headers["Authorization"])
    }

    @Test
    fun publishListingInvalidDraftNeverReachesNetwork() {
        val bad = validDraft().copy(name = "x", priceCents = -1)
        val result = center.publishListing(sellerToken, bad)
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error!!.contains("invalid"))
        assertEquals(0, transport.callCount())
    }

    @Test
    fun publishListingServerErrorIsFailed() {
        transport.respond("POST", "/api/v1/listings", 422, """{"error":"duplicate name"}""")
        val result = center.publishListing(sellerToken, validDraft())
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertEquals(422, result.result.exitCode)
    }

    @Test
    fun updateListingSuccess() {
        transport.respond("PATCH", "/api/v1/listings/lst-1", 200, listingBody("lst-1"))
        val result = center.updateListing(sellerToken, "lst-1", validDraft())
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals("lst-1", result.listing!!.id)
    }

    @Test
    fun updateListing404IsFailed() {
        transport.respond("PATCH", "/api/v1/listings/gone", 404, "{}")
        val result = center.updateListing(sellerToken, "gone", validDraft())
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertEquals(404, result.result.exitCode)
    }

    @Test
    fun updateListingBlankIdIsFailedWithoutNetworkCall() {
        val result = center.updateListing(sellerToken, "  ", validDraft())
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertEquals(0, transport.callCount())
    }

    @Test
    fun unpublishListingSuccess() {
        transport.respond("POST", "/api/v1/listings/lst-1/unpublish", 200, listingBody("lst-1", "UNPUBLISHED"))
        val result = center.unpublishListing(sellerToken, "lst-1")
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(ListingStatus.UNPUBLISHED, result.listing!!.status)
    }

    @Test
    fun unpublishListing404IsFailed() {
        transport.respond("POST", "/api/v1/listings/gone/unpublish", 404, "{}")
        val result = center.unpublishListing(sellerToken, "gone")
        assertEquals(OperationStatus.FAILED, result.result.status)
    }

    @Test
    fun salesReportSuccess() {
        val sales = JSONArray()
            .put(
                JSONObject().put("orderId", "ord-1").put("listingId", "lst-1")
                    .put("buyerId", "b-1").put("priceCents", 999).put("currency", "USD")
                    .put("purchasedAt", 111L).put("refunded", false)
            )
            .put(
                JSONObject().put("orderId", "ord-2").put("listingId", "lst-1")
                    .put("buyerId", "b-2").put("priceCents", 999).put("currency", "USD")
                    .put("purchasedAt", 222L).put("refunded", true)
            )
        transport.respond(
            "GET", "/api/v1/sellers/seller-1/sales", 200,
            JSONObject().put("sales", sales)
                .put("totalRevenueCents", 1998)
                .put("payoutPendingCents", 999)
                .toString()
        )
        val result = center.getSalesReport(sellerToken, "seller-1")
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        val report = result.report!!
        assertEquals(2, report.sales.size)
        assertEquals(1998L, report.totalRevenueCents)
        assertEquals(999L, report.payoutPendingCents)
        assertTrue(report.sales[1].refunded)
    }

    @Test
    fun salesReportTransportErrorIsFailed() {
        transport.respondTransportError("GET", "/api/v1/sellers/seller-1/sales")
        val result = center.getSalesReport(sellerToken, "seller-1")
        assertEquals(OperationStatus.FAILED, result.result.status)
    }
}
