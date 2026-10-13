package com.codeair.mhe.marketplace

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire protocol for the Mobile Harness Extended marketplace registry (Milestone 13).
 *
 * Base URL: the registry's `registryUrl` (e.g. https://marketplace.example.com).
 * Auth: buyer/seller calls use `Authorization: Bearer <token>`; discovery calls
 * are public. A blank registry URL is never contacted — the clients refuse with
 * NOT_CONFIGURED instead.
 *
 * Endpoints (all JSON unless noted):
 * - GET    /api/v1/listings                          -> { "listings": [ {...} ] } (200)
 * - GET    /api/v1/listings/search?q=..&category=..&maxPriceCents=..&sort=.. -> { "listings": [ {...} ] } (200)
 * - GET    /api/v1/listings/{id}                     -> { "listing": {...} } (200 / 404)
 * - POST   /api/v1/sellers                           -> { "seller": {...}, "sellerToken": "..." } (201)
 * - POST   /api/v1/listings                          -> { "listing": {...} } (201, Bearer seller token)
 * - PATCH  /api/v1/listings/{id}                     -> { "listing": {...} } (200, Bearer seller token)
 * - POST   /api/v1/listings/{id}/unpublish           -> { "listing": {...} } (200, Bearer seller token)
 * - POST   /api/v1/orders                            -> { "order": {...} } (201, Bearer buyer token)
 * - GET    /api/v1/orders/{id}                       -> { "order": {...} } (200, Bearer buyer token)
 * - GET    /api/v1/entitlements/{token}              -> { "valid": true, "listingId": "...", "expiresAt": n } (200)
 * - GET    /api/v1/sellers/{id}/sales                -> { "sales": [ {...} ], "totalRevenueCents": n, "payoutPendingCents": n } (200, Bearer seller token)
 */

/** Minimal HTTP request/response pair used by the injectable transport seam. */
data class MarketplaceTransportRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

data class MarketplaceTransportResponse(
    val statusCode: Int,
    val body: String
)

/**
 * Injectable HTTP transport seam. Production implementation uses HttpURLConnection;
 * tests use a fake. Never throws for transport errors — encodes them in the response
 * (statusCode = -1) so callers stay honest.
 */
interface MarketplaceHttpTransport {
    fun send(baseUrl: String, request: MarketplaceTransportRequest): MarketplaceTransportResponse
}

/** Production transport over HttpURLConnection. Pure JVM, no Android dependency. */
class UrlConnectionMarketplaceTransport(
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 30000
) : MarketplaceHttpTransport {

    override fun send(baseUrl: String, request: MarketplaceTransportRequest): MarketplaceTransportResponse {
        return try {
            val url = java.net.URL(baseUrl.trimEnd('/') + request.path)
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = request.method
                instanceFollowRedirects = true
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (request.body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
                }
                connect()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            conn.disconnect()
            MarketplaceTransportResponse(code, text)
        } catch (e: Exception) {
            MarketplaceTransportResponse(-1, "transport error: ${e.message}")
        }
    }
}

/** Price of a marketplace listing in minor currency units. */
data class MarketplacePrice(
    val cents: Long,
    val currency: String = "USD"
) {
    val isFree: Boolean get() = cents <= 0

    fun format(): String {
        if (isFree) return "Free"
        val major = cents / 100
        val minor = (cents % 100).toString().padStart(2, '0')
        return "$currency $major.$minor"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("cents", cents)
        .put("currency", currency)

    companion object {
        fun fromJson(json: JSONObject): MarketplacePrice = MarketplacePrice(
            cents = json.optLong("cents", 0),
            currency = json.optString("currency", "USD").ifBlank { "USD" }
        )
    }
}

enum class ListingStatus {
    DRAFT, PUBLISHED, UNPUBLISHED, REJECTED;

    companion object {
        fun parse(raw: String?): ListingStatus = when (raw?.uppercase()) {
            "PUBLISHED" -> PUBLISHED
            "UNPUBLISHED" -> UNPUBLISHED
            "REJECTED" -> REJECTED
            else -> DRAFT
        }
    }
}

/**
 * A listing as published on the remote registry. Maps 1:1 to the registry's
 * listing JSON; [toMarketplaceItem] bridges it into the local install flow.
 */
data class RemoteListing(
    val id: String,
    val name: String,
    val category: MarketplaceCategory,
    val sellerId: String,
    val sellerName: String,
    val description: String,
    val version: String,
    val price: MarketplacePrice,
    val rating: Double = 0.0,
    val downloads: Int = 0,
    val artifactUrl: String = "",
    val sha256: String = "",
    val tags: List<String> = emptyList(),
    val publishedAt: Long = 0,
    val status: ListingStatus = ListingStatus.PUBLISHED
) {
    fun toMarketplaceItem(): MarketplaceItem = MarketplaceItem(
        id = id,
        name = name,
        category = category,
        author = sellerName,
        description = description,
        rating = rating,
        downloads = downloads,
        installUrl = artifactUrl,
        tags = tags,
        priceCents = price.cents
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("category", category.name)
        put("sellerId", sellerId)
        put("sellerName", sellerName)
        put("description", description)
        put("version", version)
        put("price", price.toJson())
        put("rating", rating)
        put("downloads", downloads)
        put("artifactUrl", artifactUrl)
        put("sha256", sha256)
        put("tags", JSONArray(tags))
        put("publishedAt", publishedAt)
        put("status", status.name)
    }

    companion object {
        fun fromJson(json: JSONObject): RemoteListing {
            val category = runCatching {
                MarketplaceCategory.valueOf(json.optString("category", "SKILLS"))
            }.getOrDefault(MarketplaceCategory.SKILLS)
            val tags = mutableListOf<String>()
            val tagsArr = json.optJSONArray("tags")
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) tags.add(tagsArr.optString(i))
            }
            return RemoteListing(
                id = json.getString("id"),
                name = json.optString("name"),
                category = category,
                sellerId = json.optString("sellerId"),
                sellerName = json.optString("sellerName"),
                description = json.optString("description"),
                version = json.optString("version", "1.0.0"),
                price = MarketplacePrice.fromJson(json.optJSONObject("price") ?: JSONObject()),
                rating = json.optDouble("rating", 0.0),
                downloads = json.optInt("downloads", 0),
                artifactUrl = json.optString("artifactUrl"),
                sha256 = json.optString("sha256"),
                tags = tags,
                publishedAt = json.optLong("publishedAt", 0),
                status = ListingStatus.parse(json.optString("status"))
            )
        }

        fun listFromJsonArray(arr: JSONArray): List<RemoteListing> {
            val out = mutableListOf<RemoteListing>()
            for (i in 0 until arr.length()) {
                out.add(fromJson(arr.getJSONObject(i)))
            }
            return out
        }
    }
}

/** A seller account on the registry. */
data class SellerProfile(
    val sellerId: String,
    val displayName: String,
    val email: String,
    val verified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sellerId", sellerId)
        .put("displayName", displayName)
        .put("email", email)
        .put("verified", verified)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(json: JSONObject): SellerProfile = SellerProfile(
            sellerId = json.getString("sellerId"),
            displayName = json.optString("displayName"),
            email = json.optString("email"),
            verified = json.optBoolean("verified", false),
            createdAt = json.optLong("createdAt", System.currentTimeMillis())
        )
    }
}

enum class OrderStatus {
    PENDING, PAID, FAILED, REFUNDED;

    val isTerminal: Boolean get() = this != PENDING

    companion object {
        fun parse(raw: String?): OrderStatus = when (raw?.uppercase()) {
            "PAID", "COMPLETED", "SUCCEEDED" -> PAID
            "FAILED", "CANCELLED", "CANCELED", "EXPIRED" -> FAILED
            "REFUNDED" -> REFUNDED
            else -> PENDING
        }
    }
}

/** A purchase order. Paid orders carry a checkout URL; completed orders a receipt token. */
data class PurchaseOrder(
    val orderId: String,
    val listingId: String,
    val buyerId: String,
    val status: OrderStatus,
    val priceCents: Long,
    val currency: String,
    val checkoutUrl: String = "",
    val receiptToken: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromJson(json: JSONObject): PurchaseOrder = PurchaseOrder(
            orderId = json.getString("orderId"),
            listingId = json.optString("listingId"),
            buyerId = json.optString("buyerId"),
            status = OrderStatus.parse(json.optString("status")),
            priceCents = json.optLong("priceCents", 0),
            currency = json.optString("currency", "USD").ifBlank { "USD" },
            checkoutUrl = json.optString("checkoutUrl"),
            receiptToken = json.optString("receiptToken"),
            createdAt = json.optLong("createdAt", System.currentTimeMillis())
        )
    }
}

enum class EntitlementVerdict { VALID, INVALID, EXPIRED }

/** Server-side verdict for an entitlement token. */
data class EntitlementCheck(
    val verdict: EntitlementVerdict,
    val listingId: String = "",
    val expiresAt: Long = 0
)

/** One completed sale from the seller's point of view. */
data class SalesRecord(
    val orderId: String,
    val listingId: String,
    val buyerId: String,
    val priceCents: Long,
    val currency: String,
    val purchasedAt: Long,
    val refunded: Boolean = false
) {
    companion object {
        fun fromJson(json: JSONObject): SalesRecord = SalesRecord(
            orderId = json.optString("orderId"),
            listingId = json.optString("listingId"),
            buyerId = json.optString("buyerId"),
            priceCents = json.optLong("priceCents", 0),
            currency = json.optString("currency", "USD").ifBlank { "USD" },
            purchasedAt = json.optLong("purchasedAt", 0),
            refunded = json.optBoolean("refunded", false)
        )
    }
}

/** Seller sales report: line items plus revenue and pending-payout totals. */
data class SellerSalesReport(
    val sellerId: String,
    val sales: List<SalesRecord>,
    val totalRevenueCents: Long,
    val payoutPendingCents: Long
)

/** Discovery query: free text plus optional category / price-cap / sort filters. */
data class MarketplaceSearchQuery(
    val query: String = "",
    val category: MarketplaceCategory? = null,
    val maxPriceCents: Long? = null,
    val sortBy: SearchSort = SearchSort.RELEVANCE
)

enum class SearchSort { RELEVANCE, RATING, DOWNLOADS, PRICE_ASC, PRICE_DESC }

/** URL-encodes a single query parameter value. */
internal fun encodeParam(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

/** Builds the registry search path from a query. Pure function, unit-tested. */
fun buildSearchPath(query: MarketplaceSearchQuery): String {
    val params = mutableListOf<String>()
    if (query.query.isNotBlank()) params += "q=" + encodeParam(query.query.trim())
    query.category?.let { params += "category=" + encodeParam(it.name) }
    query.maxPriceCents?.let { params += "maxPriceCents=$it" }
    params += "sort=" + encodeParam(query.sortBy.name)
    return "/api/v1/listings/search" + (if (params.isEmpty()) "" else "?" + params.joinToString("&"))
}
