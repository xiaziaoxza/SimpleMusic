package com.simplemusic.app.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplemusic.app.data.db.SongEntity
import com.simplemusic.app.data.repository.MusicRepository
import com.simplemusic.app.player.AudioEngine
import com.simplemusic.app.player.AudioInfo
import com.simplemusic.app.player.EqualizerInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MainUiState(
    val songs: List<SongEntity> = emptyList(),
    val currentTitle: String = "",
    val currentArtist: String = "",
    val currentAlbum: String = "",
    val currentSongId: Long = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val audioInfo: AudioInfo = AudioInfo(),
    val isBitPerfect: Boolean = false,
    val equalizerInfo: EqualizerInfo? = null,
    val songCount: Int = 0,
    val isScanning: Boolean = false,
    val scanProgress: String = "",
    // 可视化数据
    val amplitude: Float = 0f,
    val fftBands: FloatArray = FloatArray(128)
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var currentPlaylist: List<SongEntity> = emptyList()
    private var currentIndex: Int = -1
    private val engine: AudioEngine get() = AudioEngine.instance!!

    init {
        // 监听歌曲库
        viewModelScope.launch {
            repository.getAllSongs().collect { songs ->
                _uiState.update { it.copy(songs = songs, songCount = songs.size) }
            }
        }

        // 轮询播放状态 + 可视化数据
        viewModelScope.launch {
            while (true) {
                delay(100)
                try {
                    val e = AudioEngine.instance ?: continue
                    _uiState.update {
                        it.copy(
                            positionMs = e.currentPosition,
                            durationMs = e.duration,
                            isPlaying = e.isPlaying,
                            audioInfo = e.getAudioInfo(),
                            isBitPerfect = e.isBitPerfect.value,
                            amplitude = AudioEngine.visualizerAmplitude,
                            fftBands = AudioEngine.visualizerFftBands.copyOf()
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        // 监听 Bit-Perfect 状态
        viewModelScope.launch {
            AudioEngine.instance?.isBitPerfect?.collect {
                _uiState.update { s -> s.copy(isBitPerfect = it) }
            }
        }

        // 初始扫描
        viewModelScope.launch { scanMediaStore() }
    }

    // ─── 播放控制 ───

    fun playSong(song: SongEntity) {
        val songs = _uiState.value.songs
        currentPlaylist = songs
        currentIndex = songs.indexOfFirst { it.id == song.id }

        engine.playMedia(
            uri = song.uri,
            title = song.title,
            artist = song.artist,
            album = song.album,
            startIndex = currentIndex.coerceAtLeast(0),
            items = songs.takeIf { it.size > 1 }?.map {
                Pair(it.uri, Triple(it.title, it.artist, it.album))
            } ?: emptyList()
        )

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
        engine.togglePlayPause()
    }

    fun seekTo(pos: Long) {
        engine.seekTo(pos)
    }

    fun skipToNext() {
        engine.skipNext()
    }

    fun skipToPrevious() {
        if (engine.currentPosition > 3000) engine.seekTo(0)
        else engine.skipPrevious()
    }

    // ─── USB DAC ───

    fun enableBitPerfect(sampleRate: Int = 0, bitDepth: Int = 0) {
        engine.enableBitPerfect(sampleRate, bitDepth)
    }

    fun disableBitPerfect() {
        engine.disableBitPerfect()
    }

    // ─── EQ ───

    fun applyEqualizer(enabled: Boolean, bandLevels: ShortArray) {
        engine.applyEqualizer(enabled, bandLevels)
    }

    fun applyBassBoost(enabled: Boolean, strength: Short) {
        engine.applyBassBoost(enabled, strength)
    }

    fun applyLoudness(enabled: Boolean, gainMb: Int) {
        engine.applyLoudness(enabled, gainMb)
    }

    // ─── 歌曲管理 ───

    fun deleteSong(id: Long) {
        viewModelScope.launch { repository.deleteSong(id) }
    }

    fun scanMediaStore() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanProgress = "扫描中...") }
            try {
                val count = repository.scanLocalAudio()
                _uiState.update { it.copy(isScanning = false, scanProgress = "找到 $count 首") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false, scanProgress = "失败: ${e.message}") }
            }
        }
    }

    fun importFromDirectory(dirUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanProgress = "导入中...") }
            try {
                val count = repository.importSongsFromDirectory(dirUri)
                _uiState.update { it.copy(isScanning = false, scanProgress = "导入 $count 首") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false, scanProgress = "失败: ${e.message}") }
            }
        }
    }

    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanProgress = "导入 ${uris.size} 个...") }
            var ok = 0
            for (u in uris) {
                try { if (repository.copyToAppStorage(u) != null) ok++ } catch (_: Exception) {}
            }
            repository.scanLocalAudio()
            _uiState.update { it.copy(isScanning = false, scanProgress = "完成: $ok/${uris.size}") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 不要在这里 release engine，service 生命周期管理
    }
}
