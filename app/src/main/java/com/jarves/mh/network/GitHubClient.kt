package com.jarves.mh.network

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

data class GitHubAccount(val login: String, val avatarUrl: String)

data class GitHubRepository(
    val fullName: String,
    val cloneUrl: String,
    val private: Boolean,
    val defaultBranch: String,
    val description: String,
    val updatedAt: String,
)

data class GitHubIssue(
    val number: Int,
    val title: String,
    val state: String,
    val user: String,
    val comments: Int,
    val htmlUrl: String,
    val createdAt: String = "",
    val body: String = "",
)

data class GitHubPullRequest(
    val number: Int,
    val title: String,
    val state: String,
    val user: String,
    val headBranch: String,
    val baseBranch: String,
    val htmlUrl: String,
    val body: String = "",
)

sealed interface GitHubTokenPoll {
    data class Success(val accessToken: String) : GitHubTokenPoll
    data class Pending(val slowDown: Boolean = false) : GitHubTokenPoll
    data class Failure(val message: String) : GitHubTokenPoll
}

open class GitHubClient {
    fun requestDeviceCode(clientId: String): GitHubDeviceCode {
        val json = postForm(
            "https://github.com/login/device/code",
            mapOf("client_id" to clientId),
        )
        return GitHubDeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            expiresInSeconds = json.optLong("expires_in", 900L),
            intervalSeconds = json.optLong("interval", 5L).coerceAtLeast(5L),
        )
    }

    fun pollDeviceToken(clientId: String, deviceCode: String): GitHubTokenPoll {
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to clientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        json.optString("access_token").takeIf(String::isNotBlank)?.let { return GitHubTokenPoll.Success(it) }
        return when (val error = json.optString("error")) {
            "authorization_pending" -> GitHubTokenPoll.Pending()
            "slow_down" -> GitHubTokenPoll.Pending(slowDown = true)
            "access_denied" -> GitHubTokenPoll.Failure("GitHub authorization was cancelled")
            "expired_token" -> GitHubTokenPoll.Failure("The GitHub sign-in code expired. Try again.")
            else -> GitHubTokenPoll.Failure(json.optString("error_description").ifBlank { error.ifBlank { "GitHub sign-in failed" } })
        }
    }

    fun account(token: String): GitHubAccount {
        val json = getJson("https://api.github.com/user", token) as JSONObject
        return GitHubAccount(json.getString("login"), json.optString("avatar_url"))
    }

    fun repositories(token: String): List<GitHubRepository> {
        val result = LinkedHashMap<String, GitHubRepository>()
        var page = 1
        while (page <= 10) {
            val array = getJson(
                "https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100&page=$page",
                token,
            ) as JSONArray
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val repo = GitHubRepository(
                    fullName = item.getString("full_name"),
                    cloneUrl = item.getString("clone_url"),
                    private = item.optBoolean("private"),
                    defaultBranch = item.optString("default_branch", "main"),
                    description = item.optString("description"),
                    updatedAt = item.optString("updated_at"),
                )
                result[repo.fullName] = repo
            }
            if (array.length() < 100) break
            page++
        }
        return result.values.toList()
    }

    fun issues(token: String, repoFullName: String): List<GitHubIssue> {
        val array = getJson(
            "https://api.github.com/repos/$repoFullName/issues?state=all&per_page=30",
            token,
        ) as? JSONArray ?: return emptyList()
        val list = mutableListOf<GitHubIssue>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (!item.has("pull_request")) {
                list.add(
                    GitHubIssue(
                        number = item.getInt("number"),
                        title = item.getString("title"),
                        state = item.getString("state"),
                        user = item.optJSONObject("user")?.optString("login") ?: "user",
                        comments = item.optInt("comments", 0),
                        htmlUrl = item.optString("html_url", ""),
                        createdAt = item.optString("created_at", ""),
                        body = item.optString("body", ""),
                    )
                )
            }
        }
        return list
    }

    fun createIssue(token: String, repoFullName: String, title: String, body: String = ""): GitHubIssue {
        val payload = JSONObject().apply {
            put("title", title)
            put("body", body)
        }
        val json = postJson("https://api.github.com/repos/$repoFullName/issues", token, payload) as JSONObject
        return GitHubIssue(
            number = json.getInt("number"),
            title = json.getString("title"),
            state = json.getString("state"),
            user = json.optJSONObject("user")?.optString("login") ?: "user",
            comments = 0,
            htmlUrl = json.getString("html_url"),
            createdAt = json.optString("created_at", ""),
            body = json.optString("body", ""),
        )
    }

    fun createIssueComment(token: String, repoFullName: String, issueNumber: Int, comment: String): GitHubIssueComment {
        val payload = JSONObject().apply { put("body", comment) }
        val json = postJson("https://api.github.com/repos/$repoFullName/issues/$issueNumber/comments", token, payload) as JSONObject
        return GitHubIssueComment(
            id = json.getLong("id"),
            body = json.getString("body"),
            user = json.optJSONObject("user")?.optString("login") ?: "user",
            createdAt = json.optString("created_at", "")
        )
    }

    fun closeIssue(token: String, repoFullName: String, issueNumber: Int): Boolean {
        val payload = JSONObject().apply { put("state", "closed") }
        val res = patchJson("https://api.github.com/repos/$repoFullName/issues/$issueNumber", token, payload)
        return res is JSONObject
    }

    fun pullRequests(token: String, repoFullName: String): List<GitHubPullRequest> {
        val array = getJson(
            "https://api.github.com/repos/$repoFullName/pulls?state=all&per_page=30",
            token,
        ) as? JSONArray ?: return emptyList()
        val list = mutableListOf<GitHubPullRequest>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            list.add(
                GitHubPullRequest(
                    number = item.getInt("number"),
                    title = item.getString("title"),
                    state = item.getString("state"),
                    user = item.optJSONObject("user")?.optString("login") ?: "user",
                    headBranch = item.optJSONObject("head")?.optString("ref") ?: "",
                    baseBranch = item.optJSONObject("base")?.optString("ref") ?: "",
                    htmlUrl = item.optString("html_url", ""),
                    body = item.optString("body", ""),
                )
            )
        }
        return list
    }

    open fun createPullRequest(
        token: String,
        repoFullName: String,
        title: String,
        head: String,
        base: String,
        body: String = "",
        draft: Boolean = false,
    ): GitHubPullRequest {
        val payload = JSONObject().apply {
            put("title", title)
            put("head", head)
            put("base", base)
            put("body", body)
            put("draft", draft)
        }
        val json = postJson("https://api.github.com/repos/$repoFullName/pulls", token, payload) as JSONObject
        return GitHubPullRequest(
            number = json.getInt("number"),
            title = json.getString("title"),
            state = json.getString("state"),
            user = json.optJSONObject("user")?.optString("login") ?: "user",
            headBranch = json.optJSONObject("head")?.optString("ref") ?: head,
            baseBranch = json.optJSONObject("base")?.optString("ref") ?: base,
            htmlUrl = json.getString("html_url"),
            body = json.optString("body", body),
        )
    }

    fun mergePullRequest(
        token: String,
        repoFullName: String,
        prNumber: Int,
        commitTitle: String? = null,
        commitMessage: String? = null,
        method: PullRequestMergeMethod = PullRequestMergeMethod.SQUASH,
    ): MergeResult {
        val payload = JSONObject().apply {
            put("merge_method", method.value)
            if (!commitTitle.isNullOrBlank()) put("commit_title", commitTitle)
            if (!commitMessage.isNullOrBlank()) put("commit_message", commitMessage)
        }
        val res = putJson("https://api.github.com/repos/$repoFullName/pulls/$prNumber/merge", token, payload) as JSONObject
        return MergeResult(
            merged = res.optBoolean("merged", false),
            message = res.optString("message", ""),
            sha = res.optString("sha", null)
        )
    }

    fun getPullRequestDiff(token: String, repoFullName: String, prNumber: Int): String {
        val connection = (URL("https://api.github.com/repos/$repoFullName/pulls/$prNumber").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github.v3.diff")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "PocketDev-Android")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    fun submitReview(
        token: String,
        repoFullName: String,
        prNumber: Int,
        event: String,
        body: String
    ): GitHubReview {
        val payload = JSONObject().apply {
            put("event", event)
            put("body", body)
        }
        val json = postJson("https://api.github.com/repos/$repoFullName/pulls/$prNumber/reviews", token, payload) as JSONObject
        return GitHubReview(
            id = json.getLong("id"),
            user = json.optJSONObject("user")?.optString("login") ?: "reviewer",
            state = json.optString("state", event),
            body = json.optString("body", body),
            submittedAt = json.optString("submitted_at", "")
        )
    }

    fun listReviews(token: String, repoFullName: String, prNumber: Int): List<GitHubReview> {
        val array = getJson("https://api.github.com/repos/$repoFullName/pulls/$prNumber/reviews", token) as? JSONArray ?: return emptyList()
        val list = mutableListOf<GitHubReview>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            list += GitHubReview(
                id = item.getLong("id"),
                user = item.optJSONObject("user")?.optString("login") ?: "reviewer",
                state = item.optString("state", ""),
                body = item.optString("body", ""),
                submittedAt = item.optString("submitted_at", "")
            )
        }
        return list
    }

    fun listWorkflows(token: String, repoFullName: String): List<GitHubWorkflow> {
        val json = getJson("https://api.github.com/repos/$repoFullName/actions/workflows", token) as? JSONObject ?: return emptyList()
        val array = json.optJSONArray("workflows") ?: return emptyList()
        val list = mutableListOf<GitHubWorkflow>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            list += GitHubWorkflow(
                id = item.getLong("id"),
                name = item.getString("name"),
                path = item.getString("path"),
                state = item.optString("state", "active")
            )
        }
        return list
    }

    fun listWorkflowRuns(token: String, repoFullName: String): List<GitHubWorkflowRun> {
        val json = getJson("https://api.github.com/repos/$repoFullName/actions/runs?per_page=30", token) as? JSONObject ?: return emptyList()
        val array = json.optJSONArray("workflow_runs") ?: return emptyList()
        val list = mutableListOf<GitHubWorkflowRun>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            list += GitHubWorkflowRun(
                id = item.getLong("id"),
                name = item.optString("name", "CI Run"),
                status = item.getString("status"),
                conclusion = item.optString("conclusion").takeIf { it.isNotBlank() },
                htmlUrl = item.getString("html_url"),
                headBranch = item.optString("head_branch", "main"),
                event = item.optString("event", "push"),
                createdAt = item.optString("created_at", ""),
                runNumber = item.optInt("run_number", 1)
            )
        }
        return list
    }

    fun dispatchWorkflow(
        token: String,
        repoFullName: String,
        workflowId: String,
        ref: String = "main",
        inputs: Map<String, Any> = emptyMap()
    ): Boolean {
        val payload = JSONObject().apply {
            put("ref", ref)
            if (inputs.isNotEmpty()) put("inputs", JSONObject(inputs))
        }
        val res = postJson("https://api.github.com/repos/$repoFullName/actions/workflows/$workflowId/dispatches", token, payload)
        return res != null
    }

    fun listReleases(token: String, repoFullName: String): List<GitHubRelease> {
        val array = getJson("https://api.github.com/repos/$repoFullName/releases?per_page=20", token) as? JSONArray ?: return emptyList()
        val list = mutableListOf<GitHubRelease>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val assetsArray = item.optJSONArray("assets")
            val assets = mutableListOf<GitHubReleaseAsset>()
            if (assetsArray != null) {
                for (j in 0 until assetsArray.length()) {
                    val a = assetsArray.getJSONObject(j)
                    assets += GitHubReleaseAsset(
                        id = a.getLong("id"),
                        name = a.getString("name"),
                        sizeBytes = a.optLong("size", 0L),
                        downloadUrl = a.optString("browser_download_url", ""),
                        contentType = a.optString("content_type", "")
                    )
                }
            }
            list += GitHubRelease(
                id = item.getLong("id"),
                tagName = item.getString("tag_name"),
                name = item.optString("name", item.getString("tag_name")),
                body = item.optString("body", ""),
                draft = item.optBoolean("draft", false),
                prerelease = item.optBoolean("prerelease", false),
                createdAt = item.optString("created_at", ""),
                publishedAt = item.optString("published_at", ""),
                htmlUrl = item.optString("html_url", ""),
                assets = assets
            )
        }
        return list
    }

    fun createRelease(
        token: String,
        repoFullName: String,
        tagName: String,
        name: String = tagName,
        body: String = "",
        draft: Boolean = false,
        prerelease: Boolean = false
    ): GitHubRelease {
        val payload = JSONObject().apply {
            put("tag_name", tagName)
            put("name", name)
            put("body", body)
            put("draft", draft)
            put("prerelease", prerelease)
        }
        val json = postJson("https://api.github.com/repos/$repoFullName/releases", token, payload) as JSONObject
        return GitHubRelease(
            id = json.getLong("id"),
            tagName = json.getString("tag_name"),
            name = json.optString("name", tagName),
            body = json.optString("body", body),
            draft = json.optBoolean("draft", draft),
            prerelease = json.optBoolean("prerelease", prerelease),
            createdAt = json.optString("created_at", ""),
            publishedAt = json.optString("published_at", ""),
            htmlUrl = json.getString("html_url"),
            assets = emptyList()
        )
    }

    private fun postForm(endpoint: String, values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "PocketDev-Android")
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection) as JSONObject
    }

    private fun postJson(endpoint: String, token: String, payload: JSONObject): Any {
        val body = payload.toString().toByteArray()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "PocketDev-Android")
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection)
    }

    private fun putJson(endpoint: String, token: String, payload: JSONObject): Any {
        val body = payload.toString().toByteArray()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "PocketDev-Android")
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection)
    }

    private fun patchJson(endpoint: String, token: String, payload: JSONObject): Any {
        val body = payload.toString().toByteArray()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "PocketDev-Android")
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection)
    }

    private fun getJson(endpoint: String, token: String): Any {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", "PocketDev-Android")
        }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): Any {
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
            error(message?.takeIf(String::isNotBlank) ?: "GitHub returned HTTP $status")
        }
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }
}
