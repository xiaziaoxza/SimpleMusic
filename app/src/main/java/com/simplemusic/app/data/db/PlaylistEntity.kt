package com.simplemusic.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlist_songs")
data class PlaylistSongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val songId: Long,
    val position: Int,
    val playlistName: String = "default"
)

@Entity(tableName = "playback_queue")
data class PlaybackQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val songId: Long,
    val position: Int
)
