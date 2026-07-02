package com.simplemusic.app.ui.import

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.simplemusic.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    songCount: Int,
    isScanning: Boolean,
    scanProgress: String,
    onScanMediaStore: () -> Unit,
    onImportFromDirectory: (Uri) -> Unit,
    onImportSingleFile: (List<Uri>) -> Unit,
    onBack: () -> Unit
) {
    val dirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // 获取持久化权限
            onImportFromDirectory(it)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportSingleFile(uris)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("导入音乐", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = DarkBackground,
                titleContentColor = TextPrimary
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                // 当前库统计
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "当前音乐库",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = "$songCount 首歌曲",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            item {
                // 扫描系统媒体库
                ImportCard(
                    icon = Icons.Default.PhoneAndroid,
                    title = "扫描手机媒体库",
                    description = "自动扫描手机中所有音乐文件（使用 Android MediaStore），涵盖常见音乐目录",
                    buttonText = if (isScanning) "扫描中..." else "开始扫描",
                    enabled = !isScanning,
                    onClick = onScanMediaStore
                )
            }

            item {
                // 从文件夹导入
                ImportCard(
                    icon = Icons.Default.Folder,
                    title = "从文件夹导入",
                    description = "选择一个文件夹，导入其中的所有音乐文件。支持嵌套目录扫描",
                    buttonText = "选择文件夹",
                    enabled = !isScanning,
                    onClick = { dirPicker.launch(null) }
                )
            }

            item {
                // 选择文件导入
                ImportCard(
                    icon = Icons.Default.AudioFile,
                    title = "选择文件导入",
                    description = "手动选择音乐文件导入。支持 MP3、FLAC、WAV、AAC、OGG、APE、M4A、AIFF、ALAC、DSF 等格式",
                    buttonText = "选择文件",
                    enabled = !isScanning,
                    onClick = {
                        filePicker.launch(
                            arrayOf(
                                "audio/*",
                                "application/octet-stream"
                            )
                        )
                    }
                )
            }

            item {
                Divider(color = DarkCard, modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                // 支持的格式
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "支持的音乐格式",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = """
                                • MP3 - 有损压缩，兼容性最好
                                • FLAC - 无损压缩，推荐音质
                                • WAV - 无损未压缩
                                • AAC - 高级音频编码
                                • OGG / Opus - 开放格式
                                • APE - Monkey's Audio 无损
                                • M4A - Apple 无损/有损
                                • AIFF / ALAC - Apple 无损格式
                                • WMA - Windows Media Audio
                                • DSF / DFF - DSD 音频
                            """.trimIndent(),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImportCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onClick,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = DarkBackground
                )
            ) {
                Text(buttonText, fontWeight = FontWeight.Bold)
            }
        }
    }
}
