package com.codex.sshterminal.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SavedAuthType {
    PASSWORD,
    PRIVATE_KEY,
}

@Entity(tableName = "saved_connections")
data class SavedConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String = SavedAuthType.PASSWORD.name,
    val createdAt: Long,
    val updatedAt: Long,
    val lastConnectedAt: Long? = null,
)
