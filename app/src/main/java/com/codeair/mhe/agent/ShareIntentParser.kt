package com.codeair.mhe.agent

/**
 * A share received from another app, reduced to what Conzyx can stage:
 * shared text for the composer and file URIs for the attachment pipeline.
 */
data class SharedContent(val text: String, val uriStrings: List<String>)

/**
 * Pure parsing of an Android share (ACTION_SEND / ACTION_SEND_MULTIPLE).
 * Pure so it is unit-testable without Android: the caller extracts the raw
 * action, text and URI strings from the Intent and hands them here.
 */
object ShareIntentParser {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    /**
     * Returns null when the action is not a share, or when the share carries
     * neither text nor files. Otherwise the trimmed text (possibly blank)
     * and the distinct non-blank URI strings.
     */
    fun parse(action: String?, text: String?, uriStrings: List<String>): SharedContent? {
        if (action != ACTION_SEND && action != ACTION_SEND_MULTIPLE) return null
        val cleanText = text?.trim().orEmpty()
        val cleanUris = uriStrings.filter { it.isNotBlank() }.distinct()
        if (cleanText.isBlank() && cleanUris.isEmpty()) return null
        return SharedContent(cleanText, cleanUris)
    }
}
