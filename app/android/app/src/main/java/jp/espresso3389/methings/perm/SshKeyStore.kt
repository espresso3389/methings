package jp.espresso3389.methings.perm

import android.content.Context
import jp.espresso3389.methings.db.PlainDbProvider
import jp.espresso3389.methings.db.SshKeyEntity
import java.security.MessageDigest

class SshKeyStore(context: Context) {
    private val dao = PlainDbProvider.get(context).sshKeyDao()

    fun upsert(key: String, label: String?, expiresAt: Long?): SshKeyEntity {
        return upsertMerge(key, label, expiresAt)
    }

    fun upsertMerge(key: String, label: String?, expiresAt: Long?): SshKeyEntity {
        val fingerprint = fingerprintFor(key)
        val existing = dao.getByFingerprint(fingerprint)
        val createdAt = existing?.createdAt ?: System.currentTimeMillis()
        val finalLabel = label?.trim()?.takeIf { it.isNotBlank() } ?: existing?.label
        val finalExpiresAt = expiresAt ?: existing?.expiresAt
        val entity = SshKeyEntity(
            fingerprint = fingerprint,
            key = key.trim(),
            label = finalLabel,
            expiresAt = finalExpiresAt,
            createdAt = createdAt
        )
        dao.upsert(entity)
        return entity
    }

    fun listAll(): List<SshKeyEntity> {
        return dao.listAll()
    }

    fun delete(fingerprint: String) {
        dao.deleteByFingerprint(fingerprint)
    }

    fun findByPublicKey(key: String): SshKeyEntity? {
        val normalized = key.trim()
        if (normalized.isEmpty()) return null
        return dao.listAll().firstOrNull { it.key.trim() == normalized }
    }

    fun listActive(now: Long = System.currentTimeMillis()): List<SshKeyEntity> {
        return dao.listAll().filter { it.expiresAt == null || it.expiresAt > now }
    }

    private fun fingerprintFor(key: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(key.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
