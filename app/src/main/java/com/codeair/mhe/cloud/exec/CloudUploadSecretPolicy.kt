package com.codeair.mhe.cloud.exec

enum class UploadFileSensitivity { ALLOWED, SENSITIVE, BLOCKED }

data class UploadFileClassification(
    val sensitivity: UploadFileSensitivity,
    val reason: String
)

object CloudUploadSecretPolicy {

    fun classify(relativePath: String): UploadFileClassification {
        val normalized = relativePath.replace('\\', '/')
        val name = normalized.substringAfterLast('/').lowercase()
        if (name == "google-services.json") {
            return UploadFileClassification(UploadFileSensitivity.ALLOWED, "app asset")
        }
        blockedReason(normalized, name)?.let {
            return UploadFileClassification(UploadFileSensitivity.BLOCKED, it)
        }
        sensitiveReason(name)?.let {
            return UploadFileClassification(UploadFileSensitivity.SENSITIVE, it)
        }
        return UploadFileClassification(UploadFileSensitivity.ALLOWED, "not sensitive")
    }

    private fun blockedReason(normalized: String, name: String): String? = when {
        name.startsWith(".env") -> "environment secrets file"
        name.contains("id_rsa") || name.contains("id_ed25519") ||
            name.contains("id_ecdsa") || name.contains("id_dsa") -> "SSH private key"
        name == "known_hosts" || name == "authorized_keys" -> "SSH key material"
        name.endsWith(".pem") || name.endsWith(".key") -> "private key file"
        name.endsWith(".p12") || name.endsWith(".pfx") ||
            name.endsWith(".jks") || name.endsWith(".keystore") -> "keystore file"
        name.endsWith(".crt") || name.endsWith(".cer") -> "certificate file"
        name == ".npmrc" -> "npm credentials file"
        name == ".git-credentials" -> "git credentials file"
        name.contains("service-account") -> "service account credentials"
        name == "application_default_credentials.json" -> "GCP credentials file"
        name.contains("credentials") && name.endsWith(".json") -> "credentials file"
        normalized.contains("/.config/gcloud/") ||
            normalized.startsWith(".config/gcloud/") -> "GCP CLI credentials"
        else -> null
    }

    private fun sensitiveReason(name: String): String? = when {
        name.contains("secret") -> "filename suggests secrets"
        name.contains("token") -> "filename suggests a token"
        name.contains("password") || name.contains("passwd") -> "filename suggests a password"
        name.contains("private") -> "filename suggests private material"
        name.contains("credential") -> "filename suggests credentials"
        name == ".netrc" || name == "_netrc" -> "netrc credentials"
        name.endsWith(".keytab") -> "Kerberos keytab"
        else -> null
    }
}
