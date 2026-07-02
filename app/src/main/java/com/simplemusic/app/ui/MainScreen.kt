package com.simplemusic.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplemusic.app.ui.components.MiniPlayer
import com.simplemusic.app.ui.import.ImportScreen
import com.simplemusic.app.ui.library.LibraryScreen
import com.simplemusic.app.ui.player.PlayerScreen
import com.simplemusic.app.ui.theme.*

sealed class Screen {
    data object Library : Screen()
    data object Player : Screen()
    data object Import : Screen()
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var currentScreen: Screen by remember { mutableStateOf(Screen.Library) }

    // 当没有歌曲时自动跳转到导入页
    LaunchedEffect(uiState.songCount, currentScreen) {
        if (uiState.songCount == 0 && currentScreen == Screen.Library) {
            currentScreen = Screen.Import
        }
    }

    Scaffold(
        bottomBar = {
            // 歌曲信息有内容时才显示 MiniPlayer
            if (uiState.currentTitle.isNotEmpty() && currentScreen != Screen.Player) {
                MiniPlayer(
                    title = uiState.currentTitle,
                    artist = uiState.currentArtist,
                    isPlaying = uiState.isPlaying,
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    onPlayPause = { viewModel.togglePlayPause() },
                    onSkipNext = { viewModel.skipToNext() },
                    onSkipPrevious = { viewModel.skipToPrevious() },
                    onClick = { currentScreen = Screen.Player }
                )
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentScreen) {
                is Screen.Library -> {
                    LibraryScreen(
                        songs = uiState.songs,
                        currentSongId = uiState.currentSongId,
                        onSongClick = { song ->
                            viewModel.playSong(song)
                            currentScreen = Screen.Player
                        },
                        onSongDelete = { id -> viewModel.deleteSong(id) },
                        onRefresh = { viewModel.scanMediaStore() },
                        onImportClick = { currentScreen = Screen.Import }
                    )
                }

                is Screen.Player -> {
                    PlayerScreen(
                        title = uiState.currentTitle,
                        artist = uiState.currentArtist,
                        album = uiState.currentAlbum,
                        isPlaying = uiState.isPlaying,
                        positionMs = uiState.positionMs,
                        durationMs = uiState.durationMs,
                        audioInfo = uiState.audioInfo,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onSeek = { pos -> viewModel.seekTo(pos) },
                        onSkipNext = { viewModel.skipToNext() },
                        onSkipPrevious = { viewModel.skipToPrevious() },
                        onBack = { currentScreen = Screen.Library }
                    )
                }

                is Screen.Import -> {
                    ImportScreen(
                        songCount = uiState.songCount,
                        isScanning = uiState.isScanning,
                        scanProgress = uiState.scanProgress,
                        onScanMediaStore = { viewModel.scanMediaStore() },
                        onImportFromDirectory = { uri -> viewModel.importFromDirectory(uri) },
                        onImportSingleFile = { uris -> viewModel.importFiles(uris) },
                        onBack = { currentScreen = Screen.Library }
                    )
                }
            }
        }
    }
}
