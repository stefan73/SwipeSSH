package com.codex.sshterminal.data.repository

import com.codex.sshterminal.data.local.SavedConnectionDao
import com.codex.sshterminal.data.local.SavedConnectionEntity
import kotlinx.coroutines.flow.Flow

data class SaveConnectionResult(
    val id: Long,
    val wasUpdate: Boolean,
    val connection: SavedConnectionEntity,
)

/** Wraps Room access for saved connection metadata. */
class ConnectionsRepository(
    private val savedConnectionDao: SavedConnectionDao,
) {
    /** Streams saved connections ordered by last successful use. */
    fun observeSavedConnections(): Flow<List<SavedConnectionEntity>> {
        return savedConnectionDao.observeSavedConnections()
    }

    /** Creates or updates a saved connection entry without storing secret material in Room. */
    suspend fun saveConnection(
        selectedConnectionId: Long?,
        name: String,
        host: String,
        port: Int,
        username: String,
        authType: String,
    ): SaveConnectionResult {
        val now = System.currentTimeMillis()
        val normalizedName = name.ifBlank { "$username@$host:$port" }

        val existing = selectedConnectionId?.let { savedConnectionDao.getById(it) }
        val connection = if (existing != null) {
            existing.copy(
                name = normalizedName,
                host = host,
                port = port,
                username = username,
                authType = authType,
                updatedAt = now,
            )
        } else {
            SavedConnectionEntity(
                name = normalizedName,
                host = host,
                port = port,
                username = username,
                authType = authType,
                createdAt = now,
                updatedAt = now,
            )
        }

        return if (existing != null) {
            savedConnectionDao.update(connection)
            SaveConnectionResult(
                id = connection.id,
                wasUpdate = true,
                connection = connection,
            )
        } else {
            val newId = savedConnectionDao.insert(connection)
            SaveConnectionResult(
                id = newId,
                wasUpdate = false,
                connection = connection.copy(id = newId),
            )
        }
    }

    /** Deletes a saved connection row by id. */
    suspend fun deleteConnection(connectionId: Long) {
        savedConnectionDao.deleteById(connectionId)
    }

    /** Records the last successful connect time for list ordering and recency. */
    suspend fun markConnectionSuccessful(connectionId: Long) {
        val now = System.currentTimeMillis()
        val existing = savedConnectionDao.getById(connectionId) ?: return
        savedConnectionDao.update(
            existing.copy(
                updatedAt = now,
                lastConnectedAt = now,
            ),
        )
    }
}
