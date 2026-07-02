package com.simplemusic.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.simplemusic.app.data.db.SongEntity
import com.simplemusic.app.ui.components.MusicListItem
import com.simplemusic.app.ui.theme.*

enum class LibraryTab { SONGS, ARTISTS, ALBUMS, FOLDERS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    songs: List<SongEntity>,
    currentSongId: Long = -1,
    onSongClick: (SongEntity) -> Unit,
    onSongDelete: (Long) -> Unit,
    onRefresh: () -> Unit,
    onImportClick: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(LibraryTab.SONGS) }
    var showSearch by remember { mutableStateOf(false) }

    val filteredSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) songs
        else songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        TopAppBar(
            title = {
                if (showSearch) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索歌曲、艺术家、专辑...", color = TextTertiary) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Primary,
                            focusedIndicatorColor = Primary
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("音乐库", fontWeight = FontWeight.Bold)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = DarkBackground,
                titleContentColor = TextPrimary
            ),
            actions = {
                IconButton(onClick = {
                    showSearch = !showSearch
                    if (!showSearch) searchQuery = ""
                }) {
                    Icon(
                        imageVector = if (showSearch) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = TextPrimary
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "刷新",
                        tint = TextPrimary
                    )
                }
                IconButton(onClick = onImportClick) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "导入",
                        tint = Primary
                    )
                }
            }
        )

        // 标签
        ScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = DarkBackground,
            contentColor = Primary,
            edgePadding = 16.dp
        ) {
            listOf("歌曲", "艺术家", "专辑", "文件夹").forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab.ordinal == index,
                    onClick = { selectedTab = LibraryTab.entries[index] },
                    text = {
                        Text(
                            title,
                            color = if (selectedTab.ordinal == index) Primary else TextSecondary,
                            fontWeight = if (selectedTab.ordinal == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // 内容
        if (filteredSongs.isEmpty()) {
            EmptyState(
                icon = Icons.Default.LibraryMusic,
                message = if (songs.isEmpty()) "还没有音乐\n点击右上角 + 导入" else "没有找到匹配的歌曲"
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredSongs, key = { it.id }) { song ->
                    MusicListItem(
                        song = song,
                        isPlaying = song.id == currentSongId,
                        onClick = { onSongClick(song) },
                        onDelete = { onSongDelete(song.id) }
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
        }
    }
}
