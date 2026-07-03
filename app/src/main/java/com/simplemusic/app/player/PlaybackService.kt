package com.simplemusic.app.player

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.BitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.simplemusic.app.MainActivity
import com.simplemusic.app.data.db.AppDatabase
import com.simplemusic.app.data.db.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    lateinit var engine: AudioEngine

    override fun onCreate() {
        super.onCreate()
        engine = AudioEngine(this)

        // 通知栏点击跳转
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_PLAYER"
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaLibrarySession.Builder(this, engine.mainPlayer, SessionCallback())
            .setSessionActivity(pi)
            .setBitmapLoader(LocalBitmapLoader())
            .build()

        // 精简通知栏按钮（去掉快进快退）
        val provider = object : DefaultMediaNotificationProvider(this) {
            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                customLayout: ImmutableList<CommandButton>,
                showPauseButton: Boolean
            ): ImmutableList<CommandButton> {
                return ImmutableList.copyOf(
                    super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
                        .filter {
                            it.playerCommand != Player.COMMAND_SEEK_FORWARD &&
                            it.playerCommand != Player.COMMAND_SEEK_BACK
                        }
                )
            }
        }
        provider.setSmallIcon(android.R.drawable.ic_media_play)
        setMediaNotificationProvider(provider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        engine.release()
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    // ─── 浏览树 ───

    private inner class SessionCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(
                LibraryResult.ofItem(
                    MediaItem.Builder()
                        .setMediaId("root")
                        .setMediaMetadata(MediaMetadata.Builder()
                            .setTitle("简音 · 音乐库")
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .build())
                        .build(),
                    params
                )
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int, pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                try {
                    val dao = AppDatabase.getInstance(this@PlaybackService).songDao()
                    val songs = dao.getAllSongsList()
                    val items = songs.map { s ->
                        MediaItem.Builder()
                            .setMediaId(s.uri)
                            .setUri(s.uri)
                            .setMediaMetadata(MediaMetadata.Builder()
                                .setTitle(s.title)
                                .setArtist(s.artist)
                                .setAlbumTitle(s.album)
                                .setIsPlayable(true)
                                .build())
                            .build()
                    }
                    LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                } catch (e: Exception) {
                    LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE)
                }
            }
        }
    }

    @UnstableApi
    private inner class LocalBitmapLoader : BitmapLoader {
        override fun supportsMimeType(mimeType: String) = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            Futures.immediateFuture(BitmapFactory.decodeByteArray(data, 0, data.size))

        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
            return when {
                metadata.artworkData != null -> decodeBitmap(metadata.artworkData!!)
                metadata.artworkUri != null -> loadBitmap(metadata.artworkUri!!)
                else -> null
            }
        }

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            val future = com.google.common.util.concurrent.SettableFuture.create<Bitmap>()
            serviceScope.launch {
                var bmp: Bitmap? = null
                try {
                    bmp = when {
                        uri.scheme == "content" -> {
                            contentResolver.openInputStream(uri)?.use {
                                BitmapFactory.decodeStream(it)
                            }
                        }
                        else -> BitmapFactory.decodeFile(uri.path)
                    }
                } catch (_: Exception) {}
                if (bmp == null) {
                    bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }
                future.set(bmp)
            }
            return future
        }
    }
}
