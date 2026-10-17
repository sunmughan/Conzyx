package com.codeair.mhe.operator.registry

data class ResolvedTarget(
    val packageName: String,
    val appLabel: String,
    val confidence: Float
)

class TargetAppResolver(private val registry: InstalledAppRegistry) {

    fun resolve(userText: String): ResolvedTarget? {
        val query = userText.lowercase().trim()
        if (query.isBlank()) return null
        var best: ResolvedTarget? = null
        for (app in registry.launchableApps()) {
            val confidence = runCatching {
                when {
                    app.packageName.lowercase() == query -> 1.0f
                    app.appLabel.lowercase() == query -> 0.95f
                    app.appLabel.lowercase().contains(query) ||
                        query.split(Regex("\\s+")).any { token ->
                            token.isNotBlank() && app.appLabel.lowercase().contains(token)
                        } -> 0.7f
                    else -> null
                }
            }.getOrNull()
            if (confidence != null && (best == null || confidence > best.confidence)) {
                best = ResolvedTarget(app.packageName, app.appLabel, confidence)
            }
        }
        return best
    }
}
