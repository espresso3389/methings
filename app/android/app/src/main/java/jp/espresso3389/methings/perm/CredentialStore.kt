package jp.espresso3389.methings.perm

import android.content.Context
import jp.espresso3389.methings.db.CredentialEntity
import jp.espresso3389.methings.db.PlainDbProvider
import jp.espresso3389.methings.security.CredentialCipher

class CredentialStore(context: Context) {
    private val dao = PlainDbProvider.get(context).credentialDao()
    private val cipher = CredentialCipher(context)

    fun set(name: String, value: String) {
        val encrypted = cipher.encrypt(value)
        dao.upsert(
            CredentialEntity(
                name = name,
                value = encrypted,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun get(name: String): CredentialEntity? {
        val row = dao.getByName(name) ?: return null
        val decrypted = cipher.decrypt(row.value) ?: return null
        return row.copy(value = decrypted)
    }

    fun delete(name: String) {
        dao.deleteByName(name)
    }

    fun list(): List<CredentialEntity> {
        return dao.listAll().mapNotNull { row ->
            val decrypted = cipher.decrypt(row.value) ?: return@mapNotNull null
            row.copy(value = decrypted)
        }
    }
}
