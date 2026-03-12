package com.codex.sshterminal.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.codex.sshterminal.data.ssh.TrustedHostKeyRecord
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores encrypted credentials with a Keystore-backed key while keeping Room free of secrets. */
class ConnectionPasswordStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Loads and decrypts a previously saved password for the given connection id. */
    fun loadPassword(connectionId: Long): String? = loadSecret(secretKey(connectionId, PASSWORD_SUFFIX))

    /** Loads and decrypts a previously saved private key for the given connection id. */
    fun loadPrivateKey(connectionId: Long): String? = loadSecret(secretKey(connectionId, PRIVATE_KEY_SUFFIX))

    /** Loads and decrypts a previously saved private-key passphrase for the given connection id. */
    fun loadKeyPassphrase(connectionId: Long): String? = loadSecret(secretKey(connectionId, KEY_PASSPHRASE_SUFFIX))

    /** Loads a trusted host-key fingerprint for the exact host and port combination. */
    fun loadTrustedHostKey(host: String, port: Int): TrustedHostKeyRecord? {
        val rawValue = loadSecret(hostKeySecretKey(host, port)) ?: return null
        val parts = rawValue.split("\n", limit = 2)
        if (parts.size != 2) {
            return null
        }
        return TrustedHostKeyRecord(
            keyType = parts[0],
            fingerprint = parts[1],
        )
    }

    /** Encrypts and persists a password, or removes it when the field is empty. */
    fun savePassword(connectionId: Long, password: String) {
        saveSecret(secretKey(connectionId, PASSWORD_SUFFIX), password)
    }

    /** Encrypts and persists a private key, or removes it when the field is empty. */
    fun savePrivateKey(connectionId: Long, privateKey: String) {
        saveSecret(secretKey(connectionId, PRIVATE_KEY_SUFFIX), privateKey)
    }

    /** Encrypts and persists a private-key passphrase, or removes it when the field is empty. */
    fun saveKeyPassphrase(connectionId: Long, passphrase: String) {
        saveSecret(secretKey(connectionId, KEY_PASSPHRASE_SUFFIX), passphrase)
    }

    /** Saves a trusted host key for later silent verification of the same host and port. */
    fun saveTrustedHostKey(host: String, port: Int, record: TrustedHostKeyRecord) {
        saveSecret(hostKeySecretKey(host, port), "${record.keyType}\n${record.fingerprint}")
    }

    /** Removes any stored password for the given connection id. */
    fun deletePassword(connectionId: Long) {
        preferences.edit().remove(secretKey(connectionId, PASSWORD_SUFFIX)).apply()
    }

    /** Removes any stored private key for the given connection id. */
    fun deletePrivateKey(connectionId: Long) {
        preferences.edit().remove(secretKey(connectionId, PRIVATE_KEY_SUFFIX)).apply()
    }

    /** Removes any stored private-key passphrase for the given connection id. */
    fun deleteKeyPassphrase(connectionId: Long) {
        preferences.edit().remove(secretKey(connectionId, KEY_PASSPHRASE_SUFFIX)).apply()
    }

    /** Removes every stored secret for a saved connection when the row is deleted. */
    fun deleteAllSecrets(connectionId: Long) {
        preferences.edit()
            .remove(secretKey(connectionId, PASSWORD_SUFFIX))
            .remove(secretKey(connectionId, PRIVATE_KEY_SUFFIX))
            .remove(secretKey(connectionId, KEY_PASSPHRASE_SUFFIX))
            .apply()
    }

    /** Encrypts plaintext with an AES key whose material stays inside Android Keystore. */
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encryptedBytes = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encryptedBytes
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /** Decrypts a previously stored ciphertext payload back into plaintext. */
    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val encryptedBytes = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, iv),
        )

        return String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8)
    }

    /** Reuses one app-owned AES key so we can protect arbitrary secret blobs, not just short passwords. */
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** Centralizes the encrypted-storage path so passwords, keys, passphrases, and host keys behave consistently. */
    private fun saveSecret(key: String, value: String) {
        if (value.isEmpty()) {
            preferences.edit().remove(key).apply()
            return
        }

        val encryptedValue = encrypt(value)
        preferences.edit().putString(key, encryptedValue).apply()
    }

    /** Returns null on missing or unreadable entries so secret corruption does not crash the form. */
    private fun loadSecret(key: String): String? {
        val storedValue = preferences.getString(key, null) ?: return null
        return runCatching { decrypt(storedValue) }.getOrNull()
    }

    private fun secretKey(connectionId: Long, suffix: String): String = "connection_${suffix}_$connectionId"

    /** Uses a stable encoded host/port key so trusted fingerprints do not collide with connection-id entries. */
    private fun hostKeySecretKey(host: String, port: Int): String {
        val encodedHost = Base64.encodeToString("$host:$port".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
        return "trusted_host_key_$encodedHost"
    }

    companion object {
        private const val PREFS_NAME = "secure-connection-passwords"
        private const val KEY_ALIAS = "ssh_terminal_connection_passwords"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private const val PASSWORD_SUFFIX = "password"
        private const val PRIVATE_KEY_SUFFIX = "private_key"
        private const val KEY_PASSPHRASE_SUFFIX = "key_passphrase"
    }
}
