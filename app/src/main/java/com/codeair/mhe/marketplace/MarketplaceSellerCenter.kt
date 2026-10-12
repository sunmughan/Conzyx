package com.codeair.mhe.marketplace

import com.codeair.mhe.core.result.OperationResult
import com.codeair.mhe.core.result.OperationStatus
import org.json.JSONObject

/** Typed result of seller registration. */
data class SellerRegistrationResult(
    val result: OperationResult,
    val seller: SellerProfile?,
    val sellerToken: String = ""
)

/** Typed result of a listing publish/update/unpublish call. */
data class ListingPublishResult(
    val result: OperationResult,
    val listing: RemoteListing?
)

/** Typed result of a sales-report fetch. */
data class SalesReportResult(
    val result: OperationResult,
    val report: SellerSalesReport?
)

private val EMAIL_REGEX = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

/**
 * Real seller integration client (Milestone 13): seller registration, listing
 * publish/update/unpublish, and sales reporting against the registry.
 *
 * Honesty contract: a blank seller token returns NOT_CONFIGURED without any
 * network call; drafts are validated locally first — an invalid draft is
 * refused before anything is sent; SUCCESS only when the registry's response
 * parses into a real listing/seller/report.
 */
class MarketplaceSellerCenter(
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

    private fun requireSellerToken(sellerToken: String, what: String): OperationResult? =
        if (sellerToken.isBlank()) {
            OperationResult.notConfigured(
                "Seller token is missing. Refusing $what without seller credentials — nothing was sent."
            )
        } else null

    private fun checkTransport(res: MarketplaceTransportResponse, what: String): OperationResult? =
        if (res.statusCode == -1) {
            OperationResult.failure(
                error = "Marketplace $what failed: transport error (${res.body}).",
                logs = listOf(res.body)
            )
        } else null

    private fun authHeaders(sellerToken: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $sellerToken")

    private fun parseListing(body: String): RemoteListing = RemoteListing.fromJson(
        JSONObject(body).getJSONObject("listing")
    )

    /**
     * POST /api/v1/sellers — registers a seller account. The display name and
     * a well-formed email are validated locally first.
     */
    fun registerSeller(displayName: String, email: String): SellerRegistrationResult {
        requireConfigured("registering a seller")?.let {
            return SellerRegistrationResult(it, null)
        }
        if (displayName.trim().length < 2) {
            return SellerRegistrationResult(
                OperationResult.failure("Seller registration failed: display name must be at least 2 characters."),
                null
            )
        }
        if (!EMAIL_REGEX.matches(email.trim())) {
            return SellerRegistrationResult(
                OperationResult.failure("Seller registration failed: '$email' is not a valid email address."),
                null
            )
        }
        val body = JSONObject()
            .put("displayName", displayName.trim())
            .put("email", email.trim())
            .toString()
        val res = transport.send(
            config.registryUrl,
            MarketplaceTransportRequest("POST", "/api/v1/sellers", body = body)
        )
        checkTransport(res, "seller registration")?.let { return SellerRegistrationResult(it, null) }
        if (res.statusCode != 201) {
            return SellerRegistrationResult(
                OperationResult.failure(
                    error = "Seller registration failed: HTTP ${res.statusCode}.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                null
            )
        }
        return try {
            val obj = JSONObject(res.body)
            val seller = SellerProfile.fromJson(obj.getJSONObject("seller"))
            val token = obj.optString("sellerToken")
            if (token.isBlank()) {
                SellerRegistrationResult(
                    OperationResult.failure("Seller registration failed: registry did not return a seller token."),
                    null
                )
            } else {
                SellerRegistrationResult(
                    OperationResult.success(
                        logs = listOf("Seller '${seller.displayName}' registered (id=${seller.sellerId})."),
                        artifacts = listOf(seller.sellerId),
                        verificationEvidence = "HTTP 201 with seller id and token"
                    ),
                    seller,
                    token
                )
            }
        } catch (e: Exception) {
            SellerRegistrationResult(
                OperationResult.failure("Seller registration failed: invalid response JSON (${e.message})."),
                null
            )
        }
    }

    /**
     * POST /api/v1/listings — publishes a new listing. The draft is validated
     * locally first; invalid drafts never reach the network.
     */
    fun publishListing(sellerToken: String, draft: ListingDraft): ListingPublishResult {
        requireConfigured("publishing a listing")?.let { return ListingPublishResult(it, null) }
        requireSellerToken(sellerToken, "publishing a listing")?.let {
            return ListingPublishResult(it, null)
        }
        val validation = validateListing(draft)
        if (!validation.isValid) {
            return ListingPublishResult(
                OperationResult.failure(
                    error = "Listing draft is invalid and was not sent: " +
                        validation.errors.joinToString("; ")
                ),
                null
            )
        }
        val res = transport.send(
            config.registryUrl,
            MarketplaceTransportRequest(
                "POST", "/api/v1/listings",
                authHeaders(sellerToken), draft.toListingJson().toString()
            )
        )
        checkTransport(res, "listing publish")?.let { return ListingPublishResult(it, null) }
        if (res.statusCode != 201) {
            return ListingPublishResult(
                OperationResult.failure(
                    error = "Listing publish failed: HTTP ${res.statusCode}.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                null
            )
        }
        return try {
            val listing = parseListing(res.body)
            ListingPublishResult(
                OperationResult.success(
                    logs = listOf("Listing '${listing.name}' published (id=${listing.id})."),
                    artifacts = listOf(listing.id),
                    verificationEvidence = "HTTP 201 with listing id"
                ),
                listing
            )
        } catch (e: Exception) {
            ListingPublishResult(
                OperationResult.failure("Listing publish failed: invalid response JSON (${e.message})."),
                null
            )
        }
    }

    /**
     * PATCH /api/v1/listings/{id} — updates an existing listing. Same local
     * validation as publish; blank ids are refused.
     */
    fun updateListing(sellerToken: String, listingId: String, draft: ListingDraft): ListingPublishResult {
        requireConfigured("updating listing '$listingId'")?.let {
            return ListingPublishResult(it, null)
        }
        requireSellerToken(sellerToken, "updating a listing")?.let {
            return ListingPublishResult(it, null)
        }
        if (listingId.isBlank()) {
            return ListingPublishResult(
                OperationResult.failure("Listing update failed: listing id is blank."),
                null
            )
        }
        val validation = validateListing(draft)
        if (!validation.isValid) {
            return ListingPublishResult(
                OperationResult.failure(
                    error = "Listing draft is invalid and was not sent: " +
                        validation.errors.joinToString("; ")
                ),
                null
            )
        }
        val res = transport.send(
            config.registryUrl,
            MarketplaceTransportRequest(
                "PATCH", "/api/v1/listings/" + encodeParam(listingId),
                authHeaders(sellerToken), draft.toListingJson().toString()
            )
        )
        checkTransport(res, "listing update")?.let { return ListingPublishResult(it, null) }
        if (res.statusCode == 404) {
            return ListingPublishResult(
                OperationResult.failure(
                    error = "Listing update failed: listing '$listingId' not found (HTTP 404).",
                    exitCode = 404
                ),
                null
            )
        }
        if (res.statusCode != 200) {
            return ListingPublishResult(
                OperationResult.failure(
                    error = "Listing update failed: HTTP ${res.statusCode}.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                null
            )
        }
        return try {
            val listing = parseListing(res.body)
            ListingPublishResult(
                OperationResult.success(
                    logs = listOf("Listing '${listing.name}' updated (id=${listing.id})."),
                    artifacts = listOf(listing.id),
                    verificationEvidence = "HTTP 200 with updated listing id"
                ),
                listing
            )
        } catch (e: Exception) {
            ListingPublishResult(
                OperationResult.failure("Listing update failed: invalid response JSON (${e.message})."),
                null
            )
        }
    }

    /** POST /api/v1/listings/{id}/unpublish — takes a listing off the market. */
    fun unpublishListing(sellerToken: String, listingId: String): ListingPublishResult {
        requireConfigured("unpublishing listing '$listingId'")?.let {
            return ListingPublishResult(it, null)
        }
        requireSellerToken(sellerToken, "unpublishing a listing")?.let {
            return ListingPublishResult(it, null)
        }
        if (listingId.isBlank()) {
            return ListingPublishResult(
                OperationResult.failure("Listing unpublish failed: listing id is blank."),
                null
            )
        }
        val res = transport.send(
            config.registryUrl,
            MarketplaceTransportRequest(
                "POST", "/api/v1/listings/" + encodeParam(listingId) + "/unpublish",
                authHeaders(sellerToken)
            )
        )
        checkTransport(res, "listing unpublish")?.let { return ListingPublishResult(it, null) }
        if (res.statusCode == 404) {
            return ListingPublishResult(
                OperationResult.failure(
                    error = "Listing unpublish failed: listing '$listingId' not found (HTTP 404).",
                    exitCode = 404
                ),
                null
            )
        }
        if (res.statusCode != 200) {
            return ListingPublishResult(
                OperationResult.failure(
                    error = "Listing unpublish failed: HTTP ${res.statusCode}.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                null
            )
        }
        return try {
            val listing = parseListing(res.body)
            ListingPublishResult(
                OperationResult.success(
                    logs = listOf("Listing '${listing.name}' unpublished."),
                    verificationEvidence = "HTTP 200, status=${listing.status}"
                ),
                listing
            )
        } catch (e: Exception) {
            ListingPublishResult(
                OperationResult.failure("Listing unpublish failed: invalid response JSON (${e.message})."),
                null
            )
        }
    }

    /**
     * GET /api/v1/sellers/{id}/sales — the seller's sales ledger plus revenue
     * and pending-payout totals. All totals come from the registry; nothing is
     * computed client-side from line items as the source of truth.
     */
    fun getSalesReport(sellerToken: String, sellerId: String): SalesReportResult {
        requireConfigured("fetching the sales report")?.let {
            return SalesReportResult(it, null)
        }
        requireSellerToken(sellerToken, "fetching the sales report")?.let {
            return SalesReportResult(it, null)
        }
        if (sellerId.isBlank()) {
            return SalesReportResult(
                OperationResult.failure("Sales report failed: seller id is blank."),
                null
            )
        }
        val res = transport.send(
            config.registryUrl,
            MarketplaceTransportRequest(
                "GET", "/api/v1/sellers/" + encodeParam(sellerId) + "/sales",
                authHeaders(sellerToken)
            )
        )
        checkTransport(res, "sales report fetch")?.let { return SalesReportResult(it, null) }
        if (res.statusCode != 200) {
            return SalesReportResult(
                OperationResult.failure(
                    error = "Sales report fetch failed: HTTP ${res.statusCode}.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                null
            )
        }
        return try {
            val obj = JSONObject(res.body)
            val sales = mutableListOf<SalesRecord>()
            val arr = obj.optJSONArray("sales")
            if (arr != null) {
                for (i in 0 until arr.length()) sales.add(SalesRecord.fromJson(arr.getJSONObject(i)))
            }
            val report = SellerSalesReport(
                sellerId = sellerId,
                sales = sales,
                totalRevenueCents = obj.optLong("totalRevenueCents", 0),
                payoutPendingCents = obj.optLong("payoutPendingCents", 0)
            )
            SalesReportResult(
                OperationResult.success(
                    logs = listOf("Sales report: ${sales.size} sales, revenue ${report.totalRevenueCents}c."),
                    verificationEvidence = "HTTP 200 with ${sales.size} sales records"
                ),
                report
            )
        } catch (e: Exception) {
            SalesReportResult(
                OperationResult.failure("Sales report fetch failed: invalid response JSON (${e.message})."),
                null
            )
        }
    }
}
