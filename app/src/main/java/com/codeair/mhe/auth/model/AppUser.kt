package com.codeair.mhe.auth.model

enum class AuthProviderType(val title: String) {
    EMAIL("Email & Password"),
    GOOGLE("Google Account"),
    GUEST("Guest / Offline"),
    OTHER("Other Provider");

    companion object {
        fun fromFirebaseProviderId(providerId: String?): AuthProviderType {
            return when (providerId) {
                "google.com" -> GOOGLE
                "password" -> EMAIL
                else -> OTHER
            }
        }
    }
}

data class AppUser(
    val uid: String,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val emailVerified: Boolean = false,
    val provider: AuthProviderType = AuthProviderType.EMAIL,
    val createdAt: Long? = null,
    val lastSignInAt: Long? = null,
)
