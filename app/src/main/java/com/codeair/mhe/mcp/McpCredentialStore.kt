package com.codeair.mhe.mcp

import com.codeair.mhe.workspace.SafeFileOps
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Storage backend for MCP credentials. The encrypted backend is the production default;
 * the in-memory backend exists for unit tests.
 */
interface McpCredentialBackend {
    fun put(key: String, secret: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun clear()
}

/** Non-persistent backend for tests. Secrets live only in process memory. */
class InMemoryCredentialBackend : McpCredentialBackend {
    private val map = ConcurrentHashMap<String, String>()
    override fun put(key: String, secret: String) { map[key] = secret }
    override fun get(key: String): String? = map[key]
    override fun remove(key: String) { map.remove(key) }
    override fun clear() { map.clear() }
}

/**
 * AES-256/GCM file backend. The data key is generated once and stored in a sidecar
 * key file (owner-only permissions on POSIX filesystems). Values are stored as
 * {"entries": {key: {"iv": b64, "value": b64}}} and never written in plaintext.
 * Pure JVM so unit tests can exercise the real encryption path.
 */
class AesGcmFileCredentialBackend(
    storageFile: File,
    keyFile: File = File(storageFile.parentFile, "mcp_cred.key")
) : McpCredentialBackend {

    private val storage: File = storageFile.apply { parentFile?.mkdirs() }
    private val keyFile: File = keyFile.apply { parentFile?.mkdirs() }
    private val lock = Any()

    private val dataKey: SecretKey by lazy { loadOrCreateKey() }

    private fun loadOrCreateKey(): SecretKey {
        synchronized(lock) {
            if (keyFile.exists() && keyFile.length() == 32L) {
                return SecretKeySpec(keyFile.readBytes(), "AES")
            }
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256, SecureRandom())
            val key = keyGen.generateKey()
            keyFile.writeBytes(key.encoded)
            runCatching {
                Files.setPosixFilePermissions(
                    keyFile.toPath(),
                    PosixFilePermissions.fromString("rw-------")
                )
            }
            return key
        }
    }

    private fun encrypt(plain: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, dataKey, SecureRandom())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = java.util.Base64.getEncoder()
        return b64.encodeToString(iv) to b64.encodeToString(cipherText)
    }

    private fun decrypt(ivB64: String, valueB64: String): String {
        val b64 = java.util.Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            dataKey,
            GCMParameterSpec(128, b64.decode(ivB64))
        )
        return cipher.doFinal(b64.decode(valueB64)).toString(Charsets.UTF_8)
    }

    private fun readAll(): JSONObject {
        if (!storage.exists()) return JSONObject()
        return runCatching {
            val raw = storage.readText()
            if (raw.isBlank()) JSONObject() else JSONObject(raw).optJSONObject("entries") ?: JSONObject()
        }.getOrDefault(JSONObject())
    }

    private fun writeAll(entries: JSONObject) {
        val root = JSONObject().put("entries", entries)
        SafeFileOps.atomicWriteText(storage, root.toString())
    }

    override fun put(key: String, secret: String) {
        synchronized(lock) {
            val entries = readAll()
            val (iv, value) = encrypt(secret)
            entries.put(key, JSONObject().put("iv", iv).put("value", value))
            writeAll(entries)
        }
    }

    override fun get(key: String): String? {
        synchronized(lock) {
            val entry = readAll().optJSONObject(key) ?: return null
            return runCatching {
                decrypt(entry.getString("iv"), entry.getString("value"))
            }.getOrNull()
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            val entries = readAll()
            if (entries.has(key)) {
                entries.remove(key)
                writeAll(entries)
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            if (storage.exists()) storage.delete()
            // The data key must go too, or a later clear() leaves a
            // orphaned key beside (a future) ciphertext.
            if (keyFile.exists()) keyFile.delete()
        }
    }
}

/**
 * Secure storage for MCP server secrets (API keys, tokens, connection strings).
 *
 * Secrets are keyed as "<serverId>::<name>" and NEVER written to mcp_servers.json —
 * that file keeps only non-secret configuration. [McpServerInfo.secretEnvNames]
 * declares which env vars resolve from this store at process-start time.
 */
class McpCredentialStore(
    baseDir: File,
    credentialBackend: McpCredentialBackend? = null
) {
    private val storeDir: File = baseDir.apply { mkdirs() }
    private val backend: McpCredentialBackend =
        credentialBackend ?: AesGcmFileCredentialBackend(File(storeDir, "mcp_credentials.json"))

    fun credentialKey(serverId: String, name: String): String = "$serverId::$name"

    fun setCredential(serverId: String, name: String, secret: String) {
        require(name.isNotBlank()) { "Credential name cannot be blank" }
        require(secret.isNotBlank()) { "Credential value cannot be blank" }
        backend.put(credentialKey(serverId, name), secret)
    }

    fun getCredential(serverId: String, name: String): String? =
        backend.get(credentialKey(serverId, name))

    fun hasCredential(serverId: String, name: String): Boolean =
        getCredential(serverId, name) != null

    fun removeCredential(serverId: String, name: String) =
        backend.remove(credentialKey(serverId, name))

    /**
     * Removes every credential belonging to a server. Called when a server is
     * uninstalled so secrets do not linger after removal.
     */
    fun removeServerCredentials(serverId: String, knownNames: List<String>) {
        knownNames.forEach { removeCredential(serverId, it) }
    }

    fun clear() = backend.clear()
}
