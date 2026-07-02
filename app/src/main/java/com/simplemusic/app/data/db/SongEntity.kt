package com.simplemusic.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["title"]),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["folderPath"])
    ]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String = "未知艺术家",
    val album: String = "未知专辑",
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val uri: String,           // content:// 或 file:// URI
    val folderPath: String = "",
    val mimeType: String = "audio/mpeg",
    val sampleRate: Int = 44100,
    val bitRate: Int = 320000,
    val channels: Int = 2,
    val bitDepth: Int = 16,
    val dateAdded: Long = System.currentTimeMillis(),
    val artworkUri: String = "" // 专辑封面 URI
)
