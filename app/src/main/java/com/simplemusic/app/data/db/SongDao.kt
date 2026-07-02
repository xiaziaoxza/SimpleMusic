package com.simplemusic.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE uri = :uri LIMIT 1")
    suspend fun getSongByUri(uri: String): SongEntity?

    @Query("""
        SELECT * FROM songs
        WHERE title LIKE '%' || :query || '%'
           OR artist LIKE '%' || :query || '%'
           OR album LIKE '%' || :query || '%'
        ORDER BY title COLLATE NOCASE ASC
    """)
    fun searchSongs(query: String): Flow<List<SongEntity>>

    @Query("SELECT DISTINCT artist FROM songs ORDER BY artist COLLATE NOCASE ASC")
    fun getArtists(): Flow<List<String>>

    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY album, title COLLATE NOCASE ASC")
    fun getSongsByArtist(artist: String): Flow<List<SongEntity>>

    @Query("SELECT DISTINCT album FROM songs ORDER BY album COLLATE NOCASE ASC")
    fun getAlbums(): Flow<List<String>>

    @Query("SELECT * FROM songs WHERE album = :album ORDER BY title COLLATE NOCASE ASC")
    fun getSongsByAlbum(album: String): Flow<List<SongEntity>>

    @Query("SELECT DISTINCT folderPath FROM songs WHERE folderPath != '' ORDER BY folderPath COLLATE NOCASE ASC")
    fun getFolders(): Flow<List<String>>

    @Query("SELECT * FROM songs WHERE folderPath = :folderPath ORDER BY title COLLATE NOCASE ASC")
    fun getSongsByFolder(folderPath: String): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Delete
    suspend fun deleteSong(song: SongEntity)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteSongById(id: Long)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongCount(): Int
}
