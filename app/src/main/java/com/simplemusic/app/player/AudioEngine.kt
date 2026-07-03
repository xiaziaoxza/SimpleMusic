package com.simplemusic.app.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * 高品质音频引擎 - 基于 AURALIS 架构重写
 * 特性：双 Player 无缝淡入淡出 | USB DAC Bit-Perfect | 实时频谱分析 | 系统 EQ
 */
@OptIn(UnstableApi::class)
class AudioEngine(private val context: Context) {

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isBitPerfect = MutableStateFlow(false)
    val isBitPerfect: StateFlow<Boolean> = _isBitPerfect.asStateFlow()

    private val _equalizerInfo = MutableStateFlow<EqualizerInfo?>(null)
    val equalizerInfo: StateFlow<EqualizerInfo?> = _equalizerInfo.asStateFlow()

    // 双 Player 架构
    var mainPlayer: ExoPlayer
    private var shadowPlayer: ExoPlayer?
    var isUsingPlayer2 = false
    private var isCrossfading = false
    private var targetVolume = 1.0f

    // 音频分析
    @Volatile var currentAmplitude: Float = 0f
    val fftBands = FloatArray(128)

    // EQ
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var audioSessionId = 0

    // USB DAC
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isCurrentlyBitPerfect = false

    companion object {
        @Volatile var instance: AudioEngine? = null

        // 音频分析数据（供 UI 读取）
        @Volatile var visualizerAmplitude: Float = 0f
        val visualizerFftBands = FloatArray(128)
    }

    init {
        instance = this

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                val originalSink = DefaultAudioSink.Builder(ctx)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(true)
                    .build()
                return VisualizerAudioSink(originalSink)
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50000, 100000, 2500, 5000)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        mainPlayer = buildPlayer(renderersFactory, audioAttributes, loadControl)
        shadowPlayer = buildPlayer(renderersFactory, audioAttributes, loadControl)

        mainPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(id: Int) {
                audioSessionId = id
                initEqualizer(id)
            }
            override fun onPlaybackStateChanged(state: Int) {
                updateState()
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                updateNowPlaying()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState()
            }
        })

        audioSessionId = mainPlayer.audioSessionId
        initEqualizer(audioSessionId)

        // 默认关闭 offload（为 Bit-Perfect 做准备）
        setAudioOffload(false)

        // 启动 crossfade 轮询
        startCrossfadeLoop()
    }

    private fun buildPlayer(
        rf: DefaultRenderersFactory,
        attrs: AudioAttributes,
        lc: DefaultLoadControl
    ): ExoPlayer {
        return ExoPlayer.Builder(context, rf)
            .setAudioAttributes(attrs, true)
            .setLoadControl(lc)
            .build()
    }

    // ─── 播放控制 ───

    fun playMedia(uri: String, title: String, artist: String, album: String,
                  startIndex: Int = 0, items: List<Pair<String, Triple<String, String, String>>> = emptyList()) {
        val mediaItems = if (items.isNotEmpty()) {
            items.map { (u, meta) ->
                MediaItem.Builder()
                    .setUri(Uri.parse(u))
                    .setMediaId(u)
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(meta.first)
                        .setArtist(meta.second)
                        .setAlbumTitle(meta.third)
                        .build())
                    .build()
            }
        } else {
            listOf(MediaItem.Builder()
                .setUri(Uri.parse(uri))
                .setMediaId(uri)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build())
                .build())
        }

        mainPlayer.setMediaItems(mediaItems, startIndex, 0L)
        mainPlayer.prepare()
        mainPlayer.playWhenReady = true
        updateNowPlaying()
    }

    fun play() = run { mainPlayer.playWhenReady = true }
    fun pause() = run { mainPlayer.playWhenReady = false }
    fun togglePlayPause() {
        if (mainPlayer.playWhenReady) pause() else play()
    }
    fun seekTo(ms: Long) { mainPlayer.seekTo(ms) }
    fun skipNext() { mainPlayer.seekToNextMediaItem() }
    fun skipPrevious() { mainPlayer.seekToPreviousMediaItem() }
    fun stop() { mainPlayer.stop() }

    fun release() {
        instance = null
        equalizer?.release(); equalizer = null
        bassBoost?.release(); bassBoost = null
        loudnessEnhancer?.release(); loudnessEnhancer = null
        tryDisableUsbBitPerfect()
        mainPlayer.release()
        shadowPlayer?.release()
        shadowPlayer = null
    }

    // 播放状态
    val currentPosition: Long get() = mainPlayer.currentPosition
    val duration: Long get() = if (mainPlayer.duration > 0) mainPlayer.duration else 0
    val isPlaying: Boolean get() = mainPlayer.playWhenReady
    val currentIndex: Int get() = mainPlayer.currentMediaItemIndex
    val mediaCount: Int get() = mainPlayer.mediaItemCount

    fun getAudioInfo(): AudioInfo {
        val fmt = mainPlayer.audioFormat ?: return AudioInfo()
        return AudioInfo(
            sampleRate = fmt.sampleRate,
            channelCount = fmt.channelCount,
            encoding = fmt.pcmEncoding,
            bitrate = fmt.bitrate
        )
    }

    // ─── Crossfade ───

    private fun startCrossfadeLoop() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                try { checkCrossfade() } catch (_: Exception) {}
                handler.postDelayed(this, 500)
            }
        }
        handler.post(runnable)
    }

    private fun checkCrossfade() {
        if (isCurrentlyBitPerfect) return
        val main = if (isUsingPlayer2) shadowPlayer else mainPlayer
        val shadow = if (isUsingPlayer2) mainPlayer else shadowPlayer
        if (main == null || shadow == null || !main.isPlaying) return

        val crossfadeMs = 5000L
        val duration = main.duration
        val position = main.currentPosition
        if (duration <= 0) return

        val timeRemaining = duration - position

        // 提前 15s 缓冲
        if (timeRemaining in (crossfadeMs + 1)..(crossfadeMs + 15000) &&
            main.hasNextMediaItem() && shadow.playbackState == Player.STATE_IDLE) {
            val items = (0 until main.mediaItemCount).map { main.getMediaItemAt(it) }
            shadow.setMediaItems(items, main.nextMediaItemIndex, 0L)
            shadow.prepare()
            shadow.pause()
        }

        // 触发 crossfade
        if (timeRemaining in 1..crossfadeMs && main.hasNextMediaItem() &&
            shadow.playbackState == Player.STATE_READY && !isCrossfading) {
            isCrossfading = true
            shadow.volume = 0f
            shadow.play()
            startVolumeCrossfade(main, shadow, crossfadeMs)
        }
    }

    private fun startVolumeCrossfade(fadeOut: ExoPlayer, fadeIn: ExoPlayer, durationMs: Long) {
        val startTime = System.currentTimeMillis()
        val startVol = fadeOut.volume
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                val p = ((System.currentTimeMillis() - startTime).toFloat() / durationMs).coerceIn(0f, 1f)
                val smooth = p * p * (3 - 2 * p)
                fadeOut.volume = startVol * (1f - smooth)
                fadeIn.volume = targetVolume * smooth
                if (p < 1f) handler.postDelayed(this, 16)
                else {
                    fadeIn.volume = targetVolume
                    fadeOut.pause(); fadeOut.clearMediaItems()
                    fadeOut.volume = targetVolume
                    isCrossfading = false
                    isUsingPlayer2 = !isUsingPlayer2
                }
            }
        })
    }

    // ─── USB DAC Bit-Perfect (Android 14+) ───

    fun enableBitPerfect(sampleRate: Int = 0, bitDepth: Int = 0) {
        if (Build.VERSION.SDK_INT < 34) return
        if (isCurrentlyBitPerfect) return

        try {
            val dac = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: return

            val targetSr = resolveSampleRate(dac, sampleRate)
            val targetEnc = resolveEncoding(dac, bitDepth)

            val mixerAttrs = AudioMixerAttributes.Builder(
                android.media.AudioFormat.Builder()
                    .setSampleRate(targetSr)
                    .setEncoding(targetEnc)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            ).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build()

            val attr = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()

            val ok = audioManager.setPreferredMixerAttributes(attr, dac, mixerAttrs)
            if (ok) {
                isCurrentlyBitPerfect = true; _isBitPerfect.value = true
                mainPlayer.playbackParameters = PlaybackParameters.DEFAULT
                shadowPlayer?.playbackParameters = PlaybackParameters.DEFAULT
                setAudioOffload(false)
            }
        } catch (e: Exception) {
            Log.e("AudioEngine", "Bit-perfect failed: ${e.message}")
        }
    }

    fun disableBitPerfect() {
        if (Build.VERSION.SDK_INT < 34 || !isCurrentlyBitPerfect) return
        tryDisableUsbBitPerfect()
    }

    private fun tryDisableUsbBitPerfect() {
        if (Build.VERSION.SDK_INT < 34 || !isCurrentlyBitPerfect) return
        try {
            val dac = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            if (dac != null) {
                val attr = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
                audioManager.clearPreferredMixerAttributes(attr, dac)
            }
            isCurrentlyBitPerfect = false; _isBitPerfect.value = false
        } catch (_: Exception) {}
    }

    private fun resolveSampleRate(dac: AudioDeviceInfo, requested: Int): Int {
        val rates = dac.sampleRates
        if (rates.isEmpty()) return if (requested > 0) requested else 48000
        if (requested > 0 && rates.contains(requested)) return requested
        return rates.maxOrNull() ?: 48000
    }

    private fun resolveEncoding(dac: AudioDeviceInfo, bitDepth: Int): Int {
        val encs = dac.encodings
        val candidates = when {
            bitDepth >= 32 -> listOf(
                android.media.AudioFormat.ENCODING_PCM_32BIT,
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
                android.media.AudioFormat.ENCODING_PCM_16BIT)
            bitDepth >= 24 -> listOf(
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
                android.media.AudioFormat.ENCODING_PCM_16BIT)
            else -> listOf(
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                android.media.AudioFormat.ENCODING_PCM_FLOAT)
        }
        if (encs.isEmpty()) return candidates.first()
        return candidates.firstOrNull { encs.contains(it) }
            ?: android.media.AudioFormat.ENCODING_PCM_16BIT
    }

    private fun setAudioOffload(enable: Boolean) {
        val mode = if (enable)
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        else
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        mainPlayer.trackSelectionParameters = mainPlayer.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(mode)
                    .setIsGaplessSupportRequired(enable)
                    .build()
            ).build()
    }

    // ─── Equalizer ───

    private fun initEqualizer(sessionId: Int) {
        if (sessionId == 0) return
        try {
            equalizer?.release()
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
            bassBoost?.release()
            bassBoost = BassBoost(0, sessionId).apply { enabled = true }
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply { enabled = true }
            updateEqualizerInfo()
        } catch (e: Exception) {
            Log.e("AudioEngine", "EQ init failed: ${e.message}")
        }
    }

    fun applyEqualizer(enabled: Boolean, bandLevels: ShortArray) {
        val eq = equalizer ?: return
        eq.enabled = enabled
        if (enabled) {
            for (i in 0 until minOf(bandLevels.size, eq.numberOfBands.toInt())) {
                try { eq.setBandLevel(i.toShort(), bandLevels[i]) } catch (_: Exception) {}
            }
        }
        updateEqualizerInfo()
    }

    fun applyBassBoost(enabled: Boolean, strength: Short) {
        bassBoost?.enabled = enabled
        if (enabled) try { bassBoost?.setStrength(strength) } catch (_: Exception) {}
    }

    fun applyLoudness(enabled: Boolean, gainMb: Int) {
        loudnessEnhancer?.enabled = enabled
        if (enabled) try { loudnessEnhancer?.setTargetGain(gainMb) } catch (_: Exception) {}
    }

    private fun updateEqualizerInfo() {
        val eq = equalizer ?: return
        try {
            val numBands = eq.numberOfBands.toInt()
            val levels = ShortArray(numBands) { eq.getBandLevel(it.toShort()) }
            val range = eq.getBandLevelRange()
            _equalizerInfo.value = EqualizerInfo(numBands, levels, range[0].toInt(), range[1].toInt())
        } catch (_: Exception) {}
    }

    // ─── 状态更新 ───

    private fun updateState() {
        val p = mainPlayer
        _playbackState.value = _playbackState.value.copy(
            isPlaying = p.playWhenReady,
            isLoading = p.playbackState == Player.STATE_BUFFERING,
            positionMs = p.currentPosition,
            durationMs = if (p.duration > 0) p.duration else 0
        )
    }

    private fun updateNowPlaying() {
        val item = mainPlayer.currentMediaItem ?: return
        _playbackState.value = _playbackState.value.copy(
            currentTitle = item.mediaMetadata.title?.toString() ?: "",
            currentArtist = item.mediaMetadata.artist?.toString() ?: "",
            currentAlbum = item.mediaMetadata.albumTitle?.toString() ?: "",
            currentUri = item.mediaId ?: ""
        )
    }

    // ─── 音频分析 ───

    inner class VisualizerAudioSink(
        private val delegate: AudioSink
    ) : AudioSink by delegate {

        private var currentEncoding = C.ENCODING_INVALID
        private var currentChannels = 2
        private var currentSampleRate = 44100
        private var filterL = 0f; private var filterR = 0f
        private val alpha = 0.15f
        private val fftBuf = FloatArray(4096)
        private var fftPos = 0
        private val fftExec = Executors.newSingleThreadExecutor { Thread(it, "FFT").also { t -> t.isDaemon = true } }

        override fun configure(fmt: Format, bufSize: Int, outChannels: IntArray?) {
            currentEncoding = fmt.pcmEncoding
            currentChannels = fmt.channelCount
            currentSampleRate = if (fmt.sampleRate > 0) fmt.sampleRate else 44100
            delegate.configure(fmt, bufSize, outChannels)
        }

        override fun handleBuffer(buf: ByteBuffer, ptsUs: Long, count: Int): Boolean {
            val remaining = buf.remaining()
            if (remaining > 0 && currentChannels > 0) {
                val rb = buf.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
                var sumSq = 0f; var n = 0
                try {
                    when (currentEncoding) {
                        C.ENCODING_PCM_FLOAT -> {
                            val fb = rb.asFloatBuffer()
                            val frames = fb.remaining() / currentChannels
                            for (i in 0 until frames) {
                                var l = fb.get(); var r = if (currentChannels > 1) fb.get() else l
                                if (currentChannels > 2) fb.position(fb.position() + currentChannels - 2)
                                if (l.isNaN() || l.isInfinite()) l = 0f
                                if (r.isNaN() || r.isInfinite()) r = 0f
                                filterL += alpha * (l - filterL); filterR += alpha * (r - filterR)
                                val mono = (filterL + filterR) * 0.5f
                                sumSq += mono * mono; n++; accFft(mono)
                            }
                        }
                        C.ENCODING_PCM_16BIT -> {
                            val sb = rb.asShortBuffer()
                            val frames = sb.remaining() / currentChannels
                            for (i in 0 until frames) {
                                val l = sb.get() / 32768f
                                val r = if (currentChannels > 1) sb.get() / 32768f else l
                                if (currentChannels > 2) sb.position(sb.position() + currentChannels - 2)
                                filterL += alpha * (l - filterL); filterR += alpha * (r - filterR)
                                val mono = (filterL + filterR) * 0.5f
                                sumSq += mono * mono; n++; accFft(mono)
                            }
                        }
                        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT -> {
                            val is24 = currentEncoding == C.ENCODING_PCM_24BIT
                            val bps = if (is24) 3 else 4
                            val maxVal = if (is24) 8388608f else 2147483648f
                            val frames = rb.remaining() / (bps * currentChannels)
                            for (i in 0 until frames) {
                                var il = 0; var ir = 0
                                if (is24) {
                                    il = (rb.get().toInt() and 0xFF) or
                                         ((rb.get().toInt() and 0xFF) shl 8) or
                                         (rb.get().toInt() shl 16)
                                    ir = if (currentChannels > 1)
                                        (rb.get().toInt() and 0xFF) or
                                        ((rb.get().toInt() and 0xFF) shl 8) or
                                        (rb.get().toInt() shl 16)
                                    else il
                                } else {
                                    il = rb.int; ir = if (currentChannels > 1) rb.int else il
                                }
                                if (currentChannels > 2) rb.position(rb.position() + (currentChannels - 2) * bps)
                                val sl = il / maxVal; val sr = ir / maxVal
                                filterL += alpha * (sl - filterL); filterR += alpha * (sr - filterR)
                                val mono = (filterL + filterR) * 0.5f
                                sumSq += mono * mono; n++; accFft(mono)
                            }
                        }
                    }
                    if (n > 0) {
                        val rms = kotlin.math.sqrt(sumSq / n)
                        if (!rms.isNaN()) {
                            currentAmplitude = rms
                            visualizerAmplitude = rms
                        }
                    }
                } catch (_: Exception) {}
            }
            return delegate.handleBuffer(buf, ptsUs, count)
        }

        private fun accFft(sample: Float) {
            fftBuf[fftPos++] = sample
            if (fftPos >= 4096) {
                fftPos = 0
                val snap = fftBuf.copyOf(); val sr = currentSampleRate
                fftExec.submit {
                    try { computeFftBands(snap, sr) } catch (_: Exception) {}
                }
            }
        }

        private fun computeFftBands(pcm: FloatArray, sampleRate: Int) {
            val n = pcm.size
            // Hann window + bit-reversal + Cooley-Tukey FFT
            val re = FloatArray(n) { i -> pcm[i] * (0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * i / (n - 1)).toFloat()) }
            val im = FloatArray(n)
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
            }
            var len = 2
            while (len <= n) {
                val hl = len / 2; val ang = -2.0 * Math.PI / len
                val wRe = kotlin.math.cos(ang).toFloat(); val wIm = kotlin.math.sin(ang).toFloat()
                var k = 0
                while (k < n) {
                    var cRe = 1f; var cIm = 0f
                    for (l in 0 until hl) {
                        val uRe = re[k+l]; val uIm = im[k+l]
                        val vRe = re[k+l+hl] * cRe - im[k+l+hl] * cIm
                        val vIm = re[k+l+hl] * cIm + im[k+l+hl] * cRe
                        re[k+l] = uRe+vRe; im[k+l] = uIm+vIm
                        re[k+l+hl] = uRe-vRe; im[k+l+hl] = uIm-vIm
                        val nCRe = cRe*wRe - cIm*wIm; cIm = cRe*wIm + cIm*wRe; cRe = nCRe
                    }
                    k += len
                }
                len *= 2
            }
            val spectrum = FloatArray(n / 2) { kotlin.math.sqrt((re[it]*re[it] + im[it]*im[it]).toDouble()).toFloat() / (n / 2) }
            // 128 段对数频率
            val nyq = sampleRate / 2.0
            val maxB = spectrum.size
            for (b in 0 until 128) {
                val fLo = 20.0 * (nyq / 20.0).pow(b.toDouble() / 128)
                val fHi = 20.0 * (nyq / 20.0).pow((b + 1.0) / 128)
                val bLo = ((fLo / nyq) * maxB).toInt().coerceIn(0, maxB - 1)
                val bHi = ((fHi / nyq) * maxB).toInt().coerceIn(bLo + 1, maxB)
                val avg = spectrum.slice(bLo until bHi).average().toFloat()
                fftBands[b] = fftBands[b] * 0.6f + avg * 0.4f
                visualizerFftBands[b] = fftBands[b]
            }
        }

        private fun Double.pow(exp: Double) = Math.pow(this, exp)
    }
}

// ─── 数据类 ───

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

data class EqualizerInfo(
    val numBands: Int,
    val bandLevels: ShortArray,
    val rangeMin: Int,
    val rangeMax: Int
)
