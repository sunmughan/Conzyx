package com.codeair.mhe.runtime

import android.content.Context

/**
 * App-private persistence for the current FCM registration token.
 *
 * The token is a routing identifier for this installation, not an OAuth
 * credential. It is retained locally so a future authenticated backend can
 * register/rotate it without coupling Firebase directly to the UI layer.
 */
object PushTokenStore {
    private const val PREFS_NAME = "push_registration"
    private const val KEY_FCM_TOKEN = "fcm_token"

    fun save(context: Context, token: String) {
        if (token.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FCM_TOKEN, token)
            .apply()
    }

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FCM_TOKEN, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_FCM_TOKEN)
            .apply()
    }
}
