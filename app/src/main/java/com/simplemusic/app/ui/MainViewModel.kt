package com.simplemusic.app.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplemusic.app.SimpleMusicApp
import com.simplemusic.app.data.db.SongEntity
import com.simplemusic.app.data.repository.MusicRepository
import com.simplemusic.app.player.AudioEngine
import com.simplemusic.app.player.AudioInfo
import com.simplemusic.app.player.PlaybackService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MainUiState(
    val songs: List<SongEntity> = emptyList(),
    val currentTitle: String = "",
    val currentArtist: String = "",
    val currentAlbum: String = "",
    val currentSongId: Long = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val audioInfo: AudioInfo = AudioInfo(),
    val songCount: Int = 0,
    val isScanning: Boolean = false,
    val scanProgress: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    val audioEngine = AudioEngine(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var currentPlaylist: List<SongEntity> = emptyList()
    private var currentIndex: Int = -1

    init {
        // 监听数据库中的歌曲
        viewModelScope.launch {
            repository.getAllSongs().collect { songs ->
                _uiState.update { it.copy(songs = songs, songCount = songs.size) }
            }
        }

        // 监听播放器状态更新
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(250)
                val player = audioEngine.exoPlayer
                if (player.playWhenReady && player.playbackState == androidx.media3.common.Player.STATE_READY) {
                    _uiState.update {
                        it.copy(
                            positionMs = player.currentPosition,
                            durationMs = if (player.duration > 0) player.duration else 0,
                            isPlaying = player.playWhenReady,
                            audioInfo = audioEngine.getAudioInfo()
                        )
                    }
                }
            }
        }

        // 初始扫描
        viewModelScope.launch {
            scanMediaStore()
        }
    }

    // ===== 播放控制 =====

    fun playSong(song: SongEntity) {
        // 把当前歌曲列表作为播放队列
        val songs = _uiState.value.songs
        currentPlaylist = songs
        currentIndex = songs.indexOfFirst { it.id == song.id }

        audioEngine.setMediaItem(
            uri = song.uri,
            title = song.title,
            artist = song.artist,
            album = song.album
        )
        audioEngine.play()

        _uiState.update {
            it.copy(
                currentTitle = song.title,
                currentArtist = song.artist,
                currentAlbum = song.album,
                currentSongId = song.id,
                isPlaying = true
            )
        }
    }

    fun togglePlayPause() {
        audioEngine.togglePlayPause()
        _uiState.update { it.copy(isPlaying = audioEngine.isPlaying) }
    }

    fun seekTo(positionMs: Long) {
        audioEngine.seekTo(positionMs)
    }

    fun skipToNext() {
        if (currentPlaylist.isEmpty() || currentIndex < 0) return
        val nextIndex = (currentIndex + 1) % currentPlaylist.size
        val nextSong = currentPlaylist[nextIndex]
        currentIndex = nextIndex
        playSong(nextSong)
    }

    fun skipToPrevious() {
        if (currentPlaylist.isEmpty() || currentIndex < 0) return
        if (audioEngine.currentPositionMs > 3000) {
            // 播了超过3秒，回到开头
            audioEngine.seekTo(0)
        } else {
            val prevIndex = if (currentIndex > 0) currentIndex - 1 else currentPlaylist.size - 1
            val prevSong = currentPlaylist[prevIndex]
            currentIndex = prevIndex
            playSong(prevSong)
        }
    }

    // ===== 歌曲管理 =====

    fun deleteSong(id: Long) {
        viewModelScope.launch {
            repository.deleteSong(id)
        }
    }

    // ===== 扫描与导入 =====

    fun scanMediaStore() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanProgress = "正在扫描系统媒体库...") }

            try {
                val count = repository.scanLocalAudio()
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        scanProgress = "扫描完成，找到 $count 首歌曲"
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Scan failed", e)
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        scanProgress = "扫描失败: ${e.message}"
                    )
                }
            }
        }
    }

    fun importFromDirectory(dirUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanProgress = "正在扫描目录...") }

            try {
                val count = repository.importSongsFromDirectory(dirUri)
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        scanProgress = "导入完成: $count 首歌曲"
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Import dir failed", e)
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        scanProgress = "导入失败: ${e.message}"
                    )
                }
            }
        }
    }

    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanProgress = "正在导入 ${uris.size} 个文件...") }

            var imported = 0
            for (uri in uris) {
                try {
                    val storedUri = repository.copyToAppStorage(uri)
                    if (storedUri != null) imported++
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Import file failed: $uri", e)
                }
            }

            // 重新扫描以更新数据库
            repository.scanLocalAudio()

            _uiState.update {
                it.copy(
                    isScanning = false,
                    scanProgress = "导入完成: $imported/${uris.size} 个文件"
                )
            }
        }
    }

    // ===== 生命周期 =====

    override fun onCleared() {
        super.onCleared()
        audioEngine.release()
    }
}
