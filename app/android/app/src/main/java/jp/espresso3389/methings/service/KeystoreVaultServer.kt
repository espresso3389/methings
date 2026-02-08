package jp.espresso3389.methings.service

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

class KeystoreVaultServer(private val context: Context) {
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (serverThread != null) return
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(8766)
                while (!Thread.currentThread().isInterrupted) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Vault server error", ex)
            }
        }.apply { isDaemon = true }
        serverThread?.start()
        Log.i(TAG, "Vault server started on 127.0.0.1:8766")
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverThread?.interrupt()
        serverThread = null
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        Thread {
            client.use { socket ->
                try {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                    val line = reader.readLine() ?: return@use
                    val parts = line.split(" ")
                    if (parts.isEmpty()) return@use
                    when (parts[0]) {
                        "CREATE" -> {
                            val name = parts.getOrNull(1) ?: return@use
                            ensureKey(name)
                            writer.write("OK\n")
                        }
                        "ENCRYPT" -> {
                            val name = parts.getOrNull(1) ?: return@use
                            val data = parts.drop(2).joinToString(" ")
                            val result = encrypt(name, data)
                            writer.write("OK $result\n")
                        }
                        "DECRYPT" -> {
                            val name = parts.getOrNull(1) ?: return@use
                            val data = parts.drop(2).joinToString(" ")
                            val result = decrypt(name, data)
                            writer.write("OK $result\n")
                        }
                        else -> writer.write("ERR unknown\n")
                    }
                    writer.flush()
                } catch (ex: Exception) {
                    Log.e(TAG, "Vault client error", ex)
                }
            }
        }.start()
    }

    private fun ensureKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        val existing = ks.getKey(aliasFor(alias), null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        keyGen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                aliasFor(alias),
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return keyGen.generateKey()
    }

    private fun encrypt(alias: String, plaintext: String): String {
        val key = ensureKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val enc = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(enc, Base64.NO_WRAP)
        return payload
    }

    private fun decrypt(alias: String, payload: String): String {
        val parts = payload.split(":", limit = 2)
        if (parts.size != 2) return ""
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val enc = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = ensureKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(enc), Charsets.UTF_8)
    }

    private fun aliasFor(name: String): String = "vault_service_" + name

    companion object {
        private const val TAG = "KeystoreVaultServer"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

