package jp.espresso3389.kugutz.perm

import android.content.Context
import jp.espresso3389.kugutz.db.PlainDbProvider
import jp.espresso3389.kugutz.db.SshKeyEntity
import java.security.MessageDigest

class SshKeyStore(context: Context) {
    private val dao = PlainDbProvider.get(context).sshKeyDao()

    fun upsert(key: String, label: String?, expiresAt: Long?): SshKeyEntity {
        val fingerprint = fingerprintFor(key)
        val entity = SshKeyEntity(
            fingerprint = fingerprint,
            key = key.trim(),
            label = label,
            expiresAt = expiresAt,
            createdAt = System.currentTimeMillis()
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

    fun listActive(now: Long = System.currentTimeMillis()): List<SshKeyEntity> {
        return dao.listAll().filter { it.expiresAt == null || it.expiresAt > now }
    }

    private fun fingerprintFor(key: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(key.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
