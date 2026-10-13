package com.codeair.mhe.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.codeair.mhe.BuildConfig
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
    val variant: String = BuildConfig.APP_VARIANT,
)

class AppUpdater(
    private val context: Context,
    /**
     * Optional manifest URL or proxy endpoint override. When non-empty,
     * used in place of [BuildConfig.APP_UPDATE_MANIFEST_URL].
     */
    private val manifestUrlOverride: String = "",
    /**
     * Optional Bearer / Personal Access Token for accessing private GitHub repository releases.
     */
    private val authToken: String = "",
) {
    fun check(): AppUpdateInfo? {
        val urlsToTry = if (manifestUrlOverride.isNotBlank()) {
            listOf(manifestUrlOverride)
        } else {
            listOf(
                BuildConfig.APP_UPDATE_MANIFEST_URL,
                "https://api.github.com/repos/sunmughan/Mobile-Harness-Extended/releases/latest",
                "https://raw.githubusercontent.com/techjarves/Mobile-Harness/main/mobile-harness-update.json",
            )
        }

        for (manifestUrl in urlsToTry) {
            if (!manifestUrl.startsWith("https://")) continue
            try {
                if (manifestUrl.contains("api.github.com/repos/") && manifestUrl.endsWith("/releases/latest")) {
                    val update = checkGitHubReleaseApi(manifestUrl)
                    if (update != null) return update
                } else {
                    val update = checkStandardManifest(manifestUrl)
                    if (update != null) return update
                }
            } catch (_: Throwable) {
                // Try next URL fallback
            }
        }
        return null
    }

    private fun checkStandardManifest(manifestUrl: String): AppUpdateInfo? {
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        if (authToken.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $authToken")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            return null
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val root = JSONObject(body)
        val versionCode = root.optLong("versionCode")
        if (versionCode <= BuildConfig.VERSION_CODE) return null

        val artifact = resolveArtifactForVariant(root, BuildConfig.APP_VARIANT) ?: return null
        val url = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }
        if (!url.startsWith("https://")) return null

        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = root.optString("versionName", versionCode.toString()),
            apkUrl = url,
            sha256 = artifact.optString("sha256").lowercase(),
            sizeBytes = artifact.optLong("sizeBytes", -1L),
            notes = root.optString("notes"),
            variant = BuildConfig.APP_VARIANT,
        )
    }

    private fun checkGitHubReleaseApi(apiUrl: String): AppUpdateInfo? {
        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        if (authToken.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $authToken")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            return null
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val releaseJson = JSONObject(body)
        val assets = releaseJson.optJSONArray("assets") ?: JSONArray()

        // 1. If release contains mobile-harness-update.json, fetch and parse it
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == "mobile-harness-update.json") {
                val assetApiUrl = asset.optString("url")
                val manifestContent = fetchStringWithAuth(assetApiUrl)
                if (manifestContent != null) {
                    val root = JSONObject(manifestContent)
                    val versionCode = root.optLong("versionCode")
                    if (versionCode <= BuildConfig.VERSION_CODE) return null
                    val artifact = resolveArtifactForVariant(root, BuildConfig.APP_VARIANT) ?: continue
                    val rawUrl = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }

                    // If the repo is private, download URL may require GitHub Asset API URL instead of raw HTML redirect
                    val targetApkUrl = if (isGitHubDomain(rawUrl) && authToken.isNotBlank()) {
                        findAssetUrlForVariant(assets, BuildConfig.APP_VARIANT) ?: rawUrl
                    } else {
                        rawUrl
                    }

                    return AppUpdateInfo(
                        versionCode = versionCode,
                        versionName = root.optString("versionName", versionCode.toString()),
                        apkUrl = targetApkUrl,
                        sha256 = artifact.optString("sha256").lowercase(),
                        sizeBytes = artifact.optLong("sizeBytes", -1L),
                        notes = root.optString("notes").ifBlank { releaseJson.optString("body") },
                        variant = BuildConfig.APP_VARIANT,
                    )
                }
            }
        }

        // 2. Fallback: Parse release assets matching app-<variant>-release.apk
        val tagName = releaseJson.optString("tag_name")
        val parsedVersionCode = parseVersionFromTag(tagName)
        if (parsedVersionCode <= BuildConfig.VERSION_CODE) return null

        val apkAssetUrl = findAssetUrlForVariant(assets, BuildConfig.APP_VARIANT) ?: return null
        val matchedAsset = findAssetForVariant(assets, BuildConfig.APP_VARIANT)

        return AppUpdateInfo(
            versionCode = parsedVersionCode,
            versionName = tagName.removePrefix("v").ifBlank { releaseJson.optString("name") },
            apkUrl = apkAssetUrl,
            sha256 = "",
            sizeBytes = matchedAsset?.optLong("size", -1L) ?: -1L,
            notes = releaseJson.optString("body"),
            variant = BuildConfig.APP_VARIANT,
        )
    }

    fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File {
        val directory = File(context.filesDir, "updates").also { it.mkdirs() }
        val partial = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk.part")
        val target = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk")

        val (stream, totalBytes) = openDownloadStream(info.apkUrl, info.sizeBytes)
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    progress(downloaded, totalBytes)
                }
            }
        } finally {
            stream.close()
        }

        if (info.sha256.isNotBlank()) {
            val actual = sha256(partial)
            check(actual.equals(info.sha256, ignoreCase = true)) { "Downloaded APK failed its SHA-256 verification" }
        }
        verifyApk(partial, info.versionCode)
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Could not prepare the downloaded update" }
        return target
    }

    private fun openDownloadStream(urlStr: String, fallbackSize: Long): Pair<InputStream, Long> {
        var currentUrl = urlStr
        var attempts = 0
        while (attempts < 5) {
            attempts++
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false

            // Only send GitHub Authorization to GitHub domain, NEVER to Amazon S3 redirects!
            if (authToken.isNotBlank() && isGitHubDomain(currentUrl)) {
                connection.setRequestProperty("Authorization", "Bearer $authToken")
            }
            connection.setRequestProperty("Accept", "application/octet-stream")

            val code = connection.responseCode
            if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                check(!location.isNullOrBlank()) { "Redirect with missing Location header" }
                currentUrl = location
                continue
            }

            check(code in 200..299) { "Update download failed (HTTP $code)" }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: fallbackSize
            return connection.inputStream to total
        }
        error("Too many redirects downloading update")
    }

    private fun fetchStringWithAuth(urlStr: String): String? {
        return try {
            val (stream, _) = openDownloadStream(urlStr, -1L)
            stream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, expectedVersionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not a valid APK")
        check(archive.packageName == context.packageName) { "Update package name does not match Mobile Harness Extended" }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        check(archiveVersion == expectedVersionCode) { "Update version does not match its manifest" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.signingInfo?.apkContentsSigners else archive.signatures
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.signingInfo?.apkContentsSigners else installed.signatures
        check(!archiveSignatures.isNullOrEmpty() && !installedSignatures.isNullOrEmpty() &&
            archiveSignatures.map { sha256(it.toByteArray()) }.toSet() == installedSignatures.map { sha256(it.toByteArray()) }.toSet()
        ) { "Update is not signed with the installed app's signing key" }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        fun resolveArtifactForVariant(root: JSONObject, variant: String): JSONObject? {
            return root.optJSONObject("artifacts")?.optJSONObject(variant)
                ?: root.optJSONObject(variant)
                ?: root.takeIf { it.has("url") || it.has("apkUrl") }
        }

        fun parseVersionFromTag(tag: String): Long {
            val cleaned = tag.removePrefix("v").trim()
            val parts = cleaned.split(".").mapNotNull { it.toLongOrNull() }
            return when {
                parts.size >= 3 -> parts[0] * 10000L + parts[1] * 100L + parts[2]
                parts.size == 2 -> parts[0] * 10000L + parts[1] * 100L
                parts.size == 1 -> parts[0]
                else -> 0L
            }
        }

        fun isGitHubDomain(url: String): Boolean {
            val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: ""
            return host.endsWith("github.com") || host.endsWith("githubusercontent.com")
        }

        private fun findAssetUrlForVariant(assets: JSONArray, variant: String): String? {
            return findAssetForVariant(assets, variant)?.optString("url")
        }

        private fun findAssetForVariant(assets: JSONArray, variant: String): JSONObject? {
            val targetName = "app-$variant-release.apk"
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (name.equals(targetName, ignoreCase = true) || (name.contains(variant, ignoreCase = true) && name.endsWith(".apk", ignoreCase = true))) {
                    return asset
                }
            }
            return null
        }
    }
}
