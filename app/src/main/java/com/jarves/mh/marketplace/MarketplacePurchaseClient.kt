package com.jarves.mh.marketplace

import com.jarves.mh.core.result.OperationResult
import com.jarves.mh.core.result.OperationStatus
import org.json.JSONObject
import java.io.File

/** Typed result of order creation. */
data class OrderCreateResult(
    val result: OperationResult,
    val order: PurchaseOrder?
)

/** Typed result of an order-status poll. */
data class OrderPollResult(
    val result: OperationResult,
    val order: PurchaseOrder?
)

/** Typed result of an entitlement check. */
data class EntitlementCheckResult(
    val result: OperationResult,
    val check: EntitlementCheck?
)

/**
 * Real marketplace purchasing client (Milestone 13).
 *
 * Flow: [createOrder] (free items complete immediately as PAID; paid items come
 * back PENDING with a checkout URL) -> buyer pays on the checkout page ->
 * [awaitPaid] polls until the order reaches a terminal state -> the registry
 * issues an entitlement token, verified later with [checkEntitlement].
 *
 * Honesty contract: a blank buyer token returns NOT_CONFIGURED without any
 * network call; PAID is reported only when the registry genuinely says so;
 * [awaitPaid] never reports success on timeout — it reports FAILED naming the
 * last observed status.
 */
class MarketplacePurchaseClient(
    private val config: MarketplaceRegistryConfig,
    private val transport: MarketplaceHttpTransport = UrlConnectionMarketplaceTransport()
) {
    private fun requireConfigured(what: String): OperationResult? =
        if (config.registryUrl.isBlank()) {
            OperationResult.notConfigured(
                "Marketplace registry URL is not configured. " +
                    "Set a registry URL before $what — nothing was requested."
            )
        } else null

    private fun requireBuyerToken(buyerToken: String, what: String): OperationResult? =
        if (buyerToken.isBlank()) {
            OperationResult.notConfigured(
                "Buyer token is missing. Refusing $what without buyer credentials — nothing was sent."
            )
        } else null

    private fun checkTransport(res: MarketplaceTransportResponse, what: String): OperationResult? =
        if (res.statusCode == -1) {
            OperationResult.failure(
                error = "Marketplace $what failed: transport error (${res.body}).",
                logs = listOf(res.body)
            )
        } else null

    private fun authHeaders(buyerToken: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $buyerToken")

    private fun parseOrder(body: String): PurchaseOrder = PurchaseOrder.fromJson(
        JSONObject(body).getJSONObject("order")
    )

    /**
     * POST /api/v1/orders — creates a purchase order for [listingId].
     * Free listings complete immediately (PAID + receipt token); paid listings
     * return PENDING with a checkout URL the buyer must visit.
     */
    fun createOrder(buyerToken: String, listingId: String, buyerId: String): OrderCreateResult {
        requireConfigured("creating an order")?.let { return OrderCreateResult(it, null) }
        requireBuyerToken(buyerToken, "creating an order")?.let { return OrderCreateResult(it, null) }
        if (listingId.isBlank()) {
            return OrderCreateResult(
                OperationResult.failure("Marketplace order creation failed: listing id is blank."),
                null
            )
        }
        val body = JSONObject()
            .put("listingId", listingId)
            .put("buyerId", buyerId)
            .toString()
        val res = transport.send(
            config.registryUrl,
            MarketplaceTransportRequest("POST", "/api/v1/orders", authHeaders(buyerToken), body)
        )
        checkTransport(res, "order creation")?.let { return OrderCreateResult(it, null) }
        if (res.statusCode != 201) {
            return OrderCreateResult(
                OperationResult.failure(
                    error = "Marketplace order creation failed: HTTP ${res.statusCode}.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                null
            )
        }
        return try {
            val order = parseOrder(res.body)
            OrderCreateResult(
                OperationResult.success(
                    logs = listOf("Order ${order.orderId} created: status=${order.status}."),
                    artifacts = listOf(order.orderId),
                    verificationEvidence = "HTTP 201 with order '${order.orderId}' status=${order.status}"
                ),
                order
            )
        } catch (e: Exception) {
            OrderCreateResult(
                OperationResult.failure("Marketplace order creation failed: invalid response JSON (${e.message})."),
                null
            )
        }
    }

    /** GET /api/v1/orders/{id} — reads the current order status. */
    fun pollOrder(buyerToken: String, orderId: String): OrderPollResult {
        requireConfigured("polling order '$orderId'")?.let { return OrderPollResult(it, null) }
        requireBuyerToken(buyerToken, "polling an order")?.let { return OrderPollResult(it, null) }
        if (orderId.isBlank()) {
            return OrderPollResult(
                OperationResult.failure("Marketplace order poll failed: order id is blank."),
                null
            )
        }
        val res = transport.send(
            config.registryUrl,
            MarketplaceTransportRequest(
                "GET",
                "/api/v1/orders/" + encodeParam(orderId),
                authHeaders(buyerToken)
            )
        )
        checkTransport(res, "order poll")?.let { return OrderPollResult(it, null) }
        if (res.statusCode == 404) {
            return OrderPollResult(
                OperationResult.failure(
                    error = "Marketplace order '$orderId' was not found (HTTP 404).",
                    exitCode = 404
                ),
                null
            )
        }
        if (res.statusCode != 200) {
            return OrderPollResult(
                OperationResult.failure(
                    error = "Marketplace order poll failed: HTTP ${res.statusCode}.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                null
            )
        }
        return try {
            val order = parseOrder(res.body)
            OrderPollResult(
                OperationResult.success(
                    logs = listOf("Order ${order.orderId}: status=${order.status}."),
                    verificationEvidence = "HTTP 200, order status=${order.status}"
                ),
                order
            )
        } catch (e: Exception) {
            OrderPollResult(
                OperationResult.failure("Marketplace order poll failed: invalid response JSON (${e.message})."),
                null
            )
        }
    }

    /**
     * Polls [pollOrder] until the order leaves PENDING or [timeoutMs] elapses.
     * SUCCESS only when the registry reports PAID; terminal non-PAID states and
     * timeouts are FAILED, naming the last observed status honestly.
     */
    fun awaitPaid(
        buyerToken: String,
        orderId: String,
        timeoutMs: Long = 5 * 60 * 1000,
        pollIntervalMs: Long = 5000,
        clock: () -> Long = System::currentTimeMillis,
        sleeper: (Long) -> Unit = Thread::sleep
    ): OrderPollResult {
        val deadline = clock() + timeoutMs
        var last: OrderPollResult? = null
        while (clock() < deadline) {
            val polled = pollOrder(buyerToken, orderId)
            last = polled
            if (polled.result.status != OperationStatus.SUCCESS || polled.order == null) {
                return polled // transport/config failure: report it, do not keep polling
            }
            when (polled.order.status) {
                OrderStatus.PAID -> return OrderPollResult(
                    OperationResult.success(
                        logs = listOf("Order $orderId is PAID."),
                        artifacts = listOf(orderId),
                        verificationEvidence = "registry reports order status=PAID"
                    ),
                    polled.order
                )
                OrderStatus.PENDING -> { /* keep waiting */ }
                else -> return OrderPollResult(
                    OperationResult.failure(
                        error = "Marketplace order $orderId ended as ${polled.order.status} — not paid.",
                        logs = listOf("Final status: ${polled.order.status}.")
                    ),
                    polled.order
                )
            }
            val remaining = deadline - clock()
            if (remaining <= 0) break
            sleeper(minOf(pollIntervalMs, remaining))
        }
        val lastStatus = last?.order?.status?.name ?: "unknown"
        return OrderPollResult(
            OperationResult.failure(
                error = "Marketplace order $orderId did not reach PAID within ${timeoutMs}ms " +
                    "(last status: $lastStatus). The order may still complete — check its status before retrying payment.",
                logs = listOf("Last observed status: $lastStatus.")
            ),
            last?.order
        )
    }

    /**
     * GET /api/v1/entitlements/{token} — verifies a purchase entitlement token
     * with the registry. A blank token is a caller error (FAILED, not a network
     * call); an unreachable/invalid token reports INVALID honestly.
     */
    fun checkEntitlement(entitlementToken: String): EntitlementCheckResult {
        requireConfigured("checking an entitlement")?.let { return EntitlementCheckResult(it, null) }
        if (entitlementToken.isBlank()) {
            return EntitlementCheckResult(
                OperationResult.failure("Marketplace entitlement check failed: token is blank."),
                null
            )
        }
        val res = transport.send(
            config.registryUrl,
            MarketplaceTransportRequest("GET", "/api/v1/entitlements/" + encodeParam(entitlementToken))
        )
        checkTransport(res, "entitlement check")?.let { return EntitlementCheckResult(it, null) }
        if (res.statusCode == 404) {
            return EntitlementCheckResult(
                OperationResult.success(
                    logs = listOf("Entitlement token is unknown to the registry (HTTP 404)."),
                    verificationEvidence = "HTTP 404 -> INVALID"
                ),
                EntitlementCheck(EntitlementVerdict.INVALID)
            )
        }
        if (res.statusCode != 200) {
            return EntitlementCheckResult(
                OperationResult.failure(
                    error = "Marketplace entitlement check failed: HTTP ${res.statusCode}.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                null
            )
        }
        return try {
            val obj = JSONObject(res.body)
            val verdict = when {
                !obj.optBoolean("valid", false) -> EntitlementVerdict.INVALID
                obj.optLong("expiresAt", 0) in 1..System.currentTimeMillis() -> EntitlementVerdict.EXPIRED
                else -> EntitlementVerdict.VALID
            }
            EntitlementCheckResult(
                OperationResult.success(
                    logs = listOf("Entitlement verdict: $verdict."),
                    verificationEvidence = "registry entitlement lookup -> $verdict"
                ),
                EntitlementCheck(
                    verdict = verdict,
                    listingId = obj.optString("listingId"),
                    expiresAt = obj.optLong("expiresAt", 0)
                )
            )
        } catch (e: Exception) {
            EntitlementCheckResult(
                OperationResult.failure("Marketplace entitlement check failed: invalid response JSON (${e.message})."),
                null
            )
        }
    }
}

/** Durable record of a granted purchase entitlement. */
data class GrantedEntitlement(
    val token: String,
    val listingId: String,
    val grantedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = 0 // 0 = never expires
) {
    fun toJson(): JSONObject = JSONObject()
        .put("token", token)
        .put("listingId", listingId)
        .put("grantedAt", grantedAt)
        .put("expiresAt", expiresAt)

    companion object {
        fun fromJson(json: JSONObject): GrantedEntitlement = GrantedEntitlement(
            token = json.getString("token"),
            listingId = json.optString("listingId"),
            grantedAt = json.optLong("grantedAt", System.currentTimeMillis()),
            expiresAt = json.optLong("expiresAt", 0)
        )
    }
}

/** Storage seam for entitlements: file-backed in production, in-memory in tests. */
interface EntitlementStorage {
    fun loadAll(): Map<String, GrantedEntitlement> // token -> entitlement
    fun saveAll(entitlements: Map<String, GrantedEntitlement>)
}

/** In-memory storage for tests. */
class InMemoryEntitlementStorage : EntitlementStorage {
    private var data: Map<String, GrantedEntitlement> = emptyMap()
    override fun loadAll(): Map<String, GrantedEntitlement> = data
    override fun saveAll(entitlements: Map<String, GrantedEntitlement>) {
        data = entitlements.toMap()
    }
}

/** File-backed storage: a single JSON file under [dir]. Pure JVM. */
class FileEntitlementStorage(dir: File) : EntitlementStorage {
    private val file: File = File(dir.apply { mkdirs() }, "entitlements.json")
    private val lock = Any()

    override fun loadAll(): Map<String, GrantedEntitlement> = synchronized(lock) {
        if (!file.isFile) return emptyMap()
        return try {
            val obj = JSONObject(file.readText())
            val out = mutableMapOf<String, GrantedEntitlement>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                runCatching { GrantedEntitlement.fromJson(obj.getJSONObject(key)) }
                    .getOrNull()?.let { out[key] = it }
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun saveAll(entitlements: Map<String, GrantedEntitlement>) = synchronized(lock) {
        val obj = JSONObject()
        entitlements.forEach { (token, e) -> obj.put(token, e.toJson()) }
        file.writeText(obj.toString(2))
    }
}

/**
 * Durable local entitlement ledger: which listings this device is entitled to
 * install. Entitlements are granted after a verified PAID order; [isEntitled]
 * also honors expiry. Pure JVM.
 */
class EntitlementStore(
    private val storage: EntitlementStorage = InMemoryEntitlementStorage(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private var entitlements: MutableMap<String, GrantedEntitlement> =
        storage.loadAll().toMutableMap()

    fun grant(entitlement: GrantedEntitlement) = synchronized(lock) {
        entitlements[entitlement.token] = entitlement
        storage.saveAll(entitlements)
    }

    fun revoke(token: String): Boolean = synchronized(lock) {
        val removed = entitlements.remove(token) != null
        if (removed) storage.saveAll(entitlements)
        removed
    }

    fun isEntitled(listingId: String): Boolean = synchronized(lock) {
        val now = clock()
        entitlements.values.any { e ->
            e.listingId == listingId && (e.expiresAt == 0L || e.expiresAt > now)
        }
    }

    fun list(): List<GrantedEntitlement> = synchronized(lock) {
        entitlements.values.toList()
    }

    /** Drops expired entitlements; returns how many were removed. */
    fun purgeExpired(): Int = synchronized(lock) {
        val now = clock()
        val expired = entitlements.filterValues { it.expiresAt in 1..now }.keys
        expired.forEach { entitlements.remove(it) }
        if (expired.isNotEmpty()) storage.saveAll(entitlements)
        expired.size
    }
}

/**
 * Purchase gate for installation: free items always install; paid items install
 * only with a live entitlement. Pure JVM — the single decision point the UI and
 * install flow consult before materializing a paid package.
 */
object MarketplacePurchaseGate {
    fun canInstall(item: MarketplaceItem, store: EntitlementStore): Boolean {
        if (item.priceCents <= 0) return true
        return store.isEntitled(item.id)
    }

    fun installRefusalReason(item: MarketplaceItem): String =
        "Purchase required: '${item.name}' is a paid listing. " +
            "Complete the purchase first, then install."
}
