package jp.espresso3389.kugutz.service

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SqlcipherKeyManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun ensureKeyFile(): File? {
        val encrypted = prefs.getString(PREF_ENC_KEY, null)
        val iv = prefs.getString(PREF_IV, null)

        val keyBytes = if (encrypted == null || iv == null) {
            val rawKey = generateRandomKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val enc = cipher.doFinal(rawKey)
            val ivBytes = cipher.iv
            prefs.edit()
                .putString(PREF_ENC_KEY, Base64.encodeToString(enc, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(ivBytes, Base64.NO_WRAP))
                .apply()
            rawKey
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
            val encBytes = Base64.decode(encrypted, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, ivBytes))
            cipher.doFinal(encBytes)
        }

        return try {
            val protectedDir = File(context.filesDir, "protected/secrets")
            protectedDir.mkdirs()
            val keyFile = File(protectedDir, "sqlcipher.key")
            keyFile.writeText(Base64.encodeToString(keyBytes, Base64.NO_WRAP))
            keyFile
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to write sqlcipher key file", ex)
            null
        }
    }

    fun cleanupLegacySecretsDir() {
        try {
            val legacyDir = File(context.filesDir, "secrets")
            if (legacyDir.exists() && legacyDir.isDirectory) {
                legacyDir.deleteRecursively()
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to remove legacy secrets dir", ex)
        }
    }

    private fun generateRandomKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey().encoded
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        keyGen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return keyGen.generateKey()
    }

    companion object {
        private const val TAG = "SqlcipherKeyManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "sqlcipher_key_alias"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "sqlcipher_secrets"
        private const val PREF_ENC_KEY = "enc_sqlcipher_key"
        private const val PREF_IV = "enc_sqlcipher_iv"
    }
}
