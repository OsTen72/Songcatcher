package com.mamaika.songcatcher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.catchSong() else vm.status = "Нужен доступ к микрофону"
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(16.dp)
            ) {
                Text("SongCatcher", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))

                vm.report?.let { r ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                r,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                            Row {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(r)) }) {
                                    Text("Скопировать")
                                }
                                TextButton(onClick = { vm.dismissReport() }) { Text("Закрыть") }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = vm.token,
                    onValueChange = vm::updateToken,
                    label = { Text("AudD API token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            ctx, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) vm.catchSong()
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    enabled = !vm.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                ) {
                    Text(if (vm.busy) "Работаю…" else "Распознать и скачать")
                }

                Spacer(Modifier.height(8.dp))
                Text(vm.status, style = MaterialTheme.typography.bodyMedium)

                if (vm.busy) {
                    Spacer(Modifier.height(8.dp))
                    val p = vm.progress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.tracks, key = { it.id }) { track ->
                        val current = track.id == vm.currentId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.play(track) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { vm.delete(track) }) { Text("✕") }
                        }
                    }
                }

                vm.currentTrack?.let { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${t.artist} — ${t.title}",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Button(onClick = { vm.togglePlay() }) {
                            Text(if (vm.isPlaying) "Пауза" else "Играть")
                        }
                    }
                }
            }
        }
    }
}
