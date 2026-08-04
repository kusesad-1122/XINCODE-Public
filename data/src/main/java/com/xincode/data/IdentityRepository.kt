package com.xincode.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Repository for identity card CRUD + active-identity selection.
 * Active identity id is persisted via [SettingDao] (existing key-value store),
 * consistent with how currentModel/thinkingEnabled are stored elsewhere in the app.
 */
class IdentityRepository(private val database: AppDatabase) {

    private val identityDao = database.identityDao()
    private val settingDao = database.settingDao()

    companion object {
        const val ACTIVE_IDENTITY_KEY = "active_identity_id"
        /** id=1 is the "默认助手" preset inserted by MIGRATION_18_19. */
        const val DEFAULT_IDENTITY_ID = 1L
    }

    fun observeAll(): Flow<List<IdentityEntity>> = identityDao.observeAll()

    /**
     * Return an identity that is valid for a normal one-to-one chat.
     *
     * Older builds allowed a group-only role to be persisted as the global active identity.
     * That made every later "new chat" unexpectedly speak as a team member. Repair the
     * persisted value while reading it so existing installations recover on first launch.
     */
    suspend fun getActiveId(): Long {
        val requested = settingDao.get(ACTIVE_IDENTITY_KEY)?.toLongOrNull()
            ?: DEFAULT_IDENTITY_ID
        val requestedCard = identityDao.getById(requested)
        if (requestedCard != null && requestedCard.scope != IdentityEntity.SCOPE_GROUP) {
            return requested
        }

        val fallback = identityDao.getById(DEFAULT_IDENTITY_ID)
            ?.takeIf { it.scope != IdentityEntity.SCOPE_GROUP }
            ?: identityDao.getAll().firstOrNull { it.scope != IdentityEntity.SCOPE_GROUP }
            ?: return DEFAULT_IDENTITY_ID
        settingDao.put(ACTIVE_IDENTITY_KEY, fallback.id.toString())
        return fallback.id
    }

    fun observeActiveId(): Flow<Long> =
        settingDao.observe(ACTIVE_IDENTITY_KEY).map { it?.toLongOrNull() ?: DEFAULT_IDENTITY_ID }

    suspend fun setActive(id: Long) {
        val identity = identityDao.getById(id)
            ?: throw IllegalArgumentException("Identity $id does not exist")
        require(identity.scope != IdentityEntity.SCOPE_GROUP) {
            "Group-only identities cannot be used by normal chats"
        }
        settingDao.put(ACTIVE_IDENTITY_KEY, id.toString())
    }

    suspend fun create(name: String, systemPrompt: String, temperature: Float = 1.0f): Long {
        require(name.isNotBlank()) { "Identity name cannot be blank" }
        return identityDao.insert(
            IdentityEntity(name = name.trim(), systemPrompt = systemPrompt, temperature = temperature)
        )
    }

    suspend fun update(identity: IdentityEntity) {
        require(identity.name.isNotBlank()) { "Identity name cannot be blank" }
        identityDao.update(identity)
    }

    suspend fun rename(id: Long, name: String) {
        require(name.isNotBlank()) { "Identity name cannot be blank" }
        identityDao.rename(id, name.trim())
    }

    suspend fun setStarred(id: Long, starred: Boolean) = identityDao.setStarred(id, starred)

    /**
     * Delete an identity card. Sessions that referenced it keep their content but lose the
     * identity link (identityId set to null). If the deleted card was active, fall back to the
     * default preset id, then to any remaining card, in that order.
     */
    suspend fun delete(id: Long) {
        identityDao.nullifyIdentityInSessions(id)
        identityDao.getById(id)?.let { identityDao.delete(it) }

        if (getActiveId() == id) {
            val fallback = identityDao.getById(DEFAULT_IDENTITY_ID)?.id
                ?: identityDao.observeAll().first().firstOrNull()?.id
            if (fallback != null) setActive(fallback)
        }
    }
}
