package com.puki.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puki.music.data.LocalMusicStore
import com.puki.music.data.Playlist
import com.puki.music.model.Track
import com.puki.music.ui.theme.NuclearPalette
import com.puki.music.ui.theme.NuclearThemeId
import com.puki.music.ui.theme.getNuclearPalette

@Composable
fun AddToPlaylistDialog(
    track: Track,
    store: LocalMusicStore,
    palette: NuclearPalette,
    onDismiss: () -> Unit,
    onAdded: (Playlist, Track) -> Unit
) {
    val playlists = remember { store.getPlaylists() }
    var newPlaylistName by remember { mutableStateOf("") }
    var newAutoDownload by remember { mutableStateOf(false) }
    var isCreatingNew by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceVariant,
        title = {
            Column {
                Text(
                    "TAMBAH KE PLAYLIST",
                    color = palette.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    track.title,
                    color = palette.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!isCreatingNew) {
                    if (playlists.isEmpty()) {
                        Text(
                            "Belum ada playlist. Buat playlist baru di bawah.",
                            color = palette.textSecondary,
                            fontSize = 12.sp
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(playlists) { pl ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            store.addTrackToPlaylist(pl.id, track)
                                            onAdded(pl, track)
                                        },
                                    colors = CardDefaults.cardColors(containerColor = palette.surface),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                pl.name,
                                                color = palette.textPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (pl.autoDownload) {
                                                Text(
                                                    "AUTO-DOWNLOAD AKTIF",
                                                    color = palette.primary,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                        Text(
                                            "${pl.trackCount} lagu",
                                            color = palette.textSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = { isCreatingNew = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = palette.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "+ Buat Playlist Baru",
                            color = palette.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        placeholder = { Text("Nama playlist...", color = palette.textSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.primary,
                            unfocusedBorderColor = palette.border,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Download", color = palette.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("Unduh otomatis lagu saat ditambahkan", color = palette.textSecondary, fontSize = 10.sp)
                        }
                        Switch(
                            checked = newAutoDownload,
                            onCheckedChange = { newAutoDownload = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = palette.background,
                                checkedTrackColor = palette.primary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { isCreatingNew = false }) {
                            Text("Batal", color = palette.textSecondary)
                        }
                        Button(
                            onClick = {
                                if (newPlaylistName.isNotBlank()) {
                                    val pl = store.createPlaylist(newPlaylistName.trim(), autoDownload = newAutoDownload)
                                    store.addTrackToPlaylist(pl.id, track)
                                    onAdded(pl, track)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary)
                        ) {
                            Text("Simpan", color = palette.background)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (!isCreatingNew) {
                TextButton(onClick = onDismiss) {
                    Text("Tutup", color = palette.textSecondary)
                }
            }
        }
    )
}

@Composable
fun NewPlaylistDialog(
    palette: NuclearPalette,
    onDismiss: () -> Unit,
    onCreate: (String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var autoDownload by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceVariant,
        title = {
            Text(
                "BUAT PLAYLIST BARU",
                color = palette.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Nama playlist...", color = palette.textSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.primary,
                        unfocusedBorderColor = palette.border,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Download", color = palette.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text("Otomatis simpan offline lagu di playlist", color = palette.textSecondary, fontSize = 10.sp)
                    }
                    Switch(
                        checked = autoDownload,
                        onCheckedChange = { autoDownload = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = palette.background,
                            checkedTrackColor = palette.primary
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) onCreate(name.trim(), autoDownload)
                },
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary)
            ) {
                Text("BUAT", color = palette.background, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = palette.textSecondary)
            }
        }
    )
}

@Composable
fun ThemeChooserDialog(
    currentThemeId: NuclearThemeId,
    palette: NuclearPalette,
    onDismiss: () -> Unit,
    onSelectTheme: (NuclearThemeId) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceVariant,
        title = {
            Text(
                "PILIH TEMA NUCLEAR",
                color = palette.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NuclearThemeId.values().forEach { theme ->
                    val themePalette = getNuclearPalette(theme)
                    val isSelected = currentThemeId == theme

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTheme(theme) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = themePalette.background),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) themePalette.primary else palette.border
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(themePalette.primary)
                                )
                                Text(
                                    text = theme.displayName,
                                    color = themePalette.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = themePalette.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup", color = palette.textSecondary)
            }
        }
    )
}
