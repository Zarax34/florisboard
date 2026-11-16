/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.clipboard.vault

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.ProviderException
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

@Serializable
data class VaultEntry(
    val id: String,
    val createdAtMs: Long,
    /** Salted SHA-256 of the secret, only used to not store the same secret twice. */
    val hash: String,
    val wrappedKey: String,
    val iv: String,
    val ciphertext: String,
)

@Serializable
data class VaultData(
    val salt: String,
    val entries: List<VaultEntry> = emptyList(),
)

data class VaultSecret(val id: String, val createdAtMs: Long, val text: String)

sealed interface VaultOpenResult {
    data class Opened(val secrets: List<VaultSecret>) : VaultOpenResult
    /** The user did not authenticate in the last [SecretVault.AUTH_VALIDITY_SECONDS] seconds. */
    data object NeedsAuth : VaultOpenResult
    /** The key was invalidated (e.g. lock screen removed), stored secrets are unrecoverable and were wiped. */
    data object KeyInvalidated : VaultOpenResult
}

/**
 * Encrypted store for secrets taken out of the clipboard.
 *
 * Each secret is encrypted with its own AES-256-GCM key, which is wrapped with the public half of an RSA key pair
 * kept in the Android Keystore. Saving therefore needs no authentication, but unwrapping needs the private key, which
 * the Keystore only releases for [AUTH_VALIDITY_SECONDS] seconds after the user authenticated (biometric or lock
 * screen credential). The file holds no plaintext.
 */
object SecretVault {
    const val AUTH_VALIDITY_SECONDS = 30

    private const val KEY_ALIAS = "florisboard_secret_vault"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val FILE_NAME = "secret_vault.json"
    private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16

    // Keystore only supports SHA-1 for MGF1, so both sides must use exactly this spec
    private val OaepSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    private val JsonConfig = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** Clips the vault takes over: sensitive text (detected secret or marked sensitive by the source app). */
    fun shouldVault(enabled: Boolean, type: ItemType, isSensitive: Boolean, text: String?): Boolean {
        return enabled && type == ItemType.TEXT && isSensitive && !text.isNullOrBlank()
    }

    internal fun dedupeHash(salt: ByteArray, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return b64(digest.digest(text.toByteArray(Charsets.UTF_8)))
    }

    internal fun encode(data: VaultData): String = JsonConfig.encodeToString(VaultData.serializer(), data)

    internal fun decode(json: String): VaultData = JsonConfig.decodeFromString(VaultData.serializer(), json)

    internal fun newData(): VaultData = VaultData(salt = b64(ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }))

    /** True if secrets can be stored, i.e. the device has a secure lock screen and the key pair exists. */
    fun isAvailable(context: Context): Boolean = publicKey(context) != null

    /**
     * Encrypts and stores [text], unless it is stored already. No authentication needed.
     *
     * @return true if the secret is in the vault afterwards.
     */
    fun save(context: Context, text: String): Boolean = synchronized(lock) {
        val publicKey = publicKey(context) ?: return false
        val data = read(context)
        val hash = dedupeHash(b64d(data.salt), text)
        if (data.entries.any { it.hash == hash }) return true
        return try {
            val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val aes = Cipher.getInstance(AES_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, aesKey) }
            val ciphertext = aes.doFinal(text.toByteArray(Charsets.UTF_8))
            val rsa = Cipher.getInstance(RSA_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, publicKey, OaepSpec) }
            val entry = VaultEntry(
                id = UUID.randomUUID().toString(),
                createdAtMs = System.currentTimeMillis(),
                hash = hash,
                wrappedKey = b64(rsa.doFinal(aesKey.encoded)),
                iv = b64(aes.iv),
                ciphertext = b64(ciphertext),
            )
            write(context, data.copy(entries = data.entries + entry))
        } catch (e: GeneralSecurityException) {
            flogError { "Could not encrypt secret for the vault: $e" }
            false
        }
    }

    /** Decrypts all secrets, newest first. Only works shortly after the user authenticated. */
    fun open(context: Context): VaultOpenResult = synchronized(lock) {
        val data = read(context)
        if (data.entries.isEmpty()) return VaultOpenResult.Opened(emptyList())
        val privateKey = try {
            keyStore().getKey(KEY_ALIAS, null) as? PrivateKey
        } catch (e: GeneralSecurityException) {
            // Possibly transient, never destroy data because of it
            flogError { "Vault key unreadable: $e" }
            return VaultOpenResult.NeedsAuth
        } ?: return wipe(context) // key gone: the entries can never be decrypted again
        val rsa = try {
            Cipher.getInstance(RSA_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, privateKey, OaepSpec) }
        } catch (e: UserNotAuthenticatedException) {
            return VaultOpenResult.NeedsAuth
        } catch (e: KeyPermanentlyInvalidatedException) {
            flogError { "Vault key permanently invalidated, wiping the vault" }
            return wipe(context)
        }
        val secrets = data.entries.mapNotNull { entry ->
            try {
                val aesKey = SecretKeySpec(rsa.doFinal(b64d(entry.wrappedKey)), "AES")
                val aes = Cipher.getInstance(AES_TRANSFORMATION)
                aes.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, b64d(entry.iv)))
                VaultSecret(entry.id, entry.createdAtMs, aes.doFinal(b64d(entry.ciphertext)).toString(Charsets.UTF_8))
            } catch (e: UserNotAuthenticatedException) {
                return VaultOpenResult.NeedsAuth
            } catch (e: GeneralSecurityException) {
                flogError { "Skipping unreadable vault entry ${entry.id}: $e" }
                null
            }
        }
        VaultOpenResult.Opened(secrets.sortedByDescending { it.createdAtMs })
    }

    fun delete(context: Context, id: String): Unit = synchronized(lock) {
        val data = read(context)
        write(context, data.copy(entries = data.entries.filter { it.id != id }))
    }

    fun deleteAll(context: Context): Unit = synchronized(lock) {
        val data = read(context)
        write(context, data.copy(entries = emptyList()))
    }

    private fun wipe(context: Context): VaultOpenResult {
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (e: GeneralSecurityException) {
            flogError { "Could not delete vault key: $e" }
        }
        file(context).delete()
        return VaultOpenResult.KeyInvalidated
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun read(context: Context): VaultData {
        val file = file(context)
        if (!file.exists()) return newData()
        return try {
            decode(file.readText())
        } catch (e: SerializationException) {
            // Never overwrite a file we can't read, keep it for inspection
            flogError { "Corrupt vault file, moving it aside: $e" }
            file.renameTo(File(file.path + ".corrupt"))
            newData()
        } catch (e: IOException) {
            flogError { "Could not read vault file: $e" }
            newData()
        }
    }

    private fun write(context: Context, data: VaultData): Boolean {
        val file = file(context)
        return try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encode(data))
            tmp.renameTo(file).also { if (!it) flogError { "Could not replace $file" } }
        } catch (e: IOException) {
            flogError { "Could not write vault file: $e" }
            false
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * The public key, re-created outside the Keystore so encrypting never asks for authentication. Generates the key
     * pair on first use; returns null without a secure lock screen.
     */
    private fun publicKey(context: Context): PublicKey? {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) {
            flogError { "No secure lock screen, the secret vault is unavailable" }
            return null
        }
        return try {
            val keystorePublic = keyStore().getCertificate(KEY_ALIAS)?.publicKey ?: generateKeyPair()
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keystorePublic.encoded))
        } catch (e: GeneralSecurityException) {
            flogError { "Secret vault key unavailable: $e" }
            null
        } catch (e: ProviderException) {
            flogError { "Secret vault key unavailable: $e" }
            null
        } catch (e: IllegalStateException) {
            flogError { "Secret vault key unavailable: $e" }
            null
        }
    }

    private fun generateKeyPair(): PublicKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            // Enrolling a new fingerprint must not silently destroy the vault
            .setInvalidatedByBiometricEnrollment(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                }
            }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        generator.initialize(spec)
        return generator.generateKeyPair().public
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun b64d(text: String): ByteArray = Base64.getDecoder().decode(text)
}
