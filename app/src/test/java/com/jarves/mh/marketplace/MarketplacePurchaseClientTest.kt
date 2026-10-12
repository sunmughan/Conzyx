package com.jarves.mh.marketplace

import com.jarves.mh.core.result.OperationStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MarketplacePurchaseClientTest {

    private lateinit var transport: FakeMarketplaceTransport
    private lateinit var client: MarketplacePurchaseClient
    private val config = MarketplaceRegistryConfig("https://market.example.com")
    private val buyerToken = "buyer-token-123"

    private fun orderBody(
        orderId: String,
        status: String,
        priceCents: Long = 499,
        checkoutUrl: String = "",
        receiptToken: String = ""
    ): String = JSONObject()
        .put(
            "order", JSONObject()
                .put("orderId", orderId)
                .put("listingId", "lst-1")
                .put("buyerId", "buyer-7")
                .put("status", status)
                .put("priceCents", priceCents)
                .put("currency", "USD")
                .put("checkoutUrl", checkoutUrl)
                .put("receiptToken", receiptToken)
        ).toString()

    @Before
    fun setUp() {
        transport = FakeMarketplaceTransport()
        client = MarketplacePurchaseClient(config, transport)
    }

    @Test
    fun blankRegistryUrlIsNotConfiguredWithoutNetworkCall() {
        val unconfigured = MarketplacePurchaseClient(MarketplaceRegistryConfig(""), transport)
        val result = unconfigured.createOrder(buyerToken, "lst-1", "buyer-7")
        assertEquals(OperationStatus.NOT_CONFIGURED, result.result.status)
        assertEquals(0, transport.callCount())
    }

    @Test
    fun blankBuyerTokenIsNotConfiguredWithoutNetworkCall() {
        val result = client.createOrder("  ", "lst-1", "buyer-7")
        assertEquals(OperationStatus.NOT_CONFIGURED, result.result.status)
        assertEquals(0, transport.callCount())
    }

    @Test
    fun createOrderFreeListingCompletesPaidImmediately() {
        transport.respond(
            "POST", "/api/v1/orders", 201,
            orderBody("ord-free", "PAID", priceCents = 0, receiptToken = "ent-free-1")
        )
        val result = client.createOrder(buyerToken, "lst-free", "buyer-7")
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(OrderStatus.PAID, result.order!!.status)
        assertEquals("ent-free-1", result.order!!.receiptToken)
        assertEquals("Bearer $buyerToken", transport.lastRequest()!!.headers["Authorization"])
    }

    @Test
    fun createOrderPaidListingReturnsPendingWithCheckoutUrl() {
        transport.respond(
            "POST", "/api/v1/orders", 201,
            orderBody("ord-1", "PENDING", checkoutUrl = "https://pay.example.com/c/1")
        )
        val result = client.createOrder(buyerToken, "lst-1", "buyer-7")
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(OrderStatus.PENDING, result.order!!.status)
        assertEquals("https://pay.example.com/c/1", result.order!!.checkoutUrl)
    }

    @Test
    fun createOrderNon201IsFailed() {
        transport.respond("POST", "/api/v1/orders", 402, """{"error":"payment required"}""")
        val result = client.createOrder(buyerToken, "lst-1", "buyer-7")
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertEquals(402, result.result.exitCode)
    }

    @Test
    fun createOrderTransportErrorIsFailed() {
        transport.respondTransportError("POST", "/api/v1/orders")
        val result = client.createOrder(buyerToken, "lst-1", "buyer-7")
        assertEquals(OperationStatus.FAILED, result.result.status)
    }

    @Test
    fun pollOrder404IsFailed() {
        transport.respond("GET", "/api/v1/orders/nope", 404, "{}")
        val result = client.pollOrder(buyerToken, "nope")
        assertEquals(OperationStatus.FAILED, result.result.status)
    }

    @Test
    fun awaitPaidSucceedsWhenOrderBecomesPaid() {
        var polls = 0
        val pollingTransport = object : MarketplaceHttpTransport {
            override fun send(baseUrl: String, request: MarketplaceTransportRequest): MarketplaceTransportResponse {
                polls++
                val status = if (polls >= 3) "PAID" else "PENDING"
                return MarketplaceTransportResponse(200, orderBody("ord-1", status))
            }
        }
        val pollingClient = MarketplacePurchaseClient(config, pollingTransport)
        var now = 0L
        val result = pollingClient.awaitPaid(
            buyerToken, "ord-1",
            timeoutMs = 60_000, pollIntervalMs = 1_000,
            clock = { now }, sleeper = { ms -> now += ms }
        )
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(OrderStatus.PAID, result.order!!.status)
        assertEquals(3, polls)
    }

    @Test
    fun awaitPaidFailsHonestlyOnTimeout() {
        val pendingTransport = object : MarketplaceHttpTransport {
            override fun send(baseUrl: String, request: MarketplaceTransportRequest): MarketplaceTransportResponse =
                MarketplaceTransportResponse(200, orderBody("ord-1", "PENDING"))
        }
        val pendingClient = MarketplacePurchaseClient(config, pendingTransport)
        var now = 0L
        val result = pendingClient.awaitPaid(
            buyerToken, "ord-1",
            timeoutMs = 3_000, pollIntervalMs = 1_000,
            clock = { now }, sleeper = { ms -> now += ms }
        )
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error!!.contains("PENDING"))
        // The order object is still returned so the caller can keep polling later.
        assertEquals(OrderStatus.PENDING, result.order!!.status)
    }

    @Test
    fun awaitPaidFailsOnTerminalFailedState() {
        transport.respond("GET", "/api/v1/orders/ord-9", 200, orderBody("ord-9", "FAILED"))
        val result = client.awaitPaid(
            buyerToken, "ord-9",
            timeoutMs = 60_000, pollIntervalMs = 1_000,
            clock = { 0L }, sleeper = {}
        )
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error!!.contains("FAILED"))
    }

    @Test
    fun checkEntitlementValidToken() {
        transport.respond(
            "GET", "/api/v1/entitlements/ent-1", 200,
            """{"valid":true,"listingId":"lst-1","expiresAt":0}"""
        )
        val result = client.checkEntitlement("ent-1")
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(EntitlementVerdict.VALID, result.check!!.verdict)
        assertEquals("lst-1", result.check!!.listingId)
    }

    @Test
    fun checkEntitlementUnknownTokenIsInvalidNotFailure() {
        transport.respond("GET", "/api/v1/entitlements/nope", 404, "{}")
        val result = client.checkEntitlement("nope")
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(EntitlementVerdict.INVALID, result.check!!.verdict)
    }

    @Test
    fun checkEntitlementExpiredToken() {
        transport.respond(
            "GET", "/api/v1/entitlements/ent-old", 200,
            """{"valid":true,"listingId":"lst-1","expiresAt":1}"""
        )
        val result = client.checkEntitlement("ent-old")
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(EntitlementVerdict.EXPIRED, result.check!!.verdict)
    }

    @Test
    fun checkEntitlementBlankTokenIsFailedWithoutNetworkCall() {
        val result = client.checkEntitlement("  ")
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertEquals(0, transport.callCount())
    }

    // ---- EntitlementStore ----

    @Test
    fun entitlementStoreGrantsAndChecks() {
        val store = EntitlementStore(InMemoryEntitlementStorage(), clock = { 1_000L })
        assertFalse(store.isEntitled("lst-1"))
        store.grant(GrantedEntitlement("tok-1", "lst-1"))
        assertTrue(store.isEntitled("lst-1"))
        assertFalse(store.isEntitled("lst-2"))
    }

    @Test
    fun entitlementStoreHonorsExpiry() {
        var now = 1_000L
        val store = EntitlementStore(InMemoryEntitlementStorage(), clock = { now })
        store.grant(GrantedEntitlement("tok-1", "lst-1", expiresAt = 5_000L))
        assertTrue(store.isEntitled("lst-1"))
        now = 6_000L
        assertFalse(store.isEntitled("lst-1"))
    }

    @Test
    fun entitlementStoreRevoke() {
        val store = EntitlementStore(InMemoryEntitlementStorage())
        store.grant(GrantedEntitlement("tok-1", "lst-1"))
        assertTrue(store.revoke("tok-1"))
        assertFalse(store.isEntitled("lst-1"))
        assertFalse(store.revoke("tok-1"))
    }

    @Test
    fun entitlementStorePurgeExpired() {
        var now = 1_000L
        val store = EntitlementStore(InMemoryEntitlementStorage(), clock = { now })
        store.grant(GrantedEntitlement("tok-1", "lst-1", expiresAt = 2_000L))
        store.grant(GrantedEntitlement("tok-2", "lst-2")) // never expires
        now = 9_000L
        assertEquals(1, store.purgeExpired())
        assertFalse(store.isEntitled("lst-1"))
        assertTrue(store.isEntitled("lst-2"))
    }

    @Test
    fun fileEntitlementStoragePersistsAcrossInstances() {
        val dir: File = Files.createTempDirectory("entitlements-test").toFile()
        try {
            val store1 = EntitlementStore(FileEntitlementStorage(dir))
            store1.grant(GrantedEntitlement("tok-1", "lst-1"))
            val store2 = EntitlementStore(FileEntitlementStorage(dir))
            assertTrue(store2.isEntitled("lst-1"))
            assertEquals(1, store2.list().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- Purchase gate ----

    @Test
    fun purchaseGateAllowsFreeItemsWithoutEntitlement() {
        val store = EntitlementStore(InMemoryEntitlementStorage())
        val free = MarketplaceItem(
            id = "free-1", name = "Free", category = MarketplaceCategory.SKILLS,
            author = "A", description = "d", installUrl = "https://x", priceCents = 0
        )
        assertTrue(MarketplacePurchaseGate.canInstall(free, store))
    }

    @Test
    fun purchaseGateBlocksPaidItemsWithoutEntitlement() {
        val store = EntitlementStore(InMemoryEntitlementStorage())
        val paid = MarketplaceItem(
            id = "paid-1", name = "Paid", category = MarketplaceCategory.SKILLS,
            author = "A", description = "d", installUrl = "https://x", priceCents = 499
        )
        assertFalse(MarketplacePurchaseGate.canInstall(paid, store))
        assertTrue(MarketplacePurchaseGate.installRefusalReason(paid).contains("Purchase required"))

        store.grant(GrantedEntitlement("tok-9", "paid-1"))
        assertTrue(MarketplacePurchaseGate.canInstall(paid, store))
    }
}
