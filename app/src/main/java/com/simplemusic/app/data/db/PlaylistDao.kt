package com.simplemusic.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON s.id = ps.songId
        WHERE ps.playlistName = :playlistName
        ORDER BY ps.position ASC
    """)
    fun getPlaylistSongs(playlistName: String = "default"): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToPlaylist(entry: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistName = :playlistName AND songId = :songId")
    suspend fun removeFromPlaylist(playlistName: String, songId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistName = :playlistName")
    suspend fun clearPlaylist(playlistName: String)

    // 播放队列
    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playback_queue pq ON s.id = pq.songId
        ORDER BY pq.position ASC
    """)
    fun getPlaybackQueue(): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToQueue(entry: PlaybackQueueEntity)

    @Query("DELETE FROM playback_queue")
    suspend fun clearQueue()

    @Query("SELECT COUNT(*) FROM playback_queue")
    suspend fun getQueueSize(): Int

    @Query("SELECT * FROM playback_queue ORDER BY position ASC")
    suspend fun getQueueEntries(): List<PlaybackQueueEntity>
}
