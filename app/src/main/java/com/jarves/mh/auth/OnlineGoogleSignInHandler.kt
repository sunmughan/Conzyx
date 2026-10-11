package com.jarves.mh.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.jarves.mh.AppCrashLogger

class OnlineGoogleSignInHandler : GoogleSignInHandler {
    override val isAvailable: Boolean = true

    override suspend fun requestGoogleIdToken(context: Context): Result<String> {
        return runCatching {
            // The Google Services Gradle plugin generates this from the Web OAuth
            // client (client_type = 3) in app/google-services.json. Do not silently
            // fall back to a hard-coded client ID: a stale ID can hide Firebase
            // project/package configuration errors.
            val resourceId = context.resources.getIdentifier(
                "default_web_client_id",
                "string",
                context.packageName,
            )
            check(resourceId != 0) {
                "Google Sign-In is not configured in this build: default_web_client_id is missing. " +
                    "Verify app/google-services.json belongs to Firebase project codeair-tech, " +
                    "contains the Web OAuth client, and the Google Services Gradle plugin ran."
            }
            val serverClientId = context.getString(resourceId).trim()
            check(serverClientId.endsWith(".apps.googleusercontent.com")) {
                "Google Sign-In configuration is invalid: default_web_client_id is not a valid OAuth client ID."
            }

            val credentialManager = CredentialManager.create(context)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context = context, request = request)
            val googleIdToken = GoogleIdTokenCredential.createFrom(result.credential.data)
            googleIdToken.idToken
        }.recoverCatching { throwable ->
            if (throwable is GetCredentialCancellationException) {
                throw GoogleSignInCancelledException()
            }

            AppCrashLogger.log("GoogleSignIn failed: ${throwable.javaClass.name}: ${throwable.message}")
            val message = throwable.message.orEmpty()
            val isDeveloperError10 =
                message.contains("DEVELOPER_ERROR", ignoreCase = true) ||
                    Regex("""(?i)(?:error|status)(?: code)?\s*[:=]?\s*10(?:\D|$)""").containsMatchIn(message)

            when {
                isDeveloperError10 -> throw Exception(
                    "Google Sign-In returned Developer Error 10. Check that Google Sign-In is enabled " +
                        "in Firebase Authentication, and that the Android OAuth client for package " +
                        "'com.codeair.mhe' has the SHA-1 fingerprint of the certificate that signed " +
                        "this exact APK (debug, CI, or release). Then download a fresh google-services.json."
                )
                message.contains("Cannot find a matching credential", ignoreCase = true) ||
                    message.contains("No credential", ignoreCase = true) ->
                    throw Exception(
                        "No eligible Google credential was returned. Confirm a Google account is available " +
                            "on the device and Google Play services/credential providers are up to date."
                    )
                else -> throw throwable
            }
        }
    }
}
