package com.simplemusic.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 高品质音频引擎
 * 基于 Media3 ExoPlayer，配置最优音质参数
 */
class AudioEngine(context: Context) {

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    val exoPlayer: ExoPlayer

    init {
        // 渲染器工厂
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        // 轨道选择器
        val trackSelector = DefaultTrackSelector(context)

        // 加载控制 - 更大缓冲以应对无损格式
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                32_000,     // 最小缓冲 32s
                64_000,     // 最大缓冲 64s
                1_500,      // 播放前缓冲 1.5s
                3_000       // 重新缓冲后播放 3s
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 播放器监听
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val isPlaying = state == Player.STATE_READY && exoPlayer.playWhenReady
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = isPlaying,
                    isLoading = state == Player.STATE_BUFFERING
                )
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateNowPlaying()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
            }
        })
    }

    fun setMediaItem(uri: String, title: String, artist: String, album: String) {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setMediaId(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build()
            )
            .build()

        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        updateNowPlaying()
    }

    fun setMediaItems(uris: List<MediaItem>) {
        exoPlayer.setMediaItems(uris)
        exoPlayer.prepare()
    }

    fun play() {
        exoPlayer.playWhenReady = true
    }

    fun pause() {
        exoPlayer.playWhenReady = false
    }

    fun togglePlayPause() {
        if (exoPlayer.playWhenReady) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    fun skipToNext() {
        exoPlayer.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        exoPlayer.seekToPreviousMediaItem()
    }

    fun stop() {
        exoPlayer.stop()
    }

    fun release() {
        exoPlayer.release()
    }

    val currentPositionMs: Long get() = exoPlayer.currentPosition
    val durationMs: Long get() = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
    val isPlaying: Boolean get() = exoPlayer.playWhenReady
    val currentMediaItemIndex: Int get() = exoPlayer.currentMediaItemIndex
    val mediaItemCount: Int get() = exoPlayer.mediaItemCount

    @OptIn(UnstableApi::class)
    fun getAudioInfo(): AudioInfo {
        val audioFormat = exoPlayer.audioFormat ?: return AudioInfo()
        return AudioInfo(
            sampleRate = audioFormat.sampleRate,
            channelCount = audioFormat.channelCount,
            encoding = audioFormat.pcmEncoding,
            bitrate = audioFormat.bitrate
        )
    }

    private fun updateNowPlaying() {
        val mediaItem = exoPlayer.currentMediaItem ?: return
        _playbackState.value = _playbackState.value.copy(
            currentTitle = mediaItem.mediaMetadata.title?.toString() ?: "",
            currentArtist = mediaItem.mediaMetadata.artist?.toString() ?: "",
            currentAlbum = mediaItem.mediaMetadata.albumTitle?.toString() ?: "",
            currentUri = mediaItem.mediaId ?: ""
        )
    }
}

data class PlaybackState(
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentTitle: String = "",
    val currentArtist: String = "",
    val currentAlbum: String = "",
    val currentUri: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0
)

data class AudioInfo(
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val encoding: Int = 0,
    val bitrate: Int = 0
) {
    val encodingName: String get() = when (encoding) {
        C.ENCODING_PCM_16BIT -> "PCM 16bit"
        C.ENCODING_PCM_24BIT -> "PCM 24bit"
        C.ENCODING_PCM_32BIT -> "PCM 32bit"
        C.ENCODING_PCM_FLOAT -> "PCM Float"
        else -> "Unknown"
    }
}
