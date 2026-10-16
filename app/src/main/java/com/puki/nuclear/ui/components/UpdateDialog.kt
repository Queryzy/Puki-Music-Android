package com.puki.nuclear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.puki.nuclear.core.ReleaseInfo
import com.puki.nuclear.ui.theme.NuclearPalette

@Composable
fun UpdateDialog(
    palette: NuclearPalette,
    releaseInfo: ReleaseInfo,
    isDownloading: Boolean,
    downloadProgress: Int,
    onDismiss: () -> Unit,
    onStartUpdate: () -> Unit
) {
    Dialog(onDismissRequest = {
        if (!isDownloading) onDismiss()
    }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = palette.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Pembaruan Tersedia",
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = releaseInfo.tagName,
                            color = palette.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.border.copy(alpha = 0.5f))
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "CATATAN RILIS:",
                    color = palette.textSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = releaseInfo.changelog.ifBlank { "Pembaruan stabilitas dan peningkatan fitur Puki Music." },
                        color = palette.textPrimary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }

                if (releaseInfo.apkSize > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val sizeMb = String.format("%.1f MB", releaseInfo.apkSize / (1024.0 * 1024.0))
                    Text(
                        text = "Ukuran berkas: $sizeMb",
                        color = palette.textSecondary,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (isDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { (downloadProgress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = palette.primary,
                            trackColor = palette.surface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (downloadProgress < 100) "Mengunduh APK pembaruan... $downloadProgress%" else "Menyiapkan penginstalan...",
                            color = palette.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("NANTI", color = palette.textSecondary, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onStartUpdate,
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "UPDATE SEKARANG",
                                color = palette.background,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
