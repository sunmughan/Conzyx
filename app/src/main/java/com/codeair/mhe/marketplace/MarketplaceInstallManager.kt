package com.codeair.mhe.marketplace

import com.codeair.mhe.network.HttpRetry
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result of a marketplace install attempt. A failed download or write is
 * [MarketplaceInstallResult.Failed] — never a fake success (Milestone 0).
 */
sealed interface MarketplaceInstallResult {
    data class Installed(val packageDir: File) : MarketplaceInstallResult
    data class Failed(val reason: String) : MarketplaceInstallResult
    data object AlreadyInstalled : MarketplaceInstallResult
}

/**
 * Durably recorded installation of a marketplace package.
 */
data class InstalledPackage(
    val itemId: String,
    val name: String,
    val version: String,
    val installedAt: Long = System.currentTimeMillis(),
    val sourceUrl: String = "",
    val packageDir: String = "",
    /** Expected SHA-256 the download was verified against ("" = none provided). */
    val sha256: String = "",
)

/** SHA-256 hex of [bytes]. Pure, unit-tested. */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * True when [bytes] hash to [expectedSha256] (case-insensitive), or when
 * the listing provides no hash (blank = nothing to verify). Pure,
 * unit-tested.
 */
internal fun verifyPackageHash(expectedSha256: String, bytes: ByteArray): Boolean {
    if (expectedSha256.isBlank()) return true
    return sha256Hex(bytes).equals(expectedSha256.trim(), ignoreCase = true)
}

/**
 * Real installation manager for the AI Developer Marketplace (Phase 14).
 *
 * Milestone 0: install status derives from THIS manager — durably persisted in
 * SharedPreferences plus a materialized package directory under the app's files
 * dir — instead of an in-memory set and toast-only callbacks. Remote packages
 * are actually downloaded; a failed download fails the install honestly.
 */
class MarketplaceInstallManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val packagesDir = File(context.filesDir, "marketplace").apply { mkdirs() }

    private val _installedPackages = MutableStateFlow(loadAll())
    val installedPackages: StateFlow<Map<String, InstalledPackage>> = _installedPackages.asStateFlow()

    fun isInstalled(itemId: String): Boolean = _installedPackages.value.containsKey(itemId)

    fun installedIds(): Set<String> = _installedPackages.value.keys.toSet()

    fun getInstalled(itemId: String): InstalledPackage? = _installedPackages.value[itemId]

    /**
     * Installs [item]: materializes the package on disk and records it durably.
     * HTTP(S) sources are really downloaded (size-capped); anything else is
     * recorded as a catalog reference with a manifest.
     */
    fun install(item: MarketplaceItem): MarketplaceInstallResult {
        if (isInstalled(item.id)) return MarketplaceInstallResult.AlreadyInstalled
        val targetDir = File(packagesDir, sanitizeId(item.id))
        return try {
            targetDir.mkdirs()
            val source = item.installUrl.trim()
            val sourceNote: String
            if (source.startsWith("http://") || source.startsWith("https://")) {
                val payload = downloadBytes(source)
                // A listing hash is a hard gate: a mismatch fails the
                // install instead of writing unverified bytes.
                check(verifyPackageHash(item.sha256, payload)) {
                    "Downloaded package hash does not match the listing (expected ${item.sha256})"
                }
                File(targetDir, "package.bin").writeBytes(payload)
                sourceNote = "downloaded from $source (${payload.size} bytes)"
            } else if (source.isNotBlank()) {
                sourceNote = "local reference: $source"
            } else {
                sourceNote = "catalog entry (no downloadable artifact)"
            }
            val manifest = JSONObject()
                .put("id", item.id)
                .put("name", item.name)
                .put("author", item.author)
                .put("category", item.category.name)
                .put("version", DEFAULT_VERSION)
                .put("source", sourceNote)
                .put("sha256", item.sha256)
                .put("installedAt", System.currentTimeMillis())
            File(targetDir, "manifest.json").writeText(manifest.toString(2))

            val record = InstalledPackage(
                itemId = item.id,
                name = item.name,
                version = DEFAULT_VERSION,
                sourceUrl = source,
                packageDir = targetDir.absolutePath,
                sha256 = item.sha256,
            )
            prefs.edit().putString(keyFor(item.id), toJson(record)).apply()
            _installedPackages.value = _installedPackages.value + (item.id to record)
            MarketplaceInstallResult.Installed(targetDir)
        } catch (e: Exception) {
            runCatching { targetDir.deleteRecursively() }
            MarketplaceInstallResult.Failed("Install failed: ${e.message}")
        }
    }

    /**
     * Uninstalls a package: removes its directory and its durable record.
     * Returns false when the package was not installed.
     */
    fun uninstall(itemId: String): Boolean {
        val record = _installedPackages.value[itemId] ?: return false
        runCatching {
            val dir = record.packageDir.takeIf { it.isNotBlank() }?.let(::File)
            if (dir != null && dir.exists()) dir.deleteRecursively()
        }
        prefs.edit().remove(keyFor(itemId)).apply()
        _installedPackages.value = _installedPackages.value - itemId
        return true
    }

    private fun loadAll(): Map<String, InstalledPackage> {
        return prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_PREFIX) || value !is String) return@mapNotNull null
            runCatching { fromJson(value) }.getOrNull()?.let { it.itemId to it }
        }.toMap()
    }

    private fun persist(record: InstalledPackage) {
        prefs.edit().putString(keyFor(record.itemId), toJson(record)).apply()
    }

    private fun toJson(record: InstalledPackage): String = JSONObject()
        .put("itemId", record.itemId)
        .put("name", record.name)
        .put("version", record.version)
        .put("installedAt", record.installedAt)
        .put("sourceUrl", record.sourceUrl)
        .put("packageDir", record.packageDir)
        .put("sha256", record.sha256)
        .toString()

    private fun fromJson(json: String): InstalledPackage {
        val o = JSONObject(json)
        return InstalledPackage(
            itemId = o.getString("itemId"),
            name = o.optString("name"),
            version = o.optString("version", DEFAULT_VERSION),
            installedAt = o.optLong("installedAt", System.currentTimeMillis()),
            sourceUrl = o.optString("sourceUrl"),
            packageDir = o.optString("packageDir"),
            sha256 = o.optString("sha256"),
        )
    }

    private fun keyFor(itemId: String): String = KEY_PREFIX + sanitizeId(itemId)

    private fun sanitizeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "pkg" }

    private fun downloadBytes(url: String): ByteArray {
        // QA Medium: one transient 429/5xx used to be final. Retry with
        // backoff, honoring the server's Retry-After header.
        var attempt = 0
        while (true) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.connect()
                val code = connection.responseCode
                if (HttpRetry.isRetryable(code) &&
                    attempt < HttpRetry.MAX_ATTEMPTS - 1
                ) {
                    val retryAfter = connection.getHeaderField("Retry-After")
                    HttpRetry.sleepBeforeRetry(attempt, retryAfter)
                    attempt++
                    continue
                }
                check(code in 200..299) { "HTTP $code downloading $url" }
                val contentLength = connection.contentLengthLong
                check(contentLength <= MAX_DOWNLOAD_BYTES || contentLength < 0) {
                    "Package too large (${contentLength} bytes, limit $MAX_DOWNLOAD_BYTES)"
                }
                val buffer = connection.inputStream.use { it.readBytes() }
                check(buffer.size <= MAX_DOWNLOAD_BYTES) {
                    "Package too large (${buffer.size} bytes, limit $MAX_DOWNLOAD_BYTES)"
                }
                check(buffer.isNotEmpty()) { "Downloaded package is empty" }
                return buffer
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "marketplace_installs"
        private const val KEY_PREFIX = "pkg_"
        private const val DEFAULT_VERSION = "1.0.0"
        private const val MAX_DOWNLOAD_BYTES = 50L * 1024L * 1024L
    }
}
