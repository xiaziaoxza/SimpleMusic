package com.simplemusic.app.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.simplemusic.app.data.db.AppDatabase
import com.simplemusic.app.data.db.PlaybackQueueEntity
import com.simplemusic.app.data.db.PlaylistSongEntity
import com.simplemusic.app.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class MusicRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val songDao = db.songDao()
    private val playlistDao = db.playlistDao()

    // ========== 扫描本地媒体库 ==========

    suspend fun scanLocalAudio(): Int = withContext(Dispatchers.IO) {
        val songs = mutableListOf<SongEntity>()
        val resolver = context.contentResolver

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "未知标题"
                val artist = cursor.getString(artistCol) ?: "未知艺术家"
                val album = cursor.getString(albumCol) ?: "未知专辑"
                val duration = cursor.getLong(durationCol)
                val size = cursor.getLong(sizeCol)
                val data = cursor.getString(dataCol) ?: continue
                val mime = cursor.getString(mimeCol) ?: "audio/mpeg"
                val dateAdded = cursor.getLong(dateCol)
                val albumId = cursor.getLong(albumIdCol)

                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                ).toString()

                val folderPath = File(data).parent ?: ""
                val artworkUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId
                ).toString()

                // 推断音频参数
                val (sampleRate, bitRate, channels, bitDepth) = inferAudioParams(mime, size, duration)

                songs.add(
                    SongEntity(
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        sizeBytes = size,
                        uri = uri,
                        folderPath = folderPath,
                        mimeType = mime,
                        sampleRate = sampleRate,
                        bitRate = bitRate,
                        channels = channels,
                        bitDepth = bitDepth,
                        dateAdded = dateAdded * 1000L,
                        artworkUri = artworkUri
                    )
                )
            }
        }

        if (songs.isNotEmpty()) {
            songDao.insertSongs(songs)
        }
        Log.d("MusicRepository", "Scanned ${songs.size} songs from MediaStore")
        songs.size
    }

    // ========== 从文件目录导入歌曲 ==========

    suspend fun importSongsFromDirectory(dirUri: Uri): Int = withContext(Dispatchers.IO) {
        val songs = mutableListOf<SongEntity>()
        val resolver = context.contentResolver

        try {
            scanDirectoryRecursive(resolver, dirUri, songs)
        } catch (e: Exception) {
            Log.e("MusicRepository", "Import directory failed", e)
        }

        if (songs.isNotEmpty()) {
            songDao.insertSongs(songs)
        }
        songs.size
    }

    private fun scanDirectoryRecursive(
        resolver: ContentResolver,
        parentUri: Uri,
        songs: MutableList<SongEntity>
    ) {
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            parentUri,
            android.provider.DocumentsContract.getTreeDocumentId(parentUri)
        )

        resolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_SIZE,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val size = cursor.getLong(2)
                val mime = cursor.getString(3) ?: ""

                if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    // 递归扫描子目录
                    val childUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        parentUri, docId
                    )
                    scanDirectoryRecursive(resolver, childUri, songs)
                } else if (isAudioFile(name, mime)) {
                    val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        parentUri, docId
                    )
                    val song = buildSongFromDocumentUri(resolver, fileUri, name, size, mime)
                    if (song != null) songs.add(song)
                }
            }
        }
    }

    private fun isAudioFile(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun buildSongFromDocumentUri(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        sizeBytes: Long,
        mimeType: String
    ): SongEntity? {
        return try {
            val title = displayName.substringBeforeLast(".")
            val (sampleRate, bitRate, channels, bitDepth) = inferAudioParams(mimeType, sizeBytes, 0)

            // 直接使用 SAF URI（应用已有持久化读取权限）
            val finalUri = uri.toString()

            SongEntity(
                title = title,
                uri = finalUri,
                sizeBytes = sizeBytes,
                mimeType = mimeType.ifEmpty { "audio/mpeg" },
                sampleRate = sampleRate,
                bitRate = bitRate,
                channels = channels,
                bitDepth = bitDepth,
                folderPath = ""
            )
        } catch (e: Exception) {
            null
        }
    }

    // ========== 复制文件到应用内部存储 ==========

    suspend fun copyToAppStorage(sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val musicDir = File(context.filesDir, "music")
            if (!musicDir.exists()) musicDir.mkdirs()

            val resolver = context.contentResolver
            val displayName = getDisplayName(resolver, sourceUri) ?: "unknown.mp3"
            val destFile = File(musicDir, displayName)

            // 去重：用 hash 检查
            if (destFile.exists()) {
                val sourceHash = computeFileHash(resolver, sourceUri)
                if (sourceHash != null) {
                    val hashFileName = "${sourceHash}_$displayName"
                    val hashFile = File(musicDir, hashFileName)
                    if (!hashFile.exists()) {
                        return@withContext copyFile(resolver, sourceUri, hashFile)
                    }
                    return@withContext hashFile.toURI().toString()
                }
            }

            return@withContext copyFile(resolver, sourceUri, destFile)
        } catch (e: Exception) {
            Log.e("MusicRepository", "Copy failed: ${e.message}")
            null
        }
    }

    private fun copyFile(resolver: ContentResolver, sourceUri: Uri, destFile: File): String? {
        resolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        return destFile.toURI().toString()
    }

    private fun computeFileHash(resolver: ContentResolver, uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun getDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf("_display_name"), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    // ========== 音频参数推断 ==========

    private data class AudioParams(
        val sampleRate: Int,
        val bitRate: Int,
        val channels: Int,
        val bitDepth: Int
    )

    private fun inferAudioParams(
        mimeType: String,
        sizeBytes: Long,
        durationMs: Long
    ): AudioParams {
        var sampleRate = 44100
        var bitRate = 320000
        var channels = 2
        var bitDepth = 16

        when {
            mimeType.contains("flac") -> {
                sampleRate = 48000
                bitRate = if (durationMs > 0) {
                    ((sizeBytes * 8 * 1000) / durationMs).toInt()
                } else 900000
                bitDepth = 24
                channels = 2
            }
            mimeType.contains("wav") -> {
                sampleRate = 48000
                bitRate = 1411000
                bitDepth = 16
                channels = 2
            }
            mimeType.contains("aac") || mimeType.contains("mp4") -> {
                sampleRate = 44100
                bitRate = 256000
                channels = 2
                bitDepth = 16
            }
            mimeType.contains("ogg") || mimeType.contains("opus") -> {
                sampleRate = 48000
                bitRate = 192000
                channels = 2
                bitDepth = 16
            }
            // MP3 默认
        }

        return AudioParams(sampleRate, bitRate, channels, bitDepth)
    }

    // ========== 歌曲操作 ==========

    fun getAllSongs(): Flow<List<SongEntity>> = songDao.getAllSongs()
    fun searchSongs(query: String): Flow<List<SongEntity>> = songDao.searchSongs(query)
    fun getArtists(): Flow<List<String>> = songDao.getArtists()
    fun getSongsByArtist(artist: String): Flow<List<SongEntity>> = songDao.getSongsByArtist(artist)
    fun getAlbums(): Flow<List<String>> = songDao.getAlbums()
    fun getSongsByAlbum(album: String): Flow<List<SongEntity>> = songDao.getSongsByAlbum(album)
    fun getFolders(): Flow<List<String>> = songDao.getFolders()
    fun getSongsByFolder(folderPath: String): Flow<List<SongEntity>> = songDao.getSongsByFolder(folderPath)

    suspend fun getSongById(id: Long): SongEntity? = songDao.getSongById(id)
    suspend fun deleteSong(id: Long) = songDao.deleteSongById(id)
    suspend fun deleteAllSongs() = songDao.deleteAll()
    suspend fun getSongCount(): Int = songDao.getSongCount()

    // ========== 播放列表操作 ==========

    fun getPlaylistSongs(playlistName: String = "default"): Flow<List<SongEntity>> =
        playlistDao.getPlaylistSongs(playlistName)

    suspend fun addToPlaylist(songId: Long, position: Int, playlistName: String = "default") {
        playlistDao.addToPlaylist(PlaylistSongEntity(songId = songId, position = position, playlistName = playlistName))
    }

    suspend fun removeFromPlaylist(playlistName: String, songId: Long) {
        playlistDao.removeFromPlaylist(playlistName, songId)
    }

    suspend fun clearPlaylist(playlistName: String) {
        playlistDao.clearPlaylist(playlistName)
    }

    // ========== 播放队列 ==========

    fun getPlaybackQueue(): Flow<List<SongEntity>> = playlistDao.getPlaybackQueue()

    suspend fun setQueue(songIds: List<Long>) {
        playlistDao.clearQueue()
        songIds.forEachIndexed { index, id ->
            playlistDao.addToQueue(PlaybackQueueEntity(songId = id, position = index))
        }
    }

    suspend fun clearQueue() = playlistDao.clearQueue()
    suspend fun getQueueSize(): Int = playlistDao.getQueueSize()

    // ========== jaudiotagger 真实元数据解析 ==========

    /**
     * 使用 jaudiotagger 直接读取音频文件头部，获取真实的位深、采样率等参数
     * 这是绕过 Android MediaMetadataRetriever 限制的关键方法
     */
    fun resolveTrueMetadata(filePath: String): AudioFileMetadata? {
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) return null

            // 关闭 jaudiotagger 日志
            java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF

            val audioFile = org.jaudiotagger.audio.AudioFileIO.read(file)
            val header = audioFile.audioHeader
            val tag = audioFile.tag

            AudioFileMetadata(
                sampleRate = header.sampleRateAsNumber,
                bitDepth = header.bitsPerSize,
                channels = if (header.channels == "Stereo") 2 else 1,
                durationMs = (header.trackLength * 1000).toLong(),
                bitRate = header.bitRateAsNumber.toInt(),
                encoding = header.encodingType,
                isLossless = header.isLossless,
                format = header.format,
                artist = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST),
                album = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM),
                title = tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)
            )
        } catch (e: Exception) {
            Log.w("MusicRepository", "jaudiotagger parse failed: ${filePath} - ${e.message}")
            null
        }
    }

    data class AudioFileMetadata(
        val sampleRate: Int = 44100,
        val bitDepth: Int = 16,
        val channels: Int = 2,
        val durationMs: Long = 0,
        val bitRate: Int = 0,
        val encoding: String = "",
        val isLossless: Boolean = false,
        val format: String = "",
        val artist: String? = null,
        val album: String? = null,
        val title: String? = null
    )

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "aac", "ogg", "opus",
            "m4a", "wma", "ape", "aiff", "alac", "dsf", "dff"
        )
    }
}
